package ir.pixellab.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import ir.pixellab.core.editor.EditorState
import ir.pixellab.core.model.Adjustment
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.GradientStop
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.Vec3
import ir.pixellab.core.render.ParameterSpec

/**
 * The adjustment sheet.
 *
 * Two states in one place: with no adjustment layer selected it offers the fourteen to add, and with
 * one selected it edits it. Splitting those into separate screens is the arrangement that makes a
 * user add a Curves layer, lose it behind a panel, and add a second one.
 *
 * Every control writes back through the editor, so a slider drag is one undo entry rather than
 * ninety.
 */
@Composable
fun AdjustmentSheetBody(state: EditorState, model: EditorViewModel, modifier: Modifier = Modifier) {
    val selected = state.primaryLayer as? Layer.AdjustmentLayer

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        AddRow(model)
        if (selected != null) {
            Text(
                selected.name,
                style = MaterialTheme.typography.labelMedium,
                color = Ink.TextMuted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            Controls(selected, model)
        } else {
            Text(
                "یک لایهٔ تنظیم اضافه کنید یا یکی را انتخاب کنید",
                style = MaterialTheme.typography.bodyMedium,
                color = Ink.TextMuted,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun AddRow(model: EditorViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for ((label, factory) in CATALOG) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = Ink.Text,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Ink.Chrome)
                    .clickable { model.addAdjustment(factory(), label) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun Controls(layer: Layer.AdjustmentLayer, model: EditorViewModel) {
    val id = layer.id
    when (val adjustment = layer.adjustment) {
        is Adjustment.BrightnessContrast -> {
            Slider("روشنایی", adjustment.brightness, -1f..1f) {
                model.setAdjustment(id, adjustment.copy(brightness = it))
            }
            Slider("کنتراست", adjustment.contrast, -1f..1f) {
                model.setAdjustment(id, adjustment.copy(contrast = it))
            }
        }

        is Adjustment.Levels -> {
            Slider("سیاه ورودی", adjustment.inputBlack, 0f..1f) {
                model.setAdjustment(id, adjustment.copy(inputBlack = it))
            }
            Slider("سفید ورودی", adjustment.inputWhite, 0f..1f) {
                model.setAdjustment(id, adjustment.copy(inputWhite = it))
            }
            Slider("گاما", adjustment.gamma, 0.1f..4f) {
                model.setAdjustment(id, adjustment.copy(gamma = it))
            }
            Slider("سیاه خروجی", adjustment.outputBlack, 0f..1f) {
                model.setAdjustment(id, adjustment.copy(outputBlack = it))
            }
            Slider("سفید خروجی", adjustment.outputWhite, 0f..1f) {
                model.setAdjustment(id, adjustment.copy(outputWhite = it))
            }
        }

        is Adjustment.Curves -> {
            var channel by remember { mutableStateOf(CurveChannel.COMPOSITE) }
            CurveChannelRow(channel) { channel = it }
            CurveEditor(
                curve = when (channel) {
                    CurveChannel.COMPOSITE -> adjustment.rgb
                    CurveChannel.RED -> adjustment.red
                    CurveChannel.GREEN -> adjustment.green
                    CurveChannel.BLUE -> adjustment.blue
                },
                onChange = { curve ->
                    model.setAdjustment(
                        id,
                        when (channel) {
                            CurveChannel.COMPOSITE -> adjustment.copy(rgb = curve)
                            CurveChannel.RED -> adjustment.copy(red = curve)
                            CurveChannel.GREEN -> adjustment.copy(green = curve)
                            CurveChannel.BLUE -> adjustment.copy(blue = curve)
                        },
                    )
                },
                channel = channel,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        is Adjustment.HueSaturation -> {
            Slider("رنگ‌مایه", adjustment.hue, -0.5f..0.5f) {
                model.setAdjustment(id, adjustment.copy(hue = it))
            }
            Slider("اشباع", adjustment.saturation, -1f..1f) {
                model.setAdjustment(id, adjustment.copy(saturation = it))
            }
            Slider("روشنی", adjustment.lightness, -1f..1f) {
                model.setAdjustment(id, adjustment.copy(lightness = it))
            }
            Toggle("رنگی‌سازی", adjustment.colorize) {
                model.setAdjustment(id, adjustment.copy(colorize = it))
            }
        }

        is Adjustment.Exposure -> {
            Slider("نوردهی", adjustment.exposure, -5f..5f) {
                model.setAdjustment(id, adjustment.copy(exposure = it))
            }
            Slider("افست", adjustment.offset, -0.5f..0.5f) {
                model.setAdjustment(id, adjustment.copy(offset = it))
            }
            Slider("گامای گیرنده", adjustment.gamma, 0.1f..4f) {
                model.setAdjustment(id, adjustment.copy(gamma = it))
            }
        }

        is Adjustment.Vibrance -> {
            Slider("سرزندگی", adjustment.vibrance, -1f..1f) {
                model.setAdjustment(id, adjustment.copy(vibrance = it))
            }
            Slider("اشباع", adjustment.saturation, -1f..1f) {
                model.setAdjustment(id, adjustment.copy(saturation = it))
            }
        }

        is Adjustment.ColorBalance -> {
            RangeRow("سایه‌ها", adjustment.shadows) { model.setAdjustment(id, adjustment.copy(shadows = it)) }
            RangeRow("میان‌ها", adjustment.midtones) { model.setAdjustment(id, adjustment.copy(midtones = it)) }
            RangeRow("روشن‌ها", adjustment.highlights) {
                model.setAdjustment(id, adjustment.copy(highlights = it))
            }
            Toggle("حفظ روشنایی", adjustment.preserveLuminosity) {
                model.setAdjustment(id, adjustment.copy(preserveLuminosity = it))
            }
        }

        is Adjustment.BlackWhite -> {
            // Six sliders, in Photoshop's order. Three would be simpler and could not tell a red
            // jumper from a green hedge of the same luminance, which is the whole job.
            val labels = listOf("قرمز", "زرد", "سبز", "فیروزه‌ای", "آبی", "سرخابی")
            for ((index, label) in labels.withIndex()) {
                Slider(label, adjustment.weights.getOrElse(index) { 0.5f }, -1f..2f) { value ->
                    val weights = MutableList(labels.size) { adjustment.weights.getOrElse(it) { 0.5f } }
                    weights[index] = value
                    model.setAdjustment(id, adjustment.copy(weights = weights))
                }
            }
        }

        is Adjustment.GradientMap -> {
            Text(
                "نقشهٔ گرادینت روشنایی را به رنگ نگاشت می‌کند",
                style = MaterialTheme.typography.labelSmall,
                color = Ink.TextMuted,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Toggle("دیترینگ", adjustment.dither) { model.setAdjustment(id, adjustment.copy(dither = it)) }
            ColorPickerBody(
                color = adjustment.gradient.stops.last().color,
                onChange = { color ->
                    val stops = adjustment.gradient.stops.toMutableList()
                    stops[stops.lastIndex] = stops.last().copy(color = color)
                    model.setAdjustment(id, adjustment.copy(gradient = adjustment.gradient.copy(stops = stops)))
                },
            )
        }

        is Adjustment.PhotoFilter -> {
            Slider("چگالی", adjustment.density, 0f..1f) {
                model.setAdjustment(id, adjustment.copy(density = it))
            }
            Toggle("حفظ روشنایی", adjustment.preserveLuminosity) {
                model.setAdjustment(id, adjustment.copy(preserveLuminosity = it))
            }
            ColorPickerBody(
                color = adjustment.color,
                onChange = { model.setAdjustment(id, adjustment.copy(color = it)) },
            )
        }

        Adjustment.Invert -> Text(
            "معکوس‌سازی پارامتری ندارد",
            style = MaterialTheme.typography.bodyMedium,
            color = Ink.TextMuted,
            modifier = Modifier.padding(16.dp),
        )

        is Adjustment.Posterize -> Slider("سطوح", adjustment.levels.toFloat(), 2f..64f) {
            model.setAdjustment(id, adjustment.copy(levels = it.toInt().coerceAtLeast(2)))
        }

        is Adjustment.Threshold -> Slider("آستانه", adjustment.level, 0f..1f) {
            model.setAdjustment(id, adjustment.copy(level = it))
        }

        is Adjustment.ColorLookup -> Slider("مقدار", adjustment.amount, 0f..1f) {
            model.setAdjustment(id, adjustment.copy(amount = it))
        }
    }
}

@Composable
private fun RangeRow(label: String, value: Vec3, onChange: (Vec3) -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = Ink.TextMuted,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
    )
    // Photoshop's three opposed axes, which is how people already think about a colour cast.
    Slider("فیروزه‌ای ↔ قرمز", value.x, -1f..1f) { onChange(Vec3(it, value.y, value.z)) }
    Slider("سرخابی ↔ سبز", value.y, -1f..1f) { onChange(Vec3(value.x, it, value.z)) }
    Slider("زرد ↔ آبی", value.z, -1f..1f) { onChange(Vec3(value.x, value.y, it)) }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!value) }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Ink.Text)
        Text(
            if (value) "روشن" else "خاموش",
            style = MaterialTheme.typography.labelLarge,
            color = if (value) Ink.Accent else Ink.TextMuted,
        )
    }
}

@Composable
private fun Slider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    PrecisionSlider(
        spec = ParameterSpec.Slider(key = label, label = label, range = range, default = value),
        value = value,
        onChange = { next, _ -> onChange(next) },
        onCommit = {},
    )
}

/** The fourteen, in the order Photoshop's own menu lists them. */
private val CATALOG: List<Pair<String, () -> Adjustment>> = listOf(
    "روشنایی/کنتراست" to { Adjustment.BrightnessContrast() },
    "سطوح" to { Adjustment.Levels() },
    "منحنی‌ها" to { Adjustment.Curves() },
    "نوردهی" to { Adjustment.Exposure() },
    "سرزندگی" to { Adjustment.Vibrance() },
    "رنگ‌مایه/اشباع" to { Adjustment.HueSaturation() },
    "تعادل رنگ" to { Adjustment.ColorBalance() },
    "سیاه‌وسفید" to { Adjustment.BlackWhite() },
    "فیلتر عکاسی" to { Adjustment.PhotoFilter(Color(1f, 0.7f, 0.35f)) },
    "نقشهٔ گرادینت" to {
        Adjustment.GradientMap(
            Fill.Gradient(
                stops = listOf(GradientStop(0f, Color.BLACK), GradientStop(1f, Color.WHITE)),
            ),
        )
    },
    "معکوس" to { Adjustment.Invert },
    "پوستری" to { Adjustment.Posterize() },
    "آستانه" to { Adjustment.Threshold() },
    "جدول رنگ" to { Adjustment.ColorLookup(ir.pixellab.core.model.AssetId("lut")) },
)
