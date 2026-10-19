package io.github.korenkrita.simulsub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AsrCatalog]: the manifest the Gitee/GitHub copy serves parses into [AsrModelDef]s, and the frozen v1 copy in the
 * test resources still matches [AsrModels.BUILTIN_CATALOG] entry for entry — which is what keeps a model already
 * downloaded downloaded ([AsrModels.ready] compares id, file names and byte counts, never a URL).
 */
class AsrCatalogTest {
    private fun hosted(): String = checkNotNull(javaClass.getResourceAsStream("/models-hosted-v1.json")).use {
        it.readBytes().toString(Charsets.UTF_8)
    }

    @Test fun `the hosted v1 manifest still matches the built-in definitions file for file`() {
        val parsed = AsrCatalog.parse(hosted())
        assertEquals("2026-10-19", parsed.revision)
        val builtins = AsrModels.BUILTIN_CATALOG.associateBy { it.id }
        assertEquals(builtins.keys, parsed.models.map { it.id }.toSet())
        assertTrue(parsed.models.first { it.id == "builtin" }.preferred) // the row's 首选 mark, from the file
        for (m in parsed.models) {
            val b = builtins.getValue(m.id)
            assertEquals(b.label, m.label)
            assertEquals(b.summary, m.summary)
            assertEquals(b.streaming, m.streaming)
            assertEquals(b.origin, m.origin)
            assertEquals(b.preferred, m.preferred)
            // what ready() compares: unchanged ids, file names and byte counts mean nothing is re-downloaded
            assertEquals(b.files.map { it.local }, m.files.map { it.local })
            assertEquals(b.files.map { it.bytes }, m.files.map { it.bytes })
            // the manifest may add a hash the code never had (Kroko): every fetched file is verified, none weaker
            for ((bf, mf) in b.files.zip(m.files)) if (bf.sha256 != null) assertEquals(bf.sha256, mf.sha256)
            assertTrue(m.files.all { it.sha256 != null })
        }
    }

    @Test fun `the file's order is the page's order, the code's builtin is the safety net`() {
        val builtins = AsrModels.BUILTIN_CATALOG
        // the file is the truth, order included…
        val withBuiltin = AsrCatalog.parse(hosted()).models
        assertEquals(withBuiltin.map { it.id }, AsrCatalog.merge(builtins, withBuiltin).map { it.id })
        // …its builtin entry keeps the code's wording out, and when the file has none it is appended
        val custom = AsrCatalog.parse(manifest(model(id = "builtin", label = "我叫它别的"))).models
        assertEquals(listOf("我叫它别的"), AsrCatalog.merge(builtins, custom).map { it.label })
        val withoutBuiltin = withBuiltin.filter { it.id != "builtin" }.reversed()
        assertEquals(withoutBuiltin.map { it.id } + "builtin", AsrCatalog.merge(builtins, withoutBuiltin).map { it.id })
        // nothing fetched: the code's own list, untouched
        assertTrue(AsrCatalog.merge(builtins, null) === builtins)
    }

    @Test fun `files are reordered to the engine's roles wherever the file lists them`() {
        val jumbled = """
            {"local":"tokens.txt","bytes":1,"sha256":"${"0".repeat(64)}","urls":["https://x/t"]},
            {"local":"joiner.onnx","bytes":2,"sha256":"${"A".repeat(64)}","urls":["https://x/j"]},
            {"local":"decoder.onnx","bytes":3,"sha256":"${"0".repeat(64)}","urls":["https://x/d"]},
            {"local":"encoder-epoch-1.int8.onnx","bytes":4,"sha256":"${"0".repeat(64)}","urls":["https://x/e"]}
        """.trimIndent()
        val d = AsrCatalog.parse("""{"schemaVersion":1,"models":[{"id":"m","label":"模型","streaming":true,"files":[$jumbled]}]}""").models.single()
        assertEquals(listOf("encoder", "decoder", "joiner", "tokens"), d.files.map { AsrModels.roleOf(it.local) })
        assertEquals(listOf(4L, 3L, 2L, 1L), d.files.map { it.bytes })
        assertEquals("a".repeat(64), d.files[2].sha256) // an uppercase hash is accepted, lowercased for comparison
        assertEquals(listOf("https://x/j"), d.files[2].urls)
    }

    @Test fun `bad manifests are dropped whole with a readable reason`() {
        val cases = listOf(
            """{"schemaVersion":2,"revision":"x","models":[${model()}]}""" to "格式版本",
            manifest(model(id = "bad/id")) to "id",
            manifest(model(id = "custom")) to "本地导入",
            manifest(model(sha = null)) to "SHA-256",
            manifest(model(url = "http://x.test/")) to "https",
            manifest(model(names = listOf("encoder.onnx", "decoder.onnx", "joiner.onnx"))) to "四个文件",
            manifest(model(bytes = 0)) to "字节数",
            manifest(model() + "," + model()) to "两次",
            """{"schemaVersion":1,"models":[]}""" to "1–50",
            "not json" to "清单格式不对",
        )
        for ((text, needle) in cases) {
            val e = assertThrows(IllegalArgumentException::class.java) { AsrCatalog.parse(text) }
            assertTrue("$needle 应在「${e.message}」里", e.message!!.contains(needle))
        }
    }

    @Test fun `a repeated role is rejected`() {
        val names = listOf("encoder-1.onnx", "encoder-2.onnx", "joiner.onnx", "tokens.txt")
        val e = assertThrows(IllegalArgumentException::class.java) { AsrCatalog.parse(manifest(model(names = names))) }
        assertTrue(e.message!!, e.message!!.contains("两次"))
    }

    /** one model object; every part can be broken on purpose */
    private fun model(
        id: String = "m-1",
        label: String = "测试模型",
        streaming: Boolean = true,
        names: List<String> = listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt"),
        bytes: Long = 1000,
        sha: String? = "0".repeat(64),
        url: String = "https://example.test/",
    ): String {
        val files = names.joinToString(",") { n ->
            """{"local":"$n","bytes":$bytes,"sha256":${if (sha == null) "null" else "\"$sha\""},"urls":["$url$n"]}"""
        }
        return """{"id":"$id","label":"$label","summary":"测试用的条目","streaming":$streaming,"origin":"me/test","files":[$files]}"""
    }

    private fun manifest(vararg models: String) =
        """{"schemaVersion":1,"revision":"2026-10-19","models":[${models.joinToString(",")}]}"""
}
