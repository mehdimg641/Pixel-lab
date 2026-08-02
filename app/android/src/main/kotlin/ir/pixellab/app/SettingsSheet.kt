package ir.pixellab.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ir.pixellab.core.editor.EditorState

/**
 * Application settings.
 *
 * Everything here is a statement about how the user likes to work, never about the artwork — which
 * is why none of it touches the undo stack and none of it is stored in the document. A user who
 * pressed undo twenty times to recover a shape would be appalled to find snapping had switched
 * itself back on along the way.
 *
 * What is deliberately absent is a light theme. The editor is dark and only dark: a light interface
 * around a design makes its colours read darker than they are, which is why every professional tool
 * is dark, and offering it as a preference would be offering the user a way to misjudge their own
 * work.
 */
@Composable
fun SettingsSheetBody(state: EditorState, model: EditorViewModel, modifier: Modifier = Modifier) {
    val preferences = model.preferences

    fun update(body: (Preferences) -> Preferences) {
        model.setPreferences(body(preferences))
    }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {

        SheetSection("چفت‌شدن")
        SheetChips {
            SheetChip("چفت‌شدن روشن", chosen = state.snapEnabled) {
                model.setSnapEnabled(!state.snapEnabled)
            }
        }
        SheetChips {
            SheetChip("لبهٔ بوم", chosen = preferences.snapToCanvas) {
                update { it.copy(snapToCanvas = !it.snapToCanvas) }
            }
            SheetChip("لایه‌ها", chosen = preferences.snapToLayers) {
                update { it.copy(snapToLayers = !it.snapToLayers) }
            }
            SheetChip("راهنماها", chosen = preferences.snapToGuides) {
                update { it.copy(snapToGuides = !it.snapToGuides) }
            }
            SheetChip("فاصلهٔ برابر", chosen = preferences.snapToSpacing) {
                update { it.copy(snapToSpacing = !it.snapToSpacing) }
            }
        }
        SheetSlider(
            "قدرت چفت‌شدن",
            preferences.snapTolerance,
            Preferences.MIN_TOLERANCE..Preferences.MAX_TOLERANCE,
            onChange = { value, _ -> update { it.copy(snapTolerance = value) } },
        )
        // In screen pixels, not canvas units. In canvas units the pull would grow with the zoom:
        // at 800% a layer becomes impossible to place freely, at 10% it never catches at all.
        SheetHint("بر حسب پیکسل صفحه — پس در هر بزرگ‌نمایی یک‌جور حس می‌شود")

        SheetSection("سند تازه")
        SheetNumberField("پهنا", preferences.defaultCanvasWidth.toString()) { typed ->
            typed.toIntOrNull()?.let { width -> update { it.copy(defaultCanvasWidth = width).sane() } }
        }
        SheetNumberField("بلندا", preferences.defaultCanvasHeight.toString()) { typed ->
            typed.toIntOrNull()?.let { height -> update { it.copy(defaultCanvasHeight = height).sane() } }
        }
        SheetChips {
            for (preset in CANVAS_PRESETS) {
                val chosen = preferences.defaultCanvasWidth == preset.width &&
                    preferences.defaultCanvasHeight == preset.height
                SheetChip(preset.label, chosen = chosen) {
                    update { it.copy(defaultCanvasWidth = preset.width, defaultCanvasHeight = preset.height) }
                }
            }
        }

        SheetSection("متن")
        SheetChips {
            SheetChip("رقم فارسی در متن تازه", chosen = preferences.persianDigits) {
                update { it.copy(persianDigits = !it.persianDigits) }
            }
        }

        SheetSection("کارایی")
        SheetSlider(
            "سقف تصویر واردشده (مگاپیکسل)",
            preferences.placedMegapixels.toFloat(),
            Preferences.MIN_MEGAPIXELS.toFloat()..Preferences.MAX_MEGAPIXELS.toFloat(),
            onChange = { value, _ -> update { it.copy(placedMegapixels = value.toInt()) } },
        )
        // The number is not arbitrary and the reason is worth one line: a 48-megapixel photograph
        // at full resolution is nearly two hundred megabytes of pixels before anything is drawn.
        SheetHint("عکس بزرگ‌تر نصف‌نصف کوچک می‌شود تا حافظه تمام نشود — مقیاس لایه بقیه را روی GPU انجام می‌دهد")

        SheetSlider(
            "ذخیرهٔ خودکار (دقیقه)",
            preferences.autoSaveMinutes.toFloat(),
            0f..Preferences.MAX_AUTO_SAVE.toFloat(),
            onChange = { value, _ -> update { it.copy(autoSaveMinutes = value.toInt()) } },
        )
        SheetHint(if (preferences.autoSaveMinutes == 0) "خاموش" else "هر ${preferences.autoSaveMinutes} دقیقه")

        SheetChips {
            SheetChip("بازخورد لمسی", chosen = preferences.hapticFeedback) {
                update { it.copy(hapticFeedback = !it.hapticFeedback) }
            }
        }

        SheetSection("فونت‌ها")
        SheetHint("${model.fontStore.catalog.size} قلم از ${model.fontStore.catalog.fileCount} فایل")
        SheetAction("مدیریت فونت") { model.act { openSheet(ir.pixellab.core.editor.SheetContent.FontPicker) } }

        SheetSection("دربارهٔ برنامه")
        SheetHint("PixelLab — متن‌باز، بدون واترمارک، بدون اشتراک، بدون نیاز به اینترنت")
        SheetAction("بازگرداندن پیش‌فرض‌ها") { model.setPreferences(Preferences()) }
    }
}

/** Where covers actually get posted, at the sizes those places actually use. */
private data class CanvasPreset(val label: String, val width: Int, val height: Int)

private val CANVAS_PRESETS = listOf(
    CanvasPreset("مربع", 1080, 1080),
    CanvasPreset("استوری", 1080, 1920),
    CanvasPreset("پست بلند", 1080, 1350),
    CanvasPreset("کاور", 1400, 1400),
    CanvasPreset("تامبنیل", 1280, 720),
)
