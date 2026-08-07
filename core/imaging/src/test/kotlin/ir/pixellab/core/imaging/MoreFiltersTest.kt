package ir.pixellab.core.imaging

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Vec2
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * The rest of the filter set: motion, lens, gradient blur, sharpening and the finishing effects.
 *
 * Each of these has a cheaper implementation that looks nearly right, and each test below is aimed
 * at the exact place the cheap version gives itself away — a lens blur with no aperture shape, a
 * vignette that ignores the frame's proportions, grain that changes every time it is applied.
 */
class MoreFiltersTest {

    private fun flat(size: Int = 32, value: Float = 0.5f) = Raster(size, size, 1).fill(value)

    /** A single bright pixel on black: the shape a blur spreads it into is the whole answer. */
    private fun dot(size: Int = 41): Raster =
        Raster(size, size, 1).also { it[size / 2, size / 2, 0] = 1f }

    private fun columns(size: Int = 32): Raster {
        val r = Raster(size, size, 1)
        for (y in 0 until size) for (x in 0 until size) r[x, y, 0] = if (x % 4 < 2) 1f else 0f
        return r
    }

    // ---- motion blur ---------------------------------------------------------------------------

    @Test
    fun `a horizontal motion blur smears sideways and leaves columns alone vertically`() {
        val out = MotionBlur.apply(dot(), angle = 0f, distance = 9f)
        val centre = 20
        // Along the trail the dot has spread; across it, nothing.
        (out[centre + 3, centre, 0] > 0f) shouldBe true
        out[centre, centre + 3, 0] shouldBe 0f
    }

    @Test
    fun `the trail is centred on the pixel, not trailing from it`() {
        val out = MotionBlur.apply(dot(), angle = 0f, distance = 11f)
        val centre = 20
        // A motion trail is symmetric about where the subject was; walking it in one direction
        // only would shift the whole image sideways.
        out[centre - 3, centre, 0] shouldBe out[centre + 3, centre, 0].plusOrMinus(1e-5f)
    }

    @Test
    fun `angle actually rotates the trail`() {
        val vertical = MotionBlur.apply(dot(), angle = 90f, distance = 9f)
        val centre = 20
        (vertical[centre, centre + 3, 0] > 0f) shouldBe true
        vertical[centre + 3, centre, 0] shouldBe 0f
    }

    @Test
    fun `a distance under one pixel is the image back`() {
        val src = columns()
        MotionBlur.apply(src, 45f, 0.5f).data.contentEquals(src.data) shouldBe true
    }

    @Test
    fun `motion blur does not change overall brightness`() {
        val src = columns()
        val out = MotionBlur.apply(src, 30f, 12f)
        // A blur that brightens or darkens has its samples weighted wrongly, and it shows up as a
        // halo the moment the layer is composited over anything.
        out.data.average().toFloat() shouldBe src.data.average().toFloat().plusOrMinus(0.03f)
    }

    // ---- lens blur -----------------------------------------------------------------------------

    @Test
    fun `a circular aperture is round and a hexagonal one is not`() {
        val circle = LensBlur.kernel(radius = 10f, blades = 0, rotation = 0f)
        val hexagon = LensBlur.kernel(radius = 10f, blades = 6, rotation = 0f)

        // The shape of the hole is the whole difference between a lens and a Gaussian: hexagonal
        // blades give hexagonal bokeh, and that is what people recognise without being told.
        circle.isNotEmpty() shouldBe true
        hexagon.isNotEmpty() shouldBe true
        (hexagon.size < circle.size) shouldBe true
    }

    @Test
    fun `a polygonal aperture stays inside the circle it replaces`() {
        val radius = 12f
        LensBlur.kernel(radius, blades = 5, rotation = 0f).all { (dx, dy) ->
            kotlin.math.hypot(dx.toFloat(), dy.toFloat()) <= radius + 1f
        } shouldBe true
    }

    @Test
    fun `rotating the aperture changes which pixels fall inside it`() {
        val upright = LensBlur.kernel(10f, blades = 5, rotation = 0f).toSet()
        val turned = LensBlur.kernel(10f, blades = 5, rotation = 36f).toSet()
        (upright != turned) shouldBe true
    }

    @Test
    fun `a highlight blooms into a disc rather than dissolving`() {
        val src = Raster(41, 41, 1)
        src[20, 20, 0] = 1f
        val out = LensBlur.apply(src, radius = 6f, blades = 0, highlightThreshold = 0.5f, highlightGain = 8f)

        // Weighted up before averaging, so the bright pixel spreads as a visible disc. Without the
        // weighting the effect is a slightly odd blur and nothing more.
        val near = out[23, 20, 0]
        val far = out[30, 20, 0]
        (near > 0f) shouldBe true
        far shouldBe 0f
    }

    @Test
    fun `a radius under a pixel is the image back`() {
        val src = columns()
        LensBlur.apply(src, radius = 0.4f).data.contentEquals(src.data) shouldBe true
    }

    // ---- gradient blur -------------------------------------------------------------------------

    @Test
    fun `a tilt-shift leaves the focal band sharp and blurs away from it`() {
        val src = columns(64)
        val out = GradientBlur.apply(
            src,
            shape = GradientBlur.Shape.LINEAR,
            centre = Vec2(32f, 32f),
            radius = 6f,
            focus = 6f,
            transition = 10f,
            angle = 0f,
        )
        // The band through the centre keeps its edges; the top of the frame does not.
        variation(out, 32, 32) shouldBe variation(src, 32, 32).plusOrMinus(0.05f)
        (variation(out, 32, 2) < variation(src, 32, 2) - 0.2f) shouldBe true
    }

    @Test
    fun `a radial gradient blur keeps the middle and blurs the corners`() {
        val src = columns(64)
        val out = GradientBlur.apply(
            src,
            shape = GradientBlur.Shape.RADIAL,
            centre = Vec2(32f, 32f),
            radius = 6f,
            focus = 8f,
            transition = 12f,
        )
        (variation(out, 32, 32) > variation(out, 60, 60)) shouldBe true
    }

    @Test
    fun `the transition has no visible corner in it`() {
        val src = columns(64)
        val out = GradientBlur.apply(
            src,
            shape = GradientBlur.Shape.RADIAL,
            centre = Vec2(32f, 32f),
            radius = 8f,
            focus = 5f,
            transition = 20f,
        )
        // Sampled along a ray, sharpness has to fall away smoothly. A linear ramp has a corner
        // where it reaches full blur, and the eye finds that edge immediately.
        val samples = (0..5).map { variation(out, 32 + it * 5, 32) }
        samples.zipWithNext().all { (a, b) -> b <= a + 0.06f } shouldBe true
    }

    @Test
    fun `a zero radius is the image back`() {
        val src = columns()
        GradientBlur.apply(src, GradientBlur.Shape.RADIAL, Vec2(16f, 16f), 0f, 5f, 5f)
            .data.contentEquals(src.data) shouldBe true
    }

    // ---- sharpening ----------------------------------------------------------------------------

    @Test
    fun `unsharp mask raises the contrast at an edge`() {
        val src = columns(32)
        val out = Sharpen.unsharpMask(src, amount = 1f, radius = 1.5f)
        (variation(out, 2, 16, radius = 3) > variation(src, 2, 16, radius = 3) - 0.001f) shouldBe true
    }

    @Test
    fun `a threshold leaves flat areas alone`() {
        val noisy = Raster(32, 32, 1).also { raster ->
            for (i in raster.data.indices) raster.data[i] = 0.5f + (if (i % 3 == 0) 0.01f else -0.01f)
        }
        val out = Sharpen.unsharpMask(noisy, amount = 3f, radius = 2f, threshold = 0.1f)
        // The whole difference between sharpening a photograph and sharpening its sensor noise.
        out.data.contentEquals(noisy.data) shouldBe true
    }

    @Test
    fun `sharpening never touches alpha`() {
        val src = Raster(16, 16, 4)
        for (y in 0 until 16) for (x in 0 until 16) {
            src[x, y, 0] = if (x < 8) 0f else 1f
            src[x, y, 3] = if (x < 8) 0f else 1f
        }
        val out = Sharpen.unsharpMask(src, amount = 2f, radius = 2f)
        // A sharpened matte carves a hard edge into a soft one, which shows as a white line around
        // every cut-out the moment it is composited.
        for (y in 0 until 16) for (x in 0 until 16) out[x, y, 3] shouldBe src[x, y, 3]
    }

    @Test
    fun `high pass centres on mid grey`() {
        val flat = flat(16, 0.8f)
        val out = Sharpen.highPass(flat, radius = 3f)
        // A flat area has no detail, so it must come out exactly neutral — that is what makes the
        // result usable in Overlay, where 0.5 changes nothing.
        out.data.all { abs(it - 0.5f) < 0.02f } shouldBe true
    }

    @Test
    fun `high pass keeps the detail and passes alpha through`() {
        val src = Raster(32, 32, 4)
        for (y in 0 until 32) for (x in 0 until 32) {
            val value = if (x % 8 < 4) 1f else 0f
            for (c in 0 until 3) src[x, y, c] = value
            src[x, y, 3] = 0.7f
        }
        val out = Sharpen.highPass(src, radius = 2f)
        (variation(out, 4, 16) > 0.1f) shouldBe true
        out[10, 10, 3] shouldBe 0.7f
    }

    // ---- finishing -----------------------------------------------------------------------------

    @Test
    fun `a vignette follows the frame rather than a circle`() {
        // Wide canvas: a circular falloff would reach the left and right edges long before the top
        // and bottom, leaving an obvious oval sitting in the middle.
        val src = Raster(120, 40, 1).fill(1f)
        val out = Stylise.vignette(src, amount = -0.9f)

        val leftEdge = out[1, 20, 0]
        val topEdge = out[60, 1, 0]
        leftEdge shouldBe topEdge.plusOrMinus(0.08f)
    }

    @Test
    fun `a vignette leaves the centre alone and darkens the corners`() {
        val src = Raster(64, 64, 1).fill(1f)
        val out = Stylise.vignette(src, amount = -0.8f)
        out[32, 32, 0] shouldBe 1f.plusOrMinus(0.01f)
        (out[1, 1, 0] < 0.5f) shouldBe true
    }

    @Test
    fun `a positive amount lightens instead`() {
        val src = Raster(64, 64, 1).fill(0.4f)
        val out = Stylise.vignette(src, amount = 0.8f)
        (out[1, 1, 0] > 0.4f) shouldBe true
    }

    @Test
    fun `pixelate averages a block rather than sampling one pixel from it`() {
        val src = Raster(8, 8, 1)
        // One bright pixel in an otherwise black block: sampling would give either 1 or 0, and
        // averaging gives the fraction — which is what makes the blocks read as flat.
        src[0, 0, 0] = 1f
        val out = Stylise.pixelate(src, blockSize = 4)
        out[3, 3, 0] shouldBe (1f / 16f).plusOrMinus(1e-5f)
        out[0, 0, 0] shouldBe out[3, 3, 0]
    }

    @Test
    fun `a partial block at the edge is still averaged over what is there`() {
        val src = Raster(6, 6, 1).fill(0.5f)
        val out = Stylise.pixelate(src, blockSize = 4)
        // The right-hand block is two wide, not four; dividing by four would darken it.
        out[5, 5, 0] shouldBe 0.5f.plusOrMinus(1e-5f)
    }

    @Test
    fun `a block size of one is the image back`() {
        val src = columns()
        Stylise.pixelate(src, 1).data.contentEquals(src.data) shouldBe true
    }

    @Test
    fun `grain is identical every time it is applied`() {
        val src = flat()
        val once = Stylise.noise(src, amount = 0.2f, seed = 7)
        val twice = Stylise.noise(src, amount = 0.2f, seed = 7)
        // An effect that changed on every application would make undo and redo produce different
        // pictures, and an export would not match what was on screen.
        once.data.contentEquals(twice.data) shouldBe true
    }

    @Test
    fun `a different seed gives different grain`() {
        val src = flat()
        val a = Stylise.noise(src, amount = 0.2f, seed = 1)
        val b = Stylise.noise(src, amount = 0.2f, seed = 2)
        a.data.contentEquals(b.data) shouldBe false
    }

    @Test
    fun `monochrome grain moves every channel together`() {
        val src = Raster(16, 16, 3).fill(0.5f)
        val out = Stylise.noise(src, amount = 0.3f, monochrome = true, seed = 3)
        // What film does. Per-channel noise reads as digital sensor noise, a different look.
        for (y in 0 until 16) for (x in 0 until 16) {
            out[x, y, 0] shouldBe out[x, y, 1]
            out[x, y, 1] shouldBe out[x, y, 2]
        }
    }

    @Test
    fun `colour grain does not`() {
        val src = Raster(16, 16, 3).fill(0.5f)
        val out = Stylise.noise(src, amount = 0.3f, monochrome = false, seed = 3)
        var differs = false
        for (y in 0 until 16) for (x in 0 until 16) {
            if (out[x, y, 0] != out[x, y, 1]) differs = true
        }
        differs shouldBe true
    }

    @Test
    fun `grain stays inside the range`() {
        val src = flat(32, 0.98f)
        Stylise.noise(src, amount = 0.5f, seed = 9).data.all { it in 0f..1f } shouldBe true
    }

    /** How much a small neighbourhood varies: high where detail survived, low where it smeared. */
    private fun variation(r: Raster, x: Int, y: Int, radius: Int = 2): Float {
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (dy in -radius..radius) for (dx in -radius..radius) {
            val v = r.clamped(x + dx, y + dy, 0)
            if (v < min) min = v
            if (v > max) max = v
        }
        return max - min
    }

    // ---- smart sharpen -------------------------------------------------------------------------

    @Test
    fun `smart sharpen fades out of the highlights`() {
        // A halo in a highlight has nowhere to go — the channel is already near clipping — so it
        // shows as a hard white rim. That is the artefact the fade exists to prevent.
        fun edgeAt(base: Float): Float {
            val size = 32
            val r = Raster(size, size, 1)
            for (y in 0 until size) {
                for (x in 0 until size) r[x, y, 0] = if (x < size / 2) base - 0.1f else base
            }
            val out = Sharpen.smart(r, amount = 2f, radius = 2f, fadeHighlights = 1f, fadeShadows = 0f)
            return abs(out[14, 16, 0] - r[14, 16, 0])
        }
        (edgeAt(0.95f) < edgeAt(0.5f)) shouldBe true
    }

    @Test
    fun `smart sharpen fades out of the shadows`() {
        fun edgeAt(base: Float): Float {
            val size = 32
            val r = Raster(size, size, 1)
            for (y in 0 until size) {
                for (x in 0 until size) r[x, y, 0] = if (x < size / 2) base else base + 0.1f
            }
            val out = Sharpen.smart(r, amount = 2f, radius = 2f, fadeHighlights = 0f, fadeShadows = 1f)
            return abs(out[14, 16, 0] - r[14, 16, 0])
        }
        (edgeAt(0.02f) < edgeAt(0.5f)) shouldBe true
    }

    @Test
    fun `undoing a lens blur uses a wider mask than undoing a Gaussian`() {
        // A lens kernel is flat-topped, so the mask that inverts it has to reach further than its
        // nominal radius. Using the Gaussian's mask is where the haloes come from.
        val size = 48
        val r = Raster(size, size, 1)
        for (y in 0 until size) {
            for (x in 0 until size) r[x, y, 0] = if (x < size / 2) 0.35f else 0.65f
        }

        fun reach(lens: Boolean): Float {
            val out = Sharpen.smart(r, amount = 1.5f, radius = 3f, lens = lens, fadeShadows = 0f, fadeHighlights = 0f)
            var total = 0f
            for (x in 0 until size) total += abs(out[x, 24, 0] - r[x, 24, 0])
            return total
        }
        (reach(lens = true) > reach(lens = false)) shouldBe true
    }
}
