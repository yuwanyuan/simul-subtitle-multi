package io.github.korenkrita.simulsub

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure pieces of the speech-model page: [AsrSymbols]' tag cleaning (the multilingual models' language tags must
 * not break [Recognition.of]'s token alignment) and the custom-model meta's JSON round trip.
 */
class AsrModelPiecesTest {
    @Test fun `clean keeps a clean input as is`() {
        val c = AsrSymbols.clean("こんばんは", arrayOf("▁", "こん", "ばんは"), floatArrayOf(0.3f, 0.4f, 0.9f))
        assertEquals("こんばんは", c.text)
        assertArrayEquals(arrayOf("▁", "こん", "ばんは"), c.tokens)
        assertArrayEquals(floatArrayOf(0.3f, 0.4f, 0.9f), c.times, 0f)
    }

    @Test fun `clean drops language tags from tokens and times together`() {
        // what the online engine can hand out: text without the tags, tokens with them
        val c = AsrSymbols.clean("你好世界", arrayOf("<ZH>", "你", "好", "世", "界"),
            floatArrayOf(0.31f, 0.32f, 0.5f, 0.7f, 0.9f))
        assertEquals("你好世界", c.text)
        assertArrayEquals(arrayOf("你", "好", "世", "界"), c.tokens)
        assertArrayEquals(floatArrayOf(0.32f, 0.5f, 0.7f, 0.9f), c.times, 0f)
    }

    @Test fun `clean also strips tag-like text and control tokens`() {
        val c = AsrSymbols.clean("a<EN>b", arrayOf("a", "<EN>", "b"), floatArrayOf(0.1f, 0.2f, 0.3f))
        assertEquals("ab", c.text)
        assertArrayEquals(arrayOf("a", "b"), c.tokens)
    }

    @Test fun `plain words are not tags`() {
        assertFalse(AsrSymbols.isSymbol("▁hello"))
        assertFalse(AsrSymbols.isSymbol("德"))
        assertTrue(AsrSymbols.isSymbol("<ZH>"))
    }

    @Test fun `cleaned text still aligns through Recognition like the built-in model's`() {
        // byte-level BPE tokens over 今日, with a language tag in front — exactly what the online engine can hand out
        val r = AsrSymbols.clean("今日", arrayOf("<ZH>", "▁ab", "c", "▁def"), floatArrayOf(0.31f, 0.35f, 0.42f, 0.5f))
        assertEquals(listOf("▁ab", "c", "▁def"), r.tokens.toList())
        val rec = Recognition.of(r.text, r.tokens, r.times, Recognition.PAD_S)
        assertEquals(listOf("今", "日"), rec.pieces!!.map { it.text })
    }

    @Test fun `custom model meta round trips`() {
        val mine = AsrModels.CustomModel(AsrModels.KIND_OFFLINE,
            linkedMapOf("tokens" to "tokens.txt", "encoder" to "my_encoder.onnx",
                "decoder" to "my_decoder.onnx", "joiner" to "my_joiner.onnx"))
        val back = AsrModels.CustomModel.parse(mine.json())
        assertEquals(AsrModels.KIND_OFFLINE, back?.kind)
        assertEquals("my_encoder.onnx", back?.files?.get("encoder"))
    }

    @Test fun `built-in def is the offline ReazonSpeech, downloadable like the rest`() {
        val def = AsrModels.def(AsrModels.BUILTIN)!!
        assertFalse(def.streaming) // the original engine's kind: OfflineRecognizer
        assertEquals(listOf("encoder", "decoder", "joiner", "tokens"),
            def.files.map { AsrModels.roleOf(it.local) })
        assertEquals(77_101_541L, def.sizeBytes) // what setup.sh used to ship in the APK
    }

    @Test fun `custom model meta of nonsense is null, not a crash`() {
        assertNull(AsrModels.CustomModel.parse(null))
        assertNull(AsrModels.CustomModel.parse(""))
        assertNull(AsrModels.CustomModel.parse("not json"))
    }

    @Test fun `import roles follow sherpa-onnx file names`() {
        assertEquals("encoder", AsrModels.roleOf("encoder-epoch-75-avg-11-chunk-16-left-128.int8.onnx"))
        assertEquals("decoder", AsrModels.roleOf("decoder.onnx"))
        assertEquals("joiner", AsrModels.roleOf("JOINER-epoch-1.int8.onnx"))
        assertEquals("tokens", AsrModels.roleOf("tokens.txt"))
        assertNull(AsrModels.roleOf("bpe.model"))
        assertNull(AsrModels.roleOf("README.md"))
    }
}
