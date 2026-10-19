package io.github.korenkrita.simulsub

import java.io.File

/** The same tag Session.kt logs under, so one grep finds the app's own messages. */
private const val TAG = "jpsub"

/**
 * Hands the core's [LlmModels] everything the desktop must supply, once, before the window is built: [main] calls it
 * next to [installAsr]. The downloaded GGUFs land under [dataDir] and the selection in [Prefs]; the 设置 › 翻译 ›
 * 本地翻译 page and the session (whose engine is [LocalLlm]) both read the same installed object.
 */
fun installLlm() {
    LlmModels.install(
        root = File(dataDir, "llm"),
        prefs = object : LlmModels.LlmPrefs {
            override var selected: String
                get() = Prefs.prefs.llmModel
                set(v) { Prefs.prefs.llmModel = v }
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
}
