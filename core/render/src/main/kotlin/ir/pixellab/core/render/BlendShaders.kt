package ir.pixellab.core.render

import ir.pixellab.core.model.BlendMode

/**
 * GLSL ES 3.0 source for the compositor's blend stage.
 *
 * Android's `Canvas` exposes only a subset of these modes, which is the reason compositing runs on
 * the GPU rather than through the platform 2D API. Each mode is selected by a uniform so the whole
 * stack shares one program and one pipeline state.
 *
 * [Blending] holds the same formulas in Kotlin and is the oracle they are tested against; a test
 * asserts every enum constant appears here, so adding a mode without shader support breaks the
 * build rather than silently falling back to Normal.
 */
object BlendShaders {

    /** Uniform value identifying a mode inside the shader. Matches the enum ordinal. */
    fun uniformValue(mode: BlendMode): Int = mode.ordinal

    /** The composite pass's fragment stage, complete with its own prologue. */
    val compositeFragment: String = """
        #version 300 es
        precision highp float;

        uniform sampler2D uBackdrop;
        uniform sampler2D uSource;
        uniform int   uBlendMode;
        uniform float uOpacity;

        // Where in the layer texture this canvas pixel comes from; see Compositing.
        uniform mat3  uMap;

        // --- masking ----------------------------------------------------------------------
        //
        // Three independent things narrow a layer's coverage, and Photoshop multiplies all of
        // them: a raster mask, a vector mask, and the alpha of the layer a clipping group is
        // clipped to. They are separate samplers rather than one pre-combined mask because
        // combining them on the CPU would mean rebuilding a canvas-sized texture whenever any
        // one of them moved.
        uniform sampler2D uMask;
        uniform sampler2D uVectorMask;
        uniform sampler2D uClip;
        uniform mat3  uMaskMap;
        uniform mat3  uVectorMaskMap;
        uniform int   uMaskFlags;
        // Photoshop's mask density: how far the mask is allowed to hide, 0 disabling it entirely.
        uniform float uMaskDensity;

        in  vec2 vUv;
        out vec4 fragColor;

        // --- separable helpers -------------------------------------------------------------

        float blendMultiply (float cb, float cs) { return cb * cs; }
        float blendScreen   (float cb, float cs) { return cb + cs - cb * cs; }
        float blendColorBurn(float cb, float cs) {
            if (cb >= 1.0) return 1.0;
            if (cs <= 0.0) return 0.0;
            return 1.0 - min(1.0, (1.0 - cb) / cs);
        }
        float blendColorDodge(float cb, float cs) {
            if (cb <= 0.0) return 0.0;
            if (cs >= 1.0) return 1.0;
            return min(1.0, cb / (1.0 - cs));
        }
        float blendHardLight(float cb, float cs) {
            return cs <= 0.5 ? cb * 2.0 * cs : blendScreen(cb, 2.0 * cs - 1.0);
        }
        float blendSoftLight(float cb, float cs) {
            float d = cb <= 0.25 ? ((16.0 * cb - 12.0) * cb + 4.0) * cb : sqrt(cb);
            return cs <= 0.5 ? cb - (1.0 - 2.0 * cs) * cb * (1.0 - cb)
                             : cb + (2.0 * cs - 1.0) * (d - cb);
        }
        float blendLinearLight(float cb, float cs) { return clamp(cb + 2.0 * cs - 1.0, 0.0, 1.0); }
        float blendVividLight (float cb, float cs) {
            return cs <= 0.5 ? blendColorBurn(cb, 2.0 * cs)
                             : blendColorDodge(cb, 2.0 * (cs - 0.5));
        }
        float blendPinLight(float cb, float cs) {
            return cs <= 0.5 ? min(cb, 2.0 * cs) : max(cb, 2.0 * (cs - 0.5));
        }

        // --- non-separable helpers ---------------------------------------------------------

        float lum(vec3 c) { return dot(c, vec3(0.30, 0.59, 0.11)); }
        float sat(vec3 c) { return max(max(c.r, c.g), c.b) - min(min(c.r, c.g), c.b); }

        vec3 clipColor(vec3 c) {
            float l = lum(c);
            float n = min(min(c.r, c.g), c.b);
            float x = max(max(c.r, c.g), c.b);
            if (n < 0.0) { float d = l - n; if (d > 0.0) c = l + (c - l) * l / d; }
            if (x > 1.0) { float d = x - l; if (d > 0.0) c = l + (c - l) * (1.0 - l) / d; }
            return c;
        }
        vec3 setLum(vec3 c, float l) { return clipColor(c + (l - lum(c))); }

        vec3 setSat(vec3 c, float s) {
            float cmax = max(max(c.r, c.g), c.b);
            float cmin = min(min(c.r, c.g), c.b);
            float cmid = c.r + c.g + c.b - cmax - cmin;
            vec3  outc = vec3(0.0);
            if (cmax > cmin) { cmid = (cmid - cmin) * s / (cmax - cmin); cmax = s; }
            else             { cmid = 0.0; cmax = 0.0; }
            cmin = 0.0;
            // Re-scatter the three sorted values back onto their original channels.
            float omax = max(max(c.r, c.g), c.b);
            float omin = min(min(c.r, c.g), c.b);
            outc.r = c.r == omax ? cmax : (c.r == omin ? cmin : cmid);
            outc.g = c.g == omax ? cmax : (c.g == omin ? cmin : cmid);
            outc.b = c.b == omax ? cmax : (c.b == omin ? cmin : cmid);
            return outc;
        }

        vec3 blend(int mode, vec3 cb, vec3 cs) {
            if (mode == ${BlendMode.NORMAL.ordinal}   || mode == ${BlendMode.DISSOLVE.ordinal}) return cs;
            if (mode == ${BlendMode.DARKEN.ordinal})       return min(cb, cs);
            if (mode == ${BlendMode.MULTIPLY.ordinal})     return cb * cs;
            if (mode == ${BlendMode.COLOR_BURN.ordinal})
                return vec3(blendColorBurn(cb.r, cs.r), blendColorBurn(cb.g, cs.g), blendColorBurn(cb.b, cs.b));
            if (mode == ${BlendMode.LINEAR_BURN.ordinal})  return clamp(cb + cs - 1.0, 0.0, 1.0);
            if (mode == ${BlendMode.DARKER_COLOR.ordinal}) return lum(cb) <= lum(cs) ? cb : cs;
            if (mode == ${BlendMode.LIGHTEN.ordinal})      return max(cb, cs);
            if (mode == ${BlendMode.SCREEN.ordinal})       return cb + cs - cb * cs;
            if (mode == ${BlendMode.COLOR_DODGE.ordinal})
                return vec3(blendColorDodge(cb.r, cs.r), blendColorDodge(cb.g, cs.g), blendColorDodge(cb.b, cs.b));
            if (mode == ${BlendMode.LINEAR_DODGE.ordinal})  return min(vec3(1.0), cb + cs);
            if (mode == ${BlendMode.LIGHTER_COLOR.ordinal}) return lum(cb) > lum(cs) ? cb : cs;
            if (mode == ${BlendMode.OVERLAY.ordinal})
                return vec3(blendHardLight(cs.r, cb.r), blendHardLight(cs.g, cb.g), blendHardLight(cs.b, cb.b));
            if (mode == ${BlendMode.SOFT_LIGHT.ordinal})
                return vec3(blendSoftLight(cb.r, cs.r), blendSoftLight(cb.g, cs.g), blendSoftLight(cb.b, cs.b));
            if (mode == ${BlendMode.HARD_LIGHT.ordinal})
                return vec3(blendHardLight(cb.r, cs.r), blendHardLight(cb.g, cs.g), blendHardLight(cb.b, cs.b));
            if (mode == ${BlendMode.VIVID_LIGHT.ordinal})
                return vec3(blendVividLight(cb.r, cs.r), blendVividLight(cb.g, cs.g), blendVividLight(cb.b, cs.b));
            if (mode == ${BlendMode.LINEAR_LIGHT.ordinal})
                return vec3(blendLinearLight(cb.r, cs.r), blendLinearLight(cb.g, cs.g), blendLinearLight(cb.b, cs.b));
            if (mode == ${BlendMode.PIN_LIGHT.ordinal})
                return vec3(blendPinLight(cb.r, cs.r), blendPinLight(cb.g, cs.g), blendPinLight(cb.b, cs.b));
            if (mode == ${BlendMode.HARD_MIX.ordinal}) {
                vec3 ll = vec3(blendLinearLight(cb.r, cs.r), blendLinearLight(cb.g, cs.g), blendLinearLight(cb.b, cs.b));
                return step(vec3(1.0), ll);
            }
            if (mode == ${BlendMode.DIFFERENCE.ordinal}) return abs(cb - cs);
            if (mode == ${BlendMode.EXCLUSION.ordinal})  return cb + cs - 2.0 * cb * cs;
            if (mode == ${BlendMode.SUBTRACT.ordinal})   return max(vec3(0.0), cb - cs);
            if (mode == ${BlendMode.DIVIDE.ordinal})
                return vec3(cs.r <= 0.0 ? 1.0 : min(1.0, cb.r / cs.r),
                            cs.g <= 0.0 ? 1.0 : min(1.0, cb.g / cs.g),
                            cs.b <= 0.0 ? 1.0 : min(1.0, cb.b / cs.b));
            if (mode == ${BlendMode.HUE.ordinal})        return setLum(setSat(cs, sat(cb)), lum(cb));
            if (mode == ${BlendMode.SATURATION.ordinal}) return setLum(setSat(cb, sat(cs)), lum(cb));
            if (mode == ${BlendMode.COLOR.ordinal})      return setLum(cs, lum(cb));
            if (mode == ${BlendMode.LUMINOSITY.ordinal}) return setLum(cb, lum(cs));
            return cs;
        }

        const int MASK_RASTER          = 1;
        const int MASK_RASTER_INVERTED = 2;
        const int MASK_VECTOR          = 4;
        const int MASK_VECTOR_INVERTED = 8;
        const int MASK_CLIP            = 16;

        /**
         * One mask's contribution at this canvas pixel.
         *
         * Outside the mask's own rectangle the answer is 0, not the clamped edge pixel: a mask
         * covers a region, and clamping would smear its border across the rest of the document.
         * Inversion is applied after that, so an inverted mask correctly reveals everything its
         * rectangle does not cover.
         */
        float maskAt(sampler2D tex, mat3 map, bool inverted) {
            vec2 uv = (map * vec3(vUv, 1.0)).xy;
            float m = (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0)
                ? 0.0
                : texture(tex, uv).a;
            return inverted ? 1.0 - m : m;
        }

        void main() {
            vec4 backdrop = texture(uBackdrop, vUv);

            vec2 uv = (uMap * vec3(vUv, 1.0)).xy;
            // A layer covers part of the canvas; everywhere else the backdrop passes through
            // untouched. Clamping instead would smear the layer's edge across the whole document.
            if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) { fragColor = backdrop; return; }
            vec4 source = texture(uSource, uv);

            float coverage = 1.0;
            if ((uMaskFlags & MASK_RASTER) != 0) {
                float m = maskAt(uMask, uMaskMap, (uMaskFlags & MASK_RASTER_INVERTED) != 0);
                coverage *= mix(1.0, m, uMaskDensity);
            }
            if ((uMaskFlags & MASK_VECTOR) != 0) {
                coverage *= maskAt(uVectorMask, uVectorMaskMap, (uMaskFlags & MASK_VECTOR_INVERTED) != 0);
            }
            // The clip source is a canvas-sized buffer holding the base of the clipping group, so
            // it is sampled in canvas space rather than through a map.
            if ((uMaskFlags & MASK_CLIP) != 0) {
                coverage *= texture(uClip, vUv).a;
            }

            float ab = backdrop.a;
            float as = source.a * uOpacity * coverage;
            float ao = as + ab * (1.0 - as);
            if (ao <= 0.0) { fragColor = vec4(0.0); return; }

            vec3 mixed = blend(uBlendMode, backdrop.rgb, source.rgb);
            vec3 co = (as * (1.0 - ab) * source.rgb + as * ab * mixed + (1.0 - as) * ab * backdrop.rgb) / ao;
            fragColor = vec4(co, ao);
        }
    """.trimIndent()
}
