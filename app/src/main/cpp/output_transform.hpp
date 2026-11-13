// output_transform.hpp -- leaving the engine's working space, as upstream does.
//
// The engine renders encoded ProPhoto RGB (spk_open's convention, RFC-018).
// Upstream's hosts convert once per destination, and so does this file:
//
//   display  matrix and clip into sRGB or Display P3 (upstream's view path,
//            `display_transform`, gamut compression off);
//   export   the same matrix, then CAM16-UCS chroma compression into the
//            file's space with the lightness shoulder off -- the film print
//            already applied it. The late chroma knee follows the Mac's
//            ColourManagement.swift file intent (d661b3b957ad, 2026-11-06).
//            Explicit Adobe RGB TIFF instead clips target linear RGB to [0,1]
//            before its exact fractional-power encoding, without CAM16.
//
// Existing intents use spk_output_transform's metadata. Adobe's explicit
// file intent uses the ROMM/Adobe D50 PCS matrices matching IccProfile.kt.
// Transfer curves and CAM16 arithmetic come from engine/host/output.cpp,
// copied in at configure time by CMakeLists.txt.
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

#include "spektrafilm/spk_engine.h"
#include "layer2.hpp"
#include "output_cancel.hpp"

namespace spk::android {

class Layer2;
class RawDevelopment;

class OutputTransform {
public:
    // Named engine target; Adobe TIFF uses linear clipping, other exports CAM16.
    bool fetch(spk_engine* engine, const std::string& target, bool exporting, std::string& error);
    bool ready() const { return ready_; }
    const std::string& target() const { return dst_; }
    bool exporting() const { return cam16_; }
    // Exact transfer curves everywhere, not the display tables (parity tests).
    void set_exact(bool on) { exact_ = on; }

    // Encoded ProPhoto in, encoded target out; Adobe export clips linear RGB.
    void apply(float c[3]) const;
    // The same from 16-bit engine codes, through an exact table.
    void apply16(const uint16_t* s, float c[3]) const;

private:
    float decode(float v) const;
    float encode(float v) const;
    void finish(const float v[3], float c[3]) const;

    bool ready_ = false, cam16_ = false, lightness_ = false, exact_ = false;
    bool adobe_export_ = false;
    std::string dst_;
    uint32_t src_mode_ = 1, dst_mode_ = 0;
    std::array<float, 9> matrix_{}, m2x_{}, m2r_{};
    std::array<float, 22> k_{};
    uint32_t nl_ = 0, nh_ = 0;
    const float* cmax_ = nullptr;
    std::vector<float> decode16_;   // every 16-bit code, exact
    std::vector<float> decode_lut_, encode_lut_;
};

// Engine RGBA16 (encoded ProPhoto) -> Layer 2 (optional) -> `xf` -> RGBA8.
void pack_rgba8(const uint16_t* src, size_t src_stride_px, uint32_t w, uint32_t h,
                uint8_t* out, size_t out_stride, const Layer2* look, const OutputTransform& xf,
                const OutputCancel& cancel = {}, const Layer2::Window& window = {});
// The same into RGBA16 with a row stride of `w` pixels (TIFF).
void pack_rgba16(const uint16_t* src, size_t src_stride_px, uint32_t w, uint32_t h,
                 std::vector<uint16_t>& out, const Layer2* look, const OutputTransform& xf,
                 const OutputCancel& cancel = {});

// No-film encoded ProPhoto RGB: grade first, quantise once, then convert.
void pack_float_rgba8(const float* src, size_t src_stride_px, uint32_t w, uint32_t h,
                      uint8_t* out, size_t out_stride, const Layer2* look, const OutputTransform& xf,
                      const OutputCancel& cancel = {}, const RawDevelopment* development = nullptr,
                      const Layer2::Window& window = {});
void pack_float_rgba16(const float* src, size_t src_stride_px, uint32_t w, uint32_t h,
                       std::vector<uint16_t>& out, const Layer2* look, const OutputTransform& xf,
                       const OutputCancel& cancel = {}, const RawDevelopment* development = nullptr);

// One immutable full-frame geometry, with only the requested output rows in
// memory. Called concurrently by TIFF workers; each supplies its own storage.
class Rgba16Rows {
public:
    Rgba16Rows(const uint16_t* src, size_t stride_px, uint32_t w, uint32_t h,
               const Layer2* look, const OutputTransform* xf);
    Rgba16Rows(const float* src, size_t stride_px, uint32_t w, uint32_t h,
               const Layer2* look, const OutputTransform* xf, const RawDevelopment* development = nullptr);
    void pack(uint32_t y0, uint32_t count, std::vector<uint16_t>& out, const OutputCancel& cancel = {}) const;

private:
    const uint16_t* src_ = nullptr;
    const float* float_src_ = nullptr;
    size_t stride_;
    uint32_t w_, h_;
    const Layer2* look_;
    const OutputTransform* xf_;
    const RawDevelopment* development_ = nullptr;
    Layer2::Frame frame_;
};

// JPEG conversion with full-frame mask/texture coordinates and bounded rows.
class Rgba8Rows {
public:
    Rgba8Rows(const uint16_t* src, size_t stride_px, uint32_t w, uint32_t h,
              const Layer2* look, const OutputTransform* xf);
    Rgba8Rows(const float* src, size_t stride_px, uint32_t w, uint32_t h,
              const Layer2* look, const OutputTransform* xf, const RawDevelopment* development = nullptr);
    // Borrowed strip storage, retaining complete-frame grade/mask coordinates.
    // A supplied frame must belong to this look and complete output dimensions.
    Rgba8Rows(const float* src, size_t stride_px, uint32_t w, uint32_t h,
              uint32_t source_y0, uint32_t source_rows, const Layer2* look, const OutputTransform* xf,
              const Layer2::Frame* frame = nullptr, const RawDevelopment* development = nullptr);
    void pack(uint32_t y0, uint32_t count, std::vector<uint8_t>& out, const OutputCancel& cancel = {}) const;

private:
    const uint16_t* src_ = nullptr;
    const float* float_src_ = nullptr;
    size_t stride_;
    uint32_t w_, h_;
    uint32_t source_y0_ = 0, source_rows_ = 0;
    const Layer2* look_;
    const OutputTransform* xf_;
    const RawDevelopment* development_ = nullptr;
    Layer2::Frame frame_;
};

}  // namespace spk::android
