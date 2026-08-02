package ir.pixellab.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import ir.pixellab.core.editor.EditorState
import ir.pixellab.core.model.Layer
import ir.pixellab.core.paint.SelectionMode
import ir.pixellab.core.render.ParameterSpec
import kotlinx.coroutines.launch

/**
 * The selection sheet.
 *
 * Four shapes, four ways of combining them, and the two numbers that decide what a selection is
 * actually usable for. The combining row is not a nicety: building a selection is almost always
 * several passes — a wand for the sky, subtract for the branch that came with it, add for the piece
 * it missed — and without it the user has to get the whole thing right in one gesture.
 */
@Composable
fun SelectionSheetBody(state: EditorState, model: EditorViewModel, modifier: Modifier = Modifier) {
    val select = model.select
    val canvas = state.document.canvas
    val scope = rememberCoroutineScope()
    var sensitivity by remember { mutableStateOf(DEFAULT_SENSITIVITY) }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        ChipRow("ابزار") {
            for (shape in SelectionShape.entries) {
                Chip(labelOf(shape), chosen = select.shape == shape) { select.shape = shape }
            }
        }

        ChipRow("ترکیب") {
            for (mode in SelectionMode.entries) {
                Chip(labelOf(mode), chosen = select.mode == mode) { select.mode = mode }
            }
        }

        if (select.shape == SelectionShape.WAND) {
            Slider("تلورانس", select.tolerance, 0f..160f) { select.tolerance = it }
            ChipRow("گستره") {
                Chip("پیوسته", chosen = select.contiguous) { select.contiguous = true }
                Chip("سراسری", chosen = !select.contiguous) { select.contiguous = false }
            }
        }

        Slider("پَر", select.feather, 0f..60f) { select.feather = it }

        ChipRow("سوژه") {
            Chip("انتخاب سوژه", chosen = false, enabled = state.primaryLayer is Layer.Image) {
                scope.launch { model.selectSubject(sensitivity) }
            }
        }
        Slider("حساسیت", sensitivity, 0f..1f) { sensitivity = it }
        Text(
            if (state.primaryLayer is Layer.Image) {
                // Said plainly: this is a classical algorithm, not a model, and it has a shape of
                // failure the user can work with once they know what it assumes.
                "لبهٔ کادر را پس‌زمینه فرض می‌کند — بعدش با «کم‌کردن» اصلاحش کنید"
            } else {
                "یک لایهٔ تصویر انتخاب کنید"
            },
            style = MaterialTheme.typography.labelSmall,
            color = Ink.TextMuted,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        ChipRow("عملیات") {
            Chip("همه", chosen = false) {
                select.selectAll(canvas.width, canvas.height)
                model.paint.selection = select.selection
            }
            Chip("هیچ", chosen = false) {
                select.clear()
                model.paint.selection = null
            }
            Chip("معکوس", chosen = false) {
                select.invert(canvas.width, canvas.height)
                model.paint.selection = select.selection
            }
            Chip("بزرگ‌تر", chosen = false) {
                select.grow(GROW_STEP)
                model.paint.selection = select.selection
            }
            Chip("کوچک‌تر", chosen = false) {
                select.grow(-GROW_STEP)
                model.paint.selection = select.selection
            }
            Chip("نرم", chosen = false) {
                select.soften(SOFTEN_RADIUS)
                model.paint.selection = select.selection
            }
        }

        Text(
            if (select.selection == null) {
                "چیزی انتخاب نشده — قلم‌مو روی کل لایه کار می‌کند"
            } else {
                "انتخاب فعال — قلم‌مو فقط داخل آن کار می‌کند"
            },
            style = MaterialTheme.typography.labelSmall,
            color = Ink.TextMuted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun ChipRow(label: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Ink.TextMuted)
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
    }
}

@Composable
private fun Chip(label: String, chosen: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = when {
            !enabled -> Ink.Divider
            chosen -> Ink.Accent
            else -> Ink.Text
        },
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (chosen) Ink.Accent.copy(alpha = 0.18f) else Ink.Chrome)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
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

private fun labelOf(shape: SelectionShape) = when (shape) {
    SelectionShape.RECTANGLE -> "مستطیل"
    SelectionShape.ELLIPSE -> "بیضی"
    SelectionShape.LASSO -> "کمند"
    SelectionShape.WAND -> "عصای جادویی"
}

private fun labelOf(mode: SelectionMode) = when (mode) {
    SelectionMode.REPLACE -> "جایگزین"
    SelectionMode.ADD -> "افزودن"
    SelectionMode.SUBTRACT -> "کم‌کردن"
    SelectionMode.INTERSECT -> "اشتراک"
}

/** Eight pixels: enough to be worth a press, small enough that two presses are still controllable. */
private const val GROW_STEP = 8

private const val SOFTEN_RADIUS = 4f

/** The middle of the range: takes the subject on a plain background without eating into it. */
private const val DEFAULT_SENSITIVITY = 0.5f
