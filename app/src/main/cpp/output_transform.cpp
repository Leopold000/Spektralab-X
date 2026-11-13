#include "output_transform.hpp"
#include "raw_development.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <limits>
#include <stdexcept>
#include <thread>

#include "core/json.hpp"
#include "layer2.hpp"

namespace spk::android {
namespace upstream {
// spow, cctf_decode_mode, cctf_encode_mode and cam16ucs_into, verbatim.
#include "upstream_output_math.inc"
}  // namespace upstream

namespace {

constexpr int kDecodeN = 4096;    // encoded ProPhoto, 0..1
constexpr int kEncodeN = 16384;   // linear target, 0..1

template <typename F>
void rows(uint32_t h, uint64_t px, F&& body) {
    const unsigned workers = px < 500'000 ? 1u : std::min(8u, std::max(1u, std::thread::hardware_concurrency()));
    if (workers == 1) { body(0u, h); return; }
    const uint32_t chunk = (h + workers - 1) / workers;
    std::vector<std::thread> threads;
    uint32_t done = std::min(h, chunk);
    try {
        for (uint32_t y = chunk; y < h; y += chunk) {
            const uint32_t y1 = std::min(h, y + chunk);
            threads.emplace_back(body, y, y1);
            done = y1;
        }
    } catch (...) {
        // Whatever was not spawned runs here.
    }
    body(0u, std::min(h, chunk));
    if (done < h) body(done, h);
    for (auto& t : threads) t.join();
}

inline float lerp_lut(const std::vector<float>& t, int n, float v) {
    const float p = v * n;
    const int i = std::min(n - 1, int(p));
    return t[i] + (t[i + 1] - t[i]) * (p - i);
}

inline float saturate(float x) { return std::min(1.0f, std::max(0.0f, x)); }

}  // namespace

bool OutputTransform::fetch(spk_engine* engine, const std::string& target, bool exporting, std::string& error) {
    ready_ = false;
    char* json = nullptr;
    const float* cmax = nullptr;
    uint32_t count = 0;
    // Match the Mac file intent (upstream d661b3b957ad): preserve most
    // in-gamut chroma, with a small safety margin at the target boundary.
    // Adobe's explicit TIFF policy is relative-colorimetric linear clipping,
    // without CAM16 compression. Keep the established intents for other spaces.
    const bool adobe_export = exporting && target == "Adobe RGB (1998)";
    const char* spec = exporting && !adobe_export
        ? R"({"lightness_compression_active":false,"knee":[0.7,0.995,6]})"
        : R"({"algorithm":"off"})";
    if (spk_output_transform(engine, "ProPhoto RGB", target.c_str(), spec, &json, &cmax, &count) != SPK_OK) {
        const char* e = spk_last_error(engine);
        error = e && *e ? e : "output transform failed";
        return false;
    }
    spk::Json reply;
    const bool parsed = spk::Json::parse(json, reply, error);
    spk_string_free(json);
    if (!parsed) return false;
    dst_ = target;
    adobe_export_ = adobe_export;
    src_mode_ = uint32_t(reply.at("source_cctf_mode").as_int());
    dst_mode_ = uint32_t(reply.at("target_cctf_mode").as_int());
    const auto& m = reply.at("matrix").items();
    if (m.size() != 9) { error = "output transform: no matrix"; return false; }
    for (size_t i = 0; i < 9; ++i) matrix_[i] = float(m[i].as_double());
    if (adobe_export_) {
        // inv(Adobe D50 PCS) * ROMM D50 PCS, matching IccProfile.kt.
        // The engine's CAT02 D50->D65 matrix does not describe the Adobe
        // profile's Bradford PCS colorants. Keep other file intents unchanged.
        matrix_ = {1.389564687376496941f, -0.169414940507039286f, -0.220232536307972017f,
                  -0.228798904814672891f, 1.231746470222334087f, -0.002947327958333993f,
                  -0.017631431735298570f, -0.096267508428705724f, 1.114304002759633084f};
    }
    const spk::Json& g = reply.at("gamut_compress");
    cam16_ = g.at("algorithm").as_string() == "cam16ucs";
    lightness_ = g.at("lightness_compression_active").as_bool();
    if (cam16_) {
        const auto& mx = g.at("m_to_xyz").items();
        const auto& mr = g.at("m_to_rgb").items();
        const auto& k = g.at("k").items();
        if (mx.size() != 9 || mr.size() != 9 || k.size() != 22) { error = "output transform: bad CAM16 block"; return false; }
        for (size_t i = 0; i < 9; ++i) { m2x_[i] = float(mx[i].as_double()); m2r_[i] = float(mr[i].as_double()); }
        for (size_t i = 0; i < 22; ++i) k_[i] = float(k[i].as_double());
        nl_ = uint32_t(g.at("cmax_rows").as_int());
        nh_ = uint32_t(g.at("cmax_cols").as_int());
        cmax_ = cmax;
        if (!cmax_ || nl_ < 2 || nh_ < 2 || size_t(nl_) * nh_ != count) {
            error = "output transform: C_max table size mismatch";
            return false;
        }
    }
    decode16_.resize(65536);
    for (uint32_t i = 0; i < 65536; ++i) decode16_[i] = upstream::cctf_decode_mode(float(i) / 65535.0f, src_mode_);
    decode_lut_.resize(kDecodeN + 1);
    for (int i = 0; i <= kDecodeN; ++i) decode_lut_[i] = upstream::cctf_decode_mode(float(i) / kDecodeN, src_mode_);
    encode_lut_.resize(kEncodeN + 1);
    for (int i = 0; i <= kEncodeN; ++i) encode_lut_[i] = upstream::cctf_encode_mode(float(i) / kEncodeN, dst_mode_);
    ready_ = true;
    return true;
}

// Tables for the display, where the result is 8 bits; the exact curves for a
// file, and for anything outside 0..1.
float OutputTransform::decode(float v) const {
    if (adobe_export_ || cam16_ || exact_ || v < 0.0f || v > 1.0f) return upstream::cctf_decode_mode(v, src_mode_);
    return lerp_lut(decode_lut_, kDecodeN, v);
}

float OutputTransform::encode(float v) const {
    // Clip before fractional-power encoding, and use the exact curve for files.
    if (adobe_export_) return upstream::cctf_encode_mode(saturate(v), dst_mode_);
    if (cam16_ || exact_ || v < 0.0f || v > 1.0f) return upstream::cctf_encode_mode(v, dst_mode_);
    return lerp_lut(encode_lut_, kEncodeN, v);
}

void OutputTransform::finish(const float v[3], float c[3]) const {
    float lin[3];
    for (int i = 0; i < 3; ++i)
        lin[i] = matrix_[i * 3] * v[0] + matrix_[i * 3 + 1] * v[1] + matrix_[i * 3 + 2] * v[2];
    if (cam16_) upstream::cam16ucs_into(lin, m2x_, m2r_, k_, cmax_, nl_, nh_, lightness_);
    for (int i = 0; i < 3; ++i) c[i] = encode(lin[i]);
}

void OutputTransform::apply(float c[3]) const {
    const float v[3] = {decode(c[0]), decode(c[1]), decode(c[2])};
    finish(v, c);
}

void OutputTransform::apply16(const uint16_t* s, float c[3]) const {
    const float v[3] = {decode16_[s[0]], decode16_[s[1]], decode16_[s[2]]};
    finish(v, c);
}

namespace {
void validate_float_source(const float* src, size_t stride, uint32_t w, uint32_t h,
                           const OutputTransform* xf) {
    if (!src || !w || !h || stride < w ||
        stride > std::numeric_limits<size_t>::max() / h / 3 / sizeof(float))
        throw std::invalid_argument("invalid float RGB row source");
    if (!xf || !xf->ready()) throw std::invalid_argument("float RGB requires an output transform");
}

void finish_float(float c[3], const OutputTransform& xf) {
    uint16_t q[3];
    for (int k = 0; k < 3; ++k) q[k] = uint16_t(std::floor(saturate(c[k]) * 65535.0f + 0.5f));
    xf.apply16(q, c);
}

template <typename Write>
void convert_float(const float* src, size_t stride, uint32_t w, uint32_t h,
                   const Layer2* look, const OutputTransform& xf, const OutputCancel& cancel,
                   const RawDevelopment* development, const Layer2::Window& window, Write&& write) {
    cancel.check();
    validate_float_source(src, stride, w, h, &xf);
    const uint32_t fw = window.full_w ? window.full_w : w;
    const uint32_t fh = window.full_h ? window.full_h : h;
    const Layer2::Frame frame = look ? look->frame(fw, fh) : Layer2::Frame{};
    const float iw = 1.0f / std::max(1u, fw), ih = 1.0f / std::max(1u, fh);
    rows(h, uint64_t(w) * h, [&](uint32_t y0, uint32_t y1) {
        for (uint32_t y = y0; y < y1; ++y) {
            if (cancel.cancelled()) return;
            const float* s = src + size_t(y) * stride * 3;
            const float v = (window.y0 + y + 0.5f) * ih;
            for (uint32_t x = 0; x < w; ++x) {
                float c[3] = {s[3 * x], s[3 * x + 1], s[3 * x + 2]};
                if (development) development->apply_encoded_prophoto(c);
                if (look) look->apply(c[0], c[1], c[2], (window.x0 + x + 0.5f) * iw, v, frame, true);
                finish_float(c, xf);
                write(y, x, c);
            }
        }
    });
    // Workers only poll; report cancellation after every writer has joined.
    cancel.check();
}

template <typename Write>
void convert(const uint16_t* src, size_t src_stride_px, uint32_t w, uint32_t h,
             const Layer2* look, const OutputTransform& xf, const OutputCancel& cancel,
             const Layer2::Window& window, Write&& write) {
    cancel.check();
    const uint32_t fw = window.full_w ? window.full_w : w;
    const uint32_t fh = window.full_h ? window.full_h : h;
    const Layer2::Frame frame = look ? look->frame(fw, fh) : Layer2::Frame{};
    const float iw = 1.0f / std::max(1u, fw), ih = 1.0f / std::max(1u, fh);
    rows(h, uint64_t(w) * h, [&](uint32_t y0, uint32_t y1) {
        for (uint32_t y = y0; y < y1; ++y) {
            if (cancel.cancelled()) return;
            const uint16_t* s = src + size_t(y) * src_stride_px * 4;
            const float v = (window.y0 + y + 0.5f) * ih;
            for (uint32_t x = 0; x < w; ++x) {
                float c[3];
                if (look) {
                    c[0] = s[4 * x] / 65535.0f; c[1] = s[4 * x + 1] / 65535.0f; c[2] = s[4 * x + 2] / 65535.0f;
                    look->apply(c[0], c[1], c[2], (window.x0 + x + 0.5f) * iw, v, frame);
                    // Upstream's grade ends at the UNORM16 boundary before any
                    // output conversion (spk_view_grade_body.glsl): clamp to
                    // 0..1 and round to 16 bits, so CAM16 never sees overshoot.
                    uint16_t q[3];
                    for (int k = 0; k < 3; ++k) q[k] = uint16_t(std::floor(saturate(c[k]) * 65535.0f + 0.5f));
                    xf.apply16(q, c);
                } else {
                    xf.apply16(s + 4 * x, c);
                }
                write(y, x, c);
            }
        }
    });
    cancel.check();
}
}  // namespace

void pack_rgba8(const uint16_t* src, size_t src_stride_px, uint32_t w, uint32_t h,
                uint8_t* out, size_t out_stride, const Layer2* look, const OutputTransform& xf,
                const OutputCancel& cancel, const Layer2::Window& window) {
    convert(src, src_stride_px, w, h, look, xf, cancel, window, [&](uint32_t y, uint32_t x, const float c[3]) {
        uint8_t* d = out + size_t(y) * out_stride + 4 * size_t(x);
        d[0] = uint8_t(std::lround(saturate(c[0]) * 255.0f));
        d[1] = uint8_t(std::lround(saturate(c[1]) * 255.0f));
        d[2] = uint8_t(std::lround(saturate(c[2]) * 255.0f));
        d[3] = 255;
    });
}

void pack_rgba16(const uint16_t* src, size_t src_stride_px, uint32_t w, uint32_t h,
                 std::vector<uint16_t>& out, const Layer2* look, const OutputTransform& xf, const OutputCancel& cancel) {
    cancel.check();
    out.assign(size_t(w) * h * 4, 65535);
    convert(src, src_stride_px, w, h, look, xf, cancel, Layer2::Window{}, [&](uint32_t y, uint32_t x, const float c[3]) {
        uint16_t* d = out.data() + (size_t(y) * w + x) * 4;
        d[0] = uint16_t(std::lround(saturate(c[0]) * 65535.0f));
        d[1] = uint16_t(std::lround(saturate(c[1]) * 65535.0f));
        d[2] = uint16_t(std::lround(saturate(c[2]) * 65535.0f));
    });
}

Rgba16Rows::Rgba16Rows(const uint16_t* src, size_t stride_px, uint32_t w, uint32_t h,
                       const Layer2* look, const OutputTransform* xf)
    : src_(src), stride_(stride_px), w_(w), h_(h), look_(look), xf_(xf) {
    if (!src || !w || !h || stride_px < w ||
        stride_px > std::numeric_limits<size_t>::max() / h / 4 / sizeof(uint16_t))
        throw std::invalid_argument("invalid RGBA16 row source");
    frame_ = look ? look->frame(w, h) : Layer2::Frame{};
}

void pack_float_rgba8(const float* src, size_t src_stride_px, uint32_t w, uint32_t h,
                      uint8_t* out, size_t out_stride, const Layer2* look, const OutputTransform& xf,
                      const OutputCancel& cancel, const RawDevelopment* development,
                      const Layer2::Window& window) {
    convert_float(src, src_stride_px, w, h, look, xf, cancel, development, window, [&](uint32_t y, uint32_t x, const float c[3]) {
        uint8_t* d = out + size_t(y) * out_stride + 4 * size_t(x);
        for (int k = 0; k < 3; ++k) d[k] = uint8_t(std::lround(saturate(c[k]) * 255.0f));
        d[3] = 255;
    });
}

void pack_float_rgba16(const float* src, size_t src_stride_px, uint32_t w, uint32_t h,
                       std::vector<uint16_t>& out, const Layer2* look, const OutputTransform& xf,
                       const OutputCancel& cancel, const RawDevelopment* development) {
    cancel.check();
    validate_float_source(src, src_stride_px, w, h, &xf);
    out.assign(size_t(w) * h * 4, 65535);
    convert_float(src, src_stride_px, w, h, look, xf, cancel, development, Layer2::Window{}, [&](uint32_t y, uint32_t x, const float c[3]) {
        uint16_t* d = out.data() + (size_t(y) * w + x) * 4;
        for (int k = 0; k < 3; ++k) d[k] = uint16_t(std::lround(saturate(c[k]) * 65535.0f));
    });
}

Rgba16Rows::Rgba16Rows(const float* src, size_t stride_px, uint32_t w, uint32_t h,
                       const Layer2* look, const OutputTransform* xf, const RawDevelopment* development)
    : float_src_(src), stride_(stride_px), w_(w), h_(h), look_(look), xf_(xf), development_(development) {
    validate_float_source(src, stride_px, w, h, xf);
    frame_ = look ? look->frame(w, h) : Layer2::Frame{};
}

void Rgba16Rows::pack(uint32_t y0, uint32_t count, std::vector<uint16_t>& out, const OutputCancel& cancel) const {
    cancel.check();
    if (y0 > h_ || count > h_ - y0) throw std::out_of_range("RGBA16 row range");
    out.assign(size_t(w_) * count * 4, 65535);
    const float iw = 1.0f / std::max(1u, w_), ih = 1.0f / std::max(1u, h_);
    for (uint32_t y = y0; y < y0 + count; ++y) {
        cancel.check();
        const uint16_t* s = src_ ? src_ + size_t(y) * stride_ * 4 : nullptr;
        const float* f = float_src_ ? float_src_ + size_t(y) * stride_ * 3 : nullptr;
        uint16_t* d = out.data() + size_t(y - y0) * w_ * 4;
        const float v = (y + 0.5f) * ih;
        for (uint32_t x = 0; x < w_; ++x) {
            float c[3];
            if (f) {
                c[0] = f[3 * x]; c[1] = f[3 * x + 1]; c[2] = f[3 * x + 2];
                if (development_) development_->apply_encoded_prophoto(c);
                if (look_) look_->apply(c[0], c[1], c[2], (x + 0.5f) * iw, v, frame_, true);
                finish_float(c, *xf_);
            } else if (look_) {
                c[0] = s[4 * x] / 65535.0f; c[1] = s[4 * x + 1] / 65535.0f; c[2] = s[4 * x + 2] / 65535.0f;
                look_->apply(c[0], c[1], c[2], (x + 0.5f) * iw, v, frame_);
                if (xf_) {
                    uint16_t q[3];
                    for (int k = 0; k < 3; ++k) q[k] = uint16_t(std::floor(saturate(c[k]) * 65535.0f + 0.5f));
                    xf_->apply16(q, c);
                }
            } else if (xf_) {
                xf_->apply16(s + 4 * x, c);
            } else {
                std::copy_n(s + 4 * x, 4, d + 4 * x);
                continue;
            }
            for (int k = 0; k < 3; ++k) d[4 * x + k] = uint16_t(std::lround(saturate(c[k]) * 65535.0f));
        }
    }
}

Rgba8Rows::Rgba8Rows(const uint16_t* src, size_t stride_px, uint32_t w, uint32_t h,
                     const Layer2* look, const OutputTransform* xf)
    : src_(src), stride_(stride_px), w_(w), h_(h), source_rows_(h), look_(look), xf_(xf) {
    if (!src || !w || !h || stride_px < w ||
        stride_px > std::numeric_limits<size_t>::max() / h / 4 / sizeof(uint16_t) ||
        !xf || !xf->ready()) throw std::invalid_argument("invalid JPEG RGBA16 row source");
    frame_ = look ? look->frame(w, h) : Layer2::Frame{};
}

Rgba8Rows::Rgba8Rows(const float* src, size_t stride_px, uint32_t w, uint32_t h,
                     const Layer2* look, const OutputTransform* xf, const RawDevelopment* development)
    : Rgba8Rows(src, stride_px, w, h, 0, h, look, xf, nullptr, development) {}

Rgba8Rows::Rgba8Rows(const float* src, size_t stride_px, uint32_t w, uint32_t h,
                     uint32_t source_y0, uint32_t source_rows, const Layer2* look, const OutputTransform* xf,
                     const Layer2::Frame* frame, const RawDevelopment* development)
    : float_src_(src), stride_(stride_px), w_(w), h_(h), source_y0_(source_y0),
      source_rows_(source_rows), look_(look), xf_(xf), development_(development) {
    validate_float_source(src, stride_px, w, h, xf);
    if (!source_rows || source_y0 > h || source_rows > h - source_y0)
        throw std::invalid_argument("invalid JPEG float strip range");
    frame_ = frame ? *frame : look ? look->frame(w, h) : Layer2::Frame{};
}

void Rgba8Rows::pack(uint32_t y0, uint32_t count, std::vector<uint8_t>& out, const OutputCancel& cancel) const {
    cancel.check();
    if (y0 > h_ || count > h_ - y0) throw std::out_of_range("RGBA8 row range");
    if (y0 < source_y0_ || y0 - source_y0_ > source_rows_ || count > source_rows_ - (y0 - source_y0_))
        throw std::out_of_range("RGBA8 source strip range");
    out.resize(size_t(w_) * count * 4);
    const float iw = 1.0f / w_, ih = 1.0f / h_;
    for (uint32_t y = y0; y < y0 + count; ++y) {
        cancel.check();
        const uint16_t* s = src_ ? src_ + size_t(y) * stride_ * 4 : nullptr;
        const float* f = float_src_ ? float_src_ + size_t(y - source_y0_) * stride_ * 3 : nullptr;
        uint8_t* d = out.data() + size_t(y - y0) * w_ * 4;
        const float v = (y + 0.5f) * ih;
        for (uint32_t x = 0; x < w_; ++x) {
            float c[3];
            if (f) {
                c[0] = f[3 * x]; c[1] = f[3 * x + 1]; c[2] = f[3 * x + 2];
                if (development_) development_->apply_encoded_prophoto(c);
                if (look_) look_->apply(c[0], c[1], c[2], (x + 0.5f) * iw, v, frame_, true);
                finish_float(c, *xf_);
            } else if (look_) {
                c[0] = s[4 * x] / 65535.0f; c[1] = s[4 * x + 1] / 65535.0f; c[2] = s[4 * x + 2] / 65535.0f;
                look_->apply(c[0], c[1], c[2], (x + 0.5f) * iw, v, frame_);
                finish_float(c, *xf_);
            } else {
                xf_->apply16(s + 4 * x, c);
            }
            for (int k = 0; k < 3; ++k) d[4 * x + k] = uint8_t(std::lround(saturate(c[k]) * 255.0f));
            d[4 * x + 3] = 255;
        }
    }
}

}  // namespace spk::android
