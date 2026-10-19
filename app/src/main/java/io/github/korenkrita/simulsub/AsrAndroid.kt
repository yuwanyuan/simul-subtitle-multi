package io.github.korenkrita.simulsub

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * The phone's side of the shared model store ([AsrModels], [AsrCatalog]): where the models live, which file keeps
 * the selection, how the UI thread is reached, and the two engines of this module — everything [AsrModels] leaves to
 * the platform. The engine classes themselves stay in [AsrEngines].
 */
private const val TAG = "jpsub"

/** Wires the model store for the phone: called once from MainActivity.onCreate, before anything reads a model. */
fun installAsr(ctx: Context) {
    AsrModels.install(
        // app-specific external storage: no permission needed, gone on uninstall; internal files when that is gone
        root = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "asr-models"),
        prefs = AndroidAsrPrefs(ctx),
        post = { android.os.Handler(android.os.Looper.getMainLooper()).post(it) },
        log = { m, e -> if (e == null) Log.i(TAG, m) else Log.w(TAG, m, e) },
        userAgent = "SimulSub/${BuildConfig.VERSION_NAME}",
    )
    // builtinDir stays null: the phone's 内置模型 is a download like the rest (the APK packs no model)
    AsrCatalog.cacheFile = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "asr-catalog.json")
    AsrCatalog.init()
    AsrModels.engineFactory = { files, streaming, threads ->
        if (streaming) StreamingSherpaAsr(files[0], files[1], files[2], files[3], threads)
        else FileTransducerAsr(files[0], files[1], files[2], files[3], threads)
    }
}

/** The settings file [Prefs] lives in too (same "jpsub" prefs), under [AsrModels]' own two keys. */
private class AndroidAsrPrefs(ctx: Context) : AsrModels.AsrPrefs {
    // the application context: this object is held by AsrModels for the whole process
    private val sp = ctx.applicationContext.getSharedPreferences("jpsub", Context.MODE_PRIVATE)

    override var selected: String
        get() = sp.getString("asrModel", AsrModels.BUILTIN) ?: AsrModels.BUILTIN
        set(v) = sp.edit().putString("asrModel", v).apply()

    override var custom: String?
        get() = sp.getString("customAsr", null)
        set(v) = sp.edit().apply { if (v == null) remove("customAsr") else putString("customAsr", v) }.apply()
}

/** The picked documents as the import's [AsrModels.Source]s (the picker gives URIs, the copy needs streams). */
fun asrSources(ctx: Context, picked: List<Pair<Uri, String>>): List<AsrModels.Source> =
    picked.map { (uri, name) ->
        AsrModels.Source(name) {
            ctx.contentResolver.openInputStream(uri) ?: throw IOException("打不开所选的文件 $name（可能移动了位置）")
        }
    }
