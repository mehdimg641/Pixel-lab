package ir.pixellab.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ir.pixellab.core.render.ParameterSpec

/**
 * The controls every sheet is built from.
 *
 * Shared rather than copied into each sheet, which is what the last few of them did. Two sheets with
 * their own private chip drift apart within a week — one gets a pressed state, the other does not —
 * and the interface starts feeling assembled rather than designed.
 *
 * Everything here now draws through [Ink], [Space] and [Corners]. That is the whole reason twenty
 * sheets could be restyled without opening twenty files: a sheet names a *control*, and the control
 * names a token. The version this replaced had each of these functions carrying its own literal
 * dimensions, which is why the interface read as grey and cramped no matter which panel you opened.
 */
@Composable
fun SheetSection(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        // Uppercase-ish emphasis is unavailable in Persian — the script has no case — so the
        // heading separates itself from its controls by colour and by the space above it instead.
        color = Ink.TextMuted,
        modifier = modifier.padding(
            start = Space.gutter,
            end = Space.gutter,
            top = Space.wide,
            bottom = Space.tight,
        ),
    )
}

@Composable
fun SheetHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Ink.TextMuted,
        modifier = modifier.padding(horizontal = Space.gutter, vertical = Space.tight),
    )
}

/**
 * A row of chips that wraps.
 *
 * Wrapping rather than scrolling sideways: a horizontal scroller hides its own contents, and a user
 * who cannot see that there are twenty-seven blend modes will never find the one they want.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SheetChips(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(
        modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.tight),
        horizontalArrangement = Arrangement.spacedBy(Space.small),
        verticalArrangement = Arrangement.spacedBy(Space.small),
    ) { content() }
}

/**
 * One choice among several.
 *
 * Outlined when unchosen and tinted when chosen, rather than two greys one step apart. The two-grey
 * arrangement is what the previous interface used, and in a row of six the user could not tell which
 * one was on without moving their head — an outline against a fill is unambiguous at arm's length.
 */
@Composable
fun SheetChip(
    label: String,
    chosen: Boolean = false,
    enabled: Boolean = true,
    tint: Color = Ink.Accent,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .heightIn(min = CHIP_HEIGHT)
            .clip(Corners.chip)
            .background(if (chosen) tint.copy(alpha = CHOSEN_TINT) else Color.Transparent)
            .border(
                width = if (chosen) CHOSEN_EDGE else PLAIN_EDGE,
                color = when {
                    !enabled -> Ink.Divider
                    chosen -> tint
                    else -> Ink.Outline
                },
                shape = Corners.chip,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Space.large, vertical = Space.small)
            // A screen reader announces a chip as a button and reads its label; without this it
            // cannot say whether the chip is the *chosen* one, which is the only thing that
            // distinguishes the six chips in a row from each other.
            .semantics {
                role = Role.RadioButton
                selected = chosen
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                !enabled -> Ink.TextDisabled
                chosen -> tint
                else -> Ink.Text
            },
            maxLines = 1,
        )
    }
}

/** A labelled slider over a plain range, for the many controls that are not effect parameters. */
@Composable
fun SheetSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (value: Float, continuous: Boolean) -> Unit,
    onCommit: () -> Unit = {},
) {
    PrecisionSlider(
        spec = ParameterSpec.Slider(key = label, label = label, range = range, default = value),
        value = value,
        onChange = onChange,
        onCommit = onCommit,
    )
}

/**
 * A number the user types.
 *
 * A slider is wrong for a canvas dimension: the useful values span four orders of magnitude and the
 * user almost always has an exact one in mind, taken from wherever the artwork is going.
 */
@Composable
fun SheetNumberField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
) {
    Column(
        modifier.padding(horizontal = Space.tight),
        verticalArrangement = Arrangement.spacedBy(Space.tight),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Ink.TextMuted)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(
                color = Ink.Text,
                fontSize = MaterialTheme.typography.bodyLarge.fontSize,
            ),
            cursorBrush = SolidColor(Ink.Accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            // The visible label is a separate Text, so the field itself would otherwise be
            // announced as an unnamed edit box.
            modifier = Modifier
                .semantics { contentDescription = label }
                .clip(Corners.small)
                .background(Ink.ChromeSunken)
                // Sunken rather than outlined, and the outline is on top of the sink: a well with
                // no edge disappears into a card of nearly the same value, which is what made the
                // old fields hard to find on a sheet.
                .border(PLAIN_EDGE, Ink.Divider, Corners.small)
                .padding(horizontal = Space.medium, vertical = Space.medium),
        )
    }
}

/**
 * A full-width action. Used where a press *does* something rather than choosing a mode.
 *
 * Tinted rather than gradient-filled: the gradient belongs to [PrimaryAction], which is the one
 * action a *screen* is about. A sheet has several of these and none of them is the app's headline.
 */
@Composable
fun SheetAction(
    label: String,
    enabled: Boolean = true,
    tint: Color = Ink.Accent,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter, vertical = Space.tight)
            .heightIn(min = Space.touch)
            .clip(Corners.chip)
            .background(if (enabled) tint.copy(alpha = CHOSEN_TINT) else Color.Transparent)
            .border(PLAIN_EDGE, if (enabled) tint.copy(alpha = EDGE_TINT) else Ink.Divider, Corners.chip)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Space.large, vertical = Space.small)
            .semantics {
                role = Role.Button
                // Announced as unavailable rather than simply not responding, which is what a
                // greyed control that only *looks* greyed sounds like.
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) tint else Ink.TextDisabled,
        )
    }
}

/** How much of the accent a chosen chip's fill carries. Low: the label has to stay readable on it. */
private const val CHOSEN_TINT = 0.16f

/** The same colour at the strength an edge needs, which is more than a fill does. */
private const val EDGE_TINT = 0.55f

private val PLAIN_EDGE = 1.dp
private val CHOSEN_EDGE = 1.5.dp

/** Comfortably inside the platform's touch minimum once the row's own spacing is counted. */
private val CHIP_HEIGHT = 40.dp
