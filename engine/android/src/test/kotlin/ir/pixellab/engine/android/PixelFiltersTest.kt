package ir.pixellab.engine.android

import io.kotest.matchers.shouldBe
import ir.pixellab.core.codec.RasterImage
import ir.pixellab.core.imaging.RadialBlur
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Rect
import ir.pixellab.core.paint.Marquee
import ir.pixellab.core.paint.PixelSelection
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * Filters that land in the pixels.
 *
 * The algorithms are tested in `core:imaging`; what is checked here is the wiring around them, and
 * every one of these is a mistake that produces a picture rather than an error: a blur that halos
 * because it averaged transparent black, a filter that ignored the selection, a fill that put a hard
 * edge where the user asked for a feathered one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PixelFiltersTest {

    private val size = 48

    private fun checkers(): RasterImage = RasterImage(
        size, size,
        IntArray(size * size) { i ->
            val x = i % size
            val y = i / size
            if ((x / 4 + y / 4) % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()
        },
    )

    /** An opaque white disc on fully transparent black — the shape that exposes a halo. */
    private fun cutout(): RasterImage = RasterImage(
        size, size,
        IntArray(size * size) { i ->
            val dx = (i % size) - size / 2f
            val dy = (i / size) - size / 2f
            if (dx * dx + dy * dy < 144f) 0xFFFFFFFF.toInt() else 0x00000000
        },
    )

    private fun leftHalf(): PixelSelection =
        Marquee.rectangle(size, size, Rect(0f, 0f, size / 2f, size.toFloat()))

    private fun rgbOf(argb: Int) = Triple((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)

    // ---- blur ------------------------------------------------------------------------------------

    @Test
    fun `a zero radius blur is the image back`() {
        val src = checkers()
        (PixelFilters.gaussian(src, 0f) === src) shouldBe true
    }

    @Test
    fun `a blur softens the checkerboard`() {
        val src = checkers()
        val out = PixelFilters.gaussian(src, 4f)
        val edge = out[16, 16]
        val (r, g, b) = rgbOf(edge)
        // Anything strictly between black and white means neighbouring squares were averaged.
        (r in 20..235 && g in 20..235 && b in 20..235) shouldBe true
    }

    @Test
    fun `blurring a cut-out does not darken its edge`() {
        val out = PixelFilters.gaussian(cutout(), 3f)
        // The disc is white; every pixel that still has alpha must still be white. Averaging
        // straight colour instead would pull in the transparent pixels' black and ring the subject
        // with grey — the single most recognisable sign of a blur done without premultiplying.
        var worst = 0
        for (i in out.pixels.indices) {
            val p = out.pixels[i]
            if ((p ushr 24) and 0xFF < 128) continue
            val (r, g, b) = rgbOf(p)
            worst = maxOf(worst, 255 - minOf(r, g, b))
        }
        (worst < 12) shouldBe true
    }

    @Test
    fun `a blur stays inside the selection`() {
        val src = checkers()
        val out = PixelFilters.gaussian(src, 5f, leftHalf())
        // Untouched on the right, changed on the left. A filter that ignored the selection is the
        // version that quietly destroys the part of the layer the user was protecting.
        out[40, 24] shouldBe src[40, 24]
        (out[8, 20] != src[8, 20]) shouldBe true
    }

    @Test
    fun `both radial methods run and change the image`() {
        val src = checkers()
        for (kind in RadialBlur.Kind.entries) {
            val out = PixelFilters.radial(src, 0.6f, kind)
            val changed = out.pixels.indices.count { out.pixels[it] != src.pixels[it] }
            (changed > src.pixels.size / 4) shouldBe true
        }
    }

    @Test
    fun `a radial blur of zero amount is the image back`() {
        val src = checkers()
        (PixelFilters.radial(src, 0f, RadialBlur.Kind.SPIN) === src) shouldBe true
    }

    // ---- fill ------------------------------------------------------------------------------------

    @Test
    fun `a fill with no selection covers everything`() {
        val out = PixelFilters.fill(checkers(), Color(1f, 0f, 0f))
        out.pixels.all { it == 0xFFFF0000.toInt() } shouldBe true
    }

    @Test
    fun `a fill respects partial coverage`() {
        val src = RasterImage.blank(size, size, 0xFF000000.toInt())
        // Sample points chosen outside the feather's reach at both ends, so "fully" means it.
        val feathered = Marquee.rectangle(size, size, Rect(12f, 12f, 36f, 36f), feather = 3f)
        val out = PixelFilters.fill(src, Color(1f, 1f, 1f), feathered)

        // Fully inside, fully outside, and genuinely in between at the edge — a fill that
        // thresholded coverage would put a hard edge exactly where the softness was asked for.
        out[24, 24] shouldBe 0xFFFFFFFF.toInt()
        out[1, 1] shouldBe 0xFF000000.toInt()
        val (r, _, _) = rgbOf(out[12, 24])
        (r in 20..235) shouldBe true
    }

    @Test
    fun `preserving transparency keeps a fill inside what is already painted`() {
        val out = PixelFilters.fill(cutout(), Color(1f, 0f, 0f), preserveTransparency = true)
        // Photoshop's lock-transparency: a painted shape is recoloured without spilling around it.
        out[24, 24] shouldBe 0xFFFF0000.toInt()
        (out[1, 1] ushr 24) shouldBe 0
    }

    @Test
    fun `clearing removes alpha inside the selection only`() {
        val src = RasterImage.blank(size, size, 0xFF3366AA.toInt())
        val out = PixelFilters.clear(src, leftHalf())
        (out[8, 8] ushr 24) shouldBe 0
        out[40, 8] shouldBe 0xFF3366AA.toInt()
    }

    @Test
    fun `clearing keeps the colour under a partly cleared pixel`() {
        val src = RasterImage.blank(size, size, 0xFF3366AA.toInt())
        val feathered = Marquee.rectangle(size, size, Rect(8f, 8f, 40f, 40f), feather = 6f)
        val out = PixelFilters.clear(src, feathered)
        val edge = out[8, 24]
        // Alpha comes down, the colour does not change: zeroing the colour as well is what turns a
        // soft eraser into a dark fringe as soon as the layer is composited over anything light.
        (abs(((edge shr 16) and 0xFF) - 0x33) < 2) shouldBe true
        ((edge ushr 24) and 0xFF in 1..254) shouldBe true
    }
}
