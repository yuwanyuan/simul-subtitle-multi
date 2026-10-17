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
}
