package ir.pixellab.core.imaging

import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.ToneRange
import ir.pixellab.core.model.Vec2
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The brushes that read the picture instead of painting on it.
 *
 * Each is checked against the property that makes it the tool it claims to be, not against a
 * reference image — a dodge scaled to a tenth would pass a picture comparison and fail every
 * assertion here.
 */
class ToneBrushTest {

    private val size = 8

    private fun flat(value: Float) = Raster(size, size, 3).also { it.data.fill(value) }

    private fun grey(vararg values: Float): Raster {
        val r = Raster(values.size, 1, 3)
        for ((i, v) in values.withIndex()) {
            for (c in 0 until 3) r.data[i * 3 + c] = v
        }
        return r
    }

    /** Full coverage over the whole image, which is what a long stroke produces. */
    private fun full(count: Int = size * size) = FloatArray(count) { 1f }

    private fun luma(r: Raster, i: Int): Float {
        val at = i * r.channels
        return 0.2126f * r.data[at] + 0.7152f * r.data[at + 1] + 0.0722f * r.data[at + 2]
    }

    // ---- dodge and burn ----------------------------------------------------------------------

    @Test
    fun `dodge lightens and burn darkens`() {
        val src = flat(0.5f)
        val dodged = ToneBrush.dodgeBurn(src, full(), 1f)
        val burned = ToneBrush.dodgeBurn(src, full(), -1f)
        luma(dodged, 0) shouldBeGreaterThan 0.5f
        luma(burned, 0) shouldBeLessThan 0.5f
    }

    @Test
    fun `the range decides which tones move`() {
        // The property a dodge tool without ranges is missing, and the one people complain about:
        // opening a dark corner must not touch the sky.
        val src = grey(0.12f, 0.5f, 0.88f)
        val onShadows = ToneBrush.dodgeBurn(src, full(3), 1f, ToneRange.SHADOWS)
        val onHighlights = ToneBrush.dodgeBurn(src, full(3), 1f, ToneRange.HIGHLIGHTS)

        val shadowGain = luma(onShadows, 0) - 0.12f
        val highlightGainOnShadowRange = luma(onShadows, 2) - 0.88f
        shadowGain shouldBeGreaterThan highlightGainOnShadowRange

        val highlightGain = luma(onHighlights, 2) - 0.88f
        val shadowGainOnHighlightRange = luma(onHighlights, 0) - 0.12f
        highlightGain shouldBeGreaterThan shadowGainOnHighlightRange
    }

    @Test
    fun `the ranges overlap, so no tone is unreachable`() {
        // A gap between the bands would be a band of the picture no setting of the Range menu could
        // reach — and a hard band edge would show up as a contour line across a gradient.
        for (step in 0..20) {
            val l = step / 20f
            val best = ToneRange.entries.maxOf { ToneBrush.weightAt(it, l) }
            best shouldBeGreaterThan 0.15f
        }
    }

    @Test
    fun `dodging never reaches white, so a highlight does not go flat`() {
        // The reason it is a gamma move rather than an addition. Adding a constant crushes at the
        // ends and leaves a flat patch where the dodge ran out of headroom.
        var src = flat(0.85f)
        repeat(20) { src = ToneBrush.dodgeBurn(src, full(), 1f, ToneRange.HIGHLIGHTS) }
        luma(src, 0) shouldBeLessThan 1f
    }

    @Test
    fun `protecting tones keeps the hue where it was`() {
        // Dodging in RGB desaturates as it approaches white, so an unprotected dodge on skin turns
        // it grey before it turns it bright. Dark enough that the dodge has headroom. Once a channel clips at white the ratio cannot
        // be held by anything — Photoshop clips there too — so a probe that clips would be testing
        // the clamp rather than the flag.
        val src = Raster(1, 1, 3)
        src.data[0] = 0.35f; src.data[1] = 0.2f; src.data[2] = 0.15f
        fun ratio(r: Raster) = r.data[0] / r.data[1]

        val held = ToneBrush.dodgeBurn(src, full(1), 1f, protectTones = true)
        abs(ratio(held) - ratio(src)) shouldBeLessThan 0.02f

        val unprotected = ToneBrush.dodgeBurn(src, full(1), 1f, protectTones = false)
        // The unprotected one genuinely differs, or the flag would be decoration.
        abs(ratio(unprotected) - ratio(src)) shouldBeGreaterThan 0.02f
    }

    @Test
    fun `coverage scales the effect, so a soft dab feathers`() {
        val src = flat(0.5f)
        val half = FloatArray(size * size) { 0.5f }
        val gentle = ToneBrush.dodgeBurn(src, half, 1f)
        val strong = ToneBrush.dodgeBurn(src, full(), 1f)
        luma(strong, 0) shouldBeGreaterThan luma(gentle, 0)
        luma(gentle, 0) shouldBeGreaterThan 0.5f
    }

    @Test
    fun `zero coverage leaves the picture exactly alone`() {
        val src = flat(0.4f)
        val none = FloatArray(size * size)
        RasterMath.meanAbsDiff(src, ToneBrush.dodgeBurn(src, none, 1f)) shouldBe 0f
    }

    // ---- sponge ------------------------------------------------------------------------------

    private fun spread(r: Raster, i: Int): Float {
        val at = i * r.channels
        val a = r.data[at]
        val b = r.data[at + 1]
        val c = r.data[at + 2]
        return max(a, max(b, c)) - min(a, min(b, c))
    }

    @Test
    fun `the sponge saturates one way and desaturates the other`() {
        val src = Raster(1, 1, 3)
        src.data[0] = 0.6f; src.data[1] = 0.45f; src.data[2] = 0.4f
        val up = ToneBrush.sponge(src, full(1), 1f)
        val down = ToneBrush.sponge(src, full(1), -1f)
        spread(up, 0) shouldBeGreaterThan spread(src, 0)
        spread(down, 0) shouldBeLessThan spread(src, 0)
    }

    @Test
    fun `vibrance protects a colour that is already vivid`() {
        // A sponge over a face should lift the muted colours and leave an already-vivid lipstick
        // alone. Without the guard it keeps pushing and clips a channel, which shifts the hue.
        val muted = Raster(1, 1, 3).also { it.data[0] = 0.55f; it.data[1] = 0.5f; it.data[2] = 0.48f }
        val vivid = Raster(1, 1, 3).also { it.data[0] = 0.95f; it.data[1] = 0.1f; it.data[2] = 0.1f }

        // Measured relative to what each already had. In absolute terms a vivid colour can still
        // move further simply because its spread is larger; what vibrance promises is that the
        // *proportional* push falls away as a colour approaches full saturation.
        fun relativeGain(r: Raster): Float {
            val before = spread(r, 0)
            val after = spread(ToneBrush.sponge(r, full(1), 1f), 0)
            return (after - before) / before
        }
        relativeGain(muted) shouldBeGreaterThan relativeGain(vivid) * 3f
    }

    @Test
    fun `desaturating fully lands on grey rather than overshooting into a new hue`() {
        var src = Raster(1, 1, 3).also { it.data[0] = 0.8f; it.data[1] = 0.3f; it.data[2] = 0.2f }
        repeat(20) { src = ToneBrush.sponge(src, full(1), -1f) }
        spread(src, 0) shouldBeLessThan 0.02f
    }

    // ---- blur and sharpen --------------------------------------------------------------------

    private fun edge(): Raster {
        val r = Raster(16, 1, 3)
        for (x in 0 until 16) {
            val v = if (x < 8) 0.3f else 0.7f
            for (c in 0 until 3) r.data[x * 3 + c] = v
        }
        return r
    }

    @Test
    fun `the blur brush softens an edge and the sharpen brush steepens it`() {
        val src = edge()
        val mask = FloatArray(16) { 1f }
        fun step(r: Raster) = r.data[8 * 3] - r.data[7 * 3]

        ToneBrush.focus(src, mask, -1f).let { step(it) shouldBeLessThan step(src) }
        ToneBrush.focus(src, mask, 1f).let { step(it) shouldBeGreaterThan step(src) }
    }

    @Test
    fun `focus outside the coverage is untouched`() {
        val src = edge()
        // Coverage only on the left half; the edge itself sits at index 8.
        val mask = FloatArray(16) { if (it < 4) 1f else 0f }
        val out = ToneBrush.focus(src, mask, -1f)
        for (x in 8 until 16) {
            abs(out.data[x * 3] - src.data[x * 3]) shouldBeLessThan 1e-5f
        }
    }

    // ---- smudge ------------------------------------------------------------------------------

    @Test
    fun `smudge drags colour along the path`() {
        // The one mode that cannot be answered from a mask, because it depends on the order the
        // dabs were laid and the direction the finger moved.
        val src = Raster(32, 8, 3)
        for (y in 0 until 8) {
            for (x in 0 until 32) {
                val v = if (x < 8) 0.9f else 0.1f
                for (c in 0 until 3) src.data[(y * 32 + x) * 3 + c] = v
            }
        }
        val path = (4..20).map { Vec2(it.toFloat(), 4f) }
        val out = ToneBrush.smudge(src, path, radius = 3f, strength = 0.8f)

        // Just past the boundary, the dark side has been lightened by the colour carried across it.
        out.data[(4 * 32 + 11) * 3] shouldBeGreaterThan src.data[(4 * 32 + 11) * 3]
    }

    @Test
    fun `smudge fades along its length rather than stamping one colour forever`() {
        val src = Raster(48, 8, 3)
        for (y in 0 until 8) {
            for (x in 0 until 48) {
                val v = if (x < 8) 0.9f else 0.1f
                for (c in 0 until 3) src.data[(y * 48 + x) * 3 + c] = v
            }
        }
        val path = (4..44).map { Vec2(it.toFloat(), 4f) }
        val out = ToneBrush.smudge(src, path, radius = 3f, strength = 0.8f)

        val near = out.data[(4 * 48 + 12) * 3]
        val far = out.data[(4 * 48 + 40) * 3]
        near shouldBeGreaterThan far
    }

    @Test
    fun `a path of one point smudges nothing`() {
        val src = flat(0.5f)
        val out = ToneBrush.smudge(src, listOf(Vec2(4f, 4f)), radius = 3f, strength = 1f)
        RasterMath.meanAbsDiff(src, out) shouldBe 0f
    }
}
