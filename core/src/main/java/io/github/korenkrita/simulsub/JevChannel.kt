package io.github.korenkrita.simulsub

/**
 * The custom Jev channel ([JevProvider.CUSTOM]): the user's own Jev-compatible service, self-hosted or a relay. The
 * address is checked and filled in the way BYOK's is ([ByokTranslator.chatUrl]) — a base, `…/v1` or the full endpoint
 * all work — and the standard Jev interface answers at `…/v1/systemone` (POST `{model, state, questions}`).
 */
object JevChannel {
    /** The path of the standard Jev interface, after the address's `…/v1` base. */
    const val PATH = "/systemone"
    /** model ids are short; a longer one is a paste mistake */
    private const val MAX_MODEL = 200

    /** (why the address cannot be used, the address to call): the first is null when it can. */
    fun url(input: String): Pair<String?, String?> {
        val s = input.trim()
        if (s.isEmpty()) return "还没填地址" to null
        val u = runCatching { java.net.URI(s) }.getOrNull() ?: return "地址格式不对" to null
        val scheme = u.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return "地址要以 http:// 或 https:// 开头" to null
        if (u.host.isNullOrEmpty()) return "地址里没有域名" to null
        if (u.rawUserInfo != null || u.rawQuery != null || u.rawFragment != null) return "地址里不要带用户名、?参数 或 #" to null
        if (u.port == 0 || u.port > 65535) return "端口号不对" to null
        var path = (u.rawPath ?: "").trimEnd('/')
        path = path.removeSuffix(PATH)
        if (!path.endsWith("/v1")) path += "/v1"
        val port = if (u.port > 0) ":${u.port}" else ""
        val out = "$scheme://${u.host.lowercase()}$port$path$PATH"
        // what OkHttp will be given: it must accept it too (IPv6 brackets, odd characters, ...)
        if (okhttp3.HttpUrl.Companion.run { out.toHttpUrlOrNull() } == null) return "地址格式不对" to null
        return null to out
    }

    /** (why the model id cannot be used, the model id to send): the first is null when it can. */
    fun model(input: String): Pair<String?, String?> {
        val s = input.trim()
        if (s.isEmpty()) return "还没填模型 ID" to null
        if (s.length > MAX_MODEL) return "模型 ID 太长了" to null
        // it goes into the request body and the session log: a control character is a paste mistake
        if (s.any { it < ' ' || it == '\u007f' }) return "模型 ID 里有控制字符" to null
        return null to s
    }

    /** The text a [probe] sends: a Japanese fragment that is plainly unfinished, so any Jev model can answer it. */
    private const val PROBE = "これはテストです、"

    /**
     * Asks the service at [url] (already filled in, see [url]) the standard "unfinished?" question, through the same
     * client a session uses, and turns what came back into a verdict for the 测试连接 button: an HTTP status, an
     * answer that is not Jev's shape, a limit, or a connection failure. Never throws. The 429 pause is per service and
     * key, as in a session, so a limit found here is respected by the session too. The deadline is the session's own
     * ([Jev]'s 2 s): a service that only answers slower than a session's request would test as usable here and then
     * never light up in a session.
     */
    fun probe(url: String, model: String, key: String? = null, deadlineMs: Long = 2_000): JevProbe {
        val call = Jev(JevProvider.CUSTOM, key.orEmpty(), deadlineMs, url = url, model = model).unfinished(PROBE)
        val ms = "${call.ms} ms"
        return when {
            call.answer != null -> JevProbe(true, "✓ 连上了：$ms，服务按 Jev 的格式回答了（unfinished 概率 ${call.noul}）")
            call.status == 200 -> JevProbe(false, "✗ 连上了，但回答不是 Jev 的格式：检查地址是否指向 /v1/systemone、模型 ID 是不是服务端的 Jev 模型")
            call.status == 401 || call.status == 403 -> JevProbe(false, "✗ 连上了，但 key 不被接受（HTTP ${call.status}）")
            call.status == 404 -> JevProbe(false, "✗ 地址不对：服务说没有这个接口（HTTP 404）")
            call.status == 429 -> JevProbe(false, "✗ 服务限流了（HTTP 429）：等它说的恢复时间过后再试")
            // opencode.ai answers this way to some regions and origins (seen from a China-routed connection, 2026-10-18)
            call.status == 451 -> JevProbe(false, "✗ 服务拒绝了这个来源的请求（HTTP 451）：换一个服务或网络再试")
            // the answer was not readable as Jev's (the body's own words are in error only when it had any)
            call.status > 0 -> JevProbe(false, "✗ 服务返回 HTTP ${call.status}" +
                (call.error?.takeUnless { it == "HTTP ${call.status}" }?.let { "：$it" } ?: ""))
            call.error == "bad key" -> JevProbe(false, "✗ key 里有空格、换行或特殊字符，请重新复制")
            call.error == "paused" -> JevProbe(false, "✗ 这个服务之前限流，正在暂停：等它说的恢复时间，或稍后再测")
            call.error?.startsWith("deadline") == true -> JevProbe(false, "✗ 连不上：$ms 内没有回应（检查地址、端口与网络）")
            else -> JevProbe(false, "✗ 连不上：${call.error ?: "未知错误"}")
        }
    }
}

/**
 * What a channel test found: [ok] false means the channel cannot be used as it is; [detail] is one line for the user,
 * already carrying ✓ or ✗.
 */
class JevProbe(val ok: Boolean, val detail: String)
