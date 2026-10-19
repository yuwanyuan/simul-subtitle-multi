package io.github.korenkrita.simulsub

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

/**
 * The local translation models the PC version offers (设置 › 翻译 › 本地翻译): Tencent Hunyuan MT GGUFs that the
 * desktop's llama.cpp binding runs in this process, fully offline — no API, no key. One model is one file, with the
 * download and readiness logic [AsrModels] uses, one file wide: `.part` → byte count + SHA-256 → rename, mirror-first
 * URLs, the same user-facing messages.
 *
 * The platform seams are installed once at start-up ([install]); everything below is plain JVM code, so the desktop's
 * settings page and a test drive the same object, its page logic and its messages.
 */
object LlmModels {
    /** One downloadable local translation model. */
    class Def(
        val id: String,
        val label: String,
        /** one line for the list row: languages, size, where it comes from, what it does */
        val summary: String,
        /** the name the file lands under, in the model's own directory ([fileOf]) */
        val file: String,
        /** download addresses, mirror first, tried in order (1–4, https): a redirected one is followed by hand */
        val urls: List<String>,
        val bytes: Long,
        val sha256: String,
        /** the catalog's 「首选」 mark (the row's label carries it): a note for the user, not a behavior */
        val preferred: Boolean = false,
    )

    // -- the catalog. Sizes and hashes are what a download is verified against, so they are the media's own metadata:
    //    698483424 and 49506eb0… are `sha256sum` of the IQ2_M file the mirror served on 2026-10-20, 1133080512 and
    //    4383ac0c… of the official Q4_K_M file (the mirror's Content-Length agrees with both). The mirror comes
    //    first: plain huggingface.co does not answer from this region. Order = the page's order.
    //
    //    Why not 混元's own 440 MB file: Hugging Face carries 1.25bit (440 MB, tencent/Hy-MT1.5-1.8B-1.25bit-GGUF)
    //    and 2bit (574 MB) builds, but both are quantized with Tencent's own schemes (Sherry's STQ1_0, and SEQ) that
    //    llama.cpp cannot read yet — the loader stops at "tensor 'blk.0.attn_k_norm.weight' has offset …, expected …"
    //    (measured on both, with the llama.cpp the binding ships). A third-party IQ1_S (481 MB) and IQ2_XXS (560 MB)
    //    do load, but answered the measured sentence with "上述现象，若取消则，皆都能是？需要答案。" and "翻译如下，但：" —
    //    too damaged to ship. IQ2_M is the smallest quant measured to translate; Q4_K_M is the official one. Getting
    //    the 440 MB file in here again is one catalog line, once llama.cpp merges STQ1_0 (its PRs 22836/27377 were
    //    still open on 2026-10-19).
    private const val HYQ4 = "tencent/HY-MT1.5-1.8B-GGUF/resolve/main/HY-MT1.5-1.8B-Q4_K_M.gguf"
    private const val HYIQ2M = "mradermacher/HY-MT1.5-1.8B-i1-GGUF/resolve/main/HY-MT1.5-1.8B.i1-IQ2_M.gguf"

    /** Built-in catalog (data; editing the list is the change): the smallest measured to translate, then the official one. */
    val CATALOG: List<Def> = listOf(
        Def(
            "hy-mt15-18b-iq2m", "混元翻译 1.8B（IQ2_M，约 666 MB）",
            "中、英、日等 · 约 666 MB · 第三方 i1 量化 · HuggingFace（镜像下载）· 本机离线运行，翻译不联网",
            "HY-MT1.5-1.8B.i1-IQ2_M.gguf",
            listOf("https://hf-mirror.com/$HYIQ2M", "https://huggingface.co/$HYIQ2M"),
            698_483_424, "49506eb0c6591afaaaa885f664e216c3c582016a3f692e02e7ce2c8bc17d0311",
            preferred = true,
        ),
        Def(
            "hy-mt15-18b-q4km", "混元翻译 1.8B（Q4_K_M，约 1.1 GB，质量更好）",
            "中、英、日等 · 约 1.1 GB · 腾讯混元 · HuggingFace（镜像下载）· 本机离线运行，翻译不联网",
            "HY-MT1.5-1.8B-Q4_K_M.gguf",
            listOf("https://hf-mirror.com/$HYQ4", "https://huggingface.co/$HYQ4"),
            1_133_080_512, "4383ac0c3c8e476de98ff979c2a3f069f8c4fb385e7860cf2d28da896cc477c7",
        ),
    )

    /** What the platform keeps the selection in (the desktop: its settings.json); "" = nothing chosen. */
    interface LlmPrefs {
        /** the chosen model id (a [Def]'s; anything unknown reads as nothing chosen) */
        var selected: String
    }

    /**
     * Hands the object everything a platform must supply, once, before the 本地翻译 page is built. [userAgent] is the
     * app's version: downloads announce themselves as `SimulSub/<userAgent>`, the shape the model hubs have seen.
     */
    fun install(root: File, prefs: LlmPrefs, post: (() -> Unit) -> Unit, log: (String, Throwable?) -> Unit, userAgent: String) {
        this.root = root.apply { mkdirs() }
        this.prefs = prefs
        this.post = post
        this.log = log
        this.userAgent = userAgent
    }

    private var prefs: LlmPrefs? = null

    private fun store(): LlmPrefs = prefs ?: error("LlmModels.install 还没调用")

    /** How a platform runs [r] on its UI thread (the desktop: the AWT event thread); the default runs it inline. */
    var post: ((() -> Unit) -> Unit) = { it() }

    /** Where a platform writes its log; the default drops it. */
    var log: (String, Throwable?) -> Unit = { _, _ -> }

    /** the version in the downloads' `User-Agent: SimulSub/<this>` */
    var userAgent: String = "SimulSub"

    /** every model gets its own directory under this (the desktop: its per-user data directory) */
    lateinit var root: File

    // -- selection -------------------------------------------------------------------------------------------------------

    /** the chosen model id (a [Def]'s, or "" = nothing chosen) */
    fun selected(): String = store().selected

    fun select(id: String) { store().selected = id }

    fun def(id: String): Def? = CATALOG.firstOrNull { it.id == id }

    // -- where the file lives & whether it is there ----------------------------------------------------------------------

    private fun dir(id: String) = File(root, id)

    private fun file(d: Def) = File(dir(d.id), d.file)

    /** a catalog model's file under [root]; an id that is not in [CATALOG] is a programming error, not a state */
    fun fileOf(id: String): File = def(id)?.let { file(it) } ?: error("未知的本地翻译模型 id：$id")

    /** A model is usable when its file is there with the expected byte count. The SHA-256 was checked when it
     *  arrived: re-hashing 0.4–1.1 GB on every page render is too slow for this check, as in [AsrModels.ready]. */
    fun ready(d: Def): Boolean {
        val f = file(d)
        return f.isFile && f.length() == d.bytes
    }

    /** true = at least one model is downloaded (what the page and the settings entry ask) */
    fun anyReady(): Boolean = CATALOG.any { ready(it) }

    /**
     * Deletes a model's directory. A deleted selection falls back to the first ready model in [CATALOG]'s order
     * (the 1.25bit is first and [Def.preferred]), or to "" (nothing chosen) when none is left — the local session
     * then refuses to start with its own message instead of running on a file that is gone.
     */
    fun delete(id: String) {
        dir(id).deleteRecursively()
        if (selected() == id) select(CATALOG.firstOrNull { ready(it) }?.id.orEmpty())
    }

    // -- downloading -----------------------------------------------------------------------------------------------------

    /** one download in progress; the page reads it each tick, [LlmModels.downloading] says if it still runs */
    class Download internal constructor(val def: Def) {
        /** the user cancelled, or the download settled: the thread notices and stops; no [error] then */
        @Volatile var stopped = false
        /** bytes that arrived so far; [totalBytes] is the definition's own size, known before the first byte */
        @Volatile var doneBytes = 0L
        val totalBytes: Long get() = def.bytes
        /** failure text; null while it runs, and (with [stopped]) after the user cancels */
        @Volatile var error: String? = null
        val percent: Int get() = if (totalBytes <= 0) 0 else (doneBytes * 100 / totalBytes).toInt().coerceIn(0, 100)
    }

    /** the download in progress, if any (one at a time) */
    @Volatile var downloading: Download? = null; private set

    /** the last download failure, shown by the page until the next attempt (a success clears it) */
    @Volatile var lastError: String? = null

    /**
     * Starts downloading [def] (no-op while another download runs or the model is already there). Checks the free
     * space first. On success the model is put in use ([select]) — the row says 「下载并使用」. [after] runs on the
     * UI thread once the download settled either way, for a re-render.
     */
    fun startDownload(d: Def, after: () -> Unit) {
        if (downloading != null) {
            lastError = "有另一个下载正在进行，等它完成再试。"
            post(after)
            return
        }
        if (ready(d)) return
        val dir = dir(d.id); dir.mkdirs()
        val free = root.usableSpace
        if (d.bytes + SPACE_MARGIN > free) {
            lastError = "空间不够：还需要 ${mb(d.bytes + SPACE_MARGIN - free)}，模型本身要 ${mb(d.bytes)}。删掉一些东西或换个模型再试。"
            post(after)
            return
        }
        val dl = Download(d)
        downloading = dl
        lastError = null
        Thread({
            val md = runCatching { MessageDigest.getInstance("SHA-256") }.getOrNull()
            try {
                // a fresh .part every attempt: what an interrupted run left is not resumed, it is overwritten
                fetch(d, File(dir, d.file + ".part"), dl, md)
                if (dl.stopped) return@Thread
                if (!File(dir, d.file + ".part").renameTo(File(dir, d.file))) throw IOException("保存文件失败")
                select(d.id)
                log("本地翻译模型下载完成，已选用：${d.label}", null)
            } catch (e: Throwable) {
                if (!dl.stopped) {
                    dl.error = e.message ?: "下载失败"
                    lastError = "「${d.label}」下载失败：${dl.error}"
                }
                log("本地翻译模型下载失败", e)
            } finally {
                // sweep what a failed or cancelled run leaves: the .part, and a final file of the wrong size (a
                // complete one made [ready] return above before this thread ever started)
                File(dir, d.file + ".part").delete()
                val f = File(dir, d.file)
                if (f.isFile && f.length() != d.bytes) f.delete()
                if (downloading === dl) downloading = null
                dl.stopped = true
                post(after)
            }
        }, "llm-model-download").start()
    }

    fun cancelDownload() { downloading?.let { it.stopped = true } }

    /**
     * Streams the model file to [dst], following redirects by hand (the model hubs 302 onto their CDNs, and
     * HttpURLConnection only follows redirects that keep the scheme), SHA-256-ing the bytes while they go by, and
     * checking size and hash when they arrived. Throws with a user-readable message on anything wrong.
     */
    private fun fetch(d: Def, dst: File, dl: Download, md: MessageDigest?) {
        var url = d.urls.first()
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
                                    if (dl.stopped) throw IOException("已取消")
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    md?.update(buf, 0, n)
                                    s += n; dl.doneBytes += n
                                }
                                s
                            }
                        }
                        if (seen != d.bytes) throw IOException("下载不完整（${seen} / ${d.bytes} 字节），请重试")
                        if (md != null) {
                            val hex = md.digest().joinToString("") { "%02x".format(it) }
                            if (hex != d.sha256) throw IOException("文件校验不对（SHA-256），请重试；一直失败的话可能是网络被干扰")
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

    /** the room a download wants beyond the model's own bytes (the .part dupe while it downloads, plus slack) */
    private const val SPACE_MARGIN = 300L * 1024 * 1024
    private fun mb(b: Long) = when {
        b >= 1_000_000L * 10 -> "${b / 1_000_000L} MB"
        b >= 1_000_000L -> "%.1f MB".format(java.util.Locale.US, b / 1_000_000.0)
        else -> "${b / 1024L} KB"
    }
}
