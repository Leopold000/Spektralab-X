package com.dearded.spektralab

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class FrameFilter(val label: String) {
    ALL("全部"), PICKS("选中"), REJECTS("淘汰"), EDITED("已编辑");

    fun keeps(f: Frame) = when (this) {
        ALL -> true
        PICKS -> f.pick > 0
        REJECTS -> f.pick < 0
        EDITED -> f.edited
    }
}

data class TableState(
    val filter: FrameFilter = FrameFilter.ALL,
    val columns: Int = 3,
    val selection: Set<String> = emptySet(),
    /** Photos still being read in by an import (0 = none running). */
    val importing: Int = 0,
    /** After a pick or a reject in the loupe, move on to the next frame. */
    val autoAdvance: Boolean = true,
    /** Grid thumbnails through each frame's own edit (developed when idle) rather than the photo as shot. */
    val filmThumbs: Boolean = false,
    /** What 复制调整 last took, for 粘贴 (null = nothing copied yet). */
    val copied: CopiedLook? = null,
    /** The groups 粘贴 carries, as last chosen. */
    val pasteGroups: Set<LookGroup> = LookGroup.entries.toSet() - LookGroup.BORDER,
    val message: String? = null,
)

/** The loupe's run of frames (by id, so a re-filtered grid does not move it) and where it opened. */
data class Loupe(val ids: List<String>, val start: Int)

/** Where the table is: the roll list, one roll, and a frame being compared through styles (all optional). */
data class TableNav(val rolls: Boolean = false, val roll: String? = null, val compare: String? = null)

/** The frame the editor has open for the light table, so its edits go back to that frame's sidecar. */
data class EditLink(val id: String, val name: String)

/**
 * The light table's state. Process-lifetime data (frames, sidecars) lives in
 * [FrameStore]; this holds the screen's choices and runs the work that must
 * not happen on the main thread.
 */
class LightTableViewModel(private val app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("light_table", 0)
    private val host = (app as SpektraLabApp).host
    val store = FrameStore.of(app)
    val frames: StateFlow<List<Frame>> = store.frames

    private val _state = MutableStateFlow(TableState(
        columns = prefs.getInt("columns", 3).takeIf { it in COLUMNS } ?: 3,
        filter = runCatching { FrameFilter.valueOf(prefs.getString("filter", "ALL")!!) }.getOrDefault(FrameFilter.ALL),
        autoAdvance = prefs.getBoolean("auto_advance", true),
        filmThumbs = prefs.getBoolean("film_thumbs", false),
        pasteGroups = prefs.getStringSet("paste_groups", null)
            ?.mapNotNull { n -> LookGroup.entries.firstOrNull { it.name == n } }?.toSet()
            ?: (LookGroup.entries.toSet() - LookGroup.BORDER),
    ))
    val state: StateFlow<TableState> = _state

    private val _editing = MutableStateFlow<EditLink?>(null)
    val editing: StateFlow<EditLink?> = _editing

    private val _loupe = MutableStateFlow<Loupe?>(null)
    val loupe: StateFlow<Loupe?> = _loupe

    private val _nav = MutableStateFlow(TableNav())
    val nav: StateFlow<TableNav> = _nav

    fun showRolls(on: Boolean) = _nav.update { it.copy(rolls = on, roll = if (on) it.roll else null) }
    fun openRoll(id: String?) = _nav.update { it.copy(rolls = true, roll = id) }
    fun compare(frame: Frame?) = _nav.update { it.copy(compare = frame?.id) }

    /** 手动装卷: the user's own rolls, which win over the automatic ones. */
    val manualRolls = ManualRolls.of(app)

    init {
        viewModelScope.launch(Dispatchers.IO) { store.load(); manualRolls.load() }
    }

    // --- manual rolls -------------------------------------------------------------

    /** 装成一卷: [frames] (in grid order) into a new roll called [name]; past 36 the rest start 卷 2. */
    fun packRoll(frames: List<Frame>, name: String) {
        if (frames.isEmpty()) return
        val label = name.trim().take(40).ifEmpty { "我的卷" }
        viewModelScope.launch {
            val made = withContext(Dispatchers.IO) { manualRolls.pack(frames.map { it.id }, label) }
            _state.update { it.copy(selection = emptySet()) }
            show(if (made > 1) "已装成 $made 卷「$label」（每卷最多 ${Roll.LENGTH} 张）" else "已装成一卷「$label」")
        }
    }

    /** 移到别的卷: [frames] onto the end of manual roll [to], as far as its 36 frames allow. */
    fun moveToRoll(frames: List<Frame>, to: String, toName: String) {
        if (frames.isEmpty()) return
        viewModelScope.launch {
            val moved = withContext(Dispatchers.IO) { manualRolls.move(frames.map { it.id }, to) }
            _state.update { it.copy(selection = emptySet()) }
            val left = frames.size - moved
            show(when {
                moved == 0 -> "「$toName」已满 ${Roll.LENGTH} 张，没有移动"
                left > 0 -> "已移 $moved 张到「$toName」，$left 张放不下，留在原卷"
                else -> "已移 $moved 张到「$toName」"
            })
        }
    }

    /** 移出手动卷: [frames] go back to their automatic film-and-day rolls. */
    fun releaseFromRolls(frames: List<Frame>) {
        if (frames.isEmpty()) return
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) { manualRolls.release(frames.map { it.id }) }
            _state.update { it.copy(selection = emptySet()) }
            show(if (n == 0) "这些照片不在手动卷里" else "已把 $n 张放回自动分卷")
        }
    }

    /** 拆卷: manual roll [id] is gone; its frames return to the automatic rolls. */
    fun dissolveRoll(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { manualRolls.dissolve(id) }
            _nav.update { if (it.roll == "m|$id") it.copy(roll = null) else it }
            show("已拆卷，照片回到自动分卷")
        }
    }

    fun renameRoll(id: String, name: String) {
        viewModelScope.launch(Dispatchers.IO) { manualRolls.rename(id, name) }
    }

    fun openLoupe(frames: List<Frame>, index: Int) {
        if (index !in frames.indices) return
        _loupe.value = Loupe(frames.map { it.id }, index)
    }

    /** The loupe moved to [index]; reopening it after the editor comes back there. */
    fun loupeAt(index: Int) = _loupe.update { it?.copy(start = index) }

    fun closeLoupe() { _loupe.value = null }

    fun visible(all: List<Frame>, filter: FrameFilter) = if (filter == FrameFilter.ALL) all else all.filter(filter::keeps)

    // --- import ---------------------------------------------------------------

    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _state.update { it.copy(importing = uris.size) }
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) {
                store.import(uris) { done -> _state.update { it.copy(importing = uris.size - done) } }
            }
            val again = uris.size - added
            show(when {
                added == 0 -> "这些照片已经在观片台上"
                again > 0 -> "已加入 $added 张，$again 张原本就在"
                else -> "已加入 $added 张"
            })
            _state.update { it.copy(importing = 0) }
        }
    }

    // --- view -------------------------------------------------------------------

    fun setColumns(n: Int) {
        if (n !in COLUMNS || n == _state.value.columns) return
        prefs.edit().putInt("columns", n).apply()
        _state.update { it.copy(columns = n) }
    }

    fun setFilter(f: FrameFilter) {
        prefs.edit().putString("filter", f.name).apply()
        _state.update { it.copy(filter = f) }
    }

    fun setAutoAdvance(on: Boolean) {
        prefs.edit().putBoolean("auto_advance", on).apply()
        _state.update { it.copy(autoAdvance = on) }
    }

    fun setFilmThumbs(on: Boolean) {
        prefs.edit().putBoolean("film_thumbs", on).apply()
        _state.update { it.copy(filmThumbs = on) }
    }

    // --- marks ------------------------------------------------------------------------

    /** Picks (1), rejects (-1) or clears (0) [ids], and/or sets their stars; null leaves that mark alone. */
    fun rate(ids: Set<String>, pick: Int? = null, stars: Int? = null) {
        if (ids.isEmpty() || (pick == null && stars == null)) return
        viewModelScope.launch(Dispatchers.IO) { store.rate(ids, pick?.coerceIn(-1, 1), stars) }
    }

    // --- selection ----------------------------------------------------------------

    fun toggle(id: String) = _state.update {
        it.copy(selection = if (id in it.selection) it.selection - id else it.selection + id)
    }

    /** A long-press drag: [base] (what was selected when it began) plus [ids]. */
    fun selectRange(base: Set<String>, ids: Collection<String>) = _state.update { it.copy(selection = base + ids) }

    fun selectAll(ids: Collection<String>) = _state.update { it.copy(selection = ids.toSet()) }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    /** Takes the selection off the table; sidecars stay, so a re-import brings the edits back. */
    fun removeSelected() {
        val ids = _state.value.selection
        if (ids.isEmpty()) return
        val inUse = host.queue.jobs.value.filter { it.state != JobState.DONE }.map { it.uri }.toSet()
        viewModelScope.launch(Dispatchers.IO) { store.remove(ids, inUse) }
        _state.update { it.copy(selection = emptySet()) }
        show("已从观片台移走 ${ids.size} 张（编辑记录保留）")
    }

    // --- editing --------------------------------------------------------------------

    /**
     * Opens [frame] in [editor] with its sidecar: the whole edit, geometry and
     * RAW white balance included. A frame without one opens the way any photo
     * does, carrying the editor's current look. False while the engine starts.
     */
    fun edit(frame: Frame, editor: EditorViewModel): Boolean {
        if (!editor.ui.value.ready) { show("引擎还在准备，稍等一下"); return false }
        _editing.value = EditLink(frame.id, frame.name)
        viewModelScope.launch {
            val car = withContext(Dispatchers.IO) { store.sidecar(frame.id) }
            val uri = Uri.parse(frame.uri)
            if (car != null) editor.openSaved(uri, car.edit, car.wbK, car.wbTint) else editor.open(uri)
        }
        return true
    }

    /** The editor opened another photo: its edits no longer belong to [link]'s frame. */
    fun unlink(link: EditLink) = _editing.compareAndSet(link, null)

    /** The editor has [link]'s frame open at [w] x [h] (its upright shape, which RAWs only tell once decoded). */
    fun opened(link: EditLink, w: Int, h: Int) {
        viewModelScope.launch(Dispatchers.IO) { store.setSize(link.id, w, h) }
    }

    fun save(link: EditLink, edit: Edit, wbK: Double, wbTint: Double) {
        viewModelScope.launch(Dispatchers.IO) { store.saveEdit(link.id, edit, wbK, wbTint) }
    }

    /** Writes [edit] (a style from 比较风格) as [frame]'s look, keeping its RAW white balance. */
    fun saveLook(frame: Frame, edit: Edit, label: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val car = store.sidecar(frame.id)
                store.saveEdit(frame.id, edit, car?.wbK ?: 0.0, car?.wbTint ?: 0.0)
            }
            show("已把「$label」用到「${frame.name}」")
        }
    }

    /**
     * 导出印样: [roll] as one contact-sheet JPEG in Pictures/Spektralab-X, at
     * most [maxPixels] (the editor's cap), as a job in 正在处理 (ProcessingQueue):
     * progress and time left there and in the notification, and a kill resumes it.
     */
    fun exportContactSheet(roll: Roll, maxPixels: Long) {
        host.queue.enqueue(RollContactSheet.job(roll, host.queue.nextId(), maxPixels))
        show("印样已加入“正在处理”，冲洗完缩略图后存进相册")
    }

    // --- batch ---------------------------------------------------------------------

    /** 复制调整: takes [frame]'s whole edit. A frame never edited has nothing to give. */
    fun copyLook(frame: Frame) {
        viewModelScope.launch {
            val car = withContext(Dispatchers.IO) { store.sidecar(frame.id) }
            if (car == null) { show("这张还没有编辑过，没有可复制的调整"); return@launch }
            _state.update { it.copy(copied = CopiedLook(car.edit, car.wbK, car.wbTint, frame.name)) }
            show("已复制「${frame.name}」的调整")
        }
    }

    fun setPasteGroups(groups: Set<LookGroup>) {
        prefs.edit().putStringSet("paste_groups", groups.map { it.name }.toSet()).apply()
        _state.update { it.copy(pasteGroups = groups) }
    }

    /**
     * 粘贴 onto [targets]: the chosen groups of the copied edit, written to
     * each frame's sidecar. Settings, not results: nothing is developed here,
     * and each photo meters itself when it is opened or exported.
     */
    fun paste(targets: List<Frame>, groups: Set<LookGroup>, catalog: Catalog) {
        val from = _state.value.copied ?: return
        if (targets.isEmpty() || groups.isEmpty()) return
        viewModelScope.launch {
            val skipped = withContext(Dispatchers.IO) {
                var skipped = 0
                for (f in targets) {
                    val base = store.sidecar(f.id) ?: Sidecar(Edit.NONE)
                    val p = pasteLook(base, f, from, groups, catalog)
                    if (p.edgeSkipped) skipped++
                    store.saveEdit(f.id, p.edit, p.wbK, p.wbTint)
                }
                skipped
            }
            show(if (skipped > 0) "已粘贴到 ${targets.size} 张；其中 $skipped 张尺寸未知，片边先没加，打开一次后再粘贴即可"
                 else "已粘贴到 ${targets.size} 张")
        }
    }

    /**
     * Queues [targets] for export through the 正在处理 queue, each with its
     * sidecar (a frame never edited goes with a fresh edit), at the editor's
     * working size and output choices. Each job opens its own session, so
     * the editor is not held up.
     */
    fun export(targets: List<Frame>, format: ExportFormat, size: ExportSize, editor: UiState) {
        if (targets.isEmpty()) return
        viewModelScope.launch {
            val cars = withContext(Dispatchers.IO) {
                targets.map { f -> f to withShootingData(f, store.sidecar(f.id) ?: Sidecar(Edit.NONE)) }
            }
            // One at a time: nextId() is unique only once the previous job is in the list.
            for ((f, car) in cars) host.queue.enqueue(jobFor(f, car, format, size, editor))
            val fresh = cars.count { (_, car) -> car.updated == 0L }
            show("已把 ${cars.size} 张加入“正在处理”" + if (fresh > 0) "（$fresh 张未编辑，按默认胶片冲洗）" else "")
        }
    }

    /**
     * IO. A frame never opened in the editor has no shooting data resolved:
     * a pasted date back (with its data line, or the data face) would print
     * blank. Its EXIF is read once here and fills only what is empty, so a
     * value the editor resolved, or a JPEG fill the user picked, stays.
     */
    private fun withShootingData(f: Frame, car: Sidecar): Sidecar {
        val e = car.edit
        val wantsData = e.dateWithData || e.dateStyle == "data"
        val missing = (e.dateImprint && e.dateText.isEmpty()) || (e.dateImprint && wantsData && e.dateDataText.isEmpty()) ||
            (e.filmEdge && e.edgeFNumber == 0.0)
        if (!missing) return car
        val exif = runCatching { ExifCopy.read(app, Uri.parse(f.uri)) }.getOrNull() ?: return car
        if (exif.isEmpty) return car
        val shot = Edit.withShooting(e, exif)
        return car.copy(edit = e.copy(
            dateText = e.dateText.ifEmpty { shot.dateText },
            dateDataText = e.dateDataText.ifEmpty { shot.dateDataText },
            edgeFNumber = if (e.edgeFNumber == 0.0) shot.edgeFNumber else e.edgeFNumber,
        ))
    }

    private fun jobFor(f: Frame, car: Sidecar, format: ExportFormat, size: ExportSize, ui: UiState): ProcessingJob {
        val edit = ui.catalog.normalize(car.edit)
        // The size it will be processed at: the working edge and pixel cap, as the editor opens it.
        val (w, h) = if (f.dimsKnown) f.width to f.height else ui.workingEdge to ui.workingEdge * 3 / 4
        val s = SourceLoader.fitScale(w, h, ui.workingEdge, ui.maxPixels)
        val ow = w * s
        val oh = h * s
        val long = maxOf(ow, oh)
        val px = (ow * oh).toLong()
        val outPixels = if (size == ExportSize.SHARE && long > SHARE_EDGE) (px * (SHARE_EDGE / long) * (SHARE_EDGE / long)).toLong() else px
        return ProcessingJob(
            id = host.queue.nextId(), uri = f.uri, name = f.name, raw = f.raw,
            editJson = edit.toJson().toString(), wbK = car.wbK, wbTint = car.wbTint,
            lookJson = if (edit.look.neutral) "" else edit.look.toJson().toString(),
            format = format, size = size, workingEdge = ui.workingEdge, maxPixels = ui.maxPixels,
            outPixels = outPixels,
            frameJson = if (edit.frame.isNone) "" else edit.frame.toJson().toString(),
            filmLabel = if (edit.noFilm) "" else ui.catalog.film(edit.film)?.label ?: "",
            edgeRealName = edit.edgeRealName,
            wideGamut = ui.wideGamut, highlightStops = ui.highlightStops,
        )
    }

    // --- messages -------------------------------------------------------------------

    fun show(text: String) = _state.update { it.copy(message = text) }
    fun consumeMessage() = _state.update { it.copy(message = null) }

    companion object {
        /** The grid's column counts, pinched through in this order. */
        val COLUMNS = listOf(2, 3, 4, 6)
        /** The engine's preview tier, the share size's long edge (as EditorViewModel). */
        private const val SHARE_EDGE = 3400.0
    }
}
