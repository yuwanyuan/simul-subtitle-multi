package io.github.korenkrita.simulsub

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/** Silero VAD for per-frame speech probability only; [Segmenter] cuts. */
class SileroProb(root: File) {
    private val vad = Vad(VadModelConfig.builder().setSileroVadModelConfig(SileroVadModelConfig.builder()
        .setModel(File(root, "vad/silero_vad.onnx").path).setWindowSize(512).build()).setSampleRate(16_000).setNumThreads(1).build())
    fun prob(frame: FloatArray): Float = vad.compute(frame)
    fun release() = vad.release()
}
