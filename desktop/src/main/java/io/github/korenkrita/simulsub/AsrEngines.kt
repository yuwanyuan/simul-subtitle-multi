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
 * The engines for the models [AsrModels] manages — the built-in, downloaded and imported ones alike: each reads the
 * four files of a transducer from disk (the phone's own pair, app/AsrEngines.kt, is the same behavior through the
 * AAR's constructor API). Both answer [Asr] exactly, so the pipeline and [Recognition]'s loss repair cannot tell them
 * apart. [ReleasableAsr] pairs the [Asr] with the native release the session must call when it ends (the core's
 * interface has no release of its own).
 */
class ZipformerStreamingAsr(encoder: String, decoder: String, joiner: String, tokens: String, threads: Int) : ReleasableAsr {
    private val rec = OnlineRecognizer(OnlineRecognizerConfig.builder()
        .setFeatureConfig(FeatureConfig.builder().setSampleRate(16_000).setFeatureDim(80).build())
        .setOnlineModelConfig(OnlineModelConfig.builder()
            .setTransducer(OnlineTransducerModelConfig.builder()
                .setEncoder(encoder).setDecoder(decoder).setJoiner(joiner).build())
            .setTokens(tokens)
            .setNumThreads(threads)
            // Must stay empty: sherpa-onnx 1.13 dispatches on this string and "zipformer" forces the 1st-gen runner,
            // which _Exit()s the process for the 2nd-gen models (metadata key mismatch, no Java exception — the empty
            // string reads model_type from the ONNX metadata instead, see the tombstone of the "caption overlay
            // vanishes" report).
            .setModelType("")
            .build())
        // one whole (padded) utterance per call: no endpoint rules, nothing to end in the middle
        .setEnableEndpoint(false)
        .setDecodingMethod("greedy_search")
        .build())

    override fun recognize(samples: FloatArray): String = recognizeTimed(samples).text

    override fun recognizeTimed(samples: FloatArray): Recognition {
        // The same 0.3 s of leading silence as [FileTransducerAsr] (the streaming models lose the first word at sample
        // 0 just like the offline one) plus a full second of trailing silence: with a 16-frame decode chunk the last
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

/** A non-streaming transducer on disk: the shipped built-in model and every offline (imported or downloaded) one. */
class FileTransducerAsr(encoder: String, decoder: String, joiner: String, tokens: String, threads: Int) : ReleasableAsr {
    private val rec = OfflineRecognizer(OfflineRecognizerConfig.builder().setOfflineModelConfig(OfflineModelConfig.builder()
        .setTransducer(OfflineTransducerModelConfig.builder()
            .setEncoder(encoder).setDecoder(decoder).setJoiner(joiner).build())
        .setTokens(tokens)
        .setNumThreads(threads)
        // empty = auto-detect from the ONNX metadata, same reason as [ZipformerStreamingAsr] ("transducer" is not an
        // identity sherpa-onnx knows either — ReazonSpeech only ran because its own metadata matched what the
        // dispatcher guessed; a built-for-a-different-generation file killed the process)
        .setModelType("")
        .build())
        .setDecodingMethod("greedy_search").build())

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
