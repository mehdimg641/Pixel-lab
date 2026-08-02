package ir.pixellab.core.paint

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.hypot

private fun opaque(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

/**
 * Selecting the subject with no model behind it.
 *
 * Each test is a picture with a known right answer, because "select subject" is the one tool where a
 * plausible-looking wrong result is worse than no result: the user takes the selection, cuts, and
 * only notices the missing shoulder after they have built a cover on top of it.
 */
class SubjectSelectionTest {

    private fun canvas(
        width: Int,
        height: Int,
        background: Int,
        paint: (x: Int, y: Int) -> Int?,
    ): IntArray = IntArray(width * height) { i ->
        paint(i % width, i / width) ?: background
    }

    private fun disc(cx: Float, cy: Float, radius: Float, color: Int): (Int, Int) -> Int? =
        { x, y -> if (hypot(x - cx, y - cy) <= radius) color else null }

    private val sky = opaque(140, 180, 230)
    private val coat = opaque(200, 40, 40)

    @Test
    fun `a coloured subject on a plain background comes out whole`() {
        val pixels = canvas(120, 120, sky, disc(60f, 60f, 30f, coat))
        val selection = SubjectSelection.select(pixels, 120, 120)

        // The disc, and only the disc — within a pixel of edge, which the refinement afterwards is
        // there to resolve properly.
        selection[60, 60] shouldBe 255
        selection[10, 10] shouldBe 0
        val area = selection.selectedArea()
        val expected = Math.PI * 30 * 30
        (area > expected * 0.9 && area < expected * 1.15) shouldBe true
    }

    @Test
    fun `an enclosed background coloured region is kept`() {
        // A subject with a hole in it the colour of the sky: the inside of a hood, a gap in hair.
        val pixels = canvas(120, 120, sky) { x, y ->
            val r = hypot(x - 60f, y - 60f)
            when {
                r <= 8f -> sky
                r <= 30f -> coat
                else -> null
            }
        }
        val selection = SubjectSelection.select(pixels, 120, 120)
        // Taking it out would leave a hole straight through the person. What distinguishes it from
        // real background is that it cannot be reached from the edge of the frame.
        selection[60, 60] shouldBe 255
    }

    @Test
    fun `a gap open to the edge is not filled`() {
        // A notch cut in from the frame edge: background, not a hole, and it must stay out.
        val pixels = canvas(120, 120, sky) { x, y ->
            val inDisc = hypot(x - 60f, y - 60f) <= 40f
            val inNotch = x in 55..65 && y >= 20
            if (inDisc && !inNotch) coat else null
        }
        val selection = SubjectSelection.select(pixels, 120, 120)
        selection[60, 100] shouldBe 0
    }

    @Test
    fun `two subjects both survive`() {
        val pixels = canvas(160, 120, sky) { x, y ->
            val left = hypot(x - 45f, y - 60f) <= 22f
            val right = hypot(x - 115f, y - 60f) <= 20f
            if (left || right) coat else null
        }
        val selection = SubjectSelection.select(pixels, 160, 120)
        // Keeping only the largest region is the version that fails on the photograph people
        // actually bring: two people, and one of them silently disappears.
        selection[45, 60] shouldBe 255
        selection[115, 60] shouldBe 255
    }

    @Test
    fun `specks are dropped`() {
        val pixels = canvas(120, 120, sky) { x, y ->
            val subject = hypot(x - 60f, y - 60f) <= 28f
            val speck = x in 8..9 && y in 8..9
            if (subject || speck) coat else null
        }
        val selection = SubjectSelection.select(pixels, 120, 120)
        selection[8, 8] shouldBe 0
        selection[60, 60] shouldBe 255
    }

    @Test
    fun `an empty frame selects nothing`() {
        val pixels = canvas(80, 80, sky) { _, _ -> null }
        SubjectSelection.select(pixels, 80, 80).isEmpty shouldBe true
    }

    @Test
    fun `transparent pixels are never the subject`() {
        // A cut-out that has already been through this once: what was discarded must not come back.
        val pixels = canvas(100, 100, 0) { x, y ->
            if (hypot(x - 50f, y - 50f) <= 20f) coat else null
        }
        val selection = SubjectSelection.select(pixels, 100, 100)
        selection[50, 50] shouldBe 255
        selection[5, 5] shouldBe 0
    }

    @Test
    fun `higher sensitivity never selects less`() {
        // A subject close in colour to what is behind it, which is exactly when the slider is used.
        val nearly = opaque(150, 175, 220)
        val pixels = canvas(120, 120, sky, disc(60f, 60f, 25f, nearly))

        val timid = SubjectSelection.select(pixels, 120, 120, sensitivity = 0.2f).selectedArea()
        val eager = SubjectSelection.select(pixels, 120, 120, sensitivity = 0.9f).selectedArea()
        (eager >= timid) shouldBe true
    }

    @Test
    fun `a frame too small to have a border is refused rather than guessed at`() {
        SubjectSelection.select(IntArray(16) { sky }, 4, 4).isEmpty shouldBe true
    }

    @Test
    fun `Otsu finds the gap in a two humped histogram`() {
        // Half the values near 0.2, half near 0.8; the threshold has to land between them.
        val values = FloatArray(1000) { if (it < 500) 0.2f else 0.8f }
        val threshold = SubjectSelection.otsu(values)
        // Stated as the caller uses it — `value > threshold` — because the boundary case is the
        // whole question: the level Otsu names is the last one that belongs *below* the split.
        (0.2f > threshold) shouldBe false
        (0.8f > threshold) shouldBe true
    }

    @Test
    fun `saliency is higher on the subject than on the border`() {
        val pixels = canvas(120, 120, sky, disc(60f, 60f, 25f, coat))
        val map = SubjectSelection.saliency(pixels, 120, 120)
        (map[60 * 120 + 60] > map[5 * 120 + 5]) shouldBe true
    }
}
