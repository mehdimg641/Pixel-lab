package ir.pixellab.core.imaging

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Shadow/highlight recovery.
 *
 * The tests below are all aimed at the one property that separates this from a tone curve: the
 * correction has to depend on the *neighbourhood*, not on the pixel. A curve passes every test that
 * only looks at single pixels, so each case here puts two identically-valued pixels in differently
 * lit surroundings and asserts they come out different.
 */
class ShadowHighlightTest {

    /** A dark half and a bright half, wide enough that a 12-pixel blur cannot see across. */
    private fun split(size: Int = 64, dark: Float = 0.15f, bright: Float = 0.85f): Raster {
        val r = Raster(size, size, 3)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val v = if (x < size / 2) dark else bright
                for (c in 0 until 3) r[x, y, c] = v
            }
        }
        return r
    }

    private fun flat(value: Float, size: Int = 32): Raster =
        Raster(size, size, 3).fill(value)

    @Test
    fun `lifting the shadows leaves the highlights where they were`() {
        val out = ShadowHighlight.apply(split(), shadowAmount = 0.6f, radius = 12f)

        val shadow = out[8, 32, 0]
        val highlight = out[56, 32, 0]
        (shadow > 0.15f) shouldBe true
        // The whole point. A curve that lifted the shadow this far would have moved the highlight
        // too, and the picture would come back flat.
        highlight shouldBe 0.85f.plusOrMinus(0.01f)
    }

    @Test
    fun `pulling the highlights leaves the shadows where they were`() {
        val out = ShadowHighlight.apply(split(), highlightAmount = 0.6f, radius = 12f)

        (out[56, 32, 0] < 0.85f) shouldBe true
        out[8, 32, 0] shouldBe 0.15f.plusOrMinus(0.01f)
    }

    @Test
    fun `the same tone is treated differently in different surroundings`() {
        // A mid-grey square in a dark field and the same grey in a bright field. A tone curve
        // cannot tell them apart at all; this is the entire reason the operation exists.
        val size = 96
        val r = Raster(size, size, 3)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val v = if (x < size / 2) 0.05f else 0.95f
                for (c in 0 until 3) r[x, y, c] = v
            }
        }
        for (y in 40..56) {
            for (x in 8..24) for (c in 0 until 3) r[x, y, c] = 0.5f
            for (x in 71..87) for (c in 0 until 3) r[x, y, c] = 0.5f
        }

        val out = ShadowHighlight.apply(r, shadowAmount = 0.8f, radius = 20f)
        val inDark = out[16, 48, 0]
        val inBright = out[79, 48, 0]
        (inDark > inBright + 0.02f) shouldBe true
    }

    @Test
    fun `an untouched image comes back identical`() {
        val src = split()
        // Not merely close: with every amount at zero there is no arithmetic to do, and a filter
        // that rebuilt the buffer anyway would cost a full blur for nothing.
        (ShadowHighlight.apply(src) === src) shouldBe true
    }

    @Test
    fun `the lift never pushes a channel past white`() {
        val out = ShadowHighlight.apply(flat(0.9f), shadowAmount = 1f, shadowTone = 1f, radius = 4f)
        for (v in out.data) (v <= 1f) shouldBe true
    }

    @Test
    fun `hue survives the lift`() {
        val size = 32
        val src = Raster(size, size, 3)
        for (i in 0 until src.pixelCount) {
            src.data[i * 3] = 0.20f
            src.data[i * 3 + 1] = 0.10f
            src.data[i * 3 + 2] = 0.05f
        }

        // One gain for all three channels, so the ratios hold. Correcting each channel on its own
        // mask is what turns an over-lifted shadow the characteristic muddy grey-green.
        val out = ShadowHighlight.apply(src, shadowAmount = 0.5f, radius = 8f, colorCorrection = 1f)
        val r = out[16, 16, 0]
        val g = out[16, 16, 1]
        val b = out[16, 16, 2]
        (g / r) shouldBe (0.10f / 0.20f).plusOrMinus(0.01f)
        (b / r) shouldBe (0.05f / 0.20f).plusOrMinus(0.01f)
    }

    @Test
    fun `colour correction puts back the saturation the lift took out`() {
        val size = 32
        val src = Raster(size, size, 3)
        for (i in 0 until src.pixelCount) {
            src.data[i * 3] = 0.20f
            src.data[i * 3 + 1] = 0.10f
            src.data[i * 3 + 2] = 0.05f
        }

        fun saturation(out: Raster): Float {
            val r = out[16, 16, 0]
            val g = out[16, 16, 1]
            val b = out[16, 16, 2]
            val mx = maxOf(r, g, b)
            val mn = minOf(r, g, b)
            return if (mx <= 0f) 0f else (mx - mn) / mx
        }

        val plain = ShadowHighlight.apply(src, shadowAmount = 0.5f, radius = 8f, colorCorrection = 1f)
        val corrected = ShadowHighlight.apply(src, shadowAmount = 0.5f, radius = 8f, colorCorrection = 1.6f)
        (saturation(corrected) > saturation(plain)) shouldBe true
    }

    @Test
    fun `a narrow tone width keeps the correction in the extremes`() {
        // Same amount, different reach: the near-midtone should move far less when the width is
        // narrow, which is what stops a shadow lift from washing out the whole picture.
        val narrow = ShadowHighlight.apply(flat(0.45f), shadowAmount = 0.8f, shadowTone = 0.1f, radius = 4f)
        val wide = ShadowHighlight.apply(flat(0.45f), shadowAmount = 0.8f, shadowTone = 1f, radius = 4f)
        (narrow[16, 16, 0] < wide[16, 16, 0]) shouldBe true
    }

    @Test
    fun `the falloff is continuous rather than a threshold`() {
        // A threshold would put a visible contour across every gradient it crossed, and a sky is
        // one long gradient. Neighbouring inputs must give neighbouring outputs.
        var previous = ShadowHighlight.falloff(0f, 0.3f)
        for (step in 1..100) {
            val current = ShadowHighlight.falloff(step / 100f, 0.3f)
            (current - previous < 0.1f) shouldBe true
            (current >= previous) shouldBe true
            previous = current
        }
    }

    @Test
    fun `midtone contrast alone still applies`() {
        // Opening both ends always costs contrast in the middle, so recovering it has to work as
        // its own operation rather than only as a rider on a lift.
        val out = ShadowHighlight.apply(flat(0.7f), midtoneContrast = 0.5f)
        (out[16, 16, 0] > 0.7f) shouldBe true
    }
}
