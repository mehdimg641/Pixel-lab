package ir.pixellab.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * The shared vocabulary every screen is built from.
 *
 * A screen may only use these. The interface this replaces had each panel inventing its own row — a
 * `Text` with a background here, a `Row` with different padding there — which is why it read as
 * unfinished even though every individual piece was reasonable.
 *
 * Two rules from the specification (§۶) bind every component below and are worth stating once
 * rather than repeating in each: **no decorative gradient and no coloured icon**, and **no value
 * written here that is not a token**. The first is why the primary action is a flat amber rather
 * than the wash it used to be — a gradient on a control the user presses fifty times a day is
 * decoration, and the specification's own reason for banning it is that the canvas has to be the
 * brightest and most interesting thing on the screen.
 */

/**
 * A raised surface holding related controls.
 *
 * Nothing separates sections but the gap between cards, which is why the gap is generous: on a dark
 * ground, space is a stronger divider than a line and does not add another edge for the eye to trip
 * over.
 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(Space.large),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(Corners.card)
            .background(Ink.ChromeRaised)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(Space.small),
        content = content,
    )
}

/**
 * A big round icon with a word under it.
 *
 * The icon sits inside a filled circle rather than bare. A bare icon on a dark ground has no target
 * to aim at and no state to show; the circle gives it both, and is what makes a row of these read as
 * *buttons* rather than as decoration.
 */
@Composable
fun IconTile(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tint = when {
        !enabled -> Ink.TextDisabled
        selected -> Ink.Accent
        else -> Ink.Text
    }
    Column(
        modifier
            .widthIn(min = TILE)
            .clip(Corners.card)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = Space.small)
            .semantics {
                role = Role.Tab
                this.selected = selected
                if (!enabled) disabled()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.small),
    ) {
        Box(
            Modifier
                .size(TILE_CIRCLE)
                .clip(Corners.chip)
                .background(if (selected) Ink.AccentSoft else Ink.ChromeRaised)
                // The fill alone is one step from the ground, which is enough on a dark screen and
                // nothing at all on a card of the same value. The edge is what makes the tile a
                // target wherever it is put — and "wherever it is put" is what a component means.
                .border(EDGE, if (selected) Ink.Accent else Ink.Outline, Corners.chip),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(Frame.icon))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Ink.Accent else Ink.TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A pill. Chooses one of a set, or toggles one thing.
 *
 * Outlined when unselected rather than filled with a slightly different grey. Two greys a step apart
 * is the arrangement that leaves a user unsure which chip is on — the previous interface's exact
 * problem — while an outline versus a tint is unambiguous at a glance and in a photograph.
 */
@Composable
fun Pill(
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    tint: Color = Ink.Accent,
    onClick: () -> Unit,
) {
    Box(
        modifier
            // Both axes. Constraining only the height leaves the width to the label, so a pill
            // reading «۱» came out under the minimum on the axis nobody had thought about.
            .sizeIn(minWidth = PILL_HEIGHT, minHeight = PILL_HEIGHT)
            .clip(Corners.chip)
            .background(if (selected) Ink.AccentSoft else Color.Transparent)
            .border(
                width = if (selected) EDGE_CHOSEN else EDGE,
                color = when {
                    !enabled -> Ink.Divider
                    selected -> tint
                    else -> Ink.Outline
                },
                shape = Corners.chip,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Space.large, vertical = Space.small)
            .semantics {
                role = Role.RadioButton
                this.selected = selected
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                !enabled -> Ink.TextDisabled
                selected -> tint
                else -> Ink.Text
            },
            maxLines = 1,
        )
    }
}

/**
 * The one action a screen is about.
 *
 * Flat amber, not a gradient: the specification bans decorative gradients, and this control is
 * exactly where the temptation to add one is strongest. What it gains from being flat is that the
 * accent now means precisely one thing everywhere it appears — the same amber on a chosen chip, an
 * active tool and this button — which is the whole value of having a single accent.
 */
@Composable
fun PrimaryAction(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(Space.touch)
            .clip(Corners.button)
            .background(if (enabled) Ink.Accent else Ink.ChromeRaised)
            // Disabled keeps the shape. A filled action that loses both its colour and its edge
            // stops looking like a control at all, and a user reads that as a rendering fault
            // rather than as "not yet".
            .border(EDGE, if (enabled) Color.Transparent else Ink.Outline, Corners.button)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics {
                role = Role.Button
                if (!enabled) disabled()
            },
        horizontalArrangement = Arrangement.spacedBy(Space.small, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (enabled) Ink.OnAccent else Ink.TextDisabled,
                modifier = Modifier.size(Frame.icon),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = if (enabled) Ink.OnAccent else Ink.TextDisabled,
        )
    }
}

/** The quieter sibling: a full-width action that is not the screen's point. */
@Composable
fun SecondaryAction(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Ink.Accent,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = Space.touch)
            .clip(Corners.button)
            .border(EDGE, if (enabled) Ink.Outline else Ink.Divider, Corners.button)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Space.large),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) tint else Ink.TextDisabled,
        )
    }
}

/**
 * A heading, with an optional action on the far side.
 *
 * The action sits on the heading's row rather than under the content it belongs to, which is the
 * reason a long list stays scannable: every "see all" is in the same place down the page.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(top = Space.wide, bottom = Space.small),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Ink.Text)
        if (action != null && onAction != null) {
            // A Box around the label rather than padding on it. Padding sized this target by the
            // *type* it holds — labelLarge plus 8dp above and below came to 37dp, and it would have
            // come to something else the next time the type scale moved. A target that is a
            // consequence of a font size is a target nobody chose.
            Box(
                Modifier
                    .sizeIn(minWidth = Space.touch, minHeight = Space.touch)
                    .clip(Corners.button)
                    .clickable(onClick = onAction)
                    .padding(horizontal = Space.medium, vertical = Space.small)
                    .semantics { role = Role.Button },
                contentAlignment = Alignment.Center,
            ) {
                Text(action, style = MaterialTheme.typography.labelLarge, color = Ink.Accent)
            }
        }
    }
}

/** A line of explanation. Quiet by default, and coloured when it is a warning worth reading. */
@Composable
fun Note(text: String, modifier: Modifier = Modifier, tone: Color = Ink.TextMuted) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = tone,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * A number the interface reports rather than a word.
 *
 * Always through this, never a plain `Text`: it carries `tnum`, which is what stops a live readout
 * from shifting sideways under the finger that is changing it.
 */
@Composable
fun Numeric(value: String, modifier: Modifier = Modifier, tone: Color = Ink.TextMuted) {
    Text(value, style = NumericStyle, color = tone, maxLines = 1, modifier = modifier)
}

/**
 * An icon with its name under it, for a ribbon or a selection bar.
 *
 * Labelled, unlike [BarIcon]. Six unnamed glyphs is what a bar becomes once it holds more than
 * delete and duplicate — "a plus" and "a slider" are not guesses anybody makes correctly — and the
 * line of type costs less than the tap that undoes the wrong one.
 */
@Composable
fun BarAction(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    tint: Color = Ink.Text,
    onClick: () -> Unit,
) {
    val colour = when {
        !enabled -> Ink.TextDisabled
        selected -> Ink.Accent
        else -> tint
    }
    Column(
        modifier
            .widthIn(min = Space.touch)
            .clip(Corners.button)
            .clickable(enabled = enabled, onClick = onClick, onClickLabel = label)
            .padding(horizontal = Space.small, vertical = Space.tight)
            .semantics {
                role = Role.Button
                this.selected = selected
                if (!enabled) disabled()
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.tight),
    ) {
        Icon(icon, contentDescription = null, tint = colour, modifier = Modifier.size(Frame.icon))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = when {
                !enabled -> Ink.TextDisabled
                selected -> Ink.Accent
                else -> Ink.TextMuted
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A small round icon button for a bar. Its label is what a screen reader announces. */
@Composable
fun BarIcon(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Ink.Text,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(Space.touch)
            .clip(Corners.chip)
            .clickable(enabled = enabled, onClick = onClick, onClickLabel = label)
            .semantics {
                contentDescription = label
                role = Role.Button
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) tint else Ink.TextDisabled,
            modifier = Modifier.size(Frame.icon),
        )
    }
}

/**
 * A bar icon that acts while it is held down and undoes itself on release.
 *
 * Separate from [BarIcon] because the gesture is a different promise. A tap is a decision the user
 * has made; a press-and-hold is a *look* — nothing has changed by the time the finger comes up. The
 * before/after compare in every photo app on the reference list works this way, and it works this way
 * because a compare that toggled would leave the app sitting in a state that is not the document.
 *
 * [onRelease] runs whether the finger lifted or the gesture was cancelled, so an interrupted press
 * can never strand the interface showing the wrong picture.
 */
@Composable
fun HeldBarIcon(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    held: Boolean = false,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    Box(
        modifier
            .size(Space.touch)
            .clip(Corners.chip)
            .then(
                if (enabled) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                onPress()
                                tryAwaitRelease()
                                onRelease()
                            },
                        )
                    }
                } else {
                    Modifier
                },
            )
            .semantics {
                contentDescription = label
                role = Role.Button
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = when {
                !enabled -> Ink.TextDisabled
                held -> Ink.Accent
                else -> Ink.Text
            },
            modifier = Modifier.size(Frame.icon),
        )
    }
}

private val TILE = 76.dp
private val TILE_CIRCLE = 56.dp
/** The platform's touch minimum. Was 40dp — see the note on [CHIP_HEIGHT] in SheetControls. */
private val PILL_HEIGHT = Space.touch

private val EDGE = 1.dp
private val EDGE_CHOSEN = 1.5.dp

/**
 * A row of colours to choose from.
 *
 * Shared because there were two of these — the colour picker's starting hues and the brush sheet's
 * palette — and they had drifted into carrying the same three defects independently: 28dp and 32dp
 * circles that took the click themselves, so neither row could be hit reliably; no name on any of
 * them, so the panel a user reaches for first was silent to a screen reader; and a chosen state
 * carried only by a border colour, which is colour as the sole indicator.
 *
 * Fixing that twice was the wrong answer. A swatch row is a component.
 *
 * It wraps rather than scrolling sideways. Eight full-size targets do not fit across a 411dp phone,
 * and the alternatives are all worse: shrinking them is what caused this, and a horizontal scroller
 * hides half the palette behind an edge with nothing to say it is there.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorSwatches(
    swatches: List<Pair<String, ir.pixellab.core.model.Color>>,
    current: ir.pixellab.core.model.Color,
    modifier: Modifier = Modifier,
    onPick: (ir.pixellab.core.model.Color) -> Unit,
) {
    FlowRow(
        modifier.fillMaxWidth(),
        // No gap: the targets sit against each other, and what separates the circles visually is
        // the ten points of empty target around each one. A gap here would be space the finger can
        // land in that belongs to no swatch.
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        for ((name, swatch) in swatches) {
            // Alpha stays out of the comparison: these are hues, and picking one keeps whatever
            // transparency the work already had.
            val here = swatch.r == current.r && swatch.g == current.g && swatch.b == current.b
            Box(
                Modifier
                    .size(Space.touch)
                    .clickable { onPick(swatch) }
                    .semantics {
                        role = Role.RadioButton
                        this.selected = here
                        contentDescription = name
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(SWATCH)
                        .clip(CircleShape)
                        .background(Color(swatch.r, swatch.g, swatch.b, 1f))
                        // The edge does two jobs: it keeps a white swatch visible on a light sheet
                        // and a black one visible on a dark sheet, and at twice the weight in the
                        // accent it carries the chosen state by more than hue.
                        .border(
                            width = if (here) SWATCH_EDGE_CHOSEN else EDGE,
                            color = if (here) Ink.Accent else Ink.Divider,
                            shape = CircleShape,
                        ),
                )
            }
        }
    }
}

/** The circle a swatch *draws*. The target around it is [Space.touch]. */
private val SWATCH = 28.dp
private val SWATCH_EDGE_CHOSEN = 2.5.dp

/**
 * The eight colours a row starts from, each with the word a screen reader says for it.
 *
 * Named in Persian rather than left as hex, because "‎#D92633" is not an answer to "which one is
 * this?". Everyday words, not paint-chart ones: a swatch row is for reaching, and somebody scanning
 * it by ear needs «قرمز», not a shade name they have to decode.
 */
val BASIC_SWATCHES: List<Pair<String, ir.pixellab.core.model.Color>> = listOf(
    "سیاه" to ir.pixellab.core.model.Color.BLACK,
    "خاکستری" to ir.pixellab.core.model.Color(0.5f, 0.5f, 0.5f),
    "سفید" to ir.pixellab.core.model.Color.WHITE,
    "قرمز" to ir.pixellab.core.model.Color(0.85f, 0.15f, 0.2f),
    "نارنجی" to ir.pixellab.core.model.Color(0.95f, 0.6f, 0.1f),
    "زرد" to ir.pixellab.core.model.Color(0.95f, 0.85f, 0.2f),
    "سبز" to ir.pixellab.core.model.Color(0.2f, 0.7f, 0.35f),
    "آبی" to ir.pixellab.core.model.Color(0.2f, 0.5f, 0.9f),
    "بنفش" to ir.pixellab.core.model.Color(0.55f, 0.25f, 0.8f),
)

/**
 * A soft fade over whichever end of a horizontal scroller still has content behind it.
 *
 * A strip that scrolls with no sign of it is read as a strip that has been cut off — a user
 * photographed «خط د…» sliced by the edge of the screen and reasonably reported it as broken
 * layout rather than as more controls. Drawn rather than reserved as space, because an arrow or a
 * gutter costs width on the axis that is already short, and drawn *over* the content via
 * `drawWithContent` so it lands on the chips instead of under them.
 *
 * Driven by the scroll position, so it disappears at each end rather than implying there is always
 * more — a permanent fade is its own small lie.
 *
 * @param background the colour behind the strip; the fade has to end in it or it reads as a shadow.
 */
fun Modifier.edgeFade(scroll: ScrollState, background: Color): Modifier = drawWithContent {
    drawContent()
    val fade = EDGE_FADE.toPx()
    if (scroll.value > 0) {
        drawRect(
            brush = Brush.horizontalGradient(listOf(background, Color.Transparent), endX = fade),
            size = Size(fade, size.height),
        )
    }
    if (scroll.value < scroll.maxValue) {
        drawRect(
            brush = Brush.horizontalGradient(
                listOf(Color.Transparent, background),
                startX = size.width - fade,
                endX = size.width,
            ),
            topLeft = Offset(size.width - fade, 0f),
            size = Size(fade, size.height),
        )
    }
}

/** Wide enough to read as a fade rather than as an edge artefact, narrow enough to hide nothing. */
private val EDGE_FADE = 28.dp
