package com.dearded.spektralab

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

enum class JobState { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

/** The pre-save check found a frame no photo produces ([what]: 全黑, 全白, 纯色); nothing was saved. */
class BlankFrameException(val what: String) : Exception(ErrorCodes.format(ErrorCodes.EXPORT_BLANK_FRAME, "导出画面异常（$what），未保存"))

/** The engine could not allocate (native heap or GPU memory); retried a size down like a Java OutOfMemoryError. */
class OutOfMemoryException(detail: String) : Exception(ErrorCodes.format(ErrorCodes.OUT_OF_MEMORY, "内存不足：$detail"))

/**
 * One export: a snapshot of the photo and its whole edit, so the editor is
 * free to move on (another photo, more edits) while this one is developed.
 */
data class ProcessingJob(
    val id: Long,
    val uri: String,
    val name: String,
    val raw: Boolean,
    val editJson: String,
    val wbK: Double,
    val wbTint: Double,
    val format: ExportFormat,
    val size: ExportSize,
    /** TIFF 导出的色彩空间（仅 format == TIFF 时有效，默认 sRGB 保持向后兼容） */
    val tiffColorSpace: TiffColorSpace = TiffColorSpace.SRGB,
    val workingEdge: Int,
    val maxPixels: Long,
    /** The output's pixel count, for the time estimate. */
    val outPixels: Long,
    /** The paper frame (FrameSpec JSON, "" = none) and the film's name for its caption. */
    val frameJson: String = "",
    val filmLabel: String = "",
    /**
     * Whether the film edge prints the stock's real name (UI.md E.1). The toggle
     * is app-side, so the engine params cannot carry it; the job must.
     */
    val edgeRealName: Boolean = true,
    /** A JPEG rendered and tagged in Display P3 rather than sRGB. */
    val wideGamut: Boolean = false,
    /** Highlight recovery the editor opened the phone photo with. */
    val highlightStops: Float = 0f,
    /** Layer 2 (Look JSON, "" = none), applied while the export is packed. */
    val lookJson: String = "",
    val state: JobState = JobState.QUEUED,
    val progress: Float = 0f,
    val phase: String = "",
    /** Seconds left for this job; -1 when unknown. */
    val etaSeconds: Int = -1,
    val message: String = "",
    val attempts: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val finishedAt: Long = 0,
    /** The source copied into app storage because [uri]'s grant could not be kept ("" = read [uri]). */
    val sourcePath: String = "",
    /** A persistable read grant on [uri] is held for this job. */
    val persisted: Boolean = false,
    /** False while the grant or copy is being made; the worker skips the job until then. */
    val secured: Boolean = true,
    /** The pending gallery entry being written, so a kill mid-save is cleaned up or found saved. */
    val pendingUri: String = "",
    /** A user cancellation survives a kill before the runner can reconcile its entry. */
    val cancelledByUser: Boolean = false,
    /** The source's GPS tags ([ExifLocation.encode]), read at enqueue when 导出保留拍摄位置 is on; "" = none. */
    val location: String = "",
    /**
     * 极致渲染: the source's own resolution (workingEdge 0, only the device's
     * pixel cap applies), the whole-frame full tier, JPEG at quality 98, and
     * pauses while the phone is near throttling. Device loss and the frame
     * check step it down as any job.
     */
    val extreme: Boolean = false,
    /**
     * 导出印样: the roll as [RollContactSheet.snapshot] wrote it ("" = a photo
     * export) and how many frames it lays out, for the estimate. Such a job
     * has no source to secure and no engine session of its own.
     */
    val sheetJson: String = "",
    val sheetFrames: Int = 0,
    /** Known opened working-source area, before SHARE or framing; 0 = unknown/legacy. */
    val workingPixels: Long = 0,
) {
    val active get() = state == JobState.QUEUED || state == JobState.RUNNING
    val sheet get() = sheetJson.isNotEmpty()

    val label get() = when {
        sheet -> "印样 · $name"
        extreme -> "高质量导出"
        format == ExportFormat.TIFF -> "16 位 TIFF · ${tiffColorSpace.displayName}"
        format == ExportFormat.HEIF -> "HEIF · 工作尺寸"
        size == ExportSize.SHARE -> "JPEG · 分享尺寸"
        else -> "JPEG · 工作尺寸"
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("uri", uri).put("name", name).put("raw", raw).put("edit", editJson)
        .put("wbK", wbK).put("wbTint", wbTint).put("format", format.name).put("size", size.name)
        .put("tiffSpace", tiffColorSpace.name)
        .put("edge", workingEdge).put("maxPixels", maxPixels).put("outPixels", outPixels)
        .put("frame", frameJson).put("filmLabel", filmLabel).put("realName", edgeRealName).put("wide", wideGamut).put("hl", highlightStops.toDouble()).put("look", lookJson)
        .put("state", state.name).put("message", message).put("attempts", attempts)
        .put("createdAt", createdAt).put("finishedAt", finishedAt)
        .put("src", sourcePath).put("persisted", persisted).put("secured", secured).put("pending", pendingUri)
        .put("cancelledByUser", cancelledByUser)
        .put("location", location).put("extreme", extreme)
        .put("sheet", sheetJson).put("sheetFrames", sheetFrames).put("workingPixels", workingPixels)

    companion object {
        fun fromJson(o: JSONObject): ProcessingJob? = runCatching {
            ProcessingJob(
                id = o.getLong("id"), uri = o.getString("uri"), name = o.optString("name"),
                raw = o.optBoolean("raw"), editJson = o.getString("edit"),
                wbK = o.optDouble("wbK", 0.0), wbTint = o.optDouble("wbTint", 0.0),
                format = ExportFormat.valueOf(o.getString("format")), size = ExportSize.valueOf(o.getString("size")),
                tiffColorSpace = runCatching { TiffColorSpace.valueOf(o.optString("tiffSpace", "SRGB")) }.getOrDefault(TiffColorSpace.SRGB),
                workingEdge = o.optInt("edge"), maxPixels = o.optLong("maxPixels"), outPixels = o.optLong("outPixels"),
                frameJson = o.optString("frame"), filmLabel = o.optString("filmLabel"),
                edgeRealName = o.optBoolean("realName", true),
                wideGamut = o.optBoolean("wide"),
                highlightStops = o.optDouble("hl", 0.0).toFloat(),
                lookJson = o.optString("look"),
                state = JobState.valueOf(o.optString("state", "QUEUED")), message = o.optString("message"),
                attempts = o.optInt("attempts"), createdAt = o.optLong("createdAt"), finishedAt = o.optLong("finishedAt"),
                sourcePath = o.optString("src"), persisted = o.optBoolean("persisted"),
                secured = o.optBoolean("secured", true), pendingUri = o.optString("pending"),
                cancelledByUser = o.optBoolean("cancelledByUser", false),
                location = o.optString("location"), extreme = o.optBoolean("extreme"),
                sheetJson = o.optString("sheet"), sheetFrames = o.optInt("sheetFrames"),
                workingPixels = o.optLong("workingPixels"),
            )
        }.getOrNull()
    }
}

/**
 * The 正在处理 list: exports run here, one at a time, in the process scope,
 * behind a foreground service that keeps HyperOS from killing the process.
 *
 * - Each job opens its own engine session from the source, so it never
 *   depends on what the editor has open. At enqueue the source is secured:
 *   a persistable grant where the URI allows one, else a copy in app storage,
 *   so a job resumed in a new process can still read it.
 * - The list is persisted; a job the system killed resumes the next time the
 *   app starts, and a job killed twice is marked failed rather than retried
 *   forever (that is usually memory pressure). After a memory kill the retry
 *   decodes one size smaller.
 * - A lost GPU device (VkResult -4) gets a new engine and one retry a size down.
 * - Files reach the gallery only checked and whole (MediaSaver); what a kill
 *   left half-written is removed when the queue next starts.
 * - The foreground service's time limit (Android 15+) and a refused start
 *   pause the queue; it continues when one of our activities is visible.
 * - The editor comes first: before each engine call the worker waits until
 *   the user has not touched a control for a moment, so a drag is never stuck
 *   behind a full-size render.
 * - The time estimate is learned: seconds per output megapixel, per format,
 *   averaged over this phone's finished jobs.
 */
class ProcessingQueue(private val app: Application, private val host: EngineHost) {
    private val prefs = app.getSharedPreferences("processing", 0)
    private val loader = SourceLoader(app)
    // Declared before _jobs: load() fills them, and a later initializer would wipe them.
    private var noticed = HashSet<Long>()
    private var interrupted = emptySet<Long>()
    private val _jobs = MutableStateFlow(load())
    val jobs: StateFlow<List<ProcessingJob>> = _jobs

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null
    private var runner: kotlinx.coroutines.Deferred<String>? = null
    private val handleLock = Any()
    private val galleryLock = Any()
    private val grantLock = SourceGrantLock.monitor
    @Volatile private var runningHandle = 0L
    @Volatile private var cancelRequested = -1L
    @Volatile private var pauseRequested = -1L
    private var notifiedAt = 0L
    // Main thread, like the loop that reads them.
    private var serviceOn = false
    private var paused = false
    private var swept = false

    private val sourcesDir = File(app.filesDir, "job-sources")
    private val exportDir = File(app.cacheDir, "export")

    /** Starts (or wakes) the worker. */
    fun start() {
        if (worker?.isActive != true) worker = host.scope.launch { loop() }
        wake.trySend(Unit)
    }

    fun enqueue(job: ProcessingJob) {
        // A contact sheet reads the light table's own frames (FrameStore holds their grants).
        synchronized(grantLock) {
            _jobs.update { it + job.copy(secured = job.sheet) }
            persist()
        }
        if (job.sheet) {
            start()
            return
        }
        host.scope.launch {
            val secured = withContext(Dispatchers.IO) {
                // From the URI as the user picked it, while its grant is fresh: a
                // copy in app storage would carry the GPS tags MediaStore redacted.
                val location = if (LocationSetting.enabled(app)) ExifLocation.encode(ExifCopy.readLocation(app, Uri.parse(job.uri))) else ""
                secure(job).copy(location = location)
            }
            set(job.id) { it.copy(sourcePath = secured.sourcePath, persisted = secured.persisted, location = secured.location, secured = true) }
            persist()
            start()
        }
    }

    fun cancel(id: Long) {
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        when (job.state) {
            JobState.QUEUED -> {
                set(id) { it.copy(state = JobState.CANCELLED, cancelledByUser = true, finishedAt = now(), etaSeconds = 0) }
                persist(sync = true)
                if (job.pendingUri.isNotEmpty()) host.scope.launch {
                    finishUnsuccessful(id, JobState.CANCELLED, "已取消", expectedPendingUri = job.pendingUri)
                    persist()
                }
                releaseSourcesLater()
            }
            JobState.RUNNING -> {
                cancelRequested = id
                set(id) { it.copy(cancelledByUser = true) }
                persist(sync = true)
                synchronized(handleLock) { if (runningHandle != 0L) NativeEngine.cancel(runningHandle) }
                runner?.cancel()
            }
            else -> Unit
        }
        refreshNotification(force = true)
    }

    fun retry(id: Long) {
        val job = _jobs.value.firstOrNull { it.id == id } ?: return
        if (job.active) return
        if (job.pendingUri.isNotEmpty()) {
            set(id) { it.copy(state = JobState.QUEUED, progress = 0f, phase = "", message = "",
                attempts = 0, etaSeconds = -1, finishedAt = 0, cancelledByUser = false) }
            persist()
            start()
            return
        }
        enqueue(job.copy(id = nextId(), state = JobState.QUEUED, progress = 0f, phase = "", message = "",
            attempts = 0, etaSeconds = -1, createdAt = now(), finishedAt = 0, pendingUri = "", cancelledByUser = false))
    }

    fun clearFinished() {
        // A failed status can still own a gallery transaction awaiting confirmation.
        _jobs.update { list -> list.filter { it.active || it.pendingUri.isNotEmpty() } }
        persist()
        releaseSourcesLater()
    }

    fun nextId(): Long = max(System.currentTimeMillis(), (_jobs.value.maxOfOrNull { it.id } ?: 0L) + 1)

    /**
     * Main thread. The foreground service hit its time limit or could not
     * become foreground: the running job goes back in line (not counted as an
     * attempt), the list is written synchronously, and nothing more runs until
     * [onAppForeground]. [serviceStopped]: the service stops itself (a timeout).
     */
    fun pause(why: String, serviceStopped: Boolean) {
        if (serviceStopped) serviceOn = false
        if (paused) return
        paused = true
        host.events.add("queue paused: $why")
        // M6 hook: a refused foreground start is what the battery / autostart hint helps with (not the time limit).
        if ("refused" in why) KeepAlive.flag(app, why, host.events)
        _jobs.value.firstOrNull { it.state == JobState.RUNNING }?.let { r ->
            pauseRequested = r.id
            set(r.id) { requeued(it) }
            synchronized(handleLock) { if (runningHandle != 0L) NativeEngine.cancel(runningHandle) }
            runner?.cancel()
        }
        persist(sync = true)
        JobNotifier.cancel(app, JobNotifier.ID)
        val waiting = _jobs.value.count { it.active }
        if (waiting > 0) JobNotifier.post(app, JobNotifier.PAUSED_ID, JobNotifier.paused(app, waiting))
        wake.trySend(Unit)
    }

    /** Main thread: one of our activities is visible, so a foreground service may start again. */
    fun onAppForeground() {
        if (paused) {
            paused = false
            JobNotifier.cancel(app, JobNotifier.PAUSED_ID)
            host.events.add("queue resumed")
        }
        if (_jobs.value.any { it.active }) start()
    }

    // --- worker ---------------------------------------------------------------

    private suspend fun loop() {
        if (!swept) {
            swept = true
            host.diagnosed.await()
            withContext(Dispatchers.IO) { recoverInterrupted(); sweepFiles() }
        }
        while (true) {
            val next = if (paused) null else _jobs.value.firstOrNull { it.state == JobState.QUEUED && it.secured }
            if (next == null) {
                if (serviceOn) { ExportService.stop(app); serviceOn = false }
                if (!paused) finishedNotice()
                wake.receive()
                continue
            }
            // One foreground period per busy stretch, however many jobs it holds.
            if (!serviceOn) {
                // Android 12+ refuses one while the app is in the background (a
                // process the system started for something else): wait for an
                // activity (onAppForeground) rather than run unprotected.
                if (!ExportService.start(app)) { pause("foreground service refused", serviceStopped = true); continue }
                serviceOn = true
            }
            runOne(next)
        }
    }

    private suspend fun runOne(queued: ProcessingJob) {
        val id = queued.id
        if (queued.pendingUri.isNotEmpty() && withContext(Dispatchers.IO) { settle(queued) }) { persist(); return }
        if (_jobs.value.firstOrNull { it.id == id }?.cancelledByUser == true) {
            set(id) { it.copy(state = JobState.CANCELLED, etaSeconds = 0, finishedAt = now(), message = "已取消") }
            persist()
            return
        }
        // Cancellation may have arrived while the provider was being queried.
        if (_jobs.value.firstOrNull { it.id == id }?.state != JobState.QUEUED) return
        if (queued.attempts >= 2) {
            set(id) { it.copy(state = JobState.FAILED, finishedAt = now(), etaSeconds = 0,
                message = "处理时两次被系统中断，多半是内存不足；请调小工作尺寸后重试") }
            persist()
            return
        }
        val started = SystemClock.elapsedRealtime()
        val estimate = estimateSeconds(queued)
        set(id) { it.copy(state = JobState.RUNNING, attempts = it.attempts + 1, progress = 0.02f, phase = "准备", etaSeconds = estimate) }
        persist(sync = true)   // a kill from here on counts as an attempt
        val ticker = host.scope.launch {
            while (isActive) {
                tick(id, started, estimate)
                delay(1000)
            }
        }
        cancelRequested = -1L
        pauseRequested = -1L
        var peakDiagnostic = "peak RSS not sampled (waiting)"
        try {
            val deferred = host.scope.async {
                runRecovering(queued, sampled = { peakDiagnostic = it }) { phase, frac ->
                    set(id) { it.copy(phase = phase, progress = max(it.progress, frac)) }
                }
            }
            runner = deferred
            val saved = deferred.await()
            learn(queued, (SystemClock.elapsedRealtime() - started) / 1000.0)
            set(id) { it.copy(state = JobState.DONE, progress = 1f, phase = "完成", etaSeconds = 0, finishedAt = now(), message = saved, pendingUri = "") }
            host.events.add("job $id done in ${(SystemClock.elapsedRealtime() - started) / 1000}s, $peakDiagnostic")
        } catch (e: CancellationException) {
            when {
                // pause() already put it back in line and wrote the list.
                pauseRequested == id -> Unit
                cancelRequested == id -> finishUnsuccessful(id, JobState.CANCELLED, "已取消")
                else -> throw e
            }
        } catch (e: OutOfMemoryError) {
            finishUnsuccessful(id, JobState.FAILED, "内存不足；请调小工作尺寸后重试")
            host.events.add("job $id failed: out of memory, $peakDiagnostic")
        } catch (e: Throwable) {
            finishUnsuccessful(id, JobState.FAILED, failureMessage(e))
            host.events.add("job $id failed: ${e.message ?: e}, $peakDiagnostic")
        } finally {
            runner = null
            ticker.cancel()
            persist()
            releaseSourcesLater()
            refreshNotification(force = true)
        }
    }

    private suspend fun finishUnsuccessful(id: Long, state: JobState, message: String, expectedPendingUri: String? = null) {
        withContext(NonCancellable + Dispatchers.IO) {
            synchronized(galleryLock) {
                val job = _jobs.value.firstOrNull { it.id == id } ?: return@synchronized
                // A delayed queued cancellation owns only its original gallery ticket.
                if (expectedPendingUri != null && job.pendingUri != expectedPendingUri) return@synchronized
                if (job.pendingUri.isNotEmpty() && settle(job)) return@synchronized
                set(id) {
                    if (it.state != job.state || (state == JobState.CANCELLED && !it.cancelledByUser)) it
                    else it.copy(state = state, etaSeconds = 0, finishedAt = now(), message = message, pendingUri = "")
                }
            }
        }
    }

    /**
     * A paused job back in line: the interruption was the system's, not the
     * job's. Its pending entry stays recorded: a pause that landed just after
     * publishing is found saved by the next run ([settle]).
     */
    private fun requeued(job: ProcessingJob) = job.copy(state = JobState.QUEUED, attempts = max(0, job.attempts - 1),
        progress = 0f, phase = "", etaSeconds = -1, message = "")

    private fun failureMessage(e: Throwable): String = when (e) {
        is SecurityException -> "无法再读取原图（访问权限已失效）；请重新打开照片后导出"
        else -> e.message ?: e.toString()
    }

    /**
     * zhaoye's recoverEngine(): nothing on a lost VkDevice works again, so the
     * engine is replaced and the job tried once more a size down (the GPU
     * watchdog fires on the longest submits, which a smaller frame shortens).
     */
    private suspend fun runRecovering(
        queued: ProcessingJob,
        sampled: (String) -> Unit,
        step: (String, Float) -> Unit,
    ): String {
        if (queued.sheet) {
            val rssBefore = ProcMemory.statusKb("VmRSS")
            val peakReset = ProcMemory.resetPeak()
            try {
                return processSheet(queued, step)
            } finally {
                // Sheets may develop small thumbnails; they are not working-source samples.
                sampled(peakText(rssBefore, peakReset))
            }
        }
        return host.transientSessions.withLease(onWaiting = { step("等待当前处理", 0.02f) }) {
            // Waiting candidates must finish before the export's learning window starts.
            val rssBefore = ProcMemory.statusKb("VmRSS")
            val peakReset = ProcMemory.resetPeak()
            try {
                val saved = runAdmitted(queued, step)
                // fitToMemory or recovery may have shrunk the job's working source.
                learnPeak(_jobs.value.firstOrNull { it.id == queued.id } ?: queued, rssBefore, peakReset)
                saved
            } finally {
                // Capture while still admitted, before the next candidate can allocate.
                sampled(peakText(rssBefore, peakReset))
            }
        }
    }

    // Admission and retries share the lease until process has released its native session.
    private suspend fun runAdmitted(queued: ProcessingJob, step: (String, Float) -> Unit): String {
        val job = fitToMemory(queued)
        try {
            return process(job, step)
        } catch (e: Throwable) {
            if (!recoverable(e)) throw e
            val pending = _jobs.value.firstOrNull { it.id == job.id }
            if (pending != null && pending.pendingUri.isNotEmpty() &&
                withContext(NonCancellable + Dispatchers.IO) { settle(pending) }) {
                val resolved = _jobs.value.first { it.id == job.id }
                if (resolved.state == JobState.DONE) return resolved.message
                throw EngineException(resolved.message)
            }
            host.events.add("job ${job.id}: ${e.message ?: e}")
            when (e) {
                is DeviceLostException -> {
                    step("GPU 被重置，正在重试", 0.05f)
                    host.recover(e.engine)
                }
                // A blank frame is a render that went wrong without saying so;
                // the same smaller retry, on the same engine.
                is BlankFrameException -> step("画面异常，正在重试", 0.05f)
                // Java heap or native allocation: what the failed attempt held is
                // released by now (process's finally), and the retry trims the
                // GPU pool before it opens.
                else -> step("内存不足，降档重试", 0.05f)
            }
            val smaller = smaller(job)
            set(job.id) { it.copy(workingEdge = smaller.workingEdge, maxPixels = smaller.maxPixels) }
            persist()
            host.events.add("job ${job.id}: retrying at edge ${smaller.workingEdge}, ${smaller.maxPixels / 1_000_000} MP")
            try {
                return process(smaller, step)
            } catch (again: DeviceLostException) {
                host.events.add("job ${job.id}: device lost again: ${again.message}")
                host.recover(again.engine)   // the editor still needs a working engine
                throw EngineException("GPU 连续两次被系统重置；请调小工作尺寸后重试")
            } catch (again: BlankFrameException) {
                host.events.add("job ${job.id}: blank again: ${again.message}")
                throw EngineException("导出画面两次异常（${again.what}），未保存；请调小工作尺寸后重试")
            } catch (again: Throwable) {
                if (!isOutOfMemory(again)) throw again
                host.events.add("job ${job.id}: out of memory again: ${again.message ?: again}")
                throw EngineException("内存不足，降档后仍失败；请调小工作尺寸后重试")
            }
        }
    }

    private fun recoverable(e: Throwable) = e is DeviceLostException || e is BlankFrameException || isOutOfMemory(e)

    private fun isOutOfMemory(e: Throwable) = e is OutOfMemoryError || e is OutOfMemoryException

    // --- memory tiers -----------------------------------------------------------

    /**
     * Queue admission heuristic, not an Android per-process allocation limit:
     * a share of the RAM by tier, and never more than what
     * is in use now plus what is free above the low-memory threshold, minus a
     * reserve for the editor and the system.
     */
    private fun memoryBudgetBytes(rssNowBytes: Long): Long {
        val am = app.getSystemService(android.app.ActivityManager::class.java) ?: return Long.MAX_VALUE
        val info = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val gib = 1L shl 30
        val share = when {
            info.totalMem <= 6 * gib -> 0.35
            info.totalMem <= 8 * gib -> 0.40
            info.totalMem <= 12 * gib -> 0.45
            else -> 0.50
        }
        val byTier = (info.totalMem * share).toLong()
        val byFree = rssNowBytes + info.availMem - info.threshold - MEMORY_RESERVE
        return minOf(byTier, byFree)
    }

    /** Peak RSS per working-source pixel. Old output-based samples are not reused. */
    private fun peakBytesPerPixel(): Double = prefs.getFloat("working_peak_bpp", DEFAULT_PEAK_BPP).toDouble()

    /**
     * Shrinks [job]'s pixel cap when its predicted peak (RSS now plus pixels
     * times the learned bytes per pixel) would not fit [memoryBudgetBytes].
     * Never below [MIN_TIER_PIXELS]: a job that small runs, and fails
     * honestly if it must.
     */
    private fun fitToMemory(job: ProcessingJob): ProcessingJob {
        val rssKb = ProcMemory.statusKb("VmRSS")
        if (rssKb < 0) return job
        val rss = rssKb * 1024
        val pixels = workingPixels(job)
        val bpp = peakBytesPerPixel()
        val budget = memoryBudgetBytes(rss)
        val fit = exportMemoryPixelCap(pixels, job.maxPixels, budget - rss, bpp, MIN_TIER_PIXELS)
        if (fit == job.maxPixels) return job
        host.events.add("job ${job.id}: memory tier ${budget shr 20} MB, ${pixels / 1_000_000} MP -> ${fit / 1_000_000} MP " +
            "(${"%.0f".format(Locale.ROOT, bpp)} B/px)")
        val smaller = job.copy(maxPixels = fit, outPixels = min(job.outPixels, fit))
        set(job.id) { it.copy(maxPixels = smaller.maxPixels, outPixels = smaller.outPixels) }
        persist()
        return smaller
    }

    /** The source stays resident even when SHARE renders a smaller output. */
    private fun workingPixels(job: ProcessingJob): Long =
        exportWorkingArea(job.workingPixels, job.workingEdge, job.maxPixels)

    /** Output estimate is still used for render pacing, independently of source residency. */
    private fun renderedPixels(job: ProcessingJob): Long =
        if (job.maxPixels > 0) min(job.outPixels, job.maxPixels) else job.outPixels

    /**
     * After a job that finished: its peak RSS above the RSS it started at, per
     * pixel, folded into the next [fitToMemory]. Only when VmHWM was reset for
     * the job; otherwise it is the process's peak, which says nothing about it.
     */
    private fun learnPeak(job: ProcessingJob, rssBeforeKb: Long, reset: Boolean) {
        // An upper bound is safe for admission, but is not a measured area for learning.
        if (!reset || rssBeforeKb < 0 || job.workingPixels <= 0) return
        val peakKb = ProcMemory.statusKb("VmHWM")
        val pixels = workingPixels(job)
        if (peakKb <= rssBeforeKb || pixels < 1_000_000) return
        val measured = ((peakKb - rssBeforeKb) * 1024.0 / pixels).coerceIn(20.0, 400.0)
        val next = 0.6 * peakBytesPerPixel() + 0.4 * measured
        prefs.edit().putFloat("working_peak_bpp", next.toFloat()).apply()
    }

    /**
     * The frame's pixel cap when the film edge is on: the canvas (frame plus
     * rebate) must fit [cap] and DeviceProfile.MAX_CANVAS_PIXELS, so the frame
     * gets that divided by [CANVAS_AREA]. A cap of 0 (none) still gets the
     * ceiling: the canvas is one plane however the job was sized.
     */
    private fun frameCapForCanvas(cap: Long): Long {
        val canvas = if (cap > 0) min(cap, DeviceProfile.MAX_CANVAS_PIXELS) else DeviceProfile.MAX_CANVAS_PIXELS
        return (canvas / CANVAS_AREA).toLong()
    }

    /** One working size down, and 60 % of the effective estimated pixel area. */
    private fun smaller(job: ProcessingJob): ProcessingJob {
        val edge = DeviceProfile.EDGES.filter { job.workingEdge <= 0 || it < job.workingEdge }.maxOrNull()
            ?: DeviceProfile.EDGES.first()
        return job.copy(workingEdge = edge, maxPixels = exportRetryPixelCap(job.maxPixels, job.outPixels))
    }

    /**
     * The editor's live renders go first; nothing here starts while a finger is
     * moving. [render]: the render comes next, so it also waits for the phone
     * to cool and hints ADPF (ExportPacer; 极致渲染 waits longer and at a lower line).
     */
    private suspend fun yieldToEditor(render: Boolean = false, extreme: Boolean = false) {
        while (SystemClock.uptimeMillis() - host.lastInteraction < EDITOR_GRACE_MS) delay(150)
        // M6 hook: thermal pacing lives in ExportPacer.
        if (render) host.pacer.beforeRender(extreme)
    }

    /** Engine thread, straight after a failed call: what the failure means for the job. */
    private fun engineFailure(engine: Long, what: String): Nothing {
        if (NativeEngine.lastWasCancelled()) throw CancellationException("cancelled")
        if (NativeEngine.lastWasDeviceLost()) throw DeviceLostException(engine, NativeEngine.lastError())
        val error = NativeEngine.lastError()
        // The pre-save check (blank_frame): nothing was written to the gallery.
        if (error.startsWith("export check:")) throw BlankFrameException(blankReason(error))
        if (nativeOutOfMemory(error)) throw OutOfMemoryException(error)
        throw EngineException("$what：$error")
    }

    // A native bad_alloc (the JNI layer's "out of memory"), a bitmap the Java
    // heap could not give, or Vulkan's out-of-host/device-memory (VkResult -1, -2).
    private fun nativeOutOfMemory(error: String) = "out of memory" in error ||
        error.startsWith("cannot allocate a ") || "(VkResult -1)" in error || "(VkResult -2)" in error

    private fun blankReason(error: String): String = when {
        "black" in error -> "全黑"
        "white" in error -> "全白"
        "solid colour" in error -> "纯色"
        else -> "无像素"
    }

    /**
     * Runs the export's render with its size published to the editor
     * (EngineHost.exportRenderPixels), so a large one is not overlapped by the
     * editor's sharp preview. Cleared however the render ends.
     */
    private suspend fun <T> rendering(job: ProcessingJob, body: suspend () -> T): T {
        host.exportRenderPixels = renderedPixels(job)
        try {
            // M6 hook: the export thread yields the big cores while the user edits or the phone is warm.
            return host.pacer.during(body)
        } finally {
            host.exportRenderPixels = 0L
        }
    }

    /**
     * IO. 极致渲染's JPEG into [part]: 4:4:4, quality 98, optimised Huffman
     * (NativeEngine.writeJpeg444), with the file's profile embedded. False,
     * logged and with nothing left in [part], when it cannot: the caller then
     * uses Bitmap.compress and the job goes on.
     */
    private fun encode444(id: Long, out: Bitmap, part: File, wide: Boolean, dir: File): Boolean {
        val icc = if (wide) displayP3Icc() else runCatching { File(dir, "io/sRGB.icc").readBytes() }.getOrNull()
        if (icc == null || icc.isEmpty()) {
            host.events.add("job $id: no ${if (wide) "Display P3" else "sRGB"} profile for 4:4:4, using Bitmap.compress")
            return false
        }
        val bitmap = if (out.config == Bitmap.Config.ARGB_8888) out else out.copy(Bitmap.Config.ARGB_8888, false)
        try {
            if (bitmap != null && NativeEngine.writeJpeg444(bitmap, part.absolutePath, 98, icc)) return true
        } finally {
            if (bitmap != null && bitmap !== out) bitmap.recycle()
        }
        part.delete()
        host.events.add("job $id: 4:4:4 JPEG failed (${NativeEngine.lastError()}), using Bitmap.compress 4:2:0")
        return false
    }

    /**
     * The Display P3 profile Bitmap.compress embeds (Skia's), so a 4:4:4 JPEG
     * carries the same bytes a 4:2:0 one does: a 1x1 P3 JPEG is encoded once
     * and its APP2 ICC_PROFILE segments joined. Null when there are none.
     */
    private val p3Icc: ByteArray? by lazy {
        runCatching {
            val p3 = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.DISPLAY_P3)
            val tiny = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888, false, p3)
            val jpeg = java.io.ByteArrayOutputStream().also { tiny.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
            tiny.recycle()
            Icc.iccFromJpeg(jpeg)
        }.getOrNull()
    }

    private fun displayP3Icc(): ByteArray? = p3Icc

    object Icc {
        /** The APP2 ICC_PROFILE chunks of [jpeg], in sequence order and joined; null when it has none. */
        fun iccFromJpeg(jpeg: ByteArray): ByteArray? {
            val tag = "ICC_PROFILE\u0000".toByteArray(Charsets.US_ASCII)
            val chunks = java.util.TreeMap<Int, ByteArray>()
            var i = 2
            while (i + 4 <= jpeg.size && (jpeg[i].toInt() and 0xFF) == 0xFF) {
                val marker = jpeg[i + 1].toInt() and 0xFF
                if (marker == 0xDA || marker == 0xD9) break   // scan data: no more headers
                val len = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
                if (len < 2 || i + 2 + len > jpeg.size) break
                val body = i + 4
                if (marker == 0xE2 && len >= 2 + tag.size + 2 &&
                    (tag.indices).all { jpeg[body + it] == tag[it] }) {
                    val seq = jpeg[body + tag.size].toInt() and 0xFF
                    chunks[seq] = jpeg.copyOfRange(body + tag.size + 2, i + 2 + len)
                }
                i += 2 + len
            }
            if (chunks.isEmpty()) return null
            val out = java.io.ByteArrayOutputStream()
            for (c in chunks.values) out.write(c)
            return out.toByteArray()
        }
    }

    /** Records the pending gallery entry before a byte is written. Any thread. */
    private fun markPending(id: Long, uri: Uri) {
        set(id) { it.copy(pendingUri = uri.toString()) }
        if (!persist(sync = true)) throw EngineException("无法记录相册写入状态")
    }

    /**
     * 导出印样 as a queue job: the roll from its snapshot, its frames from
     * FrameStore (one removed since is left out), progress per frame laid out.
     * Saved like any export, its pending entry recorded first. FilmThumbs'
     * idle wait lets a running sheet through, or its own thumbnails would wait
     * for it.
     */
    private suspend fun processSheet(job: ProcessingJob, step: (String, Float) -> Unit): String {
        val store = FrameStore.of(app)
        val roll = withContext(Dispatchers.IO) { store.load(); RollContactSheet.restore(store, job.sheetJson) }
            ?: throw EngineException("这卷的照片都已从观片台移除，印样没有保存")
        step("排版", 0.05f)
        return RollContactSheet.export(app, store, roll, job.maxPixels,
            progress = { done, total -> step("排版 $done / $total", 0.05f + 0.85f * done / max(1, total)) },
            onCreated = { markPending(job.id, it) })
    }

    private suspend fun process(job: ProcessingJob, step: (String, Float) -> Unit): String {
        val parsed = Edit.fromParams(JSONObject(job.editJson), Edit())
        val ready = host.start(parsed.film, parsed.paper)
        val engine = ready.engine
        // The catalog fills the edge print back in from the film and gauge; the
        // app-side toggle decides whether it reaches the engine. The wire cannot
        // carry that toggle, so it rides on the job (Edit.edgeRealName).
        val edit = ready.catalog.normalize(parsed).let { if (job.edgeRealName) it else it.copy(edgeText = "") }
        // The copy made at enqueue when the URI's grant could not be kept.
        val local = job.sourcePath.takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isFile }
        val uri = if (local != null) Uri.fromFile(local) else Uri.parse(job.uri)
        step("读取照片", 0.05f)
        val exif = withContext(Dispatchers.IO) { ExifCopy.read(app, uri, fillKey = Uri.parse(job.uri)) }.withLocation(ExifLocation.decode(job.location))
        // The film edge lays the frame on a canvas up to CANVAS_AREA times its
        // area, and the canvas, not the frame, is the one GPU plane that has to
        // fit the buffer cap and the 42 MP ceiling (45.75 MP + a 135 edge is
        // 69.3 MP and took a device down, SAINTTaiYi dd7bd70). A job with no cap
        // still gets the ceiling here.
        val maxPixels = if (edit.filmEdge) frameCapForCanvas(job.maxPixels) else job.maxPixels
        // JPEG uses the wide-gamut preference; TIFF selects its own target below.
        val wide = job.wideGamut && job.format == ExportFormat.JPEG
        val outSpace = if (wide) "Display P3" else "sRGB"
        yieldToEditor()
        // Own the handle before returning from the native dispatcher. A cancelled
        // withContext may discard its return value after native open succeeded.
        var handle = 0L
        try {
            if (job.raw) {
                // LibRaw reads a path: the kept copy itself, or the URI's descriptor
                // (/proc/self/fd), falling back to a cache copy (SourceLoader.rawInput).
                // Assigned inside the IO hop, so a cancel as it returns still closes it.
                var input: RawInput? = null
                try {
                    if (local == null) withContext(Dispatchers.IO) { input = loader.rawInput(uri, job.name, "job") }
                    val path = local?.absolutePath ?: input!!.path
                    step("解码 RAW", 0.12f)
                    // trimMemory is engine-wide (EngineThread); the session is the export's own.
                    withContext(EngineThread.dispatcher) { NativeEngine.trimMemory(engine) }
                    withRawOpenCancellation { cancellation ->
                        withContext(ExportThread.dispatcher) {
                            val params = Edit.openingParams(edit, inputSpace = "ProPhoto RGB", linear = true).toString()
                            val h = NativeEngine.openRawCancellableV1(engine, path, job.workingEdge, maxPixels, params, job.wbK, job.wbTint, cancellation)
                            if (h == 0L) engineFailure(engine, "打开失败")
                            handle = h
                            synchronized(handleLock) { runningHandle = h }
                        }
                    }
                } finally {
                    input?.close()
                }
            } else {
                step("解码照片", 0.10f)
                var photo: Photo? = null
                try {
                    withContext(Dispatchers.IO) { photo = loader.decodePhoto(uri, job.workingEdge, maxPixels) }
                    withContext(EngineThread.dispatcher) { NativeEngine.trimMemory(engine) }
                    withContext(ExportThread.dispatcher) {
                        val opening = photo!!
                        photo = null // openPhotoSession now owns and recycles the bitmaps.
                        val h = openPhotoSession(engine, opening, job.highlightStops) { space, linear ->
                            Edit.openingParams(edit, inputSpace = space, linear = linear).toString()
                        }.handle
                        if (h == 0L) engineFailure(engine, "打开失败")
                        handle = h
                        synchronized(handleLock) { runningHandle = h }
                    }
                } finally {
                    photo?.recycle()
                }
            }
            // The file's own space, through the CAM16 export path (upstream's export
            // transform, lightness shoulder off). TIFF selects a local transform below.
            withContext(ExportThread.dispatcher) {
                if (!NativeEngine.setOutput(handle, outSpace, true)) engineFailure(engine, "导出失败")
            }
            // Layer 2 rides on the job's own session: every pack below goes through it.
            Look.fromJson(job.lookJson.takeIf { it.isNotEmpty() }?.let { JSONObject(it) }).let { look ->
                withContext(ExportThread.dispatcher) {
                    UserLibrary.get(app).applyLut(handle, look, null)
                    if (!NativeEngine.setLook(handle, if (look.neutral) null else look.values()))
                        engineFailure(engine, "应用调整失败")
                }
            }
            val stem = "Spektralab-X_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date()) + "_" + (job.id % 1000)
            yieldToEditor(render = true, extreme = job.extreme)
            step(if (job.extreme) "高质量冲洗" else "冲洗", 0.25f)
            when (job.format) {
                ExportFormat.TIFF -> {
                    // Rendered straight into a pending entry (the gallery hides it), so
                    // a 100+ MB file is written once; checked, then published.
                    val name = "$stem.tif"
                    val target = withContext(Dispatchers.IO) {
                        MediaSaver.create(app, name, "image/tiff").also { markPending(job.id, it) }
                    }
                    try {
                        // 根据目标色彩空间准备 ICC 配置文件路径和目标空间名称
                        val (iccPath, targetSpace) = when (job.tiffColorSpace) {
                            TiffColorSpace.SRGB -> {
                                val icc = File(ready.dir, "io/sRGB.icc").absolutePath
                                icc to "sRGB"
                            }
                            TiffColorSpace.DISPLAY_P3 -> {
                                // Display P3: 运行时从系统提取，保存临时文件供 native 读取
                                val p3Icc = displayP3Icc()
                                if (p3Icc == null || p3Icc.isEmpty()) {
                                    // Fallback: 尝试从引擎资源读取（未来可能添加）
                                    val fallback = File(ready.dir, "io/Display_P3.icc")
                                    if (fallback.isFile) {
                                        fallback.absolutePath to "Display P3"
                                    } else {
                                        throw EngineException("Display P3 ICC 配置文件不可用")
                                    }
                                } else {
                                    val tmpIcc = File(app.cacheDir, "export_p3.icc")
                                    tmpIcc.writeBytes(p3Icc)
                                    tmpIcc.absolutePath to "Display P3"
                                }
                            }
                            TiffColorSpace.PROPHOTO -> {
                                // ProPhoto: 生成标准 ROMM RGB ICC
                                val prophotoIcc = IccProfile.generateProPhotoRgb()
                                val tmpIcc = File(app.cacheDir, "export_prophoto.icc")
                                tmpIcc.writeBytes(prophotoIcc)
                                tmpIcc.absolutePath to "ProPhoto RGB"
                            }
                            TiffColorSpace.ADOBE_RGB -> {
                                // Exact 563/256 TRC; Android's named space uses gamma 2.2.
                                val tmpIcc = File(app.cacheDir, "export_adobe_rgb.icc")
                                tmpIcc.writeBytes(IccProfile.generateAdobeRgb())
                                tmpIcc.absolutePath to job.tiffColorSpace.targetName
                            }
                        }

                        withContext(Dispatchers.IO) {
                            val pfd = app.contentResolver.openFileDescriptor(target, "w") ?: throw EngineException("无法写入文件")
                            pfd.use {
                                // engineFailure reads this thread's error, so it stays inside.
                                rendering(job) {
                                    withContext(ExportThread.dispatcher) {
                                        if (!NativeEngine.exportTiffFd(handle, it.fd, iccPath, targetSpace, job.size.tier))
                                            engineFailure(engine, "导出失败")
                                    }
                                }
                            }
                        }
                        step("校验", 0.94f)
                        withContext(Dispatchers.IO) {
                            MediaSaver.checkTiff(app, target)
                            MediaSaver.publish(app, target)
                        }
                    } catch (e: Throwable) {
                        withContext(NonCancellable + Dispatchers.IO) { MediaSaver.discardIfPending(app, target) }
                        throw e
                    }
                    return name
                }
                // HEIF takes the JPEG path whole (render, paper frame, .part, check,
                // gallery) with its own encoder; it is always sRGB (wide is JPEG only).
                ExportFormat.JPEG, ExportFormat.HEIF -> {
                    val heif = job.format == ExportFormat.HEIF
                    val frame = FrameSpec.fromJson(job.frameJson.takeIf { it.isNotEmpty() }?.let { JSONObject(it) })
                    val streamProfile = if (job.extreme && !heif && frame.isNone) {
                        if (wide) displayP3Icc() else runCatching { File(ready.dir, "io/sRGB.icc").readBytes() }.getOrNull()
                    } else null
                    if (streamProfile != null && streamProfile.isNotEmpty()) {
                        val name = "$stem.jpg"
                        val part = withContext(Dispatchers.IO) {
                            exportDir.mkdirs()
                            File(exportDir, "$name.part")
                        }
                        try {
                            val shape = rendering(job) {
                                withContext(ExportThread.dispatcher) {
                                    NativeEngine.exportJpeg444(handle, part.absolutePath, job.size.tier, 98, streamProfile)
                                        .takeIf { it != 0L } ?: engineFailure(engine, "导出失败")
                                }
                            }
                            val width = (shape ushr 32).toInt()
                            val height = (shape and 0xffffffffL).toInt()
                            step("校验", 0.90f)
                            val description = withContext(Dispatchers.IO) {
                                exif.applyTo(part, width, height, wide = wide)
                                MediaSaver.checkJpeg(part, width, height)
                                "$name · ${width}×${height} · " + String.format(Locale.ROOT, "%.2f MB", part.length() / 1_000_000.0)
                            }
                            step("写入相册", 0.92f)
                            withContext(Dispatchers.IO) {
                                MediaSaver.saveFile(app, part, name, "image/jpeg") { markPending(job.id, it) }
                            }
                            host.events.add("job ${job.id} saved $description (JPEG rows)")
                            return description
                        } finally {
                            withContext(NonCancellable + Dispatchers.IO) { part.delete() }
                        }
                    }
                    val bitmap: Bitmap = rendering(job) {
                        withContext(ExportThread.dispatcher) {
                            NativeEngine.exportBitmap(handle, job.size.tier) ?: engineFailure(engine, "导出失败")
                        }
                    }
                    // The pixels are P3 already; tag them so compress embeds the profile.
                    if (wide) runCatching { bitmap.setColorSpace(android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.DISPLAY_P3)) }
                    step("编码", 0.88f)
                    val name = if (heif) "$stem.heic" else "$stem.jpg"
                    var savedDescription = name
                    try {
                        // Encoded to a .part first: EXIF is written in place, and the
                        // file is checked whole before the gallery sees any of it.
                        val part = withContext(Dispatchers.IO) {
                            exportDir.mkdirs()
                            val part = File(exportDir, "$name.part")
                            try {
                                // The paper frame is drawn around the print here, after the engine.
                                val out = if (frame.isNone) bitmap else FrameSpec.render(bitmap, frame, Caption.of(exif, job.filmLabel))
                                try {
                                    if (heif) {
                                        // EXIF goes in while encoding: a HEIF cannot be tagged afterwards.
                                        HeifExport.encode(out, part, HeifExport.exifBlock(exif, out.width, out.height, exportDir))
                                        MediaSaver.checkHeif(part, out.width, out.height)
                                    } else {
                                        // 极致渲染: 4:4:4 through libjpeg-turbo; anything else, or a
                                        // failure there, Bitmap.compress (4:2:0).
                                        val full = job.extreme && encode444(job.id, out, part, wide, ready.dir)
                                        if (!full) {
                                            val encoded = part.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, if (job.extreme) 98 else 95, it) }
                                            if (!encoded) throw EngineException("JPEG 编码失败")
                                        }
                                        exif.applyTo(part, out.width, out.height, wide = wide)
                                        MediaSaver.checkJpeg(part, out.width, out.height)
                                    }
                                    savedDescription = "$name · ${out.width}×${out.height} · " +
                                        String.format(Locale.ROOT, "%.2f MB", part.length() / 1_000_000.0)
                                } finally {
                                    if (out !== bitmap) out.recycle()
                                }
                                part
                            } catch (e: Throwable) {
                                part.delete()
                                throw e
                            }
                        }
                        step("写入相册", 0.92f)
                        try {
                            withContext(Dispatchers.IO) {
                                MediaSaver.saveFile(app, part, name, if (heif) "image/heic" else "image/jpeg") { markPending(job.id, it) }
                            }
                        } finally {
                            part.delete()
                        }
                    } finally {
                        bitmap.recycle()
                    }
                    host.events.add("job ${job.id} saved $savedDescription")
                    return savedDescription
                }
            }
        } finally {
            withContext(NonCancellable + ExportThread.dispatcher) {
                synchronized(handleLock) {
                    if (handle != 0L) NativeEngine.release(handle)
                    runningHandle = 0L
                }
            }
        }
    }

    // --- sources, leftovers -------------------------------------------------------

    /**
     * IO. A share or view intent's grant ends with its activity, and a
     * resumed job runs in a new process: take a persistable grant where the
     * URI offers one (the photo picker and the document picker do), else copy
     * the file into app storage. A failure leaves the job on its URI, which
     * still works while this process lives.
     */
    private fun secure(job: ProcessingJob): ProcessingJob {
        if (job.sourcePath.isNotEmpty() && File(job.sourcePath).isFile) return job
        val uri = Uri.parse(job.uri)
        if (uri.scheme == "file") return job
        val resolver = app.contentResolver
        val persisted = synchronized(grantLock) {
            val held = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
            if (held || runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }.isSuccess) {
                prefs.edit().putStringSet("grants", HashSet(prefs.getStringSet("grants", emptySet()).orEmpty()) + job.uri).apply()
                true
            } else false
        }
        if (persisted) return job.copy(persisted = true)
        return runCatching {
            sourcesDir.mkdirs()
            val safe = job.name.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80).ifEmpty { "source" }
            val file = File(sourcesDir, "${job.id}_$safe")
            val part = File(sourcesDir, "${job.id}_$safe.part")
            try {
                (resolver.openInputStream(uri) ?: throw EngineException("无法读取该文件")).use { i ->
                    part.outputStream().use { o -> i.copyTo(o, 1 shl 20) }
                }
                if (!part.renameTo(file)) throw EngineException("cannot keep a copy of the source")
            } finally {
                part.delete()
            }
            host.events.add("job ${job.id}: source copied (${file.length() shr 20} MB)")
            job.copy(sourcePath = file.absolutePath)
        }.getOrElse { e ->
            host.events.add("job ${job.id}: source not secured: ${e.message ?: e}")
            job
        }
    }

    /**
     * IO, before the first job of a process. A job the last process died in
     * may have a pending gallery entry: a finished photo when the kill came
     * after publishing, otherwise a partial file to delete.
     */
    private fun recoverInterrupted() {
        for (job in _jobs.value) if (job.pendingUri.isNotEmpty()) settle(job)
        // Killed for memory mid-export: the retry decodes one size smaller.
        if (host.lastExitWasMemory) for (id in interrupted) {
            set(id) { if (it.state == JobState.QUEUED) smaller(it) else it }
            host.events.add("job $id: memory kill, retrying a size down")
        }
        val protectedUris = _jobs.value.map { it.pendingUri }.filter { it.isNotEmpty() }.toSet()
        val stale = MediaSaver.sweepPending(app, protectedUris)
        if (stale > 0) host.events.add("removed $stale unfinished gallery entries")
        persist()
    }

    /**
     * IO. A job's recorded gallery entry: published means the job is done
     * (true). Only confirmed removal permits another render (false); an
     * unavailable provider retains the URI and stops this job for manual retry.
     */
    private fun settle(job: ProcessingJob): Boolean = synchronized(galleryLock) {
        val latest = _jobs.value.firstOrNull { it.id == job.id } ?: return@synchronized true
        if (latest.pendingUri.isEmpty()) return@synchronized latest.state != JobState.QUEUED
        val entry = MediaSaver.discardIfPending(app, Uri.parse(latest.pendingUri))
        when (entry.state) {
            GalleryState.PUBLISHED -> {
                set(job.id) { it.copy(state = JobState.DONE, progress = 1f, phase = "完成", etaSeconds = 0,
                    finishedAt = now(), message = entry.name.ifEmpty { "已保存到相册" }, pendingUri = "") }
                host.events.add("job ${job.id}: already saved as ${entry.name}")
                true
            }
            GalleryState.GONE -> {
                set(job.id) { it.copy(pendingUri = "") }
                false
            }
            GalleryState.UNKNOWN, GalleryState.PENDING -> {
                set(job.id) { it.copy(state = JobState.FAILED, etaSeconds = 0, finishedAt = now(),
                    message = "相册写入结果暂时无法确认；请稍后重试核对") }
                host.events.add("job ${job.id}: gallery result unknown; URI retained")
                true
            }
        }
    }

    /** IO. Encodes a killed export left in cache, and sources no job needs any more. */
    private fun sweepFiles() {
        exportDir.listFiles()?.forEach { it.delete() }
        File(app.cacheDir, "job").listFiles()?.forEach { it.delete() }
        // Older builds encoded into the cache root.
        app.cacheDir.listFiles()?.forEach { f ->
            if (f.isFile && (f.name.startsWith("Spektralab-X_") || f.name.startsWith("SpektraLab_"))) f.delete()
        }
        releaseSources()
    }

    private fun releaseSourcesLater() {
        host.scope.launch(Dispatchers.IO) { releaseSources() }
    }

    /**
     * IO. Deletes copied sources and releases grants that only finished jobs
     * referred to. A failed or cancelled job keeps its source for 重试, until
     * it leaves the list; a job still being secured keeps whatever it is making.
     */
    private fun releaseSources() = synchronized(grantLock) {
        val needed = _jobs.value.filter { it.state != JobState.DONE }
        val paths = needed.map { it.sourcePath }.filter { it.isNotEmpty() }.toSet()
        val securing = needed.filter { !it.secured }.map { "${it.id}_" }
        sourcesDir.listFiles()?.forEach { f ->
            if (f.absolutePath !in paths && securing.none { f.name.startsWith(it) }) f.delete()
        }
        val uris = needed.filter { it.persisted || !it.secured }.map { it.uri }.toSet()
        val held = prefs.getStringSet("grants", emptySet()).orEmpty().toSet()
        val keep = held.filter { it in uris }.toSet()
        val editorUri = app.getSharedPreferences("settings", 0).getString("editor_grant", null)
        val frames = FrameStore.of(app)
        for (u in held - keep) {
            // Persisted grants belong to the app, not to each take() call.
            // Hand ownership back without leaving a stale queue reference.
            if (u != editorUri && !frames.holds(u)) {
                runCatching { app.contentResolver.releasePersistableUriPermission(Uri.parse(u), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            }
        }
        if (keep.size != held.size) prefs.edit().putStringSet("grants", keep).apply()
    }

    /** VmHWM is a diagnostic peak RSS; it is not the OS memory admission limit. */
    private fun peakText(rssBeforeKb: Long, reset: Boolean): String {
        val kb = ProcMemory.statusKb("VmHWM")
        if (kb < 0) return "peak RSS unknown"
        return "RSS ${rssBeforeKb / 1024} MB before, peak ${kb / 1024} MB" + if (reset) "" else " (process peak)"
    }

    // --- estimate, progress, notification -----------------------------------

    private fun tick(id: Long, startedAt: Long, estimate: Int) {
        val elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1000.0
        val guess = (0.05 + 0.88 * (elapsed / max(1.0, estimate.toDouble()))).coerceAtMost(0.95)
        set(id) {
            if (it.state != JobState.RUNNING) it
            else it.copy(progress = max(it.progress, guess.toFloat()), etaSeconds = max(1, (estimate - elapsed).roundToInt()))
        }
        refreshNotification()
    }

    private fun estimateSeconds(job: ProcessingJob): Int {
        if (job.sheet) return (BASE_SECONDS + secondsPerFrame() * max(1, job.sheetFrames)).roundToInt()
        val mp = max(0.5, job.outPixels / 1e6)
        return (BASE_SECONDS + secondsPerMp(job) * mp).roundToInt()
    }

    // Learned per format, and separately for 极致渲染: the whole-frame full
    // tier at the source's size runs slower per megapixel than a working-size
    // export (larger blurs, more pool traffic), and would skew the others.
    private fun rateKey(job: ProcessingJob) = "spmp_${job.format.name}" + if (job.extreme) "_extreme" else ""

    private fun secondsPerMp(job: ProcessingJob): Double {
        val base = if (job.format == ExportFormat.TIFF) 1.6f else 1.2f
        return prefs.getFloat(rateKey(job), if (job.extreme) base * 1.5f else base).toDouble()
    }

    /** A contact sheet's learned seconds per frame (a thumbnail developed, or read from cache, and drawn). */
    private fun secondsPerFrame(): Double = prefs.getFloat(SHEET_RATE_KEY, DEFAULT_SHEET_SPF).toDouble()

    private fun learn(job: ProcessingJob, seconds: Double) {
        if (job.sheet) {
            val measured = max(0.05, (seconds - BASE_SECONDS) / max(1, job.sheetFrames))
            prefs.edit().putFloat(SHEET_RATE_KEY, (0.6 * secondsPerFrame() + 0.4 * measured).toFloat()).apply()
            return
        }
        val mp = max(0.5, job.outPixels / 1e6)
        val measured = max(0.05, (seconds - BASE_SECONDS) / mp)
        val next = 0.6 * secondsPerMp(job) + 0.4 * measured
        prefs.edit().putFloat(rateKey(job), next.toFloat()).apply()
    }

    private fun refreshNotification(force: Boolean = false) {
        // Paused: the service is gone and the paused notice says why.
        if (paused) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - notifiedAt < 900) return
        notifiedAt = now
        val list = _jobs.value
        if (list.none { it.active }) return
        JobNotifier.post(app, JobNotifier.ID, JobNotifier.build(app, list))
    }


    /** One short "done" notice per batch, after the ongoing one goes away. */
    private fun finishedNotice() {
        val fresh = _jobs.value.filter { !it.active && it.id !in noticed && it.finishedAt > 0 }
        if (fresh.isEmpty()) return
        noticed.addAll(fresh.map { it.id })
        val done = fresh.count { it.state == JobState.DONE }
        val failed = fresh.count { it.state == JobState.FAILED }
        if (done == 0 && failed == 0) return
        JobNotifier.post(app, JobNotifier.DONE_ID, JobNotifier.finished(app, done, failed))
    }

    // --- state ------------------------------------------------------------------

    private fun set(id: Long, f: (ProcessingJob) -> ProcessingJob) =
        _jobs.update { list -> list.map { if (it.id == id) f(it) else it } }

    /** [sync] writes before returning: for state a kill must not lose (an attempt, a pending entry). */
    private fun persist(sync: Boolean = false): Boolean {
        // Keep every unresolved gallery ticket, plus the newest 30 finished records.
        val list = _jobs.value
        val keep = list.filter { it.active || it.pendingUri.isNotEmpty() } +
            list.filter { !it.active && it.pendingUri.isEmpty() }.sortedByDescending { it.finishedAt }.take(30)
        val arr = JSONArray()
        keep.sortedBy { it.id }.forEach { arr.put(it.toJson()) }
        val edit = prefs.edit().putString("jobs", arr.toString())
        return if (sync) edit.commit() else { edit.apply(); true }
    }

    private fun load(): List<ProcessingJob> = runCatching {
        val arr = JSONArray(prefs.getString("jobs", "[]"))
        val list = (0 until arr.length()).mapNotNull { ProcessingJob.fromJson(arr.getJSONObject(it)) }
        interrupted = list.filter { it.state == JobState.RUNNING }.map { it.id }.toSet()
        // A killed runner resumes unless the user cancelled it. Its gallery
        // ticket is still reconciled first, so a published result always wins.
        list.map { if (it.state == JobState.RUNNING) it.copy(state = if (it.cancelledByUser) JobState.CANCELLED else JobState.QUEUED) else it }
            .map { if (!it.secured) it.copy(secured = true) else it }
            .also { noticedInit(it) }
    }.getOrDefault(emptyList())

    private fun noticedInit(list: List<ProcessingJob>) {
        noticed = HashSet(list.filter { !it.active }.map { it.id })
    }

    private fun now() = System.currentTimeMillis()

    companion object {
        private const val BASE_SECONDS = 2.0
        private const val EDITOR_GRACE_MS = 1200L
        private const val SHEET_RATE_KEY = "spf_contact"
        private const val DEFAULT_SHEET_SPF = 1.5f
        /**
         * Canvas over frame area, worst case over upstream's formats
         * (overscan.cpp kFormats, strip mode, carrier on): 135 half frame is
         * (35 + 0.8) / 24 across by (18 + 2 x 0.5) / 18 along = 1.57; full 135
         * 1.55, XPan 1.53, the 120 formats about 1.23, frame mode about 1.2.
         */
        private const val CANVAS_AREA = 1.6
        /** Kept free for the editor's own session and the system UI. */
        private const val MEMORY_RESERVE = 768L shl 20
        /** Peak RSS per rendered pixel before this phone has measured its own (DeviceProfile's 100 B/px). */
        private const val DEFAULT_PEAK_BPP = 100f
        /** The smallest frame a memory tier shrinks a job to. */
        private const val MIN_TIER_PIXELS = 4_000_000L

        /** Seconds until every active job is done: the running one's own estimate plus each queued one's. */
        fun remainingSeconds(jobs: List<ProcessingJob>, perMpJpeg: Double = 1.2): Int {
            var total = 0
            for (j in jobs) when (j.state) {
                JobState.RUNNING -> total += max(0, j.etaSeconds)
                JobState.QUEUED -> total += if (j.sheet) (BASE_SECONDS + DEFAULT_SHEET_SPF * max(1, j.sheetFrames)).roundToInt()
                    else (BASE_SECONDS + perMpJpeg * (if (j.extreme) 1.5 else 1.0) * max(0.5, j.outPixels / 1e6)).roundToInt()
                else -> Unit
            }
            return total
        }

        fun formatEta(seconds: Int): String = when {
            seconds <= 0 -> "即将完成"
            seconds < 60 -> "$seconds 秒"
            else -> "${seconds / 60} 分 ${min(59, seconds % 60)} 秒"
        }
    }
}
