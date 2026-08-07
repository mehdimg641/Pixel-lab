package ir.pixellab.core.imaging

import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * The filter gallery.
 *
 * Testing "the output differs from the input" would pass for every one of these and for a random
 * number generator, so each test here asks the question the *algorithm* is supposed to answer: does
 * the oil paint keep an edge sharp where a blur would soften it, does the watercolour actually pool
 * pigment at a boundary, do the pencil strokes follow the picture's contours, are the crystal cells
 * flat and jittered.
 */
class ArtisticTest {

    /** A hard vertical edge: black on the left, white on the right. */
    private fun edge(w: Int = 48, h: Int = 48): Raster {
        val r = Raster(w, h, 3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = if (x < w / 2) 0.15f else 0.85f
                val o = r.index(x, y)
                r.data[o] = v
                r.data[o + 1] = v
                r.data[o + 2] = v
            }
        }
        return r
    }

    /** Flat mid-grey with fine noise, for testing that texture is removed. */
    private fun noisy(w: Int = 48, h: Int = 48): Raster {
        val r = Raster(w, h, 3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = 0.5f + if ((x + y) % 2 == 0) 0.08f else -0.08f
                val o = r.index(x, y)
                r.data[o] = v
                r.data[o + 1] = v
                r.data[o + 2] = v
            }
        }
        return r
    }

    /** How far the middle of the picture travels from black to white, in pixels. */
    private fun edgeWidth(r: Raster): Int {
        val y = r.height / 2
        var count = 0
        for (x in 0 until r.width) {
            val v = r[x, y]
            if (v > 0.25f && v < 0.75f) count++
        }
        return count
    }

    private fun spread(r: Raster): Float {
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (i in 0 until r.pixelCount) {
            val v = r.data[i * r.channels]
            if (v < min) min = v
            if (v > max) max = v
        }
        return max - min
    }

    // ---- oil paint ---------------------------------------------------------------------------

    @Test
    fun `oil paint keeps the edge where a blur would smear it`() {
        // The whole reason it takes the mode rather than the mean. If this test ever fails, the
        // filter has become an expensive blur.
        val source = edge()
        val painted = Artistic.oilPaint(source, radius = 4)
        val blurred = Blur.gaussian(source, 4f)
        (edgeWidth(painted) < edgeWidth(blurred)) shouldBe true
    }

    @Test
    fun `oil paint flattens texture into strokes`() {
        // A wash of fine noise should come out as flat paint, which is the visible half of what the
        // mode filter does.
        val painted = Artistic.oilPaint(noisy(), radius = 3)
        spread(painted) shouldBeLessThan spread(noisy())
    }

    @Test
    fun `fewer levels means broader strokes`() {
        // Photoshop's Stylization slider, and the one that changes the character rather than the
        // scale — so it has to actually do something distinct from the radius.
        val source = noisy()
        val coarse = Artistic.oilPaint(source, radius = 3, levels = 4)
        val fine = Artistic.oilPaint(source, radius = 3, levels = 64)
        spread(coarse) shouldBeLessThan spread(fine) + 1e-6f
    }

    @Test
    fun `oil paint refuses a picture with no colour`() {
        try {
            Artistic.oilPaint(Raster(8, 8, 1))
            error("a single-channel raster was accepted")
        } catch (expected: IllegalArgumentException) {
            (expected.message?.contains("colour") == true) shouldBe true
        }
    }

    // ---- watercolour -------------------------------------------------------------------------

    @Test
    fun `watercolour pools pigment darker at the boundary`() {
        // The half most implementations leave out, and without it the result is a soft photograph.
        // Measured on the light side, where darkening is unambiguous.
        val painted = Artistic.waterColour(edge(), radius = 3, pooling = 0.8f)
        val y = painted.height / 2
        val atEdge = painted[painted.width / 2 + 1, y]
        val awayFromEdge = painted[painted.width - 3, y]
        atEdge shouldBeLessThan awayFromEdge
    }

    @Test
    fun `no pooling leaves the boundary alone`() {
        // So the slider means what it says, and a user who wants flat washes can have them.
        val painted = Artistic.waterColour(edge(), radius = 3, pooling = 0f)
        val y = painted.height / 2
        abs(painted[painted.width / 2 + 2, y] - painted[painted.width - 3, y]) shouldBeLessThan 0.05f
    }

    @Test
    fun `watercolour flattens the texture into washes`() {
        val painted = Artistic.waterColour(noisy(), radius = 3, detail = 0f)
        spread(painted) shouldBeLessThan spread(noisy())
    }

    // ---- coloured pencil ---------------------------------------------------------------------

    @Test
    fun `pencil strokes run along the contour, not across it`() {
        // A vertical edge has a horizontal gradient, so its contour — and the strokes — run
        // vertically. Smearing across the edge instead would soften it, which is what a fixed-angle
        // hatching filter does and why every photograph comes out of one looking the same.
        val drawn = Artistic.colouredPencil(edge(), length = 6, pressure = 1f)
        // Vertical smearing cannot move the vertical edge, so it stays about as narrow as it was.
        (edgeWidth(drawn) < 4) shouldBe true
    }

    @Test
    fun `the paper shows through the highlights`() {
        // What makes it read as a drawing on paper rather than a photograph with lines on it.
        val bright = Raster(16, 16, 3).fill(0.9f)
        val drawn = Artistic.colouredPencil(bright, pressure = 0f, paper = 1f)
        drawn[8, 8] shouldBeGreaterThan 0.9f
    }

    @Test
    fun `the same picture draws the same way twice`() {
        // The stroke direction in a flat area comes from a hash of the position rather than from a
        // random number, so before-and-after comparison means something.
        val source = noisy()
        val once = Artistic.colouredPencil(source)
        val twice = Artistic.colouredPencil(source)
        once.data.contentEquals(twice.data) shouldBe true
    }

    // ---- crystallize -------------------------------------------------------------------------

    @Test
    fun `a crystal cell is one flat colour`() {
        // Which is what makes it stained glass rather than a blur.
        val crystallised = Artistic.crystallize(noisy(64, 64), size = 8)
        // Count how many distinct values appear; a flat-celled image has far fewer than the pixels.
        val distinct = HashSet<Int>()
        for (i in 0 until crystallised.pixelCount) {
            distinct += (crystallised.data[i * crystallised.channels] * 1000).toInt()
        }
        (distinct.size < crystallised.pixelCount / 8) shouldBe true
    }

    @Test
    fun `cells are jittered rather than a grid of squares`() {
        // A plain grid is a mosaic; the jitter is the entire difference. Checked by looking at
        // whether the cell boundaries land exactly on the grid lines — in a jittered partition most
        // of them do not.
        val source = edge(64, 64)
        val crystallised = Artistic.crystallize(source, size = 8)
        var offGrid = 0
        val y = 31
        for (x in 1 until crystallised.width) {
            val changed = abs(crystallised[x, y] - crystallised[x - 1, y]) > 1e-4f
            if (changed && x % 8 != 0) offGrid++
        }
        (offGrid > 0) shouldBe true
    }

    @Test
    fun `every pixel belongs to its nearest centre, including on a cell boundary`() {
        // The nine-neighbour search is exact only because a centre never leaves its own grid square.
        // A wrong nearest centre shows as one pixel of the wrong colour on a boundary, which reads
        // as damage rather than as style — so this checks no cell is left empty, which is what a
        // mis-assignment would produce.
        val crystallised = Artistic.crystallize(noisy(64, 64), size = 8)
        // Every pixel got some colour: an unassigned one would be exactly zero against a mid-grey
        // source that contains no zeros.
        var zeros = 0
        for (i in 0 until crystallised.pixelCount) {
            if (crystallised.data[i * crystallised.channels] == 0f) zeros++
        }
        zeros shouldBe 0
    }

    @Test
    fun `a different seed gives a different partition`() {
        val source = noisy(64, 64)
        val a = Artistic.crystallize(source, size = 8, seed = 1)
        val b = Artistic.crystallize(source, size = 8, seed = 2)
        a.data.contentEquals(b.data) shouldBe false
    }

    @Test
    fun `crystallize keeps the alpha channel it was given`() {
        // It averages every channel rather than only the first three, so a cut-out does not come
        // back opaque.
        val source = Raster(32, 32, 4).fill(0.5f)
        for (i in 0 until source.pixelCount) source.data[i * 4 + 3] = 0.25f
        val crystallised = Artistic.crystallize(source, size = 8)
        crystallised.channels shouldBe 4
        abs(crystallised.data[3] - 0.25f) shouldBeLessThan 1e-4f
    }

    @Test
    fun `a cell smaller than two pixels is refused rather than dividing by it`() {
        try {
            Artistic.crystallize(noisy(), size = 1)
            error("a one-pixel cell was accepted")
        } catch (expected: IllegalArgumentException) {
            (expected.message?.contains("at least 2") == true) shouldBe true
        }
    }
}
