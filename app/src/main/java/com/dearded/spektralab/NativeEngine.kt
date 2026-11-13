package com.dearded.spektralab

import android.graphics.Bitmap

/**
 * The JNI surface in `spektralab_jni.cpp`. Handles are raw native pointers.
 * One session is used on one thread only: the editor's on [EngineThread], an
 * export's on [ExportThread]. lastError, lastWasCancelled and
 * lastWasDeviceLost read a thread-local string, so they are called on the
 * thread of the call that failed. Engine-level calls (create, destroy,
 * capabilities, trimMemory, warmUp) run on EngineThread only.
 */
object NativeEngine {
    init {
        System.loadLibrary("spektralab_jni")
    }

    external fun lastError(): String
    external fun deviceInfo(): String

    /**
     * The storage-buffer size, in bytes up to [ceiling], whose far writes were
     * measured to land on a throwaway VkDevice; 0 when even 128 MiB failed
     * (lastError has the log). Slow: EngineHost runs it once per driver.
     */
    external fun probeStorageRange(ceiling: Long): Long

    external fun create(resourcesDir: String, tmpDir: String): Long
    external fun destroy(engine: Long)
    external fun capabilities(engine: Long): String

    /**
     * Decodes a RAW file and opens a session on it, downscaled to fit both limits (0 = none).
     * [temperatureK] 0 decodes with the camera's as-shot white balance.
     */
    external fun openRaw(
        engine: Long, path: String, maxEdge: Int, maxPixels: Long, paramsJson: String,
        temperatureK: Double, tint: Double,
    ): Long

    /** RAW-open tokens may be signalled on any thread; release only after open returns. */
    external fun createRawOpenCancellation(): Long
    external fun cancelRawOpen(cancellation: Long)
    external fun releaseRawOpenCancellation(cancellation: Long)

    /** [openRaw] with cooperative cancellation before a session exists. */
    external fun openRawCancellableV1(
        engine: Long, path: String, maxEdge: Int, maxPixels: Long, paramsJson: String,
        temperatureK: Double, tint: Double, cancellation: Long,
    ): Long

    /**
     * Opens a session on an ARGB_8888 bitmap in [space] ("sRGB" or "Display P3").
     * The pixels are converted to linear ProPhoto natively, as upstream's host
     * does, so [paramsJson] must say input_color_space "ProPhoto RGB" and
     * input_cctf_decoding false. [headroomStops] > 0 undoes the phone's
     * highlight shoulder so encoded white lands that many stops above 1.0.
     */
    external fun openBitmap(
        engine: Long, bitmap: Bitmap, space: String, paramsJson: String, headroomStops: Float,
        sourceWidth: Int = 0, sourceHeight: Int = 0,
    ): Long

    /**
     * Version 1 high precision SDR input: software RGBA_F16 in encoded sRGB/P3.
     * Unpremultiplied codes must be finite and in [0,1]; no PQ/HLG or gain map.
     * [alphaMode] is 0 (opaque), 1 (premultiplied) or 2 (straight).
     * Stride and format are read from the actual native bitmap; params as above.
     */
    external fun openBitmapF16V1(
        engine: Long, bitmap: Bitmap, space: String, paramsJson: String, headroomStops: Float,
        alphaMode: Int, sourceWidth: Int = 0, sourceHeight: Int = 0,
    ): Long

    /**
     * Opens an Ultra HDR photo: [bitmap] (the SDR base, ARGB_8888, in [space])
     * recovered with its gain map [gain] (ALPHA_8 or ARGB_8888) to linear light
     * at full boost, then converted to linear ProPhoto. [meta] is ratioMin,
     * ratioMax, gamma, epsilonSdr, epsilonHdr, 3 each. [paramsJson] as for openBitmap.
     */
    external fun openBitmapHdr(
        engine: Long, bitmap: Bitmap, gain: Bitmap, meta: FloatArray, space: String, paramsJson: String,
        sourceWidth: Int = 0, sourceHeight: Int = 0,
    ): Long

    /** A small sRGB thumbnail of the frame before any film was applied. */
    external fun beforeBitmap(session: Long): Bitmap?

    /** The photo at 384 px through [paramsJson] (complete opening params), in a throwaway session. */
    external fun filmPreview(session: Long, paramsJson: String): Bitmap?

    external fun sourceInfo(session: Long): String
    external fun setParams(session: Long, deltaJson: String): String?
    external fun getParams(session: Long): String?

    /**
     * RFC-015's meter: `target` "exposure" returns
     * `{"solved_params":{"exposure_compensation_ev":…},"exposure_ev_by_method":{…}}`
     * for the whole frame, without rendering. A tiled export meters the complete
     * frame once and freezes that EV on every tile. Null with lastError on failure.
     */
    external fun solve(session: Long, target: String): String?

    /**
     * One window of the film canvas (patch 0054). The layout, gate and edge
     * print stay the whole frame's, so every tile lines up; [winX]/[winY] is
     * where this window starts on the full canvas and [srcX]/[srcY] where this
     * session's source crop starts on the full frame. Call before [renderBitmap].
     */
    external fun setRenderWindow(
        session: Long, fullW: Int, fullH: Int,
        winX: Int, winY: Int, winW: Int, winH: Int,
        srcX: Int, srcY: Int, srcW: Int, srcH: Int,
    ): Boolean

    /**
     * The film canvas and gate quads a whole frame of [width] x [height] lays
     * out, without rendering. Same JSON as the engine's `spk_overscan_geometry`.
     */
    external fun overscanLayout(session: Long, width: Int, height: Int): String?

    /**
     * Where this render's tile sits in the *look's* frame (patch 0055): the
     * film-edge canvas when the edge is on, otherwise the photo. The look's
     * vignette, masks and dust then follow the whole picture on every tile.
     * Call after [setRenderWindow], before [renderBitmap].
     */
    external fun setLookWindow(session: Long, fullW: Int, fullH: Int, x0: Int, y0: Int): Boolean

    /** Tier is "live", "preview" or "full". */
    external fun renderBitmap(session: Long, tier: String): Bitmap?

    /**
     * JPEG colors at [tier], before encoding/paper composition. [target] is
     * "sRGB" or "Display P3"; tag the returned codes with that file target,
     * not the display space. Keeps the session's output and repack source.
     */
    external fun renderProofBitmap(session: Long, tier: String, target: String): Bitmap?

    /**
     * [renderBitmap] for an export: null (lastError "export check: ...") when
     * the frame is flat black or white, which only a failed render produces.
     */
    external fun exportBitmap(session: Long, tier: String): Bitmap?

    /**
     * [bitmap] (ARGB_8888) as a 4:4:4 JPEG at [quality] with optimised
     * Huffman tables, [icc] embedded when not null (libjpeg-turbo). Any thread,
     * no session; false with lastError (read on the same thread) on failure.
     */
    external fun writeJpeg444(bitmap: Bitmap, path: String, quality: Int, icc: ByteArray?): Boolean

    /**
     * Render/check/convert/encode without a full output Bitmap. Returns actual
     * width in the upper 32 bits and height in the lower 32 bits, or 0 on failure.
     * [icc] must describe the session's selected output space.
     */
    external fun exportJpeg444(session: Long, path: String, tier: String, quality: Int, icc: ByteArray): Long

    /** Renders [tier] into [reuse] when it is the same size, else into a new bitmap; null on failure. */
    external fun renderReuse(session: Long, tier: String, reuse: Bitmap?): Bitmap?

    /** Renders [tier] ("full" or "preview") and writes an RGB16 TIFF to [path]. */
    external fun exportTiff(session: Long, path: String, iccPath: String, tier: String): Boolean

    /** [exportTiff] into an open writable descriptor (a pending MediaStore entry); the caller closes it. */
    external fun exportTiffFd(session: Long, fd: Int, iccPath: String, tier: String): Boolean

    /**
     * TIFF 导出到文件描述符，支持指定目标色彩空间。
     * @param targetSpace "sRGB", "Display P3", "ProPhoto RGB", or "Adobe RGB (1998)".
     * [iccPath] must describe the selected target's encoded samples.
     */
    fun exportTiffFd(session: Long, fd: Int, iccPath: String, targetSpace: String, tier: String): Boolean =
        exportTiffFdV1(session, fd, iccPath, targetSpace, tier)

    // A distinct native name keeps the original four-argument JNI ABI intact.
    private external fun exportTiffFdV1(session: Long, fd: Int, iccPath: String, targetSpace: String, tier: String): Boolean

    external fun release(session: Long)

    /** Layer 2 for display/export (Look.values()); null clears it. False preserves the old look. */
    external fun setLook(session: Long, values: FloatArray?): Boolean

    /**
     * The user LUT (lut3d.hpp): [side]^3 RGB over encoded ProPhoto, red fastest,
     * as CubeLut.kt's LutBake makes it; mixed in at [strength] 0..1 before every
     * Layer 2 slider and kept across setLook. Null or strength 0 clears it;
     * false preserves the old state; read lastError on this thread.
     */
    external fun setLut(session: Long, values: FloatArray?, side: Int, strength: Float): Boolean

    /** The last display render again, through the current look, into [reuse] if it fits. */
    external fun repack(session: Long, reuse: Bitmap?): Bitmap?

    /**
     * Where the session's pixels go: [target] "sRGB" or "Display P3", for the
     * screen (matrix and clip) or, [exporting], a file (CAM16 chroma
     * compression into the space). Engine thread.
     */
    external fun setOutput(session: Long, target: String, exporting: Boolean): Boolean

    /** Any thread: stops the session's in-flight render at the next node. */
    external fun cancel(session: Long)
    external fun warmUp(engine: Long, film: String, paper: String): Boolean
    external fun trimMemory(engine: Long): Long
    external fun lastWasCancelled(): Boolean

    /** True when the last failure lost the GPU device (VkResult -4): only a new engine helps (EngineHost.recover). */
    external fun lastWasDeviceLost(): Boolean
}
