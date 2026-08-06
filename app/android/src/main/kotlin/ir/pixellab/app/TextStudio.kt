package ir.pixellab.app

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Deblur
import androidx.compose.material.icons.outlined.Diamond
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.FontDownload
import androidx.compose.material.icons.outlined.FormatColorFill
import androidx.compose.material.icons.outlined.FormatLineSpacing
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Opacity
import androidx.compose.material.icons.outlined.Rectangle
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import ir.pixellab.core.editor.EditorState
import ir.pixellab.core.editor.SheetContent
import ir.pixellab.core.editor.SheetDetent
import ir.pixellab.core.editor.TextSection
import ir.pixellab.core.model.CharacterStyle
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Effect
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId
import ir.pixellab.core.text.Clusters
import ir.pixellab.core.text.Granularity

/**
 * Everything that can be done to a piece of text, in one panel.
 *
 * ### The shape, and why it is this shape
 *
 * Taken from the mobile editor people actually set Persian titles in: select the words, get a
 * labelled row of sections, tap one and it opens where you already are. This application had the
 * opposite — a sheet per concern, reached from a menu — so «رنگ» lived in one panel, «سایه» in
 * another and «سه‌بعدی» in a third, while all three are the same decision about the same headline.
 * Counting taps is the whole argument: setting a colour and then a shadow was five, and here it is
 * two.
 *
 * The section strip stays on screen rather than being a landing page you go back to, which is one
 * tap better than the editor it is modelled on. It scrolls, and it has the same edge fade the main
 * ribbon gained after a user photographed a control sliced in half by the screen edge and
 * reasonably read it as broken.
 *
 * ### The bar above it, which is the actually new thing
 *
 * Every section that changes an *appearance* rather than a *shape* can be aimed at part of the
 * string instead of all of it. That is the feature this panel was built for — «کلمهٔ کابینت را جدا
 * رنگ کن» — and the target bar is the one place it is expressed, so no individual control has to
 * grow a "apply to selection" toggle of its own.
 *
 * Sections that cannot be aimed do not show the bar. A drop shadow belongs to the layer, not to a
 * word: Photoshop attaches layer effects to the layer, and offering a per-word shadow that quietly
 * applied to everything would be worse than not offering it. Making a word cast its own shadow
 * means splitting it into its own layer, which is a different feature and is honest about being one.
 */
@Composable
fun TextStudioBody(
    state: EditorState,
    content: SheetContent.TextStudio,
    model: EditorViewModel,
    modifier: Modifier = Modifier,
) {
    val layer = state.document.findLayer(content.layer) as? Layer.Text
    if (layer == null) {
        MissingSubject(
            message = if (model.canAddText) {
                "لایهٔ متنی انتخاب نشده — یکی بسازید یا روی متنی روی بوم بزنید"
            } else {
                "هنوز فونتی بارگذاری نشده — از تنظیمات یک پوشهٔ فونت اضافه کنید"
            },
            action = "افزودن متن",
            enabled = model.canAddText,
            modifier = modifier,
        ) { model.addTextLayer() }
        return
    }

    Column(modifier.fillMaxWidth()) {
        SectionStrip(content.section) { section ->
            model.act { openSheet(SheetContent.TextStudio(content.layer, section), SheetDetent.FULL) }
        }
        if (content.section.aimable) TargetBar(state, layer, model)

        // **No scroller here.** Each section brings its own, because three of them delegate to
        // panels that already scroll — the font picker is a `LazyColumn` — and a scrollable inside
        // an unbounded-height scrollable is not a layout Compose will measure. It throws, and it
        // threw the moment the audit was pointed at a section that had one.
        when (content.section) {
            TextSection.CONTENT -> Scrolling { ContentSection(layer, model) }
            TextSection.FONT -> FontPickerBody(state, model)
            TextSection.COLOR -> Scrolling { ColorSection(state, layer, model) }
            TextSection.METRICS -> Scrolling { MetricsSection(state, layer, model) }
            TextSection.STROKE -> Scrolling { EffectSection(state, layer, model, STROKE_KINDS, ::defaultStroke) }
            TextSection.SHADOW -> Scrolling { EffectSection(state, layer, model, SHADOW_KINDS, ::defaultShadow) }
            TextSection.GLOW -> Scrolling { GlowSection(state, layer, model) }
            TextSection.DIMENSIONAL -> DimensionalSheetBody(state, model)
            TextSection.MATERIAL -> Scrolling { MaterialSection(layer, model) }
            TextSection.BACKGROUND -> Scrolling { BackgroundSection(layer, model) }
            TextSection.CURVE -> Scrolling { CurveSection(layer, model) }
            TextSection.REFLECTION -> Scrolling {
                EffectSection(state, layer, model, REFLECTION_KINDS, ::defaultReflection)
            }
            TextSection.BLEND -> BlendSection(state, layer, model)
            TextSection.ADVANCED -> Scrolling { EffectSection(state, layer, model, ADVANCED_KINDS, ::defaultSatin) }
        }
    }
}

/** A section that is a plain list of controls and needs somewhere to scroll. */
@Composable
private fun Scrolling(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { content() }
}

// ---- the strip ---------------------------------------------------------------------------------

/**
 * The section chooser.
 *
 * One scrolling row rather than a wrapping grid, because a grid of fourteen entries is four rows of
 * chrome above every panel and the panel is what the user came for. The fade is the same one the
 * main ribbon carries and exists for the same reason: a strip that scrolls with no sign of it reads
 * as a strip that has been cut off.
 */
@Composable
private fun SectionStrip(current: TextSection, onPick: (TextSection) -> Unit) {
    val scroll = rememberScrollState()
    Row(
        Modifier
            .fillMaxWidth()
            .height(Frame.ribbon)
            .background(Ink.ChromeRaised)
            .edgeFade(scroll, Ink.ChromeRaised)
            .horizontalScroll(scroll)
            .padding(horizontal = Space.small),
        horizontalArrangement = Arrangement.spacedBy(Space.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (section in TextSection.entries) {
            BarAction(
                icon = section.icon,
                label = section.persianLabel,
                selected = section == current,
                onClick = { onPick(section) },
            )
        }
    }
}

// ---- the target bar ----------------------------------------------------------------------------

/**
 * What the controls below are aimed at: the whole layer, or one chosen word.
 *
 * The ribbon underneath is the selector as well as the kashida control, which is the reason it is
 * here rather than a second list of the same words. Choosing a chip aims the panel at it; choosing
 * the same chip again releases it and the panel goes back to the whole string.
 */
@Composable
private fun TargetBar(state: EditorState, layer: Layer.Text, model: EditorViewModel) {
    val ribbon = remember(layer.id) { RibbonState() }
    val range = state.activeTextRange
    val chosen = range?.let { layer.spec.text.substring(it.first, it.last + 1) }

    Column(Modifier.fillMaxWidth()) {
        SheetChips {
            SheetChip("کل متن", chosen = range == null) {
                ribbon.clear()
                model.act { selectTextRange(null) }
            }
            if (chosen != null) SheetChip("فقط «$chosen»", chosen = true) { }
        }
        GlyphRibbonPanel(
            text = layer.spec.text,
            state = ribbon,
            showHint = false,
            onSelect = { cluster ->
                model.act { selectTextRange(cluster?.let { it.start until it.end }) }
            },
            onStretch = { cluster, amount ->
                model.setText(layer.id, Clusters.elongate(layer.spec.text, cluster, amount))
            },
        )
        SheetHint(
            if (range == null) {
                "روی یک خوشه بزنید تا فقط همان بخش تغییر کند"
            } else {
                "تغییرات این پنل فقط روی «$chosen» اعمال می‌شود"
            },
        )
        if (range != null) {
            SheetAction("برگرداندن این بخش به حالت پیش‌فرض") {
                model.clearCharacterStyle(layer.id, range)
            }
        }
    }
}

// ---- sections ----------------------------------------------------------------------------------

@Composable
private fun ContentSection(layer: Layer.Text, model: EditorViewModel) {
    val actions = LocalEditorActions.current
    SheetSection("نوشته")
    SheetTextField("متن", layer.spec.text) { model.setText(layer.id, it) }
    SheetHint("برای متن چندخطی، از دکمهٔ «ویرایش» روی نوار بالای بوم استفاده کنید")

    SheetSection("کشیدگی")
    KashidaControls(layer, model)

    SheetSection("لایهٔ تازه")
    SheetAction("افزودن متن دیگر", enabled = model.canAddText) { actions.addText() }
    // The panel is about this layer, so the count is the honest answer to "did anything I did to a
    // range survive": a user who tinted three words and sees «۰ بخش» knows immediately.
    SheetHint("${layer.spec.runs.size} بخش از این متن جداگانه سبک‌دهی شده است")
}

@Composable
private fun ColorSection(state: EditorState, layer: Layer.Text, model: EditorViewModel) {
    val range = state.activeTextRange
    SheetSection(if (range == null) "رنگ کل متن" else "رنگ بخش انتخاب‌شده")
    FillEditor(
        fill = fillOf(state, layer),
        model = model,
        onChange = { fill ->
            if (range == null) {
                model.setLayerFill(layer.id, fill)
            } else {
                model.setCharacterStyle(layer.id, range) { it.copy(fill = fill) }
            }
        },
    )
    SheetSlider(
        label = "شفافیت",
        value = opacityOf(state, layer) * PERCENT,
        range = 0f..PERCENT,
        onChange = { value, continuous ->
            if (range == null) {
                model.act { setLayerOpacity(layer.id, value / PERCENT, continuous) }
            } else {
                model.setCharacterStyle(layer.id, range, continuous) { it.copy(opacity = value / PERCENT) }
            }
        },
        onCommit = { model.act { endScrub() } },
    )
}

@Composable
private fun MetricsSection(state: EditorState, layer: Layer.Text, model: EditorViewModel) {
    val range = state.activeTextRange
    val spec = layer.spec
    val paragraph = spec.paragraph

    SheetSection("اندازه")
    if (range == null) {
        SheetNumberField("اندازه", spec.size.toInt().toString()) { typed ->
            typed.toFloatOrNull()?.let { model.setTextSize(layer.id, it.coerceIn(MIN_SIZE, MAX_SIZE)) }
        }
        SheetSlider("اندازه", spec.size.coerceAtMost(SLIDER_MAX_SIZE), MIN_SIZE..SLIDER_MAX_SIZE, onChange = { value, _ ->
            model.setTextSize(layer.id, value)
        })
    } else {
        // A multiplier rather than a size, so the word keeps its relationship to the rest of the
        // headline when the headline is resized. A per-word absolute size is a number that has to
        // be corrected every time anything else changes.
        SheetSlider(
            label = "نسبت اندازه",
            value = styleAt(state, layer)?.sizeScale ?: 1f,
            range = MIN_SCALE..MAX_SCALE,
            onChange = { value, continuous ->
                model.setCharacterStyle(layer.id, range, continuous) { it.copy(sizeScale = value) }
            },
            onCommit = { model.act { endScrub() } },
        )
        SheetSlider(
            label = "بالا و پایین بردن",
            value = styleAt(state, layer)?.baselineShift ?: 0f,
            range = -BASELINE..BASELINE,
            onChange = { value, continuous ->
                model.setCharacterStyle(layer.id, range, continuous) { it.copy(baselineShift = value) }
            },
            onCommit = { model.act { endScrub() } },
        )
    }

    SheetSection("فاصله")
    if (range == null) {
        SheetSlider("فاصلهٔ نویسه", paragraph.letterSpacing, -TRACKING..TRACKING, onChange = { value, _ ->
            model.setParagraph(layer.id, paragraph.copy(letterSpacing = value))
        })
        SheetSlider("فاصلهٔ کلمه", paragraph.wordSpacing, -TRACKING..TRACKING, onChange = { value, _ ->
            model.setParagraph(layer.id, paragraph.copy(wordSpacing = value))
        })
        SheetSlider("ارتفاع خط", paragraph.lineHeight, MIN_LEADING..MAX_LEADING, onChange = { value, _ ->
            model.setParagraph(layer.id, paragraph.copy(lineHeight = value))
        })
    } else {
        SheetSlider(
            label = "فاصلهٔ نویسه",
            value = styleAt(state, layer)?.letterSpacing ?: paragraph.letterSpacing,
            range = -TRACKING..TRACKING,
            onChange = { value, continuous ->
                model.setCharacterStyle(layer.id, range, continuous) { it.copy(letterSpacing = value) }
            },
            onCommit = { model.act { endScrub() } },
        )
    }

    // Alignment and direction are properties of a paragraph, not of a word, so they are not offered
    // per range at all rather than offered and quietly ignored.
    SheetSection("چینش")
    AlignmentControls(layer, model)
}

@Composable
private fun MaterialSection(layer: Layer.Text, model: EditorViewModel) {
    val geometry = layer.geometry3D
    if (geometry == null) {
        MissingSubject(
            message = "جنس روی متن سه‌بعدی معنا دارد — اول متن را سه‌بعدی کنید",
            action = "رفتن به بخش سه‌بعدی",
        ) {
            model.act { openSheet(SheetContent.TextStudio(layer.id, TextSection.DIMENSIONAL), SheetDetent.FULL) }
        }
        return
    }
    // Three surfaces rather than one, because they are what a real title is made of: a lacquered
    // face, a gold bevel catching the key light, and a side that is usually the same metal. Giving
    // them one shared material is what makes extruded type look like a toy.
    SheetSection("رویه")
    MaterialControls("رویه", geometry.faceMaterial) { model.setGeometry3D(layer.id, geometry.copy(faceMaterial = it)) }
    SheetSection("پخ")
    MaterialControls("پخ", geometry.bevelMaterial) { model.setGeometry3D(layer.id, geometry.copy(bevelMaterial = it)) }
    SheetSection("بدنه")
    MaterialControls("بدنه", geometry.sideMaterial) { model.setGeometry3D(layer.id, geometry.copy(sideMaterial = it)) }
}

/**
 * The panel behind the words.
 *
 * A caption over a photograph is unreadable until something sits behind it, and this application had
 * no way to say so at all — the only workaround was a second shape layer, positioned by hand and
 * re-positioned every time the words changed.
 *
 * «هر خط جدا» first, because it is the choice that decides what the thing looks like and it is the
 * one every editor gets wrong. One box around a three-line centred title leaves a wide empty band
 * beside the short lines; a box per line is the look people are actually copying.
 */
@Composable
private fun BackgroundSection(layer: Layer.Text, model: EditorViewModel) {
    val background = layer.spec.background
    if (background == null) {
        MissingSubject(
            message = "پشت این متن چیزی نیست — روی عکس، نوشته بدون پس‌زمینه خوانده نمی‌شود",
            action = "افزودن پس‌زمینه",
        ) {
            model.setTextBackground(layer.id, ir.pixellab.core.model.TextBackground())
        }
        return
    }

    SheetSection("شکل")
    SheetChips {
        SheetChip("هر خط جدا", chosen = background.perLine) {
            model.setTextBackground(layer.id, background.copy(perLine = true))
        }
        SheetChip("یک کادر", chosen = !background.perLine) {
            model.setTextBackground(layer.id, background.copy(perLine = false))
        }
    }
    SheetHint("هر خط جدا برای تیتر است؛ یک کادر برای بند متن")

    SheetSlider(
        label = "حاشیهٔ افقی",
        value = background.paddingX,
        range = 0f..MAX_PADDING,
        onChange = { value, continuous ->
            model.scrubTextBackground(layer.id, continuous) { it.copy(paddingX = value) }
        },
        onCommit = { model.act { endScrub() } },
    )
    SheetSlider(
        label = "حاشیهٔ عمودی",
        value = background.paddingY,
        range = 0f..MAX_PADDING,
        onChange = { value, continuous ->
            model.scrubTextBackground(layer.id, continuous) { it.copy(paddingY = value) }
        },
        onCommit = { model.act { endScrub() } },
    )
    SheetSlider(
        label = "گردی گوشه",
        value = background.cornerRadius,
        range = 0f..MAX_RADIUS,
        onChange = { value, continuous ->
            model.scrubTextBackground(layer.id, continuous) { it.copy(cornerRadius = value) }
        },
        onCommit = { model.act { endScrub() } },
    )
    SheetSlider(
        label = "شفافیت",
        value = background.opacity * PERCENT,
        range = 0f..PERCENT,
        onChange = { value, continuous ->
            model.scrubTextBackground(layer.id, continuous) { it.copy(opacity = value / PERCENT) }
        },
        onCommit = { model.act { endScrub() } },
    )

    SheetSection("پر")
    FillEditor(
        fill = background.fill,
        model = model,
        onChange = { model.setTextBackground(layer.id, background.copy(fill = it)) },
    )

    SheetAction("برداشتن پس‌زمینه", tint = Ink.Danger) { model.setTextBackground(layer.id, null) }
}

/**
 * Bending the letters, and bending the line they sit on.
 *
 * Two different things and worth keeping in one section, because a user reaching for "curved text"
 * does not know which one they want and trying both is how they find out. A warp distorts the
 * letterforms; a path moves them along an arc and leaves each one upright relative to the curve.
 */
@Composable
private fun CurveSection(layer: Layer.Text, model: EditorViewModel) {
    val path = layer.spec.path as? ir.pixellab.core.model.TextPath.Arc

    SheetSection("خط پایه")
    SheetChips {
        SheetChip("مستقیم", chosen = layer.spec.path == null) { model.setTextPath(layer.id, null) }
        SheetChip("کمان", chosen = path != null) {
            if (path == null) model.setTextPath(layer.id, ir.pixellab.core.model.TextPath.Arc(radius = DEFAULT_ARC))
        }
    }
    if (path != null) {
        // Negative radius curves the other way, and the slider crosses zero rather than pairing a
        // magnitude with a direction switch — one control where the shape actually is continuous.
        SheetSlider(
            label = "شعاع کمان",
            value = path.radius,
            range = -MAX_ARC..MAX_ARC,
            onChange = { value, _ -> model.setTextPath(layer.id, path.copy(radius = value)) },
        )
        SheetSlider(
            label = "زاویهٔ شروع",
            value = path.startAngle,
            range = -FULL_TURN..FULL_TURN,
            onChange = { value, _ -> model.setTextPath(layer.id, path.copy(startAngle = value)) },
        )
        SheetChips {
            SheetChip("داخل کمان", chosen = path.flip) { model.setTextPath(layer.id, path.copy(flip = true)) }
            SheetChip("بیرون کمان", chosen = !path.flip) { model.setTextPath(layer.id, path.copy(flip = false)) }
        }
        SheetHint("شعاع منفی کمان را برعکس می‌کند — برای نوشتن روی نیمهٔ پایین دایره")
    }

    SheetSection("تاب حروف")
    WarpControls(layer, model)
}

@Composable
private fun GlowSection(state: EditorState, layer: Layer.Text, model: EditorViewModel) {
    SheetSection("دستور آماده")
    // Neon is not one effect and never has been: it is a tight bright core, a wide soft halo and a
    // saturated fill, and asking a user to discover that combination is asking them not to have it.
    SheetAction("نئون") {
        model.act {
            addEffect(layer.id, Effect.Overlay(fill = Fill.Solid(NEON_CORE)))
            addEffect(layer.id, Effect.OuterGlow(fill = Fill.Solid(NEON_HALO), blur = NEON_INNER))
            addEffect(layer.id, Effect.OuterGlow(fill = Fill.Solid(NEON_HALO), blur = NEON_OUTER))
        }
    }
    EffectSection(state, layer, model, GLOW_KINDS, ::defaultGlow)
}

@Composable
private fun BlendSection(state: EditorState, layer: Layer.Text, model: EditorViewModel) {
    LayerParametersSheetBody(state, SheetContent.LayerParameters(layer.id), model)
}

/**
 * The effects of one kind, with their own numbers where they are rather than a sheet away.
 *
 * Every effect type may repeat — reference documents stack three strokes and ten shadows — so this
 * lists what is there and offers another, instead of pretending there is one of each.
 */
@Composable
private fun EffectSection(
    state: EditorState,
    layer: Layer.Text,
    model: EditorViewModel,
    kinds: Set<String>,
    add: () -> Effect,
) {
    val present = layer.style.effects.withIndex().filter { (_, effect) -> kindOf(effect) in kinds }

    SheetAction("افزودن") { model.act { addEffect(layer.id, add()) } }
    if (present.isEmpty()) {
        SheetHint("هنوز چیزی اضافه نشده — دکمهٔ بالا یکی با تنظیمات قابل‌دیدن می‌سازد")
        return
    }

    for ((index, effect) in present) {
        SheetSection(labelOf(effect))
        EffectControls(state, layer.id, index, model)
        SheetAction("حذف", tint = Ink.Danger) { model.act { removeEffect(layer.id, index) } }
        SheetDivider()
    }
}

// ---- reading the current value ------------------------------------------------------------------

/** The fill the colour section should show: the range's own, or the layer's. */
private fun fillOf(state: EditorState, layer: Layer.Text): Fill =
    styleAt(state, layer)?.fill ?: layer.style.fill

private fun opacityOf(state: EditorState, layer: Layer.Text): Float =
    styleAt(state, layer)?.opacity ?: layer.opacity

/**
 * The style already covering the aimed-at range, if any.
 *
 * Read from the run that starts the range rather than from all of them: a range that spans two
 * different runs has no single value to show, and showing the first is what every desktop editor
 * does with a mixed selection. It is also self-correcting — the next edit writes one value across
 * the whole range.
 */
private fun styleAt(state: EditorState, layer: Layer.Text): CharacterStyle? {
    val range = state.activeTextRange ?: return null
    return layer.spec.runs.firstOrNull { it.start <= range.first && it.end > range.first }?.style
}

// ---- naming --------------------------------------------------------------------------------------

/** The section names, shared with the strip so the header and the chip can never disagree. */
internal val TextSection.persianLabel: String
    get() = when (this) {
        TextSection.CONTENT -> "نوشته"
        TextSection.FONT -> "فونت"
        TextSection.COLOR -> "رنگ"
        TextSection.METRICS -> "اندازه"
        TextSection.STROKE -> "خط دور"
        TextSection.SHADOW -> "سایه"
        TextSection.GLOW -> "درخشش"
        TextSection.DIMENSIONAL -> "سه‌بعدی"
        TextSection.MATERIAL -> "جنس"
        TextSection.BACKGROUND -> "پس‌زمینه"
        TextSection.CURVE -> "تاب"
        TextSection.REFLECTION -> "بازتاب"
        TextSection.BLEND -> "ترکیب"
        TextSection.ADVANCED -> "پیشرفته"
    }

private val TextSection.icon: ImageVector
    get() = when (this) {
        TextSection.CONTENT -> Icons.Outlined.TextFields
        TextSection.FONT -> Icons.Outlined.FontDownload
        TextSection.COLOR -> Icons.Outlined.FormatColorFill
        TextSection.METRICS -> Icons.Outlined.FormatLineSpacing
        TextSection.STROKE -> Icons.Outlined.BorderColor
        TextSection.SHADOW -> Icons.Outlined.Layers
        TextSection.GLOW -> Icons.Outlined.Lightbulb
        TextSection.DIMENSIONAL -> Icons.Outlined.ViewInAr
        TextSection.MATERIAL -> Icons.Outlined.Diamond
        TextSection.BACKGROUND -> Icons.Outlined.Rectangle
        TextSection.CURVE -> Icons.Outlined.Timeline
        TextSection.REFLECTION -> Icons.Outlined.Flip
        TextSection.BLEND -> Icons.Outlined.Opacity
        TextSection.ADVANCED -> Icons.Outlined.AutoAwesome
    }

/**
 * Whether a section can be pointed at part of the string.
 *
 * Appearance of the letters themselves: yes. Anything that belongs to the layer as an object —
 * every layer effect, the 3D geometry, the blend mode — no, because that is where it genuinely
 * lives. Offering the bar on those sections would be a control that lies about what it does.
 */
private val TextSection.aimable: Boolean
    get() = this == TextSection.COLOR || this == TextSection.METRICS

private fun kindOf(effect: Effect): String = ir.pixellab.core.render.EffectRegistry.idOf(effect)

private fun labelOf(effect: Effect): String =
    ir.pixellab.core.render.builtinEffectRegistry[kindOf(effect)]?.label ?: kindOf(effect)

// ---- the defaults a press produces ----------------------------------------------------------------

// Chosen to be visible at a glance and immediately adjustable rather than to be subtle. A drop
// shadow at two per cent is indistinguishable from a button that did nothing.

private fun defaultStroke() = Effect.Stroke(width = 10f, fill = Fill.Solid(Color.WHITE))

private fun defaultShadow() = Effect.DropShadow(color = Color.BLACK, angle = 135f, distance = 12f, blur = 18f)

private fun defaultGlow() = Effect.OuterGlow(fill = Fill.Solid(Color(1f, 0.85f, 0.4f)), blur = 28f)

private fun defaultReflection() = Effect.Reflection()

private fun defaultSatin() = Effect.Satin()

private val STROKE_KINDS = setOf("stroke")
private val SHADOW_KINDS = setOf("drop_shadow", "inner_shadow")
private val GLOW_KINDS = setOf("outer_glow", "inner_glow")
private val REFLECTION_KINDS = setOf("reflection")
private val ADVANCED_KINDS = setOf("satin", "bevel", "extrude", "noise", "chromatic_offset", "edge_roughen", "overlay", "backdrop_blur")

private val NEON_CORE = Color(1f, 0.98f, 0.92f)
private val NEON_HALO = Color(0.25f, 0.85f, 1f)
private const val NEON_INNER = 10f
private const val NEON_OUTER = 44f

private const val PERCENT = 100f

private const val MIN_SIZE = 8f
private const val SLIDER_MAX_SIZE = 400f
private const val MAX_SIZE = 4000f

/** Half again as small to two and a half times as large — past that a word stops being in the line. */
private const val MIN_SCALE = 0.4f
private const val MAX_SCALE = 2.5f

/** A fraction of the type size, each way. Half an em is already further than any real setting. */
private const val BASELINE = 0.5f

/** Padding wide enough to make a card of a short word, in the same units as the type size. */
private const val MAX_PADDING = 200f
private const val MAX_RADIUS = 200f

/** Where an arc starts life: wide enough to read as a gentle curve on a headline, not a ring. */
private const val DEFAULT_ARC = 900f
private const val MAX_ARC = 3000f
private const val FULL_TURN = 360f

private const val TRACKING = 0.25f
private const val MIN_LEADING = 0.6f
private const val MAX_LEADING = 3f
