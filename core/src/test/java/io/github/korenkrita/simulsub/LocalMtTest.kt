package io.github.korenkrita.simulsub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [LocalMt]: the prompt one sentence turns into (the model's own forms), and how its answer becomes an [MtResult]. */
class LocalMtTest {
    /** Records how it was asked; answers exactly [answer] (the real engine's text, whitespace and all). */
    private class FakeGen(private val answer: String) : TextGen {
        var system: String? = null
        var prompt: String? = null
        var maxTokens = -1
        var closed = false
        override fun generate(system: String, prompt: String, maxTokens: Int): String {
            this.system = system; this.prompt = prompt; this.maxTokens = maxTokens
            return answer
        }
        override fun close() { closed = true }
    }

    private val scene = Scene.GENERAL

    @Test fun `no system prompt reaches the engine and the cap is the BYOK one`() {
        val gen = FakeGen("译文")
        val r = LocalMt(gen, scene, Target.ZH).translate("将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释： こんにちは")!!
        assertEquals("", gen.system) // measured: the model answers its own forms, a chat system prompt derails it
        assertEquals(160, gen.maxTokens) // mtMaxTokens(中文): the very cap the BYOK requests send for this target
        assertEquals("译文", r.text)
        assertTrue(r.usable)
        assertEquals('e', r.stop)
    }

    @Test fun `a sentence without context uses the card's plain form`() {
        val gen = FakeGen("x")
        val mt = LocalMt(gen, scene, Target.ZH)
        val p = mt.prompt("こんにちは", emptyList(), emptyList())
        assertEquals("将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释： こんにちは", p)
        mt.translate(p)
        assertEquals(p, gen.prompt) // what prompt() built is what the engine is asked
    }

    @Test fun `previous sentences use the card's context form, which never translates them`() {
        val p = LocalMt(FakeGen("x"), scene, Target.ZH).prompt("こんにちは", emptyList(), listOf("おはよう。", "元気ですか。"))
        assertEquals("おはよう。\n元気ですか。 参考上面的信息，把下面的文本翻译成中文，注意不需要翻译上文，也不要额外解释： こんにちは", p)
    }

    @Test fun `scene terms use the card's terminology form`() {
        val withTerms = Scene("t", "测试", null, listOf("推し" to "推", "配信" to "直播"))
        val p = LocalMt(FakeGen("x"), withTerms, Target.ZH).prompt("配信を始めます", emptyList(), emptyList())
        assertEquals(
            "参考下面的翻译： 推し 翻译成 推 参考下面的翻译： 配信 翻译成 直播 " +
                "将以下文本翻译为中文，注意只需要输出翻译后的结果，不要额外解释： 配信を始めます", p)
    }

    @Test fun `another target language gets the wider cap and its own name`() {
        val gen = FakeGen("hello")
        val mt = LocalMt(gen, scene, Target.of(Lang.EN))
        val p = mt.prompt("こんにちは", emptyList(), emptyList())
        assertTrue(p.endsWith("： こんにちは"))
        assertTrue(p.contains("翻译为英语"))
        mt.translate(p)
        assertEquals(256, gen.maxTokens) // mtMaxTokens: everything but Chinese, per BYOK's own measurement note
    }

    @Test fun `surrounding whitespace does not reach the caption`() {
        val r = LocalMt(FakeGen("  译文 \n"), scene, Target.ZH).translate("x")!!
        assertEquals("译文", r.text)
        assertTrue(r.usable)
        // raw stays the engine's own text: the session log keeps the unedited answer, as it does for BYOK
        assertEquals("  译文 \n", r.raw)
    }

    @Test fun `an empty answer is not a caption`() {
        val r = LocalMt(FakeGen(""), scene, Target.ZH).translate("x")!!
        assertFalse(r.usable)
        assertTrue(r.text.isEmpty())
    }

    @Test fun `cancel does not touch the engine and the next sentence still runs`() {
        val gen = FakeGen("译文")
        val mt = LocalMt(gen, scene, Target.ZH)
        mt.cancel()
        assertFalse(gen.closed) // the engine outlives a cancel; closing it is the platform's own business
        assertEquals("译文", mt.translate("x")!!.text)
    }

    @Test fun `every other translator keeps the BYOK prompt`() {
        // the Pipeline asks mt.prompt() for every backend now: the default must stay the online prompt verbatim
        val mt = object : Mt {
            override fun translate(prompt: String) = null
            override fun cancel() {}
        }
        assertEquals(byokUser("こんにちは", listOf("术语"), listOf("前文。")),
            mt.prompt("こんにちは", listOf("术语"), listOf("前文。")))
    }
}
