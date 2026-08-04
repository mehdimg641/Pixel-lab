package ir.pixellab.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import ir.pixellab.core.ai.FaceReshape
import ir.pixellab.core.editor.EditorState
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Layer
import ir.pixellab.engine.android.FaceTools
import kotlinx.coroutines.launch

/**
 * Portrait retouching — the panel every reference app opens with and this one did not have.
 *
 * The thing worth saying about its shape: **detection is a button, not a side effect.** Finding a
 * face is a full-resolution pass, and running it whenever a layer is selected would tax everyone for
 * a feature most people are not using at that moment. So the panel is honest about the two steps —
 * find the face, then edit it — and every control below is disabled until there is a face to edit.
 *
 * The second thing: **everything applies in one press.** Nine live sliders each running their own
 * full-image pass would resample the picture nine times, and a face resampled twice is visibly soft.
 * So the sliders set values, the ✓ applies them together, and the sheet's own cancel puts them back.
 */
@Composable
fun PortraitSheetBody(state: EditorState, model: EditorViewModel, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val onPixels = state.primaryLayer is Layer.Image
    val ready = model.faces.isNotEmpty()

    var smooth by remember { mutableStateOf(0f) }
    var shine by remember { mutableStateOf(0f) }
    var teeth by remember { mutableStateOf(0f) }
    var eyes by remember { mutableStateOf(0f) }
    var redEye by remember { mutableStateOf(false) }

    var lipstick by remember { mutableStateOf<Color?>(null) }
    var lipStrength by remember { mutableStateOf(DEFAULT_LIP) }
    var blush by remember { mutableStateOf<Color?>(null) }
    var eyeshadow by remember { mutableStateOf<Color?>(null) }
    var brows by remember { mutableStateOf<Color?>(null) }

    val reshape = remember { androidx.compose.runtime.mutableStateMapOf<FaceReshape.Adjustment, Float>() }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        SheetHint(
            when {
                !onPixels -> "پرتره روی پیکسل کار می‌کند — یک لایهٔ تصویر انتخاب کنید"
                model.detecting -> "در حال تشخیص…"
                else -> model.faceOutcome ?: "اول چهره را پیدا کنید"
            },
        )
        SheetAction("پیدا کردن چهره", enabled = onPixels && !model.detecting) {
            scope.launch { model.detectFaces() }
        }
        // Said once, plainly, because it is the question a privacy-minded user asks first and the
        // honest answer is the app's best argument for having a model at all.
        SheetHint("مدل داخل خود اپ است و روی همین دستگاه اجرا می‌شود — هیچ تصویری جایی نمی‌رود")

        SheetSection("پوست")
        SheetSlider("نرمی پوست", smooth, 0f..1f, enabled = ready, onChange = { value, _ -> smooth = value })
        SheetSlider("کاهش براقی", shine, 0f..1f, enabled = ready, onChange = { value, _ -> shine = value })
        // The one thing about skin smoothing that decides whether it looks retouched or plastic.
        SheetHint("چشم و لب و ابرو از ناحیهٔ نرم‌شدن بیرون می‌مانند — وگرنه صورت پلاستیکی می‌شود")

        SheetSection("چشم و دندان")
        SheetSlider("روشنی چشم", eyes, 0f..1f, enabled = ready, onChange = { value, _ -> eyes = value })
        SheetSlider("سفیدی دندان", teeth, 0f..1f, enabled = ready, onChange = { value, _ -> teeth = value })
        SheetChips {
            SheetChip("حذف قرمزی چشم", chosen = redEye, enabled = ready) { redEye = !redEye }
        }

        SheetSection("آرایش")
        MakeupRow("رژ لب", lipstick, LIP_SWATCHES, ready) { lipstick = it }
        SheetSlider("شدت رژ", lipStrength, 0f..1f, enabled = ready && lipstick != null, onChange = { value, _ ->
            lipStrength = value
        })
        MakeupRow("رژگونه", blush, BLUSH_SWATCHES, ready) { blush = it }
        MakeupRow("سایهٔ چشم", eyeshadow, SHADOW_SWATCHES, ready) { eyeshadow = it }
        MakeupRow("ابرو", brows, BROW_SWATCHES, ready) { brows = it }
        // Why it does not look like a sticker, which is the difference people notice without naming.
        SheetHint("رنگ روی بافت خود عکس می‌نشیند — سایه و برق لب از زیرش می‌آید")

        SheetSection("تغییر شکل")
        for (adjustment in FaceReshape.Adjustment.entries) {
            SheetSlider(
                adjustment.persianLabel,
                reshape[adjustment] ?: 0f,
                -1f..1f,
                enabled = ready,
                onChange = { value, _ -> reshape[adjustment] = value },
            )
        }
        SheetHint("حالت صُلب: چرخش آزاد است، کشیدگی نه — تنها حالتی که چهره را همان چهره نگه می‌دارد")

        SheetAction("اعمال روی چهره", enabled = ready) {
            scope.launch {
                model.applyFaceSettings(
                    FaceTools.Settings(
                        smoothSkin = smooth,
                        reduceShine = shine,
                        whitenTeeth = teeth,
                        brightenEyes = eyes,
                        removeRedEye = redEye,
                        lipstick = lipstick,
                        lipstickStrength = lipStrength,
                        blush = blush,
                        eyeshadow = eyeshadow,
                        brows = brows,
                        reshape = reshape.toMap(),
                    ),
                )
            }
        }
        SheetHint("همهٔ تنظیم‌ها با هم و در یک گذر اعمال می‌شوند — تصویر فقط یک بار بازنمونه‌برداری می‌شود")
    }
}

/** A named makeup colour and an "off" chip, which has to be as easy to press as the colours. */
@Composable
private fun MakeupRow(
    label: String,
    chosen: Color?,
    swatches: List<Pair<String, Color>>,
    enabled: Boolean,
    onPick: (Color?) -> Unit,
) {
    SheetChips {
        SheetChip("$label — بدون", chosen = chosen == null, enabled = enabled) { onPick(null) }
        for ((name, colour) in swatches) {
            SheetChip(name, chosen = chosen == colour, enabled = enabled, tint = colour.toCompose()) {
                onPick(colour)
            }
        }
    }
}

private fun Color.toCompose() = androidx.compose.ui.graphics.Color(r, g, b, 1f)

/**
 * The swatches, chosen rather than generated.
 *
 * A colour picker would be more general and worse: makeup colours are a narrow, well-known set, and
 * a user who has to mix a lipstick from a wheel will produce one that does not exist. The picker is
 * still one tap away in the brush sheet for anyone who wants it.
 */
private val LIP_SWATCHES = listOf(
    "کالباسی" to Color(0.78f, 0.33f, 0.34f),
    "قرمز" to Color(0.72f, 0.13f, 0.17f),
    "صورتی" to Color(0.88f, 0.45f, 0.55f),
    "شرابی" to Color(0.45f, 0.12f, 0.22f),
    "صدفی" to Color(0.75f, 0.52f, 0.46f),
)

private val BLUSH_SWATCHES = listOf(
    "هلویی" to Color(0.95f, 0.63f, 0.52f),
    "صورتی" to Color(0.93f, 0.6f, 0.65f),
    "برنزه" to Color(0.78f, 0.52f, 0.36f),
)

private val SHADOW_SWATCHES = listOf(
    "قهوه‌ای" to Color(0.45f, 0.32f, 0.26f),
    "طلایی" to Color(0.76f, 0.6f, 0.32f),
    "زغالی" to Color(0.28f, 0.27f, 0.3f),
    "بنفش" to Color(0.42f, 0.3f, 0.48f),
)

private val BROW_SWATCHES = listOf(
    "قهوه‌ای تیره" to Color(0.28f, 0.19f, 0.14f),
    "مشکی" to Color(0.13f, 0.12f, 0.12f),
    "بلوطی" to Color(0.42f, 0.28f, 0.18f),
)

private const val DEFAULT_LIP = 0.5f
