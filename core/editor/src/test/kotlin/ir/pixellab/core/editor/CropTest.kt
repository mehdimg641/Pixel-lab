package ir.pixellab.core.editor

import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Rect
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * The crop ratio.
 *
 * `cropCanvas` has taken a rectangle since wave 1 and the only rectangle anything could hand it was
 * a selection's bounding box, so a square post or a 9:16 story had to be drawn by eye. These check
 * the two things that make a ratio worth having, and the one that makes it usable under a finger.
 */
class CropTest {

    private val canvas = Rect(0f, 0f, 1000f, 500f)

    private fun Rect.ratio() = width / height

    @Test
    fun `fit gives the largest rectangle of the proportion that the canvas holds`() {
        val square = AspectRatio.SQUARE.fit(canvas)
        abs(square.ratio() - 1f) shouldBeLessThan 0.001f
        // The canvas is wider than it is tall, so height is what runs out.
        square.height shouldBe canvas.height
        square.width shouldBe canvas.height
    }

    @Test
    fun `fit centres, so the frame is equally wrong in both directions`() {
        val frame = AspectRatio(9f, 16f).fit(canvas)
        abs((frame.left + frame.right) / 2f - 500f) shouldBeLessThan 0.001f
        abs((frame.top + frame.bottom) / 2f - 250f) shouldBeLessThan 0.001f
    }

    @Test
    fun `fit works the other way round when the canvas is the tall one`() {
        val tall = Rect(0f, 0f, 500f, 1000f)
        val wide = AspectRatio(16f, 9f).fit(tall)
        wide.width shouldBe tall.width
        abs(wide.ratio() - 16f / 9f) shouldBeLessThan 0.001f
    }

    @Test
    fun `constrain reshapes about the centre so the frame does not walk across the canvas`() {
        val dragged = Rect(100f, 100f, 400f, 200f)
        val fixed = AspectRatio.SQUARE.constrain(dragged)
        abs((fixed.left + fixed.right) / 2f - 250f) shouldBeLessThan 0.001f
        abs((fixed.top + fixed.bottom) / 2f - 150f) shouldBeLessThan 0.001f
        abs(fixed.ratio() - 1f) shouldBeLessThan 0.001f
    }

    @Test
    fun `constrain shrinks rather than grows`() {
        // The property that keeps a drag from fighting the finger. A 300x100 box asked to be square
        // becomes 100x100, not 300x300 — the second would jump out from under the pointer, and on
        // the last few pixels of a drag towards a corner it would keep pushing back.
        val fixed = AspectRatio.SQUARE.constrain(Rect(0f, 0f, 300f, 100f))
        fixed.width shouldBe 100f
        fixed.height shouldBe 100f
    }

    @Test
    fun `a frame pushed off an edge slides back instead of changing shape`() {
        // Clamping by translating, not by reshaping. Reshaping at the edge would mean a frame
        // dragged into a corner silently stopped being the ratio the user chose.
        val fixed = AspectRatio.SQUARE.constrain(Rect(-200f, 200f, 100f, 500f), canvas)
        fixed.left shouldBe 0f
        abs(fixed.ratio() - 1f) shouldBeLessThan 0.001f
        (fixed.right <= canvas.right) shouldBe true
        (fixed.bottom <= canvas.bottom) shouldBe true
    }

    @Test
    fun `a frame too big for the canvas falls back to the biggest one that fits`() {
        // Rather than returning something that hangs off two opposite edges, which no amount of
        // sliding can rescue.
        val fixed = AspectRatio.SQUARE.constrain(Rect(-500f, -500f, 1500f, 1500f), canvas)
        fixed.height shouldBe canvas.height
        abs(fixed.ratio() - 1f) shouldBeLessThan 0.001f
    }

    @Test
    fun `flipping turns the ratio on its side`() {
        AspectRatio(9f, 16f).flipped() shouldBe AspectRatio(16f, 9f)
    }

    @Test
    fun `a ratio with a zero side is refused rather than dividing by it`() {
        try {
            AspectRatio(1f, 0f)
            error("a zero-height ratio was accepted")
        } catch (expected: IllegalArgumentException) {
            (expected.message?.contains("positive") == true) shouldBe true
        }
    }

    @Test
    fun `every preset is labelled and none is repeated`() {
        // The row is pressed without being read, so a duplicate would be a button that appears to do
        // nothing — and a missing label would be a chip with no text on it.
        AspectRatio.PRESETS.map { it.second }.toSet().size shouldBe AspectRatio.PRESETS.size
        AspectRatio.PRESETS.all { it.first.isNotBlank() } shouldBe true
    }
}
