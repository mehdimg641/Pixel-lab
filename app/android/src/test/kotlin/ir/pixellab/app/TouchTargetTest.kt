package ir.pixellab.app

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.min

/**
 * Whether the interface can be *hit*.
 *
 * The companion to [ThemeContrastTest], and it exists for the same reason: nobody had ever measured
 * it. **Ten controls were under the platform minimum** when this was written — the chip, the pill,
 * the slider's drag strip, the section action, the kashida handle, a layer's visibility and lock, a
 * group's expand triangle, the structure bar, and the eight colour swatches — and the worst of them,
 * a history step, was a **six-point square carrying a click**: not a small target but an unreachable
 * one. Six of the ten were found by measuring rather than by reading.
 *
 * None of the app's nineteen hundred other tests could see any of it, because a test that calls
 * `onClick` hits a control of any size; only a *laid-out measurement* can tell you a finger would
 * have missed. Two text fields turned out to have a live strip barely twenty points tall sitting
 * inside a well that looked twice that, with dead space all around it — a defect invisible both to a
 * screenshot and to a test that types into the field.
 *
 * ### Why this measures geometry rather than constants
 *
 * The obvious test asserts on `CHIP_HEIGHT` and friends. That test passes forever and catches
 * nothing, because the next undersized control will be a fresh literal in a new file. This one walks
 * the real screens through [AuditedInterface] and measures **every node that reports an action** — so
 * a control written next month is covered on the day it is written, without anyone remembering to
 * add it here.
 *
 * ### The bar
 *
 * [Space.touch] — 48dp — on both axes. That is the app's own token, Android's stated minimum, and
 * above WCAG 2.2's 2.5.5 AAA of 44. A control may fall to [DENSE] on one axis only when its count is
 * genuinely unbounded, and only by being named in [DENSE_TARGETS]: the allowance is WCAG 2.5.8's for
 * dense repeated controls, and the point of spelling the exceptions out here is that taking one has
 * to be a decision somebody made rather than a number somebody typed.
 *
 * ### What this does not cover, stated plainly
 *
 * Controls driven by a bare `pointerInput` with no semantic action are invisible to a semantics
 * walk. That was true of the parameter slider and the kashida handle when this was written, and it
 * is [ScreenReaderTest] that now refuses to let a control stay in that state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class TouchTargetTest {

    private fun assertAllReachable(screen: AuditedInterface.Screen) {
        val failures = AuditedInterface.probe(screen).mapNotNull { probe ->
            val floor = if (DENSE_TARGETS.any { probe.label.startsWith(it) }) DENSE else BAR
            // Both axes against the floor, and the larger axis against the full bar when the dense
            // allowance is in play — an exemption buys one narrow side, not two.
            val ok = min(probe.widthDp, probe.heightDp) >= floor &&
                (floor == BAR || maxOf(probe.widthDp, probe.heightDp) >= BAR)
            // One decimal, because the interesting failures are the near misses: a target that
            // reports as "48×48" and fails is a 47.5 that rounding hid, and a message that hides it
            // sends the reader looking for a bug in the test.
            if (ok) null else "%s is %.1f×%.1f dp".format(probe.label, probe.widthDp, probe.heightDp)
        }
        // Every failure at once. Fixing targets one assertion at a time is one rebuild per control,
        // and they are nearly always the same mistake repeated.
        assertTrue(
            "on ${screen.name} these targets are under ${BAR.toInt()}dp:\n" + failures.joinToString("\n"),
            failures.isEmpty(),
        )
    }

    @Test
    fun `every target on the home screen can be hit`() =
        assertAllReachable(AuditedInterface.homeScreen())

    @Test
    fun `every target on the editor chrome can be hit`() =
        assertAllReachable(AuditedInterface.editorChrome())

    @Test
    fun `every target on the text path can be hit`() =
        assertAllReachable(AuditedInterface.textPath())

    @Test
    fun `every target on the layer panel can be hit`() =
        assertAllReachable(AuditedInterface.layerPanel())

    @Test
    fun `a history step can be hit`() =
        assertAllReachable(AuditedInterface.historyStrip())

    @Test
    fun `every shared component can be hit`() =
        assertAllReachable(AuditedInterface.sharedComponents())

    @Test
    fun `every control on the colour picker can be hit`() =
        assertAllReachable(AuditedInterface.colourPicker())

    @Test
    fun `every control on every sheet can be hit`() {
        // The sheets were outside this audit until the screen-reader sweep reached them and found
        // eight 32dp swatches on the brush panel. A control that is too small and unnamed is one
        // defect written twice, and the audit that only looked at the chrome saw neither.
        for (sheet in AuditedInterface.sheets()) assertAllReachable(sheet)
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
    }
}
