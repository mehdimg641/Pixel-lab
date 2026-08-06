package ir.pixellab.app

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
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
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import ir.pixellab.core.editor.EditorState
import ir.pixellab.core.editor.SheetContent
import ir.pixellab.core.editor.TextSection
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Effect
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.FontRef
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId
import ir.pixellab.core.model.TextSpec

/**
 * The interface, rendered once and described as plain data, so more than one thing can be asked of it.
 *
 * Two audits share this: [TouchTargetTest] asks whether a control is big enough for a finger, and
 * [ScreenReaderTest] asks whether it says what it is. Both need the same expensive thing — the real
 * screens, composed right-to-left and laid out — and both would otherwise carry their own copy of
 * six screen definitions that drift apart the first time a signature changes.
 *
 * Nodes are flattened into [Probe] **inside** the composition, because a [SemanticsNode] is a handle
 * into a tree that is disposed when the block ends. What survives is a description, which is all
 * either audit needs.
 */
@OptIn(ExperimentalTestApi::class)
internal object AuditedInterface {

    /**
     * One interactive node, as it was laid out.
     *
     * [name] is null when the control has nothing a screen reader could announce — no click label,
     * no content description, no text of its own. That is the whole subject of [ScreenReaderTest],
     * so it is kept as a genuine null rather than defaulted to a placeholder.
     */
    data class Probe(
        val name: String?,
        val role: String?,
        val widthDp: Float,
        val heightDp: Float,
        val clickable: Boolean,
        /**
         * Whether the control announces which state it is in — by either of the two ways there are.
         *
         * A radio button or a tab carries `Selected`; a switch or a checkbox carries a
         * `ToggleableState`. They are different properties for a real reason — a checkbox has three
         * states and a radio button has two — and a test that demanded only the first reported a
         * correctly-announced switch as silent.
         */
        val selectable: Boolean,
    ) {
        /** For failure messages, where an unnamed control still has to be pointed at somehow. */
        val label: String get() = name ?: role?.let { "an unnamed $it" } ?: "an unnamed control"
    }

    /** A screen worth auditing, named for the failure message. */
    data class Screen(val name: String, val content: @Composable () -> Unit)

    /**
     * Anything a finger is meant to land on.
     *
     * A click action *or* an interactive role: the role alone catches a control whose press is
     * handled by a gesture detector, and the action alone catches one that never declared a role.
     * Either is enough to mean somebody is expected to operate it.
     */
    private val interactive = SemanticsMatcher("is an interactive control") { node ->
        node.config.contains(SemanticsActions.OnClick) ||
            node.config.getOrNull(SemanticsProperties.Role) in INTERACTIVE_ROLES
    }

    /**
     * Composes right-to-left, lays out, and describes every interactive control.
     *
     * Right-to-left because the interface is Persian, and a control measured in the mirror of its
     * real layout is not the control the user reaches for.
     *
     * ### The line that makes this work at all
     *
     * `mainClock.autoAdvance = false`, set before anything is composed. Without it these audits
     * throw `AppNotIdleException` — "did not get idle after 2,689,774 attempts in 60 SECONDS" — on
     * compositions that are in fact laid out and correct. With the clock auto-advancing,
     * `waitForIdle` also waits on the frame clock's *awaiters*, and anything in the tree that keeps
     * asking for frames holds the environment un-idle forever. Nothing here needs frames to
     * advance: layout has already happened by the time `waitForIdle` returns, and layout is the
     * entire subject.
     *
     * It is worth recording how that was found, because two plausible readings were both wrong. The
     * failure appeared only inside `./gradlew build` and never when the module's tests were run
     * alone, which reads exactly like a machine-load flake — it is not: serialising the build to a
     * single worker reproduced it unchanged. It also survived stopping the view model's autosave
     * timer and its startup font scan, both of which looked like plausible sources of pending work.
     * What actually separates the two runs is `assembleDebug`: once the app's merged resources
     * exist, something in the real tree asks for frames, and the clock obliges forever.
     */
    fun probe(screen: Screen, dark: Boolean = true): List<Probe> {
        var found = emptyList<Probe>()
        runComposeUiTest {
            mainClock.autoAdvance = false
            setContent {
                PixelLabTheme(dark = dark) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        screen.content()
                    }
                }
            }
            waitForIdle()
            Shadows.idleMainLooper()

            found = onAllNodes(interactive, useUnmergedTree = false)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)
                .map { node ->
                    Probe(
                        name = node.config.getOrNull(SemanticsActions.OnClick)?.label
                            ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
                            ?: node.config.getOrNull(SemanticsProperties.Text)?.firstOrNull()?.text,
                        role = node.config.getOrNull(SemanticsProperties.Role)?.toString(),
                        widthDp = node.size.width / density.density,
                        heightDp = node.size.height / density.density,
                        clickable = node.config.contains(SemanticsActions.OnClick),
                        selectable = node.config.contains(SemanticsProperties.Selected) ||
                            node.config.contains(SemanticsProperties.ToggleableState),
                    )
                }
        }
        return found
    }

    // ---- the screens ------------------------------------------------------------------------

    /**
     * A view model with its recovery timer switched off.
     *
     * `EditorViewModel.init` starts `AutoSave`, whose loop is `while (isActive) { delay(minutes) }`,
     * so constructing one leaves a task scheduled on the main looper that never comes due. Whether
     * that on its own can stall the idling check is not something these audits proved — the hang
     * they actually had is explained on [probe]. But a test that leaves a timer running in a sandbox
     * shared with every other test in the module is borrowing trouble from whatever runs next.
     */
    private fun editor(): EditorViewModel =
        EditorViewModel(ApplicationProvider.getApplicationContext())
            .also { it.autoSave.stop() }

    fun homeScreen() = Screen("the home screen") {
        HomeScreen(projects = emptyList(), onNew = {}, onOpen = {}, onQuickAction = {}, onSettings = {})
    }

    /**
     * The screen that matters: the top bar, the selection card, the ribbon and the dock are where
     * the user spends the whole session.
     */
    fun editorChrome(): Screen {
        val model = editor()
        model.act { select(state.document.layers.first().id) }
        return Screen("the editor chrome") {
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
        }
    }

    /**
     * The loop the application exists for: place a word, then reach for the font, the colour, the
     * shadow. Its own screen because the ribbon under the canvas *changes with the selection* — with
     * a photograph selected it shows different controls entirely.
     */
    fun textPath(): Screen {
        val model = editor()
        // The layer is built here rather than through `addTextLayer`, which needs a scanned font
        // catalogue, and the scan finds nothing in this environment. The controls being audited read
        // the *spec*, not the resolved face, so this is the same layer as far as the ribbon is
        // concerned.
        val text = LayerId("text-under-audit")
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
        return Screen("the text path") {
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
        }
    }

    fun layerPanel(): Screen {
        val model = editor()
        model.act { select(state.document.layers.first().id) }
        return Screen("the layer panel") {
            Column(Modifier.fillMaxSize().background(Ink.ChromeRaised)) {
                LayerPanel(model.state, model)
            }
        }
    }

    /**
     * The strip is empty until something has happened, so it needs edits rather than selections: the
     * view model records a step only when the editor's undo depth actually moved.
     */
    fun historyStrip(): Screen {
        val model = editor()
        val first = model.state.document.layers.first().id
        repeat(4) { step -> model.act { setLayerOpacity(first, 0.9f - step * 0.1f) } }
        return Screen("the history strip") {
            Column(Modifier.fillMaxWidth().background(Ink.ChromeRaised)) {
                HistoryStrip(model)
            }
        }
    }

    /**
     * The gallery rather than a screen: these are the pieces every sheet is assembled from, so one
     * defect here is the same defect on twenty panels.
     */
    fun sharedComponents() = Screen("the shared components") {
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
    }

    /** The colour picker, which is the panel a user reaches for before any other. */
    fun colourPicker() = Screen("the colour picker") {
        var colour by remember { mutableStateOf(ir.pixellab.core.model.Color(0.85f, 0.30f, 0.45f)) }
        Column(Modifier.fillMaxWidth().background(Ink.ChromeRaised)) {
            ColorPickerBody(color = colour, onChange = { colour = it })
        }
    }

    /**
     * The sheets, each with a photograph selected so the panels have a subject to work on.
     *
     * Ten of these had no accessibility semantics anywhere in the file. A sheet is where the actual
     * work of the application happens — the brush settings, the filters, the adjustments — so an
     * audit that stopped at the chrome would have declared the interface fine while every panel
     * behind it stayed silent.
     */
    fun sheets(): List<Screen> {
        fun sheet(name: String, body: @Composable (EditorState, EditorViewModel) -> Unit): Screen {
            val model = editor()
            model.act { select(state.document.layers.first().id) }
            return Screen(name) {
                // Deliberately taller than any sheet, and that is not cosmetic. A Column hands each
                // child only the height it has left, so a long sheet running past the 891dp window
                // has every control below the fold measured against nothing: a 48dp chip comes back
                // as 35 and the ones after it as zero. Those are artefacts of the harness, not
                // defects, and an audit that reports them teaches everyone to distrust it.
                //
                // A tall box rather than `verticalScroll`, which is the obvious fix and the wrong
                // one: several of these sheets hold a LazyColumn of their own, and wrapping one in
                // a scroller hands it an infinite height, which Compose refuses outright.
                //
                // `requiredHeight`, not `height`: a plain height request is still clamped by the
                // window's own 891dp maximum, so it changed nothing at all.
                Column(Modifier.fillMaxWidth().requiredHeight(TALL).background(Ink.ChromeRaised)) {
                    body(model.state, model)
                }
            }
        }
        return listOf(
            sheet("the brush sheet") { _, model -> BrushSheetBody(model) },
            sheet("the canvas sheet") { state, model -> CanvasSheetBody(state, model, onPickImage = {}) },
            sheet("the filter sheet") { state, model -> FilterSheetBody(state, model) },
            sheet("the adjustment sheet") { state, model -> AdjustmentSheetBody(state, model) },
            sheet("the selection sheet") { state, model -> SelectionSheetBody(state, model) },
            sheet("the retouch sheet") { state, model -> RetouchSheetBody(state, model) },
            sheet("the vector sheet") { state, model -> VectorSheetBody(state, model) },
            sheet("the library sheet") { state, model -> LibrarySheetBody(state, model) },
            sheet("the font picker") { state, model -> FontPickerBody(state, model) },
            sheet("the dimensional sheet") { state, model -> DimensionalSheetBody(state, model) },
            sheet("the type sheet") { state, model -> TypeSheetBody(state, model) },
            sheet("the shape sheet") { state, model -> ShapeSheetBody(state, model) },
            sheet("the guide sheet") { state, model -> GuideSheetBody(state, model) },
            sheet("the portrait sheet") { state, model -> PortraitSheetBody(state, model) },
            sheet("the settings sheet") { state, model -> SettingsSheetBody(state, model) },
            sheet("the arrange sheet") { state, model ->
                ArrangeSheetBody(state, model, render = { null })
            },
            sheet("the collage sheet") { _, model -> CollageSheetBody(model, onPickPhotos = {}) },
            sheet("the layer sheet") { state, model ->
                LayerParametersSheetBody(
                    state,
                    SheetContent.LayerParameters(state.document.layers.first().id),
                    model,
                )
            },
            effectSheet(),
        ) + textStudioSheets()
    }

    /**
     * Every section of the text panel, audited as its own screen.
     *
     * One per section rather than one for the panel, because they share nothing but the strip at the
     * top: a section that hides a 32dp swatch or an unlabelled chip is invisible to a sweep that
     * only ever opens the first one. Fourteen entries generated from the enum, so a section added
     * later is audited without anybody remembering to add it here — which is exactly how the two
     * defects this file was written after got in.
     */
    private fun textStudioSheets(): List<Screen> {
        val model = editor()
        // Built directly rather than through `addTextLayer`, which needs a scanned font catalogue —
        // and the scan finds nothing in this environment, so that route returns null and every
        // section below would be silently skipped. A screen list that quietly shrinks to nothing is
        // the exact shape of the gap these audits exist to close, so it is worth the four lines.
        val id = LayerId("text-studio-under-audit")
        model.act {
            addLayer(
                Layer.Text(
                    id = id,
                    spec = TextSpec(text = SENTENCE, font = FontRef(family = "Vazirmatn"), size = 96f),
                    name = SENTENCE,
                ),
            )
        }
        model.act { select(id) }
        // Aimed at one word, so the sections that can be aimed are audited in the state that has
        // the most controls on screen rather than the fewest.
        model.act { selectTextRange(SENTENCE.indexOf(WORD) until SENTENCE.length) }
        check(model.state.activeTextRange != null) { "the text range did not take, so the target bar is unaudited" }
        return TextSection.entries.map { section ->
            Screen("the text studio · ${section.name.lowercase()}") {
                Column(Modifier.fillMaxWidth().requiredHeight(TALL).background(Ink.ChromeRaised)) {
                    TextStudioBody(model.state, SheetContent.TextStudio(id, section), model)
                }
            }
        }
    }

    /**
     * The panel behind every layer effect, and the one that was missed.
     *
     * It needs a layer with an effect actually on it, which is why it cannot be built by the little
     * helper above. Worth the extra lines: this is where two of the defects the audits were supposed
     * to catch had been sitting — a fifth private chip and a third private swatch row — precisely
     * because the audit listed its screens by hand and this one was not on the list.
     */
    private fun effectSheet(): Screen {
        val model = editor()
        val layer = model.state.document.layers.first().id
        model.act { select(layer) }
        model.act { addEffect(layer, Effect.Stroke(6f, Fill.Solid(Color.BLACK))) }
        return Screen("the effect sheet") {
            Column(Modifier.fillMaxWidth().requiredHeight(TALL).background(Ink.ChromeRaised)) {
                ParameterSheetBody(
                    model.state,
                    SheetContent.EffectParameters(layer, effectIndex = 0),
                    model,
                )
            }
        }
    }

    /** Every screen above, for an audit that should sweep rather than name one. */
    fun all(): List<Screen> = listOf(
        homeScreen(),
        editorChrome(),
        textPath(),
        layerPanel(),
        historyStrip(),
        sharedComponents(),
        colourPicker(),
    ) + sheets()

    /**
     * Room enough that no audited sheet runs out of it.
     *
     * Twenty metres of it, which sounds absurd and is not: the filter sheet alone is over four,
     * and running out is silent — the controls past the end simply report zero and read as defects.
     */
    /** The sentence the per-range feature was asked for, so the audit runs on the real case. */
    private const val SENTENCE = "برای اطلاع از قیمت کابینت"
    private const val WORD = "کابینت"

    private val TALL = 20_000.dp

    private object Shadows {
        fun idleMainLooper() =
            org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private val INTERACTIVE_ROLES = setOf(
        androidx.compose.ui.semantics.Role.Button,
        androidx.compose.ui.semantics.Role.Checkbox,
        androidx.compose.ui.semantics.Role.RadioButton,
        androidx.compose.ui.semantics.Role.Switch,
        androidx.compose.ui.semantics.Role.Tab,
    )
}
