package ir.pixellab.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ir.pixellab.core.editor.EditorState
import ir.pixellab.core.model.FontRef
import ir.pixellab.core.model.KashidaMode
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.TextAlign
import ir.pixellab.core.model.TextBoxMode
import ir.pixellab.core.model.TextDirection
import ir.pixellab.core.model.TextWarp
import ir.pixellab.core.model.WarpStyle

/**
 * The character and paragraph panels.
 *
 * Everything about type except which typeface — that is its own sheet, because picking a face is
 * something a user does once and nudging the tracking is something they do twenty times, and
 * putting both in one panel means scrolling past a font list to reach a slider.
 *
 * Two things here exist in no other mobile editor and are the reason this app is worth using for
 * Persian work at all: the kashida controls, and the stylistic-set toggles. Six of the supplied
 * typefaces ship alternate Persian letterforms behind `ss01`–`ss05`, and until now the only way to
 * reach them was to open the file in a desktop application.
 */
@Composable
fun TypeSheetBody(state: EditorState, model: EditorViewModel, modifier: Modifier = Modifier) {
    val layer = state.primaryLayer as? Layer.Text
    if (layer == null) {
        // A dead end until now: the panel said "select a text layer" and nothing in the application
        // could make one except the font picker, which is a different sheet and does not look like
        // the place you go to write a word. A panel that names a precondition has to offer the way
        // to meet it.
        Column(modifier.fillMaxWidth()) {
            SheetHint(
                if (model.canAddText) {
                    "لایهٔ متنی انتخاب نشده — یکی بسازید یا روی متنی روی بوم بزنید"
                } else {
                    "هنوز فونتی بارگذاری نشده — از تنظیمات یک پوشهٔ فونت اضافه کنید"
                },
            )
            SheetAction("افزودن متن", enabled = model.canAddText) { model.addTextLayer() }
        }
        return
    }

    val id = layer.id
    val spec = layer.spec
    val paragraph = spec.paragraph
    val typeface = model.typefaceFor(spec)

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {

        // ---- colour ------------------------------------------------------------------------

        // First, and it was missing entirely. The colour of the letters is the thing anyone changes
        // before anything else, and the only way to change it was to apply a whole saved style —
        // which also replaced the stroke, the shadow and everything else the user had set.
        SheetSection("رنگ")
        FillEditor(
            fill = layer.style.fill,
            model = model,
            onChange = { model.setLayerFill(id, it) },
        )

        // ---- character ---------------------------------------------------------------------

        SheetSection("نویسه")
        // A field as well as a slider: a cover is often set to an exact size taken from a brief,
        // and the slider's top is far below the sizes a poster uses.
        SheetNumberField("اندازه", spec.size.toInt().toString()) { typed ->
            typed.toFloatOrNull()?.let { model.setTextSize(id, it.coerceIn(MIN_SIZE, MAX_SIZE)) }
        }
        SheetSlider("اندازه", spec.size.coerceAtMost(SLIDER_MAX_SIZE), MIN_SIZE..SLIDER_MAX_SIZE, onChange = { value, _ ->
            model.setTextSize(id, value)
        })

        // Tracking in *em* rather than pixels, so it survives a resize. A value in pixels would
        // mean the letters drift apart when the layer is scaled up, which is the one thing tracking
        // must never do.
        SheetSlider("فاصلهٔ نویسه", paragraph.letterSpacing, -TRACKING..TRACKING, onChange = { value, _ ->
            model.setParagraph(id, paragraph.copy(letterSpacing = value))
        })
        SheetSlider("فاصلهٔ کلمه", paragraph.wordSpacing, -TRACKING..TRACKING, onChange = { value, _ ->
            model.setParagraph(id, paragraph.copy(wordSpacing = value))
        })

        if (typeface?.supportsPersianDigits == true) {
            SheetChips {
                SheetChip("رقم فارسی", chosen = paragraph.persianDigits) {
                    model.setParagraph(id, paragraph.copy(persianDigits = true))
                }
                SheetChip("رقم لاتین", chosen = !paragraph.persianDigits) {
                    model.setParagraph(id, paragraph.copy(persianDigits = false))
                }
            }
        }

        // ---- paragraph ---------------------------------------------------------------------

        SheetSection("بند")
        AlignmentControls(layer, model)

        // ---- kashida -----------------------------------------------------------------------

        SheetSection("کشیدگی")
        KashidaControls(layer, model)

        // ---- OpenType ----------------------------------------------------------------------

        val axes = typeface?.variableFile?.axes.orEmpty().filterKeys { it !in FontRef.KASHIDA_AXES }
        if (axes.isNotEmpty()) {
            SheetSection("محورهای فونت")
            for ((tag, range) in axes.entries.sortedBy { it.key }) {
                val current = spec.font.variations[tag] ?: range.start
                SheetSlider(tag.axisLabel, current, range, onChange = { value, _ ->
                    model.setFontAxis(id, tag, value)
                })
            }
        }

        val sets = typeface?.stylisticSets.orEmpty()
        if (sets.isNotEmpty()) {
            SheetSection("مجموعه‌های سبکی")
            SheetChips {
                for (tag in sets) {
                    SheetChip(tag, chosen = spec.font.features[tag] == 1) {
                        model.setFontFeature(id, tag, spec.font.features[tag] != 1)
                    }
                }
            }
            SheetHint("شکل‌های جایگزین حروف که در خود فونت هست — ارزان‌ترین راه برای جلدی که شبیه بقیه نباشد")
        }

        // ---- warp --------------------------------------------------------------------------

        // The way through to the panel this application exists for. Placed at the end of the type
        // panel rather than on the toolbar because 3D is the *last* decision about a headline —
        // the face, the size and the tracking all have to be settled first.
        SheetSection("سه‌بعدی")
        SheetAction("متن سه‌بعدی واقعی") {
            model.act {
                openSheet(
                    ir.pixellab.core.editor.SheetContent.Dimensional,
                    ir.pixellab.core.editor.SheetDetent.FULL,
                )
            }
        }

        SheetSection("تاب متن")
        WarpControls(layer, model)
    }
}

/**
 * Alignment, direction, leading and the point-or-area choice.
 *
 * Shared with the text studio rather than written twice. Two implementations of the same panel is
 * how «رنگ» ended up meaning one thing in one sheet and another somewhere else, and a control that
 * exists in two places will be fixed in one of them.
 */
@Composable
internal fun AlignmentControls(layer: Layer.Text, model: EditorViewModel) {
    val id = layer.id
    val spec = layer.spec
    val paragraph = spec.paragraph

    SheetChips {
        for (align in TextAlign.entries) {
            SheetChip(align.persianLabel, chosen = paragraph.align == align) {
                model.setParagraph(id, paragraph.copy(align = align))
            }
        }
    }
    SheetChips {
        for (direction in TextDirection.entries) {
            SheetChip(direction.persianLabel, chosen = paragraph.direction == direction) {
                model.setParagraph(id, paragraph.copy(direction = direction))
            }
        }
    }
    // Automatic is the right default and the reason is worth saying once: a caption that mixes
    // Persian with a Latin brand name has to resolve its direction per paragraph, from the first
    // strong character, or the punctuation lands on the wrong end.
    SheetHint("خودکار جهت هر بند را از اولین حرف قوی می‌گیرد — درست برای متن فارسی و لاتین باهم")

    // A multiplier, not a point size: line height set in points has to be reset every time the type
    // size changes, and nobody remembers to.
    SheetSlider("ارتفاع خط", paragraph.lineHeight, MIN_LEADING..MAX_LEADING, onChange = { value, _ ->
        model.setParagraph(id, paragraph.copy(lineHeight = value))
    })
    SheetSlider("فاصلهٔ بند", paragraph.paragraphSpacing, 0f..MAX_PARAGRAPH_GAP, onChange = { value, _ ->
        model.setParagraph(id, paragraph.copy(paragraphSpacing = value))
    })
    SheetSlider("تورفتگی", paragraph.indent, 0f..MAX_INDENT, onChange = { value, _ ->
        model.setParagraph(id, paragraph.copy(indent = value))
    })

    SheetChips {
        SheetChip("نقطه‌ای", chosen = spec.boxMode == TextBoxMode.POINT) {
            model.setTextBox(id, area = false)
        }
        SheetChip("کادردار", chosen = spec.boxMode == TextBoxMode.AREA) {
            model.setTextBox(id, area = true)
        }
    }
    SheetHint("نقطه‌ای با نوشتن پهن‌تر می‌شود؛ کادردار داخل کادر می‌شکند و بلندتر می‌شود")
}

/** The elongation controls — the two mechanisms and how much of one to use. */
@Composable
internal fun KashidaControls(layer: Layer.Text, model: EditorViewModel) {
    val id = layer.id
    val paragraph = layer.spec.paragraph
    val typeface = model.typefaceFor(layer.spec)

    SheetChips {
        for (mode in KashidaMode.entries) {
            val usable = mode != KashidaMode.VARIABLE_AXIS || typeface?.hasKashidaAxis == true
            SheetChip(mode.persianLabel, chosen = paragraph.kashida == mode, enabled = usable) {
                model.setParagraph(id, paragraph.copy(kashida = mode))
            }
        }
    }
    SheetSlider("مقدار کشیدگی", paragraph.kashidaAmount, 0f..1f, onChange = { value, _ ->
        model.setParagraph(id, paragraph.copy(kashidaAmount = value))
    })
    SheetHint(
        when {
            typeface?.hasKashidaAxis == true ->
                "این فونت محور کشیدگی دارد — حرف واقعاً کشیده می‌شود و متن دست‌نخورده می‌ماند"
            // The honest version. Tatweel changes the string itself, and a user who later copies
            // the text out gets a row of U+0640 they did not type.
            else -> "این فونت محور کشیدگی ندارد — با تطویل کشیده می‌شود که کاراکتر به متن اضافه می‌کند"
        },
    )
}

/** The sixteen warp shapes and the three numbers that steer whichever is chosen. */
@Composable
internal fun WarpControls(layer: Layer.Text, model: EditorViewModel) {
    val id = layer.id
    val warp = layer.spec.warp

    SheetChips {
        for (style in WarpStyle.entries) {
            SheetChip(style.persianLabel, chosen = warp.style == style) {
                model.setTextWarp(id, warp.copy(style = style))
            }
        }
    }
    if (!warp.isActive) return

    SheetSlider("خمش", warp.bend, -1f..1f, onChange = { value, _ ->
        model.setTextWarp(id, warp.copy(bend = value))
    })
    SheetSlider("اعوجاج افقی", warp.horizontal, -1f..1f, onChange = { value, _ ->
        model.setTextWarp(id, warp.copy(horizontal = value))
    })
    SheetSlider("اعوجاج عمودی", warp.vertical, -1f..1f, onChange = { value, _ ->
        model.setTextWarp(id, warp.copy(vertical = value))
    })
    SheetChips {
        SheetChip("محور افقی", chosen = warp.horizontalAxis) {
            model.setTextWarp(id, warp.copy(horizontalAxis = true))
        }
        SheetChip("محور عمودی", chosen = !warp.horizontalAxis) {
            model.setTextWarp(id, warp.copy(horizontalAxis = false))
        }
    }
    SheetAction("بدون تاب") { model.setTextWarp(id, TextWarp.NONE) }
}

private val TextAlign.persianLabel: String
    get() = when (this) {
        TextAlign.START -> "راست‌چین"
        TextAlign.CENTER -> "وسط‌چین"
        TextAlign.END -> "چپ‌چین"
        TextAlign.JUSTIFY -> "هم‌ترازی"
    }

private val TextDirection.persianLabel: String
    get() = when (this) {
        TextDirection.AUTO -> "خودکار"
        TextDirection.RTL -> "راست به چپ"
        TextDirection.LTR -> "چپ به راست"
    }

private val KashidaMode.persianLabel: String
    get() = when (this) {
        KashidaMode.NONE -> "بدون"
        KashidaMode.AUTO -> "خودکار"
        KashidaMode.VARIABLE_AXIS -> "محور فونت"
        KashidaMode.TATWEEL -> "تطویل"
    }

/** The four-character tags nobody outside type design recognises, named. */
private val String.axisLabel: String
    get() = when (this) {
        FontRef.AXIS_WEIGHT -> "وزن"
        FontRef.AXIS_WIDTH -> "عرض"
        FontRef.AXIS_SLANT -> "شیب"
        else -> this
    }

/** Below eight pixels the shaping is guesswork; above the slider's top the field still takes it. */
private const val MIN_SIZE = 8f
private const val SLIDER_MAX_SIZE = 400f
private const val MAX_SIZE = 4000f

/** A quarter of an em each way, which is already further than any real setting goes. */
private const val TRACKING = 0.25f

private const val MIN_LEADING = 0.6f
private const val MAX_LEADING = 3f

private const val MAX_PARAGRAPH_GAP = 200f
private const val MAX_INDENT = 400f
