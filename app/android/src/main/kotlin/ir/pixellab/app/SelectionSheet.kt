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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.JoinFull
import androidx.compose.material.icons.outlined.JoinInner
import androidx.compose.material.icons.outlined.JoinLeft
import androidx.compose.material.icons.outlined.Rectangle
import androidx.compose.ui.graphics.vector.ImageVector
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
        ChipRow("ماسک سریع") {
            Chip(
                if (model.quickMask) "خروج از ماسک سریع" else "ماسک سریع",
                chosen = model.quickMask,
            ) { model.toggleQuickMask() }
        }
        SheetHint(
            if (model.quickMask) {
                "قرمز یعنی بیرون از انتخاب — با قلم‌موی سفید اضافه کنید و با مشکی کم"
            } else {
                "مورچه‌ها فقط مرزِ پنجاه‌درصد را نشان می‌دهند؛ ماسک سریع خودِ پوشش را می‌کشد"
            },
        )

        // The marquee shapes and the four boolean modes are the two rows every editor draws as
        // pictures, and for the same reason: "subtract from the selection" is a diagram of two
        // overlapping shapes, and no phrase describes it faster than the diagram does.
        ChipRow("ابزار") {
            for (shape in SelectionShape.entries) {
                SheetIconChip(shape.icon, labelOf(shape), chosen = select.shape == shape) {
                    select.shape = shape
                }
            }
        }

        ChipRow("ترکیب") {
            for (mode in SelectionMode.entries) {
                SheetIconChip(mode.icon, labelOf(mode), chosen = select.mode == mode) {
                    select.mode = mode
                }
            }
        }

        if (select.shape == SelectionShape.QUICK) {
            Slider("پهنای نمونه", select.quickRadius, 4f..80f) { select.quickRadius = it }
            Slider("تلورانس", select.tolerance, 0f..160f) { select.tolerance = it }
            // The one sentence that stops it being mistaken for a fatter magic wand.
            SheetHint("روی ناحیه بکشید — از همان‌جا که کشیدید رشد می‌کند و سرِ لبه‌ها می‌ایستد")
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
        if (state.primaryLayer !is Layer.Image) {
            SheetAction("افزودن عکس", onClick = LocalEditorActions.current.pickImage)
        }

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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(label: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Ink.TextMuted)
        // Wrapping, not a fixed row. Six chips do not fit across a 411dp phone, and a plain Row
        // hands each child only the width it has left — so «نرم», last in its row, came out 32dp
        // wide with its label clipped. The row did not overflow visibly; it quietly crushed its
        // final control, which is the harder version of the bug to notice by eye.
        FlowRow(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) { content() }
    }
}

/**
 * Delegates to [SheetChip].
 *
 * It used to be a `Text` with a click and eight points of padding — 36.5dp tall, under the touch
 * minimum, and one of four private chips across the sheets that had each drifted into the same
 * defect independently. `SheetChip`'s own documentation warned about exactly this: "two sheets with
 * their own private chip drift apart within a week". They did.
 *
 * Kept as a local name rather than deleted so the call sites stay short.
 */
@Composable
private fun Chip(label: String, chosen: Boolean, enabled: Boolean = true, onClick: () -> Unit) =
    SheetChip(label, chosen = chosen, enabled = enabled, onClick = onClick)

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
    SelectionShape.QUICK -> "انتخاب سریع"
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

/**
 * The diagram for each marquee tool and each boolean mode.
 *
 * The four combine modes are the clearest case in the application for a picture over a word: a user
 * scanning «جایگزین / افزودن / کاستن / اشتراک» has to read four words and hold them apart, where
 * four overlapping-circle diagrams are told apart without reading anything.
 */
private val SelectionShape.icon: ImageVector
    get() = when (this) {
        SelectionShape.RECTANGLE -> Icons.Outlined.CropSquare
        SelectionShape.ELLIPSE -> Icons.Outlined.Circle
        SelectionShape.LASSO -> Icons.Outlined.Gesture
        SelectionShape.WAND -> Icons.Outlined.AutoAwesome
        SelectionShape.QUICK -> Icons.Outlined.Brush
    }

private val SelectionMode.icon: ImageVector
    get() = when (this) {
        SelectionMode.REPLACE -> Icons.Outlined.Rectangle
        SelectionMode.ADD -> Icons.Outlined.JoinFull
        SelectionMode.SUBTRACT -> Icons.Outlined.JoinLeft
        SelectionMode.INTERSECT -> Icons.Outlined.JoinInner
    }
