package com.dearded.spektralab

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class ExportFormat { JPEG, TIFF, HEIF }

/** "full" is the working size; "preview" is the engine's 3400 px tier, for sharing. */
enum class ExportSize(val tier: String) { FULL("full"), SHARE("preview") }

/** TIFF 导出的色彩空间选项，决定输出转换和嵌入的 ICC 配置文件 */
enum class TiffColorSpace(val displayName: String, val targetName: String) {
    SRGB("sRGB", "sRGB"),
    DISPLAY_P3("Display P3", "Display P3"),
    PROPHOTO("ProPhoto RGB", "ProPhoto RGB"),
    ADOBE_RGB("Adobe RGB (1998)", "Adobe RGB (1998)")
}

data class UiState(
    val ready: Boolean = false,
    val busy: String? = "正在准备引擎…",
    val error: String? = null,
    val message: String? = null,
    val catalog: Catalog = Catalog.EMPTY,
    val edit: Edit = Edit(),
    val image: Bitmap? = null,
    val imageTier: String = "",
    /** The frame before any film, shown while the preview is held down. */
    val before: Bitmap? = null,
    /** Film id -> a 384 px preview of the open photo through that film. Filled in the background. */
    val filmCards: Map<String, Bitmap> = emptyMap(),
    val source: SourceInfo? = null,
    val workingEdge: Int = 4096,
    /** JPEG exports in Display P3 (on by default: Xiaomi flagships have P3 screens). */
    val wideGamut: Boolean = true,
    /** Highlight recovery for phone photos without a gain map, in stops (0 = off). */
    val highlightStops: Float = 0f,
    val maxPixels: Long = 0,
    val deviceSummary: String = "",
    val engineSummary: String = "",
    val rendering: Boolean = false,
    val exporting: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val presets: List<Preset> = emptyList(),
    /** The 正在处理 sheet is open. */
    val showQueue: Boolean = false,
    /** The open photo's shooting data, for the paper frame's caption. */
    val exif: ExifCopy = ExifCopy.NONE,
    /** The full working-size render for 1:1 viewing; dropped on any edit. */
    val detail: Bitmap? = null,
    /** Non-null for a preview-size JPEG color inspection, in this file target. */
    val detailTarget: String? = null,
    val loadingDetail: Boolean = false,
    /** The film and paper a new photo starts on; "" = the last photo's (remembered). */
    val defaultFilm: String = "",
    val defaultPaper: String = "",
    /** What a newly opened photo starts on: NEW_NONE (every stage 无), NEW_REMEMBER or NEW_FIXED. */
    val newPhotoMode: String = EditorViewModel.NEW_NONE,
) {
    val hasImage get() = source != null
}

class EngineException(message: String, val errorCode: String = ErrorCodes.ENGINE_EXCEPTION) : Exception(ErrorCodes.format(errorCode, message))

class EditorViewModel(private val app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", 0)
    private val presetStore = PresetStore(prefs)
    private val _ui = MutableStateFlow(UiState(workingEdge = prefs.getInt("working_edge", 4096),
        wideGamut = prefs.getBoolean("wide_gamut", true),
        highlightStops = prefs.getFloat("highlight_stops_v2", 0f),
        defaultFilm = prefs.getString("default_film", "").orEmpty(),
        defaultPaper = prefs.getString("default_paper", "").orEmpty(),
        newPhotoMode = newPhotoMode(prefs)))
    val ui: StateFlow<UiState> = _ui

    // Touched only on EngineThread, except where marked volatile.
    private var engine = 0L
    // Written on EngineThread; read from the main thread only to cancel, under
    // cancelLock, which release also takes, so a cancel never meets a freed session.
    @Volatile private var session = 0L
    private val cancelLock = Any()
    /** The tier the engine thread is rendering right now, or null. */
    @Volatile private var inFlightTier: String? = null
    private var applied = Edit()
    private var iccPath = ""
    @Volatile private var wanted = Edit.NONE
    private val previewPublication = PreviewPublication(Edit.NONE)
    private var appliedIntent = previewPublication.intent()
    private var sessionOutput = "sRGB"

    private val renders = Channel<Unit>(Channel.CONFLATED)
    // A Layer 2 change: the last render re-packed through the new look, no engine pass.
    private val repacks = Channel<Unit>(Channel.CONFLATED)
    // The open photo and its edit for a new process (saveLoop); declared before init, which starts the loop.
    private val stateFile = File(app.filesDir, "editor-state.json")
    private val saves = Channel<Unit>(Channel.CONFLATED)
    /** The look the native session holds (engine thread). */
    private var appliedLook = Look.NEUTRAL
    // Diagnostics: the raw probe replies and the last events, for the copyable report.
    private var deviceJson = ""
    private var capabilitiesJson = ""
    // Process-lifetime engine, event log and thermal monitor (EngineHost).
    private val host = (app as SpektraLabApp).host
    private val loader = SourceLoader(app)
    private val history = EditHistory(kSettleMs, kMaxUndo)

    private fun log(event: String) = host.events.add(event)
    private var lastUri: Uri? = null
    // The edit this photo opened with, for 重置全部.
    private var openedEdit: Edit? = null
    private var sourceExif: ExifCopy = ExifCopy.NONE
    private var pendingOpen: Uri? = null
    // The open photo's input space, so a film card opens on it the same way.
    private var openSpace = "sRGB"
    private var openLinear = false
    private var cardsJob: kotlinx.coroutines.Job? = null
    @Volatile private var detailRevision = 0L
    // 基准测试结果，用于诊断信息
    private var benchmarkResult: BenchmarkResult? = null
    private var benchmarkJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch { startEngine() }
        viewModelScope.launch { renderLoop() }
        viewModelScope.launch { repackLoop() }
        viewModelScope.launch { saveLoop() }
        // A lost GPU device (here or in an export) makes EngineHost build a new
        // engine and bump generation; the open photo is reopened on it with the
        // whole edit kept, the way a white-balance re-decode keeps it.
        viewModelScope.launch {
            host.generation.drop(1).collect { onEngineReplaced() }
        }
    }

    private suspend fun onEngineReplaced() {
        val ready = try { host.start(wanted.film, wanted.paper) } catch (e: Throwable) { return }
        if (ready.engine == engine) return
        openJob?.cancelAndJoin()
        withContext(EngineThread.dispatcher) { releaseSession() }
        engine = ready.engine
        log("engine replaced after a lost GPU device; reopening")
        clearPool()
        val uri = lastUri ?: return
        val src = _ui.value.source
        _ui.update { it.copy(message = "GPU 重置了一下，已自动重新打开照片，编辑都还在") }
        launchOpen(uri, wb = (src?.wbK ?: 0.0) to (src?.wbTint ?: 0.0))
    }

    private suspend fun startEngine() {
        try {
            val ready = host.start(wanted.film, wanted.paper)
            engine = ready.engine
            // Imported films live outside engine/ (its reinstall deletes the tree): copy
            // them back in and list them. Nothing to copy leaves the host's catalog as is.
            val catalog = withContext(Dispatchers.IO) {
                val synced = runCatching { UserLibrary.get(app).profiles.syncInto(ready.dir) }.getOrDefault(0)
                if (synced > 0) runCatching { Catalog.load(ready.dir) }.getOrDefault(ready.catalog) else ready.catalog
            }
            iccPath = File(ready.dir, "io/sRGB.icc").absolutePath
            deviceJson = ready.deviceJson
            capabilitiesJson = ready.capabilitiesJson
            val gpu = DeviceProfile.gpu(ready.deviceJson)
            val maxPixels = DeviceProfile.maxPixels(gpu, DeviceProfile.availableMemory(app))
            // First run: pick the working edge from the GPU's buffer cap (3072 on
            // Adreno 7xx, Xiaomi 12-14) instead of a fixed 4096. A user's choice wins.
            val edge = if (prefs.contains("working_edge")) prefs.getInt("working_edge", 4096)
                       else DeviceProfile.recommendedEdge(maxPixels)
            log(if (ready.reused) "engine reused" else "engine ready")
            _ui.update {
                it.copy(
                    ready = true, busy = null, catalog = catalog, workingEdge = edge,
                    deviceSummary = DeviceProfile.summary(gpu),
                    engineSummary = DeviceProfile.summarizeCapabilities(ready.capabilitiesJson),
                    maxPixels = maxPixels,
                )
            }
            val pending = pendingOpen
            // Nothing asked for: back to the photo and edit the last process left (killed, or closed).
            if (pending != null) { pendingOpen = null; open(pending) } else if (lastUri == null) restoreLast()
            // Jobs left by a process the system killed pick up where they were.
            if (host.queue.jobs.value.any { it.active }) host.queue.start()
        } catch (e: Throwable) {
            _ui.update { it.copy(busy = null, error = e.message ?: e.toString()) }
        }
    }

    // --- opening -------------------------------------------------------------

    fun open(uri: Uri) {
        if (!_ui.value.ready) { pendingOpen = uri; return }
        if (_ui.value.exporting) {
            _ui.update { it.copy(message = "正在导出，完成后再打开新照片") }
            return
        }
        launchOpen(uri, wb = null)
    }

    /** The successfully opened photo, for the light table's link to it. */
    val openUri: Uri? get() = lastUri

    /**
     * Light table hook: opens [uri] with a saved [edit] kept whole, geometry
     * and RAW white balance included, the way a white-balance re-decode keeps
     * it. Undo starts over and the EXIF is read for the new photo.
     */
    fun openSaved(uri: Uri, edit: Edit, wbK: Double, wbTint: Double) {
        if (!_ui.value.ready || _ui.value.exporting) { open(uri); return }
        launchOpen(uri, wbK to wbTint, savedEdit = edit)
    }

    private var openJob: kotlinx.coroutines.Job? = null
    private data class ReopenEdit(val edit: Edit, val record: Boolean, val moveHistory: (() -> Unit)?)

    /**
     * Opens run one at a time: a newer one first cancels and waits for the
     * older. Two in flight used to leak a whole native session (each released
     * the old one, then both stored theirs) and could delete each other's
     * cached RAW.
     */
    private fun launchOpen(uri: Uri, wb: Pair<Double, Double>?, savedEdit: Edit? = null, change: ReopenEdit? = null) {
        invalidateDetail()
        clearOverride(render = false)
        val previous = openJob
        openJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            doOpen(uri, wb, savedEdit, change)
        }
    }

    /**
     * RAW white balance is chosen at decode (LibRaw multipliers), so a change
     * re-opens the same file keeping the whole edit. [temperatureK] 0 = as shot.
     */
    fun setWhiteBalance(temperatureK: Double, tint: Double) {
        val uri = lastUri ?: return
        if (_ui.value.source?.kind != "raw" || _ui.value.exporting) return
        launchOpen(uri, wb = temperatureK to tint)
    }

    /**
     * Fills the open photo's missing shooting data from [jpeg], a same-shot
     * JPEG the user picked with the system picker (no media permission). Only
     * gaps are filled; the date back and data line follow. Remembered for the
     * source, so a reopen and the export queue carry it.
     */
    fun fillExifFrom(jpeg: Uri) {
        val source = lastUri ?: return
        viewModelScope.launch {
            val (filled, note) = withContext(Dispatchers.IO) { ExifCopy.fillFromJpeg(app, source, sourceExif, jpeg) }
            if (lastUri != source) return@launch
            if (filled !== sourceExif) {
                sourceExif = filled
                val e = wanted
                applyEdit(Edit.withShooting(e, filled).let { if (it.dateText.isEmpty()) it.copy(dateImprint = false) else it }, record = false)
                _ui.update { it.copy(exif = filled) }
            }
            _ui.update { it.copy(message = note) }
        }
    }

    private suspend fun doOpen(uri: Uri, wb: Pair<Double, Double>? = null, savedEdit: Edit? = null, change: ReopenEdit? = null) {
        _ui.update { it.copy(busy = "正在读取…", error = null, detail = null) }
        cardsJob?.cancel()
        cancelStalePreview()
        // Drop idle bitmap references, but keep the old image and native session
        // usable until the replacement is fully prepared (also on OOM or cancel).
        clearPool()
        var committed = false
        try {
          host.transientSessions.withLease(onWaiting = {
              _ui.update { it.copy(busy = "等待当前处理…") }
          }) {
            _ui.update { it.copy(busy = "正在读取…") }
          withSessionCandidate(EngineThread.dispatcher, NativeEngine::release) { candidate ->
            val resolver = app.contentResolver
            // A query plus a 4-byte header read: a cloud provider can block for seconds.
            val (name, raw) = withContext(Dispatchers.IO) {
                val n = loader.displayName(uri)
                n to loader.isRaw(n, resolver.getType(uri), uri)
            }
            val requested = change?.edit ?: savedEdit?.let(_ui.value.catalog::normalize) ?: wanted
            val resetHistory = wb == null || savedEdit != null
            val edge = _ui.value.workingEdge
            // The film edge lays the frame on a canvas up to 1.6x its area, and the
            // canvas is one GPU buffer: on Adreno 7xx (Xiaomi 12-14) that buffer is
            // capped at 128 MiB. A re-decode with the edge on keeps the canvas inside.
            val maxPixels = _ui.value.maxPixels.let {
                if (wb != null && requested.filmEdge && it > 0) it * 10 / 16 else it
            }
            // Rotation and crop belong to one photo; the look (film, paper, effects) carries over.
            // A white-balance re-decode is the same photo: everything is kept.
            // The film edge is turned off with the crop (the engine refuses a frame
            // that is not the gate's shape); the date back follows the new photo's EXIF.
            val nextExif = if (resetHistory) withContext(Dispatchers.IO) { ExifCopy.read(app, uri) } else sourceExif
            // Every new frame is metered, a phone photo too: upstream meters JPEG
            // and RAW alike ("balanced", a log average that a bright sky barely moves).
            val carried = _ui.value.catalog.normalize(if (wb != null) requested else requested.copy(
                autoExposure = true,
                rawDevelopment = RawDevelopmentMode.NEUTRAL, rawAutoExposure = true, rawExposureEv = 0.0,
                quarterTurns = 0, flipH = false,
                cropX = 0.0, cropY = 0.0, cropW = 1.0, cropH = 1.0, cropAspect = 0.0,
                filmEdge = false,
            ).let { Edit.withShooting(it, nextExif) }.let { if (it.dateText.isEmpty()) it.copy(dateImprint = false) else it }
                // A border that crops or sets a film edge belongs to the last photo's
                // geometry; a pure paper frame (data bar, mat) carries over as a look.
                .let {
                    val p = Borders.byId(it.borderId)
                    if (wb == null && p != null && (p.edge != null || p.crop > 0)) it.copy(frame = FrameSpec(), borderId = "none") else it
                })
            // Upstream PR #53: the chosen pair only for a photo with no saved edit.
            val look = if (wb == null) withNewPhotoStocks(carried) else carried
            val (wbK, wbTint) = wb ?: (0.0 to 0.0)
            val handle: Long
            if (raw) {
                // Assigned inside the IO hop: a cancel that lands as it returns
                // still leaves the descriptor (or copy) where finally closes it.
                var input: RawInput? = null
                handle = try {
                    withContext(Dispatchers.IO) { input = loader.rawInput(uri, name) }
                    val path = input!!.path
                    _ui.update { it.copy(busy = "正在解码 RAW…") }
                    withRawOpenCancellation { cancellation ->
                        withContext(EngineThread.dispatcher) {
                            val params = Edit.openingParams(look, inputSpace = INPUT_SPACE, linear = true).toString()
                            val h = NativeEngine.openRawCancellableV1(engine, path, edge, maxPixels, params, wbK, wbTint, cancellation)
                            if (h == 0L) {
                                if (NativeEngine.lastWasCancelled()) throw kotlinx.coroutines.CancellationException("RAW open cancelled")
                                throw EngineException("打开失败：${NativeEngine.lastError()}")
                            }
                            candidate.own(h)
                            h
                        }
                    }
                } finally {
                    input?.close()
                }
            } else {
                var photo: Photo? = null
                handle = try {
                    withContext(Dispatchers.IO) { photo = loader.decodePhoto(uri, edge, maxPixels) }
                    _ui.update { it.copy(busy = if (photo!!.hdr) "正在恢复 HDR 高光…" else "正在打开…") }
                    withContext(EngineThread.dispatcher) {
                        val input = photo!!
                        photo = null // openPhotoSession owns and recycles it, including on failure.
                        val opened = openPhotoSession(engine, input, _ui.value.highlightStops) { space, linear ->
                            Edit.openingParams(look, inputSpace = space, linear = linear).toString()
                        }
                        val h = opened.handle
                        if (h == 0L) throw EngineException("打开失败：${NativeEngine.lastError()}")
                        candidate.own(h)
                        if (opened.linear) log("ultra hdr gain map applied")
                        h
                    }
                } finally {
                    photo?.recycle()
                }
            }
            val displaySpace = DisplayColor.space
            val (edit, info, before) = withContext(EngineThread.dispatcher) {
                // The screen's space: Display P3 when the window is wide-gamut.
                if (!NativeEngine.setOutput(handle, displaySpace, false))
                    throw EngineException("display transform: ${NativeEngine.lastError()}")
                val params = NativeEngine.getParams(handle)?.let(::JSONObject) ?: JSONObject()
                val edit = Edit.fromParams(params, look)
                Triple(edit, JSONObject(NativeEngine.sourceInfo(handle)), NativeEngine.beforeBitmap(handle))
            }
            // Allocate the replacement UI before transferring native ownership.
            val nextUi = _ui.value.copy(
                    busy = null, edit = edit, image = null, imageTier = "", before = before, exif = nextExif,
                    filmCards = emptyMap(), detail = null, loadingDetail = false,
                    canUndo = if (resetHistory) false else history.canUndo,
                    canRedo = if (resetHistory) false else history.canRedo,
                    source = SourceInfo(
                        name = name,
                        width = info.optInt("width"), height = info.optInt("height"),
                        sourceWidth = info.optInt("sourceWidth"), sourceHeight = info.optInt("sourceHeight"),
                        kind = info.optString("kind"),
                        camera = listOf(info.optString("make"), info.optString("model"))
                            .filter { s -> s.isNotBlank() }.joinToString(" "),
                        asShotK = info.optDouble("asShotK", 0.0), asShotTint = info.optDouble("asShotTint", 0.0),
                        wbK = info.optDouble("wbK", 0.0), wbTint = info.optDouble("wbTint", 0.0),
                        rawStatsBinned = info.optBoolean("rawStatsBinned", false),
                        rawStatsReady = info.optBoolean("rawStatsReady", false),
                        inputPrecision = info.optString("inputPrecision"),
                        inputPrecisionNote = info.optString("inputPrecisionNote"),
                    ),
                )
            var retired = 0L
            try {
                candidate.commit { replacement ->
                    withContext(EngineThread.dispatcher) {
                        retired = synchronized(cancelLock) {
                            session.also { session = replacement }
                        }
                        applied = edit
                        appliedLook = Look.NEUTRAL
                        wanted = edit
                        previewPublication.replaceSource(replacement)
                        appliedIntent = previewPublication.request(edit)
                        sessionOutput = displaySpace
                        openSpace = INPUT_SPACE
                        openLinear = true
                    }
                    // No suspension between these fields and the UI publication:
                    // saving/exporting can never pair an old source with a new URI.
                    lastUri = uri
                    sourceExif = nextExif
                    lastOpenedAny = true
                    if (resetHistory) {
                        openedEdit = if (savedEdit != null) requested else edit
                        history.clear()
                    }
                    if (change != null) {
                        if (change.record) history.record(_ui.value.edit, edit, android.os.SystemClock.uptimeMillis())
                        change.moveHistory?.invoke()
                        rememberPair(_ui.value.edit, edit)
                    }
                    _ui.value = nextUi.copy(canUndo = history.canUndo, canRedo = history.canRedo)
                    committed = true
                }
            } finally {
                // The candidate has been adopted before any native release can fail.
                if (retired != 0L) withContext(NonCancellable) {
                    withContext(EngineThread.dispatcher) { NativeEngine.release(retired) }
                }
            }
            log("open ${if (raw) "raw" else "photo"} ${info.optInt("sourceWidth")}x${info.optInt("sourceHeight")} -> ${info.optInt("width")}x${info.optInt("height")}")
            // Say so when the frame was scaled down, so a smaller export is not a surprise.
            val sw = info.optInt("sourceWidth"); val sh = info.optInt("sourceHeight")
            val ww = info.optInt("width"); val wh = info.optInt("height")
            if (wb == null && sw > 0 && (ww < sw || wh < sh)) {
                _ui.update {
                    it.copy(message = "原图 ${sw}×${sh}，按工作尺寸缩到 ${ww}×${wh} 处理；可在“设置”里调大")
                }
            }
            renders.trySend(Unit)
            startFilmCards(handle)
            saves.trySend(Unit)
            // The previous photo's read grant is retained until the new one lands.
            if (wb == null) withContext(NonCancellable + Dispatchers.IO) { holdGrant(uri) }
          }
          }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e   // superseded by a newer open, or the editor closed: not an error
        } catch (e: OutOfMemoryError) {
            log("open failed: out of memory")
            _ui.update { it.copy(busy = null, error = "内存不足，请在“设置”里把工作尺寸调小后重试") }
        } catch (e: Throwable) {
            log("open failed: ${e.message ?: e}")
            _ui.update { it.copy(busy = null, error = e.message ?: e.toString()) }
        } finally {
            if (!committed) {
                _ui.update { it.copy(busy = null) }
                // Requests skipped while opening still belong to the intact old session.
                if (session != 0L) {
                    renders.trySend(Unit)
                    saves.trySend(Unit)
                    startFilmCards(session)
                }
            }
        }
    }

    private fun thermalHeadroom(): Float = host.thermal.headroom()

    private fun releaseSession() = synchronized(cancelLock) {
        if (session != 0L) NativeEngine.release(session)
        session = 0L
        previewPublication.replaceSource(0L)
    }

    /**
     * A new edit makes the slow preview render in flight stale: stop it at its
     * next node so the live render for the new edit starts at once, instead of
     * after a whole full-resolution pass. Live renders are left to finish, or
     * a slider drag would never show anything.
     */
    private fun cancelStalePreview() = synchronized(cancelLock) {
        if ((inFlightTier == "preview" || inFlightTier == "detail") && session != 0L) NativeEngine.cancel(session)
    }

    // --- editing and rendering -------------------------------------------------

    fun update(edit: Edit) {
        // The film edge lays the frame on a canvas up to 1.6x its area (the 6x17
        // strip and the 135 rebate). Past the GPU buffer cap that canvas cannot be
        // rendered at all (and reset the driver in another port), so the photo is
        // re-decoded smaller first, with the whole edit kept.
        val src = _ui.value.source
        val uri = lastUri
        if (edit.filmEdge && !_ui.value.edit.filmEdge && src != null && uri != null &&
            _ui.value.maxPixels > 0 && src.width.toLong() * src.height * 16 / 10 > _ui.value.maxPixels) {
            applyEdit(edit, record = true, render = false)
            _ui.update { it.copy(message = "加边框后画布超出 GPU 单缓冲上限，已按更小尺寸重新打开") }
            launchOpen(uri, wb = src.wbK to src.wbTint)
            return
        }
        applyEdit(edit, record = true)
    }

    private fun rememberPair(current: Edit, edit: Edit) {
        if (edit.film != current.film || edit.paper != current.paper || edit.noFilm != current.noFilm ||
            edit.digitalIntermediate != current.digitalIntermediate || edit.scanFilm != current.scanFilm)
            prefs.edit().putString("last_film", edit.film).putString("last_paper", edit.paper)
                .putBoolean("last_no_film", edit.noFilm).putBoolean("last_di", edit.digitalIntermediate)
                .putBoolean("last_scan_film", edit.scanFilm).apply()
    }

    private fun applyEdit(raw: Edit, record: Boolean, render: Boolean = true, moveHistory: (() -> Unit)? = null) {
        val edit = _ui.value.catalog.normalize(raw)
        val src = _ui.value.source
        val uri = lastUri
        // Keep the accepted edit/save/history intact until a full-source candidate
        // has decoded and validated the requested development metadata.
        if (src != null && uri != null && edit.requiresRawDevelopmentSource(src)) {
            cancelStalePreview()
            launchOpen(uri, wb = src.wbK to src.wbTint, change = ReopenEdit(edit, record, moveHistory))
            return
        }
        // The real edit renders below; after an override the engine holds the override's
        // params, so even a look-only change must develop again, not re-pack.
        val wasOverridden = clearOverride(render = false)
        val current = _ui.value.edit
        if (edit != current || wasOverridden) invalidateDetail()
        // "记住上次用的" across a restart: a new process would start on 无 otherwise.
        rememberPair(current, edit)
        if (record) history.record(current, edit, android.os.SystemClock.uptimeMillis())
        moveHistory?.invoke()
        wanted = edit
        previewPublication.request(edit)
        saves.trySend(Unit)
        host.lastInteraction = android.os.SystemClock.uptimeMillis()
        _ui.update {
            it.copy(edit = edit, canUndo = history.canUndo, canRedo = history.canRedo,
                detail = if (edit == it.edit) it.detail else null)
        }
        // A sharp preview of the previous edit is now wasted work: stop it so the
        // live render of this one starts at once.
        // Only the look moved: the print is the same, so re-pack it (a few ms)
        // instead of developing again. Anything else renders, look included.
        val outputOnly = !wasOverridden && edit != current && edit.sameRenderInputAs(current)
        if (outputOnly && _ui.value.image != null) { if (render) repacks.trySend(Unit); return }
        cancelStalePreview()
        if (render) renders.trySend(Unit)
    }

    /**
     * Renders the full working size once for 1:1 viewing. Refused above
     * kMaxDetailPixels, where the ARGB bitmap alone would pass ~64 MB.
     */
    fun loadDetail() = loadInspection(null)

    fun loadExportProof(target: String) {
        require(target == "sRGB" || target == "Display P3")
        loadInspection(target)
    }

    private fun loadInspection(target: String?) {
        val state = _ui.value
        val src = state.source ?: return
        if (state.loadingDetail || state.detail != null || state.exporting || state.busy != null) return
        if (target == null && src.width.toLong() * src.height > kMaxDetailPixels) {
            _ui.update { it.copy(message = "工作尺寸超过 1600 万像素，1:1 查看已关闭；导出不受影响") }
            return
        }
        clearOverride(render = true)
        val edit = state.edit
        val intent = previewPublication.intent()
        val revision = ++detailRevision
        var identity: PreviewFrame<Edit>? = null
        fun current() = detailRevision == revision && _ui.value.edit == edit &&
            _ui.value.source === src && _ui.value.busy == null && previewPublication.intent() == intent &&
            (identity?.let { previewPublication.current(it) } ?: true)
        _ui.update { it.copy(loadingDetail = true) }
        viewModelScope.launch {
            try {
                val bitmap = withContext(EngineThread.dispatcher) {
                    if (!current() || session == 0L) return@withContext null
                    applyWanted()?.let { throw EngineException("参数无效：$it") }
                    identity = previewPublication.frame(session, appliedIntent,
                        if (target == null) "full" else "preview", target ?: sessionOutput)
                    inFlightTier = "detail"
                    try {
                        val rendered = if (target == null) NativeEngine.renderBitmap(session, "full")
                            else NativeEngine.renderProofBitmap(session, "preview", target)
                        if (rendered == null) {
                            if (NativeEngine.lastWasCancelled()) return@withContext null
                            throw EngineException("渲染失败：${NativeEngine.lastError()}")
                        }
                        try {
                            DisplayColor.tagOutput(rendered, target ?: sessionOutput)
                        } catch (e: Throwable) {
                            rendered.recycle()
                            throw e
                        }
                        rendered
                    } finally {
                        inFlightTier = null
                    }
                }
                if (bitmap != null) {
                    if (current()) {
                        _ui.update { it.copy(detail = bitmap, detailTarget = target) }
                        log("${if (target == null) "detail" else "color preview $target"} ${bitmap.width}x${bitmap.height}")
                    } else bitmap.recycle()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                if (current()) _ui.update { it.copy(message = "内存不足，无法打开图像预览") }
            } catch (e: Throwable) {
                if (current()) _ui.update { it.copy(message = e.message ?: e.toString()) }
            } finally {
                if (detailRevision == revision) _ui.update { it.copy(loadingDetail = false) }
            }
        }
    }

    private fun invalidateDetail() {
        ++detailRevision
        synchronized(cancelLock) {
            if (inFlightTier == "detail" && session != 0L) NativeEngine.cancel(session)
        }
        _ui.update { it.copy(detail = null, detailTarget = null, loadingDetail = false) }
    }

    fun closeDetail() = invalidateDetail()

    fun undo() {
        if (_ui.value.busy != null) return
        val current = _ui.value.edit
        applyEdit(history.undoTarget ?: return, record = false, moveHistory = { history.undo(current) })
    }

    fun redo() {
        if (_ui.value.busy != null) return
        val current = _ui.value.edit
        applyEdit(history.redoTarget ?: return, record = false, moveHistory = { history.redo(current) })
    }

    /**
     * 重置全部: back to how the photo opened (style, stocks, every parameter,
     * border, crop), RAW white balance back to as shot. One undo step, so 撤销
     * brings the edit back.
     */
    fun resetAll() {
        val opened = openedEdit ?: return
        if (_ui.value.exporting) return
        applyEdit(opened, record = true)
        val src = _ui.value.source
        val uri = lastUri
        if (src != null && uri != null && src.kind == "raw" && (src.wbK != 0.0 || src.wbTint != 0.0))
            launchOpen(uri, wb = 0.0 to 0.0)
        _ui.update { it.copy(message = "已重置为刚打开时的样子，可撤销") }
    }

    // --- presets: film, paper and print/effects only, never per-photo settings ---

    fun loadPresets() = _ui.update { it.copy(presets = presetStore.load()) }

    fun savePreset(name: String) {
        val clean = name.trim().take(40)
        if (clean.isEmpty()) return
        val list = presetStore.save(Preset.of(clean, _ui.value.edit))
        _ui.update { it.copy(presets = list, message = "已保存预设“$clean”") }
    }

    fun applyPreset(preset: Preset) {
        val catalog = _ui.value.catalog
        // A preset saved on another build may name a stock this one does not ship.
        if (catalog.films.isNotEmpty() && catalog.film(preset.film) == null) {
            _ui.update { it.copy(message = "预设里的胶片不存在：${preset.film}") }
            return
        }
        update(preset.applyTo(_ui.value.edit))
    }

    /** A one-tap style (Styles.kt): film, paper, print, effects and its finish together. */
    fun applyStyle(style: Style) {
        val next = style.applyTo(_ui.value.edit, _ui.value.catalog) ?: run {
            _ui.update { it.copy(message = "这个风格用的胶片不在当前版本里") }
            return
        }
        update(next)
    }

    fun deletePreset(name: String) = _ui.update { it.copy(presets = presetStore.delete(name)) }

    fun pickFilm(id: String) {
        val state = _ui.value
        update(withFilm(state.edit, state.catalog, id) ?: return)
    }

    /** 胶片「无」: the photo itself. Film and paper stay chosen, so picking a film brings them back. */
    fun pickNoFilm() = update(_ui.value.edit.copy(noFilm = true))

    /** 风格「无」: no film, no film effects, no Layer 2; geometry and border stay. */
    fun clearStyle() = update(_ui.value.edit.withoutStyle())

    /**
     * One card per film, rendered after the photo opens: the open photo's small
     * copy through that film, with the user's current paper and effects. One
     * card per engine-thread hop, so the user's own renders get in between.
     * Cancelled by the next open; a card that fails is simply left out.
     */
    private fun startFilmCards(handle: Long) {
        cardsJob?.cancel()
        cardsJob = viewModelScope.launch {
            kotlinx.coroutines.delay(1500)   // let the first preview land first
            val catalog = _ui.value.catalog
            for (film in catalog.films) {
                // Never ahead of the user's own drag renders on the one engine thread.
                // Hold back while a finger is on a slider, and while the phone is hot:
                // cards are optional, the preview the user is looking at is not.
                while (interacting || thermalHeadroom() > kHotHeadroom) kotlinx.coroutines.delay(150)
                // Cards show the film's colour, not the border or date: both cost a larger canvas.
                val base = withFilm(wanted, catalog, film.id)?.copy(filmEdge = false, dateImprint = false) ?: continue
                val params = Edit.openingParams(base, inputSpace = openSpace, linear = openLinear).toString()
                val card = try {
                    withContext(EngineThread.dispatcher) {
                        if (session != handle) null else NativeEngine.filmPreview(handle, params)?.also(DisplayColor::tag)
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    null
                } ?: continue
                _ui.update { it.copy(filmCards = it.filmCards + (film.id to card)) }
            }
            log("film cards ${_ui.value.filmCards.size}/${catalog.films.size}")
        }
    }

    /** Sends whatever the user changed since the last call. Engine thread only. */
    /** The last edit the engine refused (engine thread). */
    @Volatile private var rejected: Edit? = null

    /**
     * 修改记录's 隐藏 (ui2_snapseed.md 4): an edit shown instead of [wanted] for a
     * moment, so one group can be looked at without it. Display only: it goes
     * through the same render and re-pack path, is never recorded for undo, never
     * saved (sidecars and editor state read [wanted]), and exports read the
     * current edit. Any real edit, a new open and an export start clear it.
     */
    @Volatile private var override: Edit? = null

    /** Shows [edit] instead of the current edit until cleared (null) or until anything real happens. */
    fun previewOverride(edit: Edit?) {
        val next = edit?.let { _ui.value.catalog.normalize(it) }?.takeIf { it != wanted }
        if (next == override) return
        override = next
        previewPublication.request(next ?: wanted)
        cancelStalePreview()
        renders.trySend(Unit)
    }

    /** Drops the override (a real edit, an open, an export): the screen goes back to the current edit. */
    private fun clearOverride(render: Boolean): Boolean {
        if (override == null) return false
        override = null
        previewPublication.request(wanted)
        if (render) renders.trySend(Unit)
        return true
    }

    private fun applyWanted(): String? {
        val intent = previewPublication.intent()
        val target = intent.edit
        applyLook(target.look)
        if (target == applied) { appliedIntent = intent; return null }
        val delta = target.delta(applied)
        if (delta.length() == 0) { applied = target; appliedIntent = intent; return null }
        if (NativeEngine.setParams(session, delta.toString()) == null) {
            if (target === override) {
                override = null
                previewPublication.request(wanted)
            } else rejected = target
            return NativeEngine.lastError()
        }
        applied = target
        appliedIntent = intent
        return null
    }

    // True while a finger is on a slider. Set from the UI thread.
    @Volatile private var interacting = false
    private val settle = Channel<Unit>(Channel.CONFLATED)

    /**
     * A slider drag starts or ends. While it lasts only the small live tier
     * renders and the film cards wait; on release the sharp preview starts at
     * once instead of after a timeout.
     */
    fun setInteracting(on: Boolean) {
        host.lastInteraction = android.os.SystemClock.uptimeMillis()
        if (on == interacting) return
        interacting = on
        if (on) settle.tryReceive() else settle.trySend(Unit)
    }

    /** Engine thread. */
    private fun applyLook(look: Look) {
        if (look == appliedLook || session == 0L) return
        UserLibrary.get(app).applyLut(session, look, appliedLook)
        if (!NativeEngine.setLook(session, if (look.neutral) null else look.values()))
            throw EngineException("应用调整失败：${NativeEngine.lastError()}")
        appliedLook = look
    }

    private suspend fun repackLoop() {
        for (request in repacks) {
            if (_ui.value.imageTier.isEmpty() || _ui.value.busy != null) continue
            var identity: PreviewFrame<Edit>? = null
            val out = try {
                withContext(EngineThread.dispatcher) {
                    if (session == 0L || _ui.value.busy != null) return@withContext null
                    val tier = previewPublication.cachedTier(session) ?: return@withContext null
                    val target = override ?: wanted
                    // A preceding film/geometry edit may still be waiting for a
                    // render. Its old cache cannot be repacked as the new input.
                    if (!target.sameRenderInputAs(applied)) return@withContext null
                    applyWanted()?.let { throw EngineException("参数无效：$it") }
                    identity = previewPublication.frame(session, appliedIntent, tier, sessionOutput)
                    val reuse = synchronized(pool) { spare.remove(tier) }?.takeIf { !it.isRecycled }
                    val bitmap = NativeEngine.repack(session, reuse)?.also { DisplayColor.tagOutput(it, sessionOutput) }
                    if (bitmap == null && reuse != null) synchronized(pool) { spare[tier] = reuse }
                    bitmap?.prepareToDraw()
                    bitmap
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                null
            }
            // Nothing kept to re-pack (a fresh session): develop instead.
            if (out == null) { renders.trySend(Unit); continue }
            val frame = identity ?: continue
            if (_ui.value.busy != null || !previewPublication.publish(frame)) continue
            val tier = frame.tier
            retire(tier, out)
            _ui.update { it.copy(image = out, imageTier = tier) }
        }
    }

    private suspend fun renderLoop() {
        for (request in renders) {
            if (!renderOnce("live")) continue
            // Dragging: wait for the next value, the release, or a finger held
            // still. Otherwise (a toggle, a film, a preset) the sharp preview
            // follows straight away, unless a newer edit is already queued.
            val newer = if (interacting) {
                withTimeoutOrNull(1200) {
                    kotlinx.coroutines.selects.select<Boolean> {
                        renders.onReceive { true }
                        settle.onReceive { false }
                    }
                }
            } else {
                renders.tryReceive().getOrNull()?.let { true }
            }
            if (newer == true) { renders.trySend(Unit); continue }
            // A large export rendering now: the 3400 px preview waits for it
            // (at most 20 s) rather than doubling the GPU's peak; the live tier above does not.
            host.yieldToExport()
            renderOnce("preview")
        }
    }

    // Preview bitmaps are reused: per tier, the frame on screen and one spare
    // the next render writes into. A 3400 px ARGB frame is 30-46 MB, and a new
    // one per render meant native-heap spikes and finalizer churn mid-drag.
    // The spare is never the bitmap on screen: a tier's previous frame becomes
    // the spare only once its successor has replaced it in the UI.
    private val pool = Any()
    private val spare = HashMap<String, Bitmap>()
    private val shownByTier = HashMap<String, Bitmap>()
    // The frame replaced most recently. It may still be in the display list
    // RenderThread is drawing, so it waits one more render before it can be
    // written into again (three buffers per tier: shown, cooling, spare).
    private val coolingByTier = HashMap<String, Bitmap>()

    /** Engine thread. */
    private fun renderPooled(tier: String): Bitmap? {
        val reuse = synchronized(pool) { spare.remove(tier) }?.takeIf { !it.isRecycled }
        // The same render fills the spare, or a new bitmap when the tier changed
        // size (crop, rotation, film edge); either way the frame renders once.
        val out = NativeEngine.renderReuse(session, tier, reuse)?.also { DisplayColor.tagOutput(it, sessionOutput) }
        if (out == null && reuse != null) synchronized(pool) { spare[tier] = reuse }
        // Upload to the GPU here, on the engine thread, instead of on the
        // RenderThread during the frame that first shows it (a 30-46 MB upload
        // there is a dropped frame exactly when the sharp preview lands).
        out?.prepareToDraw()
        return out
    }

    private fun retire(tier: String, shown: Bitmap) = synchronized(pool) {
        val previous = shownByTier.put(tier, shown)
        if (previous == null || previous === shown) return@synchronized
        val cooled = coolingByTier.put(tier, previous)
        if (cooled != null && cooled !== shown && cooled !== previous) spare[tier] = cooled
    }

    private fun clearPool() = synchronized(pool) {
        spare.clear()
        shownByTier.clear()
        coolingByTier.clear()
    }

    // An exception escaping here would end renderLoop for good (and leave the
    // progress bar up), so everything but cancellation is turned into a message,
    // and the busy flag is cleared in finally.
    private suspend fun renderOnce(tier: String): Boolean {
        if (_ui.value.busy != null) return false
        _ui.update { it.copy(rendering = true) }
        var failure: String? = null
        var reverted: Edit? = null
        var bitmap: Bitmap? = null
        var identity: PreviewFrame<Edit>? = null
        try {
            val started = android.os.SystemClock.elapsedRealtime()
            bitmap = withContext(EngineThread.dispatcher) {
                if (session == 0L || _ui.value.busy != null) return@withContext null
                identity = previewPublication.frame(session, previewPublication.intent(), tier, sessionOutput)
                applyWanted()?.let { error ->
                    failure = "参数无效：$error"
                    // Only if the user has not asked for something newer meanwhile.
                    if (wanted == rejected) {
                        wanted = applied
                        val restored = previewPublication.request(applied)
                        identity = previewPublication.frame(session, restored, tier, sessionOutput)
                        reverted = applied
                    }
                    return@withContext null
                }
                identity = previewPublication.frame(session, appliedIntent, tier, sessionOutput)
                inFlightTier = tier
                try {
                    renderPooled(tier)?.also { previewPublication.keep(identity!!) } ?: run {
                        // Cancelled for a newer edit: not a failure, and the loop
                        // already has the newer request queued.
                        if (NativeEngine.lastWasDeviceLost()) {
                            failure = "GPU 重置了，正在恢复…"
                            val lost = engine
                            viewModelScope.launch { runCatching { host.recover(lost) } }
                        } else if (!NativeEngine.lastWasCancelled()) failure = "渲染失败：${NativeEngine.lastError()}"
                        null
                    }
                } finally {
                    inFlightTier = null
                }
            }
            val ms = android.os.SystemClock.elapsedRealtime() - started
            bitmap?.let { log("render $tier ${it.width}x${it.height} ${ms} ms") }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            failure = "内存不足，渲染失败；请在“设置”里调小工作尺寸"
        } catch (e: Throwable) {
            failure = "渲染失败：${e.message ?: e}"
        } finally {
            failure?.let { log("render $tier failed: $it") }
            val frame = identity
            val current = frame != null && previewPublication.current(frame)
            val shown = bitmap?.takeIf {
                frame != null && _ui.value.busy == null && previewPublication.publish(frame, allowStaleLive = true)
            }
            // Outside update: its lambda may run more than once.
            if (shown != null) retire(tier, shown)
            _ui.update {
                var next = it.copy(rendering = false)
                if (shown != null) next = next.copy(image = shown, imageTier = tier)
                if (current && it.busy == null) {
                    reverted?.let { e -> next = next.copy(edit = e) }
                    failure?.let { f -> next = next.copy(message = f) }
                }
                next
            }
        }
        return bitmap != null
    }

    // --- export --------------------------------------------------------------

    /** Every export, past and present, newest last. */
    val jobs: StateFlow<List<ProcessingJob>> get() = host.queue.jobs

    fun showQueue(on: Boolean) = _ui.update { it.copy(showQueue = on) }
    /** A photo opened from another app (share / open with); AppScreens brings the editor forward. */
    val externalOpens = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    fun cancelJob(id: Long) = host.queue.cancel(id)
    fun retryJob(id: Long) = host.queue.retry(id)
    fun clearFinishedJobs() = host.queue.clearFinished()

    /**
     * Queues an export of the open photo with its whole edit. The job opens its
     * own session from the source, so the editor is free at once: keep editing,
     * open the next photo, or leave the app. Progress and the remaining time are
     * in 正在处理 and in the notification (on HyperOS, the island).
     */
    fun export(format: ExportFormat, size: ExportSize = ExportSize.FULL, tiffColorSpace: TiffColorSpace = TiffColorSpace.SRGB) {
        clearOverride(render = true)
        val state = _ui.value
        val src = state.source ?: return
        val uri = lastUri ?: return
        val px = src.width.toLong() * src.height
        val outPixels = if (size == ExportSize.SHARE) {
            val long = max(src.width, src.height).toDouble()
            if (long <= SHARE_EDGE) px else (px * (SHARE_EDGE / long) * (SHARE_EDGE / long)).toLong()
        } else px
        // The queue reopens the original; a prior film-edge edit may have
        // left the editor's current source smaller than this job will open.
        val workingPixels = if (src.sourceWidth > 0 && src.sourceHeight > 0) {
            val scale = SourceLoader.fitScale(src.sourceWidth, src.sourceHeight, state.workingEdge, state.maxPixels)
            (src.sourceWidth * scale * src.sourceHeight * scale).toLong()
        } else 0
        val job = ProcessingJob(
            id = host.queue.nextId(), uri = uri.toString(), name = src.name, raw = src.kind == "raw",
            editJson = state.edit.toJson().toString(), wbK = src.wbK, wbTint = src.wbTint,
            lookJson = if (state.edit.look.neutral) "" else state.edit.look.toJson().toString(),
            format = format, size = size, tiffColorSpace = tiffColorSpace,
            workingEdge = state.workingEdge, maxPixels = state.maxPixels,
            outPixels = outPixels, workingPixels = workingPixels,
            frameJson = if (state.edit.frame.isNone) "" else state.edit.frame.toJson().toString(),
            filmLabel = if (state.edit.noFilm) "" else state.catalog.film(state.edit.film)?.label ?: "",
            edgeRealName = state.edit.edgeRealName,
            wideGamut = state.wideGamut,
            highlightStops = state.highlightStops,
        )
        host.queue.enqueue(job)
        log("queued ${job.label} ${src.width}x${src.height}")
        _ui.update { it.copy(message = "已加入“正在处理”，可以继续编辑或切到后台") }
    }

    /**
     * 极致渲染: a JPEG at the source's own resolution (working edge 0; only the
     * device's pixel cap applies), the whole-frame full tier, in the background
     * queue. The queue steps it down on a lost GPU or a blank frame.
     */
    fun exportExtreme() {
        clearOverride(render = true)
        val state = _ui.value
        val src = state.source ?: return
        val uri = lastUri ?: return
        val sw = src.sourceWidth.takeIf { it > 0 } ?: src.width
        val sh = src.sourceHeight.takeIf { it > 0 } ?: src.height
        val px = sw.toLong() * sh
        val job = ProcessingJob(
            id = host.queue.nextId(), uri = uri.toString(), name = src.name, raw = src.kind == "raw",
            editJson = state.edit.toJson().toString(), wbK = src.wbK, wbTint = src.wbTint,
            lookJson = if (state.edit.look.neutral) "" else state.edit.look.toJson().toString(),
            format = ExportFormat.JPEG, size = ExportSize.FULL, workingEdge = 0, maxPixels = state.maxPixels,
            outPixels = if (state.maxPixels > 0) minOf(px, state.maxPixels) else px,
            workingPixels = if (src.sourceWidth > 0 && src.sourceHeight > 0) px else 0,
            frameJson = if (state.edit.frame.isNone) "" else state.edit.frame.toJson().toString(),
            filmLabel = if (state.edit.noFilm) "" else state.catalog.film(state.edit.film)?.label ?: "",
            edgeRealName = state.edit.edgeRealName,
            wideGamut = state.wideGamut,
            highlightStops = state.highlightStops,
            extreme = true,
        )
        host.queue.enqueue(job)
        log("queued ${job.label} ${sw}x$sh")
        _ui.update { it.copy(message = "已加入“正在处理”：优先原始尺寸，受设备与内存上限限制；可继续编辑或切到后台") }
    }

    /**
     * 分块全分辨率导出（实验）：把整幅照片切成带重叠的块，逐块冲洗后拼回
     * 原尺寸。整幅只测光一次，每块用 [NativeEngine.solve] 的 EV 冻结自动
     * 曝光，避免每块各自测光在拼接处留下亮度台阶。
     *
     * 每块尺寸完全相同、贴到画面边缘，因此所有块走同一个像素尺寸，胶片
     * 里按微米定义的颗粒/晕影等效果尺寸也一致。
     *
     * 相纸边框（留白、输出比例、打孔条、参数条）在整幅拼接完成后套一次，
     * 所以边框也是全分辨率，和预览用同一份 FrameSpec。
     *
     * 片边（135）走引擎的窗口渲染：布局按整幅画布生成，每次只派发画布上的
     * 一块窗口，因此齿孔间距、片基和边缘字跨块一致。片边画布比画面大，块
     * 按画布切，源图按引擎给的片门四角反算。
     *
     * 只支持手机照片（JPEG/PNG/HEIC，8 位 SDR），且不含日期、旋转/翻转、
     * 光晕、眩光、暗角、蒙版和灰尘；片边时还要求关闭颗粒。其余是跨块效果，
     * 分块后会切开。RAW 需要原生裁切。输出 JPEG。
     */
    suspend fun exportTiled(): String {
        val state = _ui.value
        val uri = lastUri ?: return "先在编辑器里打开一张照片"
        val src = state.source ?: return "照片还没打开完"
        if (src.kind == "raw") return "分块导出暂只支持 JPEG/PNG/HEIC；RAW 需要原生裁切，下一步再做"
        val engineHandle = engine
        if (engineHandle == 0L) return "引擎还没准备好"
        val catalog = state.catalog
        val base = state.edit
        if (base.dateImprint) return "分块导出暂不支持日期，请先关闭「日期」"
        if (base.quarterTurns != 0 || base.flipH) return "分块导出暂不支持旋转和翻转，请先恢复整幅"
        // 片边会把画面裁到片门（withFilmEdge），所以只有片边时允许非整幅裁切；
        // 那块裁切由 App 从整幅源图切出来，tile 参数里的 geometry crop 是 identity。
        if (!base.filmEdge && (base.cropX != 0.0 || base.cropY != 0.0 || base.cropW != 1.0 || base.cropH != 1.0))
            return "分块导出暂不支持裁切，请先恢复整幅"
        if (base.noFilm) return "分块导出暂不支持「无胶片」，请先选择胶片和相纸"
        if (base.halationActive) return "分块导出暂不支持光晕，请先关闭「光晕」"
        if (base.glareActive) return "分块导出暂不支持眩光，请先关闭「眩光」"
        val look = base.look
        if (look.vignette != 0.0 || look.masks.isNotEmpty() || look.dust.active)
            return "分块导出暂不支持暗角、蒙版和灰尘，请先关闭"
        val film = base.film.takeIf { it.isNotEmpty() && catalog.film(it) != null }
            ?: return "请先在编辑器里选择胶片"
        val edit = withFilm(base, catalog, film)?.let { catalog.normalize(it) } ?: return "胶片不可用：$film"
        val edgeOn = edit.filmEdge
        // 颗粒按每块的局部像素和每个 session 的种子生成，窗口之间接不上；
        // 片边这一版先要求关闭。光晕/眩光在上面已经一律拒绝。
        if (edgeOn && edit.grainActive && edit.grainAmount != 0.0)
            return "分块导出片边暂不支持颗粒，请先在「胶片效果」里关闭颗粒"

        var full: Photo? = null
        var canvasBitmap: Bitmap? = null
        var meterSession = 0L
        var layoutSession = 0L
        val started = SystemClock.elapsedRealtime()
        try {
            // 整幅解码一次：分块从它裁剪，测光也从它缩小，两者来自同一像素源。
            val photo = withContext(Dispatchers.IO) { loader.decodePhoto(uri, 0, 0) }
            full = photo
            if (photo.hdr) return "分块导出暂不支持 HDR 照片"
            if (photo.highPrecisionSdr) return "分块导出暂不支持 16 位 SDR 照片"
            val source = photo.bitmap
            if (source.config != Bitmap.Config.ARGB_8888) return "分块导出暂只支持 8 位照片"
            val w = source.width
            val h = source.height
            if (w < 256 || h < 256) return "照片太小，分块导出没有意义"

            // 片边时引擎的「整幅画面」是裁到片门后的那一块；片门矩形只在这里
            // 算一次，布局、切块和片边画布都用它。tile session 的 geometry crop
            // 是 identity，因为窗口是从这块源图上直接切下来的。
            val gateX = if (edgeOn) (edit.cropX * w).roundToInt().coerceIn(0, w - 1) else 0
            val gateY = if (edgeOn) (edit.cropY * h).roundToInt().coerceIn(0, h - 1) else 0
            val frameW = if (edgeOn) (edit.cropW * w).roundToInt().coerceIn(1, w - gateX) else w
            val frameH = if (edgeOn) (edit.cropH * h).roundToInt().coerceIn(1, h - gateY) else h
            val tileEdit = if (edgeOn) {
                edit.copy(cropX = 0.0, cropY = 0.0, cropW = 1.0, cropH = 1.0, cropAspect = 0.0)
            } else edit

            // 片边的画布（片基、齿孔、边缘字、漏光）由引擎按整幅画布生成，
            // 逐块渲染会每块各画一圈。这里先在 8×8 的空图上问一次布局，拿到
            // 画布尺寸和片门四角，再按画布切窗口；每个窗口都带整幅 frame 尺寸，
            // 布局因此与预览一致。
            var canvasW = frameW
            var canvasH = frameH
            var vertical = false
            val gateQuad = DoubleArray(8)
            if (edgeOn) {
                val layoutJson = withContext(ExportThread.dispatcher) {
                    val probe = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
                    val opened = openPhotoSession(
                        engineHandle,
                        Photo(probe, photo.space, sourceWidth = 8, sourceHeight = 8),
                        state.highlightStops,
                    ) { space, linear ->
                        Edit.openingParams(tileEdit, inputSpace = space, linear = linear).toString()
                    }
                    layoutSession = opened.handle
                    if (layoutSession == 0L) null else NativeEngine.overscanLayout(layoutSession, frameW, frameH)
                }
                if (layoutSession == 0L) return "片边布局失败：${NativeEngine.lastError()}"
                if (layoutJson == null) return "片边布局失败：${NativeEngine.lastError()}"
                val layout = runCatching { JSONObject(layoutJson) }.getOrNull()
                    ?: return "片边布局失败：引擎返回的布局不是 JSON"
                if (!layout.optBoolean("valid")) return "片边布局失败：引擎没有给出片边画布"
                canvasW = layout.optInt("canvas_w", 0)
                canvasH = layout.optInt("canvas_h", 0)
                if (canvasW <= 0 || canvasH <= 0) return "片边布局失败：片边画布尺寸为 0"
                vertical = layout.optBoolean("vertical", false)
                val q = layout.optJSONArray("gates")?.optJSONArray(0)
                    ?: return "片边布局失败：引擎没有给出片门位置"
                if (q.length() < 8) return "片边布局失败：片门位置不完整"
                for (i in 0 until 8) gateQuad[i] = q.optDouble(i, 0.0)
                log("tiled export: film edge $frameW×$frameH -> canvas $canvasW×$canvasH vertical=$vertical")
                withContext(NonCancellable + ExportThread.dispatcher) { NativeEngine.release(layoutSession) }
                layoutSession = 0L
            }

            // 相纸边框在拼接后按整幅成图绘制。先按片边画布（没有片边时就是
            // 画面本身）算好成图大小，超出画布预算就在这里说明，而不是等到 OOM。
            val frame = base.frame
            val frameLayout = if (frame.isNone) null else frame.layout(canvasW, canvasH)
            if (frameLayout != null) {
                val framedPixels = frameLayout.width.toLong() * frameLayout.height
                if (framedPixels > TILED_FRAME_MAX_PIXELS) {
                    val mp = framedPixels / 1_000_000.0
                    return "边框会把成图扩到 %.1f MP，超过分块导出的画布上限 %.0f MP；请换更窄的边框或先关闭边框"
                        .format(Locale.ROOT, mp, TILED_FRAME_MAX_PIXELS / 1_000_000.0)
                }
            }

            // 整幅测光：引擎的测光固定缩到 1600 px 长边，这里直接从整幅缩，
            // 得到整幅的 EV；分块不再各自测光。
            val solvedEv = if (edit.autoExposure) {
                val meterScale = min(1.0, 1600.0 / max(w, h))
                val mw = max(1, (w * meterScale).roundToInt())
                val mh = max(1, (h * meterScale).roundToInt())
                val meterBmp = if (mw != w || mh != h) {
                    Bitmap.createScaledBitmap(source, mw, mh, true)
                } else {
                    source.copy(Bitmap.Config.ARGB_8888, false) ?: return "无法复制测光图"
                }
                try {
                    val opened = withContext(ExportThread.dispatcher) {
                        openPhotoSession(
                            engineHandle,
                            Photo(meterBmp, photo.space, sourceWidth = mw, sourceHeight = mh),
                            state.highlightStops,
                        ) { space, linear ->
                            Edit.openingParams(edit, inputSpace = space, linear = linear).toString()
                        }
                    }
                    meterSession = opened.handle
                    if (meterSession == 0L) return "整幅测光失败：${NativeEngine.lastError()}"
                    val json = withContext(ExportThread.dispatcher) { NativeEngine.solve(meterSession, "exposure") }
                    JSONObject(json ?: "{}").optJSONObject("solved_params")
                        ?.optDouble("exposure_compensation_ev", 0.0) ?: 0.0
                } finally {
                    if (meterSession != 0L) withContext(NonCancellable + ExportThread.dispatcher) {
                        NativeEngine.release(meterSession)
                    }
                    meterSession = 0L
                    meterBmp.recycle()
                }
            } else 0.0

            // 每块不超过设备的像素上限和长边；块外留一圈重叠，拼接时裁掉。
            // 所有块都取 tileW×tileH 并贴边，边缘的块向左/向上多取，而不是
            // 变窄变小——统一的块尺寸让胶片按微米算的效果保持同一比例。
            // 片边时的块是**画布**上的窗口（片基比画面大），所以按画布算。
            val cap = state.maxPixels.takeIf { it > 0 } ?: 8_388_608L
            val workingEdge = (state.workingEdge.takeIf { it > 0 } ?: 3072).coerceIn(1024, 3072)
            val tileW = min(canvasW, workingEdge)
            val tileH = min(canvasH, min(workingEdge, (cap / tileW).toInt().coerceAtLeast(1)))
            if (tileW < 256 || tileH < 256) return "可用内存太小，无法切块"
            val ov = min(160, min(tileW, tileH) / 4)
            val coreW = max(1, tileW - 2 * ov)
            val coreH = max(1, tileH - 2 * ov)
            val cols = (canvasW + coreW - 1) / coreW
            val rows = (canvasH + coreH - 1) / coreH

            // 片门四角到画面像素的仿射（canvas → frame）。四角按胶片的
            // (s0,t0),(s1,t0),(s1,t1),(s0,t1) 给：landscape 时它们就是画面的
            // (0,0)、(w,0)、(w,h)、(0,h)；vertical 时胶片沿画面 y 走，画面的
            // (0,0) 落在第 4 个角上，x 沿 −t、y 沿 +s。
            val p00x = if (vertical) gateQuad[6] * canvasW else gateQuad[0] * canvasW
            val p00y = if (vertical) gateQuad[7] * canvasH else gateQuad[1] * canvasH
            val p10x = if (vertical) gateQuad[0] * canvasW else gateQuad[2] * canvasW
            val p10y = if (vertical) gateQuad[1] * canvasH else gateQuad[3] * canvasH
            val p01x = if (vertical) gateQuad[4] * canvasW else gateQuad[6] * canvasW
            val p01y = if (vertical) gateQuad[5] * canvasH else gateQuad[7] * canvasH
            val axx = p10x - p00x
            val axy = p10y - p00y
            val ayx = p01x - p00x
            val ayy = p01y - p00y
            val det = axx * ayy - ayx * axy
            if (edgeOn && abs(det) < 1e-9) return "片边布局失败：片门位置退化"
            fun frameX(cx: Double, cy: Double) =
                frameW * ((cx - p00x) * ayy - (cy - p00y) * ayx) / det
            fun frameY(cx: Double, cy: Double) =
                frameH * (axx * (cy - p00y) - axy * (cx - p00x)) / det

            val canvasSpace = if (state.wideGamut) {
                ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
            } else {
                ColorSpace.get(ColorSpace.Named.SRGB)
            }
            val canvasBmp = Bitmap.createBitmap(canvasW, canvasH, Bitmap.Config.ARGB_8888, false, canvasSpace)
            canvasBitmap = canvasBmp
            val canvas = Canvas(canvasBmp)
            val paint = Paint().apply { isFilterBitmap = false }
            val target = if (state.wideGamut) "Display P3" else "sRGB"
            var done = 0
            for (row in 0 until rows) for (col in 0 until cols) {
                val ccx0 = col * coreW
                val ccy0 = row * coreH
                val ccx1 = min(canvasW, ccx0 + coreW)
                val ccy1 = min(canvasH, ccy0 + coreH)
                if (ccx0 >= ccx1 || ccy0 >= ccy1) continue
                val winX = min(max(0, ccx0 - ov), max(0, canvasW - tileW))
                val winY = min(max(0, ccy0 - ov), max(0, canvasH - tileH))
                val winW = min(canvasW - winX, tileW)
                val winH = min(canvasH - winY, tileH)
                // 窗口对应的源图矩形：片边时按 canvas→frame 仿射取窗口四角的
                // bbox，向外取整并留 2 px 余量（双线性采样要读到邻点），再
                // clamp 到画幅；没有片边时窗口就是源图矩形。
                var sx = winX
                var sy = winY
                var sw = winW
                var sh = winH
                if (edgeOn) {
                    var minFx = Double.MAX_VALUE
                    var minFy = Double.MAX_VALUE
                    var maxFx = -Double.MAX_VALUE
                    var maxFy = -Double.MAX_VALUE
                    for (px in intArrayOf(winX, winX + winW)) for (py in intArrayOf(winY, winY + winH)) {
                        val fx = frameX(px.toDouble(), py.toDouble())
                        val fy = frameY(px.toDouble(), py.toDouble())
                        minFx = min(minFx, fx); minFy = min(minFy, fy)
                        maxFx = max(maxFx, fx); maxFy = max(maxFy, fy)
                    }
                    sx = (floor(minFx) - 2.0).toInt().coerceIn(0, frameW - 1)
                    sy = (floor(minFy) - 2.0).toInt().coerceIn(0, frameH - 1)
                    sw = (ceil(maxFx) + 2.0).toInt().coerceIn(sx + 1, frameW) - sx
                    sh = (ceil(maxFy) + 2.0).toInt().coerceIn(sy + 1, frameH) - sy
                }
                var crop: Bitmap? = Bitmap.createBitmap(source, gateX + sx, gateY + sy, sw, sh)
                if (crop === source) {
                    crop = source.copy(Bitmap.Config.ARGB_8888, false)
                        ?: return "分块导出失败：无法复制整幅分块"
                }
                var handle = 0L
                try {
                    val opened = withContext(ExportThread.dispatcher) {
                        openPhotoSession(
                            engineHandle,
                            Photo(crop!!, photo.space, sourceWidth = sw, sourceHeight = sh),
                            state.highlightStops,
                        ) { space, linear ->
                            val p = Edit.openingParams(tileEdit, inputSpace = space, linear = linear)
                            p.put("auto_exposure", false)
                            if (edit.autoExposure) p.put("exposure_baseline_ev", solvedEv)
                            p.toString()
                        }
                    }
                    handle = opened.handle
                    crop = null // openPhotoSession 已接管这块源图
                    if (handle == 0L) return "分块导出失败：${NativeEngine.lastError()}"
                    val tile = withContext(ExportThread.dispatcher) {
                        // 片边：告诉引擎这块画布窗口在整幅画布上的位置，以及
                        // 这块源图在整幅画面上的位置。布局仍是整幅的，所以齿孔
                        // 间距、片基颜色和边缘字跨块一致。
                        val windowed = !edgeOn || NativeEngine.setRenderWindow(
                            handle, frameW, frameH, winX, winY, winW, winH, sx, sy, sw, sh,
                        )
                        if (!windowed) return@withContext null
                        if (!NativeEngine.setOutput(handle, target, true)) return@withContext null
                        // 调整层是逐像素的（暗角/蒙版/灰尘已在上面排除），
                        // 每块应用同一份，拼接后与整幅一致。
                        if (!look.neutral) {
                            UserLibrary.get(app).applyLut(handle, look, null)
                            if (!NativeEngine.setLook(handle, look.values())) {
                                return@withContext null
                            }
                        }
                        NativeEngine.renderBitmap(handle, "full")
                    } ?: return "分块导出失败：${NativeEngine.lastError()}"
                    if (tile.width != winW || tile.height != winH) {
                        val got = "${tile.width}×${tile.height}"
                        tile.recycle()
                        return "分块导出失败：输出 $got 与分块 ${winW}×${winH} 不一致"
                    }
                    // 只把这块的核心区域放回去，重叠留在边缘。
                    canvas.drawBitmap(
                        tile,
                        Rect(ccx0 - winX, ccy0 - winY, ccx1 - winX, ccy1 - winY),
                        Rect(ccx0, ccy0, ccx1, ccy1),
                        paint,
                    )
                    tile.recycle()
                    done++
                    log("tiled export: tile $done/${rows * cols} ${winW}x${winH}")
                } finally {
                    if (handle != 0L) withContext(NonCancellable + ExportThread.dispatcher) {
                        NativeEngine.release(handle)
                    }
                    crop?.recycle()
                }
            }

            // 拼接完成，源图不再需要：先放掉它，再按整幅成图绘制相纸边框，
            // 峰值就只有「打印 + 成图」两张，和分块前的源图+画布一样。
            full?.recycle()
            full = null
            val output = if (frame.isNone) {
                canvasBmp
            } else {
                FrameSpec.render(canvasBmp, frame, Caption.of(state.exif, catalog.film(edit.film)?.label ?: ""))
            }
            if (output !== canvasBmp) {
                canvasBmp.recycle()
                canvasBitmap = output
            }

            val name = "Spektralab-X_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date()) + "_tiled.jpg"
            val dir = File(app.cacheDir, "export").apply { mkdirs() }
            val part = File(dir, "$name.part")
            // 先走队列同款的 4:4:4 编码（无子采样，色度细节不软），ICC 取自
            // 输出位图自己的色彩空间；编不了时退回 Bitmap.compress 的 4:2:0，
            // 不让一次编码失败丢掉整张导出。
            val codec = withContext(Dispatchers.IO) {
                val icc = tiledIcc(output.colorSpace)
                if (icc != null && NativeEngine.writeJpeg444(output, part.absolutePath, 98, icc)) {
                    "4:4:4"
                } else {
                    part.delete()
                    if (part.outputStream().use { output.compress(Bitmap.CompressFormat.JPEG, 98, it) }) "4:2:0" else null
                }
            } ?: return "分块导出失败：JPEG 编码失败"
            try {
                withContext(Dispatchers.IO) { MediaSaver.saveFile(app, part, name, "image/jpeg") }
            } finally {
                withContext(Dispatchers.IO) { part.delete() }
            }
            val ms = SystemClock.elapsedRealtime() - started
            val text = buildString {
                append("分块导出完成\n")
                append("来源：${src.kind.uppercase(Locale.ROOT)} ${src.sourceWidth}×${src.sourceHeight}\n")
                append("画面：$frameW×$frameH（%.1f MP）\n".format(Locale.ROOT, frameW.toDouble() * frameH / 1_000_000.0))
                if (edgeOn) {
                    append(("片边画布：$canvasW×$canvasH（%.1f MP）· ${edit.edgeFormat}\n")
                        .format(Locale.ROOT, canvasW.toDouble() * canvasH / 1_000_000.0))
                }
                if (!frame.isNone) {
                    val label = Borders.byId(frame.id)?.label ?: "自定义边框"
                    append(("边框：$label · 成图 ${output.width}×${output.height}（%.1f MP）\n")
                        .format(Locale.ROOT, output.width.toDouble() * output.height / 1_000_000.0))
                }
                append("分块：${cols}×${rows} · 每块 ${tileW}×$tileH · 重叠 $ov px\n")
                if (edit.autoExposure) append("整幅测光：%+.2f EV（每块一致）\n".format(Locale.ROOT, solvedEv))
                append("色彩：${if (state.wideGamut) "Display P3" else "sRGB"} · JPEG 98 · $codec\n")
                append("用时：%.1f s\n".format(Locale.ROOT, ms / 1000.0))
                append("已保存到相册：$name")
            }
            log("tiled export: $text")
            return text
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            return "分块导出失败：内存不足，整幅源图和输出图放不下"
        } catch (e: Throwable) {
            return "分块导出失败：${e.message ?: e}"
        } finally {
            if (meterSession != 0L) {
                try {
                    withContext(NonCancellable + ExportThread.dispatcher) { NativeEngine.release(meterSession) }
                } catch (_: Throwable) {
                }
            }
            if (layoutSession != 0L) {
                try {
                    withContext(NonCancellable + ExportThread.dispatcher) { NativeEngine.release(layoutSession) }
                } catch (_: Throwable) {
                }
            }
            full?.recycle()
            canvasBitmap?.recycle()
        }
    }

    /**
     * The ICC profile Skia embeds for [space]: a 1x1 JPEG is encoded and its
     * APP2 ICC_PROFILE chunks joined, the same way ProcessingQueue does for its
     * 4:4:4 path, so a tiled 4:4:4 and a queue 4:4:4 carry identical bytes.
     */
    private fun tiledIcc(space: ColorSpace?): ByteArray? = runCatching {
        val s = space ?: ColorSpace.get(ColorSpace.Named.SRGB)
        val tiny = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888, false, s)
        val jpeg = java.io.ByteArrayOutputStream()
            .also { tiny.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
        tiny.recycle()
        ProcessingQueue.Icc.iccFromJpeg(jpeg)
    }.getOrNull()

    // --- settings ------------------------------------------------------------

    /** Everything a bug report needs, as plain text. No file names or locations. */
    fun diagnostics(): String {
        val ui = _ui.value
        val am = app.getSystemService(android.app.ActivityManager::class.java)
        val mem = android.app.ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val rt = Runtime.getRuntime()
        val src = ui.source
        return buildString {
            appendLine("Spektralab-X ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (${android.os.Build.DEVICE}) · " +
                "Android ${android.os.Build.VERSION.RELEASE} / API ${android.os.Build.VERSION.SDK_INT}")
            appendLine("SoC: ${if (android.os.Build.VERSION.SDK_INT >= 31) android.os.Build.SOC_MODEL else "?"}")
            appendLine("Thermal headroom (10s forecast): ${"%.2f".format(Locale.ROOT, thermalHeadroom())} (1.0 = severe threshold; NaN = unavailable)")
            appendLine("RAM: total ${mem.totalMem shr 20} MB, available ${mem.availMem shr 20} MB, low=${mem.lowMemory}")
            appendLine("Java heap: max ${rt.maxMemory() shr 20} MB, used ${(rt.totalMemory() - rt.freeMemory()) shr 20} MB")
            appendLine("Native heap: ${android.os.Debug.getNativeHeapAllocatedSize() shr 20} MB")
            appendLine("Working edge: ${ui.workingEdge} · pixel cap: ${ui.maxPixels}")
            appendLine("GPU: ${deviceJson.ifEmpty { "-" }}")
            appendLine("Engine: ${ui.engineSummary.ifEmpty { "-" }}")
            if (src != null) appendLine("Image: ${src.kind} ${src.sourceWidth}x${src.sourceHeight} -> ${src.width}x${src.height}" +
                (if (src.camera.isNotBlank()) " · ${src.camera}" else ""))
            if (src != null && src.inputPrecision.isNotBlank()) appendLine("Input precision: ${src.inputPrecision}" +
                (if (src.inputPrecisionNote.isNotBlank()) " · ${src.inputPrecisionNote}" else ""))
            benchmarkResult?.let { append(BenchmarkPolicy.diagnostics(it)) }
            appendLine("Edit: ${ui.edit.toJson()}")
            appendLine("Events:")
            host.events.snapshot().forEach { appendLine("  $it") }
        }
    }

    fun setWideGamut(on: Boolean) {
        prefs.edit().putBoolean("wide_gamut", on).apply()
        _ui.update { it.copy(wideGamut = on) }
    }

    /** A phone photo is re-opened with the new recovery; the edit is kept. */
    fun setHighlightStops(stops: Float) {
        if (stops == _ui.value.highlightStops || _ui.value.exporting) return
        prefs.edit().putFloat("highlight_stops_v2", stops).apply()
        _ui.update { it.copy(highlightStops = stops) }
        val uri = lastUri ?: return
        val src = _ui.value.source ?: return
        if (src.kind == "raw") return
        launchOpen(uri, wb = src.wbK to src.wbTint)
    }

    // --- surviving process death (Spektrafilm-android #219) ----------------------

    // The open photo and its whole edit, as a sidecar plus its URI. Rewritten
    // shortly after every edit; read once when a new process starts with
    // nothing to open, so a photo the system killed us under comes back as it was.

    private suspend fun saveLoop() {
        for (request in saves) {
            kotlinx.coroutines.delay(400)
            saves.tryReceive()
            val uri = lastUri ?: continue
            val state = _ui.value
            if (state.busy != null) continue
            val src = state.source ?: continue
            val frame = Frame(id = "", uri = uri.toString(), name = src.name, size = 0, date = 0, mime = "",
                raw = src.kind == "raw")
            val json = Sidecar(wanted, src.wbK, src.wbTint, System.currentTimeMillis()).toJson(frame)
                .put("uri", uri.toString()).toString()
            withContext(Dispatchers.IO) {
                runCatching {
                    val part = File(stateFile.path + ".part")
                    part.writeText(json)
                    if (!part.renameTo(stateFile)) { stateFile.delete(); part.renameTo(stateFile) }
                }
            }
        }
    }

    /**
     * Reopens the photo the last process had open, edit and RAW white balance
     * kept. The file is removed first and written again once the open lands, so
     * a photo that fails (or crashes) to open is not retried at every launch.
     */
    private suspend fun restoreLast() {
        val state = withContext(Dispatchers.IO) {
            runCatching { JSONObject(stateFile.readText()) }.getOrNull().also { stateFile.delete() }
        } ?: return
        val uri = state.optString("uri").takeIf { it.isNotEmpty() }?.let(Uri::parse) ?: return
        val car = Sidecar.fromJson(state) ?: return
        val readable = withContext(Dispatchers.IO) {
            runCatching { app.contentResolver.openFileDescriptor(uri, "r")?.close() != null }.getOrDefault(false)
        }
        if (!readable) { log("last photo no longer readable; not restored"); return }
        log("restoring the last photo")
        openSaved(uri, car.edit, car.wbK, car.wbTint)
        _ui.update { it.copy(message = "已回到上次的照片和编辑") }
    }

    /**
     * IO. Keeps a read grant on a photo the user picked, so a new process can
     * open it again; the previous photo's grant is let go unless the light table
     * or the export queue still reads it.
     */
    private fun holdGrant(uri: Uri) {
        if (uri.scheme != "content") return
        synchronized(SourceGrantLock.monitor) {
            val resolver = app.contentResolver
            val took = runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess
            val previous = prefs.getString("editor_grant", null)
            if (previous != null && previous != uri.toString()) {
                val queued = app.getSharedPreferences("processing", 0).getStringSet("grants", emptySet()).orEmpty()
                if (previous !in queued && !FrameStore.of(app).holds(previous))
                    runCatching { resolver.releasePersistableUriPermission(Uri.parse(previous), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
            prefs.edit().putString("editor_grant", if (took) uri.toString() else null).apply()
        }
    }

    /** A photo has been opened in this process, so [wanted] already holds the last pair. */
    private var lastOpenedAny = false

    /**
     * The film and paper a new photo starts on (upstream PR #53); empty film =
     * the last photo's, which is what the editor has always carried over.
     */
    fun setNewPhotoStocks(film: String, paper: String) {
        val mode = if (film.isEmpty()) NEW_REMEMBER else NEW_FIXED
        prefs.edit().putString("default_film", film).putString("default_paper", paper)
            .putString("new_photo_mode", mode).apply()
        _ui.update { it.copy(defaultFilm = film, defaultPaper = paper, newPhotoMode = mode) }
    }

    /** 新照片的胶片和相纸 → 无: every new photo opens as it is, nothing applied. */
    fun setNewPhotoNone() {
        prefs.edit().putString("new_photo_mode", NEW_NONE).apply()
        _ui.update { it.copy(newPhotoMode = NEW_NONE) }
    }

    /**
     * [e] on the chosen pair. A film no longer in the catalog leaves [e] as it
     * is; a paper that cannot print the film (colour vs B&W) gives way to the
     * film's own target print, as picking the film by hand does.
     */
    private fun withNewPhotoStocks(e: Edit): Edit {
        val catalog = _ui.value.catalog
        val mode = _ui.value.newPhotoMode
        // 无: the photo as it is. Only display preferences and this photo's own
        // shooting data come along, nothing of the last photo's look.
        if (mode == NEW_NONE) return catalog.normalize(Edit.NONE.copy(
            dateOrder = e.dateOrder, dateWithData = e.dateWithData, edgeRealName = e.edgeRealName,
            dateText = e.dateText, dateDataText = e.dateDataText, edgeFNumber = e.edgeFNumber,
        ))
        // Remembering: in this process the last photo's pair is already carried; the
        // first photo after a restart takes the pair recorded by applyEdit.
        val remember = mode == NEW_REMEMBER
        if (remember && lastOpenedAny) return e
        if (!remember) return withFixedStocks(e, catalog, _ui.value.defaultFilm, _ui.value.defaultPaper) ?: e
        val wantFilm = prefs.getString("last_film", "").orEmpty()
        val wantPaper = prefs.getString("last_paper", "").orEmpty()
        return withRememberedStocks(e, catalog, wantFilm, wantPaper,
            prefs.getBoolean("last_no_film", false), prefs.getBoolean("last_di", false),
            if (prefs.contains("last_scan_film")) prefs.getBoolean("last_scan_film", false) else null) ?: e
    }

    fun setWorkingEdge(edge: Int) {
        if (edge == _ui.value.workingEdge || _ui.value.exporting) return
        prefs.edit().putInt("working_edge", edge).apply()
        _ui.update { it.copy(workingEdge = edge) }
        // Same photo at a new size: crop, white balance and history stay.
        val uri = lastUri ?: return
        val src = _ui.value.source ?: return
        launchOpen(uri, wb = src.wbK to src.wbTint)
    }

    /** 启动性能测试（在设置页面调用）*/
    fun startBenchmark() {
        benchmarkJob?.cancel()
        benchmarkJob = viewModelScope.launch {
            _ui.update { it.copy(busy = "正在准备性能测试…") }
            try {
                val ui = _ui.value
                val ready = host.start(wanted.film, wanted.paper)
                val backend = RealBenchmarkBackend(ready.engine, ready.catalog, ready.dir)
                val runner = BenchmarkRunner(
                    backend = backend,
                    clockMs = { android.os.SystemClock.elapsedRealtime() },
                    headroom = { host.thermal.headroom() },
                    resetPeak = { ProcMemory.resetPeak() },
                    rssKb = { ProcMemory.statusKb("VmRSS") },
                    peakKb = { ProcMemory.statusKb("VmHWM") },
                    maxPixels = ui.maxPixels,
                    // memoryClass is a Java-heap MB limit, not native/GPU RSS.
                    // No measured per-process native budget is available here.
                    peakBudgetMb = 0L,
                )
                val outcome = host.transientSessions.withLease(
                    onWaiting = { _ui.update { it.copy(busy = "等待当前处理") } },
                ) { runner.run { phase, _ -> _ui.update { it.copy(busy = phase) } } }
                when (outcome) {
                    is BenchmarkOutcome.Done -> {
                        benchmarkResult = outcome.result
                        log("benchmark completed: ${outcome.result.firstPreviewMs}ms preview, ${outcome.result.exportMs}ms export")
                        _ui.update { it.copy(busy = null, message = "性能测试完成。结果已加入诊断信息。") }
                    }
                    is BenchmarkOutcome.TooHot -> {
                        val msg = "机身温度偏高（热余量 ${"%.2f".format(outcome.headroom)}），测试已停止在「${outcome.phase}」。降温后重试。"
                        log("benchmark aborted: too hot at ${outcome.phase}")
                        _ui.update { it.copy(busy = null, message = msg) }
                    }
                    is BenchmarkOutcome.Failed -> {
                        val msg = "[${outcome.code}] ${outcome.message}"
                        log("benchmark failed: $msg")
                        _ui.update { it.copy(busy = null, error = msg) }
                    }
                }
            } catch (e: CancellationException) {
                _ui.update { it.copy(busy = null) }
            } catch (e: Throwable) {
                val code = ErrorCodes.extract(e.message) ?: ErrorCodes.ENGINE_EXCEPTION
                val msg = ErrorCodes.format(code, "性能测试失败：${e.message}")
                log("benchmark exception: $msg")
                _ui.update { it.copy(busy = null, error = msg) }
            }
        }
    }

    /**
     * The system is short of memory (onTrimMemory): give back the GPU buffer
     * pool's idle planes and the detail view. The session and its cached
     * negative stay, so the photo does not have to be re-opened.
     */
    fun trimMemory(level: Int) {
        if (level < android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW &&
            level != android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return
        invalidateDetail()
        synchronized(pool) { spare.clear() }
        val e = engine
        if (e == 0L) return
        viewModelScope.launch {
            val freed = withContext(EngineThread.dispatcher) { NativeEngine.trimMemory(e) }
            log("trim memory level $level: ${freed shr 20} MB freed")
        }
    }

    /**
     * Experimental, read-only: opens the current photo again at a 2048 px long
     * edge with the current film and paper and no border, renders one full
     * frame, and reports the timings. It uses a throwaway session, so the
     * editor's own session, undo history and rendered preview are untouched.
     * This checks whether one tile fits and how long it takes; it does not
     * stitch tiles or claim to export high-resolution photos.
     */
    suspend fun benchmarkTile(): String {
        val state = _ui.value
        val uri = lastUri ?: return "先在编辑器里打开一张照片"
        val src = state.source ?: return "照片还没打开完"
        val engineHandle = engine
        if (engineHandle == 0L) return "引擎还没准备好"
        val catalog = state.catalog
        val film = state.edit.film.takeIf { it.isNotEmpty() && catalog.film(it) != null }
            ?: catalog.films.firstOrNull()?.id ?: return "没有可用的胶片"
        val base = withFilm(state.edit, catalog, film) ?: return "胶片不可用：$film"
        val tileEdit = catalog.normalize(base.copy(
            quarterTurns = 0, flipH = false,
            cropX = 0.0, cropY = 0.0, cropW = 1.0, cropH = 1.0, cropAspect = 0.0,
            filmEdge = false, dateImprint = false, borderId = "none", frame = FrameSpec(),
        ))
        val params = Edit.openingParams(tileEdit, inputSpace = INPUT_SPACE, linear = true).toString()
        val started = SystemClock.elapsedRealtime()
        var handle = 0L
        var openError = ""
        try {
            if (src.kind == "raw") {
                var input: RawInput? = null
                try {
                    input = withContext(Dispatchers.IO) { loader.rawInput(uri, src.name) }
                    val raw = input ?: throw EngineException("无法读取该文件")
                    handle = withContext(ExportThread.dispatcher) {
                        val h = NativeEngine.openRaw(engineHandle, raw.path, BENCH_EDGE, 0, params, src.wbK, src.wbTint)
                        if (h == 0L) openError = NativeEngine.lastError()
                        h
                    }
                } finally {
                    runCatching { input?.close() }
                }
            } else {
                val photo = withContext(Dispatchers.IO) { loader.decodePhoto(uri, BENCH_EDGE, 0) }
                handle = withContext(ExportThread.dispatcher) {
                    val h = openPhotoSession(engineHandle, photo, state.highlightStops) { space, linear ->
                        Edit.openingParams(tileEdit, inputSpace = space, linear = linear).toString()
                    }.handle
                    if (h == 0L) openError = NativeEngine.lastError()
                    h
                }
            }
            val openMs = SystemClock.elapsedRealtime() - started
            if (handle == 0L) return "分块测试失败：${openError.ifEmpty { "打开失败" }}"
            val bench = withContext(ExportThread.dispatcher) {
                NativeEngine.setOutput(handle, "sRGB", false)
                val firstStart = SystemClock.elapsedRealtime()
                val first = NativeEngine.renderBitmap(handle, "full")
                val firstMs = SystemClock.elapsedRealtime() - firstStart
                if (first == null) return@withContext Bench(-1, -1, 0, 0, NativeEngine.lastError())
                val secondStart = SystemClock.elapsedRealtime()
                val second = NativeEngine.renderBitmap(handle, "full")
                val secondMs = SystemClock.elapsedRealtime() - secondStart
                val w = first.width
                val h = first.height
                first.recycle()
                second?.recycle()
                Bench(firstMs, if (second != null) secondMs else -1, w, h,
                    if (second == null) NativeEngine.lastError() else "")
            }
            if (bench.firstMs < 0) return "分块测试失败：${bench.error.ifEmpty { "渲染失败" }}"
            val mp = bench.w.toDouble() * bench.h / 1_000_000.0
            val tileMp = BENCH_EDGE.toDouble() * BENCH_EDGE / 1_000_000.0
            val steady = if (bench.secondMs >= 0) bench.secondMs else bench.firstMs
            val estimate = if (mp > 0) (steady * tileMp / mp).roundToInt() else steady
            val text = buildString {
                append("单块测试（${BENCH_EDGE} px 长边）\n")
                append("来源：${src.kind.uppercase(Locale.ROOT)} ${src.sourceWidth}×${src.sourceHeight}\n")
                append("打开：${openMs} ms\n")
                append("首帧：${bench.firstMs} ms\n")
                if (bench.secondMs >= 0) append("热帧：${bench.secondMs} ms\n")
                append("输出：${bench.w}×${bench.h}（%.1f MP）\n".format(Locale.ROOT, mp))
                append("按 ${BENCH_EDGE}×${BENCH_EDGE} 一块估算：约 $estimate ms\n")
                append("只测单块，未拼图；接缝和整图导出下一步。")
            }
            log("tile bench: $text")
            return text
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            return "分块测试失败：内存不足，连 ${BENCH_EDGE} px 单块都放不下"
        } catch (e: Throwable) {
            return "分块测试失败：${e.message ?: e}"
        } finally {
            if (handle != 0L) withContext(kotlinx.coroutines.NonCancellable + ExportThread.dispatcher) {
                NativeEngine.release(handle)
            }
        }
    }

    /** One tile's measured times and output size; [error] when the second render failed. */
    private data class Bench(val firstMs: Long, val secondMs: Long, val w: Int, val h: Int, val error: String)

    /**
     * Experimental, read-only: cuts four overlapping tiles from the current
     * photo (2x2) and renders each one as its own film+paper session, with auto
     * exposure, grain, halation, glare, scanner sharpening, border and film
     * edge off. The overlapping pixels are compared where the stitched image
     * would join them. A throwaway session per tile, so the editor's session,
     * undo history and preview are untouched.
     *
     * This answers the one question the single-tile benchmark cannot: is the
     * film pipeline the same on every tile? If it is, tiles can be butted
     * together or blended; if not, a tiled export needs global statistics or a
     * wider blend. RAW is not supported here: cropping a RAW needs a native
     * decode path, not a Bitmap crop.
     */
    suspend fun seamTest(): String {
        val state = _ui.value
        val uri = lastUri ?: return "先在编辑器里打开一张照片"
        val src = state.source ?: return "照片还没打开完"
        if (src.kind == "raw") return "接缝测试目前只支持 JPEG/PNG/HEIC；RAW 需要原生裁切，下一步再做"
        val engineHandle = engine
        if (engineHandle == 0L) return "引擎还没准备好"
        val catalog = state.catalog
        val film = state.edit.film.takeIf { it.isNotEmpty() && catalog.film(it) != null }
            ?: catalog.films.firstOrNull()?.id ?: return "没有可用的胶片"
        val stock = catalog.film(film) ?: return "胶片不可用：$film"
        val base = withFilm(state.edit, catalog, film) ?: return "胶片不可用：$film"
        val paperId = base.paper.takeIf { catalog.paper(it)?.bw == stock.bw }
            ?: stock.targetPrint?.takeIf { catalog.paper(it)?.bw == stock.bw }
            ?: catalog.papers.firstOrNull { it.bw == stock.bw }?.id
            ?: return "没有与胶片匹配的相纸"
        val paperLabel = catalog.paper(paperId)?.label ?: paperId
        // A normal print path, not a slide scan or a digital intermediate.
        val testEdit = catalog.normalize(base.copy(
            paper = paperId,
            noFilm = false,
            scanFilm = stock.positive,
            digitalIntermediate = false,
            autoExposure = false,
            exposureEv = 0.0,
            labLevels = false,
            quarterTurns = 0, flipH = false,
            cropX = 0.0, cropY = 0.0, cropW = 1.0, cropH = 1.0, cropAspect = 0.0,
            filmEdge = false, dateImprint = false, borderId = "none", frame = FrameSpec(),
        ).withoutEffects())

        var photo: Photo? = null
        val tiles = ArrayList<SeamTile>(4)
        try {
            val decodeStart = SystemClock.elapsedRealtime()
            val decoded = withContext(Dispatchers.IO) { loader.decodePhoto(uri, SEAM_EDGE, 0) }
            photo = decoded
            decoded.gain?.recycle()
            val decodeMs = SystemClock.elapsedRealtime() - decodeStart
            val bitmap = decoded.bitmap
            val w = bitmap.width
            val h = bitmap.height
            val splitX = w / 2
            val splitY = h / 2
            if (splitX < 128 || splitY < 128) return "照片太小，接缝测试至少需要 256×256"
            // At most a quarter of the short side, so the overlap never eats the core.
            val margin = min(SEAM_MARGIN, min(splitX, splitY) / 2)
            if (margin < 8) return "照片太小，接缝测试需要更大的图"
            val rects = listOf(
                intArrayOf(0, 0, splitX + margin, splitY + margin),
                intArrayOf(splitX - margin, 0, w, splitY + margin),
                intArrayOf(0, splitY - margin, splitX + margin, h),
                intArrayOf(splitX - margin, splitY - margin, w, h),
            )
            var totalOpen = 0L
            var totalRender = 0L
            for ((i, r) in rects.withIndex()) {
                var crop = Bitmap.createBitmap(bitmap, r[0], r[1], r[2] - r[0], r[3] - r[1])
                if (crop === bitmap) {
                    crop = crop.copy(Bitmap.Config.ARGB_8888, false)
                        ?: return "接缝测试失败：无法复制分块"
                }
                val handle = LongArray(1)
                val error = arrayOf("")
                try {
                    val openMs = withContext(ExportThread.dispatcher) {
                        val start = SystemClock.elapsedRealtime()
                        val opened = openPhotoSession(engineHandle, Photo(crop, decoded.space), 0f) { space, linear ->
                            Edit.openingParams(testEdit, inputSpace = space, linear = linear).toString()
                        }
                        handle[0] = opened.handle
                        if (opened.handle == 0L) error[0] = NativeEngine.lastError()
                        SystemClock.elapsedRealtime() - start
                    }
                    if (handle[0] == 0L) return "接缝测试失败：${error[0].ifEmpty { "打开第 ${i + 1} 块失败" }}"
                    val rendered = withContext(ExportThread.dispatcher) {
                        val ok = NativeEngine.setOutput(handle[0], "sRGB", true)
                        if (!ok) error[0] = NativeEngine.lastError()
                        val start = SystemClock.elapsedRealtime()
                        val out = if (ok) NativeEngine.renderBitmap(handle[0], "full") else null
                        val ms = SystemClock.elapsedRealtime() - start
                        if (out == null && ok) error[0] = NativeEngine.lastError()
                        out to ms
                    }
                    val out = rendered.first
                    if (out == null) return "接缝测试失败：${error[0].ifEmpty { "渲染第 ${i + 1} 块失败" }}"
                    if (out.width != r[2] - r[0] || out.height != r[3] - r[1])
                        return "接缝测试失败：第 ${i + 1} 块输出 ${out.width}×${out.height} " +
                            "与分块 ${r[2] - r[0]}×${r[3] - r[1]} 不一致"
                    totalOpen += openMs
                    totalRender += rendered.second
                    tiles += SeamTile(out, r[0], r[1])
                } finally {
                    if (handle[0] != 0L) withContext(kotlinx.coroutines.NonCancellable + ExportThread.dispatcher) {
                        NativeEngine.release(handle[0])
                    }
                }
            }
            if (tiles.size != 4) return "接缝测试失败：分块没有全部生成"

            val tl = tiles[0]
            val tr = tiles[1]
            val bl = tiles[2]
            val br = tiles[3]
            // The whole overlap: catches a global shift anywhere in the strip.
            val topAll = seamDiff(tl.bitmap, splitX - margin, 0, tr.bitmap, 0, 0, 2 * margin, splitY + margin)
            val bottomAll = seamDiff(bl.bitmap, splitX - margin, 0, br.bitmap, 0, 0,
                2 * margin, h - (splitY - margin))
            val leftAll = seamDiff(tl.bitmap, 0, splitY - margin, bl.bitmap, 0, 0,
                splitX + margin, 2 * margin)
            val rightAll = seamDiff(tr.bitmap, 0, splitY - margin, br.bitmap, 0, 0,
                w - (splitX - margin), 2 * margin)
            // The band the stitched image actually joins: what the eye can see.
            val half = min(SEAM_BAND / 2, margin)
            val band = 2 * half
            val top = seamDiff(tl.bitmap, splitX - half, 0, tr.bitmap, margin - half, 0,
                band, splitY + margin)
            val bottom = seamDiff(bl.bitmap, splitX - half, 0, br.bitmap, margin - half, 0,
                band, h - (splitY - margin))
            val left = seamDiff(tl.bitmap, 0, splitY - half, bl.bitmap, 0, margin - half,
                splitX + margin, band)
            val right = seamDiff(tr.bitmap, 0, splitY - half, br.bitmap, 0, margin - half,
                w - (splitX - margin), band)

            val centers = listOf(top, bottom, left, right)
            val worstMax = centers.maxOf { it.max }
            val worstMean = centers.maxOf { it.mean }
            val worstBias = centers.maxOf { abs(it.bias) }
            val verdict = when {
                worstMax <= 2 && worstMean <= 1.0 ->
                    "接缝可忽略（≤2 级），可以按块直接拼接"
                worstMax <= 6 ->
                    "差异轻微（最大 $worstMax 级），建议拼接时重叠 16–32 px 融合"
                else ->
                    "差异明显（最大 $worstMax 级），需要融合或全图统计"
            }
            fun line(s: SeamDiff) = "平均 %.1f · p99 %d · 最大 %d".format(Locale.ROOT, s.mean, s.p99, s.max)
            fun allLine(s: SeamDiff) = "平均 %.1f · 最大 %d".format(Locale.ROOT, s.mean, s.max)
            val text = buildString {
                append("接缝测试（2×2 · 重叠 ${margin} px）\n")
                append("来源：${src.kind.uppercase(Locale.ROOT)} ${src.sourceWidth}×${src.sourceHeight} → ${w}×${h}\n")
                append("胶片：${stock.label} / 相纸：$paperLabel\n")
                append("自动曝光关 · 效果关 · 无边框/片边 · 不含调整层\n")
                append("单块约 ${splitX + margin}×${splitY + margin}（%.1f MP）\n"
                    .format(Locale.ROOT, (splitX + margin).toDouble() * (splitY + margin) / 1_000_000.0))
                append("解码 $decodeMs ms · 打开均 ${totalOpen / 4} ms · 渲染均 ${totalRender / 4} ms\n")
                append("拼接带（中心 ${band} px）：\n")
                append("  上 ${line(top)}\n")
                append("  下 ${line(bottom)}\n")
                append("  左 ${line(left)}\n")
                append("  右 ${line(right)}\n")
                append("整段重叠（参考）：上 ${allLine(topAll)} · 下 ${allLine(bottomAll)}\n")
                append("  左 ${allLine(leftAll)} · 右 ${allLine(rightAll)}\n")
                append("最大亮度偏移：%.2f 级\n".format(Locale.ROOT, worstBias))
                append("判定：$verdict\n")
                append("只比较相邻块，不改编辑；RAW 需要原生裁切。")
            }
            log("seam test: $text")
            return text
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            return "接缝测试失败：内存不足，放不下 ${SEAM_EDGE} px 的源图和分块"
        } catch (e: Throwable) {
            return "接缝测试失败：${e.message ?: e}"
        } finally {
            for (t in tiles) t.bitmap.recycle()
            photo?.recycle()
        }
    }

    /** One rendered tile and where its top-left corner sits in the decoded source. */
    private data class SeamTile(val bitmap: Bitmap, val x0: Int, val y0: Int)

    /** Overlap difference in 8-bit levels; [bias] is the signed mean luminance difference. */
    private data class SeamDiff(val mean: Double, val p99: Int, val max: Int, val bias: Double)

    /**
     * Compares the same source rectangle in two rendered tiles. Local coordinates
     * are given per tile, so no scaling is involved: the crops are 1:1 pixels.
     */
    private fun seamDiff(
        a: Bitmap, ax: Int, ay: Int, b: Bitmap, bx: Int, by: Int, w: Int, h: Int,
    ): SeamDiff {
        val n = w * h
        val pa = IntArray(n)
        val pb = IntArray(n)
        a.getPixels(pa, 0, w, ax, ay, w, h)
        b.getPixels(pb, 0, w, bx, by, w, h)
        val hist = IntArray(256)
        var sum = 0.0
        var bias = 0.0
        var maxD = 0
        for (i in 0 until n) {
            val p = pa[i]
            val q = pb[i]
            val dr = ((p shr 16) and 0xFF) - ((q shr 16) and 0xFF)
            val dg = ((p shr 8) and 0xFF) - ((q shr 8) and 0xFF)
            val db = (p and 0xFF) - (q and 0xFF)
            val d = max(abs(dr), max(abs(dg), abs(db)))
            hist[d]++
            sum += d
            bias += 0.2126 * dr + 0.7152 * dg + 0.0722 * db
            if (d > maxD) maxD = d
        }
        var acc = 0
        var p99 = 0
        val target = (n * 0.99).toInt().coerceAtLeast(1)
        for (d in 0..255) {
            acc += hist[d]
            if (acc >= target) { p99 = d; break }
        }
        return SeamDiff(sum / n, p99, maxD, bias / n)
    }

    // --- imported styles and films (StyleImport.kt) ------------------------------

    /** After a film import: the engine directory's profiles read again, so the new stock is listed. */
    fun reloadCatalog() {
        viewModelScope.launch {
            val dir = UserLibrary.get(app).engineDir
            val catalog = withContext(Dispatchers.IO) { runCatching { Catalog.load(dir) }.getOrNull() } ?: return@launch
            _ui.update { it.copy(catalog = catalog) }
        }
    }

    /**
     * A just-imported film or paper opened once in the engine (warmUp builds its
     * tables): null when it works, the engine's error otherwise, so the import
     * can be deleted again.
     */
    suspend fun trialStock(film: String, paper: String): String? = withContext(EngineThread.dispatcher) {
        if (engine == 0L) return@withContext "引擎还没准备好"
        if (NativeEngine.warmUp(engine, film, paper)) null else NativeEngine.lastError().ifEmpty { "未知错误" }
    }

    /** The open edit as a .spklook in the cache, for the share sheet; null when there is no photo. */
    suspend fun exportLook(name: String): File? {
        val state = _ui.value
        if (!state.hasImage) return null
        return withContext(Dispatchers.IO) {
            val library = UserLibrary.get(app)
            library.exportLook(state.edit, state.catalog, name, StyleImports.thumbnail(state.image))
        }
    }

    fun consumeMessage() = _ui.update { it.copy(message = null) }
    fun showMessage(text: String) = _ui.update { it.copy(message = text) }
    fun dismissError() = _ui.update { it.copy(error = null) }

    override fun onCleared() {
        // The engine stays warm in EngineHost for the next editor. The session
        // is released after a pending open has either committed or cleaned up.
        // A commit's NonCancellable section may still install its new session.
        cardsJob?.cancel()
        val opening = openJob
        opening?.cancel()
        host.scope.launch {
            opening?.join()
            withContext(EngineThread.dispatcher) { releaseSession() }
        }
    }

    companion object {
        /** Edits closer together than this are one undo step (a slider drag). */
        private const val kSettleMs = 700L
        private const val kMaxUndo = 50
        private const val kHotHeadroom = 0.85f
        private const val kMaxDetailPixels = 16_000_000L
        /** The experimental tile benchmark's long edge; one 2048 px frame is ~3 MP in 3:2. */
        private const val BENCH_EDGE = 2048
        /**
         * The 2x2 seam test decodes the photo once at this long edge, then cuts
         * four overlapping tiles from it. At 4096 px a 3:2 frame's tile is about
         * 3.6 MP, below the 8.4 MP engine cap with room for the film pipeline.
         */
        private const val SEAM_EDGE = 4096
        /** Pixels of overlap on each side of a seam; enough to see edge effects, still small. */
        private const val SEAM_MARGIN = 128
        /** The band around a seam that the final stitched image would actually show. */
        private const val SEAM_BAND = 32
        /** The cap on the framed card a tiled export may build, in pixels (384 MB at ARGB_8888). */
        private const val TILED_FRAME_MAX_PIXELS = 96_000_000L
        /** The engine's preview tier, which the share size exports. */
        private const val SHARE_EDGE = 3400.0

        /**
         * [edit] with film [id] and 胶片「无」off; the paper changes only when it
         * cannot print it (colour vs B&W). A negative's 相纸「无」(the Digital
         * Intermediate) carries to the next negative; a slide is scanned directly.
         */
        fun withFilm(edit: Edit, catalog: Catalog, id: String): Edit? {
            val film = catalog.film(id) ?: return null
            var paper = edit.paper
            val paperBw = catalog.paper(paper)?.bw ?: false
            if (film.targetPrint != null && paperBw != film.bw) paper = film.targetPrint
            // From a slide's direct scan (相纸「无」) to a negative: its 无 is the DI.
            val di = !film.positive && !film.bw && (edit.digitalIntermediate || edit.scanFilm)
            return catalog.normalize(edit.copy(film = id, scanFilm = film.positive, paper = paper,
                noFilm = false, digitalIntermediate = di))
        }

        /** A fixed film/paper pair prints on that paper, just like picking it by hand. */
        internal fun withFixedStocks(edit: Edit, catalog: Catalog, filmId: String, paperId: String): Edit? {
            val film = catalog.film(filmId) ?: return null
            val next = withFilm(edit, catalog, filmId) ?: return null
            val paper = catalog.paper(paperId)
                ?.takeIf { it.bw == film.bw }
                ?: catalog.paper(next.paper)?.takeIf { it.bw == film.bw }
                ?: return next
            return catalog.normalize(next.copy(paper = paper.id, scanFilm = false, digitalIntermediate = false))
        }

        /** Restart restores the selected print/scan intent, not only the stock names. */
        internal fun withRememberedStocks(edit: Edit, catalog: Catalog, filmId: String, paperId: String,
                                          noFilm: Boolean, di: Boolean, scan: Boolean?): Edit? {
            val film = catalog.film(filmId) ?: return null
            val next = withFilm(edit, catalog, filmId) ?: return null
            val paper = catalog.paper(paperId)
            val paired = if (paper != null && paper.bw == film.bw) next.copy(paper = paper.id) else next
            return catalog.normalize(paired.copy(noFilm = noFilm, digitalIntermediate = di,
                scanFilm = scan ?: paired.scanFilm))
        }

        const val NEW_NONE = "none"
        const val NEW_REMEMBER = "remember"
        const val NEW_FIXED = "fixed"

        /**
         * The stored 新照片 mode. Before 无 a fixed pair was a non-empty
         * default_film and "remember" the empty one, which cannot be told from
         * never having chosen: both read as 无, the new default.
         */
        fun newPhotoMode(prefs: android.content.SharedPreferences): String =
            prefs.getString("new_photo_mode", null)
                ?: if (prefs.getString("default_film", "").orEmpty().isNotEmpty()) NEW_FIXED else NEW_NONE

    }
}
