package ir.pixellab.core.render

import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.BlendMode
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Curve
import ir.pixellab.core.model.Effect
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.StrokePosition
import org.junit.jupiter.api.Test

/**
 * The contract between what an effect *declares* and what it can actually do.
 *
 * The parameter sheet is generated from [EffectModule.parameters]. A key declared but not wired
 * shows as a control that does nothing — silent, and only found by someone dragging that particular
 * slider. These tests make it a build failure instead.
 */
class ParameterBindingTest {

    /** One instance of every effect, so the whole registry is covered rather than a sample of it. */
    private val samples: List<Effect> = listOf(
        Effect.Stroke(4f, Fill.Solid(Color.BLACK)),
        Effect.DropShadow(), Effect.InnerShadow(), Effect.OuterGlow(), Effect.InnerGlow(),
        Effect.Bevel(), Effect.Satin(), Effect.Overlay(Fill.Solid(Color.WHITE)),
        Effect.Extrude(), Effect.Reflection(), Effect.ChromaticOffset(),
        Effect.BackdropBlur(), Effect.Noise(), Effect.EdgeRoughen(),
    )

    /** A value of the shape a spec asks for, deliberately different from any default. */
    private fun probe(spec: ParameterSpec, current: ParameterValue?): ParameterValue = when (spec) {
        is ParameterSpec.Slider -> num(
            // Inside the declared range but not where it started, so a write that quietly does
            // nothing is distinguishable from one that works.
            if (current?.number == spec.range.start) spec.range.endInclusive else spec.range.start,
        )
        is ParameterSpec.Toggle -> flag(!(current?.flag ?: spec.default))
        is ParameterSpec.ColorPicker -> tint(Color(0.1f, 0.2f, 0.3f, 0.4f))
        is ParameterSpec.FillPicker -> paint(Fill.Solid(Color(0.9f, 0.1f, 0.5f, 1f)))
        is ParameterSpec.CurveEditor -> shape(Curve.CHAMFER)
        is ParameterSpec.Choice -> ParameterValue.Option(
            spec.options.map { it.value }.first { it != current?.optionName },
        )
    }

    @Test
    fun `every effect covers every parameter it declares`() {
        for (effect in samples) {
            val id = EffectRegistry.idOf(effect)
            for (spec in builtinEffectRegistry.parametersOf(effect)) {
                val read = builtinEffectRegistry.read(effect, spec.key)
                    ?: error("$id declares '${spec.key}' but cannot read it")

                val written = builtinEffectRegistry.write(effect, spec.key, probe(spec, read))
                val back = builtinEffectRegistry.read(written, spec.key)
                if (back == read) error("$id declares '${spec.key}' but writing it changed nothing")
            }
        }
    }

    @Test
    fun `a read value matches the shape its spec declares`() {
        for (effect in samples) {
            for (spec in builtinEffectRegistry.parametersOf(effect)) {
                val value = builtinEffectRegistry.read(effect, spec.key)
                val matches = when (spec) {
                    is ParameterSpec.Slider -> value is ParameterValue.Number
                    is ParameterSpec.Toggle -> value is ParameterValue.Flag
                    is ParameterSpec.ColorPicker -> value is ParameterValue.Tint
                    is ParameterSpec.FillPicker -> value is ParameterValue.Paint
                    is ParameterSpec.CurveEditor -> value is ParameterValue.Shape
                    // The blend-mode picker is a choice backed by its own value type.
                    is ParameterSpec.Choice -> value is ParameterValue.Option || value is ParameterValue.Blend
                }
                if (!matches) {
                    error("${EffectRegistry.idOf(effect)}.${spec.key} reads as $value, not what its spec declares")
                }
            }
        }
    }

    @Test
    fun `a choice stores the enum constant and never its label`() {
        for (effect in samples) {
            for (spec in builtinEffectRegistry.parametersOf(effect).filterIsInstance<ParameterSpec.Choice>()) {
                // A stored Persian label would go straight into saved documents and break them the
                // moment the wording changed.
                val stored = builtinEffectRegistry.read(effect, spec.key)?.optionName
                if (stored !in spec.options.map { it.value }) {
                    error("${EffectRegistry.idOf(effect)}.${spec.key} holds '$stored', not one of its options")
                }
                spec.default shouldBe spec.options.map { it.value }.first { it == spec.default }
            }
        }
    }

    @Test
    fun `writing a value of the wrong shape leaves the effect alone`() {
        val shadow = Effect.DropShadow(blur = 20f)
        // A wrong-typed write is a bug upstream; corrupting the document because of it is worse
        // than ignoring it.
        builtinEffectRegistry.write(shadow, "blur", flag(true)) shouldBe shadow
        builtinEffectRegistry.write(shadow, "knockOut", num(3f)) shouldBe shadow
        builtinEffectRegistry.write(shadow, "nonexistent", num(3f)) shouldBe shadow
    }

    @Test
    fun `the shared parameters work on every effect without the module doing anything`() {
        for (effect in samples) {
            val id = EffectRegistry.idOf(effect)
            builtinEffectRegistry.write(effect, "enabled", flag(false)).enabled shouldBe false
            builtinEffectRegistry.write(effect, "opacity", num(0.25f)).opacity shouldBe 0.25f
            builtinEffectRegistry.write(effect, "blendMode", ParameterValue.Blend(BlendMode.HARD_LIGHT))
                .blendMode shouldBe BlendMode.HARD_LIGHT
            // And by name, which is what a picker sends.
            builtinEffectRegistry.write(effect, "blendMode", ParameterValue.Option("EXCLUSION"))
                .blendMode shouldBe BlendMode.EXCLUSION
            builtinEffectRegistry.read(effect, "enabled")?.flag ?: error("$id cannot read 'enabled'")
        }
    }

    @Test
    fun `opacity is clamped rather than accepted out of range`() {
        builtinEffectRegistry.write(Effect.Noise(), "opacity", num(4f)).opacity shouldBe 1f
        builtinEffectRegistry.write(Effect.Noise(), "opacity", num(-2f)).opacity shouldBe 0f
    }

    @Test
    fun `a step count is clamped to what the model accepts`() {
        // Effect.Extrude rejects anything outside 1..512, so a slider dragged to its end would
        // throw out of the middle of a gesture.
        val far = builtinEffectRegistry.write(Effect.Extrude(), "steps", num(9000f)) as Effect.Extrude
        far.steps shouldBe 512
        val none = builtinEffectRegistry.write(Effect.Extrude(), "steps", num(0f)) as Effect.Extrude
        none.steps shouldBe 1
    }

    @Test
    fun `a written enum survives the round trip`() {
        val stroke = builtinEffectRegistry.write(
            Effect.Stroke(4f, Fill.Solid(Color.BLACK)),
            "position",
            ParameterValue.Option(StrokePosition.INSIDE.name),
        ) as Effect.Stroke
        stroke.position shouldBe StrokePosition.INSIDE
        builtinEffectRegistry.read(stroke, "position")?.option shouldBe "INSIDE"
    }

    @Test
    fun `every parameter key is unique within an effect`() {
        for (effect in samples) {
            val keys = builtinEffectRegistry.parametersOf(effect).map { it.key }
            // A duplicate key means two controls fighting over one value; the sheet would show both.
            keys.size shouldBe keys.distinct().size
        }
    }
}
