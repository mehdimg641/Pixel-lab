package ir.pixellab.core.paint

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.Vec2
import org.junit.jupiter.api.Test

/**
 * Pixel selection.
 *
 * The thing every one of the reference apps has and this one did not, and the prerequisite for
 * masking by hand, for retouching and for cutting a subject out. Coverage rather than a bitmask
 * throughout, because a hard-edged selection is exactly what makes a cut-out look pasted on.
 */
class SelectionTest {

    private val width = 64
    private val height = 64

    // ---- shapes ------------------------------------------------------------------------------

    @Test
    fun `a rectangle selects its own area`() {
        val selection = Marquee.rectangle(width, height, Rect(10f, 10f, 30f, 20f))
        selection.selectedArea() shouldBe 200.0.plusOrMinus(20.0)
        // The right edge is exclusive: the rectangle reaches x=30, and pixel 30's own samples all sit
        // past it, so the last covered column is 29 and the box ends at 30.
        selection.bounds shouldBe Rect(10f, 10f, 30f, 20f)
    }

    @Test
    fun `an ellipse selects about pi over four of its box`() {
        val selection = Marquee.ellipse(width, height, Rect(0f, 0f, 40f, 40f))
        // A circle of diameter 40 is ~1257 square units; a rectangle of the same box is 1600. Any
        // implementation that returns the box has selected the corners too.
        selection.selectedArea() shouldBe 1257.0.plusOrMinus(80.0)
    }

    @Test
    fun `an edge is antialiased rather than staircased`() {
        val selection = Marquee.ellipse(width, height, Rect(0f, 0f, 40f, 40f))
        var partial = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = selection[x, y]
                if (value in 1..254) partial++
            }
        }
        // A staircase edge is the single most recognisable sign of a bad composite, and a selection
        // with no partial coverage cannot produce anything else.
        (partial > 40) shouldBe true
    }

    @Test
    fun `a lasso closes itself`() {
        val triangle = listOf(Vec2(10f, 10f), Vec2(40f, 10f), Vec2(25f, 40f))
        val selection = Marquee.polygon(width, height, triangle)
        // A lasso the user did not quite finish is the normal case, not an error.
        selection.selectedArea() shouldBe 450.0.plusOrMinus(40.0)
    }

    @Test
    fun `a lasso of fewer than three points selects nothing rather than throwing`() {
        Marquee.polygon(width, height, listOf(Vec2(1f, 1f), Vec2(5f, 5f))).isEmpty shouldBe true
    }

    // ---- algebra -----------------------------------------------------------------------------

    @Test
    fun `adding two regions unions them`() {
        val left = Marquee.rectangle(width, height, Rect(0f, 0f, 20f, 20f))
        val right = Marquee.rectangle(width, height, Rect(30f, 0f, 50f, 20f))
        val both = left.combine(right, SelectionMode.ADD)
        // Building a selection is almost always several passes; without algebra a user has to get
        // it right in one gesture.
        both.selectedArea() shouldBe (left.selectedArea() + right.selectedArea()).plusOrMinus(1.0)
    }

    @Test
    fun `subtracting takes a bite out`() {
        val whole = Marquee.rectangle(width, height, Rect(0f, 0f, 40f, 40f))
        val bite = Marquee.rectangle(width, height, Rect(0f, 0f, 20f, 40f))
        val left = whole.combine(bite, SelectionMode.SUBTRACT)
        (left.selectedArea() < whole.selectedArea() / 2 + 30) shouldBe true
        left[5, 5] shouldBe 0
        (left[30, 20] > 200) shouldBe true
    }

    @Test
    fun `intersecting keeps only the overlap`() {
        val a = Marquee.rectangle(width, height, Rect(0f, 0f, 30f, 30f))
        val b = Marquee.rectangle(width, height, Rect(20f, 20f, 50f, 50f))
        val both = a.combine(b, SelectionMode.INTERSECT)
        both.bounds shouldBe Rect(20f, 20f, 30f, 30f)
    }

    @Test
    fun `subtracting respects partial coverage rather than treating it as solid`() {
        val soft = Marquee.rectangle(width, height, Rect(10f, 10f, 30f, 30f)).feathered(4f)
        val hard = Marquee.rectangle(width, height, Rect(0f, 0f, 20f, 64f))
        val left = soft.combine(hard, SelectionMode.SUBTRACT)
        // Treating anything non-zero as fully selected would give the result a hard edge where the
        // feather used to be, which is how a soft mask silently becomes a hard one.
        var partial = 0
        for (y in 0 until height) for (x in 0 until width) if (left[x, y] in 1..254) partial++
        (partial > 10) shouldBe true
    }

    @Test
    fun `inverting swaps what is chosen`() {
        val inside = Marquee.rectangle(width, height, Rect(10f, 10f, 30f, 30f))
        val outside = inside.inverted()
        outside[20, 20] shouldBe 0
        outside[50, 50] shouldBe 255
        (inside.selectedArea() + outside.selectedArea()) shouldBe (width * height).toDouble().plusOrMinus(1.0)
    }

    @Test
    fun `select all and select nothing are opposites`() {
        PixelSelection.everything(width, height).selectedArea() shouldBe (width * height).toDouble()
        PixelSelection.nothing(width, height).isEmpty shouldBe true
    }

    // ---- growing and feathering --------------------------------------------------------------

    @Test
    fun `growing a selection expands it evenly rather than into a diamond`() {
        val circle = Marquee.ellipse(width, height, Rect(20f, 20f, 40f, 40f))
        val grown = circle.grown(6)
        val box = grown.bounds!!

        // Repeated 3x3 dilation is the obvious implementation and it produces an octagon, which is
        // visible the moment a round selection is expanded by more than a few pixels.
        (box.width - box.height) shouldBe 0f
        (box.width > circle.bounds!!.width + 10) shouldBe true
    }

    @Test
    fun `shrinking pulls the edge in`() {
        val square = Marquee.rectangle(width, height, Rect(10f, 10f, 40f, 40f))
        val shrunk = square.grown(-5)
        (shrunk.selectedArea() < square.selectedArea()) shouldBe true
        shrunk[11, 11] shouldBe 0
        (shrunk[25, 25] > 200) shouldBe true
    }

    @Test
    fun `feathering softens the edge and leaves the middle alone`() {
        val square = Marquee.rectangle(width, height, Rect(16f, 16f, 48f, 48f))
        val soft = square.feathered(5f)
        (soft[32, 32] > 240) shouldBe true
        (soft[16, 32] in 1..254) shouldBe true
    }

    @Test
    fun `feathering by nothing changes nothing`() {
        val square = Marquee.rectangle(width, height, Rect(10f, 10f, 30f, 30f))
        square.feathered(0f).selectedArea() shouldBe square.selectedArea()
    }

    @Test
    fun `feathering conserves roughly as much as it spreads`() {
        val square = Marquee.rectangle(width, height, Rect(20f, 20f, 44f, 44f))
        val soft = square.feathered(4f)
        // A blur moves coverage, it does not create or destroy it. A feather that visibly shrinks a
        // selection is the classic sign of clamping at the wrong point in the pipeline.
        soft.selectedArea() shouldBe square.selectedArea().plusOrMinus(square.selectedArea() * 0.1)
    }

    // ---- magic wand --------------------------------------------------------------------------

    private fun twoTone(): IntArray {
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = if (x < 32) BLUE else RED
            }
        }
        return pixels
    }

    @Test
    fun `the wand takes the region under the finger`() {
        val selection = MagicWand.select(twoTone(), width, height, Vec2(10f, 10f), tolerance = 10f)
        (selection[10, 10] > 200) shouldBe true
        selection[50, 10] shouldBe 0
        selection.selectedArea() shouldBe (32 * 64).toDouble().plusOrMinus(1.0)
    }

    @Test
    fun `tolerance decides how much comes with it`() {
        val pixels = IntArray(width * height) { i ->
            // A gradient across the image: at zero tolerance a wand takes one column, and at a wide
            // one it takes most of the picture.
            val x = i % width
            0xFF shl 24 or (x * 4 shl 16) or (x * 4 shl 8) or (x * 4)
        }
        val tight = MagicWand.select(pixels, width, height, Vec2(0f, 0f), tolerance = 4f)
        val loose = MagicWand.select(pixels, width, height, Vec2(0f, 0f), tolerance = 100f)
        (loose.selectedArea() > tight.selectedArea() * 4) shouldBe true
    }

    @Test
    fun `a non-contiguous wand reaches matching pixels it is not connected to`() {
        val pixels = IntArray(width * height) { RED }
        // Two separate blue squares.
        for (y in 4 until 12) for (x in 4 until 12) pixels[y * width + x] = BLUE
        for (y in 40 until 48) for (x in 40 until 48) pixels[y * width + x] = BLUE

        val connected = MagicWand.select(pixels, width, height, Vec2(6f, 6f), contiguous = true)
        val everywhere = MagicWand.select(pixels, width, height, Vec2(6f, 6f), contiguous = false)

        connected[44, 44] shouldBe 0
        (everywhere[44, 44] > 200) shouldBe true
    }

    @Test
    fun `the wand follows a shape rather than filling its bounding box`() {
        val pixels = IntArray(width * height) { RED }
        // A blue ring: filling from outside must not leak into the middle.
        for (y in 10 until 50) for (x in 10 until 50) {
            val onEdge = x < 14 || x >= 46 || y < 14 || y >= 46
            if (onEdge) pixels[y * width + x] = BLUE
        }
        val selection = MagicWand.select(pixels, width, height, Vec2(11f, 11f), tolerance = 10f)
        (selection[11, 11] > 200) shouldBe true
        selection[30, 30] shouldBe 0
    }

    @Test
    fun `a tap outside the image selects nothing rather than throwing`() {
        MagicWand.select(twoTone(), width, height, Vec2(-5f, 500f)).isEmpty shouldBe true
    }

    @Test
    fun `transparency is part of the match`() {
        val pixels = IntArray(width * height) { i ->
            // Same colour, different alpha, on the two halves.
            if (i % width < 32) (0 shl 24) or 0xFF0000 else (0xFF shl 24) or 0xFF0000
        }
        val selection = MagicWand.select(pixels, width, height, Vec2(5f, 5f), tolerance = 10f)
        // Without alpha in the distance, a wand on an empty area selects every transparent pixel
        // regardless of what is underneath — and every cut-out then comes back with a halo.
        selection[50, 5] shouldBe 0
    }

    @Test
    fun `the wand edge is antialiased`() {
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            val v = (x * 4).coerceAtMost(255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val selection = MagicWand.select(pixels, width, height, Vec2(0f, 0f), tolerance = 60f)
        var partial = 0
        for (y in 0 until height) for (x in 0 until width) if (selection[x, y] in 1..254) partial++
        (partial > 0) shouldBe true
    }

    @Test
    fun `a selection can be handed to another of the same size only`() {
        val a = Marquee.rectangle(width, height, Rect(0f, 0f, 10f, 10f))
        val b = Marquee.rectangle(32, 32, Rect(0f, 0f, 10f, 10f))
        try {
            a.combine(b, SelectionMode.ADD)
            throw AssertionError("mismatched sizes should be refused")
        } catch (e: IllegalArgumentException) {
            (e.message?.contains("size") == true) shouldBe true
        }
    }

    private companion object {
        const val BLUE = 0xFF0000FF.toInt()
        const val RED = 0xFFFF0000.toInt()
    }
}
