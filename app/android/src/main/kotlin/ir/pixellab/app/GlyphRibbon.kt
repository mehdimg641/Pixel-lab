package ir.pixellab.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ir.pixellab.core.text.ArabicJoining
import ir.pixellab.core.text.Clusters
import ir.pixellab.core.text.Granularity
import ir.pixellab.core.text.TextCluster
import kotlin.math.roundToInt

/**
 * The glyph ribbon — one chip per connected cluster.
 *
 * The specification's interaction idea ۳, and the interface half of the rule in §۶.۹: the unit a
 * Persian word comes apart at is the connected cluster, not the character. «سلام» is four characters
 * and two pieces, and every tool that offers per-letter effects on Persian gets this wrong — which is
 * why Persian is not used in them.
 *
 * The ribbon makes that unit visible and touchable. Each chip shows a piece of the word as the script
 * actually draws it; tapping one selects it, and **dragging its edge stretches it with a kashida**.
 * That gesture is the point: elongating a letterform is how Persian type is balanced, and until now
 * it has meant typing U+0640 by hand into the middle of a word.
 *
 * A chip that cannot stretch does not offer a handle. A lone «و» has no join to pull and neither does
 * an English word, and a handle that did nothing when dragged would be worse than none.
 */
@Composable
fun GlyphRibbon(
    text: String,
    modifier: Modifier = Modifier,
    granularity: Granularity = Granularity.CLUSTER,
    selected: Int? = null,
    onSelect: (Int) -> Unit,
    onStretch: (cluster: TextCluster, amount: Int) -> Unit,
) {
    // Recomputed only when the text or the granularity changes. Splitting is cheap, but it happens
    // under a drag, and a re-split per frame would also rebuild every chip's identity mid-gesture.
    val clusters = remember(text, granularity) { Clusters.of(text, granularity) }
    if (clusters.isEmpty()) return

    Row(
        modifier
            .fillMaxWidth()
            .height(Frame.ribbon)
            .background(Ink.Chrome)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Space.small),
        horizontalArrangement = Arrangement.spacedBy(Space.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        clusters.forEachIndexed { index, cluster ->
            // A half-space is a piece — it is what split the word — but it is not a thing to press,
            // and it draws nothing, so as a chip it was an empty pill that looked like a fault.
            //
            // Shown as a mark instead, which is also the specification asking for it: interaction
            // idea ۸ wants the hidden characters of Persian visible as fine amber marks while text
            // is being edited. This is that, in the one place a writer is already looking at how
            // their word comes apart.
            if (cluster.text == ArabicJoining.ZWNJ.toString()) {
                JoinerMark()
                return@forEachIndexed
            }
            // Whitespace separates and is not a piece to press either. Shown as a gap, which is
            // what it is.
            if (cluster.text.isBlank()) {
                Box(Modifier.width(Space.medium))
                return@forEachIndexed
            }
            ClusterChip(
                cluster = cluster,
                chosen = index == selected,
                onSelect = { onSelect(index) },
                onStretch = { amount -> onStretch(cluster, amount) },
            )
        }
    }
}

/**
 * The half-space, drawn.
 *
 * A short amber stroke rather than a character, because U+200C has no glyph — it is an instruction
 * to the shaper, and the whole difficulty of proof-reading Persian is that the most consequential
 * character in a word is invisible. Marking it is what lets a writer see at a glance that «می‌رود»
 * carries one and «میرود» does not.
 */
@Composable
private fun JoinerMark() {
    Box(
        Modifier
            .size(width = Space.medium, height = Space.touch)
            .semantics { contentDescription = "نیم‌فاصله" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = 2.dp, height = MARK)
                .clip(Corners.chip)
                .background(Ink.Accent),
        )
    }
}

/**
 * One cluster.
 *
 * The chip draws the piece in the interface font rather than the user's chosen one. That is a
 * deliberate limitation and worth stating: the ribbon's job is to say *which piece is which*, and
 * Vazirmatn shapes every Persian cluster correctly, whereas a display face chosen for a cover may
 * have no glyph for a fragment shown out of context.
 */
@Composable
private fun ClusterChip(
    cluster: TextCluster,
    chosen: Boolean,
    onSelect: () -> Unit,
    onStretch: (Int) -> Unit,
) {
    Row(
        Modifier
            .heightIn(min = Space.touch)
            .clip(Corners.chip)
            .background(if (chosen) Ink.AccentSoft else Color.Transparent)
            .border(
                width = if (chosen) 1.5.dp else 1.dp,
                color = if (chosen) Ink.Accent else Ink.Outline,
                shape = Corners.chip,
            )
            .clickable(onClick = onSelect)
            .padding(start = Space.medium, end = if (cluster.stretchable) Space.tight else Space.medium)
            .semantics {
                role = Role.RadioButton
                this.selected = chosen
                contentDescription = cluster.text
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.small),
    ) {
        Text(
            cluster.text,
            style = MaterialTheme.typography.titleLarge,
            color = if (chosen) Ink.Accent else Ink.Text,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        if (cluster.stretchable) StretchHandle(chosen) { onStretch(it) }
    }
}

/**
 * The handle that pulls a kashida out of a cluster.
 *
 * A drag rather than a slider, because the thing being controlled is a *length* and the finger is
 * already on it. The specification asks for exactly this — "dragging the edge of a chip adds a
 * kashida" — and it is the interaction that turns a typographic feature nobody can reach into one
 * anybody can use.
 *
 * The gesture reports whole kashida counts rather than a continuous value, and that is honest about
 * the mechanism: inserting U+0640 is discrete. Where the font has a `KASH` axis the planner uses it
 * instead and the result is continuous, but this control cannot promise that for every font.
 */
@Composable
private fun StretchHandle(chosen: Boolean, onStretch: (Int) -> Unit) {
    val step = with(LocalDensity.current) { STEP.toPx() }
    var travelled by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    Box(
        Modifier
            .size(width = HANDLE, height = Space.touch)
            .pointerInput(step) {
                awaitEachGesture {
                    awaitFirstDown()
                    dragging = true
                    travelled = 0f
                    var emitted = 0
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        change.consume()
                        // Towards the leading edge lengthens. The interface is right-to-left, so
                        // that is *leftwards*, and using the raw sign here would stretch a word by
                        // dragging it the way the reader's eye does not travel.
                        travelled -= change.position.x - change.previousPosition.x
                        val wanted = (travelled / step).roundToInt().coerceAtLeast(0)
                        if (wanted != emitted) {
                            onStretch(wanted - emitted)
                            emitted = wanted
                        }
                    }
                    dragging = false
                    travelled = 0f
                }
            }
            .semantics { contentDescription = "کشیده" },
        contentAlignment = Alignment.Center,
    ) {
        // Two short bars, the universal shape for a drag handle, in the accent when the chip is
        // chosen so the target the finger is aiming for is the one the eye already found.
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            val tone = when {
                dragging -> Ink.Accent
                chosen -> Ink.Accent
                else -> Ink.TextMuted
            }
            repeat(2) {
                Box(
                    Modifier
                        .size(width = 2.dp, height = GRIP)
                        .clip(Corners.chip)
                        .background(tone),
                )
            }
        }
    }
}

/** How far the finger travels for one kashida. Roughly a letter's width at ribbon scale. */
private val STEP = 24.dp

/**
 * The drag handle's target. Square at [Space.touch], where it was 28dp wide.
 *
 * A target is two-dimensional and the narrow axis is the one that fails: this handle sits at the
 * edge of a chip in a horizontally scrolling ribbon, so 28dp of width meant a thumb reaching for
 * the kashida grabbed the chip beside it and selected a different cluster instead.
 */
private val HANDLE = Space.touch

/** The two bars *drawn* inside the handle. Deliberately smaller than the target that carries them. */
private val GRIP = 18.dp

/** The half-space mark. Shorter than a chip, so it reads as punctuation rather than as a piece. */
private val MARK = 14.dp

/**
 * The ribbon's state, kept beside the editor rather than inside it.
 *
 * Which chip is chosen is a property of the *ribbon*, not of the document: closing the ribbon and
 * reopening it should not restore a selection the user has since moved past, and undoing an edit
 * should not resurrect one. Keeping it here is what makes both of those true without either being
 * written down as a special case.
 */
class RibbonState {
    var granularity: Granularity by mutableStateOf(Granularity.CLUSTER)
        private set

    var chosen: Int? by mutableStateOf(null)
        private set

    fun choose(index: Int) {
        chosen = if (chosen == index) null else index
    }

    fun showAt(next: Granularity) {
        granularity = next
        // The index meant a piece at the old granularity and means a different one at the new. Any
        // attempt to carry it across would silently move the selection to another part of the word.
        chosen = null
    }

    fun clear() {
        chosen = null
    }
}

/**
 * The granularity picker that sits above the ribbon.
 *
 * Four levels rather than the specification's five: `ALL` is not offered, because selecting "the
 * whole text" is what the layer itself already is and a chip for it would do nothing the canvas does
 * not already do.
 */
@Composable
fun GranularityPicker(state: RibbonState, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.small),
        horizontalArrangement = Arrangement.spacedBy(Space.small),
    ) {
        for (level in PICKABLE) {
            Pill(
                label = level.label,
                selected = state.granularity == level,
                onClick = { state.showAt(level) },
            )
        }
    }
}

private val PICKABLE = listOf(
    Granularity.LINE,
    Granularity.WORD,
    Granularity.CLUSTER,
    Granularity.CHARACTER,
)

/**
 * The Persian names.
 *
 * «خوشه» for a cluster is the specification's own word and the right one — a Persian typographer
 * recognises it, where a transliteration of "cluster" would mean nothing.
 */
private val Granularity.label: String
    get() = when (this) {
        Granularity.ALL -> "همه"
        Granularity.LINE -> "خط"
        Granularity.WORD -> "کلمه"
        Granularity.CLUSTER -> "خوشه"
        Granularity.CHARACTER -> "حرف"
    }

/** The whole panel: the picker, the ribbon, and a line saying what the chosen chip can do. */
@Composable
fun GlyphRibbonPanel(
    text: String,
    state: RibbonState,
    modifier: Modifier = Modifier,
    onStretch: (cluster: TextCluster, amount: Int) -> Unit,
) {
    val clusters = remember(text, state.granularity) { Clusters.of(text, state.granularity) }
    Column(modifier.fillMaxWidth().background(Ink.Chrome)) {
        GranularityPicker(state)
        GlyphRibbon(
            text = text,
            granularity = state.granularity,
            selected = state.chosen,
            onSelect = state::choose,
            onStretch = onStretch,
        )
        val chosen = state.chosen?.let { clusters.getOrNull(it) }
        SheetHint(
            when {
                chosen == null -> "روی یک خوشه بزنید تا انتخاب شود"
                chosen.stretchable -> "دستگیرهٔ کنار خوشه را بکشید تا کشیده اضافه شود"
                // Said rather than left to be discovered by a drag that does nothing.
                else -> "این خوشه جای کشیده ندارد"
            },
        )
    }
}
