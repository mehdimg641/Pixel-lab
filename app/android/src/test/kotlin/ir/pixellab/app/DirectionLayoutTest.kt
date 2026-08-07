package ir.pixellab.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Whether the four directions are four *layouts* rather than four palettes.
 *
 * ### Why this exists
 *
 * The application shipped four themes for a while that differed in colour, corner radius and
 * typeface and in nothing else. That is a defensible product and it is not the one that was asked
 * for: the brief gives each direction its own arrangement — Console opens with a menu bar and puts a
 * tool rail and an inspector down the two edges, Iris keeps the chrome off the picture and floats
 * the two values a hand is changing on the glass, Ember and «امبر — فارسی» dock a plain panel and
 * put nothing at all over the artwork.
 *
 * A screenshot shows that; it cannot *fail* on it. `ScreenshotTest` photographs all eight frames and
 * would go on passing if Console quietly lost its rail, because a frame with a rail and a frame
 * without both carry plenty of distinct colours. This is the half that fails.
 *
 * ### Why every assertion is a pair
 *
 * Each one checks that a direction has a thing **and that another direction does not**. The positive
 * half alone is satisfied by putting every feature in every direction, which is precisely the state
 * this is here to prevent — the four would then be four palettes again, just busier ones.
 *
 * Matched on the words and the spoken labels rather than on node structure, because those are what a
 * person and a screen reader actually get. A rail whose buttons lost their `contentDescription`
 * fails here, which is correct: an unlabelled icon column is not a tool rail to somebody using
 * TalkBack.
 */
@RunWith(RobolectricTestRunner::class)
// A real phone's dimensions, not Robolectric's 320×470 default. Half of what this file asserts is
// about what fits on a screen — a tool rail beside a canvas, fourteen tabs laid out at once — and
// on a screen narrower than any phone shipped this decade, the answer is "nothing does".
@Config(sdk = [34], qualifiers = "fa-w411dp-h891dp-xhdpi")
class DirectionLayoutTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * One composition for every direction, swapped under the test.
     *
     * `setContent` may only be called once per test, and — the more expensive lesson — [Metrics] is
     * a process-global singleton, so the direction left in force at the end of a test is the one the
     * next class in the same worker composes in. It is put back in [restore] for that reason. See
     * the note in `NumericReadoutTest`, which is where thirty-two unrelated failures came from.
     */
    private var skin by mutableStateOf(ThemeSkin.EMBER)

    private fun editor() {
        val model = EditorViewModel(ApplicationProvider.getApplicationContext())
            // The recovery timer schedules a delay on the main looper that never comes due, and the
            // startup coroutine scans the font library off-thread and resumes on Main.
            // Compose's idling check waits on both queues; neither is anything this test
            // measures.
            .also { it.stopBackgroundWork() }
        model.act { select(state.document.layers.first().id) }
        compose.setContent {
            PixelLabTheme(skin = skin, dark = true) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Box(Modifier.fillMaxSize()) { EditorScreen(model = model) }
                }
            }
        }
    }

    private fun show(direction: ThemeSkin) {
        skin = direction
        compose.waitForIdle()
    }

    @org.junit.After
    fun restore() {
        skin = ThemeSkin.EMBER
        compose.waitForIdle()
    }

    private fun texts(label: String): Int =
        compose.onAllNodesWithText(label).fetchSemanticsNodes().size

    private fun described(label: String): Int =
        compose.onAllNodesWithContentDescription(label).fetchSemanticsNodes().size

    @Test
    fun `only Console opens with a menu bar`() {
        editor()

        show(ThemeSkin.CONSOLE)
        for (menu in listOf("فایل", "ویرایش", "تصویر", "لایه", "هوش")) {
            assertTrue("«$menu» is missing from Console's menu bar", texts(menu) > 0)
        }

        // «لایه» and «متن» are also panel tabs, so the negative half has to name something the menu
        // bar alone carries. «ویرایش» and «تصویر» appear nowhere else in the editor's chrome.
        for (direction in listOf(ThemeSkin.EMBER, ThemeSkin.IRIS, ThemeSkin.EMBER_FA)) {
            show(direction)
            assertEquals(
                "${direction.persianLabel} grew a menu bar. Only Console has one — the brief gives " +
                    "the other three their verbs in the panel, and a 30dp row of text targets is " +
                    "a compromise exactly one direction is allowed to make.",
                0,
                texts("ویرایش") + texts("تصویر"),
            )
        }
    }

    @Test
    fun `only Console lays a tool rail and an inspector beside the canvas`() {
        editor()

        show(ThemeSkin.CONSOLE)
        // Three of the eight rail tools, chosen because none of them is a word that appears in the
        // panel tabs or the contextual bar in this state.
        for (tool in listOf("قلم مسیر", "شکل", "ترمیم")) {
            assertTrue("«$tool» is missing from Console's tool rail", described(tool) > 0)
        }

        for (direction in listOf(ThemeSkin.EMBER, ThemeSkin.IRIS, ThemeSkin.EMBER_FA)) {
            show(direction)
            assertEquals(
                "${direction.persianLabel} grew a tool rail. It costs a permanent gutter out of a " +
                    "411dp canvas, which is a trade only the dense direction makes.",
                0,
                described("قلم مسیر"),
            )
        }
    }

    @Test
    fun `only Iris floats a value on the artwork`() {
        editor()

        show(ThemeSkin.IRIS)
        assertTrue(
            "Iris lost its canvas dial. «canvas-first, chrome hidden until needed» is not a " +
                "promise it can keep if changing a value means opening a panel over the picture " +
                "the value is being judged against.",
            described("شفافیت") > 0,
        )

        for (direction in listOf(ThemeSkin.EMBER, ThemeSkin.CONSOLE, ThemeSkin.EMBER_FA)) {
            show(direction)
            assertEquals(
                "${direction.persianLabel} drew a control on the artwork. The docked directions put " +
                    "their controls in the panel — that is what makes them the docked directions.",
                0,
                described("شفافیت"),
            )
        }
    }

    @Test
    fun `all four keep the five tabs and the export button`() {
        // The half that must **not** vary. Four layouts are four ways to arrange the same
        // application; a direction that dropped a tab would be a fifth of the program missing
        // depending on a colour preference, which is the failure mode of theming taken too far.
        editor()
        for (direction in ThemeSkin.entries) {
            show(direction)
            for (tab in PanelTab.entries) {
                assertTrue(
                    "«${tab.persianLabel}» is missing from ${direction.persianLabel}",
                    described(tab.persianLabel) > 0,
                )
            }
            assertTrue("خروجی is missing from ${direction.persianLabel}", texts("خروجی") > 0)
        }
    }

    @Test
    fun `the text studio's section strip takes a different shape in each direction`() {
        // The brief's last three-way split, and the one most likely to be quietly lost: the strip
        // is one composable with a branch in it, so a refactor that drops the branch leaves three
        // directions looking identical and nothing failing.
        //
        // Asserted on what the shape *does* rather than on how it is drawn: Console's block lays
        // every one of the fourteen sections out at once, and the scrolling strip the other three
        // use puts most of them past the edge of the screen. «پیشرفته» is the last of the fourteen.
        //
        // On *displayed* rather than on present. A `horizontalScroll` is not lazy, so all fourteen
        // are in the semantics tree either way — the difference is whether they are on the screen,
        // which is also the difference a person experiences.
        val model = EditorViewModel(ApplicationProvider.getApplicationContext())
            .also { it.stopBackgroundWork() }
        val headline = ir.pixellab.core.model.LayerId("headline")
        model.act {
            addLayer(
                ir.pixellab.core.model.Layer.Text(
                    id = headline,
                    spec = ir.pixellab.core.model.TextSpec(
                        text = "سلام",
                        font = ir.pixellab.core.model.FontRef("Vazirmatn"),
                    ),
                    name = "تیتر",
                ),
            )
            select(headline)
        }
        compose.setContent {
            PixelLabTheme(skin = skin, dark = true) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Box(Modifier.fillMaxSize()) {
                        TextStudioScreen(model, layer = headline, onDone = {})
                    }
                }
            }
        }

        show(ThemeSkin.CONSOLE)
        compose.onNodeWithText("پیشرفته").assertIsDisplayed()

        for (direction in listOf(ThemeSkin.EMBER, ThemeSkin.IRIS, ThemeSkin.EMBER_FA)) {
            show(direction)
            compose.onNodeWithText("پیشرفته").assertIsNotDisplayed()
        }
    }

    @Test
    fun `only the Persian direction sets its read-outs in Persian digits`() {
        editor()

        show(ThemeSkin.EMBER_FA)
        assertTrue(
            "«امبر — فارسی» is the one direction whose whole difference is this, and the canvas " +
                "read-out is the most visible number in the application.",
            texts("۱۰۸۰ × ۱۰۸۰") > 0,
        )

        for (direction in listOf(ThemeSkin.EMBER, ThemeSkin.IRIS, ThemeSkin.CONSOLE)) {
            show(direction)
            assertEquals(
                "${direction.persianLabel} switched to Persian digits. The specification's argument " +
                    "for Latin here — that these are values the user types back in — holds for the " +
                    "three directions that are not the exception to it.",
                0,
                texts("۱۰۸۰ × ۱۰۸۰"),
            )
            assertTrue("1080 × 1080 is missing from ${direction.persianLabel}", texts("1080 × 1080") > 0)
        }
    }
}
