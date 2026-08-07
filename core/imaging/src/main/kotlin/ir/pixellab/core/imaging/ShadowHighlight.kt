package ir.pixellab.core.imaging

import kotlin.math.abs

/**
 * Recovers shadow and highlight detail without flattening the whole picture.
 *
 * The one correction that a curve genuinely cannot do. Lifting shadows with a curve lifts *every*
 * dark pixel, including the ones inside a bright subject, so a backlit portrait comes back with a
 * grey face and a washed-out rim. This lifts a pixel by how dark **its neighbourhood** is, which is
 * why a dark face against a bright window opens up while the dark edge of an eyelash does not.
 *
 * That neighbourhood is the reason this is a filter and not an adjustment layer, and it is the same
 * reason Photoshop keeps Shadows/Highlights under Image rather than offering it as a layer: the
 * result at a pixel depends on pixels a radius away, so it cannot be evaluated from the one texel a
 * compositing shader has in hand.
 */
object ShadowHighlight {

    /**
     * @param shadowAmount 0..1, how far the dark neighbourhoods are lifted.
     * @param shadowTone 0..1, how far up the range "dark" reaches. Small values touch only the
     *   deepest shadows; Photoshop's default of half is a broad, gentle lift.
     * @param highlightAmount 0..1, the same in the other direction.
     * @param radius the neighbourhood, in pixels. Too small and every edge grows a halo, because
     *   the mask then follows the detail it is supposed to be ignoring.
     * @param midtoneContrast -1..1, applied after, since opening both ends always costs contrast in
     *   the middle and getting it back is part of the same operation.
     * @param colorCorrection 0..2, saturation applied in proportion to how much a pixel moved.
     *   Lifting a shadow desaturates it — the colours were compressed down there — and this puts
     *   back what the lift took out, without touching the parts that did not move.
     */
    fun apply(
        src: Raster,
        shadowAmount: Float = 0f,
        shadowTone: Float = 0.5f,
        highlightAmount: Float = 0f,
        highlightTone: Float = 0.5f,
        radius: Float = 30f,
        midtoneContrast: Float = 0f,
        colorCorrection: Float = 1f,
    ): Raster {
        require(src.channels >= 3) { "shadow/highlight needs colour, got ${src.channels} channels" }
        if (shadowAmount <= 0f && highlightAmount <= 0f && midtoneContrast == 0f) return src

        // The mask is the *blurred* luminance, not the pixel's own. That single substitution is
        // what separates this from a curve: it asks "is this region dark?" rather than "is this
        // pixel dark?", and only the first question has a useful answer.
        val local = if (radius > 0f) Blur.gaussian(src.luminance(), radius) else src.luminance()

        val out = src.copy()
        for (i in 0 until src.pixelCount) {
            val at = i * src.channels
            val neighbourhood = local.data[i].coerceIn(0f, 1f)

            var gain = 1f
            if (shadowAmount > 0f) {
                gain *= 1f + shadowAmount * SHADOW_HEADROOM * falloff(1f - neighbourhood, shadowTone)
            }
            if (highlightAmount > 0f) {
                gain *= 1f - highlightAmount * HIGHLIGHT_DEPTH * falloff(neighbourhood, highlightTone)
            }

            val red = src.data[at]
            val green = src.data[at + 1]
            val blue = src.data[at + 2]

            // Scaling all three channels by one gain rather than correcting each on its own mask:
            // a per-channel gain shifts the hue of everything it lifts, which is exactly the muddy
            // look that gives an over-processed HDR away.
            var r = red * gain
            var g = green * gain
            var b = blue * gain

            if (midtoneContrast != 0f) {
                r = contrast(r, midtoneContrast)
                g = contrast(g, midtoneContrast)
                b = contrast(b, midtoneContrast)
            }

            if (colorCorrection != 1f && gain != 1f) {
                val luma = LUMA_R * r + LUMA_G * g + LUMA_B * b
                // Only in proportion to how far the pixel actually moved, so an untouched midtone
                // keeps the saturation it was given.
                val strength = 1f + (colorCorrection - 1f) * abs(gain - 1f).coerceAtMost(1f)
                r = luma + (r - luma) * strength
                g = luma + (g - luma) * strength
                b = luma + (b - luma) * strength
            }

            out.data[at] = r.coerceIn(0f, 1f)
            out.data[at + 1] = g.coerceIn(0f, 1f)
            out.data[at + 2] = b.coerceIn(0f, 1f)
        }
        return out
    }

    private fun contrast(v: Float, amount: Float): Float =
        ((v - 0.5f) * (1f + amount) + 0.5f).coerceIn(0f, 1f)

    /**
     * How strongly a tone belongs to the range being corrected.
     *
     * [tone] is a *width*, read the way the panel's slider reads: at half, the shadow correction
     * reaches the tones below half and stops — an 85 % highlight is untouched by a shadow lift, and
     * that is the property that makes the control predictable.
     *
     * Ramped rather than switched at the boundary, because a threshold puts a visible contour
     * across every gradient it crosses, and a sky is one long gradient. Smoothstep rather than a
     * straight ramp for the same reason one step further out: a linear ramp is continuous but its
     * slope is not, and the kink shows as a faint edge in a clear sky.
     *
     * @param distanceIntoRange 1 at the far end of the range being corrected — for shadows that is
     *   black, for highlights white.
     */
    internal fun falloff(distanceIntoRange: Float, tone: Float): Float {
        val width = tone.coerceIn(MIN_TONE, 1f)
        val t = ((distanceIntoRange.coerceIn(0f, 1f) - (1f - width)) / width).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** A shadow can be opened by about two and a half stops before the noise beneath it arrives. */
    private const val SHADOW_HEADROOM = 2.5f

    /** And a highlight pulled to a fifth of its value is already at the edge of looking grey. */
    private const val HIGHLIGHT_DEPTH = 0.8f

    private const val MIN_TONE = 0.05f

    private const val LUMA_R = 0.2126f
    private const val LUMA_G = 0.7152f
    private const val LUMA_B = 0.0722f
}
