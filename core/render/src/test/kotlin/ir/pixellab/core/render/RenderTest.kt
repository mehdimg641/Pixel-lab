package ir.pixellab.core.render

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.BlendMode
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Effect
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.StrokePosition
import ir.pixellab.core.model.Style
import ir.pixellab.core.model.Vec2
import org.junit.jupiter.api.Test

class BlendingTest {

    private val eps = 1e-4f

    @Test
    fun `separable formulas match the PDF blend model`() {
        Blending.channel(BlendMode.MULTIPLY, 0.5f, 0.5f) shouldBe (0.25f plusOrMinus eps)
        Blending.channel(BlendMode.SCREEN, 0.5f, 0.5f) shouldBe (0.75f plusOrMinus eps)
        Blending.channel(BlendMode.DIFFERENCE, 0.8f, 0.3f) shouldBe (0.5f plusOrMinus eps)
        Blending.channel(BlendMode.EXCLUSION, 0.5f, 0.5f) shouldBe (0.5f plusOrMinus eps)
        Blending.channel(BlendMode.LINEAR_BURN, 0.6f, 0.6f) shouldBe (0.2f plusOrMinus eps)
        Blending.channel(BlendMode.LINEAR_DODGE, 0.6f, 0.6f) shouldBe (1f plusOrMinus eps)
        Blending.channel(BlendMode.SUBTRACT, 0.6f, 0.2f) shouldBe (0.4f plusOrMinus eps)
        Blending.channel(BlendMode.DIVIDE, 0.4f, 0.8f) shouldBe (0.5f plusOrMinus eps)
    }

    @Test
    fun `overlay is hard light with the operands swapped`() {
        for (cb in listOf(0.1f, 0.4f, 0.6f, 0.9f)) {
            for (cs in listOf(0.2f, 0.5f, 0.8f)) {
                Blending.channel(BlendMode.OVERLAY, cb, cs) shouldBe
                    (Blending.channel(BlendMode.HARD_LIGHT, cs, cb) plusOrMinus eps)
            }
        }
    }

    @Test
    fun `division and burn guard against their singularities`() {
        Blending.channel(BlendMode.DIVIDE, 0.5f, 0f) shouldBe 1f
        Blending.channel(BlendMode.COLOR_BURN, 1f, 0f) shouldBe 1f
        Blending.channel(BlendMode.COLOR_BURN, 0.5f, 0f) shouldBe 0f
        Blending.channel(BlendMode.COLOR_DODGE, 0f, 0.5f) shouldBe 0f
        Blending.channel(BlendMode.COLOR_DODGE, 0.5f, 1f) shouldBe 1f
    }

    @Test
    fun `hard mix collapses to the extremes`() {
        Blending.channel(BlendMode.HARD_MIX, 0.9f, 0.9f) shouldBe 1f
        Blending.channel(BlendMode.HARD_MIX, 0.1f, 0.1f) shouldBe 0f
    }

    @Test
    fun `every mode leaves an opaque backdrop unchanged when the source is fully transparent`() {
        val backdrop = floatArrayOf(0.2f, 0.4f, 0.6f, 1f)
        val clear = floatArrayOf(0.9f, 0.1f, 0.3f, 0f)
        for (mode in BlendMode.entries) {
            val out = Blending.composite(mode, backdrop, clear)
            out[0] shouldBe (backdrop[0] plusOrMinus eps)
            out[1] shouldBe (backdrop[1] plusOrMinus eps)
            out[2] shouldBe (backdrop[2] plusOrMinus eps)
            out[3] shouldBe (1f plusOrMinus eps)
        }
    }

    @Test
    fun `an opaque source in normal mode replaces the backdrop`() {
        val out = Blending.composite(
            BlendMode.NORMAL,
            floatArrayOf(0.2f, 0.4f, 0.6f, 1f),
            floatArrayOf(0.9f, 0.1f, 0.3f, 1f),
        )
        out[0] shouldBe (0.9f plusOrMinus eps)
        out[2] shouldBe (0.3f plusOrMinus eps)
    }

    @Test
    fun `opacity interpolates towards the backdrop`() {
        val out = Blending.composite(
            BlendMode.NORMAL,
            floatArrayOf(0f, 0f, 0f, 1f),
            floatArrayOf(1f, 1f, 1f, 1f),
            opacity = 0.5f,
        )
        out[0] shouldBe (0.5f plusOrMinus eps)
    }

    @Test
    fun `compositing onto nothing yields nothing`() {
        val out = Blending.composite(
            BlendMode.MULTIPLY,
            floatArrayOf(0f, 0f, 0f, 0f),
            floatArrayOf(0f, 0f, 0f, 0f),
        )
        out[3] shouldBe 0f
    }

    @Test
    fun `luminosity blending takes brightness from the source and colour from the backdrop`() {
        val backdrop = Triple(0.8f, 0.2f, 0.2f)
        val source = Triple(0.5f, 0.5f, 0.5f)
        val out = Blending.rgb(BlendMode.LUMINOSITY, backdrop, source)
        Blending.luminosity(out) shouldBe (Blending.luminosity(source) plusOrMinus 1e-3f)
    }

    @Test
    fun `colour blending takes luminance from the backdrop`() {
        val backdrop = Triple(0.2f, 0.2f, 0.2f)
        val source = Triple(0.9f, 0.1f, 0.1f)
        val out = Blending.rgb(BlendMode.COLOR, backdrop, source)
        Blending.luminosity(out) shouldBe (Blending.luminosity(backdrop) plusOrMinus 1e-3f)
    }

    @Test
    fun `darker and lighter colour pick a whole colour rather than mixing channels`() {
        val dark = Triple(0.1f, 0.1f, 0.5f)
        val light = Triple(0.9f, 0.9f, 0.2f)
        Blending.rgb(BlendMode.DARKER_COLOR, dark, light) shouldBe dark
        Blending.rgb(BlendMode.LIGHTER_COLOR, dark, light) shouldBe light
    }

    @Test
    fun `results stay inside the unit range for every mode and operand`() {
        val samples = listOf(0f, 0.13f, 0.5f, 0.87f, 1f)
        for (mode in BlendMode.entries) {
            for (cb in samples) for (cs in samples) {
                val out = Blending.composite(
                    mode,
                    floatArrayOf(cb, cb, cb, 1f),
                    floatArrayOf(cs, cs, cs, 1f),
                )
                for (i in 0..3) {
                    val ok = out[i] >= -1e-3f && out[i] <= 1f + 1e-3f
                    if (!ok) error("$mode produced ${out[i]} for backdrop=$cb source=$cs")
                }
            }
        }
    }
}

class BlendShaderTest {

    @Test
    fun `the shader handles every declared blend mode`() {
        val source = BlendShaders.compositeFragment
        // Each mode is dispatched on its ordinal, so a new enum constant without a shader branch
        // must fail here rather than silently rendering as Normal.
        for (mode in BlendMode.entries) {
            val marker = "mode == ${mode.ordinal}"
            if (!source.contains(marker)) error("no shader branch for $mode (ordinal ${mode.ordinal})")
        }
    }

    @Test
    fun `uniform values follow the enum ordinals`() {
        BlendShaders.uniformValue(BlendMode.NORMAL) shouldBe 0
        BlendShaders.uniformValue(BlendMode.LUMINOSITY) shouldBe BlendMode.entries.lastIndex
    }

    @Test
    fun `shaders declare the ES 3 version and balanced braces`() {
        for (src in listOf(BlendShaders.compositeFragment)) {
            src.trimStart().startsWith("#version 300 es") shouldBe true
            src.count { it == '{' } shouldBe src.count { it == '}' }
            src.count { it == '(' } shouldBe src.count { it == ')' }
        }
    }
}

class RenderPlannerTest {

    @Test
    fun `passes come out in photoshop's draw order regardless of list order`() {
        val style = Style(
            effects = listOf(
                Effect.Stroke(width = 4f, fill = Fill.Solid(Color.BLACK)),
                Effect.DropShadow(),
                Effect.Bevel(),
                Effect.OuterGlow(),
            ),
        )
        val slots = RenderPlanner.plan(style).passes.map { it.slot }
        (slots.indexOf(PassSlot.DROP_SHADOW) < slots.indexOf(PassSlot.OUTER_GLOW)) shouldBe true
        (slots.indexOf(PassSlot.OUTER_GLOW) < slots.indexOf(PassSlot.FILL)) shouldBe true
        (slots.indexOf(PassSlot.FILL) < slots.indexOf(PassSlot.BEVEL)) shouldBe true
        (slots.indexOf(PassSlot.BEVEL) < slots.indexOf(PassSlot.STROKE)) shouldBe true
    }

    @Test
    fun `a fill pass always exists even with no effects`() {
        RenderPlanner.plan(Style()).passes.count { it.isFill } shouldBe 1
    }

    @Test
    fun `repeated effects keep their relative order inside a slot`() {
        val style = Style(
            effects = listOf(
                Effect.Stroke(width = 12f, fill = Fill.Solid(Color.WHITE)),
                Effect.Stroke(width = 8f, fill = Fill.Solid(Color.BLACK)),
                Effect.Stroke(width = 4f, fill = Fill.Solid(Color.WHITE)),
            ),
        )
        val widths = RenderPlanner.plan(style).passes
            .mapNotNull { (it.effect as? Effect.Stroke)?.width }
        widths shouldBe listOf(12f, 8f, 4f)
    }

    @Test
    fun `a layered shadow stack reserves room for its furthest shadow`() {
        // The distances and blurs measured in the reference PSD.
        val style = Style(
            effects = listOf(
                Effect.DropShadow(distance = 6f, blur = 13f, angle = 90f, useGlobalLight = false),
                Effect.DropShadow(distance = 16f, blur = 29f, angle = 90f, useGlobalLight = false),
                Effect.DropShadow(distance = 71f, blur = 111f, angle = 90f, useGlobalLight = false),
            ),
        )
        val plan = RenderPlanner.plan(style)
        // Angle 90 points up mathematically, so the shadow lands above the shape on screen.
        plan.bleed.top shouldBe (71f + 111f * 3f plusOrMinus 0.5f)
        plan.bleed.maxExtent shouldBe (404f plusOrMinus 0.5f)
    }

    @Test
    fun `an outside stroke bleeds by its full width and an inside stroke not at all`() {
        val outside = Style(effects = listOf(Effect.Stroke(10f, Fill.Solid(Color.BLACK), StrokePosition.OUTSIDE)))
        val inside = Style(effects = listOf(Effect.Stroke(10f, Fill.Solid(Color.BLACK), StrokePosition.INSIDE)))
        RenderPlanner.plan(outside).bleed.maxExtent shouldBe 10f
        RenderPlanner.plan(inside).bleed.maxExtent shouldBe 0f
    }

    @Test
    fun `extrusion bleeds along its accumulated offset`() {
        val style = Style(effects = listOf(Effect.Extrude(steps = 29, stepOffset = Vec2(-3f, 3f))))
        val bleed = RenderPlanner.plan(style).bleed
        bleed.left shouldBe 87f
        bleed.bottom shouldBe 87f
        bleed.right shouldBe 0f
    }

    @Test
    fun `texture bounds expand the shape by the bleed`() {
        val style = Style(effects = listOf(Effect.OuterGlow(blur = 10f, spread = 5f)))
        val bounds = RenderPlanner.plan(style).textureBounds(Rect(0f, 0f, 100f, 100f))
        bounds.left shouldBe -35f
        bounds.right shouldBe 135f
    }

    @Test
    fun `a reflection extends below by gap plus a share of the shape height`() {
        val style = Style(effects = listOf(Effect.Reflection(gap = 8f, height = 0.5f, blur = 0f)))
        RenderPlanner.plan(style, shapeHeight = 200f).bleed.bottom shouldBe 108f
    }

    @Test
    fun `plans that sample the destination are flagged`() {
        RenderPlanner.plan(Style(effects = listOf(Effect.BackdropBlur()))).readsBackdrop shouldBe true
        RenderPlanner.plan(Style(fill = Fill.Backdrop())).readsBackdrop shouldBe true
        RenderPlanner.plan(Style()).readsBackdrop shouldBe false
    }

    @Test
    fun `disabled effects are excluded from the plan and from the bleed`() {
        val style = Style(effects = listOf(Effect.DropShadow(distance = 100f, blur = 50f, enabled = false)))
        val plan = RenderPlanner.plan(style)
        plan.passes.count { it.effect != null } shouldBe 0
        plan.bleed shouldBe Bleed.NONE
    }

    @Test
    fun `cost grows with extrusion depth so heavy stacks can be warned about`() {
        val light = RenderPlanner.plan(Style(effects = listOf(Effect.Extrude(steps = 4))))
        val heavy = RenderPlanner.plan(Style(effects = listOf(Effect.Extrude(steps = 200))))
        (heavy.estimatedCost > light.estimatedCost * 10) shouldBe true
    }

    @Test
    fun `global light steers every shadow that opts into it`() {
        val style = Style(effects = listOf(Effect.DropShadow(distance = 50f, blur = 0f, useGlobalLight = true)))
        val up = RenderPlanner.plan(style, globalLightAngle = 90f).bleed
        val down = RenderPlanner.plan(style, globalLightAngle = 270f).bleed
        up.top shouldBe (50f plusOrMinus 0.5f)
        down.bottom shouldBe (50f plusOrMinus 0.5f)
    }

    @Test
    fun `bleed rounds up to whole pixels at the export scale`() {
        val bleed = Bleed(1.2f, 0f, 3.7f, 0f).toPixels(2f)
        bleed.left shouldBe 3f
        bleed.right shouldBe 8f
    }

    @Test
    fun `a hollow shape is reported so the renderer can skip its fill`() {
        RenderPlanner.plan(Style(fillOpacity = 0f)).fillIsInvisible shouldBe true
        RenderPlanner.plan(Style()).fillIsInvisible shouldBe false
    }
}
