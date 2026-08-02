package ir.pixellab.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.GradientStop
import ir.pixellab.core.model.Vec2

/**
 * Choosing what paints an interior: a colour, a gradient, or a pattern.
 *
 * The three are one control because they are one decision. Splitting them into separate pickers is
 * what leads to a panel that silently flattens a carefully-built gradient into a solid colour the
 * moment the user opens the colour picker to check a shade — the previous fill is kept here instead,
 * so switching to solid and back restores the gradient rather than a default black-to-white.
 */
@Composable
fun FillEditor(
    fill: Fill,
    model: EditorViewModel,
    onChange: (Fill) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        SheetChips {
            SheetChip("یک‌دست", chosen = fill is Fill.Solid) {
                if (fill !is Fill.Solid) onChange(Fill.Solid(representativeColor(fill)))
            }
            SheetChip("گرادیان", chosen = fill is Fill.Gradient) {
                if (fill !is Fill.Gradient) onChange(defaultGradient(representativeColor(fill)))
            }
            SheetChip("الگو", chosen = fill is Fill.Pattern) {
                if (fill !is Fill.Pattern) {
                    onChange(Fill.Pattern(model.registerPattern(ir.pixellab.core.imaging.Procedural.Pattern.STRIPES)))
                }
            }
        }

        when (fill) {
            is Fill.Solid -> ColorPickerBody(color = fill.color, onChange = { onChange(Fill.Solid(it)) })

            is Fill.Gradient -> GradientEditorBody(gradient = fill, onChange = onChange)

            is Fill.Pattern -> {
                PatternPicker(model, onPick = { onChange(fill.copy(asset = it)) })
                SheetSlider("مقیاس", fill.scale.x, PATTERN_MIN_SCALE..PATTERN_MAX_SCALE, onChange = { value, _ ->
                    // One number for both axes: a pattern stretched on one axis reads as a mistake
                    // rather than as a design, and the two-field version is a control nobody uses
                    // correctly. A rotation is the thing people actually want, and it is below.
                    onChange(fill.copy(scale = Vec2(value, value)))
                })
                SheetSlider("چرخش", fill.rotation, 0f..FULL_TURN, onChange = { value, _ ->
                    onChange(fill.copy(rotation = value))
                })
            }

            // Backdrop samples what is already composited underneath instead of producing its own
            // colour, so there is nothing here to set — it is what makes text read as carved into
            // its background, and it has no parameters at all.
            else -> SheetHint("این پر از پس‌زمینهٔ زیرش نمونه می‌گیرد و تنظیمی ندارد")
        }
    }
}

/**
 * A single colour that stands for a fill.
 *
 * Used when switching kinds, so the new fill starts somewhere near the old one rather than at a
 * default the user has to undo. A gradient hands over its first stop, which is the colour anyone
 * would name if asked what colour the gradient is.
 */
private fun representativeColor(fill: Fill): Color = when (fill) {
    is Fill.Solid -> fill.color
    is Fill.Gradient -> fill.stops.minByOrNull { it.position }?.color ?: Color.WHITE
    else -> Color.WHITE
}

/** From the colour to transparent, which is the gradient people mean nine times in ten. */
private fun defaultGradient(from: Color) = Fill.Gradient(
    stops = listOf(
        GradientStop(0f, from),
        GradientStop(1f, from.copy(a = 0f)),
    ),
)

private const val PATTERN_MIN_SCALE = 0.1f
private const val PATTERN_MAX_SCALE = 8f
private const val FULL_TURN = 360f
