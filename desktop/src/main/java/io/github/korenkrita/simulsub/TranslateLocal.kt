package io.github.korenkrita.simulsub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay

/** The desktop's settings, read on every render the way App.kt's own `prefs` reads them (a write is what recomposes). */
private val mtPrefs get() = Prefs.prefs

/**
 * The local model a session would run: the selected one when its file is there, else the first one that is (the same
 * fallback [LlmModels.delete] leaves behind). Null while no model is downloaded.
 */
internal fun localModel(): LlmModels.Def? =
    LlmModels.def(LlmModels.selected())?.takeIf { LlmModels.ready(it) }
        ?: LlmModels.CATALOG.firstOrNull { LlmModels.ready(it) }

/**
 * true = captions can start on this machine alone: 用本地模型翻译 is on and there is a downloaded model for it. The
 * home card and [startCaptions] ask this — without it a user who never filled an LLM API has no way to start, even
 * though the local model is right there (the bug that came back from the first local-translation build).
 */
internal fun localReady(): Boolean = mtPrefs.localMtOn && localModel() != null

/**
 * 设置 › 翻译 › 本地翻译（离线）: the PC's offline translation, as [LlmModels] lays it out — one Tencent Hunyuan MT GGUF
 * downloaded once, then every sentence translated in this process by the desktop's llama.cpp binding, with no API and
 * no network. The group is [ModelPage]'s shape (its rows, its ask-windows, the tick while a download runs) over a
 * two-entry catalog, so the page and that one cannot drift apart in how a download is confirmed, cancelled or deleted.
 *
 * The switch is the group's only setting and just writes [Prefs]: the session reads the choice itself before it builds
 * its translator, so what the row prompts is a change from the next time captions start, not a live switch-over.
 */
@Composable
fun LocalMtGroup() {
    // LlmModels is a plain object, not Compose state: [tick] is what re-reads it — it is bumped by everything the
    // group itself starts, and by the effect below while a download runs
    var tick by remember { mutableIntStateOf(0) }
    // the group's questions, each in a window of its own (null: closed)
    var askDownload by remember { mutableStateOf<LlmModels.Def?>(null) }
    var askDelete by remember { mutableStateOf<LlmModels.Def?>(null) }

    // a download runs off the UI thread and reports nothing by itself: while one runs, the group repaints twice a
    // second, which moves the bar and the received bytes (downloading is null again once it settled)
    val running = LlmModels.downloading != null
    LaunchedEffect(running) { if (running) while (true) { delay(500); tick++ } }
    tick.let { } // read: the tick is what recomposes everything below

    val dl = LlmModels.downloading
    // what the page reports and the switch needs: any catalog model whose file is there
    val downloaded = LlmModels.anyReady()
    val current = localModel() // the model the 现状行 names, and the one a session would run
    val sel = LlmModels.selected()

    // the download itself, which settles on the UI thread, where [tick] shows the result
    fun downloadNow(def: LlmModels.Def) { LlmModels.startDownload(def) { tick++ }; tick++ }
    // one line of the page a data warning is worth waiting for: half a gigabyte goes straight ahead ([ModelPage]'s
    // own 50 MB threshold, so the two pages ask in the same cases)
    fun startDownload(def: LlmModels.Def) {
        if (def.bytes < 50L * 1024 * 1024) downloadNow(def) else askDownload = def
        tick++
    }

    Group("本地翻译（离线）") {
        Hint(when {
            current == null -> "本地模型：还没下载"
            mtPrefs.localMtOn -> "本地模型：${current.label} · 正在使用（不联网）"
            else -> "本地模型：${current.label} 已下载"
        })
        for (def in LlmModels.CATALOG) {
            Divider()
            if (dl != null && dl.def.id == def.id) {
                // the row turns into the download: the name, what is arriving now, and the way out
                LlmRow(
                    texts = {
                        Text(def.label, fontSize = 16.sp, color = C.text)
                        Text("正在下载… ${dl.percent}%（${mb(dl.doneBytes)} / ${mb(def.bytes)}）",
                            fontSize = 13.sp, lineHeight = 20.sp, color = C.text2)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(progress = dl.percent / 100f,
                            modifier = Modifier.fillMaxWidth().height(4.dp), color = C.brand, backgroundColor = C.pill)
                    },
                    actions = { Btn("取消") { LlmModels.cancelDownload(); tick++ } })
                continue
            }
            val ready = LlmModels.ready(def)
            val used = ready && sel == def.id
            LlmRow(
                texts = {
                    Text(def.label, fontSize = 16.sp, color = C.text)
                    Text(def.summary, fontSize = 13.sp, lineHeight = 20.sp, color = C.text2)
                },
                actions = {
                    // only a ready model can be used (and deleted); an undownloaded one offers 下载
                    if (ready) {
                        if (used) LlmOkMark("已下载") else Btn("使用") { LlmModels.select(def.id); tick++ }
                        TextBtn("删除", danger = true) { askDelete = def }
                    } else Btn("下载") { startDownload(def) }
                })
        }
        Divider()
        // the one setting: the session reads it before building its translator, so a change lands from the next start
        SwitchRow("用本地模型翻译", "不联网，用本机跑混元翻译模型；从下次开始字幕时生效。", mtPrefs.localMtOn,
            enabled = downloaded) { mtPrefs.localMtOn = it }
        if (!downloaded) Hint("先下载一个模型（混元翻译 1.8B · 约 666 MB 起）。")
        LlmModels.lastError?.let { Note("⚠ $it") }
        Hint("本地模型只存在这台电脑上；用本地翻译时字幕不再需要联网，也不会把内容发给任何服务。")
    }

    // -- the group's questions, each in a window of its own -----------------------------------------------------------
    askDownload?.let { def ->
        LlmAskWindow("下载「${def.label}」？", onClose = { askDownload = null }) {
            Text("约 ${mb(def.bytes)}，下载完可以直接用来翻译（不用填 API）。", fontSize = 14.sp, lineHeight = 21.sp, color = C.text)
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Grow()
                TextBtn("取消") { askDownload = null }
                Spacer(Modifier.width(8.dp))
                Btn("下载", primary = true) { askDownload = null; downloadNow(def) }
            }
        }
    }
    askDelete?.let { def ->
        LlmAskWindow("删除「${def.label}」？", onClose = { askDelete = null }) {
            Text("删除后要再下载才能用（约 ${mb(def.bytes)}）。", fontSize = 14.sp, lineHeight = 21.sp, color = C.text)
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Grow()
                TextBtn("取消") { askDelete = null }
                Spacer(Modifier.width(8.dp))
                Btn("删除", danger = true) { askDelete = null; LlmModels.delete(def.id); tick++ }
            }
        }
    }
}

/** One row of the local-model list, [ModelPage]'s shape: the name and the grey line on the left, what applies now on the right. */
@Composable
private fun LlmRow(texts: @Composable ColumnScope.() -> Unit, actions: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), content = texts)
        Spacer(Modifier.width(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

/** A green status word on a row (已下载), [ModelPage]'s 使用中 pill shape: a word, not a button. */
@Composable
private fun LlmOkMark(t: String) = Text(t, color = C.ok, fontSize = 14.sp, fontWeight = FontWeight.Medium,
    modifier = Modifier.padding(horizontal = 6.dp))

/**
 * One of the group's questions in a window of its own, [ModelPage]'s AskWindow shape: a second [Window] (the desktop's
 * own dialogs, no new dependency), themed with the palette the page is under, its close button doing what 取消 does.
 */
@Composable
private fun LlmAskWindow(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val dark = C.dark // the main window's palette, which this window does not inherit by itself
    Window(onCloseRequest = onClose, title = title, state = rememberWindowState(size = DpSize(460.dp, 230.dp))) {
        Themed(dark) {
            Column(Modifier.fillMaxSize().background(C.bg).padding(18.dp).verticalScroll(rememberScrollState()), content = content)
        }
    }
}

/** A byte count the way [ModelPage] sizes its downloads: whole MB from 10 MB up, one decimal to 1 MB, else KB. */
private fun mb(b: Long) = when {
    b >= 1_000_000L * 10 -> "${b / 1_000_000L} MB"
    b >= 1_000_000L -> "%.1f MB".format(java.util.Locale.US, b / 1_000_000.0)
    else -> "${b / 1024L} KB"
}
