package io.github.korenkrita.simulsub

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [LlmModels]: the catalog data, the readiness check the page and the session's start gate share, the selection's
 * fallback after a delete, and a download against a stand-in mirror on loopback (a real 440 MB download has no place
 * in a unit test — what is locked in here is the check order, the selection and the user-facing messages).
 */
class LlmModelsTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakePrefs : LlmModels.LlmPrefs {
        override var selected: String = ""
    }

    private val prefs = FakePrefs()
    private val servers = mutableListOf<HttpServer>()

    private fun install() = LlmModels.install(tmp.newFolder("models"), prefs, { it() }, { _, _ -> }, "test")

    @After fun stopServers() { servers.forEach { runCatching { it.stop(0) } } }

    /** A stand-in for the model mirror on loopback: [handler] answers every path. @return the base URL */
    private fun serve(handler: (HttpExchange) -> Unit): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/") { ex -> try { handler(ex) } finally { ex.close() } }
        s.start()
        servers += s
        return "http://127.0.0.1:${s.address.port}"
    }

    /** 10 bytes: a model's file in miniature, so the size and hash checks run for real without the 440 MB */
    private val body = "0123456789".toByteArray()
    private val sum = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }

    private fun def(url: String, bytes: Long = body.size.toLong(), sha256: String = sum) =
        LlmModels.Def("test-model", "测试模型（10 字节）", "测试 · 10 字节", "test.gguf", listOf(url), bytes, sha256)

    private fun serveBody() = serve { ex -> ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) } }

    private fun download(d: LlmModels.Def): Boolean {
        val done = CountDownLatch(1)
        LlmModels.startDownload(d) { done.countDown() }
        return done.await(10, TimeUnit.SECONDS)
    }

    @Test fun `the catalog names the two hunyuan models, mirror first and pinned checks`() {
        assertEquals(listOf("hy-mt15-18b-iq2m", "hy-mt15-18b-q4km"), LlmModels.CATALOG.map { it.id })
        val small = LlmModels.def("hy-mt15-18b-iq2m")!!
        assertEquals(698_483_424, small.bytes)
        assertEquals("49506eb0c6591afaaaa885f664e216c3c582016a3f692e02e7ce2c8bc17d0311", small.sha256)
        assertTrue(small.preferred)
        val q4 = LlmModels.def("hy-mt15-18b-q4km")!!
        assertEquals(1_133_080_512, q4.bytes)
        assertEquals("4383ac0c3c8e476de98ff979c2a3f069f8c4fb385e7860cf2d28da896cc477c7", q4.sha256)
        assertFalse(q4.preferred)
        for (d in LlmModels.CATALOG) {
            // both entries name the same file on the two hosts, the mirror first: plain huggingface.co does not answer
            // from this region (the same order AsrModels' own list uses)
            assertEquals(2, d.urls.size)
            assertEquals(d.urls[0].removePrefix("https://hf-mirror.com"), d.urls[1].removePrefix("https://huggingface.co"))
            assertTrue(d.urls[0].endsWith(d.file))
            assertTrue(d.file.endsWith(".gguf"))
        }
    }

    @Test fun `ready compares the file's byte count`() {
        install()
        val d = LlmModels.CATALOG.first()
        assertFalse(LlmModels.ready(d))
        assertFalse(LlmModels.anyReady())
        assertEquals(File(LlmModels.root, "${d.id}/${d.file}"), LlmModels.fileOf(d.id))
        // a sparse file of the right length is what a finished download leaves
        RandomAccessFile(LlmModels.fileOf(d.id).apply { parentFile.mkdirs() }, "rw").use { it.setLength(d.bytes) }
        assertTrue(LlmModels.ready(d))
        assertTrue(LlmModels.anyReady())
        RandomAccessFile(LlmModels.fileOf(d.id), "rw").use { it.setLength(d.bytes - 1) }
        assertFalse(LlmModels.ready(d)) // one byte short is not the file the catalog names
    }

    @Test fun `deleting the selected model falls back to a ready one, else clears the selection`() {
        install()
        val a = LlmModels.CATALOG[0]; val b = LlmModels.CATALOG[1]
        for (d in listOf(a, b)) RandomAccessFile(LlmModels.fileOf(d.id).apply { parentFile.mkdirs() }, "rw").use { it.setLength(d.bytes) }
        LlmModels.select(b.id)
        LlmModels.delete(b.id)
        assertFalse(File(LlmModels.root, b.id).exists())
        assertEquals(a.id, LlmModels.selected()) // the other one is right there: keep the captions startable
        LlmModels.delete(a.id)
        assertEquals("", LlmModels.selected()) // nothing left: no selection, the session gate says what to do
        assertFalse(LlmModels.anyReady())
    }

    @Test fun `a redirecting download lands as the model's file and puts it in use`() {
        val base = serve { ex ->
            if (ex.requestURI.path == "/model.gguf") {
                ex.responseHeaders.add("Location", "/real.gguf"); ex.sendResponseHeaders(302, -1)
            } else {
                ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
            }
        }
        install()
        val d = def("$base/model.gguf")
        assertTrue(download(d))
        assertTrue(LlmModels.ready(d))
        assertEquals(d.id, LlmModels.selected())
        assertNull(LlmModels.lastError)
        assertNull(LlmModels.downloading)
        assertFalse(File(LlmModels.root, "${d.id}/${d.file}.part").exists())
    }

    @Test fun `a download the server refuses says so and leaves nothing behind`() {
        val base = serve { ex -> ex.sendResponseHeaders(404, -1) }
        install()
        val d = def("$base/model.gguf")
        assertTrue(download(d))
        assertEquals("「${d.label}」下载失败：下载地址返回 404，请稍后再试", LlmModels.lastError)
        assertEquals("", LlmModels.selected())
        assertFalse(LlmModels.ready(d))
        assertFalse(File(LlmModels.root, "${d.id}/${d.file}").exists())
        assertFalse(File(LlmModels.root, "${d.id}/${d.file}.part").exists())
    }

    @Test fun `a short answer is refused as incomplete`() {
        val base = serve { ex -> ex.sendResponseHeaders(200, 5); ex.responseBody.use { it.write(body, 0, 5) } }
        install()
        val d = def("$base/model.gguf") // the catalog claims 10 bytes, the server sends 5
        assertTrue(download(d))
        assertEquals("「${d.label}」下载失败：下载不完整（5 / 10 字节），请重试", LlmModels.lastError)
        assertFalse(LlmModels.ready(d))
    }

    @Test fun `a file that does not hash as the catalog says is refused`() {
        val base = serveBody()
        install()
        val d = def("$base/model.gguf", sha256 = "0".repeat(64))
        assertTrue(download(d))
        assertEquals("「${d.label}」下载失败：文件校验不对（SHA-256），请重试；一直失败的话可能是网络被干扰", LlmModels.lastError)
        assertEquals("", LlmModels.selected())
        assertFalse(LlmModels.ready(d))
    }
}
