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
import ir.pixellab.core.imaging.LocalContrast
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
    var shadowLift by remember { mutableStateOf(0f) }
    var highlightPull by remember { mutableStateOf(0f) }
    var midtone by remember { mutableStateOf(0f) }
    var localRadius by remember { mutableStateOf(DEFAULT_LOCAL_RADIUS) }
    var lumaNoise by remember { mutableStateOf(0f) }
    var chromaNoise by remember { mutableStateOf(DEFAULT_CHROMA_NOISE) }
    var detail by remember { mutableStateOf(DEFAULT_DETAIL) }
    var dustRadius by remember { mutableStateOf(DEFAULT_DUST_RADIUS) }
    var dustThreshold by remember { mutableStateOf(DEFAULT_DUST_THRESHOLD) }
    var sharpenAmount by remember { mutableStateOf(DEFAULT_SHARPEN) }
    var undoLens by remember { mutableStateOf(false) }
    var highPassRadius by remember { mutableStateOf(DEFAULT_HIGH_PASS) }
    var threshold by remember { mutableStateOf(0f) }
    var vignetteAmount by remember { mutableStateOf(DEFAULT_VIGNETTE) }
    var surfaceRadius by remember { mutableStateOf(DEFAULT_SURFACE_RADIUS) }
    var surfaceThreshold by remember { mutableStateOf(DEFAULT_SURFACE_THRESHOLD) }
    var blockSize by remember { mutableStateOf(DEFAULT_BLOCK) }
    var grainAmount by remember { mutableStateOf(DEFAULT_GRAIN) }
    var monochromeGrain by remember { mutableStateOf(true) }
    var hazeStrength by remember { mutableStateOf(DEFAULT_HAZE) }
    var contrastScale by remember { mutableStateOf(LocalContrast.Scale.CLARITY) }
    var contrastAmount by remember { mutableStateOf(DEFAULT_LOCAL_CONTRAST) }
    var rayThreshold by remember { mutableStateOf(DEFAULT_RAY_THRESHOLD) }
    var rayLength by remember { mutableStateOf(DEFAULT_RAY_LENGTH) }
    var rayIntensity by remember { mutableStateOf(DEFAULT_RAY_INTENSITY) }

    Column(modifier.fillMaxWidth()) {
        SheetHint(
            when {
                !onPixels -> "فیلترها روی پیکسل کار می‌کنند — یک لایهٔ تصویر یا نقاشی انتخاب کنید"
                model.select.selection == null -> "چیزی انتخاب نشده — فیلتر روی کل لایه اجرا می‌شود"
                else -> "فیلتر فقط داخل انتخاب اجرا می‌شود و لبه‌اش با همان نرمی محو می‌شود"
            },
        )
        if (!onPixels) SheetAction("افزودن عکس", onClick = LocalEditorActions.current.pickImage)

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

        SheetSection("محو سطحی")
        SheetSlider(
            "شعاع", surfaceRadius, 0f..MAX_SURFACE_RADIUS,
            onChange = { value, _ -> surfaceRadius = value },
        )
        SheetSlider(
            "آستانه", surfaceThreshold, 0.01f..MAX_SURFACE_THRESHOLD,
            onChange = { value, _ -> surfaceThreshold = value },
        )
        SheetAction("اعمال", enabled = onPixels) {
            scope.launch { model.surfaceBlur(surfaceRadius, surfaceThreshold) }
        }
        // The whole point of it, and the reason the second slider exists at all.
        SheetHint("درون نواحی هم‌رنگ صاف می‌کند و سر لبه‌ها می‌ایستد — آستانه می‌گوید «لبه» یعنی چقدر اختلاف")

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

        SheetSection("سایه و روشنایی")
        SheetSlider("باز کردن سایه", shadowLift, 0f..1f, onChange = { value, _ -> shadowLift = value })
        SheetSlider("مهار روشنایی", highlightPull, 0f..1f, onChange = { value, _ -> highlightPull = value })
        SheetSlider("کنتراست میانی", midtone, -1f..1f, onChange = { value, _ -> midtone = value })
        SheetSlider("شعاع", localRadius, 4f..MAX_LOCAL_RADIUS, onChange = { value, _ -> localRadius = value })
        SheetAction("اعمال", enabled = onPixels) {
            scope.launch { model.shadowHighlight(shadowLift, highlightPull, localRadius, midtone) }
        }
        // The one control people get wrong, and the one that decides whether the result looks like
        // a photograph or like an HDR.
        SheetHint("شعاع کوچک دور هر لبه هالهٔ روشن می‌سازد — بزرگ‌تر از جزئیاتی که می‌خواهید حفظ شود")

        SheetSection("کاهش نویز")
        SheetSlider("نویز روشنایی", lumaNoise, 0f..1f, onChange = { value, _ -> lumaNoise = value })
        SheetSlider("نویز رنگ", chromaNoise, 0f..1f, onChange = { value, _ -> chromaNoise = value })
        SheetSlider("حفظ جزئیات", detail, 0f..1f, onChange = { value, _ -> detail = value })
        SheetAction("اعمال", enabled = onPixels) {
            scope.launch { model.reduceNoise(lumaNoise, chromaNoise, detail) }
        }
        // The single most useful thing to know about noise reduction, and it is not obvious.
        SheetHint("نویز رنگ را می‌شود خیلی بیشتر از نویز روشنایی کم کرد — چشم جزئیات رنگ را نمی‌بیند")

        SheetSlider("شعاع لکه", dustRadius.toFloat(), 1f..MAX_DUST, onChange = { value, _ ->
            dustRadius = value.toInt().coerceAtLeast(1)
        })
        SheetSlider("آستانهٔ لکه", dustThreshold, 0f..1f, onChange = { value, _ -> dustThreshold = value })
        SheetAction("گرد و غبار و خط", enabled = onPixels) {
            scope.launch { model.dustAndScratches(dustRadius, dustThreshold) }
        }
        SheetHint("آستانه فقط پیکسل‌های پرت را عوض می‌کند — بدون آن، مژه‌ها هم با گرد و غبار پاک می‌شوند")

        SheetSection("تیز کردن")
        SheetSlider("مقدار", sharpenAmount, 0f..MAX_SHARPEN, onChange = { value, _ -> sharpenAmount = value })
        SheetSlider("آستانه", threshold, 0f..MAX_THRESHOLD, onChange = { value, _ -> threshold = value })
        SheetAction("ماسک آنشارپ", enabled = onPixels) {
            scope.launch { model.unsharpMask(sharpenAmount, SHARPEN_RADIUS, threshold) }
        }
        SheetHint("آستانه، نواحی صاف را دست‌نخورده می‌گذارد — تفاوت تیز کردن عکس با تیز کردن نویزش")

        SheetChips {
            SheetChip("محو گاوسی", chosen = !undoLens) { undoLens = false }
            SheetChip("محو لنزی", chosen = undoLens) { undoLens = true }
        }
        SheetAction("تیز کردن هوشمند", enabled = onPixels) {
            scope.launch { model.smartSharpen(sharpenAmount, SHARPEN_RADIUS, undoLens) }
        }
        // What "smart" actually means here, said plainly rather than left as a brand name.
        SheetHint("تیز کردن هوشمند در سایه و روشنایی محو می‌شود — جایی که هاله و نویز پیدا می‌شوند")

        SheetSlider("شعاع بالاگذر", highPassRadius, 0.5f..MAX_HIGH_PASS, onChange = { value, _ ->
            highPassRadius = value
        })
        SheetAction("بالاگذر روی لایهٔ جدید", enabled = onPixels) {
            scope.launch { model.highPass(highPassRadius) }
        }
        SheetHint("بالاگذر روی یک لایهٔ جدید با مود Overlay ساخته می‌شود — روی خود عکس بی‌معنی است")

        SheetSection("هوا و نور")
        SheetSlider("مه‌زدایی", hazeStrength, 0f..1f, onChange = { value, _ -> hazeStrength = value })
        SheetAction("اعمال مه‌زدایی", enabled = onPixels) { scope.launch { model.dehaze(hazeStrength) } }
        // Why it is not the contrast slider, said in the one sentence that makes the difference land.
        SheetHint("مه به فاصله بستگی دارد — این فیلتر دورها را باز می‌کند و نزدیک‌ها را دست‌نخورده می‌گذارد")

        SheetChips {
            for (scale in LocalContrast.Scale.entries) {
                SheetChip(scale.persianLabel, chosen = contrastScale == scale) { contrastScale = scale }
            }
        }
        SheetSlider(
            "کنتراست موضعی", contrastAmount, -1f..1f,
            onChange = { value, _ -> contrastAmount = value },
        )
        SheetAction("اعمال", enabled = onPixels) {
            scope.launch { model.localContrast(contrastScale, contrastAmount) }
        }
        // Three names across the reference apps, one operation. Saying so is the useful part.
        SheetHint("هر سه یک کارند با اندازهٔ متفاوت: بافت یعنی منافذ و پارچه، شفافیت یعنی حجم صورت، درخشندگی یعنی کل صحنه")

        SheetSlider("آستانهٔ نور", rayThreshold, 0.3f..0.98f, onChange = { value, _ -> rayThreshold = value })
        SheetSlider("طول پرتو", rayLength, 0.1f..1f, onChange = { value, _ -> rayLength = value })
        SheetSlider("شدت پرتو", rayIntensity, 0f..1f, onChange = { value, _ -> rayIntensity = value })
        SheetAction("پرتوهای نور", enabled = onPixels) {
            scope.launch { model.lightRays(rayThreshold, rayLength, rayIntensity) }
        }
        SheetHint(
            if (model.select.selection == null) {
                "خورشید وسط قاب فرض می‌شود — با انتخاب، جایش را خودتان بگویید"
            } else {
                "پرتوها از وسط ناحیهٔ انتخاب‌شده بیرون می‌زنند"
            },
        )

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

/** Photoshop's own default radius for shadow/highlight, and a sane one on a phone-sized canvas. */
private const val DEFAULT_LOCAL_RADIUS = 30f
private const val MAX_LOCAL_RADIUS = 200f

/** Colour noise can take a heavy hand from the start; brightness noise cannot, so it begins at nil. */
private const val DEFAULT_CHROMA_NOISE = 0.5f
private const val DEFAULT_DETAIL = 0.5f

private const val DEFAULT_DUST_RADIUS = 2
private const val MAX_DUST = 8f
private const val DEFAULT_DUST_THRESHOLD = 0.15f

private const val DEFAULT_HIGH_PASS = 3f
private const val MAX_HIGH_PASS = 40f

private const val DEFAULT_SHARPEN = 1f
private const val MAX_SHARPEN = 4f
private const val MAX_THRESHOLD = 0.2f

/** A pixel and a half: fine enough to sharpen detail without haloing every edge. */
private const val SHARPEN_RADIUS = 1.5f

/** The sharp region and its ramp, as multiples of the blur radius the user set. */
private const val FOCUS_FRACTION = 6f
private const val TRANSITION_FRACTION = 10f

/** Photoshop's own defaults, and the pair that smooths skin without flattening a face. */
private const val DEFAULT_SURFACE_RADIUS = 8f
private const val DEFAULT_SURFACE_THRESHOLD = 0.06f
private const val MAX_SURFACE_RADIUS = 24f
private const val MAX_SURFACE_THRESHOLD = 0.5f

private const val DEFAULT_VIGNETTE = -0.4f

private const val DEFAULT_BLOCK = 12
private const val MAX_BLOCK = 80f

private const val DEFAULT_HAZE = 0.5f
private const val DEFAULT_LOCAL_CONTRAST = 0.4f

/** Rays start off; the threshold and length are only meaningful once someone reaches for them. */
private const val DEFAULT_RAY_THRESHOLD = 0.8f
private const val DEFAULT_RAY_LENGTH = 0.6f
private const val DEFAULT_RAY_INTENSITY = 0f

private const val DEFAULT_GRAIN = 0.06f
private const val MAX_GRAIN = 0.4f
