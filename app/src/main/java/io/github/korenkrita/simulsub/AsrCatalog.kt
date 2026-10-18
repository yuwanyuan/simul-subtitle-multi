package io.github.korenkrita.simulsub

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * The downloadable-model list as data: a `models.json` the fork's author keeps in the Gitee mirror of this repo
 * (primary) and in the GitHub fork's copy (backup, with the two download proxies in front of it), so a model can be
 * added, a dead URL replaced or a wrong size corrected without shipping an APK. [AsrModels.BUILTIN_CATALOG] stays in
 * the code as the offline fallback and the one entry no edit can drop (`builtin`, the model the app was born with).
 *
 * A fetched list is the truth: it replaces the code's list wholesale, and its order is the page's order (and
 * [AsrModels.firstReady]'s fallback order). What makes an already-downloaded model stay downloaded is the entry
 * keeping its id, its file names and its byte counts ([AsrModels.ready] compares exactly those) — editing a URL or a
 * wording never re-downloads anything; changing a size or an id means new files.
 *
 * Parsing is strict on purpose (id shape, exactly the four transducer roles, https-only URLs, a SHA-256 per file):
 * the app is about to download from what the file names, and a bad file is dropped whole with a reason instead of
 * being half-listed. The last good text is cached next to the model directories; with no network that copy is used,
 * with none at all the code's list.
 */
object AsrCatalog {
    private const val TAG = "jpsub"
    /** the only shape this app understands; a file of a newer one is rejected, never half-read */
    private const val SCHEMA = 1
    /** the Gitee mirror (edited there directly) and the GitHub fork's copy (the backup) */
    private const val GITEE = "https://gitee.com/ywyyuwanyuan/simul-subtitle-multi/raw/main/models.json"
    private const val GITHUB = "https://raw.githubusercontent.com/yuwanyuan/simul-subtitle-multi/my-mod/models.json"
    /** a manifest is a few KB; anything far larger is not one (the same cap as the update manifest) */
    private const val MAX_MANIFEST = 128 * 1024
    /** the page refetches on open at most this often; the status line's click always fetches */
    private const val REFRESH_INTERVAL_MS = 60 * 60 * 1000L
    /** a failure is not retried by simply opening the page again before this */
    private const val RETRY_MS = 5 * 60 * 1000L

    /** What a parsed manifest holds: the [revision] the page shows and the [models] in the file's own order. */
    class Parsed(val revision: String?, val models: List<AsrModelDef>)

    /** the fetched list; [AsrModels.CATALOG] prefers it, null = the code's own list */
    @Volatile private var fetched: List<AsrModelDef>? = null
    /** true while a fetch runs (the model page repaints while it does) */
    @Volatile var fetching = false; private set
    /** the last fetch failure, for the page's status line; null after a success (and before the first try) */
    @Volatile var lastError: String? = null; private set
    /** the fetched file's own revision ("2026-10-19"), shown as 更新于 … */
    @Volatile var revision: String? = null; private set
    /** true = the list in use came from a fetch (the cached one included), not from the code */
    val usingFetched: Boolean get() = fetched != null
    /** when the list in use was fetched; a cached one carries its file's timestamp */
    @Volatile private var lastOkAt = 0L
    @Volatile private var lastTryAt = 0L
    @Volatile private var loaded = false

    /** the URLs tried in order: Gitee (its raw 302s onto its own CDN), the GitHub copy, then the proxies */
    fun sources() = listOf(GITEE, GITHUB, "https://gh-proxy.com/$GITHUB", "https://gh.llkk.cc/$GITHUB")

    /**
     * Reads the cached list once, at the activity's start: a few KB on the UI thread, and a cold start on no network
     * still knows a remote model id the saved selection may point at ([AsrModels.selectionUsable]).
     */
    fun init(ctx: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val f = cacheFile(ctx)
            if (f.isFile) {
                val p = parse(f.readText())
                fetched = p.models
                revision = p.revision
                // when that copy really was fetched, so an old one is still refreshed on the next page open
                lastOkAt = f.lastModified()
            }
        }.onFailure { Log.w(TAG, "缓存的模型清单读不出来", it) }
    }

    /** [AsrModels.CATALOG]: the fetched list when there is one, else the code's own */
    fun withRemotes(builtins: List<AsrModelDef>): List<AsrModelDef> = merge(builtins, fetched)

    /** the file is the truth (order included); the code's `builtin` is the one entry an edit cannot drop */
    internal fun merge(builtins: List<AsrModelDef>, fetched: List<AsrModelDef>?): List<AsrModelDef> {
        if (fetched == null) return builtins
        val safety = builtins.filter { b -> b.id == AsrModels.BUILTIN && fetched.none { it.id == b.id } }
        return fetched + safety
    }

    /**
     * What the model page calls when it opens: fetch unless one is running, the last success is younger than
     * [REFRESH_INTERVAL_MS], or the last failure is younger than [RETRY_MS]. [after] runs on the UI thread either way.
     */
    fun maybeRefresh(ctx: Context, after: () -> Unit) {
        val now = System.currentTimeMillis()
        if (fetching || (fetched != null && now - lastOkAt < REFRESH_INTERVAL_MS) || now - lastTryAt < RETRY_MS) {
            post(after)
            return
        }
        refresh(ctx, after)
    }

    /** Fetches now (the status line's click): one at a time, off the UI thread; [after] runs on the UI thread. */
    fun refresh(ctx0: Context, after: () -> Unit) {
        if (fetching) { post(after); return }
        val ctx = ctx0.applicationContext
        fetching = true
        lastTryAt = System.currentTimeMillis()
        Thread({
            var why: String? = null
            var good: Pair<String, Parsed>? = null
            for (url in sources()) {
                try { good = request(url); break }
                catch (t: Throwable) { why = reason(t) }
            }
            if (good != null) {
                val (text, p) = good
                fetched = p.models
                revision = p.revision
                lastError = null
                lastOkAt = System.currentTimeMillis()
                runCatching {
                    val f = cacheFile(ctx)
                    val tmp = File(f.parentFile, f.name + ".part")
                    tmp.writeBytes(text.toByteArray(Charsets.UTF_8))
                    if (!tmp.renameTo(f)) tmp.delete() // on Linux rename replaces; a refusal leaves no .part behind
                }.onFailure { Log.w(TAG, "模型清单没能写进缓存", it) }
                Log.i(TAG, "模型清单已更新：${revision ?: "无 revision"}，${p.models.size} 个模型")
            } else {
                lastError = why ?: "获取失败"
                Log.w(TAG, "模型清单获取失败：$lastError")
            }
            fetching = false
            post(after)
        }, "asr-catalog").start()
    }

    /**
     * @throws IllegalArgumentException with a readable reason: a manifest is about to have the app download from it,
     * so any trouble drops the whole file — the cached list (or the code's own) stays in use and the page says why.
     */
    fun parse(text: String): Parsed = try {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST) { "清单文件太大" }
        val j = JSONObject(text)
        val schema = j.get("schemaVersion").toString().toInt()
        require(schema == SCHEMA) { "清单的格式版本是 $schema，这个 App 只认 $SCHEMA" }
        val rev = j.optString("revision", "").trim().take(40).takeIf { it.isNotEmpty() }
        val arr = j.getJSONArray("models")
        require(arr.length() in 1..50) { "模型清单要有 1–50 个模型" }
        val ids = HashSet<String>()
        Parsed(rev, (0 until arr.length()).map { model(arr.getJSONObject(it), ids) })
    } catch (e: JSONException) {
        throw IllegalArgumentException("清单格式不对：${e.message}")
    } catch (e: NumberFormatException) {
        throw IllegalArgumentException("清单里的数字格式不对：${e.message}")
    }

    /** One model entry, validated; [ids] holds the ones already seen (a repeated id is a config mistake). */
    private fun model(o: JSONObject, ids: MutableSet<String>): AsrModelDef {
        val id = o.getString("id")
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) { "模型 id「$id」不合法（小写字母、数字、._-）" }
        require(id != AsrModels.CUSTOM) { "id「$id」是留给本地导入的" }
        require(ids.add(id)) { "模型 id「$id」出现了两次" }
        val label = o.getString("label").trim()
        require(label.isNotEmpty() && label.length <= 120) { "「$id」的 label 不对" }
        val summary = o.optString("summary", "").trim().take(300)
        val streaming = o.getBoolean("streaming")
        val origin = o.optString("origin", "").trim().take(200)
        // optional: the 「首选」 mark the row shows (a note to the user; the list order stays what decides fallback)
        val preferred = o.optBoolean("preferred", false)
        val fa = o.getJSONArray("files")
        require(fa.length() == 4) { "「$id」必须给齐四个文件（encoder / decoder / joiner / tokens）" }
        val byRole = LinkedHashMap<String, AsrModelDef.FileDef>()
        for (i in 0 until fa.length()) {
            val f = fa.getJSONObject(i)
            val local = f.getString("local")
            require(local.length <= 120 && local.none { it == '/' || it == '\\' } &&
                !local.startsWith(".") && local != "..") { "「$id」的文件名「$local」不合法" }
            val role = AsrModels.roleOf(local)
            require(role != null) { "「$id」的文件名「$local」认不出是哪个角色" }
            val bytes = f.get("bytes").toString().toLong()
            require(bytes > 0) { "「$id」的 $local 字节数不对" }
            val sha = f.optString("sha256", "").trim().lowercase()
            require(sha.matches(Regex("[0-9a-f]{64}"))) { "「$id」的 $local 没有合法的 SHA-256" }
            val ua = f.getJSONArray("urls")
            require(ua.length() in 1..4) { "「$id」的 $local 要有 1–4 个下载地址" }
            val urls = (0 until ua.length()).map { ua.getString(it) }
            require(urls.all { it.startsWith("https://") && it.length <= 500 }) { "「$id」的 $local 地址必须以 https:// 开头" }
            require(byRole.put(role, AsrModelDef.FileDef(local, urls, bytes, sha)) == null) {
                "「$id」的 ${AsrModels.roleName(role)} 出现了两次"
            }
        }
        // the engine reads the four in role order, wherever the file happens to list them
        return AsrModelDef(id, label, summary, streaming, AsrModels.ROLES.map { byRole[it]!! }, origin, preferred)
    }

    private class CatalogHttp(val code: Int) : IOException("HTTP $code")

    /** One URL read whole and parsed. Redirects are followed (Gitee's raw 302s onto its own CDN); nothing secret is sent. */
    private fun request(url: String): Pair<String, Parsed> {
        CLIENT.newCall(Request.Builder().url(url).header("Cache-Control", "no-cache").build()).execute().use { r ->
            if (!r.isSuccessful) throw CatalogHttp(r.code)
            val body = r.body ?: throw CatalogHttp(r.code)
            val bytes = body.source().let { s -> s.request(MAX_MANIFEST + 1L); s.buffer.readByteArray() }
            if (bytes.size > MAX_MANIFEST) throw IOException("清单文件太大")
            val text = bytes.toString(Charsets.UTF_8)
            return text to parse(text)
        }
    }

    /** a short reason for the page's status line (the Log gets the whole failure) */
    private fun reason(t: Throwable): String = when {
        t is CatalogHttp -> "HTTP ${t.code}"
        t is SocketTimeoutException -> "连接超时"
        t is IllegalArgumentException -> t.message ?: "清单格式不对"
        t is IOException -> "网络不通"
        else -> t.message ?: t.javaClass.simpleName
    }

    /** beside the model directories (the same app-specific storage [AsrModels.root] uses): the last good text */
    private fun cacheFile(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "asr-catalog.json")

    private fun post(r: () -> Unit) = android.os.Handler(android.os.Looper.getMainLooper()).post(r)

    private val CLIENT = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true).build()
}
