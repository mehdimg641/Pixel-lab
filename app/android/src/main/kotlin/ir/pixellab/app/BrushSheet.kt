package ir.pixellab.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as UiColor
import androidx.compose.ui.unit.dp
import ir.pixellab.core.model.Color
import ir.pixellab.core.paint.BrushPreset
import ir.pixellab.core.paint.BrushTip
import ir.pixellab.core.render.ParameterSpec

/**
 * The brush sheet.
 *
 * Presets first, then the four controls that get touched constantly. Photoshop's brush panel has
 * twelve groups; putting all of them on a phone would mean none of them is reachable, and the ones
 * below cover what a cover design actually needs. The rest live in the preset.
 *
 * Size and hardness edit the *preset*, not a separate live brush, so the sheet and the tool can
 * never disagree about what is about to be painted.
 */
@Composable
fun BrushSheetBody(model: EditorViewModel, modifier: Modifier = Modifier) {
    val preset = model.paint.preset

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        PresetRow(preset) { model.paint.preset = it }

        BrushSlider("اندازه", preset.size, 1f..400f, ParameterSpec.Slider.Unit.PIXELS) {
            model.paint.preset = preset.copy(size = it)
        }
        val hardness = (preset.tip as? BrushTip.Round)?.hardness
        if (hardness != null) {
            BrushSlider("سختی", hardness, 0f..1f, ParameterSpec.Slider.Unit.PERCENT) {
                model.paint.preset = preset.copy(tip = BrushTip.Round(it.coerceIn(0f, 1f)))
            }
        }
        BrushSlider("جریان", preset.flow, 0.01f..1f, ParameterSpec.Slider.Unit.PERCENT) {
            model.paint.preset = preset.copy(flow = it.coerceIn(0.01f, 1f))
        }
        BrushSlider("شفافیت", preset.opacity, 0.01f..1f, ParameterSpec.Slider.Unit.PERCENT) {
            model.paint.preset = preset.copy(opacity = it.coerceIn(0.01f, 1f))
        }
        BrushSlider("فاصله", preset.spacing, 0.02f..2f, ParameterSpec.Slider.Unit.PERCENT) {
            model.paint.preset = preset.copy(spacing = it.coerceAtLeast(0.02f))
        }
        BrushSlider("نرمی حرکت", preset.smoothing, 0f..0.95f, ParameterSpec.Slider.Unit.PERCENT) {
            model.paint.preset = preset.copy(smoothing = it.coerceIn(0f, 0.95f))
        }

        if (!preset.erase) {
            ColorRow(preset.color) { model.paint.preset = preset.copy(color = it) }
        }
    }
}

@Composable
private fun PresetRow(current: BrushPreset, onPick: (BrushPreset) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (preset in BrushPreset.ALL) {
            val chosen = preset.name == current.name
            Column(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (chosen) Ink.Accent.copy(alpha = 0.18f) else Ink.Chrome)
                    .clickable {
                        // The colour follows the user across presets. Resetting it to the preset's
                        // own black every time is the fastest way to make a brush picker annoying.
                        onPick(preset.copy(color = current.color, erase = preset.erase))
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    preset.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (chosen) Ink.Accent else Ink.Text,
                )
            }
        }
    }
}

/**
 * The palette.
 *
 * A fixed row rather than a wheel, because a wheel needs a picker of its own and this is the sheet
 * people open mid-stroke. The full picker belongs with the adjustment work.
 */
@Composable
private fun ColorRow(current: Color, onPick: (Color) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("رنگ", style = MaterialTheme.typography.labelMedium, color = Ink.TextMuted)
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (color in PALETTE) {
                val chosen = color == current
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(UiColor(color.r, color.g, color.b, color.a))
                        .border(
                            width = if (chosen) 2.dp else 1.dp,
                            color = if (chosen) Ink.Accent else Ink.Divider,
                            shape = CircleShape,
                        )
                        .clickable { onPick(color) },
                )
            }
        }
    }
}

@Composable
private fun BrushSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: ParameterSpec.Slider.Unit,
    onChange: (Float) -> Unit,
) {
    PrecisionSlider(
        spec = ParameterSpec.Slider(
            key = label,
            label = label,
            range = range,
            default = value,
            unit = unit,
            // Size needs fine control at the small end, where a few pixels change the mark
            // completely, and coarse control at the large end where they do not.
            skew = if (unit == ParameterSpec.Slider.Unit.PIXELS) SIZE_SKEW else 1f,
        ),
        value = value,
        onChange = { next, _ -> onChange(next) },
        onCommit = {},
    )
}

/** Black, white and the colours a cover actually uses; a wheel belongs with the colour picker. */
private val PALETTE = listOf(
    Color.BLACK,
    Color.WHITE,
    Color(0.85f, 0.15f, 0.2f),
    Color(0.95f, 0.6f, 0.1f),
    Color(0.95f, 0.85f, 0.2f),
    Color(0.2f, 0.7f, 0.35f),
    Color(0.2f, 0.5f, 0.9f),
    Color(0.55f, 0.25f, 0.8f),
)

private const val SIZE_SKEW = 0.5f
