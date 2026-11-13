package com.dearded.spektralab

import androidx.compose.animation.togetherWith
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.background
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.windowInsetsPadding
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(vm: EditorViewModel, onLightTable: () -> Unit = {}) {
    val ui by vm.ui.collectAsState()
    val jobs by vm.jobs.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }
    var openMenu by remember { mutableStateOf(false) }
    var exportMenu by remember { mutableStateOf(false) }
    // ui2_reactbits.md 3.4 C: when 保存 last queued an export, so its icon follows that job (Moments.kt SaveMark).
    var savedAt by remember { mutableLongStateOf(0L) }

    // Android 13+: the export's progress notification needs POST_NOTIFICATIONS.
    // Asked when an export starts; a refusal only hides the notification.
    val context = androidx.compose.ui.platform.LocalContext.current
    var queuedExport by remember { mutableStateOf<(() -> Unit)?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        queuedExport?.invoke()
        queuedExport = null
    }
    fun exportWithNotice(format: ExportFormat, size: ExportSize, tiffColorSpace: TiffColorSpace = TiffColorSpace.SRGB) {
        savedAt = System.currentTimeMillis()
        val missing = android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (missing) {
            queuedExport = { vm.export(format, size, tiffColorSpace) }
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.export(format, size, tiffColorSpace)
        }
    }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        uri?.let(vm::open)
    }
    // RAW files rarely carry an image/* type, so the document picker accepts anything.
    // It starts where the last file came from (the photo picker has no such option).
    val pickContext = androidx.compose.ui.platform.LocalContext.current
    val pickFile = rememberLauncherForActivityResult(OpenDocumentFromLast(pickContext)) { uri: Uri? ->
        uri?.let { OpenDocumentFromLast.remember(pickContext, it); vm.open(it) }
    }

    // Tap the photo to hide every control and the system bars (docs/UI.md,
    // 沉浸式). Anything that needs the user -- an error, the queue, the 1:1
    // viewer, no photo at all -- brings the chrome back by itself.
    var chrome by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
    val canHide = immersiveAllowed()
    val showChrome = chrome || !canHide || !ui.hasImage || ui.error != null || ui.showQueue ||
        ui.detail != null || ui.busy != null
    LaunchedEffect(ui.source) { chrome = true }
    SystemBarsHidden(!showChrome)
    // UI2.md 3.1: chrome recedes while a slider is dragged (the panel itself stays).
    var dragging by remember { mutableStateOf(false) }
    val barsShown = showChrome && !dragging
    // UI2.md 2.1: tapping the open entry again folds the panel down to the toolbar,
    // so the photo gets the screen; any entry opens it again.
    // Three heights (UI2.md 2.1): folded to the toolbar, half (the default), tall for every parameter.
    var panelLevel by androidx.compose.runtime.saveable.rememberSaveable { mutableIntStateOf(1) }
    val panelOpen = panelLevel > 0
    val recede by androidx.compose.animation.core.animateFloatAsState(
        if (dragging) 0.15f else 1f, Motion.defaultEffects(), label = "recede")
    // The first-run guide waits until there is a photo and nothing over it.
    val coach = LocalCoach.current
    val coachAllowed = ui.hasImage && showChrome && ui.busy == null && ui.error == null &&
        !ui.showQueue && ui.detail == null && !showSettings && !ui.exporting
    androidx.compose.runtime.SideEffect { coach?.allowed = coachAllowed }
    androidx.activity.compose.BackHandler(enabled = !showChrome) { chrome = true }
    val hintPrefs = remember(context) { context.getSharedPreferences("settings", 0) }
    val toggleChrome: () -> Unit = {
        if (canHide && ui.hasImage) {
            chrome = !chrome
            if (!chrome && !hintPrefs.getBoolean("hint_immersive", false)) {
                hintPrefs.edit().putBoolean("hint_immersive", true).apply()
                vm.showMessage("轻点照片恢复界面")
            }
        }
    }

    LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    // UI2.md 2.1: 保存 sits at the right end of the bottom toolbar, under the thumb.
    val saveButton: @Composable () -> Unit = {
                    Box {
                        androidx.compose.material3.Button(
                            onClick = { exportMenu = true }, enabled = ui.hasImage && !ui.exporting,
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
                            modifier = Modifier.height(48.dp).coachAnchor(CoachStep.SAVE),
                        ) {
                            SaveMark(jobs, savedAt)
                            Spacer(Modifier.width(6.dp))
                            Text("保存")
                        }
                        DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
                            DropdownMenuItem(text = { Text("JPEG · 工作尺寸") }, onClick = {
                                exportMenu = false; exportWithNotice(ExportFormat.JPEG, ExportSize.FULL)
                            })
                            DropdownMenuItem(text = { Text("JPEG · 分享尺寸（长边不超过 3400）") }, onClick = {
                                exportMenu = false; exportWithNotice(ExportFormat.JPEG, ExportSize.SHARE)
                            })
                            for (target in listOf("sRGB", "Display P3")) {
                                DropdownMenuItem(text = { Text("JPEG 颜色预览 · $target") },
                                    enabled = !ui.loadingDetail && ui.busy == null,
                                    onClick = { exportMenu = false; vm.loadExportProof(target) })
                            }
                            // TIFF target selection is explicit for each export.
                            DropdownMenuItem(text = { Text("16 位 TIFF · sRGB") }, onClick = {
                                exportMenu = false; exportWithNotice(ExportFormat.TIFF, ExportSize.FULL, TiffColorSpace.SRGB)
                            })
                            DropdownMenuItem(text = { Text("16 位 TIFF · Display P3") }, onClick = {
                                exportMenu = false; exportWithNotice(ExportFormat.TIFF, ExportSize.FULL, TiffColorSpace.DISPLAY_P3)
                            })
                            DropdownMenuItem(text = { Text("16 位 TIFF · ProPhoto RGB") }, onClick = {
                                exportMenu = false; exportWithNotice(ExportFormat.TIFF, ExportSize.FULL, TiffColorSpace.PROPHOTO)
                            })
                            DropdownMenuItem(text = { Text("16 位 TIFF · Adobe RGB (1998)") }, onClick = {
                                exportMenu = false; exportWithNotice(ExportFormat.TIFF, ExportSize.FULL, TiffColorSpace.ADOBE_RGB)
                            })
                            if (HeifExport.available) DropdownMenuItem(text = { Text("HEIF · 工作尺寸（sRGB）") }, onClick = { exportMenu = false; exportWithNotice(ExportFormat.HEIF, ExportSize.FULL) })
                            DropdownMenuItem(
                                text = { Text(if (ui.tiledExport) "全分辨率导出（分块）" else "高质量导出（受设备上限限制）") },
                                onClick = {
                                    exportMenu = false; savedAt = System.currentTimeMillis(); vm.exportExtreme()
                                },
                            )
                        }
                    }
    }

    val nav = remember { EditorNav() }
    var showChanges by remember { mutableStateOf(false) }
    // The light table's view model is the activity's, so its 复制调整 is visible here too.
    val table: LightTableViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val tableState by table.state.collectAsState()
    var showPaste by remember { mutableStateOf(false) }
    androidx.compose.runtime.CompositionLocalProvider(LocalSliderDrag provides { on: Boolean -> dragging = on },
        LocalEditorNav provides nav) {
    Scaffold(
        snackbarHost = { SwipeNotice(snackbar) },
        contentWindowInsets = WindowInsets.systemBarsIgnoringVisibility,
        topBar = {
            TopAppBar(
                expandedHeight = 56.dp,
                modifier = Modifier.chrome(showChrome).graphicsLayer { alpha = recede },
                windowInsets = WindowInsets.systemBarsIgnoringVisibility.only(
                    androidx.compose.foundation.layout.WindowInsetsSides.Horizontal +
                        androidx.compose.foundation.layout.WindowInsetsSides.Top),
                title = {
                    Column {
                        Text(ui.source?.name ?: "Spektralab-X", style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        ui.source?.let { s ->
                            Text(
                                listOf(s.camera, "${s.width}×${s.height}").filter { it.isNotBlank() }
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { openMenu = true }, enabled = ui.ready && !ui.exporting) {
                            Icon(Icons.Default.Add, contentDescription = "打开")
                        }
                        DropdownMenu(expanded = openMenu, onDismissRequest = { openMenu = false }) {
                            DropdownMenuItem(text = { Text("从相册选择") }, onClick = {
                                openMenu = false
                                pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            })
                            DropdownMenuItem(text = { Text("从文件选择（RAW / DNG）") }, onClick = {
                                openMenu = false
                                pickFile.launch(arrayOf("*/*"))
                            })
                        }
                    }
                    val active = jobs.count { it.active }
                    // Undo / redo as icons; tasks and settings behind ⋯ (docs/UI.md 2.2).
                    // A running export stays visible: its count rides on the ⋯ button.
                    val canUndo = ui.canUndo && !ui.exporting
                    val canRedo = ui.canRedo && !ui.exporting
                    var confirmReset by remember { mutableStateOf(false) }
                    IconButton(onClick = { confirmReset = true }, enabled = ui.hasImage && !ui.exporting) {
                        Icon(Icons.Default.Refresh, contentDescription = "重置全部",
                            tint = if (ui.hasImage && !ui.exporting) Palette.OnSurface else Palette.OnSurfaceSoft.copy(alpha = 0.38f))
                    }
                    if (confirmReset) AlertDialog(
                        onDismissRequest = { confirmReset = false },
                        title = { Text("重置全部调整？") },
                        text = { Text("照片会回到刚打开时的样子：风格、胶片、相纸、全部参数、边框和裁剪都会还原，RAW 白平衡回到拍摄时。之后仍可以点撤销找回。") },
                        confirmButton = { TextButton(onClick = { confirmReset = false; vm.resetAll() }) { Text("重置", color = Palette.Warning) } },
                        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } },
                    )
                    ui.tiledResult?.let { result ->
                        AlertDialog(
                            onDismissRequest = { vm.consumeTiledResult() },
                            title = { Text("全分辨率导出（分块）") },
                            text = { Text(result, style = MaterialTheme.typography.bodySmall) },
                            confirmButton = { TextButton(onClick = { vm.consumeTiledResult() }) { Text("好") } },
                        )
                    }
                    IconButton(onClick = vm::undo, enabled = canUndo) {
                        HistoryIcon(redo = false, tint = if (canUndo) Palette.OnSurface else Palette.OnSurfaceSoft.copy(alpha = 0.38f))
                    }
                    IconButton(onClick = vm::redo, enabled = canRedo) {
                        HistoryIcon(redo = true, tint = if (canRedo) Palette.OnSurface else Palette.OnSurfaceSoft.copy(alpha = 0.38f))
                    }
                    Box {
                        var moreMenu by remember { mutableStateOf(false) }
                        IconButton(onClick = { moreMenu = true }) {
                            androidx.compose.material3.BadgedBox(badge = {
                                if (active > 0) androidx.compose.material3.Badge(containerColor = Palette.Accent,
                                    contentColor = Palette.OnAccent) { Text("$active") }
                            }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "更多")
                            }
                        }
                        DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                            DropdownMenuItem(text = { Text("观片台") }, onClick = { moreMenu = false; onLightTable() })
                            DropdownMenuItem(text = { Text("修改记录") }, enabled = ui.hasImage,
                                onClick = { moreMenu = false; showChanges = true })
                            // 粘贴调整 (ui2_snapseed.md 5): what 观片台's 复制调整 took, by group.
                            DropdownMenuItem(
                                text = { Text(tableState.copied?.let { "粘贴调整（来自「${it.fromName}」）" } ?: "粘贴调整（先在观片台复制）") },
                                enabled = ui.hasImage && !ui.exporting && tableState.copied != null,
                                onClick = { moreMenu = false; showPaste = true },
                            )
                            DropdownMenuItem(
                                text = { Text(if (active > 0) "正在处理（$active）" else "正在处理") },
                                onClick = { moreMenu = false; vm.showQueue(true) },
                            )
                            val update by Updater.state.collectAsState()
                            val waiting = update is Updater.State.Available || update is Updater.State.Ready
                            DropdownMenuItem(
                                text = { Text(if (waiting) "有新版本 · 查看" else "检查更新") },
                                onClick = {
                                    moreMenu = false
                                    if (waiting) Updater.show() else { Updater.show(); Updater.check(context, manual = true) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("设置") },
                                leadingIcon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                onClick = { moreMenu = false; showSettings = true },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val boxHeight = maxHeight
            val landscape = maxWidth > boxHeight
            if (landscape) {
                Row(Modifier.fillMaxSize()) {
                    Preview(ui, Modifier.weight(1f).fillMaxHeight(), onDetail = vm::loadDetail, onOpen = { openMenu = true },
                        onLightTable = onLightTable, chrome = barsShown, onToggleChrome = toggleChrome)
                    if (ui.hasImage) Controls(ui, vm, saveButton, true, {}, Modifier.width(360.dp).fillMaxHeight().chrome(showChrome))
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Preview(ui, Modifier.weight(1f).fillMaxWidth(), onDetail = vm::loadDetail, onOpen = { openMenu = true },
                        onLightTable = onLightTable, chrome = barsShown, onToggleChrome = toggleChrome)
                    // shots_review_1 P1: the empty page is the whole screen.
                    if (!ui.hasImage) return@Column
                    // The handle follows the finger 1:1; on release the panel springs to the
                    // nearest of its three heights, a fling carrying it one further.
                    // UI2.md 3.1: the height lives in an Animatable read only in the layout
                    // phase below, so a drag frame re-measures the panel and recomposes nothing.
                    val heights = listOf(96.dp, boxHeight * 0.55f, boxHeight * 0.75f)
                    val density = androidx.compose.ui.platform.LocalDensity.current
                    val anchors = remember(heights, density) { with(density) { heights.map { it.toPx() } } }
                    val panelPx = remember { androidx.compose.animation.core.Animatable(anchors[panelLevel]) }
                    var handleDown by remember { mutableStateOf(false) }
                    // Set on release: the finger's speed carried into the spring (px/s, + = taller).
                    var releaseVelocity by remember { mutableFloatStateOf(0f) }
                    var settleKey by remember { mutableIntStateOf(0) }
                    val panelScope = androidx.compose.runtime.rememberCoroutineScope()
                    val view = androidx.compose.ui.platform.LocalView.current
                    // Any level change (a drag, an entry tapped again, back) or a new screen size springs there.
                    LaunchedEffect(panelLevel, anchors, settleKey) {
                        if (handleDown) return@LaunchedEffect
                        val v = releaseVelocity
                        releaseVelocity = 0f
                        panelPx.settle(anchors[panelLevel], v)
                    }
                    // UI2.md 3.8: back from the tall panel folds it to half, following the finger.
                    val panelBack = rememberBackProgress()
                    PredictiveBack(enabled = showChrome && panelLevel == 2, progress = panelBack, resetOnCommit = true) {
                        panelLevel = 1
                    }
                    Column(
                        Modifier.fillMaxWidth()
                            .layout { measurable, constraints ->
                                val full = panelPx.value
                                val h = (full - (full - anchors[1]).coerceAtLeast(0f) * panelBack.value)
                                    .roundToInt().coerceIn(0, constraints.maxHeight)
                                val p = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                                layout(p.width, h) { p.place(0, 0) }
                            }
                            .chrome(showChrome),
                    ) {
                        Box(
                            Modifier.fillMaxWidth().height(20.dp).background(Palette.Surface)
                                .pointerInput(anchors) {
                                    val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                                    var at = 0f
                                    // bughunt_main 5: new anchors restart this block mid-drag, and a cancelled
                                    // coroutine never calls onDragCancel; the finally lets go of the panel
                                    // (and the 120 Hz vote) and springs it back to a height.
                                    try {
                                    detectVerticalDragGestures(
                                        onDragStart = {
                                            handleDown = true
                                            tracker.resetTracking()
                                            at = panelPx.value
                                            view.haptic(HapticEvent.GestureStart)
                                        },
                                        onDragEnd = {
                                            // A finger moving up (negative y) makes the panel taller.
                                            val v = -tracker.calculateVelocity().y
                                            val best = snapIndex(anchors, at, v, from = panelLevel)
                                            if (best != panelLevel) view.haptic(HapticEvent.Segment)
                                            releaseVelocity = v
                                            panelLevel = best
                                            handleDown = false
                                            settleKey++
                                        },
                                        onDragCancel = { handleDown = false; settleKey++ },
                                    ) { change, dy ->
                                        change.consume()
                                        tracker.addPosition(change.uptimeMillis, change.position)
                                        at = (at - dy).coerceIn(anchors.first(), anchors.last())
                                        val now = at
                                        panelScope.launch { panelPx.snapTo(now) }
                                    }
                                    } finally {
                                        if (handleDown) { handleDown = false; settleKey++ }
                                    }
                                }
                                .semantics {
                                    contentDescription = "面板高度"
                                    stateDescription = listOf("收起", "半屏", "展开")[panelLevel]
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(Modifier.width(36.dp).height(4.dp).background(Palette.Outline, RoundedCornerShape(2.dp)))
                        }
                        Controls(ui, vm, saveButton, panelOpen, { panelLevel = if (it) maxOf(1, panelLevel) else 0 },
                            Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
            ui.busy?.let { BusyOverlay(it) }
        }
    }
    }

    ui.error?.let { error ->
        val clipboard = LocalClipboardManager.current
        val report = remember(error, ui.deviceSummary, ui.engineSummary) {
            "$error\n\n${BuildConfig.VERSION_NAME} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · " +
                "Android ${android.os.Build.VERSION.RELEASE}\n${ui.deviceSummary}\n${ui.engineSummary}"
        }
        AlertDialog(
            onDismissRequest = vm::dismissError,
            confirmButton = { TextButton(onClick = vm::dismissError) { Text("好") } },
            dismissButton = {
                // The copy carries the whole diagnostics report, not just the message.
                TextButton(onClick = { clipboard.setText(AnnotatedString(report + "\n\n" + vm.diagnostics())) }) {
                    Text("复制")
                }
            },
            title = { Text("出错了") },
            text = {
                // Scrollable and selectable: an engine error is often long and is
                // what a bug report needs verbatim.
                SelectionContainer {
                    Text(report, Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()))
                }
            },
        )
    }

    if (showSettings) SettingsScreen(ui, vm) { showSettings = false }
    val copied = tableState.copied
    val pasteSrc = ui.source
    if (showPaste && copied != null && pasteSrc != null) PasteSheet(
        from = copied, count = 1, initial = tableState.pasteGroups,
        onDismiss = { showPaste = false },
        onPaste = { groups ->
            showPaste = false
            table.setPasteGroups(groups)
            // The open photo as a frame: its own shape, RAW-ness and EXIF date, so a pasted
            // date back takes this photo's date and a film edge gets this photo's gate crop.
            val here = Frame(id = "", uri = "", name = pasteSrc.name, size = 0, date = 0, mime = "",
                raw = pasteSrc.kind == "raw", width = pasteSrc.width, height = pasteSrc.height,
                exifDate = ui.exif.dateTimeOriginal.orEmpty())
            val p = pasteLook(Sidecar(ui.edit, pasteSrc.wbK, pasteSrc.wbTint), here, copied, groups, ui.catalog)
            vm.update(p.edit)
            if (here.raw && (p.wbK != pasteSrc.wbK || p.wbTint != pasteSrc.wbTint)) vm.setWhiteBalance(p.wbK, p.wbTint)
            vm.showMessage("已粘贴「${copied.fromName}」的调整，可撤销")
        },
    )
    if (showChanges) ChangesSheet(ui, vm, onGo = { c ->
        nav.tab = c.tab
        if (c.step >= 0) nav.step = c.step
        if (c.seg >= 0) nav.adjustSeg = c.seg
        panelLevel = maxOf(1, panelLevel)
        showChanges = false
    }) { showChanges = false }
    if (ui.showQueue) QueueSheet(jobs, vm::cancelJob, vm::retryJob, vm::clearFinishedJobs) { vm.showQueue(false) }
    ui.detail?.let {
        val target = ui.detailTarget
        DetailViewer(it, vm::closeDetail,
            title = if (target == null) "1:1" else "JPEG 颜色预览 · $target",
            note = if (target == null) null else "预览尺寸；不含纸框和 JPEG 压缩")
    }
    UpdateDialog(editing = ui.hasImage)
}

@Composable
private fun Preview(
    ui: UiState, modifier: Modifier, onDetail: () -> Unit = {}, onOpen: () -> Unit = {},
    onLightTable: () -> Unit = {}, chrome: Boolean = true, onToggleChrome: () -> Unit = {},
) {
    val toggle by androidx.compose.runtime.rememberUpdatedState(onToggleChrome)
    var scale by remember(ui.source) { mutableFloatStateOf(1f) }
    var offset by remember(ui.source) { mutableStateOf(Offset.Zero) }
    var holding by remember { mutableStateOf(false) }
    val view = androidx.compose.ui.platform.LocalView.current
    // The surround the user chose in 设置 (18 % 灰 / 黑 / 白), never a hue (UI2.md 1.1).
    val surround = rememberSurround()
    Box(modifier.background(surround), contentAlignment = Alignment.Center) {
        // Held down: the frame as it went into the engine, before any film.
        val image = if (holding && ui.before != null) ui.before else ui.image
        if (image != null) {
            val bitmap = remember(image) { image.asImageBitmap() }
            val gestures = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { toggle() },
                            onDoubleTap = { scale = 1f; offset = Offset.Zero },
                            onLongPress = {
                                holding = true
                                view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                            },
                            onPress = {
                                tryAwaitRelease()
                                holding = false
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            // Keep the point under the fingers fixed, and the image on screen.
                            val next = (scale * zoom).coerceIn(1f, 8f)
                            val q = centroid - Offset(size.width / 2f, size.height / 2f)
                            var o = q + pan - (q - offset) * (next / scale)
                            val maxX = size.width * (next - 1f) / 2f
                            val maxY = size.height * (next - 1f) / 2f
                            o = Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
                            scale = next
                            offset = if (next == 1f) Offset.Zero else o
                        }
                    }
                    // Lambda form: read in the draw phase, so a pinch frame does not recompose.
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale
                        translationX = offset.x; translationY = offset.y
                    }
            // When comparing with border enabled, original and print must align pixel-perfect.
            // Pass the real frame to maintain identical photo positioning, but suppress decoration.
            val frame = ui.edit.frame
            val showFrameDecoration = !(holding && ui.before != null)
            val caption = remember(ui.exif, ui.edit.film, ui.catalog) {
                Caption.of(ui.exif, ui.catalog.film(ui.edit.film)?.label ?: "")
            }
            // A film change crosses over from the old print (defaultEffects, no
            // overshoot); drag renders, tier upgrades and the held original swap
            // instantly, so live feedback is never delayed by an animation.
            val film = ui.edit.film
            var shownFilm by remember { mutableStateOf(film) }
            var back by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
            var front by remember { mutableStateOf(bitmap) }
            val fade = remember { androidx.compose.animation.core.Animatable(1f) }
            LaunchedEffect(bitmap) {
                if (bitmap === front) return@LaunchedEffect
                val cross = film != shownFilm && !holding && back == null
                shownFilm = film
                if (!cross) { front = bitmap; fade.snapTo(1f); return@LaunchedEffect }
                back = front; front = bitmap; fade.snapTo(0f)
                // UI2.md 3.7 / ui2_reactbits.md 8.2: alpha only, about 110 ms (fastEffects), so the old print
                // is gone before the eye settles on the new one; with animations off it is a cut.
                try { fade.animateTo(1f, Motion.fastEffects()) } finally { back = null }
            }
            Box(gestures.coachAnchor(CoachStep.HOLD)) {
                val under = back
                if (under != null) {
                    FramedImage(under, frame, caption, showFrameDecoration, Modifier.fillMaxSize())
                    FramedImage(front, frame, caption, showFrameDecoration, Modifier.fillMaxSize().graphicsLayer { alpha = fade.value })
                } else {
                    FramedImage(bitmap, frame, caption, showFrameDecoration, Modifier.fillMaxSize())
                }
                if (!holding) MaskOverlay(ui, Modifier.fillMaxSize())   // edit-effects: mask handles and brush (MaskPanel.kt)
            }
        } else if (ui.ready && !ui.hasImage) {
            Aura(Modifier.matchParentSize(), strength = 0.8f)
            EmptyState(onPick = onOpen, onLightTable = onLightTable)
        }
        // Shown only after 400 ms, so quick drag renders never flicker a bar.
        val showProgress by androidx.compose.runtime.produceState(false, ui.rendering) {
            value = false
            if (ui.rendering) { kotlinx.coroutines.delay(400); value = true }
        }
        if (showProgress) {
            // ui2_reactbits.md 8.2: next to the photo only a neutral line, never the coloured AuraBar.
            WorkLine(Modifier.align(Alignment.TopCenter))
        }
        // Histogram of what is on screen (the print, or the original while held).
        if (image != null) {
            var showHistogram by remember { mutableStateOf(false) }
            val histogram by androidx.compose.runtime.produceState<Histogram?>(null, image, showHistogram) {
                value = if (showHistogram)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Histogram.of(image) }
                else null
            }
            Column(Modifier.align(Alignment.TopStart).padding(8.dp).chrome(chrome)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OverlayChip(if (showHistogram) "隐藏直方图" else "直方图", on = showHistogram) { showHistogram = !showHistogram }
                    OverlayChip(if (ui.loadingDetail) "渲染中…" else "1:1",
                        enabled = !ui.loadingDetail && ui.imageTier.isNotEmpty(), onClick = onDetail)
                    // ui2_icecream.md 6.9: zoomed in, a visible way back besides the double tap.
                    if (scale != 1f || offset != Offset.Zero) OverlayChip("适应") { scale = 1f; offset = Offset.Zero }
                }
                Spacer(Modifier.height(6.dp))
                histogram?.let { h ->
                    // Grey luminance only: no colour over the photo while judging it (UI2.md 1.1).
                    MonoHistogram(h, Modifier.width(160.dp).height(80.dp)
                        .clip(Corners.s).background(Color.Black.copy(alpha = 0.5f)))
                    if (h.clippedLow > 0.005f || h.clippedHigh > 0.005f) {
                        Text(
                            String.format(Locale.ROOT, "暗部溢出 %.1f%% · 高光溢出 %.1f%%", h.clippedLow * 100, h.clippedHigh * 100),
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
        if (ui.image != null && holding && ui.before != null) {
            Text(
                "原图",
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).chrome(chrome)
                    .clip(Corners.s).background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * The photo's histogram as one grey luminance curve (ui2_snapseed.md 10): the
 * RGB curves in Histogram.kt paint red, green and blue next to the print being
 * judged. Square-root scale like HistogramView; clipping is a white tick at the
 * edge, as tall as the clipped share x10.
 */
@Composable
private fun MonoHistogram(h: Histogram, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val scale = kotlin.math.sqrt(maxOf(1, h.y.max()).toFloat())
        val bw = size.width / Histogram.BINS
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(0f, size.height)
            for (i in h.y.indices) {
                val top = size.height * (1f - kotlin.math.sqrt(h.y[i].toFloat()) / scale)
                lineTo(i * bw, top); lineTo((i + 1) * bw, top)
            }
            lineTo(size.width, size.height); close()
        }
        drawPath(path, Color.White.copy(alpha = 0.72f))
        fun tick(x: Float, share: Float) {
            if (share <= 0.001f) return
            val hgt = size.height * minOf(1f, share * 10)
            drawRect(Color.White, androidx.compose.ui.geometry.Offset(x, size.height - hgt),
                androidx.compose.ui.geometry.Size(3f, hgt))
        }
        tick(0f, h.clippedLow); tick(size.width - 3f, h.clippedHigh)
    }
}

/**
 * A pill over the photo: a dark scrim so it reads on a white sky as well as
 * a black one; "on" is shown by fill and a near-white label, not colour alone.
 * Pressed it shrinks to 0.96 (UI.md 1, 按钮按下).
 */
@Composable
private fun OverlayChip(text: String, on: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val press = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by press.collectIsPressedAsState()
    val scale by androidx.compose.animation.core.animateFloatAsState(
        if (pressed) 0.96f else 1f, Motion.fastSpatial(), label = "chip")
    Box(
        Modifier.heightIn(min = 36.dp).graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(Corners.s)   // photo overlays: 8 dp blocks, not pills (ui_ai_flavor_audit 8)
            .background(if (on) Palette.PanelHighest.copy(alpha = 0.9f) else Color.Black.copy(alpha = 0.45f))
            .clickable(enabled = enabled, interactionSource = press,
                indication = androidx.compose.material3.ripple(), onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = when {
            !enabled -> Color.White.copy(alpha = 0.4f)
            on -> Palette.Accent
            else -> Color.White.copy(alpha = 0.9f)
        })
    }
}

@Composable
private fun BusyOverlay(text: String) {
    // The Gemini-style glow (Aura.kt) over a dark scrim: engine start, opening
    // and decoding all wait on this, with nothing to judge colour against yet.
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Aura(Modifier.matchParentSize(), strength = 0.9f, ground = Palette.Background.copy(alpha = 0.92f))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, color = Palette.OnSurface.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Controls(
    ui: UiState, vm: EditorViewModel, save: @Composable () -> Unit,
    open: Boolean, onOpen: (Boolean) -> Unit, modifier: Modifier,
) {
    // Held by the screen (ChangesSheet.kt), so 修改记录 can open an entry at its step.
    val nav = LocalEditorNav.current
    var tab by nav::tab
    var step by nav::step
    val e = ui.edit
    val enabled = ui.hasImage && !ui.exporting
    Surface(modifier, tonalElevation = 2.dp) {
        Column {
            // Pages slide in the direction of travel and fade; the panel height is
            // fixed, so the photo above never moves.
            Box(Modifier.weight(1f).clipToBounds()) {
            androidx.compose.animation.AnimatedContent(
                targetState = tab,
                transitionSpec = { directionalSwap(targetState > initialState) },
                label = "tab",
            ) { page ->
            // This page is the AnimatedVisibilityScope shared frames read (LocalNavScope).
            androidx.compose.runtime.CompositionLocalProvider(LocalNavScope provides this) {
            Column(
                // UI2.md 7.2 point 1: rows 12 dp apart, groups 20; no dividers.
                Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(RowGap),
            ) {
                    when (page) {
                        0 -> {
                            PanelHeader(
                                title = "风格",
                                summary = if (e.noFilm) "原图" else ui.catalog.film(e.film)?.label.orEmpty(),
                            )
                        StyleStrip(ui, enabled, onEdit = vm::update, onNone = vm::clearStyle) { vm.applyStyle(it) }
                        PresetRow(ui, vm, enabled)
                        }
                        1 -> {
                            // A dot on each step that differs from what a new photo opens on (Edit.NONE, 无):
                            // a film chosen, a paper chosen, exposure moved, or the film's effects on or tuned.
                            val d = Edit()
                            val positive = ui.catalog.film(e.film)?.positive == true
                            val stepEdited = listOf(
                                !e.noFilm || e.filmFormatMm != d.filmFormatMm,
                                !e.noFilm && !e.paperNone(positive),
                                !e.autoExposure || e.exposureEv != 0.0 || !e.sceneCurve.isIdentity || e.printExposure != 1.0 || e.yShift != 0.0 || e.mShift != 0.0,
                                !e.effectsNone || e.scatterAmount != d.scatterAmount || e.couplersAmount != d.couplersAmount ||
                                    e.grainLayered != d.grainLayered || e.grainArea != d.grainArea || e.unsharpSigma != d.unsharpSigma ||
                                    e.extraEffectsTuned(d),
                            )
                            // The panel head names the pair being developed (ui2_derivatives.md).
                            PanelHeader(
                                title = "冲洗",
                                // Collapsed state at a glance (upstream SectionHeader): film · paper · exposure.
                                summary = listOfNotNull(if (e.noFilm) "无胶卷" else ui.catalog.film(e.film)?.label,
                                    if (e.noFilm) null else if (e.scanFilm) "直接扫描" else if (e.digitalIntermediate) "数字中间片" else ui.catalog.paper(e.paper)?.label,
                                    if (e.exposureEv != 0.0) String.format(java.util.Locale.ROOT, "%+.1f EV", e.exposureEv) else null,
                                ).joinToString(" · "),
                            )
                            DevelopSteps(step, stepEdited, enabled) { step = it }
                            if (e.noFilm && ui.source?.kind == "raw") {
                                GroupLabel("RAW 显影")
                                Segmented(options = listOf("线性显示", "标准显色（实验）"),
                                    selected = if (e.rawDevelopment == RawDevelopmentMode.NEUTRAL) 0 else 1,
                                    enabled = enabled) { i ->
                                    vm.update(e.copy(rawDevelopment = if (i == 0) RawDevelopmentMode.NEUTRAL else RawDevelopmentMode.STANDARD_V1))
                                }
                                if (e.rawDevelopment == RawDevelopmentMode.STANDARD_V1) {
                                    SwitchRow("RAW 自动曝光", e.rawAutoExposure, enabled) { vm.update(e.copy(rawAutoExposure = it)) }
                                    LabeledSlider("RAW 曝光", e.rawExposureEv, -8.0, 8.0, 0.0, enabled, "%+.2f EV", onDrag = vm::setInteracting) {
                                        vm.update(e.copy(rawExposureEv = it))
                                    }
                                    Hint("先调曝光，再应用相机内嵌曲线或标准曲线。实验显色不等同相机直出；按住对比仍显示线性原片。")
                                    TextButton(enabled = enabled, onClick = {
                                        vm.update(e.copy(rawDevelopment = RawDevelopmentMode.NEUTRAL, rawAutoExposure = true, rawExposureEv = 0.0))
                                    }) { Text("重置 RAW 显影") }
                                }
                            }
                            // 胶片「无」: the steps after the film have nothing to develop; they keep their settings.
                            val enabled = enabled && !(e.noFilm && step > 0)
                            if (e.noFilm && step > 0) FoldHint("没有选胶卷，这一步不起作用。")
                            when (step) {
                                0 -> {
                                FilmStrip(ui, enabled, onNone = vm::pickNoFilm) { vm.pickFilm(it) }
                                GroupLabel("画幅")
                                // One set, one track (UI2.md 7.2 point 8); a size not in the list selects nothing.
                                Segmented(
                                    options = FORMATS.map { it.first },
                                    selected = FORMATS.indexOfFirst { kotlin.math.abs(e.filmFormatMm - it.second) < 0.5 },
                                    enabled = enabled,
                                ) { i -> vm.update(e.copy(filmFormatMm = FORMATS[i].second)) }
                                    NextStep("相纸") { step = 1 }
                                }
                                1 -> {
                                PaperStrip(ui, enabled) { vm.update(it) }
                                    NextStep("曝光") { step = 2 }
                                }
                                2 -> {
                                    val d = Edit()
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Spacer(Modifier.weight(1f))
                                    StepReset(enabled, what = "曝光", changed = !e.autoExposure || e.exposureEv != 0.0 || e.printExposure != 1.0 ||
                                        e.yShift != 0.0 || e.mShift != 0.0 || !e.sceneCurve.isIdentity) {
                                        vm.update(e.copy(autoExposure = d.autoExposure, exposureEv = d.exposureEv,
                                            sceneCurve = SceneCurve.IDENTITY,
                                            printExposure = d.printExposure, yShift = d.yShift, mShift = d.mShift))
                                    }
                                    }
                                LabeledSlider("曝光补偿", e.exposureEv, -4.0, 4.0, 0.0, enabled, "%+.1f EV", onDrag = vm::setInteracting) {
                                    vm.update(e.copy(exposureEv = it))
                                }
                                // ui2_icecream.md 6.6: a grey word from the print's histogram, never a colour.
                                val shown = ui.image
                                val exposureWord by androidx.compose.runtime.produceState<String?>(null, shown) {
                                    value = shown?.let { b ->
                                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { Histogram.of(b) }
                                    }?.let(::exposureVerdict)
                                }
                                exposureWord?.let { Hint("画面：$it") }
                                LabeledSlider("放大曝光", e.printExposure, 0.2, 3.0, 1.0, enabled, "×%.2f", onDrag = vm::setInteracting) {
                                    vm.update(e.copy(printExposure = it))
                                }
                                SwitchRow("自动曝光", e.autoExposure, enabled) { vm.update(e.copy(autoExposure = it)) }
                                if (!e.noFilm) {
                                    GroupLabel("胶片前曲线")
                                    Hint("横轴为胶片曝光，纵轴为映射后的曝光：−8 至 +8 EV，中点为中灰。")
                                    CurveEditor(e.sceneCurve.display, null, enabled,
                                        includeLuma = true, editable = !e.sceneCurve.isSampled,
                                        onDrag = vm::setInteracting) { curves ->
                                        vm.update(e.copy(sceneCurve = SceneCurve.fromControls(curves)))
                                    }
                                    if (!e.sceneCurve.isIdentity) TextButton(enabled = enabled,
                                        onClick = { vm.update(e.copy(sceneCurve = SceneCurve.IDENTITY)) }) {
                                        Text(if (e.sceneCurve.isSampled) "重置采样曲线并开始编辑" else "重置所有通道")
                                    }
                                }
                                // UI2.md 2.3 ③: the colour filters and the RAW white balance behind 更多参数.
                                val expandAll = rememberExpandAll()
                                var moreExposure by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(expandAll) }
                                MoreRow(open = moreExposure, edited = e.yShift != 0.0 || e.mShift != 0.0 ||
                                    (ui.source?.wbK ?: 0.0) > 0) { moreExposure = !moreExposure }
                                if (!moreExposure) Hint(moreSummary(
                                    if (ui.source?.kind == "raw") "黄 / 品红滤镜、RAW 白平衡" else "黄 / 品红滤镜",
                                    "黄" to e.yShift.takeIf { it != 0.0 }?.let { String.format(java.util.Locale.ROOT, "%+.2f", it) },
                                    "品红" to e.mShift.takeIf { it != 0.0 }?.let { String.format(java.util.Locale.ROOT, "%+.2f", it) },
                                    "白平衡" to ui.source?.takeIf { it.wbK > 0 }?.let { String.format(java.util.Locale.ROOT, "%.0f K", it.wbK) },
                                ))
                                Expandable(moreExposure, verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(RowGap)) {
                                    LabeledSlider("黄滤镜", e.yShift, -1.0, 1.0, 0.0, enabled && !e.scanFilm, "%+.2f", onDrag = vm::setInteracting,
                                        disabledReason = "",
                                        ends = "偏黄" to "偏蓝") {
                                        vm.update(e.copy(yShift = it))
                                    }
                                    // Upstream measured it through the engine (EnlargerSection.swift): more
                                    // magenta filtration is a greener print, so the ends read the other way.
                                    LabeledSlider("品红滤镜", e.mShift, -1.0, 1.0, 0.0, enabled && !e.scanFilm, "%+.2f", onDrag = vm::setInteracting,
                                        ends = "偏品红" to "偏绿") {
                                        vm.update(e.copy(mShift = it))
                                    }
                                    // A control that cannot be used says why (ui2_derivatives.md 10).
                                    if (e.scanFilm) Hint("直接扫描没有放大机滤镜。")
                                    ui.source?.takeIf { it.kind == "raw" && it.asShotK > 0 }?.let { s ->
                                        WhiteBalance(s, ui.hasImage && !ui.exporting) { k, t -> vm.setWhiteBalance(k, t) }
                                    }
                                }
                                    NextStep("效果") { step = 3 }
                                }
                                else -> {
                                    val d = Edit()
                                    val fx = d.copy(film = e.film, paper = e.paper)
                                    fun Edit.effects() = listOf(grainActive, grainAmount, grainArea, grainLayered, halationActive,
                                        halationAmount, scatterAmount, glareActive, glareAmount, unsharpAmount, unsharpSigma, couplersAmount)
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Spacer(Modifier.weight(1f))
                                    StepReset(enabled, what = "效果", changed = e.effects() != fx.effects() || e.extraEffectsTuned(d)) {
                                        vm.update(e.copy(grainActive = d.grainActive, grainAmount = d.grainAmount, grainArea = d.grainArea,
                                            grainLayered = d.grainLayered, halationActive = d.halationActive, halationAmount = d.halationAmount,
                                            scatterAmount = d.scatterAmount, glareActive = d.glareActive, glareAmount = d.glareAmount,
                                            unsharpAmount = d.unsharpAmount, unsharpSigma = d.unsharpSigma, couplersAmount = d.couplersAmount)
                                            .withExtraEffectsReset(d))
                                    }
                                    }
                                    // 无 first (tools/work/no_film_research.md 4): no grain, halation, glare or sharpening.
                                    Segmented(options = listOf("无", "胶片自带"), selected = if (e.effectsNone) 0 else 1, enabled = enabled) { i ->
                                        vm.update(if (i == 0) e.withoutEffects() else e.copy(grainActive = true, halationActive = true,
                                            glareActive = true, unsharpAmount = if (e.unsharpAmount == 0.0) Edit.SCAN_UNSHARP else e.unsharpAmount))
                                    }
                                EffectsSection(e, enabled, onDrag = vm::setInteracting) { vm.update(it) }
                                DustSection(e.look, enabled, onDrag = vm::setInteracting) { vm.update(e.copy(look = it)) }   // edit-effects: 灰尘 (MaskPanel.kt)
                                }
                            }
                        }
                        2 -> AdjustPage(ui, vm, enabled)
                        3 -> {
                            val src = ui.source
                            // UI2.md 7.2 point 2: the head says which border is on and offers to take it off.
                            val border = Borders.byId(e.borderId)
                            PanelHeader(
                                title = "边框",
                                summary = listOfNotNull(
                                    border?.takeIf { it.id != "none" }?.label ?: "无边框",
                                    if (e.filmEdge) "片边" else null,
                                    if (e.frame.perf != null) "齿孔" else null,
                                    if (e.dateImprint) "日期" else null,
                                ).joinToString(" · "),
                            )
                        BorderPicker(e.borderId, enabled && src != null, noFilm = e.noFilm) { p ->
                            if (src != null) vm.update(Edit.withBorder(e, p, src.width, src.height))
                        }
                        Borders.byId(e.borderId)?.let { p ->
                            Hint(p.hint)
                        }
                        // Sprocket strips only: any photo, any film, no crop, nothing re-developed.
                        val perf = e.frame.perf
                        SwitchRow("只加齿孔", perf != null, enabled && !e.filmEdge,
                            reason = if (enabled && e.filmEdge) "胶片边框已经带齿孔；关掉胶片边框才能单独加。" else "") { on ->
                            vm.update(e.copy(frame = e.frame.copy(perf = if (on) Perforation() else null)))
                        }
                        if (perf != null) {
                            fun setPerf(p: Perforation) = vm.update(e.copy(frame = e.frame.copy(perf = p)))
                            // Two separate either-or sets, each in its own track (UI2.md 7.2 point 8).
                            val wheres = listOf("both" to "上下", "top" to "只上", "bottom" to "只下")
                            Segmented(wheres.map { it.second }, wheres.indexOfFirst { it.first == perf.where }, enabled) { i ->
                                setPerf(perf.copy(where = wheres[i].first))
                            }
                            val shapes = listOf("ks" to "方孔 KS", "bh" to "圆孔 BH")
                            Segmented(shapes.map { it.second }, shapes.indexOfFirst { it.first == perf.shape }, enabled) { i ->
                                setPerf(perf.copy(shape = shapes[i].first))
                            }
                            GroupLabel("片基颜色")
                            PillRow {
                                for ((c, label) in Perforation.BASES) {
                                    SelectPill(selected = perf.base == c,
                                        onClick = { setPerf(perf.copy(base = c, holes = Perforation.holesFor(c))) },
                                        label = { Text(label) }, enabled = enabled)
                                }
                            }
                            SwitchRow("片基字", perf.edgeText, enabled) { setPerf(perf.copy(edgeText = it)) }
                            androidx.compose.material3.OutlinedTextField(
                                value = perf.text, onValueChange = { setPerf(perf.copy(text = it.take(32))) },
                                singleLine = true, enabled = enabled && perf.edgeText, modifier = Modifier.fillMaxWidth(),
                                label = { Text("片基文字（留空用胶片名）") },
                            )
                            SwitchRow("帧号", perf.numbers, enabled && perf.edgeText,
                                reason = if (enabled && !perf.edgeText) "片基字打开后才印文字和帧号。" else "") { setPerf(perf.copy(numbers = it)) }
                        }
                        if (e.filmEdge) {
                            GroupLabel("片边")
                            val modes = listOf("strip" to "整条底片", "filed" to "锉边")
                            Segmented(modes.map { it.second }, modes.indexOfFirst { it.first == e.edgeMode }, enabled) { i ->
                                vm.update(e.copy(edgeMode = modes[i].first))
                            }
                            val holes = listOf("white" to "扫描 · 白孔", "black" to "相纸 · 黑孔")
                            Segmented(holes.map { it.second }, holes.indexOfFirst { it.first == e.edgeHoles }, enabled) { i ->
                                vm.update(e.copy(edgeHoles = holes[i].first))
                            }
                            LabeledSlider("边缘雾化", e.edgeFog, 0.0, 3.0, 1.0, enabled, "%.1f", onDrag = vm::setInteracting) {
                                vm.update(e.copy(edgeFog = it))
                            }
                            LabeledSlider("片轴漏光", e.edgeLeaks, 0.0, 3.0, 0.0, enabled, "%.1f", onDrag = vm::setInteracting) {
                                vm.update(e.copy(edgeLeaks = it))
                            }
                            // Upstream's gate families and carriers (overscan.cpp accepts exactly these).
                            GroupLabel("片门")
                            PillRow {
                                for ((v, label) in listOf("auto" to "随机", "square" to "直角", "rounded" to "圆角", "eared" to "耳朵",
                                    "shouldered" to "肩形", "kicked" to "斜角")) {
                                    SelectPill(selected = e.edgeGate == v, onClick = { vm.update(e.copy(edgeGate = v)) },
                                        label = { Text(label) }, enabled = enabled)
                                }
                            }
                            GroupLabel("片夹")
                            PillRow {
                                for ((v, label) in listOf("none" to "无", "black" to "黑色片夹", "open" to "开口片夹")) {
                                    SelectPill(selected = e.edgeCarrier == v, onClick = { vm.update(e.copy(edgeCarrier = v)) },
                                        label = { Text(label) }, enabled = enabled)
                                }
                            }
                            SwitchRow("使用真实片名", e.edgeRealName, enabled) { vm.update(e.copy(edgeRealName = it)) }
                            if (e.edgeRealName && e.edgeText.isNotBlank()) {
                                Hint("片边印字：${e.edgeText}")
                            }
                            LabeledSlider("画幅编号", e.edgeFrameNumber.toDouble(), 0.0, 99.0, 0.0, enabled, "%.0f") {
                                vm.update(e.copy(edgeFrameNumber = Math.round(it).toInt().coerceIn(0, 99)))
                            }
                            Hint("0 为随机编号。")
                            TonalButton("重新抽取片门和漏光", enabled = enabled) {
                                // New "camera" and "this frame": gate centring, weave and leak positions re-drawn.
                                val r = java.util.Random()
                                vm.update(e.copy(edgeCameraSeed = 1 + r.nextInt(Int.MAX_VALUE - 1),
                                    edgeFrameSeed = 1 + r.nextInt(Int.MAX_VALUE - 1)))
                            }
                        }
                        if (!e.frame.isNone) {
                            GroupLabel("相纸")
                            // 48 dp targets around 30 dp swatches; the chosen one wears the card's ring (UI2.md 1.2).
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (c in FrameSpec.PAPERS) {
                                    val on = e.frame.paper == c
                                    Box(
                                        Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape)
                                            .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.RadioButton) {
                                                vm.update(e.copy(frame = e.frame.copy(paper = c)))
                                            },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Box(Modifier.size(42.dp).border(2.dp, if (on) Palette.Accent else Color.Transparent,
                                            androidx.compose.foundation.shape.CircleShape))
                                        Box(Modifier.size(30.dp).background(Color(c), androidx.compose.foundation.shape.CircleShape)
                                            .border(1.dp, Palette.Outline, androidx.compose.foundation.shape.CircleShape))
                                    }
                                }
                            }
                            // A data bar has only a bottom band; every other card scales as a whole.
                            val bar = e.frame.side == 0f && e.frame.top == 0f
                            val cur = if (bar) e.frame.bottom else maxOf(e.frame.side, e.frame.top)
                            val presetFrame = Borders.byId(e.borderId)?.frame
                            val def = presetFrame?.let { if (bar) it.bottom else maxOf(it.side, it.top) } ?: cur
                            LabeledSlider("边宽", cur.toDouble(), 0.01, 0.3, def.toDouble(), enabled, "%.2f") { v ->
                                val f = e.frame
                                val next = if (bar) f.copy(bottom = v.toFloat()) else {
                                    val r = (v / cur.coerceAtLeast(0.001f)).toFloat()
                                    f.copy(side = f.side * r, top = f.top * r, bottom = f.bottom * r)
                                }
                                vm.update(e.copy(frame = next))
                            }
                            SwitchRow("参数文字", e.frame.caption, enabled) { vm.update(e.copy(frame = e.frame.copy(caption = it))) }
                            BrandPanel(ui, vm, enabled)
                            if (e.frame.sign) {
                                androidx.compose.material3.OutlinedTextField(
                                    value = e.frame.signText,
                                    onValueChange = { vm.update(e.copy(frame = e.frame.copy(signText = it.take(40)))) },
                                    singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth(),
                                    label = { Text("签名") },
                                )
                            }
                            Hint("相纸在导出 JPEG 时加上；16 位 TIFF 只含胶片本身。")
                        }
                        // bughunt_main 1: 胶片「无」 skips the pipeline that draws the edge and the date back.
                        SwitchRow("胶片边框", e.filmEdge, enabled && src != null && !e.noFilm,
                            reason = if (enabled && e.noFilm) "没有选胶卷，画不出片边。" else "") { on ->
                            if (src != null) vm.update(Edit.withFilmEdge(e, src.width, src.height, on).let {
                                if (!on && Borders.byId(e.borderId)?.edge != null && e.frame.isNone) it.copy(borderId = "none") else it
                            }.let {
                                // An instant card crops to its own shape, which the gate would cut (UI.md E.3.1).
                                if (on && (Borders.byId(e.borderId)?.crop ?: 0.0) > 0.0) it.copy(frame = FrameSpec(), borderId = "none") else it
                            })
                        }
                        if (e.filmEdge && src != null) {
                            PillRow {
                                for ((id, label, _) in Edit.EDGE_FORMATS) {
                                    SelectPill(
                                        selected = e.edgeFormat == id,
                                        onClick = {
                                            var next = Edit.withFilmEdge(e, src.width, src.height, true, id)
                                            if (!Edit.dateBackFits(id)) next = next.copy(dateImprint = false)
                                            vm.update(next)
                                        },
                                        label = { Text(label) },
                                        enabled = enabled,
                                    )
                                }
                            }
                            Hint("边框打开时画面会裁成片门的比例；裁剪比例按钮暂时不可用。")
                        }
                        val dateOk = !e.filmEdge || Edit.dateBackFits(e.edgeFormat)
                        SwitchRow("日期戳", e.dateImprint, enabled && dateOk && e.dateText.isNotBlank() && !e.noFilm, reason = when {
                            !enabled -> ""
                            e.noFilm -> "没有选胶卷，日期印不上。"
                            e.dateText.isBlank() -> "这张照片没有拍摄时间，不能加日期戳。"
                            !dateOk -> "宽幅画幅没有日期后背。"
                            else -> ""
                        }) {
                            vm.update(e.copy(dateImprint = it))
                        }
                        Hint(when {
                                e.dateText.isBlank() -> "这张照片没有拍摄时间，不能加日期戳。"
                                !dateOk -> "宽幅画幅没有日期后背。"
                                else -> "日期：${e.dateText}（取自拍摄时间）"
                            })
                        if (e.dateImprint && dateOk) {
                            // Upstream's limits (FilmEdge.swift): the data face and between-frames
                            // placement need a film edge; 120 backs print in the margin.
                            GroupLabel("日期后背")
                            PillRow {
                                for ((v, label) in listOf("lcd" to "橙色 LCD", "dots" to "点阵", "data" to "数据后背")) {
                                    SelectPill(selected = e.dateStyle == v, onClick = { vm.update(e.copy(dateStyle = v)) },
                                        label = { Text(label) }, enabled = enabled && (v != "data" || e.filmEdge))
                                }
                            }
                            if (!e.filmEdge) Hint("数据后背印在片边上，要先打开胶片边框。")
                            if (e.dateStyle == "data") Hint("数据后背按相机的格式印，顺序不能改。")
                            val orders = listOf("japan" to "年 月 日", "us" to "月 日 年")
                            Segmented(orders.map { it.second }, orders.indexOfFirst { it.first == e.dateOrder },
                                enabled && e.dateStyle != "data") { i ->
                                val v = orders[i].first
                                vm.update(e.copy(dateOrder = v, dateText = Edit.dateInOrder(e.dateText, v)))
                            }
                            // Where first, then which corner: the corner only matters on the picture.
                            val places = listOf("frame" to "印在画面", "rebate" to "印在帧间")
                            Segmented(places.map { it.second }, places.indexOfFirst { it.first == e.datePlacement }, enabled,
                                optionEnabled = { places[it].first == "frame" || e.rebateDateFits }) { i ->
                                vm.update(e.copy(datePlacement = places[i].first))
                            }
                            if (!e.rebateDateFits) Hint("印在帧间需要 135 或半格的胶片边框。")
                            else if (e.datePlacement != "frame") Hint("印在帧间时位置由片边决定，不用选角落。")
                            val corners = listOf("br" to "右下", "bl" to "左下", "tr" to "右上", "tl" to "左上")
                            Segmented(corners.map { it.second }, corners.indexOfFirst { it.first == e.dateCorner },
                                enabled && e.datePlacement == "frame") { i ->
                                vm.update(e.copy(dateCorner = corners[i].first))
                            }
                            LabeledSlider("字号", e.dateSize, 0.4, 3.0, 1.0, enabled, "%.1f", onDrag = vm::setInteracting) {
                                vm.update(e.copy(dateSize = it))
                            }
                            LabeledSlider("亮度", e.dateEv, -2.0, 8.0, 3.5, enabled, "%+.1f EV", onDrag = vm::setInteracting) {
                                vm.update(e.copy(dateEv = it))
                            }
                            // ui2_icecream.md 6.4: greyed with the reason, not hidden, so the user knows they exist.
                            val onPicture = e.datePlacement == "frame"
                            LabeledSlider("横向边距", e.dateInsetX, 0.0, 30.0, 3.0, enabled && onPicture, "%.1f mm", onDrag = vm::setInteracting) {
                                vm.update(e.copy(dateInsetX = it))
                            }
                            LabeledSlider("纵向边距", e.dateInsetY, 0.0, 30.0, 2.4, enabled && onPicture, "%.1f mm", onDrag = vm::setInteracting) {
                                vm.update(e.copy(dateInsetY = it))
                            }
                            // One reason for both margins, under the pair (shots_review_3).
                            if (enabled && !onPicture) Hint("边距只在印在画面时有用。")
                            if (e.dateStyle != "data") {
                                SwitchRow("同时印拍摄参数", e.dateWithData, enabled && e.filmEdge && e.dateDataText.isNotBlank(), reason = when {
                                    !enabled -> ""
                                    !e.filmEdge -> "拍摄参数印在片边上，要先打开胶片边框。"
                                    e.dateDataText.isBlank() -> "这张照片没有快门、光圈或焦距信息。"
                                    else -> ""
                                }) {
                                    vm.update(e.copy(dateWithData = it))
                                }
                            }
                            // The switch above already says why it is off (no data, no film edge);
                            // this line only shows what will print (shots_review_3).
                            if (e.dateDataText.isNotBlank() && e.filmEdge) Hint("拍摄参数：${e.dateDataText}")
                            // 数据后背 has no switch to carry the reason, so it is said here once.
                            else if (e.dateStyle == "data" && e.dateDataText.isBlank()) Hint("这张照片没有快门、光圈或焦距信息，数据后背无内容。")
                            // A RAW without lens or exposure data: the user picks the
                            // same-shot JPEG (one URI through the picker, no permission).
                            // Registered unconditionally, so a result survives recreation.
                            val pickJpeg = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
                                uri?.let(vm::fillExifFrom)
                            }
                            if (ui.source?.kind == "raw" && ui.exif.missingShooting) {
                                androidx.compose.material3.TextButton(enabled = enabled, onClick = {
                                    pickJpeg.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }) { Text("从同名 JPEG 补全参数") }
                            }
                        }
                        }
                        else -> {
                            PanelHeader(
                                title = "构图",
                                summary = listOfNotNull(
                                    ASPECTS.firstOrNull { kotlin.math.abs(e.cropAspect - it.second) < 1e-3 }?.first ?: "自定",
                                    if (e.quarterTurns % 4 != 0) "转 ${e.quarterTurns % 4 * 90}°" else null,
                                    if (e.flipH) "翻转" else null,
                                ).joinToString(" · "),
                            )
                        val src = ui.source
                        // With the film edge on, the crop must stay the gate's shape in the new orientation.
                        fun reframe(next: Edit) = when {
                            src == null -> next
                            next.filmEdge -> Edit.withFilmEdge(next.copy(cropX = 0.0, cropY = 0.0, cropW = 1.0, cropH = 1.0),
                                src.width, src.height, on = true)
                            else -> Edit.centredCrop(next, src.width, src.height, next.cropAspect)
                        }
                        // UI2.md 2.2: the ratio is the first thing on the page; turning and flipping under it.
                        PillRow {
                            for ((label, aspect) in ASPECTS) {
                                SelectPill(
                                    selected = kotlin.math.abs(e.cropAspect - aspect) < 1e-3,
                                    onClick = { vm.update(reframe(e.copy(cropAspect = aspect))) },
                                    label = { Text(label) },
                                    enabled = enabled && !e.filmEdge,
                                )
                            }
                        }
                        if (e.filmEdge) Hint("胶片边框打开时，比例跟着片门走；关掉边框才能选。")
                        GroupLabel("方向")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TonalButton("向左转 90°", enabled = enabled, modifier = Modifier.weight(1f)) { vm.update(reframe(e.turned(3))) }
                            TonalButton("向右转 90°", enabled = enabled, modifier = Modifier.weight(1f)) { vm.update(reframe(e.turned(1))) }
                        }
                        SwitchRow("水平翻转", e.flipH, enabled) { vm.update(e.copy(flipH = it)) }
                        }
                    }
            }
            }
            }
            }
            // Bottom category bar: icon + short label, thumb-reachable (docs/UI.md 2.2).
            // A dot on each entry whose part of the edit differs from a fresh one (UI2.md 7.2 point 10).
            val fresh = Edit()
            val entryEdited = listOf(
                false,
                e.film != fresh.film || e.paper != fresh.paper || e.scanFilm != fresh.scanFilm ||
                    e.filmFormatMm != fresh.filmFormatMm || !e.autoExposure || e.exposureEv != 0.0 || !e.sceneCurve.isIdentity || e.rawDevelopmentChanged ||
                    e.printExposure != 1.0 || e.yShift != 0.0 || e.mShift != 0.0,
                e.look != Look.NEUTRAL,
                e.borderId != "none" || e.filmEdge || e.frame.perf != null || e.dateImprint,
                e.cropAspect != 0.0 || e.quarterTurns % 4 != 0 || e.flipH,
            )
            CategoryBar(selected = tab, enabled = true, trailing = save, edited = entryEdited) { i ->
                if (i == tab) onOpen(!open) else { tab = i; onOpen(true) }
            }
        }
    }
}

/** One line under a panel's title: what this step is for, in plain words (UI2.md 2.6). */
@Composable
private fun PanelHint(text: String) = FoldHint(text)

/** The darkroom order (UI2.md 2.3): film, paper, exposure and enlarging, the film's own effects. */
private val DEVELOP_STEPS = listOf("胶卷", "相纸", "曝光", "效果")

@Composable
private fun DevelopSteps(step: Int, edited: List<Boolean>, enabled: Boolean, onStep: (Int) -> Unit) {
    val view = androidx.compose.ui.platform.LocalView.current
    // ui_ai_flavor_audit 9: four equal segments, all on one row; order is the darkroom's.
    Segmented(DEVELOP_STEPS, step, enabled, Modifier.padding(bottom = 4.dp),
        edited = { edited.getOrElse(it) { false } }) { i -> view.tick(); onStep(i) }
}

/**
 * 重置这一项 (UI2.md 2.5): one step back to its defaults, no confirmation, one undo.
 * Upstream's rule: a reset says what it resets (ui2_derivatives.md). Shown only
 * once something changed, so an untouched step has nothing to second-guess.
 */
@Composable
private fun StepReset(enabled: Boolean, changed: Boolean, what: String = "这一项", onReset: () -> Unit) {
    if (!changed) return
    QuietButton("重置$what", enabled = enabled, onClick = onReset)
}

/** The step's last row: the next step as a quiet pill on the right, after a group's worth of space. */
@Composable
private fun NextStep(name: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = GroupGap - RowGap), horizontalArrangement = Arrangement.End) {
        QuietButton("下一步：$name", onClick = onClick,
            modifier = Modifier.background(Palette.Panel, Corners.m))
    }
}

private val FORMATS = listOf("半格" to 24.0, "135" to 36.0, "645" to 56.0, "6×7" to 70.0, "4×5" to 127.0)

/**
 * RAW white balance. It is applied at decode, so a change re-opens the file:
 * the sliders move freely and commit only when released.
 */
@Composable
private fun WhiteBalance(s: SourceInfo, enabled: Boolean, onCommit: (Double, Double) -> Unit) {
    // The same slider as every other parameter (ui-audit.md, 冲洗 ③). A drag only moves the
    // readout and commits on release; a tap, a typed value or a reset commits at once.
    // The default is the camera's as-shot setting, so ↺ and the default dot mean 原始.
    val currentK = if (s.wbK > 0) s.wbK else s.asShotK
    val currentTint = if (s.wbK > 0) s.wbTint else s.asShotTint
    var k by remember(s) { mutableFloatStateOf(currentK.toFloat().coerceIn(2000f, 12000f)) }
    var tint by remember(s) { mutableFloatStateOf(currentTint.toFloat().coerceIn(-150f, 150f)) }
    var dragging by remember { mutableStateOf(false) }
    fun commit() {
        val asShot = kotlin.math.abs(k - s.asShotK) < 1 && kotlin.math.abs(tint - s.asShotTint) < 0.5
        if (asShot) { if (s.wbK > 0) onCommit(0.0, 0.0) } else onCommit(k.toDouble(), tint.toDouble())
    }
    GroupLabel("RAW 白平衡")
    LabeledSlider("色温", k.toDouble(), 2000.0, 12000.0, s.asShotK.coerceIn(2000.0, 12000.0), enabled, "%.0f K",
        onDrag = { on -> dragging = on; if (!on) commit() }) { k = it.toFloat(); if (!dragging) commit() }
    LabeledSlider("色调", tint.toDouble(), -150.0, 150.0, s.asShotTint.coerceIn(-150.0, 150.0), enabled, "%+.0f",
        onDrag = { on -> dragging = on; if (!on) commit() }) { tint = it.toFloat(); if (!dragging) commit() }
    Hint("松手后重新解码，需要几秒。")
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, enabled: Boolean, reason: String = "", onChange: (Boolean) -> Unit) =
    SettingRow(label, checked, enabled, disabledReason = reason, onChange = onChange)

/** FilmStrip's two extra groups, after the four kinds (0..3). */
private const val FAV = 10
private const val RECENT = 11

/** The film cards: each film on the open photo. Films whose card has not rendered yet show their name only. */
@Composable
private fun FilmStrip(ui: UiState, enabled: Boolean, onNone: () -> Unit = {}, onPick: (String) -> Unit) {
    if (!ui.hasImage || ui.catalog.films.isEmpty()) return
    // UI2.md 2.3 ①: four kinds of film, the current one's kind open first.
    val kinds = listOf("彩色负片", "反转片", "电影卷", "黑白")
    fun kindOf(s: Stock) = when {
        s.bw -> 3
        s.positive -> 1
        s.id.contains("vision3") || s.id.startsWith("cinestill") -> 2
        else -> 0
    }
    var kind by androidx.compose.runtime.saveable.rememberSaveable {
        mutableStateOf(ui.catalog.film(ui.edit.film)?.let(::kindOf) ?: 0)
    }
    // 收藏 and 最近 come first, each only once it has something (ui2_derivatives.md 9).
    val picks = rememberPicks("film")
    val favs = picks.favourites.mapNotNull { id -> ui.catalog.films.firstOrNull { it.id == id } }
    val recents = picks.recent.mapNotNull { id -> ui.catalog.films.firstOrNull { it.id == id } }
    if ((kind == FAV && favs.isEmpty()) || (kind == RECENT && recents.isEmpty())) kind = 0
    PillRow(Modifier.padding(bottom = 4.dp)) {
        if (favs.isNotEmpty()) SelectPill(selected = kind == FAV, onClick = { kind = FAV }, label = { Text("收藏 ${favs.size}") })
        if (recents.isNotEmpty()) SelectPill(selected = kind == RECENT, onClick = { kind = RECENT }, label = { Text("最近") })
        kinds.forEachIndexed { i, name ->
            val n = ui.catalog.films.count { kindOf(it) == i }
            if (n > 0) SelectPill(selected = kind == i, onClick = { kind = i }, label = { Text("$name $n") })
        }
    }
    val films = when (kind) {
        FAV -> favs
        RECENT -> recents
        else -> ui.catalog.films.filter { kindOf(it) == kind }
    }
    val view = androidx.compose.ui.platform.LocalView.current
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    // The current film is brought into view when the strip appears or the film
    // changes from elsewhere (a preset, undo), with the motion spring.
    // Item 0 is 无.
    val selectedIndex = if (ui.edit.noFilm) 0 else films.indexOfFirst { it.id == ui.edit.film }.let { if (it < 0) -1 else it + 1 }
    LaunchedEffect(selectedIndex) {
        if (selectedIndex < 0) return@LaunchedEffect
        val visible = state.layoutInfo.visibleItemsInfo
        val shown = visible.any { it.index == selectedIndex && it.offset >= 0 &&
            it.offset + it.size <= state.layoutInfo.viewportEndOffset }
        if (!shown) state.animateScrollToItem(maxOf(0, selectedIndex - 1))
    }
    LazyRow(
        state = state,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
        // Flings settle on a card edge rather than halfway through one.
        flingBehavior = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(state),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // 无 leads the strip and is a new photo's default (tools/work/no_film_research.md).
        item(key = "_none") {
            val original = remember(ui.before) { ui.before?.asImageBitmap() }
            NoneCard(selected = ui.edit.noFilm, original = original, enabled = enabled, size = 88.dp) {
                if (!ui.edit.noFilm) { view.tick(); onNone() }
            }
        }
        items(films, key = { it.id }) { film ->
            val selected = !ui.edit.noFilm && film.id == ui.edit.film
            val card = ui.filmCards[film.id]
            val bitmap = remember(card) { card?.asImageBitmap() }
            // UI2.md 7.2 points 3-4: a 104 dp square, the name, and ISO · balance · one trait.
            // Before its card renders, the stock's own name stands in for the picture.
            ThumbCard(
                selected = selected,
                image = bitmap,
                title = film.label.removePrefix("Kodak ").removePrefix("Fujifilm ").removePrefix("Ilford "),
                subtitle = FilmNotes.line(film),
                enabled = enabled,
                placeholder = film.label, size = 88.dp,
                starred = picks.isFavourite(film.id),
                onLongClick = { view.tick(strong = true); picks.toggleFavourite(film.id) },
            ) { if (!selected) { view.tick(); picks.used(film.id); onPick(film.id) } }
        }
    }
}

private val ASPECTS = listOf(
    "原始" to 0.0, "3:2" to 1.5, "2:3" to 2.0 / 3.0, "4:3" to 4.0 / 3.0, "3:4" to 0.75,
    "1:1" to 1.0, "16:9" to 16.0 / 9.0, "XPan 65:24" to 65.0 / 24.0,
)

@Composable
private fun PresetRow(ui: UiState, vm: EditorViewModel, enabled: Boolean) {
    var menu by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.loadPresets() }
    // shots_review_1 P2: saving is 我的 → + 存为风格 now; presets made before stay reachable here.
    if (ui.presets.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            TonalButton("套用旧预设（${ui.presets.size}）",
                enabled = enabled && ui.presets.isNotEmpty()) { menu = true }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                for (p in ui.presets) {
                    DropdownMenuItem(
                        text = { Text(p.name) },
                        onClick = { menu = false; vm.applyPreset(p) },
                        trailingIcon = {
                            TextButton(onClick = { menu = false; deleting = p.name }) { Text("删除") }
                        },
                    )
                }
            }
        }
    }
    if (naming) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { naming = false },
            confirmButton = {
                TextButton(onClick = { naming = false; vm.savePreset(name) }, enabled = name.isNotBlank()) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("取消") } },
            title = { Text("存为预设") },
            text = {
                Column {
                    androidx.compose.material3.OutlinedTextField(
                        value = name, onValueChange = { name = it.take(40) }, singleLine = true,
                        label = { Text("名称") },
                    )
                    Text(
                        "保存胶片、相纸、放大和效果参数；曝光、裁剪和白平衡属于单张照片，不保存。同名会覆盖。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
    }
    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            confirmButton = { TextButton(onClick = { deleting = null; vm.deletePreset(target) }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
            title = { Text("删除预设") },
            text = { Text("删除“$target”？") },
        )
    }
}

/**
 * The print on its paper frame, scaled to fit. With no frame it is a plain
 * fitted image. Drawn rather than composed, so the frame costs no bitmap.
 */
@Composable
private fun FramedImage(
    bitmap: androidx.compose.ui.graphics.ImageBitmap,
    frame: FrameSpec,
    caption: Caption,
    showDecoration: Boolean,
    modifier: Modifier,
) {
    if (frame.isNone) {
        Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier)
        return
    }
    val androidBitmap = remember(bitmap) { bitmap.asAndroidBitmap() }
    androidx.compose.foundation.Canvas(modifier) {
        val l = frame.layout(bitmap.width, bitmap.height)
        val k = minOf(size.width / l.width, size.height / l.height)
        val ox = (size.width - l.width * k) / 2f
        val oy = (size.height - l.height * k) / 2f
        drawIntoCanvas { c ->
            val n = c.nativeCanvas
            n.save(); n.translate(ox, oy); n.scale(k, k)
            if (showDecoration) {
                // Full card: paper or blur, strips, print, hairline, text.
                FrameSpec.drawCard(n, frame, l, androidBitmap, caption)
            } else {
                // Compare mode: draw only the photo itself, aligned to where it would be with the frame.
                // The photo rect within the card is at (l.x, l.y).
                n.drawBitmap(androidBitmap, null,
                    android.graphics.RectF(l.x.toFloat(), l.y.toFloat(),
                        (l.x + bitmap.width).toFloat(), (l.y + bitmap.height).toFloat()),
                    android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            }
            n.restore()
        }
    }
}

/**
 * The document picker opened in the folder of the last file picked with it
 * (DocumentsContract.EXTRA_INITIAL_URI, API 26+). The provider decides whether
 * to honour it; a stale or foreign URI simply opens the picker's own default.
 */
private class OpenDocumentFromLast(private val context: android.content.Context) : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: android.content.Context, input: Array<String>): android.content.Intent =
        super.createIntent(context, input).also { intent ->
            this.context.getSharedPreferences("settings", 0).getString(KEY, null)?.let {
                intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(it))
            }
        }

    companion object {
        private const val KEY = "last_document"
        fun remember(context: android.content.Context, uri: Uri) =
            context.getSharedPreferences("settings", 0).edit().putString(KEY, uri.toString()).apply()
    }
}
