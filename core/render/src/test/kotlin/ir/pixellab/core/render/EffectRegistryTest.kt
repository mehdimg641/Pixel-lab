package ir.pixellab.core.render

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Effect
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.Vec2
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EffectRegistryTest {

    /**
     * Every effect the model can express must have a module.
     *
     * Adding a variant to [Effect] without registering it would otherwise render as nothing at all,
     * which is invisible until a user applies it. Failing the build is cheaper.
     */
    @Test
    fun `every effect type is registered`() {
        val samples: List<Effect> = listOf(
            Effect.Stroke(4f, Fill.Solid(Color.BLACK)),
            Effect.DropShadow(),
            Effect.InnerShadow(),
            Effect.OuterGlow(),
            Effect.InnerGlow(),
            Effect.Bevel(),
            Effect.Satin(),
            Effect.Overlay(Fill.Solid(Color.WHITE)),
            Effect.Extrude(),
            Effect.Reflection(),
            Effect.ChromaticOffset(),
            Effect.BackdropBlur(),
            Effect.Noise(),
            Effect.EdgeRoughen(),
        )
        val sealedCount = Effect::class.sealedSubclasses.size
        samples.size shouldBe sealedCount

        for (effect in samples) {
            val id = EffectRegistry.idOf(effect)
            builtinEffectRegistry[id].shouldNotBeNull()
        }
        builtinEffectRegistry.ids.size shouldBe sealedCount
    }

    @Test
    fun `every module exposes at least one control so its sheet is never empty`() {
        for (module in builtinEffectRegistry.all) {
            module.parameters.shouldNotBeEmpty()
            module.label.isNotBlank() shouldBe true
        }
    }

    @Test
    fun `parameter keys are unique inside a module`() {
        for (module in builtinEffectRegistry.all) {
            val keys = module.parameters.map { it.key }
            keys.distinct().size shouldBe keys.size
        }
    }

    @Test
    fun `slider defaults sit inside their declared range`() {
        for (module in builtinEffectRegistry.all) {
            for (p in module.parameters.filterIsInstance<ParameterSpec.Slider>()) {
                val ok = p.default in p.range
                if (!ok) error("${module.id}.${p.key} default ${p.default} outside ${p.range}")
            }
        }
    }

    @Test
    fun `bevel depth reaches the values real styles use`() {
        val depth = BevelModule.parameters
            .filterIsInstance<ParameterSpec.Slider>()
            .single { it.key == "depth" }
        // The reference PSDs run to 317%; a 0..100 slider could not reproduce them.
        (depth.range.endInclusive >= 317f) shouldBe true
    }

    @Test
    fun `a third-party effect can be registered without touching the pipeline`() {
        // Reuses an existing effect type but supplies a different module, standing in for a plugin.
        val custom = object : EffectModule<Effect.Noise> {
            override val id = "custom_halftone"
            override val label = "هافتون"
            override val slot = PassSlot.POST
            override val parameters = listOf(ParameterSpec.Slider("dots", "تراکم", 1f..64f, 8f))
            override fun bleed(effect: Effect.Noise, context: BleedContext) = Bleed.uniform(2f)
            override fun describe(effect: Effect.Noise, context: RenderContext) =
                PassDescriptor("halftone", floats = mapOf("uDots" to effect.scale))
            override fun cost(effect: Effect.Noise) = 2
        }
        val extended = EffectRegistry.builder()
            .register(StrokeModule)
            .register(custom)
            .build()

        extended["custom_halftone"].shouldNotBeNull().label shouldBe "هافتون"
        extended.ids.size shouldBe 2
    }

    @Test
    fun `registering a duplicate id fails loudly`() {
        assertThrows<IllegalArgumentException> {
            EffectRegistry.builder().register(StrokeModule).register(StrokeModule).build()
        }
    }

    @Test
    fun `registry bleed agrees with the planner`() {
        val shadow = Effect.DropShadow(distance = 71f, blur = 111f, angle = 90f, useGlobalLight = false)
        val viaRegistry = builtinEffectRegistry.bleedOf(shadow, BleedContext())
        viaRegistry.top shouldBe (71f + 111f * 3f)
    }

    @Test
    fun `descriptors scale pixel units for high resolution export`() {
        val shadow = Effect.DropShadow(distance = 10f, blur = 20f, useGlobalLight = false, angle = 90f)
        val preview = DropShadowModule.describe(shadow, RenderContext(scale = 1f))
        val export = DropShadowModule.describe(shadow, RenderContext(scale = 4f))
        export.floats.getValue("uBlur") shouldBe (preview.floats.getValue("uBlur") * 4f)
        export.vectors.getValue("uOffset")[1] shouldBe (preview.vectors.getValue("uOffset")[1] * 4f)
    }

    @Test
    fun `extrusion issues one instance per step`() {
        val effect = Effect.Extrude(steps = 29, stepOffset = Vec2(-3f, 3f))
        ExtrudeModule.describe(effect, RenderContext()).instanceCount shouldBe 29
        builtinEffectRegistry.costOf(effect) shouldBe 29
    }

    @Test
    fun `slots come from the modules`() {
        builtinEffectRegistry.slotOf(Effect.BackdropBlur()) shouldBe PassSlot.BACKDROP
        builtinEffectRegistry.slotOf(Effect.Stroke(1f, Fill.Solid(Color.BLACK))) shouldBe PassSlot.STROKE
        builtinEffectRegistry.slotOf(Effect.Bevel()) shouldBe PassSlot.BEVEL
    }
}
