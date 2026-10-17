package io.github.korenkrita.simulsub

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * The engines for the models [AsrModels] manages (downloaded from a model hub, or imported from the phone): they read
 * the four files of a transducer from disk, where [SherpaAsr] reads the built-in ones from the APK's assets. Both
 * answer [Asr] exactly like [SherpaAsr] — the pipeline and [Recognition]'s loss repair cannot tell them apart.
 * [ReleasableAsr] pairs the [Asr] with the native release the session must call when it ends (the core's interface
 * has no release of its own).
 */
interface ReleasableAsr : Asr {
    fun release()
}

/** A streaming (chunked) zipformer transducer on disk, run by sherpa-onnx's online recognizer. */
class StreamingSherpaAsr(encoder: String, decoder: String, joiner: String, tokens: String, threads: Int) : ReleasableAsr {
    private val rec = OnlineRecognizer(
        config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(encoder = encoder, decoder = decoder, joiner = joiner),
                tokens = tokens,
                numThreads = threads,
                modelType = "zipformer",
            ),
            // one whole (padded) utterance per call: no endpoint rules, nothing to end in the middle
            enableEndpoint = false,
            decodingMethod = "greedy_search",
        ),
    )

    override fun recognize(samples: FloatArray): String = recognizeTimed(samples).text

    override fun recognizeTimed(samples: FloatArray): Recognition {
        // The same 0.3 s of leading silence as [SherpaAsr] (the streaming models lose the first word at sample 0
        // just like the offline one) plus a full second of trailing silence: with an 16-frame decode chunk the last
        // spoken frames must land in a chunk the decoder runs at all, or the last word of a phrase can be left out.
        val head = Recognition.padded(samples)
        val padded = head.copyOf(head.size + TAIL_S.samples())
        val s = rec.createStream()
        try {
            s.acceptWaveform(padded, 16_000)
            s.inputFinished()
            while (rec.isReady(s)) rec.decode(s)
            val r = rec.getResult(s)
            val c = AsrSymbols.clean(r.text, r.tokens, r.timestamps)
            return Recognition.of(c.text, c.tokens, c.times, Recognition.PAD_S)
        } finally {
            s.release()
        }
    }

    override fun release() = rec.release()

    private companion object {
        /** trailing silence fed after [Recognition.PAD_S] of head silence, in seconds (see [recognizeTimed]) */
        const val TAIL_S = 1.0f
        fun Float.samples() = (this * 16_000).toInt()
    }
}

/** A non-streaming transducer on disk, same as [SherpaAsr] but read from files (an imported model). */
class FileTransducerAsr(encoder: String, decoder: String, joiner: String, tokens: String, threads: Int) : ReleasableAsr {
    private val rec = OfflineRecognizer(
        config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16_000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = encoder, decoder = decoder, joiner = joiner,
                ),
                tokens = tokens,
                numThreads = threads,
                modelType = "transducer",
            ),
            decodingMethod = "greedy_search",
        ),
    )

    override fun recognize(samples: FloatArray): String = recognizeTimed(samples).text

    override fun recognizeTimed(samples: FloatArray): Recognition {
        val s = rec.createStream()
        try {
            s.acceptWaveform(Recognition.padded(samples), 16_000)
            rec.decode(s)
            val r = rec.getResult(s)
            return Recognition.of(r.text, r.tokens, r.timestamps, Recognition.PAD_S)
        } finally {
            s.release()
        }
    }

    override fun release() = rec.release()
}

/** The bracketed control tokens the multilingual models emit (language tags like <ZH>, plus <blk>, <unk>…). */
internal object AsrSymbols {
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
