package ir.pixellab.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
 */
@Composable
fun SheetSection(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = Ink.TextMuted,
        modifier = modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp),
    )
}

@Composable
fun SheetHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Ink.TextMuted,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
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
        modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
fun SheetChip(
    label: String,
    chosen: Boolean = false,
    enabled: Boolean = true,
    tint: Color = Ink.Accent,
    onClick: () -> Unit,
) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = when {
            !enabled -> Ink.Divider
            chosen -> tint
            else -> Ink.Text
        },
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (chosen) tint.copy(alpha = CHOSEN_TINT) else Ink.Chrome)
            .clickable(enabled = enabled, onClick = onClick)
            // The platform's 48dp minimum in spirit: a mis-tap on a canvas costs an undo.
            .padding(horizontal = 12.dp, vertical = 9.dp)
            // A screen reader announces a chip as a button and reads its label; without this it
            // cannot say whether the chip is the *chosen* one, which is the only thing that
            // distinguishes the six chips in a row from each other.
            .semantics {
                role = Role.RadioButton
                selected = chosen
                if (!enabled) disabled()
            },
    )
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
    Column(modifier.padding(horizontal = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Ink.TextMuted)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = Ink.Text, fontSize = MaterialTheme.typography.bodyLarge.fontSize),
            cursorBrush = SolidColor(Ink.Accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            // The visible label is a separate Text, so the field itself would otherwise be
            // announced as an unnamed edit box.
            modifier = Modifier
                .semantics { contentDescription = label }
                .clip(RoundedCornerShape(8.dp))
                .background(Ink.ChromeSunken)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

/** A full-width action. Used where a press *does* something rather than choosing a mode. */
@Composable
fun SheetAction(
    label: String,
    enabled: Boolean = true,
    tint: Color = Ink.Accent,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) tint.copy(alpha = CHOSEN_TINT) else Ink.Chrome)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp)
            .semantics {
                role = Role.Button
                // Announced as unavailable rather than simply not responding, which is what a
                // greyed control that only *looks* greyed sounds like.
                if (!enabled) disabled()
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) tint else Ink.Divider,
        )
    }
}

private const val CHOSEN_TINT = 0.18f
