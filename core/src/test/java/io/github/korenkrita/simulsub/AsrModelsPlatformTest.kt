package io.github.korenkrita.simulsub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/**
 * [AsrModels]' platform seams, the part the phone and the desktop fill differently: [AsrModels.AsrPrefs] holds the
 * selection and the custom meta, [AsrModels.builtinDir] says whether the built-in model ships with the application
 * (the desktop packs it: no download, [AsrModels.ready] never looks at the files, [AsrModels.delete] cannot take it
 * away) and [AsrModels.engineFactory] builds the engine from the paths this object picked.
 */
class AsrModelsPlatformTest {
    @get:Rule val tmp = TemporaryFolder()

    private class FakePrefs : AsrModels.AsrPrefs {
        override var selected: String = AsrModels.BUILTIN
        override var custom: String? = null
    }

    private class FakeAsr(val files: List<String>, val streaming: Boolean) : ReleasableAsr {
        var released = false
        override fun recognize(samples: FloatArray) = ""
        override fun release() { released = true }
    }

    private fun install(root: File, prefs: FakePrefs) =
        AsrModels.install(root, prefs, { it() }, { _, _ -> }, "test")

    @Test fun `a shipped builtin is never downloaded and is read from the packed paths`() {
        val pack = tmp.newFolder("models")
        var made: FakeAsr? = null
        AsrModels.engineFactory = { files, streaming, _ -> FakeAsr(files, streaming).also { made = it } }
        AsrModels.builtinDir = pack
        try {
            install(tmp.newFolder("data"), FakePrefs())
            val builtin = AsrModels.def(AsrModels.BUILTIN)!!
            assertTrue(AsrModels.shipped(builtin))
            assertTrue(AsrModels.ready(builtin)) // nothing is on disk and it is still usable
            val asr = AsrModels.createAsr(2)
            assertEquals(listOf("asr/encoder.int8.onnx", "asr/decoder.onnx", "asr/joiner.int8.onnx", "asr/tokens.txt")
                .map { File(pack, it).absolutePath }, made!!.files)
            assertFalse(made!!.streaming) // the packed model is the offline ReazonSpeech
            asr.release()
            assertTrue(made!!.released)
            AsrModels.delete(AsrModels.BUILTIN) // the packed files are the installation's, not a download
            assertTrue(AsrModels.ready(builtin))
            assertEquals(AsrModels.BUILTIN, AsrModels.selected())
        } finally {
            AsrModels.builtinDir = null
            AsrModels.engineFactory = null
        }
    }

    @Test fun `without a packed builtin it is an ordinary download and deleting it falls back`() {
        install(tmp.newFolder("data"), FakePrefs())
        AsrModels.builtinDir = null
        val builtin = AsrModels.def(AsrModels.BUILTIN)!!
        assertFalse(AsrModels.shipped(builtin))
        assertFalse(AsrModels.ready(builtin)) // no files yet
        // what ready() compares is the byte count: sparse files of the right length are what a download leaves
        val dir = AsrModels.dir(builtin.id).apply { mkdirs() }
        builtin.files.forEach { RandomAccessFile(File(dir, it.local), "rw").use { f -> f.setLength(it.bytes) } }
        assertTrue(AsrModels.ready(builtin))
        RandomAccessFile(File(dir, builtin.files[0].local), "rw").use { it.setLength(builtin.files[0].bytes - 1) }
        assertFalse(AsrModels.ready(builtin)) // one byte short is not the file the definition names
        AsrModels.select(builtin.id)
        AsrModels.delete(builtin.id)
        assertFalse(dir.exists())
        assertEquals(AsrModels.BUILTIN, AsrModels.selected())
    }

    @Test fun `the selection and the custom meta go through the platform's AsrPrefs`() {
        val prefs = FakePrefs()
        install(tmp.newFolder("data"), prefs)
        AsrModels.select("hf-kroko-de")
        assertEquals("hf-kroko-de", prefs.selected)
        assertEquals("hf-kroko-de", AsrModels.selected())
        AsrModels.saveCustom(AsrModels.CustomModel(AsrModels.KIND_STREAMING,
            linkedMapOf("encoder" to "encoder.onnx", "decoder" to "decoder.onnx")))
        assertEquals(AsrModels.KIND_STREAMING, AsrModels.CustomModel.parse(prefs.custom)?.kind)
        assertEquals("encoder.onnx", AsrModels.custom()?.files?.get("encoder"))
        AsrModels.saveCustom(null)
        assertNull(prefs.custom)
        assertNull(AsrModels.custom())
    }
}
