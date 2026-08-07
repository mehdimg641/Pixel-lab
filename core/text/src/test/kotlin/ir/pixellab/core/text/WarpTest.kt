package ir.pixellab.core.text

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.TextWarp
import ir.pixellab.core.model.Vec2
import ir.pixellab.core.model.WarpStyle
import kotlin.math.abs
import org.junit.jupiter.api.Test

/**
 * Warp Text.
 *
 * Fifteen styles, and they are not decoration on one curve: each is a specific pair of displacement
 * curves, and reusing a single one collapses Arc, Flag and Fish into the same shape — which is what
 * every implementation that treats warp as "bend the baseline" ends up with.
 */
class WarpTest {

    private val box = Rect(0f, 0f, 200f, 100f)

    private fun map(style: WarpStyle, at: Vec2, bend: Float = 1f) =
        TextWarper.map(at, box, TextWarp(style = style, bend = bend))

    @Test
    fun `no warp leaves every point exactly where it was`() {
        val point = Vec2(37f, 61f)
        TextWarper.map(point, box, TextWarp.NONE) shouldBe point
    }

    @Test
    fun `a zero-sized box is left alone rather than dividing by nothing`() {
        val flat = Rect(10f, 10f, 10f, 10f)
        TextWarper.map(Vec2(10f, 10f), flat, TextWarp(WarpStyle.ARC)) shouldBe Vec2(10f, 10f)
    }

    @Test
    fun `an arc pushes the middle furthest`() {
        val middle = map(WarpStyle.ARCH, Vec2(100f, 50f))
        val end = map(WarpStyle.ARCH, Vec2(0f, 50f))
        // The bow is strongest at the centre and vanishes at the ends, which is what makes it an
        // arc rather than a shear.
        (abs(middle.y - 50f) > abs(end.y - 50f)) shouldBe true
    }

    @Test
    fun `the ends of an arc stay put`() {
        val left = map(WarpStyle.ARCH, Vec2(0f, 50f))
        val right = map(WarpStyle.ARCH, Vec2(200f, 50f))
        left.y shouldBe 50f.plusOrMinus(1f)
        right.y shouldBe 50f.plusOrMinus(1f)
    }

    @Test
    fun `bend reverses the curve`() {
        val up = map(WarpStyle.ARCH, Vec2(100f, 50f), bend = 1f)
        val down = map(WarpStyle.ARCH, Vec2(100f, 50f), bend = -1f)
        ((up.y - 50f) * (down.y - 50f) < 0f) shouldBe true
    }

    @Test
    fun `zero bend is the same as no warp`() {
        val point = Vec2(63f, 21f)
        val warped = TextWarper.map(point, box, TextWarp(style = WarpStyle.WAVE, bend = 0f))
        warped.x shouldBe point.x.plusOrMinus(0.01f)
        warped.y shouldBe point.y.plusOrMinus(0.01f)
    }

    @Test
    fun `arch moves both edges together and bulge moves them apart`() {
        val archTop = map(WarpStyle.ARCH, Vec2(100f, 0f)).y
        val archBottom = map(WarpStyle.ARCH, Vec2(100f, 100f)).y - 100f
        // Arch keeps the text's thickness through the curve; bulge is the one that fattens it.
        (archTop * archBottom > 0f) shouldBe true

        val bulgeTop = map(WarpStyle.BULGE, Vec2(100f, 0f)).y
        val bulgeBottom = map(WarpStyle.BULGE, Vec2(100f, 100f)).y - 100f
        (bulgeTop * bulgeBottom < 0f) shouldBe true
    }

    @Test
    fun `the two shells bend opposite edges`() {
        val lowerTop = abs(map(WarpStyle.SHELL_LOWER, Vec2(0f, 0f)).y - 0f)
        val lowerBottom = abs(map(WarpStyle.SHELL_LOWER, Vec2(0f, 100f)).y - 100f)
        // "Lower" means the lower edge is the one that moves; swapping them is a difference the
        // user sees immediately because the text sits on the wrong side of the curve.
        (lowerBottom > lowerTop) shouldBe true

        val upperTop = abs(map(WarpStyle.SHELL_UPPER, Vec2(0f, 0f)).y - 0f)
        val upperBottom = abs(map(WarpStyle.SHELL_UPPER, Vec2(0f, 100f)).y - 100f)
        (upperTop > upperBottom) shouldBe true
    }

    @Test
    fun `a wave changes direction more than once along its length`() {
        val samples = (0..8).map { map(WarpStyle.WAVE, Vec2(it * 25f, 50f)).y - 50f }
        var turns = 0
        for (i in 1 until samples.size - 1) {
            if ((samples[i] - samples[i - 1]) * (samples[i + 1] - samples[i]) < 0f) turns++
        }
        // A wave that only turns once is an arc. Two frequencies are what stop it reading as a
        // plain sine.
        (turns >= 2) shouldBe true
    }

    @Test
    fun `a rise tilts rather than bows`() {
        val start = map(WarpStyle.RISE, Vec2(0f, 50f)).y - 50f
        val middle = map(WarpStyle.RISE, Vec2(100f, 50f)).y - 50f
        val end = map(WarpStyle.RISE, Vec2(200f, 50f)).y - 50f
        // Monotonic, unlike every arc: the far end lifts and the near end stays.
        (start < middle && middle < end) shouldBe true
    }

    @Test
    fun `a fisheye pushes outwards in both directions`() {
        val warped = map(WarpStyle.FISHEYE, Vec2(120f, 60f))
        // The only style that moves a point horizontally as well as vertically; treating it like
        // the others gives a vertical squash instead of a lens.
        (abs(warped.x - 120f) > 0.5f) shouldBe true
        (abs(warped.y - 60f) > 0.5f) shouldBe true
    }

    @Test
    fun `a twist rotates`() {
        val warped = map(WarpStyle.TWIST, Vec2(180f, 20f))
        (abs(warped.x - 180f) > 1f) shouldBe true
    }

    @Test
    fun `every style does something`() {
        for (style in WarpStyle.entries) {
            if (style == WarpStyle.NONE) continue
            val moved = (0..4).flatMap { i -> (0..4).map { j -> Vec2(i * 50f, j * 25f) } }
                .count { point ->
                    val warped = map(style, point)
                    abs(warped.x - point.x) > 0.1f || abs(warped.y - point.y) > 0.1f
                }
            // A style with no branch renders as a plain copy, which reads as the control being
            // broken rather than as a missing feature.
            withClue(style.name) { (moved > 0) shouldBe true }
        }
    }

    @Test
    fun `every style has a persian label`() {
        for (style in WarpStyle.entries) {
            withClue(style.name) { style.persianLabel.isNotBlank() shouldBe true }
        }
    }

    @Test
    fun `horizontal distortion tapers rather than bending`() {
        // Sampled off the centre line: the taper scales about the middle, so a point *on* it has no
        // leverage and would not move however far the slider went.
        val plain = TextWarper.map(Vec2(180f, 0f), box, TextWarp(style = WarpStyle.ARCH, horizontal = 0f))
        val tapered = TextWarper.map(Vec2(180f, 0f), box, TextWarp(style = WarpStyle.ARCH, horizontal = 0.8f))
        // It composes with the style rather than replacing it, which is why the two are separate
        // controls in the panel at all.
        (abs(tapered.x - plain.x) > 0.5f) shouldBe true
    }

    @Test
    fun `the vertical axis warps down the page instead of across it`() {
        val across = TextWarper.map(Vec2(100f, 50f), box, TextWarp(WarpStyle.ARCH, horizontalAxis = true))
        val down = TextWarper.map(Vec2(100f, 50f), box, TextWarp(WarpStyle.ARCH, horizontalAxis = false))
        (across != down) shouldBe true
    }

    @Test
    fun `expanded bounds cover where the warp actually reaches`() {
        val warp = TextWarp(style = WarpStyle.ARCH, bend = 1f)
        val expanded = TextWarper.expandedBounds(box, warp)
        // The renderer needs this before it rasterises: a warp that reaches past the texture is
        // clipped, and the clip appears as a flat edge across the middle of a letter.
        (expanded.height > box.height) shouldBe true
        (expanded.top <= box.top && expanded.bottom >= box.bottom) shouldBe true
    }

    @Test
    fun `an unwarped box is its own expansion`() {
        TextWarper.expandedBounds(box, TextWarp.NONE) shouldBe box
    }

    private inline fun withClue(clue: String, block: () -> Unit) {
        try {
            block()
        } catch (e: AssertionError) {
            throw AssertionError("$clue: ${e.message}", e)
        }
    }
}
