package ir.pixellab.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import ir.pixellab.core.editor.SheetContent
import ir.pixellab.core.editor.SheetDetent
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Whether a panel still covers the picture it is editing.
 *
 * ### The change this guards
 *
 * Twenty-one panels used to be modal sheets that slid up over the artwork. They are the panel's own
 * body one level down now, with the five tabs still on screen beneath them and the canvas still
 * above them. `ExportSheet` is the only modal left in the editor, and it earns it: it has a flow,
 * it ends, and it does not need to see the canvas.
 *
 * ### Why this is not covered by the screenshots
 *
 * A frame with a sheet over the canvas and a frame with a docked panel both draw plenty of distinct
 * colours, and both contain the panel's controls. The difference is whether the canvas is *also*
 * there — which is the entire point, and which only a displayed-or-not assertion can fail on.
 *
 * ### One assertion is structural
 *
 * Any new panel written as a modal would pass every test here, because the tests can only reach the
 * panels that exist. So the source is checked for the shape itself: exactly one `AnimatedVisibility`
 * driven by `sheet.isOpen` used to lift a panel over the artwork, and there should now be none.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fa-w411dp-h891dp-xhdpi")
class DockedPanelTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun editor(): EditorViewModel {
        val model = EditorViewModel(ApplicationProvider.getApplicationContext())
            // The recovery timer schedules a delay on the main looper that never comes due, and
            // Compose's idling check waits on that queue.
            .also { it.autoSave.stop() }
        model.act { select(state.document.layers.first().id) }
        compose.setContent {
            PixelLabTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Box(Modifier.fillMaxSize()) { EditorScreen(model = model) }
                }
            }
        }
        compose.waitForIdle()
        return model
    }

    private fun open(model: EditorViewModel, content: SheetContent, detent: SheetDetent = SheetDetent.FULL) {
        model.act { openSheet(content, detent) }
        compose.waitForIdle()
    }

    @Test
    fun `a panel at its tallest still leaves the canvas on screen`() {
        // `FULL` is the tallest detent any panel asks for, so if the artwork survives this it
        // survives all of them. The zoom read-out is the proxy for "the canvas is there": it is
        // drawn on the canvas and nowhere else.
        val model = editor()
        open(model, SheetContent.Adjustments)

        compose.onNodeWithContentDescription("بزرگ‌نمایی").assertIsDisplayed()
    }

    @Test
    fun `the tabs stay reachable from inside a panel`() {
        // What makes this a place rather than a modal: leaving is one press, and going somewhere
        // else entirely is also one press. A sheet made both of those two.
        val model = editor()
        open(model, SheetContent.FontPicker)

        compose.onNodeWithContentDescription("لایه‌ها").assertIsDisplayed()
        compose.onNodeWithContentDescription("متن").performClick()
        compose.waitForIdle()
    }

    @Test
    fun `no panel is lifted over the artwork any more`() {
        // Structural, because the tests above can only reach the panels that exist today and the
        // failure this guards is a *new* one being written as a modal. The editor grew twenty-one
        // sheets exactly that way: the first was modal, and the next twenty followed its shape
        // rather than its argument.
        val source = java.io.File("src/main/kotlin/ir/pixellab/app/EditorScreen.kt")
            .takeIf { it.exists() }
            ?.readText()
            ?: error("EditorScreen.kt not found; this test runs from the app module's directory")

        val lifted = Regex("""AnimatedVisibility\([^)]*visible\s*=\s*state\.sheet\.isOpen""")
            .findAll(source)
            .count()
        assertTrue(
            "a panel is being animated over the canvas again. Panels dock into the work panel; " +
                "the only modal left in the editor is the export sheet, which has a flow and ends.",
            lifted == 0,
        )
    }

    @Test
    fun `the contextual bar steps aside for a panel`() {
        // The one thing that *should* still hide. Its job is "what do I do to this next", and
        // inside a panel the answer is the panel — so leaving it up makes it a second, quieter
        // suggestion competing with the one being acted on.
        // Matched on «به جلو», which `BarAction` draws as a word under an icon and which nothing
        // else in the editor's chrome carries. «حذف» and «چیدمان» both appear in the layers tab as
        // well, so either would be found whether the bar was there or not.
        val model = editor()
        assertTrue(
            "the contextual bar is missing before any panel is open, so this test proves nothing",
            compose.onAllNodesWithText("به جلو").fetchSemanticsNodes().isNotEmpty(),
        )

        open(model, SheetContent.Guides)
        assertTrue(
            "the contextual bar is still up inside a panel. Its job is «what do I do to this " +
                "next», and inside a panel the answer is the panel.",
            compose.onAllNodesWithText("به جلو").fetchSemanticsNodes().isEmpty(),
        )
    }
}
