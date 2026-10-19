package io.github.korenkrita.simulsub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import java.io.FileInputStream
import javax.swing.JFileChooser

/**
 * The model the captions listen with, in the words the home card's row and the 字幕 tab's row show (one function for
 * both, so the two can never disagree): the selection when its files are there, the model a session would fall back
 * to when they are not, and a pointer at this page when nothing is downloaded at all.
 */
fun modelSummary(): String = when {
    AsrModels.selectionUsable() -> AsrModels.labelOf() + when (AsrModels.selected()) {
        AsrModels.BUILTIN, AsrModels.CUSTOM -> ""
        else -> if (AsrModels.downloading?.def?.id == AsrModels.selected()) "（下载中）" else ""
    }
    AsrModels.firstReady() != null -> "${AsrModels.firstReady()!!.label}（已选的「${AsrModels.labelOf()}」文件不全）"
    else -> "还没下载，先去 设置 › 字幕 › 语音识别模型 下载一个"
}

/**
 * 设置 › 字幕 › 语音识别模型, the twin of the phone's page (MainActivity's showModel / renderModels): the models that
 * can be downloaded, one the user imported, and the way back. Models are not in the app; the files are downloaded or
 * copied here, and a change takes effect the next time captions start (a running session is not interrupted).
 */
@Composable
fun ModelPage(onBack: () -> Unit) {
    // AsrModels and AsrCatalog are plain objects, not Compose state: [tick] is what re-reads them — it is bumped by
    // everything the page itself starts, and by the effect below while a download, an import or a fetch runs
    var tick by remember { mutableIntStateOf(0) }
    // the questions of the page, each in a window of its own (null / false: closed)
    var askDownload by remember { mutableStateOf<AsrModelDef?>(null) }
    var askDelete by remember { mutableStateOf<AsrModelDef?>(null) }
    var askDeleteCustom by remember { mutableStateOf(false) }
    // the picked files wait here for the 流式/非流式 answer; their streams are opened when the import actually runs
    var askKind by remember { mutableStateOf<List<AsrModels.Source>?>(null) }
    // what AsrModels.import refused at once (a return value, not lastError): the group that asked shows it
    var importError by remember { mutableStateOf<String?>(null) }

    // a download, an import and the catalog fetch run off the UI thread and report nothing by themselves: while one
    // runs, the page repaints twice a second, which moves the progress bar, the received bytes and the 获取中… label
    val running = AsrModels.downloading != null || AsrModels.importing || AsrCatalog.fetching
    LaunchedEffect(running) { if (running) while (true) { delay(500); tick++ } }
    // the list may have been edited since the last look: refetch (at most hourly; 刷新清单 always fetches)
    LaunchedEffect(Unit) { AsrCatalog.maybeRefresh { tick++ }; tick++ }
    tick.let { } // read: the tick is what recomposes everything below

    // the download itself, and the import: both settle on the UI thread, where [tick] shows the result
    fun downloadNow(def: AsrModelDef) { AsrModels.startDownload(def) { tick++ }; tick++ }
    // 下载并使用: a small model goes straight ahead, anything worth a data warning waits for a yes (the phone's line)
    fun startDownload(def: AsrModelDef) {
        if (def.sizeBytes < 50L * 1024 * 1024) downloadNow(def) else askDownload = def
        tick++
    }
    fun startImport(files: List<AsrModels.Source>, streaming: Boolean) {
        importError = AsrModels.import(files, streaming) { tick++ }
        tick++
    }

    Column(Modifier.fillMaxSize()) {
        // the page header, in the settings header's shape (the tab row stays hidden while this page is up)
        Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp)) {
            TextBtn("‹ 返回") { onBack() }
            Text("语音识别模型", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 24.dp)) {

            Group("可下载的模型") {
                // where the list came from, and an obvious way to fetch it again (the automatic one is at most hourly)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Hint(catalogLine()) }
                    Btn(if (AsrCatalog.fetching) "获取中…" else "刷新清单", enabled = !AsrCatalog.fetching) {
                        AsrCatalog.refresh { tick++ }
                        tick++ // fetching is true already: without this the label waits for the result
                    }
                }
                val dl = AsrModels.downloading
                val sel = AsrModels.selected()
                for (def in AsrModels.CATALOG) {
                    Divider()
                    if (dl != null && dl.def.id == def.id) {
                        // the row turns into the download: the name, what is arriving now, and the way out
                        ModelRow(
                            texts = {
                                Text(def.label, fontSize = 16.sp, color = C.text)
                                val name = def.files.getOrNull(dl.fileIdx)?.local?.substringBeforeLast('.') ?: ""
                                Text("正在下载 $name… ${dl.percent}%（${mb(dl.doneBytes)} / ${mb(def.sizeBytes)}）",
                                    fontSize = 13.sp, lineHeight = 20.sp, color = C.text2)
                                if (def.origin.isNotEmpty()) Text("原名：${def.origin}", fontSize = 13.sp, lineHeight = 20.sp, color = C.text2)
                                Spacer(Modifier.height(6.dp))
                                LinearProgressIndicator(progress = dl.percent / 100f,
                                    modifier = Modifier.fillMaxWidth().height(4.dp), color = C.brand, backgroundColor = C.pill)
                            },
                            actions = {
                                if (def.preferred) OkMark("首选") // the manifest's mark stays visible on a running row too
                                Btn("取消") { AsrModels.cancelDownload(); tick++ }
                            })
                        continue
                    }
                    val ready = AsrModels.ready(def)
                    val used = ready && sel == def.id
                    ModelRow(
                        texts = {
                            Text(def.label, fontSize = 16.sp, color = C.text)
                            Text(def.summary + when {
                                used -> " · 现在使用"
                                ready -> " · 已下载"
                                else -> " · 未下载"
                            }, fontSize = 13.sp, lineHeight = 20.sp, color = C.text2)
                            if (def.origin.isNotEmpty()) Text("原名：${def.origin}", fontSize = 13.sp, lineHeight = 20.sp, color = C.text2)
                        },
                        actions = {
                            // the manifest's 「首选」 mark: a green status word like 使用中, not a button
                            if (def.preferred) OkMark("首选")
                            // only a ready model can be used (and deleted); an undownloaded one offers 下载并使用
                            if (ready) {
                                if (used) OkMark("使用中") else Btn("使用") { AsrModels.select(def.id); tick++ }
                                // the files shipped inside the app are not the user's to delete
                                if (!AsrModels.shipped(def)) Btn("删除", danger = true) { askDelete = def }
                            } else Btn("下载并使用") { startDownload(def) }
                        })
                }
                AsrModels.lastError?.let { Note("⚠ " + it) }
            }

            // -- a model the user brought: four files picked from this computer's file window
            Group("本地自定义模型") {
                val custom = AsrModels.custom()
                if (custom == null) {
                    Hint("用自己的 sherpa-onnx transducer 模型：识别不出的语言、自己训练的模型都可以。需要四个文件：encoder、decoder、joiner 和 tokens.txt。")
                    if (AsrModels.importing)
                        ModelRow(texts = { RowTexts("从本机导入", "正在导入（复制四个文件并检查）…") },
                            actions = { OkMark("导入中…") })
                    else
                        ModelRow(texts = { RowTexts("从本机导入", "在文件窗口里选中这四个文件") },
                            actions = { Btn("选文件") { pickFiles()?.let { askKind = it; importError = null } } })
                } else if (AsrModels.importing) {
                    // its files are being replaced right now: nothing on this row is safe to press (the import re-renders it)
                    ModelRow(texts = { RowTexts("自定义模型", "正在导入新模型（复制四个文件并检查）…") },
                        actions = { OkMark("导入中…") })
                } else {
                    val streamName = if (custom.kind == AsrModels.KIND_STREAMING) "流式" else "非流式"
                    ModelRow(
                        texts = { RowTexts("自定义模型", "$streamName · ${custom.files.values.joinToString("、")}") },
                        actions = {
                            if (AsrModels.selected() == AsrModels.CUSTOM && AsrModels.customReady()) OkMark("使用中")
                            else Btn("使用") { AsrModels.select(AsrModels.CUSTOM); tick++ }
                            Btn("删除", danger = true) { askDeleteCustom = true }
                        })
                }
                importError?.let { Note(it) }
                Label("识别方式")
                Dropdown(listOf("流式 zipformer（模型站下载的一般是）", "非流式 transducer（自己导出的）"),
                    if (custom == null || custom.kind == AsrModels.KIND_STREAMING) 0 else 1, enabled = custom != null) {
                    val kind = if (it == 0) AsrModels.KIND_STREAMING else AsrModels.KIND_OFFLINE
                    if (custom != null && custom.kind != kind) { AsrModels.setCustomKind(kind); tick++ }
                }
                Hint("多数能下载到的 zipformer 模型是流式的；自己导出的离线 transducer 选非流式。拿不准就先用流式，识别不出再来改。")
            }
            Hint("模型决定「听」的能力和会的语言。换模型从下次开始字幕时生效；正在识别时换了也不打断。" +
                "下载和导入的模型只存在这台手机上，卸载 App 会一起删掉。")
        }
    }

    // -- the questions, each in a window of its own (the phone's dialogs) -----------------------------------------
    askDownload?.let { def ->
        AskWindow("下载「${def.label}」？", onClose = { askDownload = null }) {
            Text("约 ${mb(def.sizeBytes)}，建议在 Wi-Fi 下。下载完会直接选用（从下次开始字幕时生效）。",
                fontSize = 14.sp, lineHeight = 21.sp, color = C.text)
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Grow()
                TextBtn("取消") { askDownload = null }
                Spacer(Modifier.width(8.dp))
                Btn("下载", primary = true) { askDownload = null; downloadNow(def) }
            }
        }
    }
    askDelete?.let { def ->
        AskWindow("删除「${def.label}」？", onClose = { askDelete = null }) {
            Text("删除后要再下载才能用（约 ${mb(def.sizeBytes)}）。", fontSize = 14.sp, lineHeight = 21.sp, color = C.text)
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Grow()
                TextBtn("取消") { askDelete = null }
                Spacer(Modifier.width(8.dp))
                Btn("删除", danger = true) { askDelete = null; AsrModels.delete(def.id); tick++ }
            }
        }
    }
    if (askDeleteCustom) {
        AskWindow("删除自定义模型？", onClose = { askDeleteCustom = false }) {
            Text("文件和设置都会删掉；要用得重新导入。", fontSize = 14.sp, lineHeight = 21.sp, color = C.text)
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Grow()
                TextBtn("取消") { askDeleteCustom = false }
                Spacer(Modifier.width(8.dp))
                Btn("删除", danger = true) { askDeleteCustom = false; AsrModels.delete(AsrModels.CUSTOM); tick++ }
            }
        }
    }
    askKind?.let { files ->
        // the import's one question: which engine runs the picked files (the files are copied either way; a wrong
        // answer is changed in the page's 识别方式 dropdown, not by importing again)
        AskWindow("这个模型是流式的吗？", onClose = { askKind = null }) {
            Text("模型站上的 zipformer 模型一般是流式的（文件名里有 streaming 或 chunk）。自己导出的离线 transducer 就选非流式。" +
                "拿不准就选流式，之后在「识别方式」里随时改。", fontSize = 14.sp, lineHeight = 21.sp, color = C.text)
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Grow()
                TextBtn("取消") { askKind = null }
                Spacer(Modifier.width(8.dp))
                Btn("非流式 transducer") { askKind = null; startImport(files, streaming = false) }
                Spacer(Modifier.width(8.dp))
                Btn("流式 zipformer", primary = true) { askKind = null; startImport(files, streaming = true) }
            }
        }
    }
}

/** One row of the model list, in the phone's shape: the name and the grey lines on the left, the actions that apply now on the right. */
@Composable
private fun ModelRow(texts: @Composable ColumnScope.() -> Unit, actions: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), content = texts)
        Spacer(Modifier.width(12.dp))
        // a status word and one or two buttons; the phone lays them out in the same order, left to right
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
    }
}

/** The name and the grey line under it of a row (the phone's rowTexts); the model list's rows all start with these two. */
@Composable
private fun ColumnScope.RowTexts(name: String, sub: String) {
    Text(name, fontSize = 16.sp, color = C.text)
    Text(sub, fontSize = 13.sp, lineHeight = 20.sp, color = C.text2)
}

/** A green status word on a row (首选、使用中、导入中…): the phone's status pill, which is not a button. */
@Composable
private fun OkMark(t: String) = Text(t, color = C.ok, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 6.dp))

/** The model page's one line about the fetched list: its revision, or the failure it fell back from. */
private fun catalogLine(): String {
    val err = AsrCatalog.lastError
    return when {
        AsrCatalog.fetching -> "模型清单：正在获取…"
        err != null -> "模型清单：获取失败（${err.take(60)}），仍用${if (AsrCatalog.usingFetched) "上次获取的清单" else "内置清单"}"
        AsrCatalog.usingFetched -> "模型清单：更新于 ${AsrCatalog.revision ?: "未标注"}"
        else -> "模型清单：用的是内置清单"
    }
}

/**
 * One of the page's questions in a window of its own, the shape AutoUpdateCheck uses for the update box: a second
 * [Window] (the desktop's own dialogs, no new dependency), themed with the palette the page is under, its close
 * button doing what 取消 does.
 */
@Composable
private fun AskWindow(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val dark = C.dark // the main window's palette, which this window does not inherit by itself
    Window(onCloseRequest = onClose, title = title, state = rememberWindowState(size = DpSize(460.dp, 230.dp))) {
        Themed(dark) {
            Column(Modifier.fillMaxSize().background(C.bg).padding(18.dp).verticalScroll(rememberScrollState()), content = content)
        }
    }
}

/**
 * The system's own file window, with multi-select on: the four transducer files are picked in one go, one per role,
 * which is what the phone's EXTRA_ALLOW_MULTIPLE is for. The files are opened only when the import copies them
 * ([AsrModels.Source.open]), so a file the user moves in between says so instead of failing at picking time.
 */
private fun pickFiles(): List<AsrModels.Source>? {
    val chooser = JFileChooser().apply {
        isMultiSelectionEnabled = true
        fileSelectionMode = JFileChooser.FILES_ONLY
        dialogTitle = "选模型的四个文件（encoder / decoder / joiner / tokens）"
    }
    if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null
    val files = chooser.selectedFiles.orEmpty()
    if (files.isEmpty()) return null
    return files.map { f -> AsrModels.Source(f.name) { FileInputStream(f) } }
}

/** A byte count the way the phone's page sizes things: whole MB from 10 MB up, one decimal to 1 MB, else KB. */
private fun mb(b: Long) = when {
    b >= 1_000_000L * 10 -> "${b / 1_000_000L} MB"
    b >= 1_000_000L -> "%.1f MB".format(java.util.Locale.US, b / 1_000_000.0)
    else -> "${b / 1024L} KB"
}
