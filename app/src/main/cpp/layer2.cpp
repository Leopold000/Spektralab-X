#include "layer2.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <thread>

#include "layer2_local.hpp"
#include "prophoto_exposure.hpp"
#include "tone_curve.hpp"

namespace spk::android {
namespace {

// Mid-grey (linear 0.18) encoded in the space the values are in: ProPhoto
// RGB, gamma 1.8 -- upstream's `proPhotoMidGrey`, RFC-018 D1.
constexpr float kMidGrey = 0.3857f;   // 0.18^(1/1.8)
constexpr float kPi = 3.14159265358979f;
constexpr float kBandHue[8] = {0, 30, 60, 120, 180, 240, 270, 300};   // red ... magenta

inline float luma(float r, float g, float b) { return 0.2126f * r + 0.7152f * g + 0.0722f * b; }
inline float saturate(float x) { return std::min(1.0f, std::max(0.0f, x)); }

// Upstream's `tonePosition`: 0 black, 0.5 mid-grey, 1 white, piecewise linear.
inline float tone_position(float l) {
    return l <= kMidGrey ? 0.5f * l / kMidGrey : 0.5f + 0.5f * (l - kMidGrey) / (1.0f - kMidGrey);
}

inline float smoothstep(float e0, float e1, float x) {
    const float t = saturate((x - e0) / (e1 - e0));
    return t * t * (3.0f - 2.0f * t);
}

// A colour wheel position as an RGB offset (upstream ColorZone.rgbOffset).
void wheel(float hue_deg, float sat, float out[3]) {
    const float h = hue_deg * kPi / 180.0f, s = sat * 0.25f;
    out[0] = std::cos(h) * s;
    out[1] = std::cos(h - 2 * kPi / 3) * s;
    out[2] = std::cos(h + 2 * kPi / 3) * s;
}

float hue_to_rgb(float p, float q, float t) {
    if (t < 0) t += 1;
    if (t > 1) t -= 1;
    if (t < 1.0f / 6) return p + (q - p) * 6 * t;
    if (t < 0.5f) return q;
    if (t < 2.0f / 3) return p + (q - p) * (2.0f / 3 - t) * 6;
    return p;
}

bool valid_sampled_row(const std::vector<float>& v, size_t at) {
    if (v.size() - at < size_t(kCurveSamples)) return false;
    return std::all_of(v.begin() + at, v.begin() + at + kCurveSamples,
                       [](float x) { return std::isfinite(x) && x >= 0 && x <= 1; });
}

// The kCurves tail as upstream's table: five rows of 256, row-major. Empty
// when the tail is missing or malformed, or all five curves are identity.
// Rows whose Double control points collapse in Float arrive already sampled
// at the same 256 positions; ordinary control-point rows retain their math.
std::vector<float> curve_table(const std::vector<float>& v) {
    std::vector<float> table(5 * 256);
    std::vector<std::array<double, 2>> p;
    size_t at = kCurves;
    bool active = false;
    for (int row = 0; row < 5; ++row) {
        p.clear();
        if (at < v.size()) {
            const float n = v[at++];
            if (n == kSampledCurveRow) {
                if (!valid_sampled_row(v, at)) return {};
                std::copy_n(v.begin() + at, kCurveSamples, table.begin() + row * kCurveSamples);
                at += kCurveSamples;
                active = true;
                continue;
            }
            if (!(n >= 2 && n <= 256) || n != std::floor(n) || at + 2 * size_t(n) > v.size()) return {};
            for (int i = 0; i < int(n); ++i, at += 2) {
                const double x = v[at], y = v[at + 1];
                if (!std::isfinite(x) || !std::isfinite(y) || x < 0 || x > 1 || y < 0 || y > 1 ||
                    (!p.empty() && x <= p.back()[0]))
                    return {};
                p.push_back({x, y});
            }
        } else {
            p = {{0, 0}, {1, 1}};
        }
        if (p.size() != 2 || p[0] != std::array<double, 2>{0, 0} || p[1] != std::array<double, 2>{1, 1}) active = true;
        const ToneCurve curve(p);
        for (int x = 0; x < 256; ++x) table[row * 256 + x] = float(curve.at(double(x) / 255));
    }
    if (!active) return {};
    return table;
}

// Where the five curve rows end, or 0 when the tail is short of them or a
// count is bad (the masks and dust block only follows a complete set).
size_t curves_end(const std::vector<float>& v) {
    size_t at = kCurves;
    for (int row = 0; row < 5; ++row) {
        if (at >= v.size()) return 0;
        const float n = v[at++];
        if (n == kSampledCurveRow) {
            if (!valid_sampled_row(v, at)) return 0;
            at += kCurveSamples;
            continue;
        }
        if (!(n >= 2 && n <= 256) || n != std::floor(n) || at + 2 * size_t(n) > v.size()) return 0;
        at += 2 * size_t(n);
    }
    return at;
}

// One row of the table at v, as upstream's shader samples it: clamped to
// 0..1, 255 intervals, linear between entries (GLSL mix).
inline float lookup(const float* row, float v) {
    const float x = saturate(v) * 255.0f;
    const int i = int(std::floor(x));
    const float f = x - float(i);
    return row[i] * (1 - f) + row[std::min(i + 1, 255)] * f;
}

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
    }
    body(0u, std::min(h, chunk));
    if (done < h) body(done, h);
    for (auto& t : threads) t.join();
}

}  // namespace

Layer2::Layer2(const std::vector<float>& v) {
    if (v.size() < size_t(kLookSize)) return;
    curves_ = curve_table(v);
    if (const size_t end = curves_end(v)) local_ = local::Local::parse(v, end);
    // With only masks or dust, the global path still runs: at neutral it
    // leaves every value exactly as it was.
    bool any = !curves_.empty() || local_;
    for (int i = 0; i < kLookSize; ++i)
        if (i != kVignetteMid && std::isfinite(v[i]) && v[i] != 0.0f) any = true;
    if (!any) return;
    identity_ = false;
    auto at = [&](int i) { return std::isfinite(v[i]) ? v[i] : 0.0f; };

    const float t = at(kTemperature) / 100, g = at(kTint) / 100;
    wb_[0] = 1 + 0.18f * t; wb_[1] = 1 - 0.12f * g; wb_[2] = 1 - 0.18f * t;
    // pow(pow(c, 2.2) * 2^ev, 1/2.2) is c * 2^(ev/2.2): the same, without two pows a pixel.
    exposure_gain_ = std::pow(2.0f, at(kExposure) / 2.2f);
    linear_exposure_gain_ = std::exp2(double(at(kExposure)));
    encoded_exposure_gain_ = std::exp2(double(at(kExposure)) / 1.8);
    contrast_ = at(kContrast) / 100;
    brightness_ = at(kBrightness) / 100;
    saturation_ = 1 + at(kSaturation) / 100;
    vibrance_ = at(kVibrance) / 100;
    highlights_ = at(kHighlights) / 100;
    shadows_ = at(kShadows) / 100;
    black_ = at(kBlackPoint) / 100;
    white_ = at(kWhitePoint) / 100;
    fade_ = at(kFade) / 100 * 0.15f;
    vignette_ = at(kVignette) / 100;
    vignette_mid_ = (std::isfinite(v[kVignetteMid]) ? v[kVignetteMid] : 50.0f) / 100;
    for (int z = 0; z < 4; ++z) {
        const float hue = at(kCbMaster + 3 * z), sat = at(kCbMaster + 3 * z + 1) / 100;
        wheel(hue, sat, cb_[z]);
        // x 0.25, as upstream's grade (view_process.cpp u[28..31], Adjustments.swift cbLum).
        cb_lum_[z] = at(kCbMaster + 3 * z + 2) / 100 * 0.25f;
        if (sat != 0 || cb_lum_[z] != 0) cb_active_ = true;
    }
    for (int b = 0; b < 8; ++b)
        for (int k = 0; k < 3; ++k) {
            hsl_[b][k] = at(kHsl + 3 * b + k) / 100;
            if (hsl_[b][k] != 0) hsl_active_ = true;
        }
    if (brightness_ != 0) {
        bright_lut_.resize(4097);
        const float e = 1.0f / (1.0f + brightness_ * 0.8f);
        for (int i = 0; i <= 4096; ++i) bright_lut_[i] = std::pow(i * 2.0f / 4096, e);
    }
}

void Layer2::set_lut(std::shared_ptr<const ColourTable> lut, float strength) {
    const float s = std::isfinite(strength) ? std::min(1.0f, std::max(0.0f, strength)) : 0.0f;
    lut_ = s > 0 ? std::move(lut) : nullptr;
    lut_strength_ = lut_ ? s : 0;
}

void Layer2::apply(float& r, float& g, float& b, float u, float v, const Frame& f, bool unquantised) const {
    if (lut_) {
        float lr = r, lg = g, lb = b;
        lut_->apply(lr, lg, lb);
        r += (lr - r) * lut_strength_; g += (lg - g) * lut_strength_; b += (lb - b) * lut_strength_;
        if (identity_) return;   // the table alone: the sliders are all neutral
    }
    float c[3] = {r * wb_[0], g * wb_[1], b * wb_[2]};
    if (unquantised ? linear_exposure_gain_ != 1.0 : exposure_gain_ != 1.0f) {
        for (float& x : c) {
            if (unquantised) x = expose_encoded_prophoto(x, linear_exposure_gain_, encoded_exposure_gain_);
            else x = std::max(0.0f, x) * exposure_gain_;
        }
    }
    if (contrast_ != 0) {
        const float k = 1 + contrast_ * 1.2f;
        for (float& x : c) x = (x - kMidGrey) * k + kMidGrey;
    }
    if (!bright_lut_.empty()) {
        for (float& x : c) {
            // Exposure/contrast can exceed the table domain; later tone or
            // saturation controls can still return that light below white.
            if (unquantised || x > 2.0f) {
                x = std::pow(std::max(0.0f, x), 1.0f / (1.0f + brightness_ * 0.8f));
                continue;
            }
            const float p = std::min(2.0f, std::max(0.0f, x)) * 2048.0f;
            const int i = std::min(4095, int(p));
            x = bright_lut_[i] + (bright_lut_[i + 1] - bright_lut_[i]) * (p - i);
        }
    }
    // Even SDR film codes can exceed white after exposure or contrast.
    // Bound the tone weights, while retaining the pixels' highlight headroom.
    float l = saturate(tone_position(luma(c[0], c[1], c[2])));
    if (shadows_ != 0) {
        const float w = (1 - l) * (1 - l);
        for (float& x : c) x += shadows_ * 0.25f * w * (1 - x);
    }
    if (highlights_ != 0) {
        const float w = l * l;
        for (float& x : c) x += highlights_ * 0.25f * w * (highlights_ > 0 ? 1 - x : x);
    }
    const float span = std::max(1 - black_ - white_, 0.05f);
    for (float& x : c) x = (x - black_) / span;
    l = luma(c[0], c[1], c[2]);
    float sat = saturation_;
    if (vibrance_ != 0) {
        // Vibrance: the less saturated a pixel is, the more it moves.
        const float chroma = std::max(c[0], std::max(c[1], c[2])) - std::min(c[0], std::min(c[1], c[2]));
        sat *= 1 + vibrance_ * (1 - saturate(chroma * 1.6f));
    }
    if (sat != 1) for (float& x : c) x = l + (x - l) * sat;
    if (cb_active_) {
        const float ls = saturate(tone_position(l));
        const float wS = (1 - ls) * (1 - ls), wH = ls * ls, wM = std::max(1 - wS - wH, 0.0f);
        const float k = 1 + cb_lum_[0] + cb_lum_[1] * wS + cb_lum_[2] * wM + cb_lum_[3] * wH;
        for (int i = 0; i < 3; ++i)
            c[i] = (c[i] + cb_[0][i] + cb_[1][i] * wS + cb_[2][i] * wM + cb_[3][i] * wH) * k;
    }
    if (!curves_.empty()) {
        // Upstream's grade: luma curve as a gain, then RGB on every channel, then each channel's own.
        const float* t = curves_.data();
        for (float& x : c) x = saturate(x);
        const float ly = luma(c[0], c[1], c[2]), ly2 = lookup(t + 256, ly);
        const float k = ly > 1e-4f ? ly2 / ly : 1.0f;
        for (float& x : c) x = lookup(t, saturate(x * k));
        for (int i = 0; i < 3; ++i) c[i] = lookup(t + 256 * (2 + i), c[i]);
    }
    if (hsl_active_) {
        for (float& x : c) x = saturate(x);   // HSL is defined on 0..1
        const float mx = std::max(c[0], std::max(c[1], c[2])), mn = std::min(c[0], std::min(c[1], c[2]));
        const float d = mx - mn;
        if (d > 1e-4f) {
            const float L = (mx + mn) / 2;
            float S = d / std::max(1e-4f, 1 - std::fabs(2 * L - 1));
            float H;
            if (mx == c[0]) H = 60 * std::fmod((c[1] - c[2]) / d + 6, 6.0f);
            else if (mx == c[1]) H = 60 * ((c[2] - c[0]) / d + 2);
            else H = 60 * ((c[0] - c[1]) / d + 4);
            // Triangular weights between the two bands either side of the hue.
            int lo = 7;
            for (int i = 0; i < 8; ++i) if (H >= kBandHue[i]) lo = i;
            const int hi = (lo + 1) % 8;
            const float a = kBandHue[lo], bnd = hi == 0 ? 360.0f : kBandHue[hi];
            const float t = saturate((H - a) / (bnd - a));
            float dh = 0, ds = 0, dl = 0;
            for (int k = 0; k < 3; ++k) {
                const float x = hsl_[lo][k] * (1 - t) + hsl_[hi][k] * t;
                (k == 0 ? dh : k == 1 ? ds : dl) = x;
            }
            const float S0 = saturate(S);
            H = std::fmod(H + dh * 30 + 360, 360.0f);
            S = saturate(S0 * (1 + ds));
            // Lightness moves the coloured pixels only, so greys of the same hue stay put.
            const float Ln = saturate(L + dl * 0.25f * S0);
            const float q = Ln < 0.5f ? Ln * (1 + S) : Ln + S - Ln * S, p = 2 * Ln - q, h = H / 360;
            c[0] = hue_to_rgb(p, q, h + 1.0f / 3);
            c[1] = hue_to_rgb(p, q, h);
            c[2] = hue_to_rgb(p, q, h - 1.0f / 3);
        }
    }
    // Upstream's step 8: masks after the global adjustments and curves, before the vignette.
    if (local_ && f.placed) local_->masks(*f.placed, c, u, v, unquantised);
    if (fade_ != 0) for (float& x : c) x = fade_ + saturate(x) * (1 - fade_);
    if (vignette_ != 0) {
        const float px = (u - 0.5f) * 2, py = (v - 0.5f) * 2;
        const float fall = smoothstep(vignette_mid_ * 1.2f, 1.5f, std::sqrt(px * px + py * py));
        const float k = 1 + vignette_ * fall * 0.9f;
        for (float& x : c) x *= k;
    }
    // Dust last: it sits on the scan, over everything the print had.
    if (local_ && f.placed) local_->dust(*f.placed, c, u, v);
    r = c[0]; g = c[1]; b = c[2];
}

Layer2::Frame Layer2::frame(uint32_t w, uint32_t h) const {
    return local_ ? Frame{local_->place(w, h)} : Frame{};
}

void pack_rgba8_look(const Layer2& look, const uint16_t* src, size_t src_stride_px,
                     uint32_t w, uint32_t h, uint8_t* out, size_t out_stride,
                     const OutputCancel& cancel, const Window& window) {
    cancel.check();
    const uint32_t fw = window.full_w ? window.full_w : w;
    const uint32_t fh = window.full_h ? window.full_h : h;
    const Layer2::Frame frame = look.frame(fw, fh);
    const float iw = 1.0f / std::max(1u, fw), ih = 1.0f / std::max(1u, fh);
    rows(h, uint64_t(w) * h, [&](uint32_t y0, uint32_t y1) {
        for (uint32_t y = y0; y < y1; ++y) {
            if (cancel.cancelled()) return;
            const uint16_t* s = src + size_t(y) * src_stride_px * 4;
            uint8_t* d = out + size_t(y) * out_stride;
            const float v = (window.y0 + y + 0.5f) * ih;
            for (uint32_t x = 0; x < w; ++x) {
                float r = s[4 * x] / 65535.0f, g = s[4 * x + 1] / 65535.0f, b = s[4 * x + 2] / 65535.0f;
                look.apply(r, g, b, (window.x0 + x + 0.5f) * iw, v, frame);
                d[4 * x + 0] = uint8_t(std::lround(saturate(r) * 255.0f));
                d[4 * x + 1] = uint8_t(std::lround(saturate(g) * 255.0f));
                d[4 * x + 2] = uint8_t(std::lround(saturate(b) * 255.0f));
                d[4 * x + 3] = 255;
            }
        }
    });
    cancel.check();
}

void apply_rgba16_in_place(const Layer2& look, uint16_t* px, size_t stride_px, uint32_t w, uint32_t h,
                           const Window& window) {
    const uint32_t fw = window.full_w ? window.full_w : w;
    const uint32_t fh = window.full_h ? window.full_h : h;
    const Layer2::Frame frame = look.frame(fw, fh);
    const float iw = 1.0f / std::max(1u, fw), ih = 1.0f / std::max(1u, fh);
    rows(h, uint64_t(w) * h, [&](uint32_t y0, uint32_t y1) {
        for (uint32_t y = y0; y < y1; ++y) {
            uint16_t* s = px + size_t(y) * stride_px * 4;
            const float v = (window.y0 + y + 0.5f) * ih;
            for (uint32_t x = 0; x < w; ++x) {
                float r = s[4 * x] / 65535.0f, g = s[4 * x + 1] / 65535.0f, b = s[4 * x + 2] / 65535.0f;
                look.apply(r, g, b, (window.x0 + x + 0.5f) * iw, v, frame);
                s[4 * x + 0] = uint16_t(std::lround(saturate(r) * 65535.0f));
                s[4 * x + 1] = uint16_t(std::lround(saturate(g) * 65535.0f));
                s[4 * x + 2] = uint16_t(std::lround(saturate(b) * 65535.0f));
            }
        }
    });
}

}  // namespace spk::android
