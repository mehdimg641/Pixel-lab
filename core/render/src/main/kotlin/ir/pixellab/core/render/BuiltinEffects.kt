package ir.pixellab.core.render

import ir.pixellab.core.model.BevelStyle
import ir.pixellab.core.model.Effect
import ir.pixellab.core.model.StrokePosition
import ir.pixellab.core.model.Vec2
import kotlin.math.max

/** Blur kernels reach roughly three standard deviations; less than that visibly clips. */
private const val BLUR_REACH = 3f

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
        ParameterSpec.Choice("position", "موقعیت", listOf("داخل", "مرکز", "بیرون"), "بیرون"),
        ParameterSpec.Slider("opacity", "شفافیت", 0f..1f, 1f, ParameterSpec.Slider.Unit.PERCENT),
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
        ints = mapOf("uInner" to 0),
    )

    override fun cost(effect: Effect.OuterGlow) = 4
}

object InnerGlowModule : EffectModule<Effect.InnerGlow> {
    override val id = "inner_glow"
    override val label = "درخشش داخلی"
    override val slot = PassSlot.INNER_GLOW
    override val parameters = listOf(
        ParameterSpec.FillPicker("fill", "پرکننده"),
        ParameterSpec.Choice("source", "منبع", listOf("مرکز", "لبه"), "لبه"),
        ParameterSpec.Slider("choke", "فشردگی", 0f..100f, 0f, ParameterSpec.Slider.Unit.PIXELS),
        ParameterSpec.Slider("blur", "محو", 0f..300f, 8f, ParameterSpec.Slider.Unit.PIXELS),
    )

    override fun bleed(effect: Effect.InnerGlow, context: BleedContext) = Bleed.NONE

    override fun describe(effect: Effect.InnerGlow, context: RenderContext) = PassDescriptor(
        shaderId = "glow",
        floats = mapOf("uBlur" to effect.blur * context.scale, "uSpread" to effect.choke * context.scale),
        ints = mapOf("uInner" to 1, "uSource" to effect.source.ordinal),
    )

    override fun cost(effect: Effect.InnerGlow) = 4
}

object BevelModule : EffectModule<Effect.Bevel> {
    override val id = "bevel"
    override val label = "پخ و برجستگی"
    override val slot = PassSlot.BEVEL
    override val parameters = listOf(
        ParameterSpec.Choice(
            "style", "سبک",
            listOf("پخ بیرونی", "پخ داخلی", "برجسته", "برجستهٔ بالشی", "برجستهٔ خط دور"),
            "پخ داخلی",
        ),
        ParameterSpec.Choice("technique", "روش", listOf("نرم", "اسکنهٔ سخت", "اسکنهٔ نرم"), "نرم"),
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
}

object OverlayModule : EffectModule<Effect.Overlay> {
    override val id = "overlay"
    override val label = "روکش"
    override val slot = PassSlot.OVERLAY
    override val parameters = listOf(ParameterSpec.FillPicker("fill", "پرکننده"))

    override fun bleed(effect: Effect.Overlay, context: BleedContext) = Bleed.NONE

    override fun describe(effect: Effect.Overlay, context: RenderContext) =
        PassDescriptor(shaderId = "overlay", floats = mapOf("uOpacity" to effect.opacity))
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
        floats = mapOf("uFarOpacity" to effect.farOpacity),
        instanceCount = effect.steps,
    )

    override fun cost(effect: Effect.Extrude) = effect.steps
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
