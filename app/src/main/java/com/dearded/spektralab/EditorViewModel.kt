package com.dearded.spektralab

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class ExportFormat { JPEG, TIFF, HEIF }

/** "full" is the working size; "preview" is the engine's 3400 px tier, for sharing. */
enum class ExportSize(val tier: String) { FULL("full"), SHARE("preview") }

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
    val loadingDetail: Boolean = false,
    /** The film and paper a new photo starts on; "" = the last photo's (remembered). */
    val defaultFilm: String = "",
    val defaultPaper: String = "",
    /** What a newly opened photo starts on: NEW_NONE (every stage 无), NEW_REMEMBER or NEW_FIXED. */
    val newPhotoMode: String = EditorViewModel.NEW_NONE,
) {
    val hasImage get() = source != null
}

class EngineException(message: String) : Exception(message)

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
        lastUri = uri
        launchOpen(uri, wb = null)
    }

    /** The photo the editor has open (or is opening), for the light table's link to it. */
    val openUri: Uri? get() = lastUri

    /**
     * Light table hook: opens [uri] with a saved [edit] kept whole, geometry
     * and RAW white balance included, the way a white-balance re-decode keeps
     * it. Undo starts over and the EXIF is read for the new photo.
     */
    fun openSaved(uri: Uri, edit: Edit, wbK: Double, wbTint: Double) {
        if (!_ui.value.ready || _ui.value.exporting) { open(uri); return }
        lastUri = uri
        val previous = openJob
        openJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            sourceExif = withContext(Dispatchers.IO) { ExifCopy.read(app, uri) }
            wanted = _ui.value.catalog.normalize(edit)
            // A light-table frame resets to the edit it was saved with.
            openedEdit = wanted
            clearHistory()
            doOpen(uri, wbK to wbTint)
        }
    }

    private var openJob: kotlinx.coroutines.Job? = null

    /**
     * Opens run one at a time: a newer one first cancels and waits for the
     * older. Two in flight used to leak a whole native session (each released
     * the old one, then both stored theirs) and could delete each other's
     * cached RAW.
     */
    private fun launchOpen(uri: Uri, wb: Pair<Double, Double>?) {
        clearOverride(render = false)
        val previous = openJob
        openJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            doOpen(uri, wb)
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

    private suspend fun doOpen(uri: Uri, wb: Pair<Double, Double>? = null) {
        _ui.update { it.copy(busy = "正在读取…", error = null) }
        try {
            val resolver = app.contentResolver
            // A query plus a 4-byte header read: a cloud provider can block for seconds.
            val (name, raw) = withContext(Dispatchers.IO) {
                if (wb == null) holdGrant(uri)
                val n = loader.displayName(uri)
                n to loader.isRaw(n, resolver.getType(uri), uri)
            }
            val edge = _ui.value.workingEdge
            // The film edge lays the frame on a canvas up to 1.6x its area, and the
            // canvas is one GPU buffer: on Adreno 7xx (Xiaomi 12-14) that buffer is
            // capped at 128 MiB. A re-decode with the edge on keeps the canvas inside.
            val maxPixels = _ui.value.maxPixels.let {
                if (wb != null && wanted.filmEdge && it > 0) it * 10 / 16 else it
            }
            // Rotation and crop belong to one photo; the look (film, paper, effects) carries over.
            // A white-balance re-decode is the same photo: everything is kept.
            // The film edge is turned off with the crop (the engine refuses a frame
            // that is not the gate's shape); the date back follows the new photo's EXIF.
            if (wb == null) {
                sourceExif = withContext(Dispatchers.IO) { ExifCopy.read(app, uri) }
            }
            // Every new frame is metered, a phone photo too: upstream meters JPEG
            // and RAW alike ("balanced", a log average that a bright sky barely moves).
            val carried = _ui.value.catalog.normalize(if (wb != null) wanted else wanted.copy(
                autoExposure = true,
                quarterTurns = 0, flipH = false,
                cropX = 0.0, cropY = 0.0, cropW = 1.0, cropH = 1.0, cropAspect = 0.0,
                filmEdge = false,
            ).let { Edit.withShooting(it, sourceExif) }.let { if (it.dateText.isEmpty()) it.copy(dateImprint = false) else it }
                // A border that crops or sets a film edge belongs to the last photo's
                // geometry; a pure paper frame (data bar, mat) carries over as a look.
                .let {
                    val p = Borders.byId(it.borderId)
                    if (wb == null && p != null && (p.edge != null || p.crop > 0)) it.copy(frame = FrameSpec(), borderId = "none") else it
                })
            // Upstream PR #53: the chosen pair only for a photo with no saved edit.
            val look = if (wb == null) withNewPhotoStocks(carried) else carried
            lastOpenedAny = true   // any open: [wanted] now holds this photo's pair
            val (wbK, wbTint) = wb ?: (0.0 to 0.0)
            if (wb == null) clearHistory()
            // Free the previous frame before decoding the next, so the two never
            // coexist in memory (a 20 MP open ran out of memory that way elsewhere).
            cardsJob?.cancel()
            withContext(EngineThread.dispatcher) { releaseSession() }
            _ui.update { it.copy(image = null, before = null, source = null, filmCards = emptyMap(), detail = null) }
            clearPool()
            val handle: Long
            if (raw) {
                // Assigned inside the IO hop: a cancel that lands as it returns
                // still leaves the descriptor (or copy) where finally closes it.
                var input: RawInput? = null
                handle = try {
                    withContext(Dispatchers.IO) { input = loader.rawInput(uri, name) }
                    val path = input!!.path
                    _ui.update { it.copy(busy = "正在解码 RAW…") }
                    withContext(EngineThread.dispatcher) {
                        releaseSession()
                        openSpace = "ProPhoto RGB"; openLinear = true
                        val params = Edit.openingParams(look, inputSpace = openSpace, linear = openLinear).toString()
                        val h = NativeEngine.openRaw(engine, path, edge, maxPixels, params, wbK, wbTint)
                        if (h == 0L) throw EngineException("打开失败：${NativeEngine.lastError()}")
                        // Owned now, in this same engine-thread hop: if the open is
                        // cancelled (back, a newer open) the next release frees it.
                        synchronized(cancelLock) { session = h }
                        h
                    }
                } finally {
                    input?.close()
                }
            } else {
                val photo = withContext(Dispatchers.IO) { loader.decodePhoto(uri, edge, maxPixels) }
                _ui.update { it.copy(busy = if (photo.hdr) "正在恢复 HDR 高光…" else "正在打开…") }
                handle = withContext(EngineThread.dispatcher) {
                    releaseSession()
                    val opened = openPhotoSession(engine, photo, _ui.value.highlightStops) { space, linear ->
                        Edit.openingParams(look, inputSpace = space, linear = linear).toString()
                    }
                    val h = opened.handle
                    openSpace = INPUT_SPACE; openLinear = true
                    if (opened.linear) log("ultra hdr gain map applied")
                    if (h == 0L) throw EngineException("打开失败：${NativeEngine.lastError()}")
                    synchronized(cancelLock) { session = h }
                    h
                }
            }
            val (edit, info, before) = withContext(EngineThread.dispatcher) {
                session = handle
                // The screen's space: Display P3 when the window is wide-gamut.
                if (!NativeEngine.setOutput(handle, DisplayColor.space, false)) log("display transform: ${NativeEngine.lastError()}")
                val params = NativeEngine.getParams(handle)?.let(::JSONObject) ?: JSONObject()
                val edit = Edit.fromParams(params, look)
                applied = edit
                appliedLook = Look.NEUTRAL
                wanted = edit
                if (wb == null) openedEdit = edit
                Triple(edit, JSONObject(NativeEngine.sourceInfo(handle)), NativeEngine.beforeBitmap(handle))
            }
            _ui.update {
                it.copy(
                    busy = null, edit = edit, image = null, imageTier = "", before = before, exif = sourceExif,
                    source = SourceInfo(
                        name = name,
                        width = info.optInt("width"), height = info.optInt("height"),
                        sourceWidth = info.optInt("sourceWidth"), sourceHeight = info.optInt("sourceHeight"),
                        kind = info.optString("kind"),
                        camera = listOf(info.optString("make"), info.optString("model"))
                            .filter { s -> s.isNotBlank() }.joinToString(" "),
                        asShotK = info.optDouble("asShotK", 0.0), asShotTint = info.optDouble("asShotTint", 0.0),
                        wbK = info.optDouble("wbK", 0.0), wbTint = info.optDouble("wbTint", 0.0),
                    ),
                )
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
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e   // superseded by a newer open, or the editor closed: not an error
        } catch (e: OutOfMemoryError) {
            log("open failed: out of memory")
            _ui.update { it.copy(busy = null, error = "内存不足，请在“设置”里把工作尺寸调小后重试") }
        } catch (e: Throwable) {
            log("open failed: ${e.message ?: e}")
            _ui.update { it.copy(busy = null, error = e.message ?: e.toString()) }
        }
    }

    private fun thermalHeadroom(): Float = host.thermal.headroom()

    private fun releaseSession() = synchronized(cancelLock) {
        if (session != 0L) NativeEngine.release(session)
        session = 0L
    }

    /**
     * A new edit makes the slow preview render in flight stale: stop it at its
     * next node so the live render for the new edit starts at once, instead of
     * after a whole full-resolution pass. Live renders are left to finish, or
     * a slider drag would never show anything.
     */
    private fun cancelStalePreview() = synchronized(cancelLock) {
        if (inFlightTier == "preview" && session != 0L) NativeEngine.cancel(session)
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

    private fun applyEdit(raw: Edit, record: Boolean, render: Boolean = true) {
        // The real edit renders below; after an override the engine holds the override's
        // params, so even a look-only change must develop again, not re-pack.
        val wasOverridden = clearOverride(render = false)
        val edit = _ui.value.catalog.normalize(raw)
        val current = _ui.value.edit
        // "记住上次用的" across a restart: a new process would start on 无 otherwise.
        if (edit.film != current.film || edit.paper != current.paper || edit.noFilm != current.noFilm ||
            edit.digitalIntermediate != current.digitalIntermediate)
            prefs.edit().putString("last_film", edit.film).putString("last_paper", edit.paper)
                .putBoolean("last_no_film", edit.noFilm).putBoolean("last_di", edit.digitalIntermediate).apply()
        if (record) history.record(current, edit, android.os.SystemClock.uptimeMillis())
        wanted = edit
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
        val lookOnly = !wasOverridden && edit.copy(look = current.look) == current && edit.look != current.look
        if (lookOnly && _ui.value.image != null) { if (render) repacks.trySend(Unit); return }
        cancelStalePreview()
        if (render) renders.trySend(Unit)
    }

    /**
     * Renders the full working size once for 1:1 viewing. Refused above
     * kMaxDetailPixels, where the ARGB bitmap alone would pass ~64 MB.
     */
    fun loadDetail() {
        val state = _ui.value
        val src = state.source ?: return
        if (state.loadingDetail || state.detail != null || state.exporting) return
        if (src.width.toLong() * src.height > kMaxDetailPixels) {
            _ui.update { it.copy(message = "工作尺寸超过 1600 万像素，1:1 查看已关闭；导出不受影响") }
            return
        }
        val edit = state.edit
        viewModelScope.launch {
            _ui.update { it.copy(loadingDetail = true) }
            try {
                val bitmap = withContext(EngineThread.dispatcher) {
                    applyWanted()?.let { throw EngineException("参数无效：$it") }
                    NativeEngine.renderBitmap(session, "full")?.also(DisplayColor::tag)
                        ?: throw EngineException("渲染失败：${NativeEngine.lastError()}")
                }
                // An edit that landed while this rendered makes it stale.
                _ui.update { if (it.edit == edit) it.copy(detail = bitmap) else it }
                log("detail ${bitmap.width}x${bitmap.height}")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                _ui.update { it.copy(message = "内存不足，无法 1:1 查看") }
            } catch (e: Throwable) {
                _ui.update { it.copy(message = e.message ?: e.toString()) }
            } finally {
                _ui.update { it.copy(loadingDetail = false) }
            }
        }
    }

    fun closeDetail() = _ui.update { it.copy(detail = null) }

    fun undo() {
        applyEdit(history.undo(_ui.value.edit) ?: return, record = false)
    }

    fun redo() {
        applyEdit(history.redo(_ui.value.edit) ?: return, record = false)
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

    private fun clearHistory() {
        history.clear()
        _ui.update { it.copy(canUndo = false, canRedo = false) }
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
        cancelStalePreview()
        renders.trySend(Unit)
    }

    /** Drops the override (a real edit, an open, an export): the screen goes back to the current edit. */
    private fun clearOverride(render: Boolean): Boolean {
        if (override == null) return false
        override = null
        if (render) renders.trySend(Unit)
        return true
    }

    private fun applyWanted(): String? {
        val target = override ?: wanted
        applyLook(target.look)
        if (target == applied) return null
        val delta = target.delta(applied)
        if (delta.length() == 0) { applied = target; return null }
        if (NativeEngine.setParams(session, delta.toString()) == null) {
            if (target === override) override = null else rejected = target
            return NativeEngine.lastError()
        }
        applied = target
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
            val tier = _ui.value.imageTier
            if (tier.isEmpty()) continue
            val out = try {
                withContext(EngineThread.dispatcher) {
                    if (session == 0L) return@withContext null
                    applyLook((override ?: wanted).look)
                    val reuse = synchronized(pool) { spare.remove(tier) }?.takeIf { !it.isRecycled }
                    val bitmap = NativeEngine.repack(session, reuse)?.also(DisplayColor::tag)
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
        val out = NativeEngine.renderReuse(session, tier, reuse)?.also(DisplayColor::tag)
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
        _ui.update { it.copy(rendering = true) }
        var failure: String? = null
        var reverted: Edit? = null
        var bitmap: Bitmap? = null
        try {
            val started = android.os.SystemClock.elapsedRealtime()
            bitmap = withContext(EngineThread.dispatcher) {
                if (session == 0L) return@withContext null
                applyWanted()?.let { error ->
                    failure = "参数无效：$error"
                    // Only if the user has not asked for something newer meanwhile.
                    if (wanted == rejected) { wanted = applied; reverted = applied }
                    return@withContext null
                }
                inFlightTier = tier
                try {
                    renderPooled(tier) ?: run {
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
            val shown = bitmap
            // Outside update: its lambda may run more than once.
            if (shown != null) retire(tier, shown)
            _ui.update {
                var next = it.copy(rendering = false)
                if (shown != null) next = next.copy(image = shown, imageTier = tier)
                reverted?.let { e -> next = next.copy(edit = e) }
                failure?.let { f -> next = next.copy(message = f) }
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
    fun export(format: ExportFormat, size: ExportSize = ExportSize.FULL) {
        clearOverride(render = true)
        val state = _ui.value
        val src = state.source ?: return
        val uri = lastUri ?: return
        val px = src.width.toLong() * src.height
        val outPixels = if (size == ExportSize.SHARE) {
            val long = max(src.width, src.height).toDouble()
            if (long <= SHARE_EDGE) px else (px * (SHARE_EDGE / long) * (SHARE_EDGE / long)).toLong()
        } else px
        val job = ProcessingJob(
            id = host.queue.nextId(), uri = uri.toString(), name = src.name, raw = src.kind == "raw",
            editJson = state.edit.toJson().toString(), wbK = src.wbK, wbTint = src.wbTint,
            lookJson = if (state.edit.look.neutral) "" else state.edit.look.toJson().toString(),
            format = format, size = size, workingEdge = state.workingEdge, maxPixels = state.maxPixels,
            outPixels = outPixels,
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
            frameJson = if (state.edit.frame.isNone) "" else state.edit.frame.toJson().toString(),
            filmLabel = if (state.edit.noFilm) "" else state.catalog.film(state.edit.film)?.label ?: "",
            edgeRealName = state.edit.edgeRealName,
            wideGamut = state.wideGamut,
            highlightStops = state.highlightStops,
            extreme = true,
        )
        host.queue.enqueue(job)
        log("queued ${job.label} ${sw}x$sh")
        _ui.update { it.copy(message = "已加入“正在处理”：全分辨率导出较慢，可以继续编辑或切到后台") }
    }

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
            val src = _ui.value.source ?: continue   // mid-open: the old frame is gone, the new not yet there
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
        val wantFilm = if (remember) prefs.getString("last_film", "").orEmpty() else _ui.value.defaultFilm
        val wantPaper = if (remember) prefs.getString("last_paper", "").orEmpty() else _ui.value.defaultPaper
        val film = catalog.film(wantFilm) ?: return e
        val next = withFilm(e, catalog, film.id) ?: return e
        val paper = catalog.paper(wantPaper)
        val paired = if (paper != null && paper.bw == film.bw) catalog.normalize(next.copy(paper = paper.id)) else next
        // A fixed pair is a film and a paper; remembering also brings back a last 无.
        return if (!remember) paired
               else catalog.normalize(paired.copy(noFilm = prefs.getBoolean("last_no_film", false),
                   digitalIntermediate = prefs.getBoolean("last_di", false)))
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

    /**
     * The system is short of memory (onTrimMemory): give back the GPU buffer
     * pool's idle planes and the detail view. The session and its cached
     * negative stay, so the photo does not have to be re-opened.
     */
    fun trimMemory(level: Int) {
        if (level < android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW &&
            level != android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return
        _ui.update { it.copy(detail = null) }
        synchronized(pool) { spare.clear() }
        val e = engine
        if (e == 0L) return
        viewModelScope.launch {
            val freed = withContext(EngineThread.dispatcher) { NativeEngine.trimMemory(e) }
            log("trim memory level $level: ${freed shr 20} MB freed")
        }
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
        // is released only after a running export (which renders it) is done.
        cardsJob?.cancel()
        host.scope.launch { withContext(EngineThread.dispatcher) { releaseSession() } }
    }

    companion object {
        /** Edits closer together than this are one undo step (a slider drag). */
        private const val kSettleMs = 700L
        private const val kMaxUndo = 50
        private const val kHotHeadroom = 0.85f
        private const val kMaxDetailPixels = 16_000_000L
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
