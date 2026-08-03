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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import ir.pixellab.core.editor.EditorState
import ir.pixellab.core.model.Adjustment
import ir.pixellab.core.model.ChannelRecipe
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.ColorFamily
import ir.pixellab.core.model.ColorRange
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.GradientStop
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.Vec3
import ir.pixellab.core.render.ParameterSpec
import kotlinx.coroutines.launch

/**
 * The adjustment sheet.
 *
 * Two states in one place: with no adjustment layer selected it offers all twenty-two to add, and with
 * one selected it edits it. Splitting those into separate screens is the arrangement that makes a
 * user add a Curves layer, lose it behind a panel, and add a second one.
 *
 * Every control writes back through the editor, so a slider drag is one undo entry rather than
 * ninety.
 */
@Composable
fun AdjustmentSheetBody(
    state: EditorState,
    model: EditorViewModel,
    onImportPreset: () -> Unit = {},
    /** Opens a picker for a `.cube` grading table. */
    onImportLut: () -> Unit = {},
    /**
     * Draws a document and hands back its pixels.
     *
     * Three of the twenty-two need a measurement of the picture rather than only of the pixel under
     * them, and this is how they get one. Defaulted to nothing so a preview or a test can build the
     * sheet without a GL context; the measure buttons simply do nothing then.
     */
    render: suspend (ir.pixellab.core.model.Document) -> ir.pixellab.core.codec.RasterImage? = { null },
    modifier: Modifier = Modifier,
) {
    val selected = state.primaryLayer as? Layer.AdjustmentLayer
    // Local rather than editor state: which of the two tabs is open is not something the document
    // knows about, and it is not worth an undo step.
    var filters by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        // Two tabs, and the label on each says which kind of change it makes. A user who has just
        // spent an hour on non-destructive adjustment layers needs to be told, once, that the
        // things on the other tab are permanent.
        SheetChips {
            SheetChip("تنظیم‌های برگشت‌پذیر", chosen = !filters) { filters = false }
            SheetChip("فیلترهای پیکسلی", chosen = filters) { filters = true }
        }
        if (filters) {
            FilterSheetBody(state, model)
            return@Column
        }

        AddRow(model)
        // Beside the catalogue rather than buried in the Curves panel: a user with a folder of
        // presets is looking for a way in, not for a curve to edit.
        SheetAction("آوردن پریست منحنی (acv.)", onClick = onImportPreset)
        if (selected != null) {
            Text(
                selected.name,
                style = MaterialTheme.typography.labelMedium,
                color = Ink.TextMuted,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            Controls(state, selected, model, onImportPreset, onImportLut, render)
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
private fun Controls(
    state: EditorState,
    layer: Layer.AdjustmentLayer,
    model: EditorViewModel,
    onImportPreset: () -> Unit,
    onImportLut: () -> Unit,
    render: suspend (ir.pixellab.core.model.Document) -> ir.pixellab.core.codec.RasterImage?,
) {
    val id = layer.id
    val scope = rememberCoroutineScope()
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
            // The histogram sits above the sliders because Levels without one is guesswork: the
            // black and white points are decisions about *where the pixels are*, and the panel that
            // does not show them is asking the user to remember what they saw a moment ago.
            HistogramView(
                model.histogram(ir.pixellab.core.imaging.HistogramChannel.LUMINANCE),
                ir.pixellab.core.imaging.HistogramChannel.LUMINANCE,
            )
            SheetAction("تنظیم از روی هیستوگرام") {
                val histogram = model.histogram(ir.pixellab.core.imaging.HistogramChannel.LUMINANCE)
                val points = histogram?.let { ir.pixellab.core.imaging.Histogram.autoLevels(it) }
                if (points != null) {
                    model.setAdjustment(
                        id,
                        adjustment.copy(
                            inputBlack = points.first / MAX_CHANNEL,
                            inputWhite = points.second / MAX_CHANNEL,
                        ),
                    )
                }
            }
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
            HistogramView(
                model.histogram(ir.pixellab.core.imaging.HistogramChannel.LUMINANCE),
                ir.pixellab.core.imaging.HistogramChannel.LUMINANCE,
            )
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
            // The real editor, which existed all along and this panel could not reach. It offered a
            // colour picker wired to the *last stop only*, so a duotone could be given its highlight
            // and never its shadow, and a third stop could not be added at all — on the one
            // adjustment whose entire purpose is the ramp.
            GradientEditorBody(
                gradient = adjustment.gradient,
                onChange = { model.setAdjustment(id, adjustment.copy(gradient = it)) },
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

        is Adjustment.ColorLookup -> {
            // The table itself, which had no way in at all: the panel used to create this layer
            // with a placeholder id nothing ever supplied, so the whole adjustment rendered as
            // untouched pixels no matter what the amount slider said.
            SheetHint(
                if (adjustment.asset.value.startsWith("lut:")) {
                    "جدول: " + adjustment.asset.value.removePrefix("lut:")
                } else {
                    "هنوز جدولی انتخاب نشده — تا آن زمان تصویر را تغییر نمی‌دهد"
                },
            )
            SheetAction("آوردن جدول رنگ (cube.)", onClick = onImportLut)
            Slider("مقدار", adjustment.amount, 0f..1f) {
                model.setAdjustment(id, adjustment.copy(amount = it))
            }
        }

        is Adjustment.ShadowsHighlights -> {
            SheetHint("سایه‌ها و روشنایی‌ها را بر اساس تاریکی *ناحیهٔ* اطراف هر پیکسل باز می‌کند")
            SectionLabel("سایه‌ها")
            Slider("مقدار", adjustment.shadowAmount, 0f..1f) {
                model.setAdjustment(id, adjustment.copy(shadowAmount = it))
            }
            Slider("گسترهٔ تن", adjustment.shadowTone, 0f..1f) {
                model.setAdjustment(id, adjustment.copy(shadowTone = it))
            }
            Slider("شعاع", adjustment.shadowRadius, 0f..250f) {
                model.setAdjustment(id, adjustment.copy(shadowRadius = it))
            }
            SectionLabel("روشنایی‌ها")
            Slider("مقدار", adjustment.highlightAmount, 0f..1f) {
                model.setAdjustment(id, adjustment.copy(highlightAmount = it))
            }
            Slider("گسترهٔ تن", adjustment.highlightTone, 0f..1f) {
                model.setAdjustment(id, adjustment.copy(highlightTone = it))
            }
            Slider("شعاع", adjustment.highlightRadius, 0f..250f) {
                model.setAdjustment(id, adjustment.copy(highlightRadius = it))
            }
            SectionLabel("تنظیم‌ها")
            Slider("رنگ", adjustment.color, -1f..1f) {
                model.setAdjustment(id, adjustment.copy(color = it))
            }
            Slider("کنتراست میان‌تن", adjustment.midtoneContrast, -1f..1f) {
                model.setAdjustment(id, adjustment.copy(midtoneContrast = it))
            }
            // The two clips are percentiles, so changing one has to re-measure the picture; the
            // slider writes the fraction and the editor writes back where it landed.
            Slider("برش سیاه", adjustment.blackClip, 0f..0.5f) {
                model.setAdjustment(id, adjustment.copy(blackClip = it))
            }
            Slider("برش سفید", adjustment.whiteClip, 0f..0.5f) {
                model.setAdjustment(id, adjustment.copy(whiteClip = it))
            }
            // A clip is a percentile, so the two sliders above say *how much* to cut and this says
            // where that lands in this particular picture. Measured on demand rather than on every
            // drag, because it costs a full render of everything beneath the layer.
            SheetAction("اندازه‌گیری نقاط برش") { scope.launch { model.measureAdjustment(id, render) } }
            SheetHint(
                "نقاط اندازه‌گیری‌شده: " +
                    "%.3f".format(adjustment.blackPoint) + " تا " + "%.3f".format(adjustment.whitePoint),
            )
        }

        is Adjustment.HdrToning -> {
            SheetChips {
                for (option in ir.pixellab.core.model.HdrMethod.entries) {
                    SheetChip(option.persianLabel, chosen = adjustment.method == option) {
                        model.setAdjustment(id, adjustment.copy(method = option))
                    }
                }
            }
            Slider("نوردهی", adjustment.exposure, -5f..5f) {
                model.setAdjustment(id, adjustment.copy(exposure = it))
            }
            Slider("گاما", adjustment.gamma, 0.1f..2f) {
                model.setAdjustment(id, adjustment.copy(gamma = it))
            }
            // Only local adaptation has a base layer, so only it has a radius, a strength and a
            // detail. Showing the other three anyway is how a panel teaches the wrong model.
            if (adjustment.method == ir.pixellab.core.model.HdrMethod.LOCAL_ADAPTATION) {
                SheetHint("شعاع تعیین می‌کند «ناحیه» چقدر بزرگ است — کوچک، هالهٔ لبه می‌سازد")
                Slider("شعاع", adjustment.radius, 1f..250f) {
                    model.setAdjustment(id, adjustment.copy(radius = it))
                }
                Slider("شدت", adjustment.strength, 0.1f..4f) {
                    model.setAdjustment(id, adjustment.copy(strength = it))
                }
                Slider("جزئیات", adjustment.detail, -3f..3f) {
                    model.setAdjustment(id, adjustment.copy(detail = it))
                }
            }
            Slider("سایه", adjustment.shadow, -1f..1f) {
                model.setAdjustment(id, adjustment.copy(shadow = it))
            }
            Slider("روشنایی", adjustment.highlight, -1f..1f) {
                model.setAdjustment(id, adjustment.copy(highlight = it))
            }
            Slider("سرزندگی", adjustment.vibrance, -1f..1f) {
                model.setAdjustment(id, adjustment.copy(vibrance = it))
            }
            Slider("اشباع", adjustment.saturation, -1f..1f) {
                model.setAdjustment(id, adjustment.copy(saturation = it))
            }
        }

        Adjustment.Desaturate -> SheetHint(
            "خاکستری از میانهٔ روشن‌ترین و تاریک‌ترین کانال — همان کاری که فتوشاپ می‌کند، نه لومای وزنی",
        )

        is Adjustment.MatchColor -> {
            SheetHint(
                if (adjustment.statistics.measured) {
                    "آمار منبع اندازه‌گیری شده و در لایه ذخیره است"
                } else {
                    "یک تصویر منبع انتخاب کنید و اندازه‌گیری بزنید — تا آن زمان لایه بی‌اثر است"
                },
            )
            SectionLabel("منبع")
            // Photoshop's Source dropdown, as chips: every image the document carries. Named by
            // layer, keyed by asset, because a layer can be renamed or merged away and the pixels
            // the statistics were taken from cannot.
            SheetChips {
                for (image in state.document.walk().filterIsInstance<Layer.Image>()) {
                    SheetChip(image.name, chosen = adjustment.source == image.asset) {
                        model.setAdjustment(id, adjustment.copy(source = image.asset))
                    }
                }
            }
            SheetAction("اندازه‌گیری منبع", enabled = adjustment.source != null) {
                scope.launch { model.measureAdjustment(id, render) }
            }
            Slider("روشنایی", adjustment.luminance, 0f..2f) {
                model.setAdjustment(id, adjustment.copy(luminance = it))
            }
            Slider("شدت رنگ", adjustment.colorIntensity, 0f..2f) {
                model.setAdjustment(id, adjustment.copy(colorIntensity = it))
            }
            Slider("محو", adjustment.fade, 0f..1f) {
                model.setAdjustment(id, adjustment.copy(fade = it))
            }
            Toggle("خنثی‌سازی", adjustment.neutralize) {
                model.setAdjustment(id, adjustment.copy(neutralize = it))
            }
        }

        is Adjustment.ReplaceColor -> {
            Slider("گستردگی", adjustment.fuzziness, 0f..1f) {
                model.setAdjustment(id, adjustment.copy(fuzziness = it))
            }
            Toggle("خوشه‌های رنگی موضعی", adjustment.localized) {
                model.setAdjustment(id, adjustment.copy(localized = it))
            }
            SectionLabel("نتیجه")
            Slider("رنگ‌مایه", adjustment.hue, -0.5f..0.5f) {
                model.setAdjustment(id, adjustment.copy(hue = it))
            }
            Slider("اشباع", adjustment.saturation, -1f..1f) {
                model.setAdjustment(id, adjustment.copy(saturation = it))
            }
            Slider("روشنی", adjustment.lightness, -1f..1f) {
                model.setAdjustment(id, adjustment.copy(lightness = it))
            }
            SectionLabel("رنگ هدف")
            ColorPickerBody(
                color = adjustment.target,
                onChange = { model.setAdjustment(id, adjustment.copy(target = it)) },
            )
        }

        is Adjustment.Equalize -> {
            SheetHint(
                if (adjustment.table.isEmpty()) {
                    "هنوز اندازه‌گیری نشده — تا آن زمان تصویر را تغییر نمی‌دهد"
                } else {
                    "نگاشت از هیستوگرام تجمعی همین تصویر ساخته شده و پارامتری ندارد"
                },
            )
            SheetAction("اندازه‌گیری") { scope.launch { model.measureAdjustment(id, render) } }
        }

        is Adjustment.SelectiveColor -> {
            // One family at a time, because nine families of four inks is thirty-six sliders and a
            // panel of thirty-six sliders is one nobody reads. Photoshop shows one too.
            var family by remember { mutableStateOf(ColorFamily.REDS) }
            SheetChips {
                for (option in ColorFamily.entries) {
                    SheetChip(option.persianLabel, chosen = family == option) { family = option }
                }
            }
            val range = adjustment.ranges.firstOrNull { it.family == family } ?: ColorRange(family)
            fun put(next: ColorRange) {
                val ranges = adjustment.ranges.filter { it.family != family } + next
                model.setAdjustment(id, adjustment.copy(ranges = ranges.sortedBy { it.family.ordinal }))
            }
            Slider("فیروزه‌ای", range.cyan, -1f..1f) { put(range.copy(cyan = it)) }
            Slider("سرخابی", range.magenta, -1f..1f) { put(range.copy(magenta = it)) }
            Slider("زرد", range.yellow, -1f..1f) { put(range.copy(yellow = it)) }
            Slider("سیاه", range.black, -1f..1f) { put(range.copy(black = it)) }
            Toggle("مطلق", adjustment.absolute) { model.setAdjustment(id, adjustment.copy(absolute = it)) }
            SheetHint(
                if (adjustment.absolute) {
                    "مطلق مقدار کامل را اضافه می‌کند — برای گریدهای سنگین"
                } else {
                    "نسبی به اندازهٔ جوهری که پیکسل دارد تغییر می‌دهد — امن روی پوست"
                },
            )
        }

        is Adjustment.ChannelMixer -> {
            Toggle("تک‌رنگ", adjustment.monochrome) {
                model.setAdjustment(id, adjustment.copy(monochrome = it))
            }
            if (adjustment.monochrome) {
                // A red filter on black-and-white film is exactly red 1, green 0, blue 0 — which is
                // how a pale sky becomes dramatic, and why the mixer beats desaturation.
                RecipeRow("خاکستری", adjustment.gray) { model.setAdjustment(id, adjustment.copy(gray = it)) }
                SheetHint("جمع سه ضریب نزدیک به ۱ باشد، وگرنه تصویر روشن‌تر یا تیره‌تر از اصل می‌شود")
            } else {
                var output by remember { mutableStateOf(CurveChannel.RED) }
                SheetChips {
                    for (option in listOf(CurveChannel.RED, CurveChannel.GREEN, CurveChannel.BLUE)) {
                        SheetChip(option.label, chosen = output == option) { output = option }
                    }
                }
                val recipe = when (output) {
                    CurveChannel.GREEN -> adjustment.green
                    CurveChannel.BLUE -> adjustment.blue
                    else -> adjustment.red
                }
                RecipeRow(output.label, recipe) { next ->
                    model.setAdjustment(
                        id,
                        when (output) {
                            CurveChannel.GREEN -> adjustment.copy(green = next)
                            CurveChannel.BLUE -> adjustment.copy(blue = next)
                            else -> adjustment.copy(red = next)
                        },
                    )
                }
            }
        }
    }
}

/**
 * A heading inside one adjustment's controls.
 *
 * Three of the panels carry two or three groups of sliders that share names — Shadows and
 * Highlights both have an Amount, a Tone and a Radius — and without the heading the second Amount
 * looks like a duplicate of the first.
 */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = Ink.TextMuted,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp),
    )
}

/** One output channel's recipe: how much of each input, and a constant to lift or drop it. */
@Composable
private fun RecipeRow(label: String, recipe: ChannelRecipe, onChange: (ChannelRecipe) -> Unit) {
    Text(
        "خروجی $label",
        style = MaterialTheme.typography.labelMedium,
        color = Ink.TextMuted,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp),
    )
    // Past ±200 % the result is clipping in every picture, which is the range Photoshop stops at.
    Slider("از قرمز", recipe.red, -2f..2f) { onChange(recipe.copy(red = it)) }
    Slider("از سبز", recipe.green, -2f..2f) { onChange(recipe.copy(green = it)) }
    Slider("از آبی", recipe.blue, -2f..2f) { onChange(recipe.copy(blue = it)) }
    Slider("ثابت", recipe.constant, -1f..1f) { onChange(recipe.copy(constant = it)) }
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

/**
 * All twenty-two, in the order Photoshop's own Adjustments menu lists them.
 *
 * The order is not cosmetic. Photoshop groups them by what they act on — tone, then colour, then
 * the ones that throw information away, then the two that read a neighbourhood, then the four that
 * take no parameters or need a second picture — and anyone who has used it navigates by position
 * long before they read the labels. Sorting these alphabetically, or by when they were written,
 * would make a familiar list unfamiliar for no gain.
 */
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
    "میکسر کانال" to { Adjustment.ChannelMixer() },
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
    "جدول رنگ" to { Adjustment.ColorLookup(ir.pixellab.core.model.AssetId("")) },
    "رنگ انتخابی" to { Adjustment.SelectiveColor() },
    "سایه‌ها/روشنایی‌ها" to { Adjustment.ShadowsHighlights() },
    "تنالیتهٔ HDR" to { Adjustment.HdrToning() },
    "کاهش اشباع" to { Adjustment.Desaturate },
    "تطبیق رنگ" to { Adjustment.MatchColor() },
    "جایگزینی رنگ" to { Adjustment.ReplaceColor() },
    "یکنواخت‌سازی" to { Adjustment.Equalize() },
)

/** Photoshop's own 0..255, which is what the histogram counts in and Levels stores as a fraction. */
private const val MAX_CHANNEL = 255f
