package ir.pixellab.core.render

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import org.junit.jupiter.api.Test

/**
 * The arithmetic that lets the distance field live in eight-bit channels.
 *
 * ### Why this is worth its own file
 *
 * The field used to be written into a half-float target, and on a driver that cannot render into
 * one it fell back to `RGBA8`, where everything clamps to 0..1. The signed distance and the "no
 * seed" sentinel both collapsed, the stroke shader read full coverage everywhere outside the
 * letters, and it painted **an opaque rectangle across the whole layer**. That is the black slab a
 * user photographed behind their headline, and it was the first thing in a brand-new document.
 *
 * The shaders now pack a signed distance into sixteen bits across a channel pair. That is a claim
 * about *exactness*, and a claim about exactness on a quantised target is precisely the kind that
 * looks fine in a preview and is wrong by half a pixel everywhere. So it is checked here in Kotlin,
 * against the same eight-bit quantisation the GPU applies, using the same formulas the GLSL uses —
 * kept side by side deliberately, because two implementations of one encoding that drift apart
 * produce an outline made of noise and no error anywhere.
 */
class PackedFieldTest {

    /** What an `RGBA8` target does to a value on its way in and out. */
    private fun quantise(channel: Float): Float = (channel.coerceIn(0f, 1f) * 255f).roundToInt() / 255f

    // ---- the GLSL, transcribed -------------------------------------------------------------------

    private fun packUnit(v: Float): Pair<Float, Float> {
        val q = floor(v.coerceIn(0f, 1f) * 65535f + 0.5f)
        val hi = floor(q / 256f)
        return hi / 255f to (q - hi * 256f) / 255f
    }

    private fun unpackUnit(c: Pair<Float, Float>): Float =
        (floor(c.first * 255f + 0.5f) * 256f + floor(c.second * 255f + 0.5f)) / 65535f

    private fun packSigned(px: Float, range: Float) = packUnit((px / range).coerceIn(-1f, 1f) * 0.5f + 0.5f)

    private fun unpackSigned(c: Pair<Float, Float>, range: Float) = (unpackUnit(c) * 2f - 1f) * range

    /** Encode, store in eight bits per channel as the GPU would, decode. */
    private fun roundTrip(px: Float, range: Float): Float {
        val (hi, lo) = packSigned(px, range)
        return unpackSigned(quantise(hi) to quantise(lo), range)
    }

    // ---- the claims ------------------------------------------------------------------------------

    @Test
    fun `a signed distance survives an eight-bit target`() {
        // The property the whole encoding exists for. Sixteen bits over ±256 px is one part in
        // 32768 of the range — far finer than the half float it replaces ever needed to be, and
        // nothing like the *one bit* the naive version had.
        val range = 256f
        val tolerance = range / 30000f
        for (step in -100..100) {
            val px = range * step / 100f
            roundTrip(px, range) shouldBe (px plusOrMinus tolerance)
        }
    }

    @Test
    fun `the sign is never lost, which is the failure that drew rectangles`() {
        // The naive encoding clamped negatives to zero, so every pixel *inside* the shape reported
        // a distance of zero and every pixel outside reported one. That single fact is what made a
        // stroke cover the entire texture.
        val range = 64f
        for (step in 1..64) {
            (roundTrip(-step.toFloat(), range) < 0f) shouldBe true
            (roundTrip(step.toFloat(), range) > 0f) shouldBe true
        }
        roundTrip(0f, range) shouldBe (0f plusOrMinus 0.01f)
    }

    @Test
    fun `beyond the reach it saturates rather than wrapping`() {
        // A distance past the reach is not interesting, but a value that *wraps* is catastrophic:
        // the far corner of a texture would report itself adjacent to the shape and the stroke
        // would reappear there. Saturation is the only safe behaviour at the ends.
        val range = 32f
        roundTrip(1000f, range) shouldBe (range plusOrMinus 0.01f)
        roundTrip(-1000f, range) shouldBe (-range plusOrMinus 0.01f)
    }

    @Test
    fun `the no-seed sentinel is recognisable after a round trip`() {
        // The flood has no spare channel for a "found one" flag, so "nothing found yet" is written
        // as the full reach on both axes — a vector longer than the reach itself. If quantisation
        // pulled that back under the reach, an unseeded pixel would pass for a real answer and the
        // field would fill with garbage near the edges of the texture.
        val range = 48f
        val x = roundTrip(range, range)
        val y = roundTrip(range, range)
        (hypot(x, y) >= range) shouldBe true
    }

    // ---- against an exact oracle -------------------------------------------------------------------

    @Test
    fun `a whole field round-trips within a fifteenth of a pixel`() {
        // A disc, whose signed distance is known in closed form, sampled everywhere and pushed
        // through the encoding. Comparing against arithmetic rather than against another
        // implementation is what makes this an oracle instead of a mirror.
        val size = 64
        val radius = 20f
        val range = 32f
        var worst = 0f

        for (y in 0 until size) {
            for (x in 0 until size) {
                val exact = hypot(x - size / 2f, y - size / 2f) - radius
                val clamped = exact.coerceIn(-range, range)
                worst = maxOf(worst, abs(roundTrip(clamped, range) - clamped))
            }
        }
        (worst < 1f / 15f) shouldBe true
    }

    @Test
    fun `the encoding in the shader is the encoding tested here`() {
        // The two live apart and must not drift. Checking the source text is crude and it is the
        // only thing that fails loudly when somebody edits one and not the other.
        val common = Shaders.SDF_RESOLVE.fragment
        ("floor(clamp(v, 0.0, 1.0) * 65535.0 + 0.5)" in common) shouldBe true
        ("(unpackUnit(c) * 2.0 - 1.0) * uSdfRange" in common) shouldBe true
    }

    @Test
    fun `every value the encoder can produce decodes to something in range`() {
        // Exhaustive over the eight-bit pairs, because there are only 65536 of them and an encoding
        // is exactly the kind of thing that is right on the values somebody thought to try.
        val range = 100f
        for (hi in 0..255) {
            for (lo in 0..255) {
                val v = unpackSigned(hi / 255f to lo / 255f, range)
                (v >= -range - 0.01f && v <= range + 0.01f) shouldBe true
            }
        }
    }

    @Test
    fun `a coarse reach still resolves finer than a pixel`() {
        // The reach grows with the effect: a 200 px glow asks for a 200 px field. Precision has to
        // stay under a pixel there too, or a wide glow would band.
        val range = 600f
        val worst = (0..1000).maxOf { step ->
            val px = -range + 2f * range * step / 1000f
            abs(roundTrip(px, range) - px)
        }
        (worst < 0.1f) shouldBe true
    }

    @Test
    fun `the smallest reach the graph allows is still exact`() {
        val range = min(8f, 8f)
        roundTrip(3.25f, range) shouldBe (3.25f plusOrMinus 0.001f)
    }
}
