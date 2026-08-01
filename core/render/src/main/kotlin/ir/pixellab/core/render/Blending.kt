package ir.pixellab.core.render

import ir.pixellab.core.model.BlendMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Reference implementation of every blend mode, in straight (non-premultiplied) colour.
 *
 * This exists twice on purpose: here in Kotlin, and again as GLSL in [BlendShaders]. The Kotlin
 * version is the oracle the shader is tested against, because a wrong blend mode is invisible until
 * a user opens a Photoshop file and the colours are subtly off — the kind of defect that survives
 * to release otherwise.
 *
 * Formulas follow the PDF blend model, which is what Photoshop implements.
 */
object Blending {

    /** Blends one source channel over one backdrop channel, both in 0..1. */
    fun channel(mode: BlendMode, cb: Float, cs: Float): Float = when (mode) {
        BlendMode.NORMAL, BlendMode.DISSOLVE -> cs
        BlendMode.DARKEN -> min(cb, cs)
        BlendMode.MULTIPLY -> cb * cs
        BlendMode.COLOR_BURN -> if (cb >= 1f) 1f else if (cs <= 0f) 0f else 1f - min(1f, (1f - cb) / cs)
        BlendMode.LINEAR_BURN -> (cb + cs - 1f).coerceIn(0f, 1f)
        BlendMode.LIGHTEN -> max(cb, cs)
        BlendMode.SCREEN -> cb + cs - cb * cs
        BlendMode.COLOR_DODGE -> if (cb <= 0f) 0f else if (cs >= 1f) 1f else min(1f, cb / (1f - cs))
        BlendMode.LINEAR_DODGE -> min(1f, cb + cs)
        BlendMode.OVERLAY -> hardLight(cs, cb)
        BlendMode.SOFT_LIGHT -> softLight(cb, cs)
        BlendMode.HARD_LIGHT -> hardLight(cb, cs)
        BlendMode.VIVID_LIGHT ->
            if (cs <= 0.5f) channel(BlendMode.COLOR_BURN, cb, 2f * cs)
            else channel(BlendMode.COLOR_DODGE, cb, 2f * (cs - 0.5f))
        BlendMode.LINEAR_LIGHT -> (cb + 2f * cs - 1f).coerceIn(0f, 1f)
        BlendMode.PIN_LIGHT ->
            if (cs <= 0.5f) min(cb, 2f * cs) else max(cb, 2f * (cs - 0.5f))
        BlendMode.HARD_MIX -> if (channel(BlendMode.LINEAR_LIGHT, cb, cs) >= 1f) 1f else 0f
        BlendMode.DIFFERENCE -> abs(cb - cs)
        BlendMode.EXCLUSION -> cb + cs - 2f * cb * cs
        BlendMode.SUBTRACT -> max(0f, cb - cs)
        BlendMode.DIVIDE -> if (cs <= 0f) 1f else min(1f, cb / cs)
        // Non-separable modes are meaningless per channel; callers must use [rgb].
        BlendMode.DARKER_COLOR, BlendMode.LIGHTER_COLOR,
        BlendMode.HUE, BlendMode.SATURATION, BlendMode.COLOR, BlendMode.LUMINOSITY,
        -> cs
    }

    /** Blends full RGB triples, handling the modes that cannot be computed per channel. */
    fun rgb(mode: BlendMode, cb: Triple<Float, Float, Float>, cs: Triple<Float, Float, Float>):
        Triple<Float, Float, Float> = when (mode) {
        BlendMode.DARKER_COLOR -> if (luminosity(cb) <= luminosity(cs)) cb else cs
        BlendMode.LIGHTER_COLOR -> if (luminosity(cb) > luminosity(cs)) cb else cs
        BlendMode.HUE -> setLum(setSat(cs, sat(cb)), luminosity(cb))
        BlendMode.SATURATION -> setLum(setSat(cb, sat(cs)), luminosity(cb))
        BlendMode.COLOR -> setLum(cs, luminosity(cb))
        BlendMode.LUMINOSITY -> setLum(cb, luminosity(cs))
        else -> Triple(
            channel(mode, cb.first, cs.first),
            channel(mode, cb.second, cs.second),
            channel(mode, cb.third, cs.third),
        )
    }

    /**
     * Full Porter-Duff source-over composite with a blend function, per the PDF model:
     * `co = as * (1 - ab) * cs + as * ab * B(cb, cs) + (1 - as) * ab * cb`
     */
    fun composite(
        mode: BlendMode,
        backdrop: FloatArray,
        source: FloatArray,
        opacity: Float = 1f,
    ): FloatArray {
        require(backdrop.size == 4 && source.size == 4) { "expected RGBA arrays" }
        val ab = backdrop[3]
        val As = source[3] * opacity.coerceIn(0f, 1f)
        val ao = As + ab * (1f - As)
        if (ao <= 0f) return floatArrayOf(0f, 0f, 0f, 0f)

        val cb = Triple(backdrop[0], backdrop[1], backdrop[2])
        val cs = Triple(source[0], source[1], source[2])
        val blended = rgb(mode, cb, cs)

        fun mix(bIdx: Int, b: Float, s: Float, m: Float): Float {
            @Suppress("UNUSED_PARAMETER")
            val unused = bIdx
            return (As * (1f - ab) * s + As * ab * m + (1f - As) * ab * b) / ao
        }
        return floatArrayOf(
            mix(0, cb.first, cs.first, blended.first),
            mix(1, cb.second, cs.second, blended.second),
            mix(2, cb.third, cs.third, blended.third),
            ao,
        )
    }

    // Rec. 601 luma, which is what the PDF spec and Photoshop use for the non-separable modes.
    fun luminosity(c: Triple<Float, Float, Float>): Float =
        0.3f * c.first + 0.59f * c.second + 0.11f * c.third

    private fun sat(c: Triple<Float, Float, Float>): Float =
        maxOf(c.first, c.second, c.third) - minOf(c.first, c.second, c.third)

    private fun setLum(c: Triple<Float, Float, Float>, l: Float): Triple<Float, Float, Float> {
        val d = l - luminosity(c)
        return clipColor(Triple(c.first + d, c.second + d, c.third + d))
    }

    private fun clipColor(c: Triple<Float, Float, Float>): Triple<Float, Float, Float> {
        val l = luminosity(c)
        val n = minOf(c.first, c.second, c.third)
        val x = maxOf(c.first, c.second, c.third)
        var (r, g, b) = c
        if (n < 0f) {
            val d = l - n
            if (d > 0f) {
                r = l + (r - l) * l / d; g = l + (g - l) * l / d; b = l + (b - l) * l / d
            }
        }
        if (x > 1f) {
            val d = x - l
            if (d > 0f) {
                r = l + (r - l) * (1f - l) / d
                g = l + (g - l) * (1f - l) / d
                b = l + (b - l) * (1f - l) / d
            }
        }
        return Triple(r, g, b)
    }

    private fun setSat(c: Triple<Float, Float, Float>, s: Float): Triple<Float, Float, Float> {
        val values = floatArrayOf(c.first, c.second, c.third)
        val maxI = values.indices.maxBy { values[it] }
        val minI = values.indices.minBy { values[it] }
        val midI = (0..2).first { it != maxI && it != minI }
        val out = FloatArray(3)
        if (values[maxI] > values[minI]) {
            out[midI] = (values[midI] - values[minI]) * s / (values[maxI] - values[minI])
            out[maxI] = s
        }
        out[minI] = 0f
        return Triple(out[0], out[1], out[2])
    }

    private fun hardLight(cb: Float, cs: Float): Float =
        if (cs <= 0.5f) cb * 2f * cs else channel(BlendMode.SCREEN, cb, 2f * cs - 1f)

    private fun softLight(cb: Float, cs: Float): Float {
        val d = if (cb <= 0.25f) ((16f * cb - 12f) * cb + 4f) * cb else sqrt(cb)
        return if (cs <= 0.5f) cb - (1f - 2f * cs) * cb * (1f - cb)
        else cb + (2f * cs - 1f) * (d - cb)
    }
}
