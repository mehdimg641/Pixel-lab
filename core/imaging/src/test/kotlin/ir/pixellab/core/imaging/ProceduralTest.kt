package ir.pixellab.core.imaging

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Generated tips and patterns.
 *
 * The property that matters for a pattern is seamlessness, and it is the one that is easy to get
 * subtly wrong — a tile can look right in isolation and show a grid of hairlines the moment it is
 * repeated. Every pattern here is measured at the seam directly, by comparing the first column
 * against what the last column's neighbour would be.
 */
class ProceduralTest {

    private fun seam(r: Raster): Float {
        // Column 0 against column width-1: in a tiled fill those two are adjacent, so their
        // difference is exactly the visible seam.
        var worst = 0f
        for (y in 0 until r.height) {
            worst = maxOf(worst, abs(r[0, y, 0] - r[r.width - 1, y, 0]))
        }
        for (x in 0 until r.width) {
            worst = maxOf(worst, abs(r[x, 0, 0] - r[x, r.height - 1, 0]))
        }
        return worst
    }

    /** The step one pixel inside the tile, as the yardstick the seam is judged against. */
    private fun interiorStep(r: Raster): Float {
        var worst = 0f
        for (y in 0 until r.height) {
            for (x in 1 until r.width) worst = maxOf(worst, abs(r[x, y, 0] - r[x - 1, y, 0]))
        }
        return worst
    }

    @Test
    fun `every pattern tiles without a seam`() {
        for (kind in Procedural.Pattern.entries) {
            val tile = Procedural.pattern(kind, size = 128, repeats = 4)
            // Judged against the pattern's own internal contrast rather than against an absolute:
            // a checkerboard steps from 0 to 1 inside the tile, so demanding the seam be smaller
            // than any fixed number would either pass everything or fail the checks.
            val allowed = maxOf(interiorStep(tile), MIN_ALLOWED)
            (seam(tile) <= allowed) shouldBe true
        }
    }

    @Test
    fun `a pattern is the same every time it is built`() {
        // A pattern that changed when the document was reopened would be worse than no pattern.
        val a = Procedural.pattern(Procedural.Pattern.NOISE, seed = 4)
        val b = Procedural.pattern(Procedural.Pattern.NOISE, seed = 4)
        a.data.contentEquals(b.data) shouldBe true
    }

    @Test
    fun `a different seed gives a different pattern`() {
        val a = Procedural.pattern(Procedural.Pattern.NOISE, seed = 1)
        val b = Procedural.pattern(Procedural.Pattern.NOISE, seed = 2)
        a.data.contentEquals(b.data) shouldBe false
    }

    @Test
    fun `more repeats means more periods, not a bigger tile`() {
        val few = Procedural.pattern(Procedural.Pattern.STRIPES, size = 128, repeats = 2)
        val many = Procedural.pattern(Procedural.Pattern.STRIPES, size = 128, repeats = 8)
        few.width shouldBe many.width

        fun crossings(r: Raster): Int {
            var count = 0
            for (x in 1 until r.width) {
                if ((r[x, 8, 0] >= HALF) != (r[x - 1, 8, 0] >= HALF)) count++
            }
            return count
        }
        (crossings(many) > crossings(few)) shouldBe true
    }

    @Test
    fun `every pattern uses its whole range`() {
        // A pattern that never reaches 0 or never reaches 1 is a flat wash with a texture printed
        // on it, and it will look like a mistake wherever it is used as a mask.
        for (kind in Procedural.Pattern.entries) {
            val tile = Procedural.pattern(kind, size = 128, repeats = 4, seed = 3)
            (tile.data.min() < LOW) shouldBe true
            (tile.data.max() > HIGH) shouldBe true
        }
    }

    // ---- tips ----------------------------------------------------------------------------------

    @Test
    fun `every tip is opaque somewhere and empty at the corners`() {
        for (kind in Procedural.Tip.entries) {
            val tip = Procedural.tip(kind, size = 96, seed = 2)
            (tip.data.max() > HIGH) shouldBe true
            // The corners are outside the unit disc for every tip, so a tip that painted there
            // would be a square stamp wearing a round mask's name — and would leave hard corners
            // on every soft stroke.
            if (kind != Procedural.Tip.SQUARE) {
                tip[0, 0, 0] shouldBe 0f
                tip[95, 95, 0] shouldBe 0f
            }
        }
    }

    @Test
    fun `a soft round tip fades and a hard one does not`() {
        val soft = Procedural.tip(Procedural.Tip.ROUND, size = 64, hardness = 0f)
        val hard = Procedural.tip(Procedural.Tip.ROUND, size = 64, hardness = 1f)

        // Half way out: the soft tip is well down, the hard one is still solid. This is the
        // difference the hardness slider is supposed to make, and it is measured rather than
        // assumed because an off-by-one in the falloff produces a tip that looks plausible.
        (soft[48, 32, 0] < HALF) shouldBe true
        (hard[48, 32, 0] > HIGH) shouldBe true
    }

    @Test
    fun `hardness is a fraction of the radius, so a tip resized stays as soft`() {
        // The behaviour every painter relies on without thinking about it, stated as the thing that
        // can actually be measured: the half-coverage point sits at the same *fraction* of the
        // radius whatever the tip's size. Comparing coverage at matched pixel indices instead would
        // measure the half-pixel the two grids disagree by, not the falloff.
        fun halfPoint(size: Int): Float {
            val tip = Procedural.tip(Procedural.Tip.ROUND, size = size, hardness = 0.3f)
            val centre = size / 2
            val crossing = (centre until size).firstOrNull { tip[it, centre, 0] < HALF } ?: size
            return (crossing - centre).toFloat() / (size / 2f)
        }

        val small = halfPoint(64)
        val large = halfPoint(256)
        // Within a pixel of the smaller tip, which is the finest either grid can express.
        (abs(small - large) < 2f / 64f) shouldBe true
    }

    @Test
    fun `an irregular tip is not a disc`() {
        // Chalk, spatter and bristle earn their names by *not* covering uniformly. A generator that
        // silently fell back to a round tip would pass every other test in this file.
        for (kind in listOf(Procedural.Tip.CHALK, Procedural.Tip.SPATTER, Procedural.Tip.BRISTLE)) {
            val tip = Procedural.tip(kind, size = 96, seed = 5)
            val round = Procedural.tip(Procedural.Tip.ROUND, size = 96)
            var difference = 0f
            for (i in tip.data.indices) difference += abs(tip.data[i] - round.data[i])
            (difference / tip.data.size > IRREGULARITY) shouldBe true
        }
    }

    @Test
    fun `a tip is the same every time it is built`() {
        val a = Procedural.tip(Procedural.Tip.SPATTER, seed = 9)
        val b = Procedural.tip(Procedural.Tip.SPATTER, seed = 9)
        a.data.contentEquals(b.data) shouldBe true
    }

    @Test
    fun `the hash stays inside the unit interval`() {
        // It feeds a coverage value directly; anything outside 0..1 becomes a clipped patch that
        // looks like a deliberate mark.
        for (i in -1000..1000) {
            val h = Procedural.hash(i, i * 7)
            (h in 0f..1f) shouldBe true
        }
    }

    private companion object {
        const val HALF = 0.5f
        const val HIGH = 0.9f
        const val LOW = 0.1f
        const val MIN_ALLOWED = 0.02f
        const val IRREGULARITY = 0.05f
    }
}
