package ir.pixellab.core.paint

import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Vec2
import org.junit.jupiter.api.Test

/**
 * Quick Selection, against the properties that separate it from the magic wand.
 *
 * The wand asks "which pixels match *this one*"; this asks "the user dragged across a region, what is
 * that region". Every test below is about that difference — a stroke across a striped or shaded area
 * has to take the whole of it, and still stop at the edge.
 */
class QuickSelectTest {

    private val width = 64
    private val height = 64

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** Left half one colour, right half another, with a hard boundary down the middle. */
    private fun twoHalves(left: Int, right: Int) = IntArray(width * height) { i ->
        if (i % width < width / 2) left else right
    }

    private fun coverage(selection: PixelSelection, x: Int, y: Int) = selection[x, y]

    private fun strokeAcross(y: Int, fromX: Int, toX: Int) =
        (fromX..toX step 2).map { Vec2(it.toFloat(), y.toFloat()) }

    @Test
    fun `a stroke selects the region it was drawn on`() {
        val pixels = twoHalves(rgb(200, 60, 60), rgb(60, 60, 200))
        val selection = QuickSelect.select(pixels, width, height, strokeAcross(32, 8, 24), radius = 3f)

        // The whole left half comes with it, including places the stroke never touched.
        (coverage(selection, 4, 4) > 200) shouldBe true
        (coverage(selection, 28, 60) > 200) shouldBe true
    }

    @Test
    fun `it stops at a hard edge`() {
        // The property the wand cannot give: growing outward is cheap inside a region and expensive
        // across a boundary, so the selection snaps to the subject rather than to a colour.
        val pixels = twoHalves(rgb(200, 60, 60), rgb(60, 60, 200))
        val selection = QuickSelect.select(pixels, width, height, strokeAcross(32, 8, 24), radius = 3f)

        (coverage(selection, 40, 32) < 40) shouldBe true
        (coverage(selection, 60, 60) < 40) shouldBe true
    }

    @Test
    fun `a stroke across stripes takes both, which a single sample cannot`() {
        // The reason the colour model clusters instead of averaging. A drag across a striped shirt
        // collects two very different colours whose mean appears nowhere in the picture — a
        // mean-based model would select nothing, or with the tolerance raised to compensate, all of
        // it.
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            when {
                x >= width / 2 -> rgb(40, 160, 40)
                (x / 4) % 2 == 0 -> rgb(230, 230, 230)
                else -> rgb(30, 30, 30)
            }
        }
        val selection = QuickSelect.select(pixels, width, height, strokeAcross(32, 4, 28), radius = 3f)

        // Both stripe colours are in.
        (coverage(selection, 2, 10) > 150) shouldBe true
        (coverage(selection, 6, 10) > 150) shouldBe true
        // The green is not.
        (coverage(selection, 50, 32) < 60) shouldBe true
    }

    @Test
    fun `the same colour change is crossed when gradual and blocked when sudden`() {
        // What the edge term buys, stated as the comparison it actually is. Both pictures end at the
        // same colour and start at the same colour; only the *sharpness* of the transition differs,
        // so any difference in how far the selection reaches is the edge term and nothing else.
        fun reach(sudden: Boolean): Int {
            val pixels = IntArray(width * height) { i ->
                val x = i % width
                val t = if (sudden) (if (x < 20) 0f else 1f) else (x / (width - 1f))
                val v = (110 - 70 * t).toInt()
                rgb(v, v, v)
            }
            val selection = QuickSelect.select(pixels, width, height, strokeAcross(32, 2, 10), radius = 2f)
            return (0 until width).count { selection[it, 32] > 128 }
        }
        (reach(sudden = false) > reach(sudden = true)) shouldBe true
    }

    @Test
    fun `the boundary is soft rather than binary`() {
        // Against a *blurred* edge, which is what a photograph has. A synthetic one-pixel step has
        // no intermediate colours for a soft edge to live in, so testing there would only prove that
        // a cliff is a cliff.
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            val t = ((x - 24f) / 16f).coerceIn(0f, 1f)
            val v = (200 - 140 * t).toInt()
            rgb(v, (60 + 30 * t).toInt(), (60 + 30 * t).toInt())
        }
        val selection = QuickSelect.select(pixels, width, height, strokeAcross(32, 4, 18), radius = 3f)
        val partial = (0 until width).count { x ->
            val c = coverage(selection, x, 32)
            c in 20..235
        }
        (partial >= 1) shouldBe true
    }

    @Test
    fun `a second stroke adds to the first rather than replacing it`() {
        // What makes it usable: a selection is built up in several drags, and each one has to keep
        // what the last one found.
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            when {
                x < 20 -> rgb(220, 40, 40)
                x < 40 -> rgb(40, 220, 40)
                else -> rgb(40, 40, 220)
            }
        }
        val first = QuickSelect.select(pixels, width, height, strokeAcross(32, 4, 14), radius = 3f)
        val both = QuickSelect.select(
            pixels, width, height, strokeAcross(32, 24, 34), radius = 3f, existing = first,
        )

        (coverage(both, 8, 32) > 150) shouldBe true
        (coverage(both, 30, 32) > 150) shouldBe true
        (coverage(both, 55, 32) < 60) shouldBe true
    }

    @Test
    fun `the same stroke twice gives the same selection`() {
        // The colour model is k-means, and an unseeded k-means would answer differently each run —
        // which would mean undo followed by redo produced two different pictures.
        val pixels = twoHalves(rgb(180, 90, 40), rgb(40, 90, 180))
        val stroke = strokeAcross(32, 6, 26)
        val a = QuickSelect.select(pixels, width, height, stroke, radius = 3f)
        val b = QuickSelect.select(pixels, width, height, stroke, radius = 3f)
        a.coverage.contentEquals(b.coverage) shouldBe true
    }

    @Test
    fun `an empty stroke selects nothing rather than everything`() {
        val pixels = twoHalves(rgb(200, 60, 60), rgb(60, 60, 200))
        QuickSelect.select(pixels, width, height, emptyList(), radius = 3f).isEmpty shouldBe true
    }

    @Test
    fun `a stroke off the canvas is handled rather than throwing`() {
        val pixels = twoHalves(rgb(200, 60, 60), rgb(60, 60, 200))
        val outside = listOf(Vec2(-50f, -50f), Vec2(-40f, -40f))
        QuickSelect.select(pixels, width, height, outside, radius = 2f).isEmpty shouldBe true
    }

    @Test
    fun `a larger tolerance reaches further`() {
        val pixels = twoHalves(rgb(200, 60, 60), rgb(150, 80, 80))
        val stroke = strokeAcross(32, 4, 20)
        fun area(tolerance: Float) =
            QuickSelect.select(pixels, width, height, stroke, radius = 3f, tolerance = tolerance)
                .selectedArea()
        (area(120f) > area(20f)) shouldBe true
    }
}
