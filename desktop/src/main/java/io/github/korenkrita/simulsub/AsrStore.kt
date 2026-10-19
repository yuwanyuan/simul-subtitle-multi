package io.github.korenkrita.simulsub

import java.io.File

/** The same tag Session.kt logs under, so one grep finds the app's own messages. */
private const val TAG = "jpsub"

/**
 * Hands the core's [AsrModels] everything the desktop must supply, once, before the window is built: [main] calls it
 * before `app()`, so the 语音识别模型 page and the first session already see it. The four built-in files ship inside
 * [modelsDir] (`asr/`, the packaged app's resources or ./models from the repo); downloaded and imported models land
 * under [dataDir], and the selection with the custom model's meta in [Prefs].
 */
fun installAsr() {
    AsrModels.install(
        root = File(dataDir, "asr-models"),
        prefs = object : AsrModels.AsrPrefs {
            override var selected: String
                get() = Prefs.prefs.asrModel
                set(v) { Prefs.prefs.asrModel = v }
            override var custom: String?
                get() = Prefs.prefs.customAsr
                set(v) { Prefs.prefs.customAsr = v }
        },
        post = { r -> java.awt.EventQueue.invokeLater(r) },
        // Session.kt's logInfo/logWarn in one: failures carry the throwable, the rest is a plain line
        log = { m, e ->
            if (e == null) System.err.println("I/$TAG: $m")
            else { System.err.println("W/$TAG: $m"); e.printStackTrace() }
        },
        // the version alone: the core spells the downloads' `User-Agent: SimulSub/<version>` from it
        userAgent = APP_VERSION,
    )
    // the built-in model is the packed one, not a download: its four files are modelsDir/asr/ (AsrModels.SHIPPED)
    AsrModels.builtinDir = modelsDir
    // what a model's four files (encoder / decoder / joiner / tokens, in that order) are built into
    AsrModels.engineFactory = { files, streaming, threads ->
        if (streaming) ZipformerStreamingAsr(files[0], files[1], files[2], files[3], threads)
        else FileTransducerAsr(files[0], files[1], files[2], files[3], threads)
    }
    // the catalog's last good list, read before the page shows whether a saved selection points at a remote model
    AsrCatalog.cacheFile = File(dataDir, "asr-catalog.json")
    AsrCatalog.init()
}
