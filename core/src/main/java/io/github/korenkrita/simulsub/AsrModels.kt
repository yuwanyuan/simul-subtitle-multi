package io.github.korenkrita.simulsub

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

/**
 * One model the 语音识别模型 page offers. [files] are stored under [AsrModels.dir]'s per-model directory under their
 * remote names; the first four make up a transducer in the order encoder / decoder / joiner / tokens.
 */
class AsrModelDef(
    val id: String,
    val label: String,
    /** one line for the list row: languages, size and where it comes from */
    val summary: String,
    /** true = streaming (chunked) zipformer → the platform's streaming engine; false = offline → its file transducer
     *  (both built through [AsrModels.engineFactory], which is where the phone's AAR and the desktop's library differ) */
    val streaming: Boolean,
    val files: List<FileDef>,
    /** the model's own name where it lives (owner/repository on Hugging Face / ModelScope), shown on the download
     *  rows: the [label] is ours and friendly, this is what the model is called upstream and what lands on disk */
    val origin: String = "",
    /** the manifest's 「首选」 mark (the row's label carries it): a note for the user — the list order, which is the
     *  manifest's order, is what still decides [AsrModels.firstReady]'s fallback */
    val preferred: Boolean = false,
) {
    class FileDef(val local: String, val urls: List<String>, val bytes: Long, val sha256: String?)

    val sizeBytes: Long get() = files.sumOf { it.bytes }
}

/**
 * The engine behind a model's four files (encoder / decoder / joiner / tokens) plus the native release the session
 * must call when it ends: the core's [Asr] has no release of its own, and the construction itself stays the
 * platform's business ([AsrModels.engineFactory]) — this module knows neither the AAR nor the desktop's library.
 */
interface ReleasableAsr : Asr {
    fun release()
}

/**
 * The downloadable / importable speech models and which one the captions run on. Selected in 设置 › 字幕 ›
 * 语音识别模型; a change takes effect the next time captions start ([SessionHost.loadModels] reads it there).
 * What is picked and a custom model's meta are stored through [AsrPrefs] (on the phone the settings' own file, its
 * own keys); a model's files live under [dir], one directory per model id, where nothing else of the app writes.
 *
 * The platform seams are installed once at start-up ([install], [engineFactory], [builtinDir]); everything below is
 * plain JVM code, so the phone's shell and the desktop's share this object, its page logic and its messages.
 */
object AsrModels {
    /** the model the app was born with — on the phone a download like the rest, where a build packs it a [shipped] one */
    const val BUILTIN = "builtin"
    /** a model the user imported from the machine (one; the next import replaces it) */
    const val CUSTOM = "custom"
    /** the [CustomModel.kind] values */
    const val KIND_STREAMING = "streaming"
    const val KIND_OFFLINE = "transducer"

    /** What the platform keeps the selection and the custom model's meta in: on the phone the SharedPreferences
     *  ("jpsub") the settings live in too, on the desktop its settings.json. null = none imported. */
    interface AsrPrefs {
        /** the selected model id ([BUILTIN], [CUSTOM] or a [CATALOG] id); anything unknown reads as built-in */
        var selected: String
        /** the custom model's meta ([CustomModel.json]) */
        var custom: String?
    }

    /**
     * Hands the object everything a platform must supply, once, before the model page is built. [userAgent] is the
     * app's version: downloads announce themselves as `SimulSub/<userAgent>`, the shape the model hubs have seen.
     */
    fun install(root: File, prefs: AsrPrefs, post: (() -> Unit) -> Unit, log: (String, Throwable?) -> Unit, userAgent: String) {
        this.root = root.apply { mkdirs() }
        this.prefs = prefs
        this.post = post
        this.log = log
        this.userAgent = userAgent
    }

    private var prefs: AsrPrefs? = null

    private fun store(): AsrPrefs = prefs ?: error("AsrModels.install 还没调用")

    /** How a platform runs [r] on its UI thread (the phone: the main Looper); the default runs it inline. */
    var post: ((() -> Unit) -> Unit) = { it() }

    /** Where a platform writes its log (the phone: Log.i / Log.w under the "jpsub" tag); the default drops it. */
    var log: (String, Throwable?) -> Unit = { _, _ -> }

    /** the version in the downloads' `User-Agent: SimulSub/<this>` (the phone: BuildConfig.VERSION_NAME) */
    var userAgent: String = "SimulSub"

    /** every model gets its own directory under this (the phone: app-specific external storage, gone on uninstall) */
    lateinit var root: File

    /**
     * non-null = [BUILTIN]'s files ship with the application (the desktop: the packed `models` directory, the four
     * files under its `asr/`): [shipped] answers true, [ready] never looks at [dir], [delete] cannot take it away and
     * [createAsr] reads the packed paths instead of a download.
     */
    var builtinDir: File? = null

    /**
     * Builds the engine behind a model's files: the four paths in encoder / decoder / joiner / tokens order, whether
     * the model streams, and the thread count. The phone hands in the AAR's recognizers, the desktop its own; unset
     * (a shell that never got that far) makes [createAsr] throw with a message the overlay can show.
     */
    var engineFactory: ((files: List<String>, streaming: Boolean, threads: Int) -> ReleasableAsr)? = null

    // -- the models the page offers. Sizes and SHA-256 taken from the repos' own metadata (ModelScope's file API for
    //    the first, setup.sh's checked list for the second) when written; a download is verified against them, or by
    //    size where no hash is published. All URLs are pinned to a revision, so an upstream re-upload cannot silent-
    //    ly break new downloads (the checks would just fail) without this reading as a broken app.
    private const val MS = "https://www.modelscope.cn/models/admin2558/lingualive-streaming-zipformer-multilingual-int8/resolve/c1faa2cad48d7a501047d84d87bfc0a2b66612e6/"
    /** huggingface.co serves the same files; the mirror first, plain Hugging Face does not answer from this region */
    private const val REAZON = "https://hf-mirror.com/csukuangfj/reazonspeech-k2-v2-ja-en/resolve/12b44671ff48b9aed622551d64e02fea9a2cf870/"
    private val KROKO = listOf(
        "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06/resolve/887db3d083240198c2d2b99fb66cfcfe6948ced8/",
        "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06/resolve/887db3d083240198c2d2b99fb66cfcfe6948ced8/",
    )

    internal val BUILTIN_CATALOG = listOf(
        AsrModelDef(
            BUILTIN, "日英模型（ReazonSpeech）",
            "日、英 · 约 77 MB · HuggingFace（镜像下载）· 非流式 zipformer · 原本装在 App 里的那个",
            streaming = false,
            files = listOf(
                AsrModelDef.FileDef("encoder-epoch-35-avg-1.int8.onnx", listOf(REAZON + "encoder-epoch-35-avg-1.int8.onnx"),
                    70_876_409, "ead1579e118b821a767242a8eb9272634b0e63ba16f8dfc4d126732406eae268"),
                AsrModelDef.FileDef("decoder-epoch-35-avg-1.onnx", listOf(REAZON + "decoder-epoch-35-avg-1.onnx"),
                    5_165_084, "90b60985672df5023e187e9d25aa2dc93abc4286bfacf71a35a78a6b76d88df5"),
                AsrModelDef.FileDef("joiner-epoch-35-avg-1.int8.onnx", listOf(REAZON + "joiner-epoch-35-avg-1.int8.onnx"),
                    1_033_417, "c7f4ba40a8ae307a6c30b5c06e2570add04466bcb45bab62699f0ec5d00ed495"),
                AsrModelDef.FileDef("tokens.txt", listOf(REAZON + "tokens.txt"),
                    26_631, "144f8a4f639373a1bdf7eabb2437482ef64b0cc5db24ad27cce65f293e4faa24"),
            ),
            origin = "csukuangfj/reazonspeech-k2-v2-ja-en",
            preferred = true, // the model the app was born with: the same mark the hosted manifest carries
        ),
        AsrModelDef(
            "ms-lingualive-multilingual", "多语种流式模型（LinguaLive）",
            "中、英、日、俄、泰、越、印尼、阿拉伯语 · 约 339 MB · 魔搭 ModelScope · 流式 zipformer",
            streaming = true,
            files = listOf(
                AsrModelDef.FileDef("encoder-epoch-75-avg-11-chunk-16-left-128.int8.onnx",
                    listOf(MS + "encoder-epoch-75-avg-11-chunk-16-left-128.int8.onnx"),
                    296_583_597, "f9001ed7a9e46d0294438c1a30cd7c72d1cc4bdd4e7880edbcda36f67081e32e"),
                AsrModelDef.FileDef("decoder-epoch-75-avg-11-chunk-16-left-128.onnx",
                    listOf(MS + "decoder-epoch-75-avg-11-chunk-16-left-128.onnx"),
                    33_837_085, "7ebc63f34b21c8efb4a41a5a2eee7fe1448829ce0230ecc5369e67fc14d90d48"),
                AsrModelDef.FileDef("joiner-epoch-75-avg-11-chunk-16-left-128.int8.onnx",
                    listOf(MS + "joiner-epoch-75-avg-11-chunk-16-left-128.int8.onnx"),
                    8_257_421, "db88e3172323551abaa99b91b18fb422a27ea4a834fd0db10389f9478816f917"),
                AsrModelDef.FileDef("tokens.txt", listOf(MS + "tokens.txt"),
                    195_244, "784f24950f6bcce1b0021035632dd60fd4617ecd8ca0581ab57d7b39d77ba5ab"),
            ),
            origin = "admin2558/lingualive-streaming-zipformer-multilingual-int8",
        ),
        AsrModelDef(
            "hf-kroko-de", "德语流式模型（Kroko）",
            "德语 · 约 71 MB · HuggingFace（镜像下载）· 流式 zipformer",
            streaming = true,
            files = listOf(
                AsrModelDef.FileDef("encoder.onnx", KROKO.map { it + "encoder.onnx" }, 70_091_557, null),
                AsrModelDef.FileDef("decoder.onnx", KROKO.map { it + "decoder.onnx" }, 617_489, null),
                AsrModelDef.FileDef("joiner.onnx", KROKO.map { it + "joiner.onnx" }, 336_817, null),
                AsrModelDef.FileDef("tokens.txt", KROKO.map { it + "tokens.txt" }, 5_606, null),
            ),
            origin = "csukuangfj/sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06",
        ),
    )

    /**
     * The models the page offers: [AsrCatalog]'s fetched list once there is one — the file is the truth and its
     * order is the page's — else [BUILTIN_CATALOG]. Ids, file names and byte counts are what [ready] compares, so a
     * fetched entry that keeps them keeps the files already on the machine (an edited URL never re-downloads).
     */
    val CATALOG: List<AsrModelDef> get() = AsrCatalog.withRemotes(BUILTIN_CATALOG)

    fun def(id: String): AsrModelDef? = CATALOG.firstOrNull { it.id == id }

    // -- selection & the custom model -----------------------------------------------------------------------------------

    /** the model the captions run on: [BUILTIN], [CUSTOM] or a [CATALOG] id; anything unknown reads as built-in */
    fun selected(): String = store().selected

    fun select(id: String) { store().selected = id }

    /** An imported model: its detected files (role → file name) and which engine runs it. */
    class CustomModel(val kind: String, val files: Map<String, String>) {
        /** [files] keeps the roles encoder/decoder/joiner/tokens in build order (LinkedHashMap on import) */
        fun json(): String = JSONObject().apply {
            put("kind", kind)
            put("files", JSONObject(files))
        }.toString()

        companion object {
            fun parse(s: String?): CustomModel? {
                if (s.isNullOrEmpty()) return null
                return runCatching {
                    val o = JSONObject(s)
                    val files = LinkedHashMap<String, String>()
                    o.getJSONObject("files").let { f -> f.keys().forEach { k -> files[k] = f.getString(k) } }
                    CustomModel(o.optString("kind", KIND_STREAMING), files)
                }.getOrNull()
            }
        }
    }

    fun custom(): CustomModel? = CustomModel.parse(store().custom)

    fun saveCustom(model: CustomModel?) { store().custom = model?.json() }

    /** The import guesses streaming (the published zipformers are); a non-streaming transducer is a choice here. */
    fun setCustomKind(kind: String) {
        val c = custom() ?: return
        saveCustom(CustomModel(kind, c.files))
    }

    // -- where the files live & whether they are there -------------------------------------------------------------------

    /** one directory per model id under [root]; [BUILTIN]'s is unused where [builtinDir] ships it */
    fun dir(id: String): File = File(root, id)

    /** A model is usable when every file of its definition is there with the expected size (the SHA-256 was checked
     *  when it arrived; re-hashing hundreds of MB is too slow for a page render). A [shipped] one always is: its
     *  files are the installation's, not a download's. */
    fun ready(def: AsrModelDef): Boolean = shipped(def) || def.files.all {
        val f = File(dir(def.id), it.local)
        f.isFile && f.length() == it.bytes
    }

    /** true = the model's four files ship with the application: nothing to download, and [delete] cannot take it
     *  away ([builtinDir] non-null, i.e. the desktop's packed models directory) */
    fun shipped(def: AsrModelDef): Boolean = builtinDir != null && def.id == BUILTIN

    fun customReady(): Boolean {
        val c = custom() ?: return false
        val dir = dir(CUSTOM)
        return c.files.values.all { name -> File(dir, name).isFile && File(dir, name).length() > 0 }
    }

    /** true = the stored selection points at a usable set of files (where nothing is shipped, the built-in id is a
     *  download like the rest) */
    fun selectionUsable(): Boolean = when (val id = selected()) {
        CUSTOM -> customReady()
        else -> def(id)?.let { ready(it) } == true
    }

    /** the model in use, as the 字幕 page's entry row names it */
    fun labelOf(): String = when (val id = selected()) {
        CUSTOM -> "自定义模型"
        else -> def(id)?.label ?: CATALOG[0].label
    }

    /** The ready model the captions would actually run on ([createAsr]'s pick): the selection, else the first ready
     *  in [CATALOG]'s order (the manifest's) — a shipped built-in makes this the built-in's own fallback. */
    fun firstReady(): AsrModelDef? = CATALOG.firstOrNull { ready(it) }

    /** true = some model is downloaded (or ships), so captions can start (what the home screen asks) */
    fun anyReady(): Boolean = selectionUsable() || CATALOG.any { ready(it) }

    /** Delete a model's directory (+ the custom meta for [CUSTOM]); a selected one falls back to built-in. A
     *  [shipped] model owns no download directory, so nothing is deleted for it: its files stay where they came from. */
    fun delete(id: String) {
        if (def(id)?.let { shipped(it) } != true) dir(id).deleteRecursively()
        if (id == CUSTOM) saveCustom(null)
        if (selected() == id) select(BUILTIN)
    }

    // -- the engine for the selected model -------------------------------------------------------------------------------

    /** where a [shipped] [BUILTIN]'s four files sit inside [builtinDir], in [ROLES] order (the desktop layout) */
    private val SHIPPED = listOf("asr/encoder.int8.onnx", "asr/decoder.onnx", "asr/joiner.int8.onnx", "asr/tokens.txt")

    /** where [d]'s files are read from: the packed directory for a [shipped] model, else its download directory */
    private fun fileDir(d: AsrModelDef): File = if (shipped(d)) builtinDir!! else dir(d.id)

    /**
     * Builds the ASR of the selected model for a session start ([SessionHost.loadModels]). The selection is used
     * when its files are there, else the first downloaded (or shipped) model; with none of them on the machine it
     * throws with a message for the overlay (the session reports `出错：…`) — nothing in the app itself can fall back.
     */
    fun createAsr(threads: Int): ReleasableAsr {
        val id = selected()
        val d = when {
            id == CUSTOM && customReady() -> {
                val c = custom() ?: throw IllegalStateException("自定义模型的文件找不到了，回「设置 › 字幕 › 语音识别模型」重新导入，或选回内置模型。")
                val dir = dir(CUSTOM)
                fun p(role: String): String {
                    val name = c.files[role] ?: throw IllegalStateException(
                        "自定义模型缺「${roleName(role)}」，回「设置 › 字幕 › 语音识别模型」重新导入。")
                    val f = File(dir, name)
                    if (!f.isFile || f.length() == 0L) throw IllegalStateException(
                        "自定义模型的 ${name} 文件不在了，回「设置 › 字幕 › 语音识别模型」重新导入。")
                    return f.absolutePath
                }
                return engine(listOf(p("encoder"), p("decoder"), p("joiner"), p("tokens")),
                    c.kind != KIND_OFFLINE, threads)
            }
            def(id)?.let { ready(it) } == true -> def(id)!!
            // the selection's files are gone (or point at an older build's id): run on whatever is available
            else -> firstReady() ?: throw IllegalStateException(
                "还没有语音识别模型可用：到「设置 › 字幕 › 语音识别模型」下载一个（日英模型 77 MB，多语种 339 MB，德语 71 MB），下次开始字幕时就能用。")
        }
        val modelDir = fileDir(d)
        val files = if (shipped(d)) SHIPPED.map { File(modelDir, it).absolutePath }
            else d.files.map { File(modelDir, it.local).absolutePath }
        // what this session will listen with (the trouble-shooting line behind "用了哪个模型" reports)
        log("ASR 模型：${d.label}（${if (d.streaming) "流式" else "非流式"}）· 目录 $modelDir", null)
        return engine(files, d.streaming, threads)
    }

    /** the platform's engine for four file paths; an unset [engineFactory] is a shell that never got that far, and
     *  its message goes onto the overlay like every other one here */
    private fun engine(files: List<String>, streaming: Boolean, threads: Int): ReleasableAsr {
        val factory = engineFactory ?: throw IllegalStateException(
            "语音识别引擎没有接上（engineFactory 未设置），请重启应用再试。")
        return factory(files, streaming, threads)
    }

    fun roleName(role: String) = when (role) {
        "encoder" -> "encoder（编码器）"
        "decoder" -> "decoder（解码器）"
        "joiner" -> "joiner"
        else -> "tokens.txt"
    }

    // -- downloading -----------------------------------------------------------------------------------------------------

    /** one download in progress; the model page reads it each tick, [AsrModels.downloading] says if it still runs */
    class Download internal constructor(val def: AsrModelDef) {
        @Volatile var stopped = false
        /** index into [AsrModelDef.files] of the file being fetched now */
        @Volatile var fileIdx = 0
        /** bytes that arrived so far (across the finished files too) and the current file's size as reported */
        @Volatile var doneBytes = 0L
        @Volatile var fileBytes = 0L
        val totalBytes: Long get() = def.sizeBytes
        /** failure text; null while it runs, and (with [stopped]) after the user cancels */
        @Volatile var error: String? = null
        val percent: Int
            get() = if (totalBytes <= 0) 0 else (doneBytes * 100 / totalBytes).toInt().coerceIn(0, 100)
    }

    /** the download in progress, if any (one at a time) */
    @Volatile var downloading: Download? = null; private set

    /** An import in progress (its copy and smoke build): the page shows it, and it keeps downloads out too. */
    @Volatile var importing: Boolean = false; private set

    /**
     * Starts downloading [def] (no-op while another download runs or the model is already there). Checks the free
     * space first. When it succeeds the model is put in use ([select]) — the row says 「下载并使用」, this is the
     * flow the page promises. [after] runs on the UI thread when the download settled either way, for a re-render.
     */
    fun startDownload(def: AsrModelDef, after: () -> Unit) {
        if (downloading != null) {
            // the confirm dialog can outlive the state it was opened on (the every-800ms page rebuild): say so
            lastError = "有另一个下载正在进行，等它完成再试。"
            post(after)
            return
        }
        if (importing) {
            // an import and a download both settle by putting their model in use (select): one of them at a time
            lastError = "正在导入自定义模型，等它完成再试。"
            post(after)
            return
        }
        if (ready(def)) return
        val dir = dir(def.id); dir.mkdirs()
        val need = def.files.filter { !existing(def, it) }.sumOf { it.bytes }
        val free = root.usableSpace
        if (need + SPACE_MARGIN > free) {
            lastError = "空间不够：还需要 ${mb(need + SPACE_MARGIN - free)}，模型本身要 ${mb(need)}。删掉一些东西或换个模型再试。"
            post(after)
            return
        }
        val d = Download(def)
        downloading = d
        lastError = null
        Thread({
            val md = runCatching { MessageDigest.getInstance("SHA-256") }.getOrNull()
            try {
                // one file at a time, in the definition's order (encoder first: the big one, visible progress);
                // files verified in an earlier attempt are skipped, an interrupted .part of the file is thrown away
                for ((idx, f) in def.files.withIndex()) {
                    d.fileIdx = idx
                    if (d.stopped) break
                    if (existing(def, f)) { d.doneBytes += f.bytes; continue }
                    fetch(f, File(dir, f.local + ".part"), d, md)
                    if (d.stopped) break
                    if (!File(dir, f.local + ".part").renameTo(File(dir, f.local))) throw IOException("保存文件失败")
                }
                if (d.stopped) return@Thread
                // a file that failed the checks never got here (fetch throws) → nothing unverified stays behind
                select(def.id)
                log("模型下载完成，已选用：${def.label}", null)
            } catch (e: Throwable) {
                if (!d.stopped) {
                    d.error = e.message ?: "下载失败"
                    lastError = "「${def.label}」下载失败：${d.error}"
                }
                log("模型下载失败", e)
            } finally {
                // sweep what a failed or cancelled run leaves: .part files always, and a wrongly sized final file of
                // this run (an earlier run's verified files stay — the next attempt skips them)
                def.files.forEach { File(dir, it.local + ".part").delete() }
                for ((idx, f) in def.files.withIndex()) {
                    val file = File(dir, f.local)
                    if (file.isFile && file.length() != f.bytes && idx >= d.fileIdx) file.delete()
                }
                if (downloading === d) downloading = null
                d.stopped = true
                post(after)
            }
        }, "asr-model-download").start()
    }

    fun cancelDownload() { downloading?.let { it.stopped = true } }

    /** the last download failure, shown by the model page until the next attempt (success clears it) */
    @Volatile var lastError: String? = null; private set

    /** A file already on disk with the right size; the `.part` of an interrupted earlier run never counts. */
    private fun existing(def: AsrModelDef, f: AsrModelDef.FileDef): Boolean {
        File(dir(def.id), f.local + ".part").delete()
        val file = File(dir(def.id), f.local)
        return file.isFile && file.length() == f.bytes
    }

    /**
     * Streams one file to [dst], following redirects by hand (the model hubs 302 onto their CDNs, and
     * HttpURLConnection only follows redirects that keep the scheme), SHA-256-ing the bytes while they go by, and
     * checking size and hash when the definition knows them. Throws with a user-readable message on anything wrong.
     */
    private fun fetch(f: AsrModelDef.FileDef, dst: File, d: Download, md: MessageDigest?) {
        var url = f.urls.first()
        repeat(6) {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "SimulSub/$userAgent")
            }
            try {
                when (c.responseCode) {
                    in 300..399 -> {
                        val loc = c.getHeaderField("Location") ?: throw IOException("服务器没有给出跳转地址")
                        url = URI(url).resolve(loc).toString(); return@repeat
                    }
                    200 -> {
                        md?.reset()
                        val seen = c.inputStream.use { input ->
                            dst.outputStream().use { out ->
                                val buf = ByteArray(256 * 1024)
                                var s = 0L
                                while (true) {
                                    if (d.stopped) throw IOException("已取消")
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    md?.update(buf, 0, n)
                                    s += n; d.doneBytes += n; d.fileBytes = s
                                }
                                s
                            }
                        }
                        if (f.bytes > 0 && seen != f.bytes) throw IOException("下载不完整（${seen} / ${f.bytes} 字节），请重试")
                        if (f.sha256 != null && md != null) {
                            val hex = md.digest().joinToString("") { "%02x".format(it) }
                            if (hex != f.sha256) throw IOException("文件校验不对（SHA-256），请重试；一直失败的话可能是网络被干扰")
                        }
                        return
                    }
                    else -> throw IOException("下载地址返回 ${c.responseCode}，请稍后再试")
                }
            } finally {
                c.disconnect()
            }
        }
        throw IOException("跳转太多次，请稍后再试")
    }

    // -- importing a custom model ----------------------------------------------------------------------------------------

    /** roles an imported transducer must cover, filled by the file-name rules of [roleOf] */
    val ROLES = listOf("encoder", "decoder", "joiner", "tokens")

    /** how a picked file tells what it is: every sherpa-onnx model names its files encoder…/decoder…/join…/tokens.txt */
    fun roleOf(name: String): String? {
        val n = name.lowercase()
        return when {
            n.startsWith("encoder") -> "encoder"
            n.startsWith("decoder") -> "decoder"
            n.startsWith("join") -> "joiner"
            n.startsWith("tokens") -> "tokens"
            else -> null
        }
    }

    /** One file the user picked for [import]: the [name] its role is read from and it is stored as, and the way to
     *  open its bytes (the phone: a ContentResolver document; the desktop: a local file). */
    class Source(val name: String, val open: () -> InputStream)

    /**
     * Copies the picked files into [CUSTOM]'s directory and records their roles in the custom meta; [streaming]
     * picks the engine (a non-streaming transducer is switched in the page with [setCustomKind]). @return what is
     * missing or blocks it (nothing starts then), or null once the copy is under way — [after] runs on the UI thread
     * when it settled: success put the custom model in use, failure leaves none and says why on the page.
     */
    fun import(items: List<Source>, streaming: Boolean, after: () -> Unit): String? {
        // one download or import at a time: both settle by putting their model in use (select), and two imports
        // copying into the same directory at once interleave their files
        if (downloading != null) return "有模型正在下载，等它完成再导入。"
        if (importing) return "上一个导入还没完成，等它完成再试。"
        val roles = LinkedHashMap<String, Source>()
        for (item in items) roleOf(item.name)?.let { roles[it] = item }
        val missing = ROLES.filter { it !in roles }
        if (missing.isNotEmpty())
            return "没认出全部四个文件，缺：${missing.joinToString("、") { roleName(it) }}。" +
                "请选择模型原本的四个文件（encoder*.onnx、decoder*.onnx、joiner*.onnx、tokens.txt）。"
        val duplicated = ROLES.filter { role -> items.count { roleOf(it.name) == role } > 1 }
        if (duplicated.isNotEmpty())
            return "选重了：${duplicated.joinToString("、") { roleName(it) }} 各选一个就够，请重新选四个正确的文件。"
        importing = true
        Thread({
            try {
                val dir = dir(CUSTOM)
                dir.deleteRecursively(); dir.mkdirs()
                val names = LinkedHashMap<String, String>()
                for (role in ROLES) {
                    // copied one per role, so an unreadable file leaves the previous import deleted and the meta not
                    // written — the session falls back to another model, and the catch below says what went wrong
                    names[role] = copy(roles[role]!!, dir)
                }
                saveCustom(CustomModel(if (streaming) KIND_STREAMING else KIND_OFFLINE, names))
                select(CUSTOM)
                // smoke-build once NOW, not at the next caption start: a wrong file can only fail here with a
                // message on the page, and (a bad engine config aside) sherpa-onnx's native code exits the whole
                // process without saying anything, which the user would only see as captions never starting
                try {
                    createAsr(threads = 2).release()
                } catch (e: Throwable) {
                    saveCustom(null)
                    select(BUILTIN)
                    lastError = "导入的模型没能通过构建检验（${e.message ?: "引擎创建失败"}），已回到默认。换对「识别方式」或换文件再试。"
                    log("自定义模型导入冒烟检验失败", e)
                    runCatching { dir.deleteRecursively() } // a model that cannot build is not kept around
                }
            } catch (e: Throwable) {
                saveCustom(null)
                // the copy above deleted the previous import's files: a selection pointing at them falls back
                if (selected() == CUSTOM) select(BUILTIN)
                lastError = "导入失败：${e.message ?: "未知原因"}，请重新选四个文件再试。"
                log("自定义模型导入失败", e)
                runCatching { dir(CUSTOM).deleteRecursively() } // half-copied files would sit there unlisted
            } finally {
                importing = false
                post(after)
            }
        }, "asr-model-import").start()
        return null
    }

    /** copies one picked file into [dir] under its own name; a file that cannot be opened or reads empty throws */
    private fun copy(source: Source, dir: File): String {
        val dst = File(dir, source.name)
        val input = runCatching { source.open() }
            .getOrElse { throw IOException("打不开所选的文件 ${source.name}（可能移动了位置）", it) }
        input.use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
        if (dst.length() == 0L) { dst.delete(); throw IOException("所选的文件 ${source.name} 读出来是空的") }
        return source.name
    }

    /** the room a download wants beyond the model's own bytes (the .part dupe of the biggest file, plus slack) */
    private const val SPACE_MARGIN = 300L * 1024 * 1024
    private fun mb(b: Long) = when {
        b >= 1_000_000L * 10 -> "${b / 1_000_000L} MB"
        b >= 1_000_000L -> "%.1f MB".format(java.util.Locale.US, b / 1_000_000.0)
        else -> "${b / 1024L} KB"
    }
}
