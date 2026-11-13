// JNI surface for the Android app. A session is used on one thread only: the
// editor's on EngineThread, an export's on ExportThread; engine-level calls
// stay on EngineThread. The error string is thread-local, so lastError() is
// read on the thread that failed. Nothing here throws into the JVM: failures
// return 0/null/false and leave a message for lastError().
#include <android/bitmap.h>
#include <android/log.h>
#include <jni.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <fstream>
#include <iterator>
#include <iomanip>
#include <memory>
#include <mutex>
#include <string>
#include <sstream>
#include <stdexcept>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include "io/raw_decoder.hpp"
#include "io/raw_camera.hpp"
#include "raw_white_balance.hpp"
#include "raw_development.hpp"
#include "raw_precision_input.hpp"
#include "gainmap.hpp"
#include "resize.hpp"
#include "spektrafilm/spk_engine.h"
#include "tiff16.hpp"
#include "storage_probe.hpp"
#include "jpeg444.hpp"
#include "rgba8_spool.hpp"
#include "float_frame_check.hpp"
#include "layer2.hpp"
#include "lut3d.hpp"
#include "look_state.hpp"
#include "output_transform.hpp"
#include "passthrough.hpp"
#include "input_transform.hpp"
#include "bitmap_f16.hpp"
#include "pipeline/view.hpp"
#include "volk.h"
#include "white_balance.hpp"

#include <libraw/libraw.h>
#if defined(_OPENMP)
#include <omp.h>
#endif
#include <mutex>
#include <array>
#include <memory>

namespace {

constexpr const char* kTag = "SpektralabX";
thread_local std::string t_error;

// Separate from Session: cancellation must also cover decoding before spk_open.
// Kotlin joins the flag writer and waits for open to return before deleting it.
struct RawOpenCancellation {
    std::atomic<bool> cancelled{false};
};

struct RawHalfSizeBudget {
    explicit RawHalfSizeBudget(uint64_t value) { spk::io::set_raw_half_size_budget(value); }
    ~RawHalfSizeBudget() { spk::io::set_raw_half_size_budget(0); }
};

struct Session {
    spk_engine* engine = nullptr;
    spk_session* session = nullptr;
    std::atomic<uint64_t> cancel_generation{0};
    uint32_t width = 0, height = 0;
    uint32_t source_width = 0, source_height = 0;
    std::string make, model, kind;
    std::string input_precision, input_precision_note;
    spk::android::FloatDngMetadata float_source;
    // RAW only: the illuminant the camera recorded, and the one decoded with (0 = as shot).
    double as_shot_k = 0, as_shot_tint = 0, wb_k = 0, wb_tint = 0;
    spk::android::RawDevelopmentMetadata raw_metadata;
    spk::android::RawDevelopmentStats raw_stats;
    spk::android::RawDevelopment raw_development;
    bool raw_stats_binned = false;
    bool raw_stats_ready = false;
    std::vector<uint8_t> before;   // RGBA8, see make_before
    uint32_t before_w = 0, before_h = 0;
    // The same small frame before encoding, in the session's input space:
    // what the film preview cards render from (see thumbnail()).
    std::vector<float> small_rgb;
    uint32_t small_w = 0, small_h = 0;
    // Layer 2, the adjustments to the finished print (layer2.hpp). Applied while
    // a display or export render is packed; film cards show the film alone.
    spk::android::LookState look;
    const spk::android::Layer2* active_look() const { return look.active(); }
    // The last display render, kept so an adjustment re-packs it (repack)
    // instead of re-running the engine. One tier only: RGBA16 uses 8 bytes per
    // pixel; no-film RGB float uses 12. Replaced on the next successful render.
    spk_result last{};
    std::vector<float> last_float;
    uint64_t render_revision = 0, last_revision = 0;
    void keep(spk_result& r, std::vector<float>& rgb) {
        spk_result_free(&last);
        last = r;
        last_float = std::move(rgb);
        last_revision = render_revision;
        std::memset(&r, 0, sizeof r);
    }
    // The engine renders encoded ProPhoto RGB (spk_open's convention), and
    // the pixels leave it once, per destination, as upstream's hosts do
    // (output_transform.hpp). `out` is set by setOutput; a session that was
    // never told gets the sRGB display path, never ProPhoto codes in an sRGB
    // bitmap. `prophoto` is false only for a session opened in another space.
    bool prophoto = true;
    // 无 (passthrough.hpp): which render the params ask for, kept from the open
    // and every accepted set_params, since x_no_film never reaches the engine.
    spk::android::ViewFlags view;
    const spk::android::RawDevelopment* development() const {
        return kind == "raw" && view.no_film && raw_development.enabled() ? &raw_development : nullptr;
    }
    spk::android::OutputTransform out;
    const spk::android::OutputTransform* transform() {
        if (!out.ready()) {
            std::string e;
            if (!out.fetch(engine, "sRGB", false, e)) return nullptr;
        }
        return &out;
    }
    const spk::android::OutputTransform* xf() { return prophoto ? transform() : nullptr; }
    ~Session() { spk_result_free(&last); }
};

// True when `params` (an open's JSON) leaves the engine in its ProPhoto output.
bool renders_prophoto(const std::string& params) {
    return params.find("\"output_color_space\"") == std::string::npos ||
           params.find("\"output_color_space\":\"ProPhoto RGB\"") != std::string::npos;
}

// Every engine and the sessions open on it. spk_engine_destroy deletes the
// sessions it still holds, so an engine the GPU lost cannot be destroyed while
// an editor has a photo open on it: `destroy` retires it instead, and the last
// session's release frees it (EngineHost.recover). A handle to an engine that
// is gone is refused rather than dereferenced.
std::mutex g_engines_lock;
std::unordered_map<spk_engine*, int> g_sessions;   // live engine -> open sessions
std::unordered_set<spk_engine*> g_retired;         // destroy asked, sessions still open

bool engine_known(spk_engine* e) {
    std::lock_guard<std::mutex> guard(g_engines_lock);
    return e && g_sessions.count(e) != 0;
}

// Known and not retired: what a new session or a warm-up may use.
bool engine_usable(spk_engine* e) {
    std::lock_guard<std::mutex> guard(g_engines_lock);
    return e && g_sessions.count(e) != 0 && g_retired.count(e) == 0;
}

void session_opened(spk_engine* e) {
    std::lock_guard<std::mutex> guard(g_engines_lock);
    ++g_sessions[e];
}

void session_closed(spk_engine* e) {
    bool destroy = false;
    {
        std::lock_guard<std::mutex> guard(g_engines_lock);
        auto it = g_sessions.find(e);
        if (it == g_sessions.end()) return;
        if (--it->second <= 0 && g_retired.erase(e)) {
            g_sessions.erase(it);
            destroy = true;
        }
    }
    if (destroy) spk_engine_destroy(e);
}

// Owns a Session until it is handed to Kotlin, so an exception between the
// engine open and the return never leaks the session's GPU buffers.
struct SessionDeleter {
    void operator()(Session* s) const {
        if (!s) return;
        spk_engine* engine = s->engine;
        spk_result_free(&s->last);   // before its session
        spk_session_release(s->session);
        delete s;
        session_closed(engine);
    }
};
using SessionPtr = std::unique_ptr<Session, SessionDeleter>;

// A render result freed on every exit (spk_result_free is a no-op once freed).
struct ResultGuard {
    spk_result r;
    std::vector<float> float_rgb;
    ResultGuard() { std::memset(&r, 0, sizeof r); }
    ~ResultGuard() { spk_result_free(&r); }
    ResultGuard(const ResultGuard&) = delete;
    ResultGuard& operator=(const ResultGuard&) = delete;
};

// Bitmap pixels locked for exactly this scope.
struct PixelLock {
    JNIEnv* env;
    jobject bitmap;
    void* pixels = nullptr;
    bool ok = false;
    PixelLock(JNIEnv* e, jobject b) : env(e), bitmap(b) {
        ok = b && AndroidBitmap_lockPixels(e, b, &pixels) == ANDROID_BITMAP_RESULT_SUCCESS && pixels;
    }
    ~PixelLock() { if (ok) AndroidBitmap_unlockPixels(env, bitmap); }
    PixelLock(const PixelLock&) = delete;
    PixelLock& operator=(const PixelLock&) = delete;
};

// Runs body(y0, y1) over [0, rows) on up to `workers` threads, the caller
// taking the first slice. If a thread cannot be created (memory or thread
// limits), the rows it would have taken run on the caller instead: a vector
// of joinable threads must never be unwound, that is std::terminate.
template <typename F>
void run_rows(uint32_t rows, uint32_t workers, F&& body) {
    if (rows == 0) return;
    workers = std::max(1u, workers);
    const uint32_t chunk = (rows + workers - 1) / workers;
    std::vector<std::thread> threads;
    uint32_t spawned_to = std::min(rows, chunk);
    try {
        threads.reserve(workers);
        for (uint32_t y = chunk; y < rows; y += chunk) {
            const uint32_t y1 = std::min(rows, y + chunk);
            threads.emplace_back(body, y, y1);
            spawned_to = y1;
        }
    } catch (...) {
        // Fall through: whatever was not spawned runs here.
    }
    body(0u, std::min(rows, chunk));
    if (spawned_to < rows) body(spawned_to, rows);
    for (auto& t : threads) t.join();
}

// LibRaw's make/model are raw bytes; NewStringUTF aborts (under CheckJNI) on
// anything that is not valid modified UTF-8. Keep valid UTF-8, replace the rest.
std::string utf8_clean(const std::string& s) {
    std::string out;
    for (size_t i = 0; i < s.size();) {
        const unsigned char c = static_cast<unsigned char>(s[i]);
        size_t n = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 0;
        bool ok = n > 0 && i + n <= s.size() && c != 0;
        for (size_t k = 1; ok && k < n; ++k) ok = (static_cast<unsigned char>(s[i + k]) >> 6) == 0x2;
        if (ok) { out.append(s, i, n); i += n; }
        else { out += '?'; i += 1; }
    }
    return out;
}

std::string str(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

jstring jstr(JNIEnv* env, const std::string& s) { return env->NewStringUTF(utf8_clean(s).c_str()); }

std::string json_escape(const std::string& s) {
    std::string out;
    for (unsigned char c : s) {
        if (c == '"' || c == '\\') { out += '\\'; out += char(c); }
        else if (c < 0x20) { char buf[8]; std::snprintf(buf, sizeof buf, "\\u%04x", c); out += buf; }
        else out += char(c);
    }
    return out;
}

void fail(const std::string& message) {
    t_error = message;
    __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", message.c_str());
}

std::string engine_error(const char* what) {
    const char* e = spk_last_error(nullptr);
    return std::string(what) + ": " + (e && *e ? e : "unknown engine error");
}

// VK_ERROR_DEVICE_LOST (VkResult -4): the GPU was reset, usually by the
// kernel driver's watchdog. Nothing on that VkDevice works again; the engine
// has to be replaced. The Vulkan backend reports it as "... (VkResult -4)".
bool device_lost(const std::string& e) {
    return e.find("(VkResult -4)") != std::string::npos ||
           e.find("VK_ERROR_DEVICE_LOST") != std::string::npos;
}

// Fits (w, h) inside max_edge on its long side and inside max_pixels in area;
// 0 means no limit. The pixel cap comes from the GPU's maxStorageBufferRange:
// one storage buffer holds a whole plane, so a frame larger than the range
// cannot be rendered at all.
void fit(uint32_t w, uint32_t h, uint32_t max_edge, uint64_t max_pixels, uint32_t& nw, uint32_t& nh) {
    double s = 1.0;
    if (max_edge && std::max(w, h) > max_edge) s = double(max_edge) / double(std::max(w, h));
    if (max_pixels && double(w) * h * s * s > double(max_pixels))
        s = std::sqrt(double(max_pixels) / (double(w) * h));
    nw = w; nh = h;
    if (s >= 1.0) return;
    nw = std::max<uint32_t>(1, uint32_t(std::floor(w * s)));
    nh = std::max<uint32_t>(1, uint32_t(std::floor(h * s)));
}

spk::android::RawDevelopmentMetadata development_metadata(const spk::io::RawDngRenderMetadata& src) {
    spk::android::RawDevelopmentMetadata out;
    out.baseline_exposure = src.baseline_exposure;
    out.baseline_exposure_present = src.baseline_exposure_present;
    out.baseline_exposure_invalid = src.baseline_exposure_invalid;
    out.baseline_exposure_offset = src.baseline_exposure_offset;
    out.baseline_exposure_offset_present = src.baseline_exposure_offset_present;
    out.baseline_exposure_offset_invalid = src.baseline_exposure_offset_invalid;
    out.default_black_render = src.default_black_render;
    out.default_black_render_present = src.default_black_render_present;
    out.default_black_render_invalid = src.default_black_render_invalid;
    out.profile_tone_curve = src.profile_tone_curve;
    out.profile_tone_curve_present = src.profile_tone_curve_present;
    out.profile_tone_curve_invalid = src.profile_tone_curve_invalid;
    out.profile_dynamic_range = src.profile_dynamic_range;
    out.profile_dynamic_range_present = src.profile_dynamic_range_present;
    out.profile_dynamic_range_invalid = src.profile_dynamic_range_invalid;
    out.extra_camera_profiles = src.extra_camera_profiles;
    out.profile_source_mismatch = src.profile_source_mismatch;
    return out;
}

Session* open_pixels(spk_engine* engine, std::vector<float>& rgb, uint32_t w, uint32_t h,
                     std::string params) {
    if (!engine_usable(engine)) { fail("open image: the engine was replaced after a GPU fault"); return nullptr; }
    spk::android::ViewFlags view;
    std::string bad;
    if (!spk::android::take_app_keys(params, view, bad)) { fail("open image: " + bad); return nullptr; }
    // Allocate and initialise the wrapper before opening GPU resources. Keep
    // the engine handle owned until registration succeeds, including on OOM.
    auto session = std::make_unique<Session>();
    session->width = w;
    session->height = h;
    session->prophoto = renders_prophoto(params);
    session->view = view;
    const spk_image input{rgb.data(), w, h, 3};
    char* reply = nullptr;
    using NativeSessionPtr = std::unique_ptr<spk_session, decltype(&spk_session_release)>;
    NativeSessionPtr opened(spk_open(engine, &input, params.c_str(), &reply), &spk_session_release);
    if (reply) spk_string_free(reply);
    if (!opened) { fail(engine_error("open image")); return nullptr; }
    session_opened(engine);
    session->engine = engine;
    session->session = opened.release();
    return session.release();
}

// RGBA16 rows (engine-encoded sRGB) to RGBA8, split across up to four
// threads: at the 3400 px tier it is ~8 M pixels, a visible slice of a drag.
void pack_rgba8(const spk_result& r, uint8_t* out, size_t stride, const spk::android::Layer2* look = nullptr,
                const spk::android::OutputTransform* xf = nullptr, const float* rgb = nullptr,
                const spk::android::OutputCancel& cancel = {}, const spk::android::RawDevelopment* development = nullptr) {
    cancel.check();
    if (rgb) {
        if (!xf || !xf->ready()) throw std::runtime_error("no-film float output transform unavailable");
        spk::android::pack_float_rgba8(rgb, r.row_stride_px, r.width, r.height, out, stride, look, *xf, cancel, development);
        return;
    }
    if (xf) { spk::android::pack_rgba8(r.rgba16, r.row_stride_px, r.width, r.height, out, stride, look, *xf, cancel); return; }
    if (look) { spk::android::pack_rgba8_look(*look, r.rgba16, r.row_stride_px, r.width, r.height, out, stride, cancel); return; }
    const auto rows = [&](uint32_t y0, uint32_t y1) {
        for (uint32_t y = y0; y < y1; ++y) {
            if (cancel.cancelled()) return;
            const uint16_t* src = r.rgba16 + size_t(y) * r.row_stride_px * 4;
            uint8_t* dst = out + size_t(y) * stride;
            for (uint32_t x = 0; x < r.width; ++x) {
                dst[4 * x + 0] = uint8_t((uint32_t(src[4 * x + 0]) + 128u) / 257u);
                dst[4 * x + 1] = uint8_t((uint32_t(src[4 * x + 1]) + 128u) / 257u);
                dst[4 * x + 2] = uint8_t((uint32_t(src[4 * x + 2]) + 128u) / 257u);
                dst[4 * x + 3] = 255;
            }
        }
    };
    const uint64_t px = uint64_t(r.width) * r.height;
    const uint32_t workers = px < 1'000'000 ? 1u : std::min(4u, std::max(1u, std::thread::hardware_concurrency()));
    run_rows(r.height, workers, rows);
    cancel.check();
}

// RGBA16 rows (engine-encoded sRGB) into an ARGB_8888 Bitmap, whose memory
// order is R, G, B, A.
jobject to_bitmap(JNIEnv* env, const spk_result& r, const spk::android::Layer2* look = nullptr,
                  const spk::android::OutputTransform* xf = nullptr, const float* rgb = nullptr,
                  const spk::android::OutputCancel& cancel = {}, const spk::android::RawDevelopment* development = nullptr) {
    cancel.check();
    jclass bitmap_class = env->FindClass("android/graphics/Bitmap");
    jclass config_class = env->FindClass("android/graphics/Bitmap$Config");
    if (!bitmap_class || !config_class) { fail("Bitmap classes not found"); return nullptr; }
    jfieldID argb_field = env->GetStaticFieldID(config_class, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    jobject argb = env->GetStaticObjectField(config_class, argb_field);
    jmethodID create = env->GetStaticMethodID(bitmap_class, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jobject bitmap = env->CallStaticObjectMethod(bitmap_class, create, jint(r.width), jint(r.height), argb);
    if (env->ExceptionCheck() || !bitmap) {
        env->ExceptionClear();
        fail("cannot allocate a " + std::to_string(r.width) + "x" + std::to_string(r.height) + " bitmap");
        return nullptr;
    }
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        fail("cannot read bitmap info");
        return nullptr;
    }
    PixelLock lock(env, bitmap);
    if (!lock.ok) { fail("cannot lock bitmap pixels"); return nullptr; }
    pack_rgba8(r, static_cast<uint8_t*>(lock.pixels), info.stride, look, xf, rgb, cancel, development);
    cancel.check();
    return bitmap;
}

float srgb_encode(float v) {
    v = std::min(1.0f, std::max(0.0f, v));
    return v <= 0.0031308f ? 12.92f * v : 1.055f * std::pow(v, 1.0f / 2.4f) - 0.055f;
}

// The "before" view: a small RGBA8 thumbnail of the frame as it went into the
// engine. RAW arrives as linear ProPhoto (D50) and is converted to encoded sRGB
// (Bradford to D65); a gallery photo is already encoded and is only scaled.
constexpr uint32_t kBeforeEdge = 1600;
constexpr uint32_t kThumbEdge = 384;

float srgb_decode(float v) {
    v = std::min(1.0f, std::max(0.0f, v));
    return v <= 0.04045f ? v / 12.92f : std::pow((v + 0.055f) / 1.055f, 2.4f);
}

// `display_p3`: an encoded Display P3 photo, shown here in sRGB (the before
// bitmap is sRGB), so press-and-hold does not look desaturated against the print.
// `linear`: the values are already linear (an Ultra HDR recovery), not encoded.
void make_before(Session* s, const std::vector<float>& rgb, uint32_t w, uint32_t h, bool linear_prophoto,
                 bool display_p3 = false, bool linear = false) {
    uint32_t nw = w, nh = h;
    fit(w, h, kBeforeEdge, 0, nw, nh);
    std::vector<float> small;
    const std::vector<float>* src = &rgb;
    if (nw != w || nh != h) { spk::android::resize_area_rgb(rgb, w, h, nw, nh, small); src = &small; }
    static constexpr float m[9] = {
        2.0341926f, -0.7274198f, -0.3067728f,
        -0.2288151f, 1.2317584f, -0.0029433f,
        -0.0085497f, -0.1532550f, 1.1618047f,
    };
    s->before.assign(size_t(nw) * nh * 4, 255);
    for (size_t i = 0, n = size_t(nw) * nh; i < n; ++i) {
        const float* p = src->data() + 3 * i;
        float r = p[0], g = p[1], b = p[2];
        if (linear_prophoto) {
            const float rr = m[0] * r + m[1] * g + m[2] * b;
            const float gg = m[3] * r + m[4] * g + m[5] * b;
            const float bb = m[6] * r + m[7] * g + m[8] * b;
            r = srgb_encode(rr); g = srgb_encode(gg); b = srgb_encode(bb);
        } else if (display_p3 || linear) {
            const float lr = linear ? r : srgb_decode(r);
            const float lg = linear ? g : srgb_decode(g);
            const float lb = linear ? b : srgb_decode(b);
            if (display_p3) {
                // Display P3 (D65) -> sRGB, linear, then the shared sRGB curve.
                r = srgb_encode(1.2249401f * lr - 0.2249404f * lg);
                g = srgb_encode(-0.0420569f * lr + 1.0420571f * lg);
                b = srgb_encode(-0.0196376f * lr - 0.0786361f * lg + 1.0982735f * lb);
            } else {
                r = srgb_encode(lr); g = srgb_encode(lg); b = srgb_encode(lb);
            }
        }
        s->before[4 * i + 0] = uint8_t(std::lround(std::min(1.0f, std::max(0.0f, r)) * 255.0f));
        s->before[4 * i + 1] = uint8_t(std::lround(std::min(1.0f, std::max(0.0f, g)) * 255.0f));
        s->before[4 * i + 2] = uint8_t(std::lround(std::min(1.0f, std::max(0.0f, b)) * 255.0f));
    }
    s->before_w = nw;
    s->before_h = nh;

    // Film cards: a still smaller copy, unencoded, so each card opens a
    // throwaway session on it in the same input space as the real one.
    uint32_t tw = nw, th = nh;
    fit(nw, nh, kThumbEdge, 0, tw, th);
    if (tw != nw || th != nh) spk::android::resize_area_rgb(*src, nw, nh, tw, th, s->small_rgb);
    else s->small_rgb = *src;
    s->small_w = tw;
    s->small_h = th;
}

bool render(Session* s, const std::string& tier, spk_result& out, std::vector<float>& rgb,
            const spk::android::OutputCancel& cancel = {}) {
    cancel.check();
    std::memset(&out, 0, sizeof out);
    rgb.clear();
    if (s->view.no_film || s->view.di()) {
        // 无: the decoded photo, or a negative's Digital Intermediate through
        // its view table, from upstream's view path (passthrough.hpp).
        std::string error;
        const bool ok = s->view.no_film
            ? spk::android::render_view_float(s->session, tier, out, rgb, error, cancel)
            : spk::android::render_view(s->session, tier, false, out, error, cancel);
        if (!ok) {
            spk_result_free(&out);
            fail("render " + tier + ": " + error);
            return false;
        }
        cancel.check();
        return true;
    }
    // spk_reprint reuses the cached negative when the last edit was print-side
    // and develops a new one otherwise, so it is right after every edit.
    const spk_status status = spk_reprint(s->session, tier.c_str(), &out);
    if (status != SPK_OK) { fail(engine_error(("render " + tier).c_str())); return false; }
    if (!out.rgba16 || !out.width || !out.height || out.row_stride_px < out.width) {
        spk_result_free(&out);
        fail("render returned no pixels");
        return false;
    }
    cancel.check();
    return true;
}

// The pre-save check on a finished RGBA16 frame: a frame of one flat colour
// (black, white or any other) came from a failed render, not from a photo:
// the pack maps NaN to 0, so a NaN frame is black, a corrupt GPU submit
// leaves zeros or one repeated value. Samples a grid of at most ~64 k pixels.
// Empty when the frame is fine.
std::string blank_frame(const uint16_t* rgba, uint32_t width, uint32_t height, size_t stride_px) {
    if (!rgba || !width || !height) return "no pixels";
    const uint32_t step_x = std::max<uint32_t>(1, width / 256), step_y = std::max<uint32_t>(1, height / 256);
    uint16_t lo[3] = {65535, 65535, 65535}, hi[3] = {0, 0, 0};
    for (uint32_t y = 0; y < height; y += step_y) {
        const uint16_t* row = rgba + size_t(y) * stride_px * 4;
        for (uint32_t x = 0; x < width; x += step_x)
            for (int c = 0; c < 3; ++c) {
                const uint16_t v = row[4 * x + c];
                lo[c] = std::min(lo[c], v);
                hi[c] = std::max(hi[c], v);
            }
    }
    // 16 of 65535 is below one 8-bit step: only an exactly flat frame fails.
    constexpr uint16_t kFlat = 16;
    if (hi[0] <= kFlat && hi[1] <= kFlat && hi[2] <= kFlat) return "the frame is completely black";
    if (lo[0] >= 65535 - kFlat && lo[1] >= 65535 - kFlat && lo[2] >= 65535 - kFlat) return "the frame is completely white";
    if (hi[0] - lo[0] <= kFlat && hi[1] - lo[1] <= kFlat && hi[2] - lo[2] <= kFlat) {
        return "the frame is one solid colour (" + std::to_string(lo[0]) + ", " + std::to_string(lo[1]) + ", " +
               std::to_string(lo[2]) + ")";
    }
    return {};
}

// Float no-film results retain HDR and signed channels. Validate those values
// before grading rather than mistaking recoverable highlights for flat white.
std::string blank_float_frame(const std::vector<float>& rgb, const spk_result& r) {
    if (!r.width || !r.height || rgb.size() != size_t(r.width) * r.height * 3) return "no float pixels";
    spk::android::FloatFrameCheck check;
    check.append(rgb.data(), r.width, r.width, r.height, 0, r.height);
    return check.finish();
}

std::string blank_result(const ResultGuard& result) {
    return result.float_rgb.empty()
        ? blank_frame(result.r.rgba16, result.r.width, result.r.height, result.r.row_stride_px)
        : blank_float_frame(result.float_rgb, result.r);
}

// LibRaw's OpenMP workers: the performance cores only. A core counts when its
// top clock is at least 70% of the fastest core's, which drops the efficiency
// cluster on every Snapdragon from the 865 (Mi 10) to the 8 Elite (all-Oryon,
// kept whole). Upstream's RawDecodeThreadScope reads the count from
// SPEKTRALAB_RAW_THREADS on each decode. KMP_BLOCKTIME=0 is read when libomp
// starts (the first decode); kmp_set_blocktime covers the calling thread's
// teams too. Workers then sleep as soon as a parallel loop ends instead of
// spinning for 200 ms on cores the GPU render and the UI need.
void configure_raw_threads() {
    static std::once_flag once;
    std::call_once(once, [] {
        std::vector<long> khz;
        for (unsigned cpu = 0; cpu < 64; ++cpu) {
            std::ifstream f("/sys/devices/system/cpu/cpu" + std::to_string(cpu) + "/cpufreq/cpuinfo_max_freq");
            long v = 0;
            if (!(f >> v)) break;
            khz.push_back(v);
        }
        const unsigned all = std::max(1u, std::thread::hardware_concurrency());
        unsigned big = 0;
        if (!khz.empty() && khz.size() == all) {
            const long top = *std::max_element(khz.begin(), khz.end());
            for (long v : khz) big += v * 10 >= top * 7;
        }
        if (big == 0) big = std::max(1u, all / 2);
        setenv("SPEKTRALAB_RAW_THREADS", std::to_string(std::min(big, 8u)).c_str(), 1);
        setenv("KMP_BLOCKTIME", "0", 0);
        __android_log_print(ANDROID_LOG_INFO, kTag, "raw decode: %u OpenMP workers of %u cores", std::min(big, 8u), all);
    });
#if defined(_OPENMP)
    kmp_set_blocktime(0);
#endif
}

}  // namespace

#define JNI_FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_dearded_spektralab_NativeEngine_##name

JNI_FN(jstring, lastError)(JNIEnv* env, jobject) { return jstr(env, t_error); }

// The storage-buffer size this GPU was measured to address, up to `ceiling`
// bytes (storage_probe.cpp); 0 when even 128 MiB failed. Its own VkInstance
// and VkDevice, so call it before create, once per driver. lastError has the log.
JNI_FN(jlong, probeStorageRange)(JNIEnv*, jobject, jlong ceiling) {
    try {
        std::string log;
        const uint64_t bytes = spk::android::probe_storage_range(uint64_t(std::max<jlong>(0, ceiling)), log);
        t_error = log;
        __android_log_print(ANDROID_LOG_INFO, kTag, "storage probe: %s", log.c_str());
        return jlong(bytes);
    } catch (...) {
        fail("storage probe: unknown failure");
        return 0;
    }
}

JNI_FN(jstring, deviceInfo)(JNIEnv* env, jobject) {
    // Queried through vkGetInstanceProcAddr directly, so volk's global table
    // (which the engine loads for its own instance) is not touched.
    if (volkInitialize() != VK_SUCCESS) return jstr(env, "{\"vulkan\":false}");
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.apiVersion = VK_API_VERSION_1_0;
    VkInstanceCreateInfo create{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    create.pApplicationInfo = &app;
    VkInstance instance = VK_NULL_HANDLE;
    if (vkCreateInstance(&create, nullptr, &instance) != VK_SUCCESS) return jstr(env, "{\"vulkan\":false}");
    auto enumerate = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(
        vkGetInstanceProcAddr(instance, "vkEnumeratePhysicalDevices"));
    auto properties = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(
        vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceProperties"));
    auto destroy = reinterpret_cast<PFN_vkDestroyInstance>(vkGetInstanceProcAddr(instance, "vkDestroyInstance"));
    std::string out = "{\"vulkan\":true,\"devices\":[";
    uint32_t count = 0;
    if (enumerate && properties && enumerate(instance, &count, nullptr) == VK_SUCCESS && count) {
        std::vector<VkPhysicalDevice> devices(count);
        enumerate(instance, &count, devices.data());
        for (uint32_t i = 0; i < count; ++i) {
            VkPhysicalDeviceProperties p{};
            properties(devices[i], &p);
            if (i) out += ",";
            out += "{\"name\":\"" + json_escape(p.deviceName) + "\"";
            out += ",\"api\":\"" + std::to_string(VK_API_VERSION_MAJOR(p.apiVersion)) + "." +
                   std::to_string(VK_API_VERSION_MINOR(p.apiVersion)) + "." +
                   std::to_string(VK_API_VERSION_PATCH(p.apiVersion)) + "\"";
            out += ",\"driver\":" + std::to_string(p.driverVersion);
            out += ",\"maxStorageBufferRange\":" + std::to_string(p.limits.maxStorageBufferRange);
            out += ",\"maxComputeWorkGroupCountX\":" + std::to_string(p.limits.maxComputeWorkGroupCount[0]);
            out += "}";
        }
    }
    out += "]}";
    if (destroy) destroy(instance, nullptr);
    return jstr(env, out);
}

JNI_FN(jlong, create)(JNIEnv* env, jobject, jstring resources, jstring tmp_dir) {
    try {
        // The engine's residency snapshot uses tmpfile(); bionic falls back to
        // $TMPDIR when /data/local/tmp is not writable, which it never is here.
        const std::string tmp = str(env, tmp_dir);
        if (!tmp.empty()) setenv("TMPDIR", tmp.c_str(), 1);
        const std::string dir = str(env, resources);
        spk_engine* engine = spk_engine_create(dir.c_str(), nullptr);
        if (!engine) { fail(engine_error("engine")); return 0; }
        {
            std::lock_guard<std::mutex> guard(g_engines_lock);
            g_sessions[engine] = 0;
        }
        return reinterpret_cast<jlong>(engine);
    } catch (const std::exception& e) {
        fail(std::string("engine: ") + e.what());
    } catch (...) {
        fail("engine: unknown failure");
    }
    return 0;
}

// Destroys the engine now or, while sessions are still open on it, retires
// it: no new session opens on it and the last release frees it.
JNI_FN(void, destroy)(JNIEnv*, jobject, jlong engine_handle) {
    auto* engine = reinterpret_cast<spk_engine*>(engine_handle);
    {
        std::lock_guard<std::mutex> guard(g_engines_lock);
        auto it = g_sessions.find(engine);
        if (it == g_sessions.end()) return;   // unknown, or already gone
        if (it->second > 0) { g_retired.insert(engine); return; }
        g_sessions.erase(it);
        g_retired.erase(engine);
    }
    spk_engine_destroy(engine);
}

JNI_FN(jstring, capabilities)(JNIEnv* env, jobject, jlong engine) {
    if (!engine_known(reinterpret_cast<spk_engine*>(engine))) return jstr(env, "{}");
    const char* caps = spk_capabilities(reinterpret_cast<spk_engine*>(engine));
    return jstr(env, caps ? caps : "{}");
}

static jlong open_raw(JNIEnv* env, jlong engine_handle, jstring path_j, jint max_edge,
                      jlong max_pixels, jstring params_j, jdouble temperature_k, jdouble tint,
                      const spk::android::FloatDngCancel& cancelled) {
    try {
        spk::android::float_dng_detail::check_cancel(cancelled);
        auto* engine = reinterpret_cast<spk_engine*>(engine_handle);
        const std::string path = str(env, path_j);
        // Half-size decode (2x2 binning) only when the binned frame still covers
        // the size the frame is fitted to on both edges, whatever the sensor's
        // shape; decided below from the sensor size the metadata open reads.
        uint32_t sensor_w = 0, sensor_h = 0;
        bool sensor_turned = false;
        // White balance is chosen at decode (LibRaw's user_mul), as upstream's
        // desktop host does: the metadata-only open reads the as-shot
        // illuminant and, for a custom one, derives the multipliers.
        double as_shot_k = 0, as_shot_tint = 0;
        std::array<float, 4> mul{};
        const bool custom_wb = temperature_k > 0;
        std::string error;
        spk::android::RawPrecisionPlan precision;
        precision.mode = "integer_compatible16";
        precision.custom_whitebalance = custom_wb;
        {
            auto probe = std::make_unique<spk::io::detail::CameraLibRaw>();
            probe->imgdata.params.use_camera_matrix = 1;
            const int opened = probe->open_file(path.c_str());
            spk::android::float_dng_detail::check_cancel(cancelled);
            if (opened == LIBRAW_SUCCESS) {
                precision = spk::android::inspect_raw_precision(path, *probe, custom_wb, cancelled);
                if (precision.mode == "float_linear_v1") {
                    // The precise path normalizes each original plane once. Its
                    // illuminant must not reuse LibRaw's integer-range-adjusted WB.
                    for (int c = 0; c < 3; ++c)
                        probe->imgdata.color.cam_mul[c] = float(1.0 / precision.source.neutral[c]);
                    probe->imgdata.color.cam_mul[3] = probe->imgdata.color.cam_mul[1];
                }
                // DNG ColorMatrix tags can exist while LibRaw's cam_xyz is empty.
                // Use the decoder's calibration before deriving temperature/tint.
                probe->interpolate_dng_matrix(nullptr);
                sensor_w = probe->imgdata.sizes.width;
                sensor_h = probe->imgdata.sizes.height;
                sensor_turned = (probe->imgdata.sizes.flip & 4) != 0;
                if (!spkhost::as_shot_temperature_tint(*probe, as_shot_k, as_shot_tint) ||
                    !std::isfinite(as_shot_k) || !std::isfinite(as_shot_tint)) {
                    as_shot_k = 0; as_shot_tint = 0;
                }
                if (custom_wb && !spk::android::raw_multipliers_for(*probe, temperature_k, tint, mul, error)) {
                    fail("white balance: " + error); return 0;
                }
            } else if (custom_wb) {
                fail("white balance: cannot read the RAW metadata"); return 0;
            }
        }
        spk::android::float_dng_detail::check_cancel(cancelled);
        std::string opening = str(env, params_j), checked = opening;
        spk::android::ViewFlags opening_view;
        if (!spk::android::take_app_keys(checked, opening_view, error)) { fail("open RAW: " + error); return 0; }
        bool binned = false;
        if (sensor_w && sensor_h) {
            uint32_t fw = 0, fh = 0;
            fit(sensor_w, sensor_h, uint32_t(std::max(0, int(max_edge))), uint64_t(std::max<jlong>(0, max_pixels)), fw, fh);
            binned = sensor_w / 2 >= fw && sensor_h / 2 >= fh;
        }
        // Standard development meters the complete unbinned source, independent
        // of preview/export working size. Neutral keeps its existing fast decode.
        if (opening_view.no_film && opening_view.raw_development.enabled) binned = false;
        if (precision.mode == "float_linear_v1") binned = false;
        // The patch bins when area > 4 x budget (Bayer only): 1 forces it, 0 never.
        configure_raw_threads();
        spk::io::DecodedRaw decoded;
        spk::android::FloatDngMetadata float_source;
        bool ok;
        {
            RawHalfSizeBudget budget(binned ? 1 : 0);
            ok = spk::android::decode_raw_precision(path, precision, custom_wb ? &mul : nullptr,
                custom_wb ? double(temperature_k) : 0.0, decoded, error, &float_source, cancelled);
        }
        spk::android::float_dng_detail::check_cancel(cancelled);
        if (!ok) { fail("RAW decode: " + error); return 0; }
        const auto raw_metadata = development_metadata(decoded.metadata.dng_render);
        spk::android::RawDevelopmentStats raw_stats;
        const bool raw_stats_ready = spk::android::prepare_raw_development_stats(
            opening_view.raw_development, opening_view.no_film, decoded.rgb.data(),
            decoded.width, decoded.width, decoded.height, raw_stats);
        spk::android::float_dng_detail::check_cancel(cancelled);
        auto development_options = opening_view.raw_development;
        development_options.enabled = development_options.enabled && opening_view.no_film;
        auto development = spk::android::RawDevelopment::build(raw_metadata, raw_stats, development_options);
        const uint32_t sw = decoded.width, sh = decoded.height;
        uint32_t w = sw, h = sh;
        fit(sw, sh, uint32_t(std::max(0, int(max_edge))), uint64_t(std::max<jlong>(0, max_pixels)), w, h);
        std::vector<float> rgb;
        if (w != sw || h != sh) {
            spk::android::resize_area_rgb(decoded.rgb, sw, sh, w, h, rgb);
            std::vector<float>().swap(decoded.rgb);
        } else {
            rgb.swap(decoded.rgb);
        }
        spk::android::float_dng_detail::check_cancel(cancelled);
        SessionPtr session(open_pixels(engine, rgb, w, h, opening));
        // open_pixels completes its native work before a cancelled candidate is
        // destroyed; never throw from an in-flight GPU callback.
        spk::android::float_dng_detail::check_cancel(cancelled);
        if (!session) return 0;
        session->raw_metadata = raw_metadata;
        session->raw_stats = raw_stats;
        session->raw_development = std::move(development);
        session->raw_stats_binned = binned;
        session->raw_stats_ready = raw_stats_ready;
        make_before(session.get(), rgb, w, h, /*linear_prophoto=*/true);
        // The sensor's size, not the binned decode's, in display orientation.
        if (sensor_w && sensor_h && binned) {
            session->source_width = sensor_turned ? sensor_h : sensor_w;
            session->source_height = sensor_turned ? sensor_w : sensor_h;
        } else {
            session->source_width = sw;
            session->source_height = sh;
        }
        session->make = decoded.metadata.make;
        session->model = decoded.metadata.model;
        session->kind = "raw";
        session->input_precision = precision.mode;
        session->input_precision_note = precision.note;
        if (precision.mode == "float_linear_v1") session->float_source = float_source;
        session->as_shot_k = as_shot_k;
        session->as_shot_tint = as_shot_tint;
        session->wb_k = custom_wb ? double(temperature_k) : 0;
        session->wb_tint = custom_wb ? double(tint) : 0;
        spk::android::float_dng_detail::check_cancel(cancelled);
        return reinterpret_cast<jlong>(session.release());
    } catch (const spk::android::FloatDngCancelled&) {
        fail("RAW open cancelled");
    } catch (const std::bad_alloc&) {
        fail("out of memory while opening the RAW file; choose a smaller working size");
    } catch (const std::exception& e) {
        fail(std::string("open RAW: ") + e.what());
    } catch (...) {
        fail("open RAW: unknown failure");
    }
    return 0;
}

JNI_FN(jlong, openRaw)(JNIEnv* env, jobject, jlong engine_handle, jstring path_j, jint max_edge,
                       jlong max_pixels, jstring params_j, jdouble temperature_k, jdouble tint) {
    return open_raw(env, engine_handle, path_j, max_edge, max_pixels, params_j, temperature_k, tint, {});
}

JNI_FN(jlong, createRawOpenCancellation)(JNIEnv*, jobject) {
    try { return reinterpret_cast<jlong>(new RawOpenCancellation); }
    catch (...) { fail("cannot allocate RAW cancellation token"); return 0; }
}

JNI_FN(void, cancelRawOpen)(JNIEnv*, jobject, jlong cancellation) {
    auto* token = reinterpret_cast<RawOpenCancellation*>(cancellation);
    if (token) token->cancelled.store(true, std::memory_order_relaxed);
}

JNI_FN(void, releaseRawOpenCancellation)(JNIEnv*, jobject, jlong cancellation) {
    delete reinterpret_cast<RawOpenCancellation*>(cancellation);
}

JNI_FN(jlong, openRawCancellableV1)(JNIEnv* env, jobject, jlong engine_handle, jstring path_j, jint max_edge,
                                  jlong max_pixels, jstring params_j, jdouble temperature_k, jdouble tint,
                                  jlong cancellation) {
    auto* token = reinterpret_cast<RawOpenCancellation*>(cancellation);
    if (!token) { fail("RAW open requires a cancellation token"); return 0; }
    return open_raw(env, engine_handle, path_j, max_edge, max_pixels, params_j, temperature_k, tint,
                    [token] { return token->cancelled.load(std::memory_order_relaxed); });
}

// Undoes the highlight shoulder of a phone's tone curve, for a JPEG that has no
// gain map: linear values up to the knee (middle grey) pass unchanged, above it
// they are expanded so encoded white lands `stops` above 1.0. It is the exact
// inverse of y = k + u / (1 + u / w), whose slope is 1 at the knee, so the
// midtones keep their exposure and only the compressed highlights get their
// range back for the film's own shoulder. Applied to the largest channel and
// scaled through all three, so hues do not shift.
struct HighlightExpand {
    float knee = 0.18f, w = 0.0f;
    explicit HighlightExpand(float stops) {
        const float d = std::exp2(stops) - knee, r = 1.0f - knee;
        w = d > r ? d * r / (d - r) : 0.0f;
    }
    bool active() const { return w > 0.0f; }
    void apply(float& r, float& g, float& b) const {
        const float m = std::max(r, std::max(g, b));
        if (m <= knee) return;
        const float u = std::min(m - knee, (1.0f - knee));
        const float denom = std::max(1e-4f, 1.0f - u / w);
        const float k = (knee + u / denom) / m;
        r *= k; g *= k; b *= k;
    }
};

// `bitmap` is ARGB_8888 in `space_j` ("sRGB" or "Display P3"), already scaled
// by the caller. Its curve and primaries are taken off here, as upstream's host
// does (input_transform.hpp), so the engine opens on linear ProPhoto and
// [params_j] must say so. `headroom_stops` > 0 also expands the highlights
// (HighlightExpand) in the photo's own primaries first.
JNI_FN(jlong, openBitmap)(JNIEnv* env, jobject, jlong engine_handle, jobject bitmap, jstring space_j,
                          jstring params_j, jfloat headroom_stops, jint source_width, jint source_height) {
    try {
        AndroidBitmapInfo info{};
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
            info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
            fail("image must be an ARGB_8888 bitmap"); return 0;
        }
        const std::string params = str(env, params_j);
        const spk::android::InputTransform xf(str(env, space_j));
        const HighlightExpand expand(std::isfinite(headroom_stops) ? std::clamp(float(headroom_stops), 0.0f, 3.0f) : 0.0f);
        // Allocated before the pixels are locked: a bad_alloc here must not
        // leave the bitmap pinned (Kotlin's recycle could not free it then).
        std::vector<float> rgb(size_t(info.width) * info.height * 3);
        {
            PixelLock lock(env, bitmap);
            if (!lock.ok) { fail("cannot lock bitmap pixels"); return 0; }
            // Row bands on up to four threads: a 50 MP phone photo is the
            // slowest thing on the open path after the decode itself.
            const uint64_t px = uint64_t(info.width) * info.height;
            const uint32_t workers = px < 1'000'000 ? 1u : std::min(4u, std::max(1u, std::thread::hardware_concurrency()));
            const auto* base = static_cast<const uint8_t*>(lock.pixels);
            run_rows(info.height, workers, [&](uint32_t y0, uint32_t y1) {
                for (uint32_t y = y0; y < y1; ++y) {
                    const auto* src = base + size_t(y) * info.stride;
                    float* dst = rgb.data() + size_t(y) * info.width * 3;
                    for (uint32_t x = 0; x < info.width; ++x) {
                        const uint32_t a = src[4 * x + 3];
                        float c[3];
                        if (a == 255 || a == 0) {
                            for (int i = 0; i < 3; ++i) c[i] = xf.decode(src[4 * x + i], i);
                        } else {
                            // ARGB_8888 is premultiplied: undo it, or semi-transparent
                            // edges (PNG) print darker than they are.
                            for (int i = 0; i < 3; ++i) c[i] = xf.decode(std::min(1.0f, src[4 * x + i] / float(a)), i);
                        }
                        if (expand.active()) expand.apply(c[0], c[1], c[2]);
                        xf.to_prophoto(c);
                        dst[3 * x + 0] = c[0];
                        dst[3 * x + 1] = c[1];
                        dst[3 * x + 2] = c[2];
                    }
                }
            });
        }
        SessionPtr session(open_pixels(reinterpret_cast<spk_engine*>(engine_handle), rgb,
                                       info.width, info.height, params));
        if (!session) return 0;
        make_before(session.get(), rgb, info.width, info.height, /*linear_prophoto=*/true);
        session->source_width = source_width > 0 ? uint32_t(source_width) : info.width;
        session->source_height = source_height > 0 ? uint32_t(source_height) : info.height;
        session->kind = "bitmap";
        session->input_precision = "rgba8_sdr";
        return reinterpret_cast<jlong>(session.release());
    } catch (const std::bad_alloc&) {
        fail("out of memory while opening the image");
    } catch (const std::exception& e) {
        fail(std::string("open image: ") + e.what());
    } catch (...) {
        fail("open image: unknown failure");
    }
    return 0;
}

// V1 deliberately leaves the established eight-bit/gain-map paths unchanged.
JNI_FN(jlong, openBitmapF16V1)(JNIEnv* env, jobject, jlong engine_handle, jobject bitmap, jstring space_j,
                               jstring params_j, jfloat headroom_stops, jint alpha_mode,
                               jint source_width, jint source_height) {
    try {
        AndroidBitmapInfo info{};
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
            info.format != ANDROID_BITMAP_FORMAT_RGBA_F16) {
            fail("F16 V1 requires a software RGBA_F16 bitmap"); return 0;
        }
        const spk::android::F16LayoutV1 layout(info.width, info.height, info.stride, int(alpha_mode));
        const std::string space = str(env, space_j);
        if (space != "sRGB" && space != "Display P3") {
            fail("unsupported F16 V1 SDR color encoding"); return 0;
        }
        const std::string params = str(env, params_j);
        const spk::android::InputTransform xf(space);
        const HighlightExpand expand(std::isfinite(headroom_stops) ? std::clamp(float(headroom_stops), 0.0f, 3.0f) : 0.0f);
        std::vector<float> rgb(layout.rgb_values);
        std::atomic<bool> valid{true};
        {
            PixelLock lock(env, bitmap);
            if (!lock.ok) { fail("cannot lock F16 bitmap pixels"); return 0; }
            const uint64_t px = uint64_t(info.width) * info.height;
            const uint32_t workers = px < 1'000'000 ? 1u : std::min(4u, std::max(1u, std::thread::hardware_concurrency()));
            const auto* base = static_cast<const uint8_t*>(lock.pixels);
            run_rows(info.height, workers, [&](uint32_t y0, uint32_t y1) {
                for (uint32_t y = y0; y < y1 && valid.load(std::memory_order_relaxed); ++y) {
                    const auto* row = base + size_t(y) * info.stride;
                    float* dst = rgb.data() + size_t(y) * info.width * 3;
                    for (uint32_t x = 0; x < info.width; ++x) {
                        float c[3];
                        if (!spk::android::decode_f16_sdr_pixel_v1(row + size_t(x) * 8, layout.alpha, c)) {
                            valid.store(false, std::memory_order_relaxed);
                            return;
                        }
                        for (int channel = 0; channel < 3; ++channel) c[channel] = xf.decode(c[channel], channel);
                        if (expand.active()) expand.apply(c[0], c[1], c[2]);
                        xf.to_prophoto(c);
                        for (int channel = 0; channel < 3; ++channel) dst[3 * x + channel] = c[channel];
                    }
                }
            });
        }
        if (!valid.load(std::memory_order_relaxed)) {
            fail("F16 V1 requires finite SDR codes in [0,1]; extended-range/HDR input is unsupported"); return 0;
        }
        SessionPtr session(open_pixels(reinterpret_cast<spk_engine*>(engine_handle), rgb,
                                       info.width, info.height, params));
        if (!session) return 0;
        make_before(session.get(), rgb, info.width, info.height, /*linear_prophoto=*/true);
        session->source_width = source_width > 0 ? uint32_t(source_width) : info.width;
        session->source_height = source_height > 0 ? uint32_t(source_height) : info.height;
        session->kind = "bitmap";
        session->input_precision = "f16_sdr_v1";
        return reinterpret_cast<jlong>(session.release());
    } catch (const std::bad_alloc&) {
        fail("out of memory while opening the F16 image");
    } catch (const std::exception& e) {
        fail(std::string("open F16 image: ") + e.what());
    } catch (...) {
        fail("open F16 image: unknown failure");
    }
    return 0;
}

// The frame as it went into the engine, for press-and-hold "original".
// Ultra HDR / ISO 21496-1: the SDR picture plus its gain map, recovered at
// full boost (weight 1) into linear light in the base's own primaries, with
// highlights above SDR white kept, so the film's shoulder and halation see
// the real highlight range instead of a clipped 1.0. `meta_j` is the Gainmap
// API's ratioMin[3], ratioMax[3], gamma[3], epsilonSdr[3], epsilonHdr[3]
// (linear ratios). The gain map is usually a quarter of the size; it is
// sampled bilinearly in code space, before applying gamma. The recovered light leaves the
// base's primaries for linear ProPhoto (input_transform.hpp), as openBitmap's
// pixels do; [params_j] must say linear ProPhoto.
JNI_FN(jlong, openBitmapHdr)(JNIEnv* env, jobject, jlong engine_handle, jobject bitmap, jobject gain,
                             jfloatArray meta_j, jstring space_j, jstring params_j, jint source_width, jint source_height) {
    try {
        AndroidBitmapInfo info{}, ginfo{};
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
            !info.width || !info.height ||
            info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) { fail("image must be an ARGB_8888 bitmap"); return 0; }
        if (!gain || AndroidBitmap_getInfo(env, gain, &ginfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
            !ginfo.width || !ginfo.height ||
            (ginfo.format != ANDROID_BITMAP_FORMAT_A_8 && ginfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888)) {
            fail("unsupported gain map"); return 0;
        }
        if (!meta_j || env->GetArrayLength(meta_j) < 15) { fail("gain map metadata missing"); return 0; }
        float meta[15];
        env->GetFloatArrayRegion(meta_j, 0, 15, meta);
        const std::string params = str(env, params_j);

        const uint32_t gc = ginfo.format == ANDROID_BITMAP_FORMAT_A_8 ? 1 : 3;
        spk::android::GainmapChannel channels[3];
        for (int c = 0; c < 3; ++c) {
            if (!spk::android::GainmapChannel::make(meta[c], meta[3 + c], meta[6 + c],
                                                    meta[9 + c], meta[12 + c], channels[c])) {
                fail("invalid gain map metadata"); return 0;
            }
        }
        const spk::android::InputTransform xf(str(env, space_j));

        const uint32_t gw = ginfo.width, gh = ginfo.height;
        std::vector<uint8_t> g(size_t(gw) * gh * gc);
        std::vector<float> rgb(size_t(info.width) * info.height * 3);
        {
            PixelLock lock(env, gain);
            if (!lock.ok) { fail("cannot lock gain map pixels"); return 0; }
            for (uint32_t y = 0; y < gh; ++y) {
                const auto* src = static_cast<const uint8_t*>(lock.pixels) + size_t(y) * ginfo.stride;
                uint8_t* dst = g.data() + size_t(y) * gw * gc;
                if (gc == 1) std::memcpy(dst, src, gw);
                else for (uint32_t x = 0; x < gw; ++x) {
                    dst[3 * x] = src[4 * x]; dst[3 * x + 1] = src[4 * x + 1]; dst[3 * x + 2] = src[4 * x + 2];
                }
            }
        }
        {
            PixelLock lock(env, bitmap);
            if (!lock.ok) { fail("cannot lock bitmap pixels"); return 0; }
            const uint32_t W = info.width, H = info.height;
            const float sx = float(gw) / W, sy = float(gh) / H;
            const auto rows = [&](uint32_t y0, uint32_t y1) {
                for (uint32_t y = y0; y < y1; ++y) {
                    const auto* src = static_cast<const uint8_t*>(lock.pixels) + size_t(y) * info.stride;
                    float* dst = rgb.data() + size_t(y) * W * 3;
                    const float fy = std::min(float(gh - 1), std::max(0.0f, (y + 0.5f) * sy - 0.5f));
                    const uint32_t y0g = uint32_t(fy), y1g = std::min(gh - 1, y0g + 1);
                    const float ty = fy - y0g;
                    for (uint32_t x = 0; x < W; ++x) {
                        const float fx = std::min(float(gw - 1), std::max(0.0f, (x + 0.5f) * sx - 0.5f));
                        const uint32_t x0g = uint32_t(fx), x1g = std::min(gw - 1, x0g + 1);
                        const float tx = fx - x0g;
                        const uint32_t a = src[4 * x + 3];
                        for (int c = 0; c < 3; ++c) {
                            uint32_t code = src[4 * x + c];
                            if (a != 255 && a != 0) code = std::min(255u, (code * 255u + a / 2) / a);
                            const int gch = gc == 1 ? 0 : c;
                            const auto at = [&](uint32_t gx, uint32_t gy) {
                                return g[(size_t(gy) * gw + gx) * gc + gch];
                            };
                            const float sample = spk::android::gainmap_bilinear(
                                at(x0g, y0g), at(x1g, y0g), at(x0g, y1g), at(x1g, y1g), tx, ty);
                            const float v = channels[c].recover(xf.decode(uint8_t(code), c), sample);
                            dst[3 * x + c] = std::isfinite(v) ? std::max(0.0f, v) : 0.0f;
                        }
                        xf.to_prophoto(dst + 3 * x);
                    }
                }
            };
            const uint64_t px = uint64_t(W) * H;
            run_rows(H, px < 1'000'000 ? 1u : std::min(4u, std::max(1u, std::thread::hardware_concurrency())), rows);
        }
        SessionPtr session(open_pixels(reinterpret_cast<spk_engine*>(engine_handle), rgb,
                                       info.width, info.height, params));
        if (!session) return 0;
        make_before(session.get(), rgb, info.width, info.height, /*linear_prophoto=*/true);
        session->source_width = source_width > 0 ? uint32_t(source_width) : info.width;
        session->source_height = source_height > 0 ? uint32_t(source_height) : info.height;
        session->kind = "bitmap";
        session->input_precision = "rgba8_gainmap";
        return reinterpret_cast<jlong>(session.release());
    } catch (const std::bad_alloc&) {
        fail("out of memory while opening the HDR image");
    } catch (const std::exception& e) {
        fail(std::string("open HDR image: ") + e.what());
    } catch (...) {
        fail("open HDR image: unknown failure");
    }
    return 0;
}

JNI_FN(jobject, beforeBitmap)(JNIEnv* env, jobject, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s || s->before.empty()) { fail("no original thumbnail"); return nullptr; }
    try {
        jclass bitmap_class = env->FindClass("android/graphics/Bitmap");
        jclass config_class = env->FindClass("android/graphics/Bitmap$Config");
        if (!bitmap_class || !config_class) { env->ExceptionClear(); fail("Bitmap classes not found"); return nullptr; }
        jfieldID argb_field = env->GetStaticFieldID(config_class, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
        jmethodID create = env->GetStaticMethodID(bitmap_class, "createBitmap",
            "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
        if (!argb_field || !create) { env->ExceptionClear(); fail("Bitmap API not found"); return nullptr; }
        jobject argb = env->GetStaticObjectField(config_class, argb_field);
        jobject bitmap = env->CallStaticObjectMethod(bitmap_class, create, jint(s->before_w), jint(s->before_h), argb);
        if (env->ExceptionCheck() || !bitmap) { env->ExceptionClear(); fail("cannot allocate original bitmap"); return nullptr; }
        AndroidBitmapInfo info{};
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
            fail("cannot read bitmap info"); return nullptr;
        }
        PixelLock lock(env, bitmap);
        if (!lock.ok) { fail("cannot lock bitmap pixels"); return nullptr; }
        for (uint32_t y = 0; y < s->before_h; ++y)
            std::memcpy(static_cast<uint8_t*>(lock.pixels) + size_t(y) * info.stride,
                        s->before.data() + size_t(y) * s->before_w * 4, size_t(s->before_w) * 4);
        return bitmap;
    } catch (const std::exception& e) {
        fail(std::string("original: ") + e.what());
    } catch (...) {
        fail("original: unknown failure");
    }
    return nullptr;
}

// A film card: the current photo, 384 px, through `params_j` (complete opening
// params, the card's film and paper). A throwaway session on the small copy
// kept at open, so the user's session and its cached negative are untouched.
JNI_FN(jobject, filmPreview)(JNIEnv* env, jobject, jlong handle, jstring params_j) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s || s->small_rgb.empty()) { fail("no preview source"); return nullptr; }
    try {
        const spk_image input{s->small_rgb.data(), s->small_w, s->small_h, 3};
        std::string params = str(env, params_j);
        spk::android::ViewFlags view;
        std::string bad;
        if (!spk::android::take_app_keys(params, view, bad)) { fail("film preview: " + bad); return nullptr; }
        std::unique_ptr<spk_session, decltype(&spk_session_release)> card(
            spk_open(s->engine, &input, params.c_str(), nullptr), &spk_session_release);
        if (!card) { fail(engine_error("film preview")); return nullptr; }
        ResultGuard result;
        if (view.no_film) {
            if (!spk::android::render_view_float(card.get(), "full", result.r, result.float_rgb, bad)) {
                fail("film preview render: " + bad); return nullptr;
            }
        } else if (view.di()) {
            // A card on 相纸「无」shows the intermediate as the print does.
            if (!spk::android::render_view(card.get(), "full", view.no_film, result.r, bad)) {
                fail("film preview render: " + bad);
                return nullptr;
            }
        } else if (spk_render(card.get(), "full", &result.r) != SPK_OK || !result.r.rgba16) {
            fail(engine_error("film preview render"));
            return nullptr;
        }
        // The card is shown beside the print, so it leaves ProPhoto the same way.
        auto options = view.raw_development;
        options.enabled = options.enabled && view.no_film && s->kind == "raw";
        if (options.enabled && !s->raw_stats_ready) {
            fail("RAW standard development requires reopening the complete source"); return nullptr;
        }
        const auto development = spk::android::RawDevelopment::build(s->raw_metadata, s->raw_stats, options);
        return to_bitmap(env, result.r, nullptr, renders_prophoto(params) ? s->transform() : nullptr,
            result.float_rgb.empty() ? nullptr : result.float_rgb.data(), {}, development.enabled() ? &development : nullptr);
    } catch (const std::exception& e) {
        fail(std::string("film preview: ") + e.what());
    } catch (...) {
        fail("film preview: unknown failure");
    }
    return nullptr;
}

JNI_FN(jstring, sourceInfo)(JNIEnv* env, jobject, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) return jstr(env, "{}");
    try {
        const auto num = [](double v) {
            std::ostringstream text;
            text.imbue(std::locale::classic());
            text << std::setprecision(std::numeric_limits<double>::max_digits10) << (std::isfinite(v) ? v : 0.0);
            return text.str();
        };
        const auto triple = [&](const std::array<double, 3>& values) {
            return "[" + num(values[0]) + "," + num(values[1]) + "," + num(values[2]) + "]";
        };
        std::string out = "{\"width\":" + std::to_string(s->width) + ",\"height\":" + std::to_string(s->height) +
            ",\"sourceWidth\":" + std::to_string(s->source_width) +
            ",\"sourceHeight\":" + std::to_string(s->source_height) +
            ",\"kind\":\"" + s->kind + "\",\"make\":\"" + json_escape(utf8_clean(s->make)) +
            "\",\"model\":\"" + json_escape(utf8_clean(s->model)) + "\"" +
            ",\"inputPrecision\":\"" + json_escape(s->input_precision) + "\"" +
            ",\"inputPrecisionNote\":\"" + json_escape(s->input_precision_note) + "\"" +
            ",\"rawFloatSource\":" + (s->input_precision == "float_linear_v1" ?
                "{\"black\":" + triple(s->float_source.black) + ",\"white\":" + triple(s->float_source.white) +
                ",\"neutral\":" + triple(s->float_source.neutral) + "}" : "null") +
            ",\"asShotK\":" + num(s->as_shot_k) + ",\"asShotTint\":" + num(s->as_shot_tint) +
            ",\"wbK\":" + num(s->wb_k) + ",\"wbTint\":" + num(s->wb_tint) +
            ",\"rawStatsBinned\":" + (s->raw_stats_binned ? "true" : "false") +
            ",\"rawStatsReady\":" + (s->raw_stats_ready ? "true" : "false") +
            ",\"rawDevelopment\":\"" + (s->development() ? "standard_v1" : "neutral") +
            "\",\"rawCurve\":\"" + (s->development() ? s->raw_development.curve_name() : "none") +
            "\",\"rawEffectiveEv\":" + num(s->raw_development.effective_ev()) +
            ",\"rawAutoEv\":" + num(s->raw_development.auto_ev()) +
            ",\"rawDevelopmentStatus\":\"" + json_escape(s->raw_development.status()) + "\"}";
        return jstr(env, out);
    } catch (...) {
        fail("source info: out of memory");
        return jstr(env, "{}");
    }
}

JNI_FN(jstring, setParams)(JNIEnv* env, jobject, jlong handle, jstring delta_j) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) { fail("no session"); return nullptr; }
    try {
        std::string delta = str(env, delta_j);
        // Kept only once the engine has taken the rest of the delta.
        spk::android::ViewFlags view = s->view;
        std::string bad;
        if (!spk::android::take_app_keys(delta, view, bad)) { fail("set params: " + bad); return nullptr; }
        auto options = view.raw_development;
        options.enabled = options.enabled && view.no_film && s->kind == "raw";
        // Build before accepting engine params; invalid display metadata cannot
        // leave the engine and application options on different edits.
        if (options.enabled && !s->raw_stats_ready) {
            fail("RAW standard development requires reopening the complete source"); return nullptr;
        }
        auto development = spk::android::RawDevelopment::build(s->raw_metadata, s->raw_stats, options);
        char* reply = nullptr;
        const spk_status st = spk_set_params(s->session, delta.c_str(), &reply);
        std::unique_ptr<char, decltype(&spk_string_free)> owned(reply, &spk_string_free);
        if (st != SPK_OK) { fail(engine_error("set params")); return nullptr; }
        // Accepted params can precede a cancelled/failed render. The old cache
        // must not be published as that new film or geometry on a later repack.
        if (delta != "{}" || view.no_film != s->view.no_film) ++s->render_revision;
        s->view = view;
        s->raw_development = std::move(development);
        return jstr(env, reply ? reply : "{}");
    } catch (const std::exception& e) {
        fail(std::string("set params: ") + e.what());
    } catch (...) {
        fail("set params: unknown failure");
    }
    return nullptr;
}

JNI_FN(jstring, getParams)(JNIEnv* env, jobject, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) { fail("no session"); return nullptr; }
    try {
        char* reply = nullptr;
        const spk_status st = spk_get_params(s->session, &reply);
        std::unique_ptr<char, decltype(&spk_string_free)> owned(reply, &spk_string_free);
        if (st != SPK_OK) { fail(engine_error("get params")); return nullptr; }
        return jstr(env, spk::android::with_app_keys(reply ? reply : "{}", s->view));
    } catch (const std::exception& e) {
        fail(std::string("get params: ") + e.what());
    } catch (...) {
        fail("get params: unknown failure");
    }
    return nullptr;
}

// RFC-015's meter, exposed for a tiled export: `spk_solve("exposure")` returns
// the one EV this frame's auto-exposure would apply, without rendering. A tiled
// export meters the complete frame once and freezes that value on every tile
// (auto_exposure off + exposure_baseline_ev), so no tile derives its own AE.
JNI_FN(jstring, solve)(JNIEnv* env, jobject, jlong handle, jstring target_j) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) { fail("no session"); return nullptr; }
    try {
        char* reply = nullptr;
        const spk_status st = spk_solve(s->session, str(env, target_j).c_str(), &reply);
        std::unique_ptr<char, decltype(&spk_string_free)> owned(reply, &spk_string_free);
        if (st != SPK_OK) { fail(engine_error("solve")); return nullptr; }
        return jstr(env, reply ? reply : "{}");
    } catch (const std::exception& e) {
        fail(std::string("solve: ") + e.what());
    } catch (...) {
        fail("solve: unknown failure");
    }
    return nullptr;
}

// Patch 0054 at the app boundary: one window of the film canvas. A tiled
// film-edge export lays the whole canvas out once, cuts it into windows and
// gives each session its own window plus the frame rectangle that session's
// source holds. All sizes are pixels of the full frame.
JNI_FN(jboolean, setRenderWindow)(JNIEnv*, jobject, jlong handle,
                                  jint full_w, jint full_h,
                                  jint win_x, jint win_y, jint win_w, jint win_h,
                                  jint src_x, jint src_y, jint src_w, jint src_h) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) { fail("no session"); return JNI_FALSE; }
    try {
        if (full_w <= 0 || full_h <= 0 || win_w <= 0 || win_h <= 0 || src_w <= 0 || src_h <= 0) {
            fail("render window: sizes must be positive");
            return JNI_FALSE;
        }
        const spk_status st = spk_set_render_window(
            s->session, uint32_t(full_w), uint32_t(full_h),
            uint32_t(win_x), uint32_t(win_y), uint32_t(win_w), uint32_t(win_h),
            uint32_t(src_x), uint32_t(src_y), uint32_t(src_w), uint32_t(src_h));
        if (st != SPK_OK) { fail(engine_error("render window")); return JNI_FALSE; }
        return JNI_TRUE;
    } catch (const std::exception& e) {
        fail(std::string("render window: ") + e.what());
    } catch (...) {
        fail("render window: unknown failure");
    }
    return JNI_FALSE;
}

// The film canvas and gate quads for a whole frame of this size, without
// rendering: the tiled film-edge export asks before it cuts the frame up.
JNI_FN(jstring, overscanLayout)(JNIEnv* env, jobject, jlong handle, jint width, jint height) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) { fail("no session"); return nullptr; }
    try {
        if (width <= 0 || height <= 0) { fail("overscan layout: the frame is empty"); return nullptr; }
        char* reply = nullptr;
        const spk_status st = spk_overscan_layout_at(s->session, uint32_t(width), uint32_t(height), &reply);
        std::unique_ptr<char, decltype(&spk_string_free)> owned(reply, &spk_string_free);
        if (st != SPK_OK) { fail(engine_error("overscan layout")); return nullptr; }
        return jstr(env, reply ? reply : "{}");
    } catch (const std::exception& e) {
        fail(std::string("overscan layout: ") + e.what());
    } catch (...) {
        fail("overscan layout: unknown failure");
    }
    return nullptr;
}

// `r` into `reuse` when it is the same size, else into a new Bitmap.
jobject pack_into(JNIEnv* env, const spk_result& r, jobject reuse, const spk::android::Layer2* look,
                  const spk::android::OutputTransform* xf, const float* rgb = nullptr,
                  const spk::android::OutputCancel& cancel = {}, const spk::android::RawDevelopment* development = nullptr) {
    cancel.check();
    AndroidBitmapInfo info{};
    if (reuse && AndroidBitmap_getInfo(env, reuse, &info) == ANDROID_BITMAP_RESULT_SUCCESS &&
        info.format == ANDROID_BITMAP_FORMAT_RGBA_8888 && info.width == r.width && info.height == r.height) {
        PixelLock lock(env, reuse);
        if (lock.ok) {
            pack_rgba8(r, static_cast<uint8_t*>(lock.pixels), info.stride, look, xf, rgb, cancel, development);
            cancel.check();
            return reuse;
        }
    }
    return to_bitmap(env, r, look, xf, rgb, cancel, development);
}

JNI_FN(jobject, renderBitmap)(JNIEnv* env, jobject, jlong handle, jstring tier_j) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) { fail("no session"); return nullptr; }
    const spk::android::OutputCancel cancel(s->cancel_generation);
    try {
        ResultGuard result;
        if (!render(s, str(env, tier_j), result.r, result.float_rgb, cancel)) return nullptr;
        return to_bitmap(env, result.r, s->active_look(), s->xf(), result.float_rgb.empty() ? nullptr : result.float_rgb.data(), cancel, s->development());
    } catch (const std::exception& e) {
        fail(std::string("render: ") + e.what());
    } catch (...) {
        fail("render: unknown failure");
    }
    return nullptr;
}

// File-target codes in a fresh bitmap. Kotlin must tag it with that target,
// allowing Android to map it to the screen without a second gamut compressor.
JNI_FN(jobject, renderProofBitmap)(JNIEnv* env, jobject, jlong handle, jstring tier_j, jstring target_j) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) { fail("no session"); return nullptr; }
    const spk::android::OutputCancel cancel(s->cancel_generation);
    try {
        const std::string target = str(env, target_j);
        if (!s->prophoto || (target != "sRGB" && target != "Display P3")) {
            fail("color preview: unsupported source or target"); return nullptr;
        }
        cancel.check();
        spk::android::OutputTransform output;
        std::string error;
        if (!output.fetch(s->engine, target, true, error)) {
            fail("color preview: " + error); return nullptr;
        }
        cancel.check();
        ResultGuard result;
        if (!render(s, str(env, tier_j), result.r, result.float_rgb, cancel)) return nullptr;
        // Do not replace the editor's output transform or last/repack source.
        return to_bitmap(env, result.r, s->active_look(), &output,
                         result.float_rgb.empty() ? nullptr : result.float_rgb.data(), cancel, s->development());
    } catch (const std::exception& e) {
        fail(std::string("color preview: ") + e.what());
    } catch (...) {
        fail("color preview: unknown failure");
    }
    return nullptr;
}

// renderBitmap for an export: the engine's frame is checked (blank_frame)
// before it is packed, so a failed render never reaches the gallery.
JNI_FN(jobject, exportBitmap)(JNIEnv* env, jobject, jlong handle, jstring tier_j) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) { fail("no session"); return nullptr; }
    const spk::android::OutputCancel cancel(s->cancel_generation);
    try {
        ResultGuard result;
        if (!render(s, str(env, tier_j), result.r, result.float_rgb, cancel)) return nullptr;
        const std::string blank = blank_result(result);
        if (!blank.empty()) { fail("export check: " + blank); return nullptr; }
        return to_bitmap(env, result.r, s->active_look(), s->xf(), result.float_rgb.empty() ? nullptr : result.float_rgb.data(), cancel, s->development());
    } catch (const std::exception& e) {
        fail(std::string("render: ") + e.what());
    } catch (...) {
        fail("render: unknown failure");
    }
    return nullptr;
}

// Bounded readback for no-film, bounded output conversion for every path.
// Packed width/height lets Kotlin write EXIF/check the file without a Bitmap.
JNI_FN(jlong, exportJpeg444)(JNIEnv* env, jobject, jlong handle, jstring path_j,
                            jstring tier_j, jint quality, jbyteArray icc_j) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) { fail("no session"); return 0; }
    const spk::android::OutputCancel cancel(s->cancel_generation);
    const auto check_cancelled = [&] { cancel.check(); };
    try {
        const std::string path = str(env, path_j);
        const auto* xf = s->xf();
        if (!xf || !xf->ready()) { fail("JPEG: output transform unavailable"); return 0; }
        std::vector<uint8_t> icc;
        if (icc_j) {
            const jsize count = env->GetArrayLength(icc_j);
            icc.resize(size_t(count));
            env->GetByteArrayRegion(icc_j, 0, count, reinterpret_cast<jbyte*>(icc.data()));
            if (env->ExceptionCheck()) return 0;
        }
        if (icc.empty()) { fail("JPEG: output profile unavailable"); return 0; }
        uint32_t width = 0, height = 0;
        std::unique_ptr<spk::android::Rgba8Spool> spool;
        std::string error;
        if (s->view.no_film) {
            spk::android::FloatFrameCheck check;
            const auto* look = s->active_look();
            spk::android::Layer2::Frame frame;
            std::vector<uint8_t> scratch;
            const auto sink = [&](uint32_t w, uint32_t h, uint32_t y0, uint32_t count,
                                  const float* rgb, std::string& sink_error) {
                check_cancelled();
                if (!check.append(rgb, w, w, h, y0, count)) {
                    sink_error = "export check: " + check.finish();
                    return false;
                }
                if (!spool) {
                    width = w; height = h;
                    spool = spk::android::Rgba8Spool::create(path, width, height);
                    if (look) frame = look->frame(width, height);
                }
                const spk::android::Rgba8Rows rows(rgb, w, w, h, y0, count, look, xf, &frame, s->development());
                rows.pack(y0, count, scratch, cancel);
                spool->append(scratch);
                return true;
            };
            ResultGuard shape;
            if (!spk::android::render_view_float_rows(s->session, str(env, tier_j), shape.r,
                                                      sink, error, cancel)) {
                fail("JPEG: " + error); return 0;
            }
            error = check.finish();
            if (!error.empty()) { fail("export check: " + error); return 0; }
            if (shape.r.width != width || shape.r.height != height) {
                fail("JPEG: inconsistent render dimensions"); return 0;
            }
            spool->seal();
        } else {
            ResultGuard result;
            if (!render(s, str(env, tier_j), result.r, result.float_rgb, cancel)) return 0;
            error = blank_result(result);
            if (!error.empty()) { fail("export check: " + error); return 0; }
            width = result.r.width; height = result.r.height;
            spool = spk::android::Rgba8Spool::create(path, width, height);
            const spk::android::Rgba8Rows rows = result.float_rgb.empty()
                ? spk::android::Rgba8Rows(result.r.rgba16, result.r.row_stride_px, width, height, s->active_look(), xf)
                : spk::android::Rgba8Rows(result.float_rgb.data(), result.r.row_stride_px, width, height, s->active_look(), xf, s->development());
            std::vector<uint8_t> scratch;
            for (uint32_t y = 0; y < height; y += 32) {
                check_cancelled();
                rows.pack(y, std::min(32u, height - y), scratch, cancel);
                spool->append(scratch);
            }
            spool->seal();
        } // Free the render result before libjpeg allocates coefficient planes.
        const spk::android::JpegRowSource source = [&](uint32_t y0, uint32_t count, std::vector<uint8_t>& scratch) {
            check_cancelled();
            return spool->read(y0, count, scratch);
        };
        check_cancelled();
        if (!spk::android::write_jpeg444(path, width, height, quality, icc, source, error)) {
            fail("JPEG: " + error); return 0;
        }
        if (cancel.cancelled()) {
            std::remove(path.c_str());
            check_cancelled();
        }
        return jlong((uint64_t(width) << 32) | height);
    } catch (const std::exception& e) {
        fail(std::string("JPEG: ") + e.what());
    } catch (...) {
        fail("JPEG: unknown failure");
    }
    return 0;
}

// Renders `tier` into `reuse` when it is the same size, so a pooled preview
// allocates nothing; otherwise into a new Bitmap from the same result, so a
// size change (first preview, crop, rotation, film edge) renders once, not
// twice. `reuse` must be a spare: cancellation may leave it partly packed.
// The cached engine/float result is replaced only after successful packing.
JNI_FN(jobject, renderReuse)(JNIEnv* env, jobject, jlong handle, jstring tier_j, jobject reuse) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) { fail("no session"); return nullptr; }
    const spk::android::OutputCancel cancel(s->cancel_generation);
    try {
        ResultGuard result;
        if (!render(s, str(env, tier_j), result.r, result.float_rgb, cancel)) return nullptr;
        jobject out = pack_into(env, result.r, reuse, s->active_look(), s->xf(), result.float_rgb.empty() ? nullptr : result.float_rgb.data(), cancel, s->development());
        cancel.check();
        if (out) s->keep(result.r, result.float_rgb);
        return out;
    } catch (const std::exception& e) {
        fail(std::string("render: ") + e.what());
    } catch (...) {
        fail("render: unknown failure");
    }
    return nullptr;
}

// Renders `tier` and writes the RGB16 TIFF to `path`, or, when `fd` >= 0, to
// that descriptor (a pending MediaStore entry, so nothing is copied twice).
// `target` is a named output space, including explicit Adobe RGB (1998) TIFF.
static jboolean export_tiff(JNIEnv* env, Session* s, const std::string& path, int fd, jstring icc_j, jstring target_j, jstring tier_j) {
    if (!s) { fail("no session"); return JNI_FALSE; }
    const spk::android::OutputCancel cancel(s->cancel_generation);
    try {
        // A TIFF without its profile would be read as some other space: refuse.
        std::vector<uint8_t> icc;
        const std::string icc_path = str(env, icc_j);
        std::ifstream file(icc_path, std::ios::binary);
        if (icc_path.empty() || !file) { fail("TIFF: ICC profile missing from engine resources"); return JNI_FALSE; }
        icc.assign(std::istreambuf_iterator<char>(file), std::istreambuf_iterator<char>());
        if (icc.empty()) { fail("TIFF: ICC profile is empty"); return JNI_FALSE; }

        const std::string target = target_j ? str(env, target_j) : std::string("sRGB");
        const std::string tier = tier_j ? str(env, tier_j) : std::string("full");

        // Each file owns its export intent. Do not overwrite the display/repack
        // transform, or replace file CAM16 compression with the preview path.
        spk::android::OutputTransform output;
        std::string error;
        const spk::android::OutputTransform* xf = nullptr;
        if (s->prophoto && !output.fetch(s->engine, target, true, error)) {
            fail("TIFF: output transform: " + error);
            return JNI_FALSE;
        }
        if (s->prophoto) xf = &output;
        else if (target != "sRGB") {
            fail("TIFF: target conversion requires a ProPhoto session");
            return JNI_FALSE;
        }

        ResultGuard result;
        if (!render(s, tier, result.r, result.float_rgb, cancel)) return JNI_FALSE;
        error = blank_result(result);
        if (!error.empty()) { fail("export check: " + error); return JNI_FALSE; }
        const auto* look = s->active_look();
        // Retain full-frame mask/dust geometry, but convert only the encoder's
        // next strips. The engine's result stays immutable through the write.
        const spk::android::Rgba16Rows rows = result.float_rgb.empty()
            ? spk::android::Rgba16Rows(result.r.rgba16, result.r.row_stride_px, result.r.width, result.r.height, look, xf)
            : spk::android::Rgba16Rows(result.float_rgb.data(), result.r.row_stride_px, result.r.width, result.r.height, look, xf, s->development());
        const spk::android::TiffRowSource source = [&](uint32_t y0, uint32_t count, std::vector<uint16_t>& scratch) {
            cancel.check();
            if (result.float_rgb.empty() && !look && !xf) return spk::android::TiffRowView{
                result.r.rgba16 + size_t(y0) * result.r.row_stride_px * 4, result.r.row_stride_px};
            rows.pack(y0, count, scratch, cancel);
            return spk::android::TiffRowView{scratch.data(), result.r.width};
        };
        cancel.check();
        if (fd >= 0) {
            // A dup, so fclose leaves the caller's ParcelFileDescriptor to close its own.
            const int own = dup(fd);
            std::FILE* file = own >= 0 ? fdopen(own, "wb") : nullptr;
            if (!file) {
                if (own >= 0) close(own);
                fail("TIFF: cannot open the gallery entry for writing");
                return JNI_FALSE;
            }
            std::unique_ptr<std::FILE, decltype(&std::fclose)> owned(file, &std::fclose);
            const bool ok = spk::android::write_tiff16(file, result.r.width, result.r.height, icc, source, error);
            const int closed = std::fclose(owned.release());
            cancel.check(); // The writer has joined every worker and closed its file.
            if (closed != 0 && ok) { fail("TIFF: close failed"); return JNI_FALSE; }
            if (!ok) { fail("TIFF: " + error); return JNI_FALSE; }
            return JNI_TRUE;
        }
        const bool ok = spk::android::write_tiff16(path, result.r.width, result.r.height, icc, source, error);
        if (cancel.cancelled()) {
            std::remove(path.c_str());
            cancel.check();
        }
        if (!ok) {
            fail("TIFF: " + error);
            return JNI_FALSE;
        }
        return JNI_TRUE;
    } catch (const std::bad_alloc&) {
        fail("TIFF: out of memory; choose a smaller working size");
    } catch (const std::exception& e) {
        fail(std::string("TIFF: ") + e.what());
    } catch (...) {
        fail("TIFF: unknown failure");
    }
    return JNI_FALSE;
}

JNI_FN(jboolean, exportTiff)(JNIEnv* env, jobject, jlong handle, jstring path_j, jstring icc_j, jstring tier_j) {
    return export_tiff(env, reinterpret_cast<Session*>(handle), str(env, path_j), -1, icc_j, nullptr, tier_j);
}

// exportTiff into an open, writable descriptor; the caller keeps and closes it.
JNI_FN(jboolean, exportTiffFd)(JNIEnv* env, jobject, jlong handle, jint fd, jstring icc_j, jstring tier_j) {
    if (fd < 0) { fail("TIFF: no descriptor"); return JNI_FALSE; }
    return export_tiff(env, reinterpret_cast<Session*>(handle), std::string(), fd, icc_j, nullptr, tier_j);
}

// Versioned entry: never bind two native signatures to the same short JNI name.
JNI_FN(jboolean, exportTiffFdV1)(JNIEnv* env, jobject, jlong handle, jint fd, jstring icc_j, jstring target_j, jstring tier_j) {
    if (fd < 0) { fail("TIFF: no descriptor"); return JNI_FALSE; }
    return export_tiff(env, reinterpret_cast<Session*>(handle), std::string(), fd, icc_j, target_j, tier_j);
}

// Safe from another thread while a render is in flight on the engine thread:
// the render unwinds at its next node and returns SPK_ERR_CANCELLED. The caller
// guarantees the session outlives this call (EditorViewModel.cancelLock).
JNI_FN(void, cancel)(JNIEnv*, jobject, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (s && s->session) {
        s->cancel_generation.fetch_add(1, std::memory_order_relaxed);
        spk_cancel(s->session, nullptr);
    }
}

// The fixed per-stock setup (tc_lut, C_max table, compute pipelines) paid
// before the first photo, so the first preview does not carry it.
JNI_FN(jboolean, warmUp)(JNIEnv* env, jobject, jlong engine, jstring film_j, jstring paper_j) {
    if (!engine_usable(reinterpret_cast<spk_engine*>(engine))) { fail("warm up: the engine was replaced"); return JNI_FALSE; }
    char* reply = nullptr;
    const std::string film = str(env, film_j), paper = str(env, paper_j);
    const spk_status st = spk_warm_up(reinterpret_cast<spk_engine*>(engine), film.c_str(), paper.c_str(), &reply);
    if (reply) spk_string_free(reply);
    if (st != SPK_OK) { fail(engine_error("warm up")); return JNI_FALSE; }
    return JNI_TRUE;
}

// Returns the bytes the GPU buffer pool gave back (onTrimMemory).
JNI_FN(jlong, trimMemory)(JNIEnv*, jobject, jlong engine) {
    if (!engine_known(reinterpret_cast<spk_engine*>(engine))) return 0;
    return jlong(spk::view::trim_idle_memory(reinterpret_cast<spk_engine*>(engine)));
}

// True when the last failure on this thread was a cancellation.
JNI_FN(jboolean, lastWasCancelled)(JNIEnv*, jobject) {
    return t_error.find("render cancelled") != std::string::npos || t_error == "RAW open cancelled" ? JNI_TRUE : JNI_FALSE;
}

// True when the last failure on this thread means the GPU device is lost:
// retrying on the same engine cannot succeed (EngineHost.recover).
JNI_FN(jboolean, lastWasDeviceLost)(JNIEnv*, jobject) {
    return device_lost(t_error) ? JNI_TRUE : JNI_FALSE;
}

// Layer 2 for this session's display and export renders (Look.kt's array;
// null or all-neutral clears it). Cheap: no render, the next pack uses it.
JNI_FN(jboolean, setLook)(JNIEnv* env, jobject, jlong handle, jfloatArray values_j) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) { fail("setLook: no session"); return JNI_FALSE; }
    try {
        std::vector<float> v(values_j ? size_t(env->GetArrayLength(values_j)) : 0);
        if (!v.empty()) env->GetFloatArrayRegion(values_j, 0, jsize(v.size()), v.data());
        if (env->ExceptionCheck()) { env->ExceptionClear(); fail("setLook: cannot read values"); return JNI_FALSE; }
        s->look.set_values(std::move(v));
        return JNI_TRUE;
    } catch (const std::bad_alloc&) {
        fail("setLook: out of memory");
    } catch (const std::exception& e) {
        fail(std::string("setLook: ") + e.what());
    } catch (...) {
        fail("setLook: unknown failure");
    }
    return JNI_FALSE;
}

// The user LUT for this session (lut3d.hpp): side^3 RGB floats over encoded
// ProPhoto, red fastest, already baked by CubeLut.kt; mixed in at `strength`
// before every Layer 2 slider. Null or strength 0 clears it.
// False preserves the previous state; lastError explains the refusal.
JNI_FN(jboolean, setLut)(JNIEnv* env, jobject, jlong handle, jfloatArray values_j, jint side, jfloat strength) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) { fail("setLut: no session"); return JNI_FALSE; }
    try {
        if (!std::isfinite(strength)) { fail("setLut: strength must be finite"); return JNI_FALSE; }
        std::shared_ptr<const spk::android::ColourTable> lut;
        if (values_j && strength > 0) {
            if (side < 2 || side > spk::android::Lut3d::kMaxSide ||
                env->GetArrayLength(values_j) != side * side * side * 3) {
                fail("setLut: the table was refused (side or length)"); return JNI_FALSE;
            }
            std::vector<float> v(size_t(side) * side * side * 3);
            if (!v.empty()) env->GetFloatArrayRegion(values_j, 0, jsize(v.size()), v.data());
            if (env->ExceptionCheck()) { env->ExceptionClear(); fail("setLut: cannot read table"); return JNI_FALSE; }
            lut = spk::android::Lut3d::make(std::move(v), side);
        }
        s->look.set_lut(std::move(lut), std::clamp(float(strength), 0.0f, 1.0f));
        return JNI_TRUE;
    } catch (const std::bad_alloc&) {
        fail("setLut: out of memory");
    } catch (const std::exception& e) {
        fail(std::string("setLut: ") + e.what());
    } catch (...) {
        fail("setLut: unknown failure");
    }
    return JNI_FALSE;
}

// The last display render again, through the current look: what an
// adjustment slider shows, in milliseconds, without the engine. Null when
// there is nothing kept (lastError), and the caller renders instead.
JNI_FN(jobject, repack)(JNIEnv* env, jobject, jlong handle, jobject reuse) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s || (!s->last.rgba16 && s->last_float.empty())) { fail("nothing to repack"); return nullptr; }
    if (s->last_revision != s->render_revision) { fail("cached render belongs to previous parameters"); return nullptr; }
    const spk::android::OutputCancel cancel(s->cancel_generation);
    try {
        return pack_into(env, s->last, reuse, s->active_look(), s->xf(), s->last_float.empty() ? nullptr : s->last_float.data(), cancel, s->development());
    } catch (const std::exception& e) {
        fail(std::string("repack: ") + e.what());
    } catch (...) {
        fail("repack: unknown failure");
    }
    return nullptr;
}

// Where this session's pixels go: `target` "sRGB" or "Display P3", for the
// display (matrix and clip) or, `exporting`, a file (CAM16 chroma compression
// into the space, the lightness shoulder off). Engine thread.
JNI_FN(jboolean, setOutput)(JNIEnv* env, jobject, jlong handle, jstring target_j, jboolean exporting) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) { fail("no session"); return JNI_FALSE; }
    try {
        std::string error;
        if (!s->out.fetch(s->engine, str(env, target_j), exporting == JNI_TRUE, error)) {
            fail("output transform: " + error);
            return JNI_FALSE;
        }
        return JNI_TRUE;
    } catch (const std::exception& e) {
        fail(std::string("output transform: ") + e.what());
    } catch (...) {
        fail("output transform: unknown failure");
    }
    return JNI_FALSE;
}

JNI_FN(void, release)(JNIEnv*, jobject, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s) return;
    spk_engine* engine = s->engine;
    spk_result_free(&s->last);   // before its session
    spk_session_release(s->session);
    delete s;
    session_closed(engine);
}

// 极致渲染's JPEG: [bitmap] (ARGB_8888, the finished and framed print) as a
// 4:4:4 baseline JPEG at [quality] with optimised Huffman tables, [icc]
// embedded when not null (jpeg444.cpp). Any thread; false with lastError, and
// no file left behind, on failure (the caller then falls back to
// Bitmap.compress).
JNI_FN(jboolean, writeJpeg444)(JNIEnv* env, jobject, jobject bitmap, jstring path_j, jint quality, jbyteArray icc_j) {
    try {
        AndroidBitmapInfo info{};
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
            info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
            fail("JPEG 4:4:4: the bitmap is not ARGB_8888");
            return JNI_FALSE;
        }
        std::vector<uint8_t> icc;
        if (icc_j) {
            icc.resize(size_t(env->GetArrayLength(icc_j)));
            if (!icc.empty()) env->GetByteArrayRegion(icc_j, 0, jsize(icc.size()), reinterpret_cast<jbyte*>(icc.data()));
        }
        const std::string path = str(env, path_j);
        PixelLock lock(env, bitmap);
        if (!lock.ok) { fail("JPEG 4:4:4: cannot lock bitmap pixels"); return JNI_FALSE; }
        std::string error;
        if (!spk::android::write_jpeg444(path, static_cast<const uint8_t*>(lock.pixels), info.width, info.height,
                                         info.stride, std::clamp(int(quality), 1, 100), icc, error)) {
            fail("JPEG 4:4:4: " + error);
            return JNI_FALSE;
        }
        return JNI_TRUE;
    } catch (const std::exception& e) {
        fail(std::string("JPEG 4:4:4: ") + e.what());
    } catch (...) {
        fail("JPEG 4:4:4: unknown failure");
    }
    return JNI_FALSE;
}
