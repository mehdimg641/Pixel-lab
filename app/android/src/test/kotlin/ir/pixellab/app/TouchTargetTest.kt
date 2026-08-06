package ir.pixellab.app

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import ir.pixellab.core.model.FontRef
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId
import ir.pixellab.core.model.TextSpec
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import kotlin.math.min

/**
 * Whether the interface can be *hit*.
 *
 * The companion to [ThemeContrastTest], and it exists for the same reason: nobody had ever measured
 * it. **Ten controls were under the platform minimum** when this was written — the chip, the pill,
 * the slider's drag strip, the section action, the kashida handle, a layer's visibility and lock,
 * a group's expand triangle, the structure bar, and the eight colour swatches — and the worst of
 * them, a history step, was a **six-point square carrying a click**: not a small target but an
 * unreachable one. Six of the ten were found by measuring rather than by reading.
 *
 * None of the app's nineteen hundred other tests could see any of it, because a test that calls
 * `onClick` hits a control of any size; only a *laid-out measurement* can tell you a finger would
 * have missed. Two text fields turned out to have a live strip barely twenty points tall sitting
 * inside a well that looked twice that, with dead space all around it — a defect that is invisible
 * both to a screenshot and to a test that types into the field.
 *
 * ### Why this measures geometry rather than constants
 *
 * The obvious test asserts on `CHIP_HEIGHT` and friends. That test passes forever and catches
 * nothing, because the next undersized control will be a fresh literal in a new file. This one
 * composes the real screens, walks the semantics tree, and measures **every node that reports an
 * action** — so a control written next month is covered on the day it is written, without anyone
 * remembering to add it here.
 *
 * ### The bar
 *
 * [Space.touch] — 48dp — on both axes. That is the app's own token, Android's stated minimum, and
 * above WCAG 2.2's 2.5.5 AAA of 44. A control may fall to [DENSE] on one axis only when its count
 * is genuinely unbounded, and only by being named in [DENSE_TARGETS]: the allowance is WCAG 2.5.8's
 * for dense repeated controls, and the point of spelling the exceptions out here is that taking one
 * has to be a decision somebody made rather than a number somebody typed.
 *
 * ### What this does not cover, stated plainly
 *
 * Controls driven by a bare `pointerInput` with no semantic action — the parameter slider's strip
 * and the kashida handle — are invisible to a semantics walk. Both are also unreachable by a screen
 * reader for the same reason, which is a real defect and a larger piece of work than this test.
 * Their dimensions — `PrecisionSlider.TOUCH_STRIP` and `GlyphRibbon.HANDLE` — are held to the bar
 * by hand at their definitions, and this paragraph is the record that they are not held here.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class TouchTargetTest {

    /**
     * A view model with its recovery timer switched off.
     *
     * `EditorViewModel.init` starts `AutoSave`, whose loop is `while (isActive) { delay(minutes) }`,
     * so constructing one leaves a task scheduled on the main looper that never comes due. Whether
     * that on its own can stall Compose's idling check is not something this class proved — the
     * hang it actually had was environment reuse, and the fix for that is in [undersized]. But a
     * test that leaves a timer running in a sandbox shared with every other test in the module is
     * borrowing trouble from whatever runs next, and stopping it costs one line.
     */
    private fun editor(): EditorViewModel =
        EditorViewModel(ApplicationProvider.getApplicationContext())
            .also { it.autoSave.stop() }

    /**
     * Composes right-to-left, lays out, and returns every target that misses the bar.
     *
     * Right-to-left because the interface is Persian, and a target measured in the mirror of its
     * real layout is not the target the user reaches for.
     *
     * ### The line that makes this class run at all
     *
     * `mainClock.autoAdvance = false`, set before anything is composed. Without it these tests
     * throw `AppNotIdleException` — "did not get idle after 2,689,774 attempts in 60 SECONDS" — on
     * compositions that are in fact laid out and correct. With the clock auto-advancing,
     * `waitForIdle` also waits on the frame clock's *awaiters*, and anything in the tree that keeps
     * asking for frames holds the environment un-idle forever. Nothing measured here needs frames
     * to advance: layout has already happened by the time `waitForIdle` returns, and layout is the
     * entire subject.
     *
     * It is worth recording how this was found, because two plausible readings were both wrong.
     * The failure appeared only inside `./gradlew build` and never when the module's tests were run
     * on their own, which reads exactly like a machine-load flake — it is not: serialising the
     * build to a single worker reproduced it unchanged. It also survived stopping the view model's
     * autosave timer and the startup font scan, both of which looked like plausible sources of
     * pending work (the scan turns out to take milliseconds and find nothing here). What actually
     * separates the two runs is `assembleDebug`: once the app's merged resources exist, something
     * in the real tree asks for frames, and the clock obliges forever.
     *
     * `runComposeUiTest` rather than a class-level `@get:Rule` is a smaller point — an environment
     * built and disposed per call rather than reused — and it is kept because per-test isolation is
     * worth having on its own, not because it fixed anything.
     */
    private fun undersized(dark: Boolean = true, content: @Composable () -> Unit): List<String> {
        // `runComposeUiTest` returns Unit, so the findings come out through this rather than as
        // its value — the alternative is asserting inside the block, which loses the screen name.
        var found = emptyList<String>()
        runComposeUiTest {
            // Off before anything is composed. With the clock auto-advancing, `waitForIdle` also
            // waits on the frame clock's awaiters, and anything that asks for a frame keeps the
            // environment permanently un-idle — which is the shape of the hang this class had:
            // `AppNotIdleException` after 2.7 million attempts, on compositions that were in fact
            // laid out. Nothing here needs frames to advance; the measurement is of layout, and
            // layout has already happened by the time `waitForIdle` returns.
            mainClock.autoAdvance = false
            setContent {
                PixelLabTheme(dark = dark) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        content()
                    }
                }
            }
            waitForIdle()
            Shadows.shadowOf(Looper.getMainLooper()).idle()

            found = onAllNodes(interactive, useUnmergedTree = false)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .mapNotNull { node ->
                    val width = node.size.width / density.density
                    val height = node.size.height / density.density
                    val name = node.describe()
                    val floor = if (DENSE_TARGETS.any(name::startsWith)) DENSE else BAR
                    // Both axes against the floor, and the larger axis against the full bar when
                    // the dense allowance is in play — an exemption buys one narrow side, not two.
                    val ok = min(width, height) >= floor &&
                        (floor == BAR || maxOf(width, height) >= BAR)
                    // One decimal, because the interesting failures are the near misses: a target
                    // that reports as "48×48" and fails is a 47.5 that rounding hid, and a message
                    // that hides it sends the reader looking for a bug in the test.
                    if (ok) null else "%s is %.1f×%.1f dp".format(name, width, height)
                }
        }
        return found
    }

    /**
     * Anything a finger is meant to land on.
     *
     * A click action *or* an interactive role: the role alone catches a control whose press is
     * handled by a gesture detector, and the action alone catches one that never declared a role.
     * Either is enough to mean somebody is expected to hit it.
     */
    private val interactive = SemanticsMatcher("is an interactive target") { node ->
        node.config.contains(SemanticsActions.OnClick) ||
            node.config.getOrNull(SemanticsProperties.Role) in INTERACTIVE_ROLES
    }

    /** The most useful name available: the click label, then the description, then the text. */
    private fun SemanticsNode.describe(): String =
        config.getOrNull(SemanticsActions.OnClick)?.label
            ?: config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
            ?: config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text
            ?: config.getOrNull(SemanticsProperties.Role)?.toString()
            ?: "an unnamed target"

    private fun assertAllReachable(screen: String, failures: List<String>) {
        // Every failure at once. Fixing targets one assertion at a time is one rebuild per control,
        // and they are nearly always the same mistake repeated.
        assertTrue(
            "on $screen these targets are under ${BAR.toInt()}dp:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    @Test
    fun `every target on the home screen can be hit`() {
        assertAllReachable(
            "the home screen",
            undersized {
                HomeScreen(projects = emptyList(), onNew = {}, onOpen = {}, onQuickAction = {}, onSettings = {})
            },
        )
    }

    @Test
    fun `every target on the editor chrome can be hit`() {
        // The screen that matters: the top bar, the selection card, the ribbon and the dock are
        // where the user spends the whole session, and three of the four undersized controls this
        // test was written for were on it.
        val model = editor()
        model.act { select(state.document.layers.first().id) }
        assertAllReachable(
            "the editor chrome",
            undersized {
                Column(
                    Modifier.fillMaxSize().background(Ink.Surround),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    TopBar(state = model.state, model = model, onHome = {})
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        SelectionCard(model.state, model, onEditText = {})
                        Ribbon(
                            dock = Dock.PHOTO,
                            state = model.state,
                            model = model,
                            onPickImage = {},
                            onAddText = {},
                            onEditText = {},
                            onExport = {},
                            onSave = {},
                            onOpen = {},
                        )
                        MainDock(Dock.PHOTO, model.state, model) {}
                    }
                }
            },
        )
    }

    @Test
    fun `every shared component can be hit`() {
        // The gallery rather than a screen: these are the pieces every sheet is assembled from, so
        // one undersized component here is an undersized control on twenty panels.
        assertAllReachable(
            "the shared components",
            undersized {
                var chosen by remember { mutableStateOf(1) }
                Column(Modifier.fillMaxWidth().background(Ink.ChromeRaised)) {
                    SectionHeader("اجزای مشترک", action = "همه") {}
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                        IconTile(Icons.Filled.ViewInAr, "متن سه‌بعدی", selected = true) {}
                        IconTile(Icons.Filled.Brush, "نقاشی") {}
                        IconTile(Icons.Filled.Image, "عکس", enabled = false) {}
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.small)) {
                        for (i in 0..2) Pill("گزینهٔ $i", selected = chosen == i) { chosen = i }
                    }
                    PrimaryAction("شروع طراحی", icon = Icons.Filled.ViewInAr) {}
                    SecondaryAction("بعداً") {}
                    SheetChips {
                        for (i in 0..3) SheetChip("گزینهٔ $i", chosen = chosen == i) { chosen = i }
                    }
                    SheetAction("انجام بده") {}
                }
            },
        )
    }

    @Test
    fun `every target on the text path can be hit`() {
        // The loop the application exists for: place a word, then reach for the font, the colour,
        // the shadow. Its own test because the ribbon under the canvas *changes with the selection*
        // — with a photograph selected it shows different controls entirely, so the editor-chrome
        // test above never touches any of these.
        val model = editor()
        // The layer is built here rather than through `addTextLayer`, which needs a scanned font
        // catalogue — and the scan is a coroutine that Robolectric's paused looper never runs. The
        // controls being measured read the *spec*, not the resolved face, so this is the same
        // layer as far as the ribbon is concerned.
        val text = LayerId("text-under-test")
        model.act {
            addLayer(
                Layer.Text(
                    id = text,
                    spec = TextSpec(text = "سلام", font = FontRef(family = "Vazirmatn"), size = 96f),
                    name = "سلام",
                ),
            )
        }
        model.act { select(text) }

        assertAllReachable(
            "the text path",
            undersized {
                Column(Modifier.fillMaxSize().background(Ink.Surround)) {
                    SelectionCard(model.state, model, onEditText = {})
                    Ribbon(
                        dock = Dock.TEXT,
                        state = model.state,
                        model = model,
                        onPickImage = {},
                        onAddText = {},
                        onEditText = {},
                        onExport = {},
                        onSave = {},
                        onOpen = {},
                    )
                    TypeSheetBody(model.state, model)
                }
            },
        )
    }

    @Test
    fun `every target on the layer panel can be hit`() {
        val model = editor()
        model.act { select(state.document.layers.first().id) }
        assertAllReachable(
            "the layer panel",
            undersized {
                Column(Modifier.fillMaxSize().background(Ink.ChromeRaised)) {
                    LayerPanel(model.state, model)
                }
            },
        )
    }

    @Test
    fun `a history step can be hit`() {
        // Its own test, because the strip is empty until something has happened — and an empty
        // strip passes every assertion above while telling you nothing about the control that was
        // six points across.
        val model = editor()
        val first = model.state.document.layers.first().id
        // Four *edits*, not four selections. The view model records a step only when the editor's
        // undo depth actually moved, and a tap that selects nothing was never worth a step — which
        // is correct behaviour and would have made this test measure an empty strip.
        repeat(4) { step -> model.act { setLayerOpacity(first, 0.9f - step * 0.1f) } }

        val failures = undersized {
            Box(Modifier.fillMaxWidth().background(Ink.ChromeRaised)) {
                HistoryStrip(model)
            }
        }
        // The strip has to have drawn something, or this test is asserting about nothing. This is
        // the guard the screenshot tests earn with their blank-image check.
        assertTrue(
            "the history strip is empty, so this test measured nothing",
            model.historyLength > 0,
        )
        assertAllReachable("the history strip", failures)
    }

    private companion object {
        /** The app's own token, and the platform's minimum. */
        val BAR = Space.touch.value

        /** WCAG 2.2 §2.5.8 AA, for repeated controls whose count cannot be bounded. */
        const val DENSE = 24f

        /**
         * The controls allowed the dense floor on one axis, matched by the start of their label.
         *
         * One entry, and it should stay hard to add to. A history strip can hold two hundred steps;
         * at the full bar that is nine metres of scroller, which is not a usable control either.
         */
        val DENSE_TARGETS = listOf("گام ")

        val INTERACTIVE_ROLES = setOf(
            androidx.compose.ui.semantics.Role.Button,
            androidx.compose.ui.semantics.Role.Checkbox,
            androidx.compose.ui.semantics.Role.RadioButton,
            androidx.compose.ui.semantics.Role.Switch,
            androidx.compose.ui.semantics.Role.Tab,
        )
    }
}
