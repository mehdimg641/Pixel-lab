package ir.pixellab.app

import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Rect
import ir.pixellab.core.paint.Marquee
import ir.pixellab.core.paint.SelectionMode
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
}
