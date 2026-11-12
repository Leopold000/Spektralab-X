package com.dearded.spektralab

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val WORKING_EDGES = listOf(
    2048 to "2048 px",
    3072 to "3072 px",
    4096 to "4096 px（默认）",
    6144 to "6144 px",
    0 to "原始尺寸",
)

/** 照片四周's names, keyed as Theme.kt SURROUNDS. */
private val SURROUND_LABELS = mapOf("dark" to "深灰（默认）", "grey18" to "18% 灰", "black" to "黑", "white" to "白")

private val HIGHLIGHT_STOPS = listOf(0f to "关（默认）", 1f to "1 档", 2f to "2 档")

/**
 * 设置 (ui-screens_research.md 2): a full page over the editor, not a sheet,
 * grouped from most to least used. Each group is one card of joined rows with
 * a 2 dp seam between them (M3 Expressive segmented list): the outer corners
 * 20 dp, the inner 4. A row names the setting, says in one line what it does,
 * and shows its current value; choosing among several values opens in place.
 * The back gesture shrinks the page under the finger.
 */
@Composable
fun SettingsScreen(ui: UiState, vm: EditorViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    var back by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler { events ->
        try {
            events.collect { back = it.progress }
            onClose()
        } catch (e: CancellationException) {
            back = 0f
            throw e
        }
    }
    Box(
        Modifier.fillMaxSize().zIndex(10f)
            .graphicsLayer {
                val k = 1f - 0.1f * back
                scaleX = k; scaleY = k
                shape = RoundedCornerShape((24 * back).dp); clip = back > 0f
            }
            .background(Palette.Background)
            // Same window as the editor: without a pointer node here, taps on blank
            // space would fall through to the editor underneath.
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回编辑器")
                }
            }
            Text("设置", style = MaterialTheme.typography.headlineSmall, color = Palette.OnSurface,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 8.dp))

            // 编辑: how the editor starts.
            // UI2.md 0.1: the key is ui-visual's (Pill.kt EXPAND_ALL); the editor reads it with rememberExpandAll().
            val prefs = remember(context) { context.getSharedPreferences("settings", 0) }
            var expandAll by remember { mutableStateOf(prefs.getBoolean(EXPAND_ALL, false)) }
            val coach = LocalCoach.current
            val scope = rememberCoroutineScope()
            var tileBench by remember { mutableStateOf<String?>(null) }
            var tileBenchRunning by remember { mutableStateOf(false) }
            var seamResult by remember { mutableStateOf<String?>(null) }
            var seamRunning by remember { mutableStateOf(false) }
            SettingsGroup("编辑", buildList<@Composable () -> Unit> {
                add {
                    SettingRow(
                        "默认展开全部参数", expandAll, true,
                        note = "冲洗和调整直接显示全部参数。",
                    ) { expandAll = it; prefs.edit().putBoolean(EXPAND_ALL, it).apply() }
                }
                // ui-visual's key (Theme.kt SURROUND_KEY / SURROUNDS); the editor follows it while it changes.
                add {
                    var surround by remember { mutableStateOf(prefs.getString(SURROUND_KEY, "dark") ?: "dark") }
                    ChoiceRow(
                        "照片四周",
                        "判断颜色用 18% 灰。",
                        value = SURROUND_LABELS[surround] ?: SURROUND_LABELS.getValue("dark"),
                    ) {
                        PillRow {
                            for ((key, colour) in SURROUNDS) {
                                SelectPill(
                                    selected = surround == key,
                                    onClick = { surround = key; prefs.edit().putString(SURROUND_KEY, key).apply() },
                                    label = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(Modifier.size(14.dp).background(colour, CircleShape)
                                                .border(1.dp, Palette.Outline, CircleShape))
                                            Spacer(Modifier.width(8.dp))
                                            Text(SURROUND_LABELS[key] ?: key)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
                add { NewPhotoStocks(ui, vm) }
                // ui-motion_splash.md 6: LaunchIntro reads this at launch; off by itself when animations are off.
                add {
                    var intro by remember { mutableStateOf(prefs.getBoolean(LAUNCH_INTRO, true)) }
                    val motion = remember { motionAllowed() }
                    SettingRow(
                        "启动动画", intro && motion, motion,
                        disabledReason = "系统已关闭动画，启动动画不会播放。",
                    ) { intro = it; prefs.edit().putBoolean(LAUNCH_INTRO, it).apply() }
                }
                if (coach != null) add {
                    ActionRow("重新查看引导", "回到编辑器时再显示一次。") { coach.replay(); onClose() }
                }
            })

            // 画质与导出: size, colour, what goes into the file.
            SettingsGroup("画质与导出", listOf<@Composable () -> Unit>(
                {
                    ChoiceRow(
                        "工作尺寸（长边）",
                        "预览和导出都用这个尺寸，越大越慢。",
                        value = WORKING_EDGES.firstOrNull { it.first == ui.workingEdge }?.second ?: "${ui.workingEdge} px",
                    ) {
                        PillRow {
                            for ((edge, label) in WORKING_EDGES) {
                                SelectPill(selected = ui.workingEdge == edge, onClick = { vm.setWorkingEdge(edge) },
                                    label = { Text(label) })
                            }
                        }
                    }
                },
                {
                    SettingRow(
                        "广色域导出（Display P3）", ui.wideGamut, true,
                        note = "仅用于 JPEG；TIFF 色彩空间在保存菜单中选择。",
                    ) { vm.setWideGamut(it) }
                },
                {
                    ChoiceRow(
                        "手机照片高光恢复",
                        "展开手机照片中灰以上的高光，结果更亮更浓，和桌面版不同。",
                        value = HIGHLIGHT_STOPS.firstOrNull { it.first == ui.highlightStops }?.second ?: "${ui.highlightStops} 档",
                    ) {
                        Segmented(
                            options = HIGHLIGHT_STOPS.map { it.second },
                            selected = HIGHLIGHT_STOPS.indexOfFirst { it.first == ui.highlightStops }.coerceAtLeast(0),
                        ) { i -> vm.setHighlightStops(HIGHLIGHT_STOPS[i].first) }
                    }
                },
                {
                    var on by remember { mutableStateOf(LocationSetting.enabled(context)) }
                    SettingRow("导出保留拍摄位置", on, true) {
                        on = it; LocationSetting.set(context, it)
                    }
                },
            ))

            // 我的风格: what the user brought in.
            SettingsGroup("我的风格", listOf<@Composable () -> Unit>({ Column(Modifier.padding(vertical = 8.dp)) { UserLibrarySection(vm) } }))

            // 更新
            SettingsGroup("更新", listOf<@Composable () -> Unit>(
                {
                    var auto by remember { mutableStateOf(Updater.autoCheck(context)) }
                    var wifi by remember { mutableStateOf(Updater.wifiDownload(context)) }
                    Column {
                        SettingRow("自动检查更新", auto, true,
                            note = "只连 GitHub 上本项目的发布页，不上传任何内容。") { auto = it; Updater.setAutoCheck(context, it) }
                        SettingRow("Wi-Fi 下自动下载新版本", wifi, auto) { wifi = it; Updater.setWifiDownload(context, it) }
                    }
                },
                {
                    ActionRow("立即检查更新", "当前 ${BuildConfig.VERSION_NAME}") {
                        onClose(); Updater.show(); Updater.check(context, manual = true)
                    }
                },
            ))

            // 关于与诊断
            SettingsGroup("关于与诊断", listOf<@Composable () -> Unit>(
                { InfoRow("设备", ui.deviceSummary.ifEmpty { "—" }) },
                { InfoRow("引擎", ui.engineSummary.ifEmpty { "—" }) },
                {
                    ActionRow("性能测试", "测量本机的预览、切换风格、导出速度，给出工作尺寸建议。") {
                        vm.startBenchmark()
                    }
                },
                {
                    val clipboard = LocalClipboardManager.current
                    var copied by remember { mutableStateOf(false) }
                    ActionRow(if (copied) "已复制诊断信息" else "复制诊断信息", "报告问题时一起贴上，里面没有照片和位置。") {
                        clipboard.setText(AnnotatedString(vm.diagnostics())); copied = true
                    }
                },
                {
                    ActionRow(
                        if (tileBenchRunning) "正在测试分块…" else "高像素分块测试（实验）",
                        "用当前照片按 2048 px 长边重开一块，只测打开和渲染时间，不改编辑。",
                    ) {
                        if (!tileBenchRunning) {
                            tileBenchRunning = true
                            tileBench = null
                            scope.launch {
                                try {
                                    tileBench = vm.benchmarkTile()
                                } finally {
                                    tileBenchRunning = false
                                }
                            }
                        }
                    }
                },
                {
                    ActionRow(
                        if (seamRunning) "正在测试接缝…" else "高像素接缝测试（实验）",
                        "把当前照片切成 2×2 带重叠的块，分别冲洗后比较接缝；不改编辑。",
                    ) {
                        if (!seamRunning) {
                            seamRunning = true
                            seamResult = null
                            scope.launch {
                                try {
                                    seamResult = vm.seamTest()
                                } finally {
                                    seamRunning = false
                                }
                            }
                        }
                    }
                },
                { Column(Modifier.padding(vertical = 12.dp)) { AboutSection() } },
            ))
            Spacer(Modifier.height(24.dp))
            tileBench?.let { result ->
                AlertDialog(
                    onDismissRequest = { tileBench = null },
                    confirmButton = { TextButton(onClick = { tileBench = null }) { Text("好") } },
                    title = { Text("分块测试") },
                    text = { Text(result, style = MaterialTheme.typography.bodySmall) },
                )
            }
            seamResult?.let { result ->
                AlertDialog(
                    onDismissRequest = { seamResult = null },
                    confirmButton = { TextButton(onClick = { seamResult = null }) { Text("好") } },
                    title = { Text("接缝测试") },
                    text = { Text(result, style = MaterialTheme.typography.bodySmall) },
                )
            }
        }
    }
}

/**
 * One group: its name, then the rows in one container (ui_corners.md 5):
 * the container's outer corners are Corners.l, the rows inside are square,
 * a hairline between them, no seams.
 */
@Composable
private fun SettingsGroup(title: String, rows: List<@Composable () -> Unit>) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = Palette.OnSurfaceSoft,
        modifier = Modifier.padding(start = 4.dp, top = GroupGap, bottom = 8.dp))
    Column(Modifier.fillMaxWidth().clip(Corners.l).background(Palette.Panel)) {
        rows.forEachIndexed { i, row ->
            if (i > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(Palette.OutlineSoft))
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) { row() }
        }
    }
}

/** A name and one line on the left, a chevron on the right; the whole row is the button. */
@Composable
private fun ActionRow(label: String, note: String = "", onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Palette.OnSurface)
            if (note.isNotBlank()) Hint(note)
        }
        Chevron(0f)
    }
}

/** A fact, not a control: the name over its value. */
@Composable
private fun InfoRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Palette.OnSurface)
        Hint(value)
    }
}

/**
 * A setting with several values: collapsed it shows the current one on the
 * right (the upstream's "a closed section says what is set"); tapped, the
 * choices open under it in place.
 */
@Composable
private fun ChoiceRow(label: String, note: String, value: String, choices: @Composable () -> Unit) {
    var open by rememberSaveable(label) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .clickable(role = Role.Button) { open = !open }
                .semantics { stateDescription = if (open) "已展开" else "已收起" }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, color = Palette.OnSurface)
                if (!open) Text(value, style = MaterialTheme.typography.bodySmall, color = Palette.OnSurfaceSoft,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val turn by animateFloatAsState(if (open) 90f else 0f, Motion.defaultSpatial(), label = "choice-turn")
            Chevron(turn)
        }
        AnimatedVisibility(open, enter = expandVertically(Motion.defaultSpatial()) + fadeIn(Motion.defaultEffects()),
            exit = shrinkVertically(Motion.defaultSpatial()) + fadeOut(Motion.fastEffects())) {
            Column(Modifier.padding(bottom = 12.dp)) {
                Hint(note, Modifier.padding(bottom = 10.dp))
                choices()
            }
        }
    }
}

/** A right-pointing chevron, turned by [degrees] (90 = open, pointing down). */
@Composable
private fun Chevron(degrees: Float) {
    Canvas(Modifier.size(20.dp).graphicsLayer { rotationZ = degrees }) {
        val u = size.width / 20f
        val w = 1.6f * u
        drawLine(Palette.OnSurfaceSoft, Offset(8 * u, 5 * u), Offset(13 * u, 10 * u), w, StrokeCap.Round)
        drawLine(Palette.OnSurfaceSoft, Offset(13 * u, 10 * u), Offset(8 * u, 15 * u), w, StrokeCap.Round)
    }
}

/** 新照片的胶片和相纸: 无 (the photo as it is), remember the last pair, or a fixed pair picked from the lists. */
@Composable
private fun NewPhotoStocks(ui: UiState, vm: EditorViewModel) {
    val mode = ui.newPhotoMode
    val fixed = mode == EditorViewModel.NEW_FIXED
    val value = if (mode == EditorViewModel.NEW_NONE) "无，打开就是原图" else if (!fixed) "记住上次用的" else listOf(
        ui.catalog.film(ui.defaultFilm)?.label ?: ui.defaultFilm,
        ui.catalog.paper(ui.defaultPaper)?.label ?: ui.defaultPaper,
    ).joinToString(" · ")
    var picking by remember { mutableStateOf<String?>(null) }
    ChoiceRow("新照片的胶片和相纸", "只对没编辑过的照片生效。", value) {
        Segmented(options = listOf("无", "记住上次用的", "固定为"),
            selected = when (mode) { EditorViewModel.NEW_NONE -> 0; EditorViewModel.NEW_REMEMBER -> 1; else -> 2 }) { i ->
            if (i == 0) vm.setNewPhotoNone()
            else if (i == 1) vm.setNewPhotoStocks("", "")
            else vm.setNewPhotoStocks(ui.defaultFilm.ifEmpty { ui.edit.film }, ui.defaultPaper.ifEmpty { ui.edit.paper })
        }
        if (fixed) {
            Spacer(Modifier.height(RowGap))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TonalButton("胶片：" + (ui.catalog.film(ui.defaultFilm)?.label ?: ui.defaultFilm),
                    modifier = Modifier.weight(1f)) { picking = "film" }
                TonalButton("相纸：" + (ui.catalog.paper(ui.defaultPaper)?.label ?: ui.defaultPaper),
                    modifier = Modifier.weight(1f)) { picking = "paper" }
            }
        }
    }
    when (picking) {
        "film" -> StockSheet("新照片的胶片", ui.catalog.films, ui.defaultFilm, { picking = null }) {
            vm.setNewPhotoStocks(it, ui.defaultPaper)
        }
        "paper" -> StockSheet("新照片的相纸", ui.catalog.papers, ui.defaultPaper, { picking = null }) {
            vm.setNewPhotoStocks(ui.defaultFilm, it)
        }
    }
}

/** A stock list in a sheet: one 56 dp row per stock, the current one on the inverted fill. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StockSheet(title: String, stocks: List<Stock>, selected: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Palette.PanelHigh) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            PanelHeader(title)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(stocks, key = { it.id }) { s ->
                    val on = s.id == selected
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(Corners.m)
                            .background(if (on) Palette.Accent else Palette.Panel)
                            .clickable(role = Role.RadioButton) { onPick(s.id); onDismiss() }
                            .semantics { contentDescription = s.label + if (on) "，当前" else "" }
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(s.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.Medium else androidx.compose.ui.text.font.FontWeight.Normal,
                            color = if (on) Palette.OnAccent else Palette.OnSurface)
                        val tags = listOfNotNull(if (s.positive) "反转" else null, if (s.bw) "黑白" else null)
                        if (tags.isNotEmpty()) Text(tags.joinToString(" "), style = MaterialTheme.typography.labelSmall,
                            color = if (on) Palette.OnAccent else Palette.OnSurfaceSoft)
                    }
                }
            }
            Spacer(Modifier.navigationBarsPadding().height(16.dp))
        }
    }
}
