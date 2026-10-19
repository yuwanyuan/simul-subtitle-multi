package io.github.korenkrita.simulsub

import net.ladenthin.llama.LlamaModel
import net.ladenthin.llama.parameters.ChatRequest
import net.ladenthin.llama.parameters.ModelParameters
import java.io.File

/** The same tag Session.kt logs under, so one grep finds the app's own messages. */
private const val TAG = "jpsub"

/**
 * The PC's local translation engine: llama.cpp's JVM bindings (`net.ladenthin:llama`) run the GGUF [LlmModels] manages
 * in this very process — no API, no key, no network. Measured on this machine (JDK 17, Windows, the 1.25bit file): 1.3
 * s to load, ~34 tok/s to translate. It is the [TextGen] the core asks for (a session wraps it into [LocalMt] exactly
 * as it wraps the BYOK translator), handed over by [Prefs.localGen] while 本地翻译 is on.
 *
 * One engine per process, keyed by the model file's path: the first [current] with a model selected and downloaded
 * loads it, and it stays loaded across sessions — the load is the part worth keeping. A selection that points at
 * another file (the user switched models, or downloaded one again) frees the old GGUF before loading the new one.
 * [current] answers null — it never throws — when there is nothing to load or the load itself failed; a session reads
 * that null as "translate through the online LLM API".
 *
 * The artifact carries prebuilt llama.cpp libraries for every platform (Windows x86_64, macOS arm64, Linux, Android)
 * and unpacks the one for this machine into the temp directory at load. Loading it inside this process was verified on
 * this machine (JDK 17, Windows 11) with no conflict. Should a future packaged build ever crash in msvcp140.dll /
 * vcruntime140.dll (the JRE's copy of the MSVC runtime and the library's in one process), the fix belongs to packaging
 * and is not made here: delete those DLLs from the bundled JRE's bin, so only the binding's copy resolves.
 */
class LocalLlm private constructor(
    /** the GGUF this engine was loaded from; a different path is what makes [current] build another engine */
    private val path: File,
    private val model: LlamaModel,
) : TextGen {

    /**
     * [prompt] is what the core's translator built for this model — [LocalMt] sends the model card's own translation
     * forms, because measured here the Hunyuan MT model answered the online 【前文】/【当前句】 prompt by echoing
     * "【当前句】" back. [system] is therefore empty in practice; a non-blank one still goes into the chat template's
     * system slot, for a future engine that wants it. Sampling is the model card's (temperature 0.7, top-k 20, top-p
     * 0.6), the answer is cut at [maxTokens], and the whole answer is one caption — like BYOK, no timings are asked
     * for.
     */
    override fun generate(system: String, prompt: String, maxTokens: Int): String {
        // ChatRequest is immutable: every appendMessage/with… returns a copy, so each step keeps the returned one (an
        // ignored return sends the model an empty request — measured: the model then rambles as if asked nothing)
        var req = ChatRequest.empty()
        if (system.isNotBlank()) req = req.appendMessage("system", system)
        req = req.appendMessage("user", prompt)
        req = req.withInferenceCustomizer { ip ->
            ip.withTemperature(0.7f).withTopK(20).withTopP(0.6f).withNPredict(maxTokens)
        }
        val t0 = System.nanoTime()
        val r = model.chatBatch(listOf(req)).firstOrNull()
        val text = r?.getFirstContent()?.trim().orEmpty()
        val ms = (System.nanoTime() - t0) / 1_000_000
        // what a slow machine shows up as in the log: the answer's token count and the wall time it took
        val t = r?.getTimings()
        logInfo("本地翻译：${path.name} 生成 ${t?.predictedN ?: 0} tok / $ms ms" +
            (t?.takeIf { it.predictedPerSecond > 0 }?.let { "（%.1f tok/s）".format(it.predictedPerSecond) } ?: ""))
        return text
    }

    /**
     * Frees the GGUF and its context. The desktop's own business, not a session's (the session's [TextGen] may not
     * close an engine it did not open): [release] calls it when the selection moves, and the engine drops out of the
     * cache so the next [current] loads again.
     */
    override fun close() {
        synchronized(lock) { if (engine === this) engine = null }
        model.close()
    }

    companion object {
        /** the context the measured runs used: the [byokSystem] prompt with 【前文】 and 【当前句】 stays far under it */
        private const val CTX_SIZE = 2048

        private val lock = Any()
        private var engine: LocalLlm? = null

        /**
         * The engine for the selected, downloaded [LlmModels] model, loading it on the first call (seconds, paid on the
         * caller's thread — the session thread, see CaptionSession's "the getter builds the local engine"). Null while
         * nothing is selected or downloaded, and null when loading failed (the log carries the throwable); either way
         * the session turns that null into its fallback to the online LLM API.
         */
        fun current(): TextGen? = synchronized(lock) {
            val d = LlmModels.def(LlmModels.selected()) ?: return null
            if (!LlmModels.ready(d)) return null
            val p = LlmModels.fileOf(d.id)
            engine?.let { if (it.path == p) return it }
            // the selection moved (another model, or this one downloaded again): free the old GGUF, whose file may
            // even be gone from disk by now, before the new one is loaded
            engine?.close()
            return try {
                LocalLlm(p, load(p)).also { engine = it }
            } catch (e: Throwable) {
                logWarn("本地翻译模型加载失败：${p.name}", e)
                null
            }
        }

        /** Frees the loaded engine, if any. llama.cpp has nothing process-wide to shut down. */
        fun release() {
            synchronized(lock) { engine?.close() }
        }

        /** Loads the GGUF under [path]; the caller holds [lock]. */
        private fun load(path: File): LlamaModel {
            // the measured setup: half the cores, CPU only (no GPU offload, so the packaged app does not depend on a
            // driver being there or on a backend that agrees with this one)
            val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
            val mp = ModelParameters().setModel(path.path).setThreads(threads).setCtxSize(CTX_SIZE).setGpuLayers(0)
            val t0 = System.nanoTime()
            val m = LlamaModel(mp)
            logInfo("本地翻译模型加载：${path.name}（$threads 线程），${(System.nanoTime() - t0) / 1_000_000} ms，" +
                "llama.cpp ${LlamaModel.getLlamaCppBuildInfo()}")
            return m
        }
    }
}

/** An I/jpsub line, the shape Session.kt's logInfo writes (the desktop has no log file of its own to write to). */
private fun logInfo(m: String) = System.err.println("I/$TAG: $m")

/** A W/jpsub line with its throwable, the shape Session.kt's logWarn writes. */
private fun logWarn(m: String, e: Throwable?) {
    System.err.println("W/$TAG: $m")
    e?.printStackTrace()
}
