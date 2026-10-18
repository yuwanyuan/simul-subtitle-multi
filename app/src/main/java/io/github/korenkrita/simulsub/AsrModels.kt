package io.github.korenkrita.simulsub

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
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
    /** true = streaming (chunked) zipformer → [StreamingSherpaAsr]; false = offline → [FileTransducerAsr] */
    val streaming: Boolean,
    val files: List<FileDef>,
    /** the model's own name where it lives (owner/repository on Hugging Face / ModelScope), shown on the download
     *  rows: the [label] is ours and friendly, this is what the model is called upstream and what lands on disk */
    val origin: String = "",
) {
    class FileDef(val local: String, val urls: List<String>, val bytes: Long, val sha256: String?)

    val sizeBytes: Long get() = files.sumOf { it.bytes }
}

/**
 * The downloadable / importable speech models and which one the captions run on. Selected in 设置 › 字幕 ›
 * 语音识别模型; a change takes effect the next time captions start ([CaptionService.loadModels] reads it there).
 * What is picked and a custom model's meta are stored next to [Prefs]' settings (same file, own keys); a model's
 * files live under [dir], one directory per model id, where nothing else of the app writes.
 */
object AsrModels {
    private const val TAG = "jpsub"
    /** the model the app was born with — still the plain default in the prefs, but a download now (not in the APK) */
    const val BUILTIN = "builtin"
    /** a model the user imported from the phone (one; the next import replaces it) */
    const val CUSTOM = "custom"
    /** the [CustomModel.kind] values */
    const val KIND_STREAMING = "streaming"
    const val KIND_OFFLINE = "transducer"

    /** keeps what was picked and the custom model's meta beside [Prefs]' settings (one file, its own keys) */
    private fun sp(ctx: Context) = ctx.getSharedPreferences("jpsub", Context.MODE_PRIVATE)

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
     * fetched entry that keeps them keeps the files already on the phone (an edited URL never re-downloads).
     */
    val CATALOG: List<AsrModelDef> get() = AsrCatalog.withRemotes(BUILTIN_CATALOG)

    fun def(id: String): AsrModelDef? = CATALOG.firstOrNull { it.id == id }

    // -- selection & the custom model -----------------------------------------------------------------------------------

    /** the model the captions run on: [BUILTIN], [CUSTOM] or a [CATALOG] id; anything unknown reads as built-in */
    fun selected(ctx: Context): String = sp(ctx).getString("asrModel", BUILTIN) ?: BUILTIN

    fun select(ctx: Context, id: String) = sp(ctx).edit().putString("asrModel", id).apply()

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

    fun custom(ctx: Context): CustomModel? = CustomModel.parse(sp(ctx).getString("customAsr", null))

    fun saveCustom(ctx: Context, model: CustomModel?) {
        sp(ctx).edit().apply { if (model == null) remove("customAsr") else putString("customAsr", model.json()) }.apply()
    }

    /** The import guesses streaming (the published zipformers are); a non-streaming transducer is a choice here. */
    fun setCustomKind(ctx: Context, kind: String) {
        val c = custom(ctx) ?: return
        saveCustom(ctx, CustomModel(kind, c.files))
    }

    // -- where the files live & whether they are there -------------------------------------------------------------------

    /** app-specific external storage: no permission needed, gone on uninstall; internal files when that is gone */
    fun root(ctx: Context): File = File(externalOrInternal(ctx), "asr-models").apply { mkdirs() }

    private fun externalOrInternal(ctx: Context): File =
        ctx.getExternalFilesDir(null) ?: ctx.filesDir

    fun dir(ctx: Context, id: String): File = File(root(ctx), id)

    /** A model is usable when every file of its definition is there with the expected size (the SHA-256 was checked
     *  when it arrived; re-hashing hundreds of MB is too slow for a page render). */
    fun ready(ctx: Context, def: AsrModelDef): Boolean = def.files.all {
        val f = File(dir(ctx, def.id), it.local)
        f.isFile && f.length() == it.bytes
    }

    fun customReady(ctx: Context): Boolean {
        val c = custom(ctx) ?: return false
        val dir = dir(ctx, CUSTOM)
        return c.files.values.all { name -> File(dir, name).isFile && File(dir, name).length() > 0 }
    }

    /** true = the stored selection points at a usable set of files (the built-in id now is a download like the rest) */
    fun selectionUsable(ctx: Context): Boolean = when (val id = selected(ctx)) {
        CUSTOM -> customReady(ctx)
        else -> def(id)?.let { ready(ctx, it) } == true
    }

    /** the model in use, as the 字幕 page's entry row names it */
    fun labelOf(ctx: Context): String = when (val id = selected(ctx)) {
        CUSTOM -> "自定义模型"
        else -> def(id)?.label ?: CATALOG[0].label
    }

    /** the ready model the captions would actually run on ([createAsr]'s pick): the selection, else the first ready */
    fun firstReady(ctx: Context): AsrModelDef? = CATALOG.firstOrNull { ready(ctx, it) }

    /** true = some model is downloaded, so captions can start (what the home screen asks) */
    fun anyReady(ctx: Context): Boolean = selectionUsable(ctx) || CATALOG.any { ready(ctx, it) }

    /** delete a model's directory (+ the custom meta for [CUSTOM]); a selected one falls back to built-in */
    fun delete(ctx: Context, id: String) {
        dir(ctx, id).deleteRecursively()
        if (id == CUSTOM) saveCustom(ctx, null)
        if (selected(ctx) == id) select(ctx, BUILTIN)
    }

    // -- the engine for the selected model -------------------------------------------------------------------------------

    /**
     * Builds the ASR of the selected model for a session start ([CaptionService.loadModels]). The selection is used
     * when its files are there, else the first downloaded model; with none of them on the phone it throws with a
     * message for the overlay (the session reports `出错：…`) — there is no model in the APK any more to fall back to.
     */
    fun createAsr(ctx: Context, threads: Int): ReleasableAsr {
        val id = selected(ctx)
        val d = when {
            id == CUSTOM && customReady(ctx) -> {
                val c = custom(ctx) ?: throw IllegalStateException("自定义模型的文件找不到了，回「设置 › 字幕 › 语音识别模型」重新导入，或选回内置模型。")
                val dir = dir(ctx, CUSTOM)
                fun p(role: String): String {
                    val name = c.files[role] ?: throw IllegalStateException(
                        "自定义模型缺「${roleName(role)}」，回「设置 › 字幕 › 语音识别模型」重新导入。")
                    val f = File(dir, name)
                    if (!f.isFile || f.length() == 0L) throw IllegalStateException(
                        "自定义模型的 ${name} 文件不在了，回「设置 › 字幕 › 语音识别模型」重新导入。")
                    return f.absolutePath
                }
                return if (c.kind == KIND_OFFLINE) FileTransducerAsr(p("encoder"), p("decoder"), p("joiner"), p("tokens"), threads)
                else StreamingSherpaAsr(p("encoder"), p("decoder"), p("joiner"), p("tokens"), threads)
            }
            def(id)?.let { ready(ctx, it) } == true -> def(id)!!
            // the selection's files are gone (or point at an older build's id): run on whatever is downloaded
            else -> firstReady(ctx) ?: throw IllegalStateException(
                "还没有语音识别模型可用：到「设置 › 字幕 › 语音识别模型」下载一个（日英模型 77 MB，多语种 339 MB，德语 71 MB），下次开始字幕时就能用。")
        }
        val dir = dir(ctx, d.id)
        fun p(f: AsrModelDef.FileDef) = File(dir, f.local).absolutePath
        // what this session will listen with (the trouble-shooting line behind "用了哪个模型" reports)
        log(ctx, "ASR 模型：${d.label}（${if (d.streaming) "流式" else "非流式"}）· 目录 ${dir}")
        return if (d.streaming) StreamingSherpaAsr(p(d.files[0]), p(d.files[1]), p(d.files[2]), p(d.files[3]), threads)
        else FileTransducerAsr(p(d.files[0]), p(d.files[1]), p(d.files[2]), p(d.files[3]), threads)
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
    fun startDownload(ctx0: Context, def: AsrModelDef, after: () -> Unit) {
        val ctx = ctx0.applicationContext
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
        if (ready(ctx, def)) return
        val dir = dir(ctx, def.id); dir.mkdirs()
        val need = def.files.filter { !existing(ctx, def, it) }.sumOf { it.bytes }
        val free = StatFs(externalOrInternal(ctx).path).availableBytes
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
                    if (existing(ctx, def, f)) { d.doneBytes += f.bytes; continue }
                    fetch(ctx, f, File(dir, f.local + ".part"), d, md)
                    if (d.stopped) break
                    if (!File(dir, f.local + ".part").renameTo(File(dir, f.local))) throw IOException("保存文件失败")
                }
                if (d.stopped) return@Thread
                // a file that failed the checks never got here (fetch throws) → nothing unverified stays behind
                select(ctx, def.id)
                log(ctx, "模型下载完成，已选用：${def.label}")
            } catch (e: Throwable) {
                if (!d.stopped) {
                    d.error = e.message ?: "下载失败"
                    lastError = "「${def.label}」下载失败：${d.error}"
                }
                log(ctx, "模型下载失败", e)
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
    private fun existing(ctx: Context, def: AsrModelDef, f: AsrModelDef.FileDef): Boolean {
        File(dir(ctx, def.id), f.local + ".part").delete()
        val file = File(dir(ctx, def.id), f.local)
        return file.isFile && file.length() == f.bytes
    }

    /**
     * Streams one file to [dst], following redirects by hand (the model hubs 302 onto their CDNs, and
     * HttpURLConnection only follows redirects that keep the scheme), SHA-256-ing the bytes while they go by, and
     * checking size and hash when the definition knows them. Throws with a user-readable message on anything wrong.
     */
    private fun fetch(ctx: Context, f: AsrModelDef.FileDef, dst: File, d: Download, md: MessageDigest?) {
        var url = f.urls.first()
        repeat(6) {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "SimulSub/${BuildConfig.VERSION_NAME}")
            }
            try {
                when (c.responseCode) {
                    in 300..399 -> {
                        val loc = c.getHeaderField("Location") ?: throw IOException("服务器没有给出跳转地址")
                        url = java.net.URI(url).resolve(loc).toString(); return@repeat
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

    /**
     * Copies the picked documents into [CUSTOM]'s directory and records their roles in the custom meta; [streaming]
     * picks the engine (a non-streaming transducer is switched in the page with [setCustomKind]). @return what is
     * missing or blocks it (nothing starts then), or null once the copy is under way — [after] runs on the UI thread
     * when it settled: success put the custom model in use, failure leaves none and says why on the page.
     */
    fun import(ctx0: Context, items: List<Pair<Uri, String>>, streaming: Boolean, after: () -> Unit): String? {
        val ctx = ctx0.applicationContext
        // one download or import at a time: both settle by putting their model in use (select), and two imports
        // copying into the same directory at once interleave their files
        if (downloading != null) return "有模型正在下载，等它完成再导入。"
        if (importing) return "上一个导入还没完成，等它完成再试。"
        val roles = LinkedHashMap<String, Pair<Uri, String>>() // role → uri + name
        for ((uri, name) in items) roleOf(name)?.let { roles[it] = uri to name }
        val missing = ROLES.filter { it !in roles }
        if (missing.isNotEmpty())
            return "没认出全部四个文件，缺：${missing.joinToString("、") { roleName(it) }}。" +
                "请选择模型原本的四个文件（encoder*.onnx、decoder*.onnx、joiner*.onnx、tokens.txt）。"
        val duplicated = ROLES.filter { role -> items.count { roleOf(it.second) == role } > 1 }
        if (duplicated.isNotEmpty())
            return "选重了：${duplicated.joinToString("、") { roleName(it) }} 各选一个就够，请重新选四个正确的文件。"
        importing = true
        Thread({
            try {
                val dir = dir(ctx, CUSTOM)
                dir.deleteRecursively(); dir.mkdirs()
                val names = LinkedHashMap<String, String>()
                for (role in ROLES) {
                    val (uri, name) = roles[role]!!
                    // copied one per role, so an unreadable file leaves the previous import deleted and the meta not
                    // written — the session falls back to another model, and the catch below says what went wrong
                    names[role] = copy(ctx, uri, dir, name)
                }
                saveCustom(ctx, CustomModel(if (streaming) KIND_STREAMING else KIND_OFFLINE, names))
                select(ctx, CUSTOM)
                // smoke-build once NOW, not at the next caption start: a wrong file can only fail here with a
                // message on the page, and (a bad engine config aside) sherpa-onnx's native code exits the whole
                // process without saying anything, which the user would only see as captions never starting
                try {
                    createAsr(ctx, threads = 2).release()
                } catch (e: Throwable) {
                    saveCustom(ctx, null)
                    select(ctx, BUILTIN)
                    lastError = "导入的模型没能通过构建检验（${e.message ?: "引擎创建失败"}），已回到默认。换对「识别方式」或换文件再试。"
                    log(ctx, "自定义模型导入冒烟检验失败", e)
                    runCatching { dir.deleteRecursively() } // a model that cannot build is not kept on the phone
                }
            } catch (e: Throwable) {
                saveCustom(ctx, null)
                // the copy above deleted the previous import's files: a selection pointing at them falls back
                if (selected(ctx) == CUSTOM) select(ctx, BUILTIN)
                lastError = "导入失败：${e.message ?: "未知原因"}，请重新选四个文件再试。"
                log(ctx, "自定义模型导入失败", e)
                runCatching { dir(ctx, CUSTOM).deleteRecursively() } // half-copied files would sit there unlisted
            } finally {
                importing = false
                post(after)
            }
        }, "asr-model-import").start()
        return null
    }

    private fun copy(ctx: Context, uri: Uri, dir: File, name: String): String {
        val dst = File(dir, name)
        ctx.contentResolver.openInputStream(uri)?.use { input -> dst.outputStream().use { input.copyTo(it) } }
            ?: throw IOException("打不开所选的文件 $name（可能移动了位置）")
        if (dst.length() == 0L) { dst.delete(); throw IOException("所选的文件 $name 读出来是空的") }
        return name
    }

    private fun post(r: () -> Unit) = android.os.Handler(android.os.Looper.getMainLooper()).post(r)
    private fun log(ctx: Context, m: String, e: Throwable? = null) = if (e == null) Log.i(TAG, m) else Log.w(TAG, m, e)

    /** the room a download wants beyond the model's own bytes (the .part dupe of the biggest file, plus slack) */
    private const val SPACE_MARGIN = 300L * 1024 * 1024
    private fun mb(b: Long) = when {
        b >= 1_000_000L * 10 -> "${b / 1_000_000L} MB"
        b >= 1_000_000L -> "%.1f MB".format(java.util.Locale.US, b / 1_000_000.0)
        else -> "${b / 1024L} KB"
    }
}
