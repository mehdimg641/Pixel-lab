package ir.pixellab.app

import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A sheet as a transaction — ✕ and ✓.
 *
 * Every sheet in this app writes its change the moment a control moves, and the only way back was
 * undo, so a user who moved four sliders and thought better of it had to press undo four times and
 * count. These check that cancel puts the document back, that confirm does not, and — the one that
 * matters most — that cancel refuses to eat work the sheet did not do.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SheetTransactionTest {

    private fun model() = EditorViewModel(ApplicationProvider.getApplicationContext())

    @Test
    fun `cancel puts the document back to where the sheet found it`() {
        val model = model()
        val id = model.state.document.layers.first().id
        model.noteSheetOpened()

        model.act { setLayerOpacity(id, 0.5f) }
        model.act { setLayerOpacity(id, 0.2f) }
        model.cancelSheet()

        model.state.document.layers.first().opacity shouldBe 1f
    }

    @Test
    fun `confirm keeps everything and leaves it on the undo stack`() {
        val model = model()
        val id = model.state.document.layers.first().id
        model.noteSheetOpened()

        model.act { setLayerOpacity(id, 0.5f) }
        model.confirmSheet()

        model.state.document.layers.first().opacity shouldBe 0.5f
        // Still undoable — confirming is not the same as forgetting.
        model.canUndo shouldBe true
    }

    @Test
    fun `cancelling twice does not walk back past the second sheet's baseline`() {
        // The baseline is re-taken on close. Without that, opening a sheet, confirming, then opening
        // another and cancelling would revert the first sheet's work too.
        val model = model()
        val id = model.state.document.layers.first().id

        model.noteSheetOpened()
        model.act { setLayerOpacity(id, 0.5f) }
        model.confirmSheet()

        model.noteSheetOpened()
        model.act { setLayerOpacity(id, 0.1f) }
        model.cancelSheet()

        model.state.document.layers.first().opacity shouldBe 0.5f
    }

    @Test
    fun `a sheet that changed nothing says so, so the tick is not lit for no reason`() {
        val model = model()
        model.noteSheetOpened()
        model.sheetHasChanges shouldBe false

        model.act { setLayerOpacity(model.state.document.layers.first().id, 0.5f) }
        model.sheetHasChanges shouldBe true
    }

    @Test
    fun `cancel does nothing when the sheet changed nothing`() {
        val model = model()
        val before = model.state.document
        model.noteSheetOpened()
        model.cancelSheet()

        model.state.document shouldBe before
        model.canUndo shouldBe false
    }
}
