package io.github.korenkrita.simulsub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [JevChannel]: the custom channel's address is filled in the way BYOK's is (a base, `…/v1` or the full endpoint), and
 * both fields are checked before a session would use them.
 */
class JevChannelTest {
    /** the address [input] is filled in to; fails the test (with the reason) when it cannot be used */
    private fun ok(input: String): String {
        val (why, url) = JevChannel.url(input)
        assertEquals(input, null, why)
        return url!!
    }

    @Test
    fun theAddressIsFilledInUpToTheStandardPath() {
        // a base, a trailing slash, …/v1 and the full endpoint all mean the same address
        assertEquals("https://my.host/v1/systemone", ok("https://my.host"))
        assertEquals("https://my.host/v1/systemone", ok("https://my.host/"))
        assertEquals("https://my.host/v1/systemone", ok("https://my.host/v1"))
        assertEquals("https://my.host/v1/systemone", ok("https://my.host/v1/"))
        assertEquals("https://my.host/v1/systemone", ok(" https://my.host/v1/systemone "))
        // a prefix of the service's own (/zen, /api), a port, and the scheme and host lowercased like OkHttp will
        assertEquals("https://my.host/zen/v1/systemone", ok("https://my.host/zen/v1/systemone"))
        assertEquals("http://my.host:8443/api/v1/systemone", ok("HTTP://My.Host:8443/api/v1"))
    }

    @Test
    fun anAddressThatCannotBeCalledSaysWhy() {
        assertEquals("还没填地址", JevChannel.url("  ").first)
        assertEquals("地址要以 http:// 或 https:// 开头", JevChannel.url("my.host/v1").first)
        assertEquals("地址要以 http:// 或 https:// 开头", JevChannel.url("ftp://my.host").first)
        // java.net.URI refuses "https://" itself; no host at all is the same complaint the BYOK address gets
        assertEquals("地址格式不对", JevChannel.url("https://").first)
        assertEquals("地址里没有域名", JevChannel.url("https:///x").first)
        assertEquals("地址里不要带用户名、?参数 或 #", JevChannel.url("https://user@my.host").first)
        assertEquals("地址里不要带用户名、?参数 或 #", JevChannel.url("https://my.host/?x=1").first)
        assertEquals("端口号不对", JevChannel.url("https://my.host:99999").first)
    }

    @Test
    fun theModelIdIsTrimmedAndChecked() {
        assertEquals("jev-latest", JevChannel.model(" jev-latest ").second)
        assertEquals("还没填模型 ID", JevChannel.model("").first)
        assertEquals("还没填模型 ID", JevChannel.model("\n").first)
        assertEquals("模型 ID 里有控制字符", JevChannel.model("jev\nmine").first)
        assertEquals("模型 ID 太长了", JevChannel.model("j".repeat(201)).first)
    }
}