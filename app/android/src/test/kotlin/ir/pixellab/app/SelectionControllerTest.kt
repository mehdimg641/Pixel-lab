package ir.pixellab.app

import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import ir.pixellab.core.editor.AspectRatio
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.Vec2
import ir.pixellab.core.paint.Marquee
import ir.pixellab.core.paint.SelectionMode
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * Adopting a selection computed somewhere else.
 *
 * The path subject selection takes into the app, and the reason it is not a plain assignment: a
 * user runs "select subject" and then corrects it — take the person, subtract the arm they grabbed
 * the railing with — and a result that always replaced would throw the correction away every time
 * they re-ran it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SelectionControllerTest {

    private val size = 40

    private fun box(left: Float, right: Float) =
        Marquee.rectangle(size, size, Rect(left, 0f, right, size.toFloat()))

    @Test
    fun `adopting with replace takes the new region whole`() {
        val controller = SelectionController()
        controller.use(box(0f, 20f))
        controller.use(box(20f, 40f))

        controller.selection!![30, 10] shouldBe 255
        controller.selection!![5, 10] shouldBe 0
    }

    @Test
    fun `adopting honours the combining mode`() {
        val controller = SelectionController()
        controller.use(box(0f, 30f))
        controller.mode = SelectionMode.SUBTRACT
        controller.use(box(20f, 40f))

        controller.selection!![10, 10] shouldBe 255
        controller.selection!![25, 10] shouldBe 0
    }

    @Test
    fun `an empty region clears rather than leaving an unusable selection`() {
        val controller = SelectionController()
        controller.use(box(0f, 20f))
        controller.use(Marquee.rectangle(size, size, Rect(0f, 0f, 0f, 0f)))
        // Null rather than an empty one: every tool reads "no selection" as "the whole layer", and
        // an empty-but-present selection would silently make each of them do nothing at all.
        controller.selection shouldBe null
        controller.outline.isEmpty() shouldBe true
    }

    @Test
    fun `the outline follows the selection`() {
        val controller = SelectionController()
        controller.use(box(10f, 30f))
        controller.outline.isNotEmpty() shouldBe true
        controller.clear()
        controller.outline.isEmpty() shouldBe true
    }

    /** The bounding box of whatever is selected, in canvas pixels. */
    private fun bounds(controller: SelectionController): Rect =
        controller.selection!!.bounds!!

    @Test
    fun `a fixed ratio reshapes the dragged box before it becomes a selection`() {
        val controller = SelectionController()
        controller.ratio = AspectRatio.SQUARE
        controller.begin(Vec2(0f, 0f), size, size)
        controller.extend(Vec2(30f, 10f))
        controller.end(Vec2(30f, 10f), size, size, null)

        val box = bounds(controller)
        // 30x10 asked to be square becomes 10x10 — shrunk, so the frame never jumps out from under
        // the finger that is dragging it.
        abs(box.width - box.height) shouldBeLessThan 2f
        (box.width <= 12f) shouldBe true
    }

    @Test
    fun `without a ratio the drag is left exactly as drawn`() {
        val controller = SelectionController()
        controller.begin(Vec2(0f, 0f), size, size)
        controller.end(Vec2(30f, 10f), size, size, null)

        val box = bounds(controller)
        abs(box.width - 30f) shouldBeLessThan 2f
        abs(box.height - 10f) shouldBeLessThan 2f
    }

    @Test
    fun `the frame command lays the largest rectangle of the ratio over the canvas`() {
        val controller = SelectionController()
        controller.ratio = AspectRatio(1f, 2f)
        controller.frame(size, size)

        val box = bounds(controller)
        // Half as wide as it is tall, and as tall as the canvas allows.
        abs(box.height - size.toFloat()) shouldBeLessThan 2f
        abs(box.width - size / 2f) shouldBeLessThan 2f
    }

    @Test
    fun `the frame replaces rather than combining, so a crop box stays a rectangle`() {
        // Through `replace`, not `use`. Going through the combining mode would let a subtract-mode
        // frame produce an L-shaped "crop box", which `cropCanvas` would then reduce to its bounding
        // box — silently cropping to something the user never saw.
        val controller = SelectionController()
        controller.use(box(0f, 10f))
        controller.mode = SelectionMode.SUBTRACT
        controller.ratio = AspectRatio.SQUARE
        controller.frame(size, size)

        controller.selection!![20, 20] shouldBe 255
    }

    @Test
    fun `a drafted rectangle is drawn as the box rather than as its diagonal`() {
        // What the user sees mid-drag. Two points meant a marquee showed a line across where the
        // selection was going to be, and with a ratio on it the corner under the finger is not even
        // on that line.
        val controller = SelectionController()
        controller.begin(Vec2(5f, 5f), size, size)
        controller.extend(Vec2(25f, 15f))

        controller.draft.size shouldBe 5
        controller.draft.first() shouldBe controller.draft.last()
        controller.draft.map { it.x }.toSet() shouldBe setOf(5f, 25f)
        controller.draft.map { it.y }.toSet() shouldBe setOf(5f, 15f)
    }

    @Test
    fun `a drafted ellipse is a curve, not two points`() {
        val controller = SelectionController()
        controller.shape = SelectionShape.ELLIPSE
        controller.begin(Vec2(0f, 0f), size, size)
        controller.extend(Vec2(20f, 20f))

        (controller.draft.size > 8) shouldBe true
        // Closed, so the dashed path has no gap in it.
        abs(controller.draft.first().x - controller.draft.last().x) shouldBeLessThan 0.01f
    }
}
