package io.github.korenkrita.simulsub

/**
 * The local translation engine of the PC version: llama.cpp's JVM bindings run a GGUF in this very process (the
 * desktop's LocalLlm). The core knows nothing of llama.cpp or GGUF — it only wraps this seam into an [Mt], so the
 * pipeline, the session and the tests stay the same as with the BYOK translator.
 */
interface TextGen {
    /**
     * One generation: [system] goes into the chat template's system slot, [prompt] is the user turn, [maxTokens] the
     * output cap. The GGUF carries the model's own template (the measured HY-MT1.5 builds do), so the platform asks
     * for a chat completion, not a raw continuation. Returns the generated text with leading/trailing whitespace
     * already gone; empty when the model produced nothing.
     */
    fun generate(system: String, prompt: String, maxTokens: Int): String

    /**
     * Frees the model and its context. The platform's business: the desktop keeps one loaded engine across sessions
     * (loading the 1.8B GGUF takes ~1.3 s), so a session must not close an engine it did not open.
     */
    fun close()
}

/**
 * Local (offline) translation: a [TextGen] wrapped into the [Mt] the pipeline calls.
 *
 * HY-MT1.5 is a translation model, not a chat assistant, and it only answers its own prompt forms (the model card's
 * 通用 / 术语 / 上下文 templates below). Measured on this machine: sent the [byokSystem]/[byokUser] pair the online
 * services answer well — 你是直播字幕翻译…【当前句】… — it echoed "【当前句】" back as the translation, while the same
 * three sentences translated correctly through the templates here. So [prompt] builds those and no system message is
 * sent at all ([prompt] reaches [TextGen.generate] with an empty system; the card says the model has no default one).
 *
 * [target] picks the output cap ([mtMaxTokens], the same 160/256 the BYOK requests use) and the language named in the
 * template; [scene] supplies the background and the terms, which the card's 上下文 and 术语 forms take.
 */
class LocalMt(private val gen: TextGen, private val scene: Scene, private val target: Target) : Mt {
    private val maxTokens = mtMaxTokens(target)

    /**
     * The model card's forms, in the order it lists them: one 参考下面的翻译 line per term, then the context
     * (background and the finished sentences the app hands over), then the sentence itself. 注意 notes have no form of
     * their own — they ride in front of the instruction, the place the card puts extra information.
     */
    override fun prompt(text: String, notes: List<String>, previous: List<String>): String = buildString {
        for ((term, translation) in scene.termsFor(target)) {
            append("参考下面的翻译： $term 翻译成 $translation ")
        }
        notes.forEach { append("$it ") }
        val context = buildList {
            scene.background?.takeIf { it.isNotBlank() }?.let { add(it) }
            addAll(previous)
        }
        if (context.isEmpty()) {
            append("将以下文本翻译为${target.zh}，注意只需要输出翻译后的结果，不要额外解释： $text")
        } else {
            // the card's 上下文 form translates only the sentence, so the 【前文】 never lands in the captions
            append(context.joinToString("\n"))
            append(" 参考上面的信息，把下面的文本翻译成${target.zh}，注意不需要翻译上文，也不要额外解释： $text")
        }
    }

    /** The whole answer is one caption. Like BYOK, the request carries no timings. */
    override fun translate(prompt: String): MtResult? {
        val text = gen.generate("", prompt, maxTokens)
        // 'l' (cut at the cap) cannot be told from 'e' here: the binding reports no finish reason. Both are kept —
        // an answer stopped at the cap is still mostly a translation, as BYOK keeps a length cut.
        return MtResult(text, if (text.isEmpty()) 'f' else 'e', null, null)
    }

    /**
     * In-process inference cannot be interrupted (llama.cpp has no mid-generation cancel), so there is nothing to do
     * here; the generation runs to its cap either way. A session that stopped meanwhile still drops the sentence: it
     * checks [Pipeline]'s running flag before showing any late result, and the session thread waits for this call to
     * return before freeing anything.
     */
    override fun cancel() {}
}
