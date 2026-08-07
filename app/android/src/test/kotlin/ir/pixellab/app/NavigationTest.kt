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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import ir.pixellab.core.editor.TextSection
import ir.pixellab.core.model.FontRef
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId
import ir.pixellab.core.model.TextSpec
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Whether every way *in* to a studio still leads somewhere.
 *
 * ### Why this file exists, and it is not a hypothetical
 *
 * Moving a panel from a sheet to a screen is indistinguishable, from the outside, from deleting it —
 * right up until somebody looks for it. That happened here already: when the contextual ribbon came
 * out of the editor, **saving and opening a project went with it**, because they had lived nowhere
 * else. The code compiled, every test stayed green, and two whole verbs were simply gone from the
 * application until a screenshot happened to be read carefully.
 *
 * A route is not code. Nothing type-checks it, nothing fails without it, and its absence looks
 * exactly like a feature that was never built. So each row of the plan's entry-point table gets an
 * assertion here: press the thing a person would press, and check the destination it asks for.
 *
 * ### Why it drives the real screen
 *
 * `EditorScreen` composed whole, with a recording `onNavigate`. Testing the callback by calling it
 * directly would prove the callback works, which was never in doubt; what is in doubt is whether
 * anything on screen is still wired to it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fa")
class NavigationTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** The last navigation the screen asked for, or null if it has asked for none. */
    private var went: Triple<Destination, LayerId?, TextSection?>? = null

    private var skin by mutableStateOf(ThemeSkin.EMBER)

    private fun editor(withText: Boolean = false): EditorViewModel {
        val model = EditorViewModel(ApplicationProvider.getApplicationContext())
            // The recovery timer schedules a delay on the main looper that never comes due, and the
            // startup coroutine scans the font library off-thread and resumes on Main.
            // Compose's idling check waits on both queues; neither is anything this test
            // measures.
            .also { it.stopBackgroundWork() }
        if (withText) {
            // Built directly: `addTextLayer` needs a scanned font catalogue and this environment
            // has none, so going through it would leave every assertion below vacuously true.
            model.act {
                addLayer(
                    Layer.Text(
                        id = HEADLINE,
                        spec = TextSpec(text = "سلام", font = FontRef("Vazirmatn")),
                        name = "تیتر",
                    ),
                )
                select(HEADLINE)
            }
        } else {
            model.act { select(state.document.layers.first().id) }
        }

        compose.setContent {
            PixelLabTheme(skin = skin, dark = true) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Box(Modifier.fillMaxSize()) {
                        EditorScreen(
                            model = model,
                            onNavigate = { destination, layer, section ->
                                went = Triple(destination, layer, section)
                            },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        return model
    }

    @org.junit.After
    fun restore() {
        // [Metrics] is a process-global singleton, so the direction left in force at the end of a
        // test is the one the next class in the same worker composes in. Thirty-two unrelated
        // failures came from exactly this — see the note in `NumericReadoutTest`.
        skin = ThemeSkin.EMBER
    }

    private fun tap(label: String) {
        compose.onNodeWithContentDescription(label).performClick()
        compose.waitForIdle()
    }

    private fun tapText(label: String) {
        compose.onNodeWithText(label).performClick()
        compose.waitForIdle()
    }

    @Test
    fun `the text tab's studio chip opens the text studio on the selected headline`() {
        editor(withText = true)
        tap("متن")
        tapText("استودیو متن")

        assertEquals(
            "the text tab's «استودیو متن» chip no longer navigates. It is the primary way into " +
                "the screen this application exists for.",
            Destination.TEXT_STUDIO,
            went?.first,
        )
        assertEquals("the studio was opened on no particular layer", HEADLINE, went?.second)
    }

    @Test
    fun `the contextual bar opens the studio at the section it names`() {
        editor(withText = true)
        // «افکت» on a piece of type is a promise about *where* it lands: on the stroke section,
        // which is what the word means on that bar. Navigating to the studio's first section
        // instead would technically pass a "does it navigate" test and be the wrong screen.
        tapText("افکت")

        assertEquals(Destination.TEXT_STUDIO, went?.first)
        assertEquals(HEADLINE, went?.second)
        assertEquals(
            "«افکت» dropped the user on the studio's first section rather than on the stroke.",
            TextSection.STROKE,
            went?.third,
        )
    }

    @Test
    fun `both retouch tiles open the retouch studio`() {
        editor()
        tap("هوش مصنوعی")

        tapText("پرتره")
        assertEquals("«پرتره» stopped navigating", Destination.RETOUCH, went?.first)

        went = null
        tapText("ترمیم")
        assertEquals("«ترمیم» stopped navigating", Destination.RETOUCH, went?.first)
    }

    @Test
    fun `the brush studio is reachable from the panel`() {
        editor()
        tap("هوش مصنوعی")
        tapText("استودیو قلم")

        assertEquals(
            "the brush studio has no entry point outside Console's tool rail, which three of the " +
                "four directions do not have.",
            Destination.BRUSH,
            went?.first,
        )
    }

    @Test
    fun `Console's AI menu reaches the retouch studio`() {
        skin = ThemeSkin.CONSOLE
        editor()
        tapText("هوش")
        tapText("ترمیم")

        assertEquals(
            "Console's menu bar still opens the old sheet. A menu entry that goes somewhere the " +
                "rest of the application no longer goes is worse than an absent one.",
            Destination.RETOUCH,
            went?.first,
        )
    }

    @Test
    fun `the gallery reaches the templates page`() {
        var wentToTemplates = false
        compose.setContent {
            PixelLabTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    HomeScreen(
                        projects = emptyList(),
                        onNew = {},
                        onOpen = {},
                        onQuickAction = {},
                        onSettings = {},
                        onAllTemplates = { wentToTemplates = true },
                    )
                }
            }
        }
        compose.waitForIdle()
        tapText("همهٔ قالب‌ها")

        org.junit.Assert.assertTrue(
            "the templates page is unreachable. The home strip shows about eight sizes and the " +
                "print ones are below the fold, which are the sizes somebody goes looking for.",
            wentToTemplates,
        )
    }

    private companion object {
        val HEADLINE = LayerId("headline")
    }
}
