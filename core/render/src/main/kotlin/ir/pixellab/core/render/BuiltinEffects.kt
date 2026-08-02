package ir.pixellab.core.render

import ir.pixellab.core.model.BevelStyle
import ir.pixellab.core.model.BevelTechnique
import ir.pixellab.core.model.Curve
import ir.pixellab.core.model.Effect
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.GlowSource
import ir.pixellab.core.model.StrokePosition
import ir.pixellab.core.model.Vec2
import kotlin.math.max
import kotlin.math.roundToInt

/** Blur kernels reach roughly three standard deviations; less than that visibly clips. */
private const val BLUR_REACH = 3f

/**
 * Builds a choice from enum constants and their Persian names.
 *
 * The constant's name is what is stored; the Persian string is only ever shown. Storing the label
 * would put interface text into saved documents and break them the moment a wording changes.
 */
private fun choice(
    key: String,
    label: String,
    vararg options: Pair<Enum<*>, String>,
    default: Enum<*> = options.first().first,
) = ParameterSpec.Choice(
    key = key,
    label = label,
    options = options.map { (value, text) -> ParameterSpec.Choice.Option(value.name, text) },
    default = default.name,
)

private fun lightOffset(distance: Float, angle: Float, useGlobal: Boolean, globalAngle: Float): Vec2 {
    val a = if (useGlobal) globalAngle else angle
    val dir = Vec2.fromAngle(a)
    // Screen y grows downwards while the light angle is measured mathematically, so y inverts.
    return Vec2(dir.x * distance, -dir.y * distance)
}

object StrokeModule : EffectModule<Effect.Stroke> {
    override val id = "stroke"
    override val label = "خط دور"
    override val slot = PassSlot.STROKE
    override val parameters = listOf(
        ParameterSpec.Slider("width", "ضخامت", 0f..200f, 4f, ParameterSpec.Slider.Unit.PIXELS),
        ParameterSpec.FillPicker("fill", "پرکننده"),
        choice("position", "موقعیت", StrokePosition.INSIDE to "داخل", StrokePosition.CENTER to "مرکز", StrokePosition.OUTSIDE to "بیرون"),
    )

    override fun bleed(effect: Effect.Stroke, context: BleedContext) = when (effect.position) {
        StrokePosition.OUTSIDE -> Bleed.uniform(effect.width)
        StrokePosition.CENTER -> Bleed.uniform(effect.width / 2f)
        StrokePosition.INSIDE -> Bleed.NONE
    }

    override fun describe(effect: Effect.Stroke, context: RenderContext) = PassDescriptor(
        shaderId = "stroke",
        floats = mapOf("uWidth" to effect.width * context.scale, "uOpacity" to effect.opacity),
        ints = mapOf("uPosition" to effect.position.ordinal),
    )

    override fun read(effect: Effect.Stroke, key: String) = when (key) {
        "width" -> num(effect.width)
        "fill" -> paint(effect.fill)
        "position" -> option(effect.position)
        else -> null
    }

    override fun write(effect: Effect.Stroke, key: String, value: ParameterValue) = when (key) {
        "width" -> effect.copy(width = value.number ?: effect.width)
        "fill" -> effect.copy(fill = value.paint ?: effect.fill)
        "position" -> effect.copy(position = value.enumOf<StrokePosition>() ?: effect.position)
        else -> effect
    }
}

object DropShadowModule : EffectModule<Effect.DropShadow> {
    override val id = "drop_shadow"
    override val label = "سایه"
    override val slot = PassSlot.DROP_SHADOW
    override val parameters = listOf(
        ParameterSpec.ColorPicker("color", "رنگ"),
        ParameterSpec.Slider("angle", "زاویه", 0f..360f, 90f, ParameterSpec.Slider.Unit.DEGREES),
        ParameterSpec.Slider("distance", "فاصله", 0f..500f, 0f, ParameterSpec.Slider.Unit.PIXELS, skew = 2f),
        ParameterSpec.Slider("spread", "گسترش", 0f..100f, 0f, ParameterSpec.Slider.Unit.PIXELS),
        ParameterSpec.Slider("blur", "محو", 0f..500f, 0f, ParameterSpec.Slider.Unit.PIXELS, skew = 2f),
        ParameterSpec.CurveEditor("contour", "منحنی"),
        ParameterSpec.Slider("noise", "نویز", 0f..1f, 0f, ParameterSpec.Slider.Unit.PERCENT),
        ParameterSpec.Toggle("knockOut", "حذف زیر لایه", true),
        ParameterSpec.Toggle("useGlobalLight", "نور سراسری", true),
    )

    override fun bleed(effect: Effect.DropShadow, context: BleedContext) = Bleed.directional(
        offset = lightOffset(effect.distance, effect.angle, effect.useGlobalLight, context.globalLightAngle),
        grow = effect.spread + effect.blur * BLUR_REACH,
    )

    override fun describe(effect: Effect.DropShadow, context: RenderContext): PassDescriptor {
        val offset = lightOffset(
            effect.distance, effect.angle, effect.useGlobalLight, context.globalLightAngle,
        )
        return PassDescriptor(
            shaderId = "shadow",
            floats = mapOf("uBlur" to effect.blur * context.scale, "uSpread" to effect.spread * context.scale),
            vectors = mapOf("uOffset" to floatArrayOf(offset.x * context.scale, offset.y * context.scale)),
            ints = mapOf("uKnockOut" to if (effect.knockOut) 1 else 0),
        )
    }

    override fun cost(effect: Effect.DropShadow) = if (effect.blur > 0f) 4 else 1

    override fun read(effect: Effect.DropShadow, key: String) = when (key) {
        "color" -> tint(effect.color)
        "angle" -> num(effect.angle)
        "distance" -> num(effect.distance)
        "spread" -> num(effect.spread)
        "blur" -> num(effect.blur)
        "contour" -> shape(effect.contour)
        "noise" -> num(effect.noise)
        "knockOut" -> flag(effect.knockOut)
        "useGlobalLight" -> flag(effect.useGlobalLight)
        else -> null
    }

    override fun write(effect: Effect.DropShadow, key: String, value: ParameterValue) = when (key) {
        "color" -> effect.copy(color = value.tint ?: effect.color)
        "angle" -> effect.copy(angle = value.number ?: effect.angle)
        "distance" -> effect.copy(distance = value.number ?: effect.distance)
        "spread" -> effect.copy(spread = value.number ?: effect.spread)
        "blur" -> effect.copy(blur = value.number ?: effect.blur)
        "contour" -> effect.copy(contour = value.shape ?: effect.contour)
        "noise" -> effect.copy(noise = value.number ?: effect.noise)
        "knockOut" -> effect.copy(knockOut = value.flag ?: effect.knockOut)
        "useGlobalLight" -> effect.copy(useGlobalLight = value.flag ?: effect.useGlobalLight)
        else -> effect
    }
}

object InnerShadowModule : EffectModule<Effect.InnerShadow> {
    override val id = "inner_shadow"
    override val label = "سایهٔ داخلی"
    override val slot = PassSlot.INNER_SHADOW
    override val parameters = listOf(
        ParameterSpec.ColorPicker("color", "رنگ"),
        ParameterSpec.Slider("angle", "زاویه", 0f..360f, 90f, ParameterSpec.Slider.Unit.DEGREES),
        ParameterSpec.Slider("distance", "فاصله", 0f..200f, 0f, ParameterSpec.Slider.Unit.PIXELS),
        ParameterSpec.Slider("choke", "فشردگی", 0f..100f, 0f, ParameterSpec.Slider.Unit.PIXELS),
        ParameterSpec.Slider("blur", "محو", 0f..200f, 0f, ParameterSpec.Slider.Unit.PIXELS),
    )

    override fun bleed(effect: Effect.InnerShadow, context: BleedContext) = Bleed.NONE

    override fun describe(effect: Effect.InnerShadow, context: RenderContext) = PassDescriptor(
        shaderId = "inner_shadow",
        floats = mapOf("uBlur" to effect.blur * context.scale, "uChoke" to effect.choke * context.scale),
    )

    override fun cost(effect: Effect.InnerShadow) = 4

    override fun read(effect: Effect.InnerShadow, key: String) = when (key) {
        "color" -> tint(effect.color)
        "angle" -> num(effect.angle)
        "distance" -> num(effect.distance)
        "choke" -> num(effect.choke)
        "blur" -> num(effect.blur)
        "contour" -> shape(effect.contour)
        "noise" -> num(effect.noise)
        "useGlobalLight" -> flag(effect.useGlobalLight)
        else -> null
    }

    override fun write(effect: Effect.InnerShadow, key: String, value: ParameterValue) = when (key) {
        "color" -> effect.copy(color = value.tint ?: effect.color)
        "angle" -> effect.copy(angle = value.number ?: effect.angle)
        "distance" -> effect.copy(distance = value.number ?: effect.distance)
        "choke" -> effect.copy(choke = value.number ?: effect.choke)
        "blur" -> effect.copy(blur = value.number ?: effect.blur)
        "contour" -> effect.copy(contour = value.shape ?: effect.contour)
        "noise" -> effect.copy(noise = value.number ?: effect.noise)
        "useGlobalLight" -> effect.copy(useGlobalLight = value.flag ?: effect.useGlobalLight)
        else -> effect
    }
}

object OuterGlowModule : EffectModule<Effect.OuterGlow> {
    override val id = "outer_glow"
    override val label = "درخشش بیرونی"
    override val slot = PassSlot.OUTER_GLOW
    override val parameters = listOf(
        ParameterSpec.FillPicker("fill", "پرکننده"),
        ParameterSpec.Slider("spread", "گسترش", 0f..100f, 0f, ParameterSpec.Slider.Unit.PIXELS),
        ParameterSpec.Slider("blur", "محو", 0f..300f, 8f, ParameterSpec.Slider.Unit.PIXELS, skew = 2f),
        ParameterSpec.CurveEditor("contour", "منحنی"),
    )

    override fun bleed(effect: Effect.OuterGlow, context: BleedContext) =
        Bleed.uniform(effect.spread + effect.blur * BLUR_REACH)

    override fun describe(effect: Effect.OuterGlow, context: RenderContext) = PassDescriptor(
        shaderId = "glow",
        floats = mapOf("uBlur" to effect.blur * context.scale, "uSpread" to effect.spread * context.scale),
        ints = mapOf("uInner" to 0, "uGlowSource" to 1),
    )

    override fun cost(effect: Effect.OuterGlow) = 4

    override fun read(effect: Effect.OuterGlow, key: String) = when (key) {
        "fill" -> paint(effect.fill)
        "spread" -> num(effect.spread)
        "blur" -> num(effect.blur)
        "contour" -> shape(effect.contour)
        "noise" -> num(effect.noise)
        else -> null
    }

    override fun write(effect: Effect.OuterGlow, key: String, value: ParameterValue) = when (key) {
        "fill" -> effect.copy(fill = value.paint ?: effect.fill)
        "spread" -> effect.copy(spread = value.number ?: effect.spread)
        "blur" -> effect.copy(blur = value.number ?: effect.blur)
        "contour" -> effect.copy(contour = value.shape ?: effect.contour)
        "noise" -> effect.copy(noise = value.number ?: effect.noise)
        else -> effect
    }
}

object InnerGlowModule : EffectModule<Effect.InnerGlow> {
    override val id = "inner_glow"
    override val label = "درخشش داخلی"
    override val slot = PassSlot.INNER_GLOW
    override val parameters = listOf(
        ParameterSpec.FillPicker("fill", "پرکننده"),
        choice("source", "منبع", GlowSource.CENTER to "مرکز", GlowSource.EDGE to "لبه", default = GlowSource.EDGE),
        ParameterSpec.Slider("choke", "فشردگی", 0f..100f, 0f, ParameterSpec.Slider.Unit.PIXELS),
        ParameterSpec.Slider("blur", "محو", 0f..300f, 8f, ParameterSpec.Slider.Unit.PIXELS),
    )

    override fun bleed(effect: Effect.InnerGlow, context: BleedContext) = Bleed.NONE

    override fun describe(effect: Effect.InnerGlow, context: RenderContext) = PassDescriptor(
        shaderId = "glow",
        floats = mapOf("uBlur" to effect.blur * context.scale, "uSpread" to effect.choke * context.scale),
        ints = mapOf("uInner" to 1, "uGlowSource" to effect.source.ordinal),
    )

    override fun cost(effect: Effect.InnerGlow) = 4

    override fun read(effect: Effect.InnerGlow, key: String) = when (key) {
        "fill" -> paint(effect.fill)
        "source" -> option(effect.source)
        "choke" -> num(effect.choke)
        "blur" -> num(effect.blur)
        "contour" -> shape(effect.contour)
        "noise" -> num(effect.noise)
        else -> null
    }

    override fun write(effect: Effect.InnerGlow, key: String, value: ParameterValue) = when (key) {
        "fill" -> effect.copy(fill = value.paint ?: effect.fill)
        "source" -> effect.copy(source = value.enumOf<GlowSource>() ?: effect.source)
        "choke" -> effect.copy(choke = value.number ?: effect.choke)
        "blur" -> effect.copy(blur = value.number ?: effect.blur)
        "contour" -> effect.copy(contour = value.shape ?: effect.contour)
        "noise" -> effect.copy(noise = value.number ?: effect.noise)
        else -> effect
    }
}

object BevelModule : EffectModule<Effect.Bevel> {
    override val id = "bevel"
    override val label = "پخ و برجستگی"
    override val slot = PassSlot.BEVEL
    override val parameters = listOf(
        choice(
            "style", "سبک",
            BevelStyle.OUTER_BEVEL to "پخ بیرونی", BevelStyle.INNER_BEVEL to "پخ داخلی",
            BevelStyle.EMBOSS to "برجسته", BevelStyle.PILLOW_EMBOSS to "برجستهٔ بالشی",
            BevelStyle.STROKE_EMBOSS to "برجستهٔ خط دور",
            default = BevelStyle.INNER_BEVEL,
        ),
        choice(
            "technique", "روش",
            BevelTechnique.SMOOTH to "نرم", BevelTechnique.CHISEL_HARD to "اسکنهٔ سخت",
            BevelTechnique.CHISEL_SOFT to "اسکنهٔ نرم",
        ),
        // The reference PSDs run depth to 317%, so the range must reach well past 100.
        ParameterSpec.Slider("depth", "عمق", 0f..1000f, 100f, ParameterSpec.Slider.Unit.PERCENT, skew = 2f),
        ParameterSpec.Slider("size", "اندازه", 0f..250f, 8f, ParameterSpec.Slider.Unit.PIXELS),
        ParameterSpec.Slider("soften", "نرمی", 0f..50f, 0f, ParameterSpec.Slider.Unit.PIXELS),
        ParameterSpec.Slider("angle", "زاویه", 0f..360f, 90f, ParameterSpec.Slider.Unit.DEGREES),
        ParameterSpec.Slider("altitude", "ارتفاع نور", 0f..90f, 30f, ParameterSpec.Slider.Unit.DEGREES),
        ParameterSpec.CurveEditor("profile", "پروفایل پخ"),
        ParameterSpec.CurveEditor("glossContour", "منحنی براقیت"),
        ParameterSpec.ColorPicker("highlightColor", "رنگ های‌لایت"),
        ParameterSpec.ColorPicker("shadowColor", "رنگ سایه"),
        ParameterSpec.Slider("textureDepth", "عمق بافت", -1f..1f, 0f, ParameterSpec.Slider.Unit.PERCENT),
    )

    override fun bleed(effect: Effect.Bevel, context: BleedContext) =
        if (effect.style == BevelStyle.OUTER_BEVEL) Bleed.uniform(effect.size + effect.soften) else Bleed.NONE

    override fun describe(effect: Effect.Bevel, context: RenderContext) = PassDescriptor(
        shaderId = "bevel",
        floats = mapOf(
            "uDepth" to effect.depth / 100f,
            "uSize" to effect.size * context.scale,
            "uSoften" to effect.soften * context.scale,
            "uAngle" to (if (effect.useGlobalLight) context.globalLightAngle else effect.angle),
            "uAltitude" to effect.altitude,
        ),
        ints = mapOf("uStyle" to effect.style.ordinal, "uTechnique" to effect.technique.ordinal),
    )

    override fun cost(effect: Effect.Bevel) = 3

    override fun read(effect: Effect.Bevel, key: String) = when (key) {
        "style" -> option(effect.style)
        "technique" -> option(effect.technique)
        "depth" -> num(effect.depth)
        "size" -> num(effect.size)
        "soften" -> num(effect.soften)
        "angle" -> num(effect.angle)
        "altitude" -> num(effect.altitude)
        "useGlobalLight" -> flag(effect.useGlobalLight)
        "profile" -> shape(effect.profile)
        "glossContour" -> shape(effect.glossContour)
        "highlightColor" -> tint(effect.highlightColor)
        "shadowColor" -> tint(effect.shadowColor)
        "textureDepth" -> num(effect.textureDepth)
        else -> null
    }

    override fun write(effect: Effect.Bevel, key: String, value: ParameterValue) = when (key) {
        "style" -> effect.copy(style = value.enumOf<BevelStyle>() ?: effect.style)
        "technique" -> effect.copy(technique = value.enumOf<BevelTechnique>() ?: effect.technique)
        "depth" -> effect.copy(depth = value.number ?: effect.depth)
        "size" -> effect.copy(size = value.number ?: effect.size)
        "soften" -> effect.copy(soften = value.number ?: effect.soften)
        "angle" -> effect.copy(angle = value.number ?: effect.angle)
        "altitude" -> effect.copy(altitude = value.number ?: effect.altitude)
        "useGlobalLight" -> effect.copy(useGlobalLight = value.flag ?: effect.useGlobalLight)
        "profile" -> effect.copy(profile = value.shape ?: effect.profile)
        "glossContour" -> effect.copy(glossContour = value.shape ?: effect.glossContour)
        "highlightColor" -> effect.copy(highlightColor = value.tint ?: effect.highlightColor)
        "shadowColor" -> effect.copy(shadowColor = value.tint ?: effect.shadowColor)
        "textureDepth" -> effect.copy(textureDepth = value.number ?: effect.textureDepth)
        else -> effect
    }
}

object SatinModule : EffectModule<Effect.Satin> {
    override val id = "satin"
    override val label = "ساتن"
    override val slot = PassSlot.SATIN
    override val parameters = listOf(
        ParameterSpec.ColorPicker("color", "رنگ"),
        ParameterSpec.Slider("angle", "زاویه", 0f..360f, 90f, ParameterSpec.Slider.Unit.DEGREES),
        ParameterSpec.Slider("distance", "فاصله", 0f..250f, 8f, ParameterSpec.Slider.Unit.PIXELS),
        ParameterSpec.Slider("blur", "محو", 0f..250f, 8f, ParameterSpec.Slider.Unit.PIXELS),
        ParameterSpec.Toggle("invert", "معکوس", false),
    )

    override fun bleed(effect: Effect.Satin, context: BleedContext) = Bleed.NONE

    override fun describe(effect: Effect.Satin, context: RenderContext) = PassDescriptor(
        shaderId = "satin",
        floats = mapOf("uDistance" to effect.distance * context.scale, "uBlur" to effect.blur * context.scale),
        ints = mapOf("uInvert" to if (effect.invert) 1 else 0),
    )

    override fun cost(effect: Effect.Satin) = 3

    override fun read(effect: Effect.Satin, key: String) = when (key) {
        "color" -> tint(effect.color)
        "angle" -> num(effect.angle)
        "distance" -> num(effect.distance)
        "blur" -> num(effect.blur)
        "contour" -> shape(effect.contour)
        "invert" -> flag(effect.invert)
        else -> null
    }

    override fun write(effect: Effect.Satin, key: String, value: ParameterValue) = when (key) {
        "color" -> effect.copy(color = value.tint ?: effect.color)
        "angle" -> effect.copy(angle = value.number ?: effect.angle)
        "distance" -> effect.copy(distance = value.number ?: effect.distance)
        "blur" -> effect.copy(blur = value.number ?: effect.blur)
        "contour" -> effect.copy(contour = value.shape ?: effect.contour)
        "invert" -> effect.copy(invert = value.flag ?: effect.invert)
        else -> effect
    }
}

object OverlayModule : EffectModule<Effect.Overlay> {
    override val id = "overlay"
    override val label = "روکش"
    override val slot = PassSlot.OVERLAY
    override val parameters = listOf(ParameterSpec.FillPicker("fill", "پرکننده"))

    override fun bleed(effect: Effect.Overlay, context: BleedContext) = Bleed.NONE

    override fun describe(effect: Effect.Overlay, context: RenderContext) =
        PassDescriptor(shaderId = "overlay", floats = mapOf("uOpacity" to effect.opacity))

    override fun read(effect: Effect.Overlay, key: String) = when (key) {
        "fill" -> paint(effect.fill)
        else -> null
    }

    override fun write(effect: Effect.Overlay, key: String, value: ParameterValue) = when (key) {
        "fill" -> effect.copy(fill = value.paint ?: effect.fill)
        else -> effect
    }
}

object ExtrudeModule : EffectModule<Effect.Extrude> {
    override val id = "extrude"
    override val label = "اکسترود"
    override val slot = PassSlot.EXTRUDE
    override val parameters = listOf(
        ParameterSpec.Slider("steps", "تعداد گام", 1f..512f, 12f, skew = 2f),
        ParameterSpec.Slider("offsetX", "آفست افقی", -20f..20f, -2f, ParameterSpec.Slider.Unit.PIXELS),
        ParameterSpec.Slider("offsetY", "آفست عمودی", -20f..20f, 2f, ParameterSpec.Slider.Unit.PIXELS),
        ParameterSpec.FillPicker("nearFill", "رنگ نزدیک"),
        ParameterSpec.FillPicker("farFill", "رنگ دور"),
        ParameterSpec.CurveEditor("falloff", "منحنی گذار"),
        ParameterSpec.Slider("farOpacity", "شفافیت دورترین", 0f..1f, 1f, ParameterSpec.Slider.Unit.PERCENT),
    )

    override fun bleed(effect: Effect.Extrude, context: BleedContext): Bleed {
        val x = effect.stepOffset.x * effect.steps
        val y = effect.stepOffset.y * effect.steps
        return Bleed(max(0f, -x), max(0f, -y), max(0f, x), max(0f, y))
    }

    override fun describe(effect: Effect.Extrude, context: RenderContext) = PassDescriptor(
        shaderId = "extrude_step",
        vectors = mapOf(
            "uStepOffset" to floatArrayOf(
                effect.stepOffset.x * context.scale,
                effect.stepOffset.y * context.scale,
            ),
        ),
        floats = mapOf(
            "uFarOpacity" to effect.farOpacity,
            // The shader derives each step's depth from this; without it every step lands at t=0
            // and the extrusion collapses to a flat smear of the near colour.
            "uStepCount" to effect.steps.toFloat(),
        ),
        instanceCount = effect.steps,
    )

    override fun cost(effect: Effect.Extrude) = effect.steps

    override fun read(effect: Effect.Extrude, key: String) = when (key) {
        "steps" -> num(effect.steps)
        "offsetX" -> num(effect.stepOffset.x)
        "offsetY" -> num(effect.stepOffset.y)
        "nearFill" -> paint(effect.nearFill)
        "farFill" -> paint(effect.farFill)
        "falloff" -> shape(effect.falloff)
        "farOpacity" -> num(effect.farOpacity)
        else -> null
    }

    override fun write(effect: Effect.Extrude, key: String, value: ParameterValue) = when (key) {
        // The model rejects a step count outside 1..512, and a slider dragged to its end would
        // otherwise throw out of a gesture rather than stopping at the limit.
        "steps" -> effect.copy(steps = value.number?.roundToInt()?.coerceIn(1, 512) ?: effect.steps)
        "offsetX" -> effect.copy(stepOffset = Vec2(value.number ?: effect.stepOffset.x, effect.stepOffset.y))
        "offsetY" -> effect.copy(stepOffset = Vec2(effect.stepOffset.x, value.number ?: effect.stepOffset.y))
        "nearFill" -> effect.copy(nearFill = value.paint ?: effect.nearFill)
        "farFill" -> effect.copy(farFill = value.paint ?: effect.farFill)
        "falloff" -> effect.copy(falloff = value.shape ?: effect.falloff)
        "farOpacity" -> effect.copy(farOpacity = value.number ?: effect.farOpacity)
        else -> effect
    }
}

object ReflectionModule : EffectModule<Effect.Reflection> {
    override val id = "reflection"
    override val label = "انعکاس"
    override val slot = PassSlot.REFLECTION
    override val parameters = listOf(
        ParameterSpec.Slider("gap", "فاصله", 0f..200f, 8f, ParameterSpec.Slider.Unit.PIXELS),
        ParameterSpec.Slider("height", "ارتفاع", 0f..1f, 0.5f, ParameterSpec.Slider.Unit.PERCENT),
        ParameterSpec.Slider("startOpacity", "شفافیت بالا", 0f..1f, 0.5f, ParameterSpec.Slider.Unit.PERCENT),
        ParameterSpec.Slider("endOpacity", "شفافیت پایین", 0f..1f, 0f, ParameterSpec.Slider.Unit.PERCENT),
        ParameterSpec.Slider("blur", "محو", 0f..100f, 0f, ParameterSpec.Slider.Unit.PIXELS),
    )

    override fun bleed(effect: Effect.Reflection, context: BleedContext) =
        Bleed(0f, 0f, 0f, effect.gap + context.shapeHeight * effect.height + effect.blur * BLUR_REACH)

    override fun describe(effect: Effect.Reflection, context: RenderContext) = PassDescriptor(
        shaderId = "reflection",
        floats = mapOf(
            "uGap" to effect.gap * context.scale,
            "uHeight" to effect.height,
            "uStartOpacity" to effect.startOpacity,
            "uEndOpacity" to effect.endOpacity,
        ),
    )

    override fun cost(effect: Effect.Reflection) = 2

    override fun read(effect: Effect.Reflection, key: String) = when (key) {
        "gap" -> num(effect.gap)
        "height" -> num(effect.height)
        "startOpacity" -> num(effect.startOpacity)
        "endOpacity" -> num(effect.endOpacity)
        "blur" -> num(effect.blur)
        else -> null
    }

    override fun write(effect: Effect.Reflection, key: String, value: ParameterValue) = when (key) {
        "gap" -> effect.copy(gap = value.number ?: effect.gap)
        "height" -> effect.copy(height = value.number ?: effect.height)
        "startOpacity" -> effect.copy(startOpacity = value.number ?: effect.startOpacity)
        "endOpacity" -> effect.copy(endOpacity = value.number ?: effect.endOpacity)
        "blur" -> effect.copy(blur = value.number ?: effect.blur)
        else -> effect
    }
}

object ChromaticOffsetModule : EffectModule<Effect.ChromaticOffset> {
    override val id = "chromatic_offset"
    override val label = "انحراف رنگی"
    override val slot = PassSlot.POST
    override val parameters = listOf(
        ParameterSpec.Slider("redX", "قرمز افقی", -50f..50f, -2f, ParameterSpec.Slider.Unit.PIXELS),
        ParameterSpec.Slider("redY", "قرمز عمودی", -50f..50f, 0f, ParameterSpec.Slider.Unit.PIXELS),
        ParameterSpec.Slider("blueX", "آبی افقی", -50f..50f, 2f, ParameterSpec.Slider.Unit.PIXELS),
        ParameterSpec.Slider("blueY", "آبی عمودی", -50f..50f, 0f, ParameterSpec.Slider.Unit.PIXELS),
    )

    override fun bleed(effect: Effect.ChromaticOffset, context: BleedContext) =
        listOf(effect.redOffset, effect.greenOffset, effect.blueOffset).fold(Bleed.NONE) { acc, o ->
            acc.union(Bleed(max(0f, -o.x), max(0f, -o.y), max(0f, o.x), max(0f, o.y)))
        }

    override fun describe(effect: Effect.ChromaticOffset, context: RenderContext) = PassDescriptor(
        shaderId = "chromatic_offset",
        vectors = mapOf(
            "uRed" to floatArrayOf(effect.redOffset.x * context.scale, effect.redOffset.y * context.scale),
            "uGreen" to floatArrayOf(effect.greenOffset.x * context.scale, effect.greenOffset.y * context.scale),
            "uBlue" to floatArrayOf(effect.blueOffset.x * context.scale, effect.blueOffset.y * context.scale),
        ),
    )

    override fun read(effect: Effect.ChromaticOffset, key: String) = when (key) {
        "redX" -> num(effect.redOffset.x)
        "redY" -> num(effect.redOffset.y)
        "blueX" -> num(effect.blueOffset.x)
        "blueY" -> num(effect.blueOffset.y)
        else -> null
    }

    override fun write(effect: Effect.ChromaticOffset, key: String, value: ParameterValue) = when (key) {
        "redX" -> effect.copy(redOffset = Vec2(value.number ?: effect.redOffset.x, effect.redOffset.y))
        "redY" -> effect.copy(redOffset = Vec2(effect.redOffset.x, value.number ?: effect.redOffset.y))
        "blueX" -> effect.copy(blueOffset = Vec2(value.number ?: effect.blueOffset.x, effect.blueOffset.y))
        "blueY" -> effect.copy(blueOffset = Vec2(effect.blueOffset.x, value.number ?: effect.blueOffset.y))
        else -> effect
    }
}

object BackdropBlurModule : EffectModule<Effect.BackdropBlur> {
    override val id = "backdrop_blur"
    override val label = "شیشه‌ای"
    override val slot = PassSlot.BACKDROP
    override val parameters = listOf(
        ParameterSpec.Slider("radius", "شعاع محو", 0f..200f, 24f, ParameterSpec.Slider.Unit.PIXELS),
        ParameterSpec.Slider("saturation", "اشباع", 0f..3f, 1f, ParameterSpec.Slider.Unit.PERCENT),
        ParameterSpec.Slider("brightness", "روشنایی", 0f..3f, 1f, ParameterSpec.Slider.Unit.PERCENT),
        ParameterSpec.ColorPicker("tint", "ته‌رنگ"),
        ParameterSpec.Slider("grain", "دانه", 0f..1f, 0f, ParameterSpec.Slider.Unit.PERCENT),
    )

    override fun bleed(effect: Effect.BackdropBlur, context: BleedContext) = Bleed.NONE

    override fun describe(effect: Effect.BackdropBlur, context: RenderContext) = PassDescriptor(
        shaderId = "backdrop_blur",
        floats = mapOf(
            "uRadius" to effect.radius * context.scale,
            "uSaturation" to effect.saturation,
            "uBrightness" to effect.brightness,
            "uGrain" to effect.grain,
        ),
    )

    override fun cost(effect: Effect.BackdropBlur) = 6

    override fun read(effect: Effect.BackdropBlur, key: String) = when (key) {
        "radius" -> num(effect.radius)
        "saturation" -> num(effect.saturation)
        "brightness" -> num(effect.brightness)
        "tint" -> tint(effect.tint)
        "grain" -> num(effect.grain)
        else -> null
    }

    override fun write(effect: Effect.BackdropBlur, key: String, value: ParameterValue) = when (key) {
        "radius" -> effect.copy(radius = value.number ?: effect.radius)
        "saturation" -> effect.copy(saturation = value.number ?: effect.saturation)
        "brightness" -> effect.copy(brightness = value.number ?: effect.brightness)
        "tint" -> effect.copy(tint = value.tint ?: effect.tint)
        "grain" -> effect.copy(grain = value.number ?: effect.grain)
        else -> effect
    }
}

object NoiseModule : EffectModule<Effect.Noise> {
    override val id = "noise"
    override val label = "نویز"
    override val slot = PassSlot.POST
    override val parameters = listOf(
        ParameterSpec.Slider("amount", "مقدار", 0f..1f, 0.1f, ParameterSpec.Slider.Unit.PERCENT),
        ParameterSpec.Slider("scale", "اندازه", 0.1f..10f, 1f),
        ParameterSpec.Toggle("monochrome", "تک‌رنگ", true),
    )

    override fun bleed(effect: Effect.Noise, context: BleedContext) = Bleed.NONE

    override fun describe(effect: Effect.Noise, context: RenderContext) = PassDescriptor(
        shaderId = "noise",
        floats = mapOf("uAmount" to effect.amount, "uScale" to effect.scale),
        ints = mapOf("uMono" to if (effect.monochrome) 1 else 0),
    )

    override fun read(effect: Effect.Noise, key: String) = when (key) {
        "amount" -> num(effect.amount)
        "scale" -> num(effect.scale)
        "monochrome" -> flag(effect.monochrome)
        else -> null
    }

    override fun write(effect: Effect.Noise, key: String, value: ParameterValue) = when (key) {
        "amount" -> effect.copy(amount = value.number ?: effect.amount)
        "scale" -> effect.copy(scale = value.number ?: effect.scale)
        "monochrome" -> effect.copy(monochrome = value.flag ?: effect.monochrome)
        else -> effect
    }
}

object EdgeRoughenModule : EffectModule<Effect.EdgeRoughen> {
    override val id = "edge_roughen"
    override val label = "فرسودگی لبه"
    override val slot = PassSlot.POST
    override val parameters = listOf(
        ParameterSpec.Slider("amount", "شدت", 0f..100f, 4f, ParameterSpec.Slider.Unit.PIXELS),
        ParameterSpec.Slider("detail", "جزئیات", 0f..1f, 0.5f, ParameterSpec.Slider.Unit.PERCENT),
        ParameterSpec.Slider("seed", "دانهٔ تصادفی", 0f..999f, 0f),
    )

    override fun bleed(effect: Effect.EdgeRoughen, context: BleedContext) = Bleed.uniform(effect.amount)

    override fun describe(effect: Effect.EdgeRoughen, context: RenderContext) = PassDescriptor(
        shaderId = "edge_roughen",
        floats = mapOf(
            "uAmount" to effect.amount * context.scale,
            "uDetail" to effect.detail,
            "uSeed" to effect.seed.toFloat(),
        ),
    )

    override fun cost(effect: Effect.EdgeRoughen) = 2

    override fun read(effect: Effect.EdgeRoughen, key: String) = when (key) {
        "amount" -> num(effect.amount)
        "detail" -> num(effect.detail)
        "seed" -> num(effect.seed)
        else -> null
    }

    override fun write(effect: Effect.EdgeRoughen, key: String, value: ParameterValue) = when (key) {
        "amount" -> effect.copy(amount = value.number ?: effect.amount)
        "detail" -> effect.copy(detail = value.number ?: effect.detail)
        "seed" -> effect.copy(seed = value.number?.roundToInt() ?: effect.seed)
        else -> effect
    }
}

/** The stock effect set. Adding an effect means adding a module and one line here. */
val builtinEffectRegistry: EffectRegistry = EffectRegistry.builder()
    .register(StrokeModule)
    .register(DropShadowModule)
    .register(InnerShadowModule)
    .register(OuterGlowModule)
    .register(InnerGlowModule)
    .register(BevelModule)
    .register(SatinModule)
    .register(OverlayModule)
    .register(ExtrudeModule)
    .register(ReflectionModule)
    .register(ChromaticOffsetModule)
    .register(BackdropBlurModule)
    .register(NoiseModule)
    .register(EdgeRoughenModule)
    .build()
