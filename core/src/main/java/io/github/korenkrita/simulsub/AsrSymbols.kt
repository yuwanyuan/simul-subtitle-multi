package io.github.korenkrita.simulsub

/**
 * The bracketed control tokens the multilingual models emit (language tags like <ZH>, plus <blk>, <unk>…). Shared
 * with the engines: the phone's own (app/AsrEngines.kt) and the desktop's both clean their recognizer output here,
 * so a streaming and an offline engine agree on what a token is.
 */
object AsrSymbols {
    private val TOKEN = Regex("^</?[A-Za-z0-9_]+>$")
    private val IN_TEXT = Regex("</?[A-Za-z0-9_]+>")

    fun isSymbol(token: String): Boolean = token.matches(TOKEN)

    /**
     * sherpa-onnx keeps these tags out of the text but (for the online engine) may leave them in the token list,
     * which would make [Recognition.of] give up on the timings. Both sides are cleaned the same way, so whatever
     * sherpa-onnx did the arrays stay aligned with the text.
     */
    fun clean(text: String, tokens: Array<String>, times: FloatArray): Cleaned {
        val keep = tokens.indices.filter { !tokens[it].matches(TOKEN) }
        if (keep.size == tokens.size && !IN_TEXT.containsMatchIn(text)) return Cleaned(text, tokens, times)
        val keepTimes = FloatArray(keep.size) { times[keep[it]] }
        return Cleaned(IN_TEXT.replace(text, ""), keep.map { tokens[it] }.toTypedArray(), keepTimes)
    }

    class Cleaned(val text: String, val tokens: Array<String>, val times: FloatArray)
}
