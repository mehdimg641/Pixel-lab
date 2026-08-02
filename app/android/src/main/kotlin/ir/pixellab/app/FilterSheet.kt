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
import ir.pixellab.core.imaging.GradientBlur
import ir.pixellab.core.imaging.HistogramChannel
import ir.pixellab.core.imaging.RadialBlur
import ir.pixellab.core.model.Layer
import kotlinx.coroutines.launch

/**
 * The filters that land in the pixels, and the histogram that says whether they are needed.
 *
 * They sit beside the adjustments rather than among them, and the difference is worth being blunt
 * about in the interface: an adjustment is a layer that keeps being recomputed and can be changed
 * or thrown away at any time, while a filter is applied *to the pixels* and, once committed, is
 * only undoable. That is exactly the line Photoshop draws between its Image menu and its Filter
 * menu.
 *
 * The histogram is at the top because it is what turns "this looks a bit dark" into a number, and
 * because clipping is invisible in the artwork — a screen cannot show the difference between 254
 * and 255.
 */
@Composable
fun FilterSheetBody(state: EditorState, model: EditorViewModel, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val onPixels = state.primaryLayer is Layer.Image

    var channel by remember { mutableStateOf(HistogramChannel.LUMINANCE) }
    var radius by remember { mutableStateOf(DEFAULT_RADIUS) }
    var amount by remember { mutableStateOf(DEFAULT_AMOUNT) }
    var angle by remember { mutableStateOf(0f) }
    var distance by remember { mutableStateOf(DEFAULT_DISTANCE) }
    var blades by remember { mutableStateOf(0) }
    var sharpenAmount by remember { mutableStateOf(DEFAULT_SHARPEN) }
    var threshold by remember { mutableStateOf(0f) }
    var vignetteAmount by remember { mutableStateOf(DEFAULT_VIGNETTE) }
    var blockSize by remember { mutableStateOf(DEFAULT_BLOCK) }
    var grainAmount by remember { mutableStateOf(DEFAULT_GRAIN) }
    var monochromeGrain by remember { mutableStateOf(true) }

    Column(modifier.fillMaxWidth()) {
        SheetHint(
            when {
                !onPixels -> "فیلترها روی پیکسل کار می‌کنند — یک لایهٔ تصویر یا نقاشی انتخاب کنید"
                model.select.selection == null -> "چیزی انتخاب نشده — فیلتر روی کل لایه اجرا می‌شود"
                else -> "فیلتر فقط داخل انتخاب اجرا می‌شود و لبه‌اش با همان نرمی محو می‌شود"
            },
        )

        SheetSection("هیستوگرام")
        SheetChips {
            for (option in HistogramChannel.entries) {
                SheetChip(option.persianLabel, chosen = channel == option) { channel = option }
            }
        }
        // Recomputed on demand rather than kept live: a full pass over the pixels every frame
        // would be the slowest thing on screen for a readout nobody watches mid-drag.
        HistogramView(model.histogram(channel), channel)
        SheetAction("سطوح خودکار", enabled = onPixels) { model.autoLevels() }
        SheetHint("سطوح خودکار یک لایهٔ تنظیم می‌سازد، نه تغییر روی پیکسل — پس بعدش قابل اصلاح است")

        SheetSection("محو گاوسی")
        SheetSlider("شعاع", radius, 0f..MAX_RADIUS, onChange = { value, _ -> radius = value })
        SheetAction("اعمال", enabled = onPixels) { scope.launch { model.blur(radius) } }

        SheetSection("محو شعاعی")
        SheetSlider("شدت", amount * PERCENT, 0f..PERCENT, onChange = { value, _ -> amount = value / PERCENT })
        SheetChips {
            for (kind in RadialBlur.Kind.entries) {
                SheetChip(kind.persianLabel, enabled = onPixels) {
                    scope.launch { model.radialBlur(amount, kind) }
                }
            }
        }
        SheetHint(
            if (model.select.selection == null) {
                "مرکز، وسط لایه است — برای جای دیگر اول آنجا را انتخاب کنید"
            } else {
                "مرکز، وسط ناحیهٔ انتخاب‌شده است"
            },
        )

        SheetSection("محو حرکتی")
        SheetSlider("زاویه", angle, 0f..FULL_TURN, onChange = { value, _ -> angle = value })
        SheetSlider("مسافت", distance, 0f..MAX_DISTANCE, onChange = { value, _ -> distance = value })
        SheetAction("اعمال", enabled = onPixels) { scope.launch { model.motionBlur(angle, distance) } }

        SheetSection("محو لنزی")
        SheetSlider("شعاع", radius, 0f..MAX_LENS_RADIUS, onChange = { value, _ -> radius = value })
        SheetChips {
            SheetChip("دایره", chosen = blades == 0) { blades = 0 }
            for (count in listOf(5, 6, 8)) {
                SheetChip("$count پره", chosen = blades == count) { blades = count }
            }
        }
        SheetAction("اعمال", enabled = onPixels) { scope.launch { model.lensBlur(radius, blades) } }
        // The one thing that separates a lens from a Gaussian, and the reason the blade count is here.
        SheetHint("شکل دیافراگم، شکل بوکه را می‌سازد — شش‌پره بوکهٔ شش‌ضلعی می‌دهد")

        SheetSection("محو تدریجی")
        SheetChips {
            for (shape in GradientBlur.Shape.entries) {
                SheetChip(shape.persianLabel, enabled = onPixels) {
                    scope.launch {
                        model.gradientBlur(shape, radius, FOCUS_FRACTION * radius, TRANSITION_FRACTION * radius)
                    }
                }
            }
        }
        SheetHint("ناحیهٔ شارپ دور انتخاب می‌ماند و به‌تدریج محو می‌شود — تیلت‌شیفت و بوکهٔ پرتره")

        SheetSection("تیز کردن")
        SheetSlider("مقدار", sharpenAmount, 0f..MAX_SHARPEN, onChange = { value, _ -> sharpenAmount = value })
        SheetSlider("آستانه", threshold, 0f..MAX_THRESHOLD, onChange = { value, _ -> threshold = value })
        SheetAction("اعمال", enabled = onPixels) {
            scope.launch { model.unsharpMask(sharpenAmount, SHARPEN_RADIUS, threshold) }
        }
        SheetHint("آستانه، نواحی صاف را دست‌نخورده می‌گذارد — تفاوت تیز کردن عکس با تیز کردن نویزش")

        SheetSection("جلوه‌های پایانی")
        SheetSlider(
            "وینیت",
            vignetteAmount,
            -1f..1f,
            onChange = { value, _ -> vignetteAmount = value },
        )
        SheetAction("اعمال وینیت", enabled = onPixels) { scope.launch { model.vignette(vignetteAmount) } }

        SheetSlider("اندازهٔ بلوک", blockSize.toFloat(), 1f..MAX_BLOCK, onChange = { value, _ ->
            blockSize = value.toInt().coerceAtLeast(1)
        })
        SheetAction("پیکسلی کردن", enabled = onPixels) { scope.launch { model.pixelate(blockSize) } }

        SheetSlider("دانه", grainAmount, 0f..MAX_GRAIN, onChange = { value, _ -> grainAmount = value })
        SheetChips {
            SheetChip("تک‌رنگ", chosen = monochromeGrain) { monochromeGrain = true }
            SheetChip("رنگی", chosen = !monochromeGrain) { monochromeGrain = false }
        }
        SheetAction("افزودن دانه", enabled = onPixels) {
            scope.launch { model.grain(grainAmount, monochromeGrain) }
        }
        SheetHint("دانهٔ تک‌رنگ کار فیلم است؛ دانهٔ رنگی نویز سنسور دیجیتال را تقلید می‌کند")
    }
}

/** Photoshop's own default, and about where a face stops being recognisable on a phone canvas. */
private const val DEFAULT_RADIUS = 8f
private const val MAX_RADIUS = 120f
private const val MAX_LENS_RADIUS = 48f

private const val DEFAULT_AMOUNT = 0.4f
private const val PERCENT = 100f
private const val FULL_TURN = 360f

private const val DEFAULT_DISTANCE = 24f
private const val MAX_DISTANCE = 200f

private const val DEFAULT_SHARPEN = 1f
private const val MAX_SHARPEN = 4f
private const val MAX_THRESHOLD = 0.2f

/** A pixel and a half: fine enough to sharpen detail without haloing every edge. */
private const val SHARPEN_RADIUS = 1.5f

/** The sharp region and its ramp, as multiples of the blur radius the user set. */
private const val FOCUS_FRACTION = 6f
private const val TRANSITION_FRACTION = 10f

private const val DEFAULT_VIGNETTE = -0.4f

private const val DEFAULT_BLOCK = 12
private const val MAX_BLOCK = 80f

private const val DEFAULT_GRAIN = 0.06f
private const val MAX_GRAIN = 0.4f
