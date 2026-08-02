package ir.pixellab.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import ir.pixellab.core.editor.EditorState
import ir.pixellab.core.imaging.RadialBlur
import ir.pixellab.core.model.Layer
import kotlinx.coroutines.launch

/**
 * The blurs.
 *
 * They sit beside the adjustments rather than among them, and the difference is worth being blunt
 * about in the interface: an adjustment is a layer that keeps being recomputed and can be changed
 * or thrown away at any time, while a filter is applied *to the pixels* and, once committed, is only
 * undoable. That is exactly the line Photoshop draws between its Image menu and its Filter menu.
 *
 * Every one of them respects the selection, which is what these are actually for — blurring the
 * background behind a subject, spinning a wheel, streaking light out of a face.
 */
@Composable
fun FilterSheetBody(state: EditorState, model: EditorViewModel, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var radius by remember { mutableStateOf(DEFAULT_RADIUS) }
    var amount by remember { mutableStateOf(DEFAULT_AMOUNT) }
    val onPixels = state.primaryLayer is Layer.Image

    Column(modifier.fillMaxWidth()) {
        SheetHint(
            when {
                !onPixels -> "فیلترها روی پیکسل کار می‌کنند — یک لایهٔ تصویر یا نقاشی انتخاب کنید"
                model.select.selection == null -> "چیزی انتخاب نشده — فیلتر روی کل لایه اجرا می‌شود"
                else -> "فیلتر فقط داخل انتخاب اجرا می‌شود و لبه‌اش با همان نرمی محو می‌شود"
            },
        )

        SheetSection("محو گاوسی")
        SheetSlider("شعاع", radius, 0f..MAX_RADIUS, onChange = { value, _ -> radius = value })
        SheetAction("اعمال محو", enabled = onPixels) { scope.launch { model.blur(radius) } }

        SheetSection("محو شعاعی")
        SheetSlider("شدت", amount * PERCENT, 0f..PERCENT, onChange = { value, _ -> amount = value / PERCENT })
        SheetChips {
            for (kind in RadialBlur.Kind.entries) {
                SheetChip(kind.persianLabel, enabled = onPixels) {
                    scope.launch { model.radialBlur(amount, kind) }
                }
            }
        }
        // The centre is the whole effect, so where it comes from has to be said rather than guessed at.
        SheetHint(
            if (model.select.selection == null) {
                "مرکز چرخش، وسط لایه است — برای جای دیگر، اول آنجا را انتخاب کنید"
            } else {
                "مرکز چرخش، وسط ناحیهٔ انتخاب‌شده است"
            },
        )
    }
}

/** Photoshop's own default, and about where a face stops being recognisable on a phone canvas. */
private const val DEFAULT_RADIUS = 8f

private const val MAX_RADIUS = 120f

private const val DEFAULT_AMOUNT = 0.4f
private const val PERCENT = 100f
