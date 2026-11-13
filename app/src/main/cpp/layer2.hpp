// layer2.hpp -- adjustments to the finished print, applied while its pixels
// are packed for display or export. Nothing here reaches the engine.
//
// A port of upstream SpektraLab's Layer 2 (modern_UI Canvas/Shaders.metal,
// `layer2Tone`, and Model/Mask.swift `Layer2Uniforms.tone`), the adjustments a
// scan of a print gets afterwards, in the same order and with the same
// constants:
//
//   white balance -> exposure/contrast/brightness -> highlights/shadows
//   -> black/white point -> saturation (+ vibrance) -> colour balance
//   -> tone curves -> HSL (8 bands) -> fade -> vignette
//
// Vibrance, the HSL bands and fade are this port's additions. The integer
// film path retains upstream's exposure arithmetic; no-film float corrects
// exposure for the actual transfer. Both bound SDR tone weights. It runs on the engine's
// encoded ProPhoto output, before the output transform, and pivots on
// ProPhoto's encoded mid-grey (0.3857, upstream's `proPhotoMidGrey`).
#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>
#include <vector>
#include "output_cancel.hpp"

namespace spk::android {

namespace local { class Local; struct Placed; }

// Index of each value in the float array Kotlin sends (Look.kt, same order).
enum LookIndex : int {
    kTemperature = 0, kTint, kExposure, kContrast, kBrightness, kSaturation, kVibrance,
    kHighlights, kShadows, kBlackPoint, kWhitePoint, kFade, kVignette, kVignetteMid,
    kCbMaster = 14,          // hue, saturation, luminance; then shadows, midtones, highlights
    kHsl = 26,               // 8 bands x (hue, saturation, luminance)
    kLookSize = 50,
    // Optional tail: the five tone curves in upstream's row order (rgb, luma,
    // red, green, blue), each a point count n (2..256) then n (x, y) pairs,
    // or kSampledCurveRow followed by kCurveSamples values in [0, 1].
    // Missing rows are identity; a malformed tail drops all five.
    kCurves = 50,
    // After all five rows, optionally: masks and dust (layer2_local.hpp).
};

inline constexpr int kCurveSamples = 256;
inline constexpr float kSampledCurveRow = -256.0f;

// A colour table applied before everything else in Layer 2 (a user LUT,
// lut3d.hpp): encoded ProPhoto 0..1 in and out. An interface so this file
// builds without the table's implementation.
struct ColourTable {
    virtual ~ColourTable() = default;
    virtual void apply(float& r, float& g, float& b) const = 0;
};

class Layer2 {
public:
    // `values` in UI units (Look.kt); fewer than kLookSize, or all neutral, is identity.
    explicit Layer2(const std::vector<float>& values);
    bool identity() const { return identity_ && !lut_; }

    // What masks and dust need of the frame about to be packed: its shape and
    // its pixel, with their geometry laid out for that size. Built once per
    // pack, before the rows run, and only read by them; empty (and free) when
    // the look has neither. The pack functions below build their own.
    struct Frame {
        std::shared_ptr<const local::Placed> placed;
    };
    Frame frame(uint32_t w, uint32_t h) const;

    // A window of a larger frame (patch 0055). `full_w/full_h` are the frame
    // the look's normalised geometry (vignette, masks, dust) was drawn for;
    // `x0/y0` is where this window starts in it. All zero is the frame itself,
    // the display and queue path byte for byte. A tiled export passes the
    // film-edge canvas and each tile's origin, so every tile's vignette,
    // mask and dust is the whole canvas's, not its own.
    struct Window {
        uint32_t full_w = 0, full_h = 0, x0 = 0, y0 = 0;
    };

    // The table mixed in at `strength` (0..1) before white balance, after the
    // print and ahead of every slider. Null or strength 0 clears it.
    void set_lut(std::shared_ptr<const ColourTable> lut, float strength);

    // One pixel, encoded, in place. (u, v) is its position in the frame, 0..1.
    void apply(float& r, float& g, float& b, float u, float v) const { apply(r, g, b, u, v, Frame{}); }
    // The same with `f`, this look's frame(w, h) for the frame (u, v) is in;
    // without it masks and dust are skipped.
    // Unquantised no-film exposure uses the actual piecewise ProPhoto transfer
    // in linear light. The RGBA16 path retains its legacy exposure arithmetic.
    void apply(float& r, float& g, float& b, float u, float v, const Frame& f,
               bool unquantised = false) const;

private:
    bool identity_ = true;
    float wb_[3] = {1, 1, 1};
    float exposure_gain_ = 1;   // in the encoded domain: (2^ev)^(1/2.2)
    double linear_exposure_gain_ = 1, encoded_exposure_gain_ = 1;
    float contrast_ = 0, brightness_ = 0, saturation_ = 1, vibrance_ = 0;
    float highlights_ = 0, shadows_ = 0, black_ = 0, white_ = 0, fade_ = 0;
    float vignette_ = 0, vignette_mid_ = 0.5f;
    float cb_[4][3] = {}, cb_lum_[4] = {};
    bool cb_active_ = false;
    float hsl_[8][3] = {};
    bool hsl_active_ = false;
    std::vector<float> curves_;       // 5 x 256 (upstream's table); empty when every curve is identity
    std::vector<float> bright_lut_;   // c^(1/(1+0.8 b)) over [0, 2]
    std::shared_ptr<const ColourTable> lut_;
    float lut_strength_ = 0;
    std::shared_ptr<const local::Local> local_;   // masks and dust; null when there are none
};

// RGBA16 rows to RGBA8 (display, JPEG) or back into RGBA16 (TIFF), through
// `look`, on up to eight threads.
void pack_rgba8_look(const Layer2& look, const uint16_t* src, size_t src_stride_px,
                     uint32_t w, uint32_t h, uint8_t* out, size_t out_stride,
                     const OutputCancel& cancel = {}, const Window& window = {});
void apply_rgba16_in_place(const Layer2& look, uint16_t* px, size_t stride_px, uint32_t w, uint32_t h,
                           const Window& window = {});

}  // namespace spk::android
