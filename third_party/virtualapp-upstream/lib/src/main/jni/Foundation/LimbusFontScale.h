#pragma once
#include <algorithm>
#include <cmath>

namespace limbus_font_scale {
struct Basis {
    float point, gradient, normal, bold, target_point;
    Basis(float p = 0, float g = 0, float n = 0, float b = 0, float target = 0)
        : point(p), gradient(g), normal(n), bold(b), target_point(target) {}
    bool valid() const {
        return std::isfinite(point) && point > 0 && std::isfinite(target_point) && target_point > 0 &&
               std::isfinite(gradient) && gradient > 1 && std::isfinite(normal) && std::isfinite(bold);
    }
};
struct Style {
    float face = 0, outline = 0, softness = 0, glow_offset = 0, glow_outer = 0;
    float shadow_x = 0, shadow_y = 0, shadow_dilate = 0, shadow_softness = 0;
    bool ratios_off = false;
};
struct Ratios { float a, b, c; };
// Unity TMP ShaderUtilities.UpdateShaderRatios, expressed in the source font's
// units. The SDF shader multiplies these ratios by GradientScale; mesh geometry
// scales by fontSize / faceInfo.pointSize. Keep their physical product constant.
inline Ratios calculate(const Basis &basis, const Style &s, float target_gradient) {
    const float weight = std::max(basis.normal, basis.bold) / 4.f;
    const float range = (weight + s.face) * (basis.gradient - 1.f);
    Ratios r{1, 1, 1};
    if (!s.ratios_off) {
        r.a = (basis.gradient - 1.f) / (basis.gradient *
                std::max(1.f, weight + s.face + s.outline + s.softness));
        r.b = std::max(0.f, basis.gradient - 1.f - range) /
                (basis.gradient * std::max(1.f, s.glow_offset + s.glow_outer));
        r.c = std::max(0.f, basis.gradient - 1.f - range) / (basis.gradient *
                std::max(1.f, std::max(std::abs(s.shadow_x), std::abs(s.shadow_y)) +
                        s.shadow_dilate + s.shadow_softness));
    }
    const float scale = basis.gradient * basis.target_point / (target_gradient * basis.point);
    return {r.a * scale, r.b * scale, r.c * scale};
}
}
