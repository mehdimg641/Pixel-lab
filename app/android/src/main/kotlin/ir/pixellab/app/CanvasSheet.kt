package ir.pixellab.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ir.pixellab.core.editor.AspectRatio
import ir.pixellab.core.editor.CanvasAnchor
import ir.pixellab.core.editor.EditorState
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Fill
import kotlinx.coroutines.launch

/**
 * The canvas and what arrives on it.
 *
 * Four things that have nothing in common except *when* they happen: they are all the first or last
 * thing a user does. Set the size, put a photograph on it, crop it down, fill what is left. Spread
 * across four places they would each be a hunt; together they are one sheet that answers "the shape
 * of this document is wrong".
 */
@Composable
fun CanvasSheetBody(
    state: EditorState,
    model: EditorViewModel,
    onPickImage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canvas = state.document.canvas
    val scope = rememberCoroutineScope()

    // Seeded from the document and re-seeded whenever it changes underneath — otherwise a resize
    // done from a template would leave stale numbers in the fields.
    var width by remember(canvas.width) { mutableStateOf(canvas.width.toString()) }
    var height by remember(canvas.height) { mutableStateOf(canvas.height.toString()) }
    var anchor by remember { mutableStateOf(CanvasAnchor.CENTER) }
    var locked by remember { mutableStateOf(false) }
    var preserveTransparency by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        SheetSection("اندازهٔ بوم")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SheetNumberField("عرض", width, modifier = Modifier.weight(1f)) { entry ->
                width = digits(entry)
                if (locked) height = scaled(width, canvas.height, canvas.width)
            }
            SheetNumberField("ارتفاع", height, modifier = Modifier.weight(1f)) { entry ->
                height = digits(entry)
                if (locked) width = scaled(height, canvas.width, canvas.height)
            }
        }
        SheetChips {
            SheetChip("قفل نسبت", chosen = locked) { locked = !locked }
            for (factor in SCALE_STEPS) {
                SheetChip(factor.label) {
                    width = (canvas.width * factor.value).toInt().coerceAtLeast(1).toString()
                    height = (canvas.height * factor.value).toInt().coerceAtLeast(1).toString()
                }
            }
            SheetChip("مربع") {
                // The larger side, so squaring a document never throws part of it off the canvas.
                val side = maxOf(canvas.width, canvas.height).toString()
                width = side
                height = side
            }
        }

        SheetSection("لنگر")
        AnchorGrid(anchor) { anchor = it }
        SheetHint("کارِ روی بوم نسبت به این نقطه سرِ جایش می‌ماند")

        SheetAction("اعمال اندازه", enabled = width.isNotBlank() && height.isNotBlank()) {
            model.resizeCanvas(
                width.toIntOrNull() ?: canvas.width,
                height.toIntOrNull() ?: canvas.height,
                anchor,
            )
        }

        SheetSection("برش")
        // The ratio row first, because it is the answer to "what am I cropping this for?" and every
        // reference app puts it before the frame. Freeform stays a chip rather than being the absence
        // of one, so turning the constraint off is as visible as turning it on.
        SheetChips {
            SheetChip("آزاد", chosen = model.select.ratio == null) { model.cropRatio(null) }
            SheetChip("کل بوم") {
                model.cropRatio(null)
                model.select.frame(canvas.width, canvas.height)
            }
            for ((label, ratio) in AspectRatio.PRESETS) {
                SheetChip(label, chosen = model.select.ratio == ratio) { model.cropRatio(ratio) }
            }
            SheetChip("چرخاندن نسبت", enabled = model.select.ratio != null) { model.flipCropRatio() }
        }
        SheetAction("برش به قاب", enabled = model.select.selection != null) { model.cropToSelection() }
        SheetHint(
            when {
                model.select.ratio != null ->
                    "قاب روی بوم است — جابه‌جایش کنید یا دوباره بکشید؛ نسبتش ثابت می‌ماند"
                model.select.selection == null -> "یک نسبت بزنید، یا با ابزار انتخاب قابی بکشید"
                else -> "بوم به کادرِ دربرگیرندهٔ انتخاب کوچک می‌شود"
            },
        )

        SheetSection("پر کردن")
        SheetHint(
            if (model.select.selection == null) {
                "چیزی انتخاب نشده — کل لایه پر می‌شود"
            } else {
                "فقط داخل انتخاب پر می‌شود، با همان نرمی لبه"
            },
        )
        SheetChips {
            for (swatch in FILL_SWATCHES) {
                SheetChip(swatch.label) {
                    scope.launch { model.fillSelection(swatch.color, preserveTransparency) }
                }
            }
            SheetChip("شفاف") { scope.launch { model.eraseSelection() } }
            SheetChip("قفل شفافیت", chosen = preserveTransparency) {
                preserveTransparency = !preserveTransparency
            }
        }
        SheetHint("با قفل شفافیت، رنگ فقط جایی می‌نشیند که لایه از قبل پیکسل دارد")

        SheetSection("پس‌زمینهٔ بوم")
        SheetChips {
            for (swatch in FILL_SWATCHES) {
                SheetChip(
                    swatch.label,
                    chosen = (canvas.background as? Fill.Solid)?.color == swatch.color,
                ) {
                    model.setCanvasBackground(Fill.Solid(swatch.color))
                }
            }
            SheetChip("بدون پس‌زمینه", chosen = canvas.background == null) {
                model.setCanvasBackground(null)
            }
        }

        SheetSection("باز کردن فایل")
        SheetSection("تفکیک‌پذیری")
        SheetNumberField("DPI", canvas.dpi.toString()) { typed ->
            typed.toIntOrNull()?.let { model.setCanvasDpi(it) }
        }
        SheetChips {
            for (preset in listOf(72, 150, 300, 600)) {
                SheetChip("$preset", chosen = canvas.dpi == preset) { model.setCanvasDpi(preset) }
            }
        }
        // What DPI does and does not do, because it is the number people expect to resize the file.
        SheetHint("DPI اندازهٔ فیزیکی خروجی PDF را می‌سازد — تعداد پیکسل‌ها را عوض نمی‌کند")

        SheetSection("عمق بیت")
        SheetChips {
            for (option in ir.pixellab.core.model.Precision.entries) {
                SheetChip(
                    "${option.bitsPerChannel} بیت",
                    chosen = state.document.color.precision == option,
                ) { model.act { setPrecision(option) } }
            }
        }
        // Why anyone would pay twice the memory for it, said in terms of what they are making.
        SheetHint(
            "۱۶ بیت برای استایل‌های چندلایه: هر گذر در ۸ بیت گرد می‌شود و باندینگی که به تصویر " +
                "نهایی می‌رسد دیگر برنمی‌گردد",
        )

        SheetAction("تصویر را به‌عنوان لایه بیاور", onClick = onPickImage)
        SheetHint("تصویر بزرگ‌تر از بوم کوچک می‌شود تا دستگیره‌هایش روی صفحه بماند")

        SheetSection("شبکه و راهنما")
        SheetAction("شبکه، خط‌کش و راهنماها") {
            model.act {
                openSheet(
                    ir.pixellab.core.editor.SheetContent.Guides,
                    ir.pixellab.core.editor.SheetDetent.FULL,
                )
            }
        }
    }
}

/**
 * The nine-cell anchor grid.
 *
 * Drawn as a grid rather than offered as two dropdowns because the choice is spatial: the user is
 * pointing at where their work should end up, and every wording of "top, vertically" has to be read
 * twice.
 */
@Composable
private fun AnchorGrid(chosen: CanvasAnchor, onPick: (CanvasAnchor) -> Unit) {
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (row in ANCHOR_ROWS) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (anchor in row) {
                    Box(
                        Modifier
                            .size(Space.touch)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (anchor == chosen) Ink.Accent.copy(alpha = 0.28f) else Ink.Chrome)
                            .border(
                                width = 1.dp,
                                color = if (anchor == chosen) Ink.Accent else Ink.Divider,
                                shape = RoundedCornerShape(6.dp),
                            )
                            .clickable { onPick(anchor) }
                            // Nine cells that were 40dp and anonymous. A grid whose whole point is
                            // that the choice is *spatial* is the worst possible control to leave
                            // unnamed: there is no label, no icon and no text anywhere in it, so
                            // without this a screen reader announces nine identical buttons and the
                            // spatial meaning — the entire reason it is a grid — is gone.
                            .semantics {
                                role = Role.RadioButton
                                selected = anchor == chosen
                                contentDescription = anchor.persianLabel
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (anchor == chosen) {
                            Box(Modifier.size(DOT.dp).clip(RoundedCornerShape(3.dp)).background(Ink.Accent))
                        }
                    }
                }
            }
        }
    }
}

/** Only digits reach the field, so a resize can never be handed a value it has to reject. */
private fun digits(entry: String) = entry.filter { it.isDigit() }.take(MAX_DIGITS)

/** Keeps the other dimension in proportion while the aspect lock is on. */
private fun scaled(entry: String, other: Int, source: Int): String {
    val value = entry.toIntOrNull() ?: return ""
    if (source <= 0) return other.toString()
    return (value.toLong() * other / source).toInt().coerceAtLeast(1).toString()
}

private data class ScaleStep(val label: String, val value: Float)

/** The three every design gets resized by, and nothing else: this is a shortcut, not a menu. */
private val SCALE_STEPS = listOf(
    ScaleStep("۵۰٪", 0.5f),
    ScaleStep("۱۵۰٪", 1.5f),
    ScaleStep("۲۰۰٪", 2f),
)

private data class Swatch(val label: String, val color: Color)

/**
 * Four fills, not a picker.
 *
 * The colour picker already exists and is one tap away in the brush sheet. What belongs here are the
 * fills someone reaches for without thinking about colour at all: black, white, and the two that
 * make a mask readable.
 */
private val FILL_SWATCHES = listOf(
    Swatch("سفید", Color.WHITE),
    Swatch("مشکی", Color.BLACK),
    Swatch("خاکستری", Color(0.5f, 0.5f, 0.5f)),
)

private val ANCHOR_ROWS = listOf(
    listOf(CanvasAnchor.TOP_LEFT, CanvasAnchor.TOP, CanvasAnchor.TOP_RIGHT),
    listOf(CanvasAnchor.LEFT, CanvasAnchor.CENTER, CanvasAnchor.RIGHT),
    listOf(CanvasAnchor.BOTTOM_LEFT, CanvasAnchor.BOTTOM, CanvasAnchor.BOTTOM_RIGHT),
)

private const val DOT = 12

/** A canvas of a hundred thousand pixels a side is not a document, it is a mistake being typed. */
private const val MAX_DIGITS = 5
