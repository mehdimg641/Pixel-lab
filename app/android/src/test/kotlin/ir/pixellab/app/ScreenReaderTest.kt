package ir.pixellab.app

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Whether the interface can be *heard*.
 *
 * The third of these audits, after [ThemeContrastTest] (can it be read) and [TouchTargetTest] (can
 * it be hit). WCAG 2.2 puts name, role and state at P0 for every control — 4.1.2 — and an icon-only
 * button with no name is the single most common way a mobile interface fails it. This application
 * is almost entirely icon-only buttons.
 *
 * ### Why a sweep and not a checklist
 *
 * Counting `semantics` blocks per file was how this started, and it is a bad instrument: nine files
 * had none at all, but a file with twenty could still be missing the one control that matters. What
 * matters is whether the *laid-out* control has something to announce, and that is only knowable
 * from the tree. So this walks the same screens [TouchTargetTest] measures and asks a different
 * question of each node.
 *
 * ### The rule
 *
 * Every interactive control must have a name: a click label, a content description, or text of its
 * own. A control the user can operate and TalkBack cannot announce is, for anyone who relies on it,
 * a control that is not there — and unlike a small target, no amount of care with the finger gets
 * round it.
 *
 * **A chosen state has to be announced too.** A row of six chips where one is selected reads as six
 * identical buttons unless the selection is in the semantics; the sighted user's only cue is a tint,
 * and colour alone is not an indicator (WCAG 1.4.1). So a control carrying a selectable role is
 * required to carry the state as well.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class ScreenReaderTest {

    private fun assertEveryControlSpeaks(screen: AuditedInterface.Screen) {
        val probes = AuditedInterface.probe(screen)
        val unnamed = probes.filter { it.name.isNullOrBlank() }
        assertTrue(
            "on ${screen.name}, ${unnamed.size} control(s) have nothing a screen reader can say.\n" +
                "Each is operable and anonymous:\n" +
                unnamed.joinToString("\n") {
                    "  %s at %.0f×%.0f dp".format(it.label, it.widthDp, it.heightDp)
                },
            unnamed.isEmpty(),
        )
    }

    private fun assertEveryChoiceAnnouncesItself(screen: AuditedInterface.Screen) {
        // Only the roles that mean "one of several". A plain Button has no chosen state to report,
        // and demanding one would push meaningless semantics onto every action in the app.
        val mute = AuditedInterface.probe(screen)
            .filter { it.role in CHOOSING_ROLES && !it.selectable }
        assertTrue(
            "on ${screen.name} these choices never say whether they are the chosen one, " +
                "so a row of them reads as identical buttons:\n" +
                mute.joinToString("\n") { "  ${it.label} (${it.role})" },
            mute.isEmpty(),
        )
    }

    @Test
    fun `every control on the home screen speaks`() =
        assertEveryControlSpeaks(AuditedInterface.homeScreen())

    @Test
    fun `every control on the editor chrome speaks`() =
        assertEveryControlSpeaks(AuditedInterface.editorChrome())

    @Test
    fun `every control on the text path speaks`() =
        assertEveryControlSpeaks(AuditedInterface.textPath())

    @Test
    fun `every control on the layer panel speaks`() =
        assertEveryControlSpeaks(AuditedInterface.layerPanel())

    @Test
    fun `every control on the colour picker speaks`() =
        assertEveryControlSpeaks(AuditedInterface.colourPicker())

    @Test
    fun `every shared component speaks`() =
        assertEveryControlSpeaks(AuditedInterface.sharedComponents())

    @Test
    fun `a history step speaks`() =
        assertEveryControlSpeaks(AuditedInterface.historyStrip())

    @Test
    fun `every control on every sheet speaks`() {
        // One test for ten sheets rather than ten: the message names the sheet, so a single red
        // test still says exactly where to look.
        for (sheet in AuditedInterface.sheets()) assertEveryControlSpeaks(sheet)
    }

    @Test
    fun `every choice announces whether it is chosen`() {
        for (screen in AuditedInterface.all()) assertEveryChoiceAnnouncesItself(screen)
    }

    private companion object {
        /** Roles that mean "one of several", and therefore carry a state worth announcing. */
        val CHOOSING_ROLES = setOf(
            androidx.compose.ui.semantics.Role.RadioButton.toString(),
            androidx.compose.ui.semantics.Role.Tab.toString(),
            androidx.compose.ui.semantics.Role.Checkbox.toString(),
            androidx.compose.ui.semantics.Role.Switch.toString(),
        )
    }
}
