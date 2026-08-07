package ir.pixellab.core.imaging

import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Vec2
import kotlin.math.abs
import org.junit.jupiter.api.Test

/**
 * Path blur, asserted on the thing that makes it path blur.
 *
 * A directional blur would pass a test that only checks "the image got softer". What distinguishes
 * this filter is that the direction *varies across the frame* and that the smear falls off with
 * distance from the stroke — so the tests below compare one region against another rather than
 * comparing the image against itself.
 */
class PathBlurTest {

    /** A single bright pixel on black: the impulse response shows the smear's shape directly. */
    private fun impulse(width: Int, height: Int, x: Int, y: Int): Raster {
        val r = Raster(width, height, 1)
        r[x, y, 0] = 1f
        return r
    }

    private fun horizontal(speed: Float) = PathBlur.Path(
        listOf(Vec2(0f, 32f), Vec2(64f, 32f)),
        startSpeed = speed,
        endSpeed = speed,
    )

    @Test
    fun `smears along the path direction and not across it`() {
        val src = impulse(64, 64, 32, 32)

        val out = PathBlur.apply(src, listOf(horizontal(16f)), 1f)

        // Along the stroke the impulse has spread; across it, it has not. That asymmetry is the
        // whole filter — a blur that spread both ways is a Gaussian.
        val along = out[28, 32, 0] + out[36, 32, 0]
        val across = out[32, 28, 0] + out[32, 36, 0]
        (along > across * 10f) shouldBe true
    }

    @Test
    fun `conserves what was in the image`() {
        val src = impulse(64, 64, 32, 32)

        val out = PathBlur.apply(src, listOf(horizontal(16f)), 1f)

        // Total energy is preserved to within the light that walked off the edge. A filter that
        // divides by the wrong count darkens or brightens the whole frame, which is invisible on
        // one image and obvious the moment it is layered over the original.
        val before = src.data.sum()
        val after = out.data.sum()
        (abs(after - before) < 0.05f) shouldBe true
    }

    @Test
    fun `falls off with distance from the stroke`() {
        // Two impulses, one on the stroke and one far from it. This is what leaves a subject sharp
        // without a mask, and it is the behaviour a directional blur cannot express at all.
        val src = Raster(128, 128, 1)
        src[32, 8, 0] = 1f
        src[32, 120, 0] = 1f
        val path = PathBlur.Path(listOf(Vec2(0f, 8f), Vec2(127f, 8f)), 24f, 24f)

        // An explicit reach, because that is the parameter under test. The default is a quarter of
        // the diagonal, which on this canvas would put the far impulse right at the half-strength
        // point and make the assertion a coin toss.
        val out = PathBlur.apply(src, listOf(path), 1f, reach = 24f)

        // The near impulse is spread thin; the far one keeps most of its peak.
        (out[32, 8, 0] < 0.2f) shouldBe true
        (out[32, 120, 0] > 0.8f) shouldBe true
    }

    @Test
    fun `direction follows the path where the path turns`() {
        // An L-shaped stroke: horizontal along the top, vertical down the right. A pixel beside
        // each arm must smear the way *that* arm runs, which one global vector cannot do.
        val src = Raster(96, 96, 1)
        src[20, 10, 0] = 1f
        src[86, 70, 0] = 1f
        val path = PathBlur.Path(
            listOf(Vec2(4f, 10f), Vec2(86f, 10f), Vec2(86f, 92f)),
            startSpeed = 16f,
            endSpeed = 16f,
        )

        val out = PathBlur.apply(src, listOf(path), 1f)

        // Beside the horizontal arm: spread left-right.
        val topAlong = out[16, 10, 0] + out[24, 10, 0]
        val topAcross = out[20, 6, 0] + out[20, 14, 0]
        (topAlong > topAcross) shouldBe true

        // Beside the vertical arm: spread up-down. Same picture, opposite axis.
        val sideAlong = out[86, 66, 0] + out[86, 74, 0]
        val sideAcross = out[82, 70, 0] + out[90, 70, 0]
        (sideAlong > sideAcross) shouldBe true
    }

    @Test
    fun `speed varies between the endpoints`() {
        // Slow at the start, fast at the end — the swing. Two identical impulses under the two ends
        // of one stroke must come out differently blurred, or the endpoint speeds do nothing.
        val src = Raster(128, 32, 1)
        src[10, 16, 0] = 1f
        src[117, 16, 0] = 1f
        val path = PathBlur.Path(listOf(Vec2(0f, 16f), Vec2(127f, 16f)), startSpeed = 1f, endSpeed = 32f)

        val out = PathBlur.apply(src, listOf(path), 1f)

        (out[10, 16, 0] > out[117, 16, 0]) shouldBe true
    }

    @Test
    fun `zero amount is the identity`() {
        val src = impulse(32, 32, 16, 16)

        val out = PathBlur.apply(src, listOf(horizontal(16f)), 0f)

        out.data.toList() shouldBe src.data.toList()
    }

    @Test
    fun `no paths is the identity`() {
        val src = impulse(32, 32, 16, 16)

        PathBlur.apply(src, emptyList(), 1f).data.toList() shouldBe src.data.toList()
    }

    @Test
    fun `a degenerate path does not divide by zero`() {
        // Two identical points: zero length, no direction. It must be ignored rather than produce
        // NaN, because a path with one stray duplicated point is what a drawn stroke actually is.
        val src = impulse(32, 32, 16, 16)
        val path = PathBlur.Path(listOf(Vec2(8f, 8f), Vec2(8f, 8f)), 16f, 16f)

        val out = PathBlur.apply(src, listOf(path), 1f)

        out.data.none { it.isNaN() } shouldBe true
    }

    @Test
    fun `sample count tracks travel and is bounded`() {
        PathBlur.samplesFor(0f) shouldBe 1
        PathBlur.samplesFor(8f) shouldBe 8
        // Bounded, or a long stroke on a large canvas costs time for samples closer together than
        // the pixels they read.
        (PathBlur.samplesFor(10_000f) <= 64) shouldBe true
    }

    @Test
    fun `works on colour without mixing the channels`() {
        val src = Raster(64, 64, 3)
        src[32, 32, 0] = 1f

        val out = PathBlur.apply(src, listOf(horizontal(16f)), 1f)

        // Red spread; green and blue stayed empty. A loop that reuses one accumulator across
        // channels bleeds them into each other and turns a red streak grey.
        (out[28, 32, 0] > 0f) shouldBe true
        out[28, 32, 1] shouldBe 0f
        out[28, 32, 2] shouldBe 0f
    }
}
