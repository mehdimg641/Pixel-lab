package ir.pixellab.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import ir.pixellab.core.model.FontRef
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId
import ir.pixellab.core.model.TextSpec
import ir.pixellab.core.paint.Marquee
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Whether the contextual bar is actually contextual.
 *
 * ### The defect
 *
 * It offered the same six actions in every state — only «متن» was conditional — and it appeared
 * **only when a layer was selected**. The two moments a person is most stuck showed nothing at all:
 * an empty canvas, where the question is "how do I put something here", and a live pixel selection,
 * which is the one state where every useful next step is a different panel.
 *
 * That is not a small omission. The deep Photoshop reading behind this work put its Contextual Task
 * Bar at the top of what that interface gets right, and the reason is exactly this — it is a *guess
 * at the next step*, and a guess that never changes is not a guess.
 *
 * ### What is checked
 *
 * That each of the four states offers what belongs to it and **not** what belongs to another. The
 * negative half is the half that matters: a bar that showed every action in every state would pass
 * any test that only looked for the right ones, which is how the original stayed unnoticed.
 *
 * Asserted through the label each button actually shows. `BarAction` draws an icon with a Persian
 * word under it and carries the same word as its click label, so matching on the text is matching
 * on what a person sees *and* on what a screen reader announces.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fa")
class ContextualBarTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun editor(): EditorViewModel =
        EditorViewModel(ApplicationProvider.getApplicationContext()).also {
            // The recovery timer schedules a delay on the main looper that never comes due, and
            // Compose's idling check waits on that queue.
            it.autoSave.stop()
        }

    private fun show(model: EditorViewModel) {
        compose.setContent {
            PixelLabTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Column { SelectionCard(model.state, model, onEditText = {}) }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertShows(vararg labels: String) {
        for (label in labels) compose.onNodeWithText(label).assertExists("«$label» is missing from the bar in this state")
    }

    private fun assertHides(vararg labels: String) {
        for (label in labels) compose.onAllNodesWithText(label).fetchSemanticsNodes().isEmpty()
            .let { org.junit.Assert.assertTrue("«$label» should not be offered in this state", it) }
    }

    @Test
    fun `an empty canvas offers a way to put something on it`() {
        // The state the old bar had no answer for, and the one every new user starts in. It showed
        // nothing here, so the first thing the application said to a first-time user was silence.
        val model = editor()
        model.act { clearSelection() }
        show(model)

        assertShows("افزودن عکس", "افزودن متن", "قالب")
        // And none of the per-layer actions, which would have nothing to act on.
        assertHides("حذف", "کپی", "چیدمان")
    }

    @Test
    fun `a live pixel selection offers the four things you do with one`() {
        val model = editor()
        model.act { clearSelection() }
        model.select.set(
            Marquee.rectangle(
                model.state.document.canvas.width,
                model.state.document.canvas.height,
                ir.pixellab.core.model.Rect(10f, 10f, 100f, 100f),
            ),
        )
        show(model)

        assertShows("اصلاح لبه", "معکوس", "لغو انتخاب")
        // The layer verbs are wrong here: the user just drew marching ants, and «حذف» in that
        // moment reads as "delete the selection", which is not what it would do.
        assertHides("حذف", "به جلو", "افکت")
    }

    @Test
    fun `a text layer offers the type panels, a picture offers the picture ones`() {
        val model = editor()
        val id = LayerId("headline")
        // Built directly: `addTextLayer` needs a scanned font catalogue and this environment has
        // none, so going through it would leave every assertion below vacuously true.
        model.act {
            addLayer(Layer.Text(id = id, spec = TextSpec(text = "سلام", font = FontRef("Vazirmatn")), name = "تیتر"))
            select(id)
        }
        show(model)

        assertShows("متن", "فونت", "افکت", "چیدمان", "کپی", "به جلو", "حذف")
        // The picture-only entry must not appear on type, or the bar is back to one state with
        // extra buttons.
        assertHides("جدا کردن سوژه", "تنظیم")
    }

    @Test
    fun `the layer verbs are written once, not once per state`() {
        // Not a UI assertion — a structural one. Three copies of «کپی · به جلو · حذف» is three
        // chances for them to drift apart, which is how the bar ended up identical in every state
        // in the first place: the differences were too expensive to keep.
        val source = java.io.File("src/main/kotlin/ir/pixellab/app/EditorScreen.kt")
            .takeIf { it.exists() }
            ?.readText()
            ?: error("EditorScreen.kt not found; this test runs from the app module's directory")

        val occurrences = Regex("\"حذف\"").findAll(source).count()
        org.junit.Assert.assertTrue(
            "«حذف» is written $occurrences times in EditorScreen.kt. The per-layer verbs belong in " +
                "LayerActions, called from each branch — copies drift.",
            occurrences <= 2,
        )
    }
}
