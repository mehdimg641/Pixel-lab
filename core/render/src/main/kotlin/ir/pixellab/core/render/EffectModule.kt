package ir.pixellab.core.render

import ir.pixellab.core.model.BlendMode
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Curve
import ir.pixellab.core.model.Effect
import ir.pixellab.core.model.Fill

/**
 * A control exposed by an effect.
 *
 * The editor builds its parameter sheet from these rather than from hand-written screens. That is
 * what makes an effect genuinely self-contained: adding one means adding a module, not editing a
 * renderer, a sheet layout and a preset serialiser in three different places.
 */
sealed interface ParameterSpec {
    val key: String
    val label: String

    data class Slider(
        override val key: String,
        override val label: String,
        val range: ClosedFloatingPointRange<Float>,
        val default: Float,
        val unit: Unit = Unit.NONE,
        /** Non-linear response so fine control sits where it is needed. */
        val skew: Float = 1f,
    ) : ParameterSpec {
        enum class Unit { NONE, PIXELS, DEGREES, PERCENT }
    }

    data class Toggle(override val key: String, override val label: String, val default: Boolean) : ParameterSpec

    data class ColorPicker(override val key: String, override val label: String) : ParameterSpec

    data class FillPicker(override val key: String, override val label: String) : ParameterSpec

    data class CurveEditor(override val key: String, override val label: String) : ParameterSpec

    data class Choice(
        override val key: String,
        override val label: String,
        val options: List<Option>,
        val default: String,
    ) : ParameterSpec {
        /**
         * [value] is stored and compared; [label] is shown.
         *
         * Keeping them apart is not cosmetic: the interface is Persian, and a choice that stored its
         * own label would put Persian text into saved documents and break the moment a label is
         * reworded.
         */
        data class Option(val value: String, val label: String)
    }
}

/**
 * A parameter's value, in the small set of shapes the editor knows how to present.
 *
 * This exists so the parameter sheet can be built from [EffectModule.parameters] alone. Without a
 * way to read and write a parameter by key, "effects are plugins" is only true of the renderer —
 * every new effect would still need a hand-written panel, which is exactly the coupling the module
 * design is meant to remove.
 */
sealed interface ParameterValue {
    data class Number(val value: Float) : ParameterValue

    data class Flag(val value: Boolean) : ParameterValue

    data class Tint(val value: Color) : ParameterValue

    data class Paint(val value: Fill) : ParameterValue

    data class Shape(val value: Curve) : ParameterValue

    /** An enum constant's name, never its label. */
    data class Option(val value: String) : ParameterValue

    data class Blend(val value: BlendMode) : ParameterValue
}

val ParameterValue.number: Float? get() = (this as? ParameterValue.Number)?.value
val ParameterValue.flag: Boolean? get() = (this as? ParameterValue.Flag)?.value
val ParameterValue.tint: Color? get() = (this as? ParameterValue.Tint)?.value
val ParameterValue.paint: Fill? get() = (this as? ParameterValue.Paint)?.value
val ParameterValue.shape: Curve? get() = (this as? ParameterValue.Shape)?.value
val ParameterValue.option: String? get() = (this as? ParameterValue.Option)?.value
val ParameterValue.blend: BlendMode? get() = (this as? ParameterValue.Blend)?.value

/**
 * The stored name behind any choice control, whichever value type backs it.
 *
 * A choice picker needs to know which of its options is currently selected without caring that the
 * blend mode happens to have its own value type.
 */
val ParameterValue.optionName: String? get() = option ?: blend?.name

/** Resolves an [ParameterValue.Option] to an enum constant, or null if it names none. */
inline fun <reified E : Enum<E>> ParameterValue.enumOf(): E? =
    option?.let { name -> enumValues<E>().firstOrNull { it.name == name } }

fun num(value: Float) = ParameterValue.Number(value)
fun num(value: Int) = ParameterValue.Number(value.toFloat())
fun flag(value: Boolean) = ParameterValue.Flag(value)
fun tint(value: Color) = ParameterValue.Tint(value)
fun paint(value: Fill) = ParameterValue.Paint(value)
fun shape(value: Curve) = ParameterValue.Shape(value)
fun option(value: Enum<*>) = ParameterValue.Option(value.name)

/** Uniform values plus a shader identifier; consumed by the platform GL layer. */
data class PassDescriptor(
    val shaderId: String,
    val floats: Map<String, Float> = emptyMap(),
    val vectors: Map<String, FloatArray> = emptyMap(),
    val ints: Map<String, Int> = emptyMap(),
    /** Number of times this pass runs; extrusion issues one per step. */
    val instanceCount: Int = 1,
)

/** Everything the renderer and the UI need to know about one effect type. */
interface EffectModule<E : Effect> {
    /** Stable identifier, also the serialised discriminator. */
    val id: String

    /** Localised name shown in the effect picker. */
    val label: String

    val slot: PassSlot

    val parameters: List<ParameterSpec>

    /** How far this effect reaches outside the shape, given its settings. */
    fun bleed(effect: E, context: BleedContext): Bleed

    fun describe(effect: E, context: RenderContext): PassDescriptor

    /** Relative cost, used to warn before a stack gets too heavy to stay interactive. */
    fun cost(effect: E): Int = 1

    /**
     * Current value of the parameter named [key], or null if this effect has no such parameter.
     *
     * Every key in [parameters] must be readable; [BuiltinEffectsParameterTest] asserts it, so a
     * parameter declared but not wired fails the build rather than showing as a dead control.
     */
    fun read(effect: E, key: String): ParameterValue?

    /** Returns [effect] with [key] set, or unchanged when the key or the value's shape is wrong. */
    fun write(effect: E, key: String, value: ParameterValue): E
}

data class BleedContext(val globalLightAngle: Float = 90f, val shapeHeight: Float = 0f)

data class RenderContext(
    val scale: Float = 1f,
    val globalLightAngle: Float = 90f,
    val linearBlending: Boolean = true,
)

/**
 * Registry of effect modules.
 *
 * The pipeline never names a concrete effect. It asks the registry, which means a new effect ships
 * as a single file and an entry here — no edits to the planner, the compositor or the UI.
 * [EffectRegistryTest] asserts every [Effect] subtype is registered, so an unwired effect breaks
 * the build instead of silently rendering as nothing.
 */
class EffectRegistry private constructor(
    private val modules: Map<String, EffectModule<out Effect>>,
) {
    val ids: Set<String> get() = modules.keys
    val all: Collection<EffectModule<out Effect>> get() = modules.values

    operator fun get(id: String): EffectModule<out Effect>? = modules[id]

    @Suppress("UNCHECKED_CAST")
    fun <E : Effect> moduleFor(effect: E): EffectModule<E>? =
        modules[idOf(effect)] as? EffectModule<E>

    fun bleedOf(effect: Effect, context: BleedContext): Bleed {
        @Suppress("UNCHECKED_CAST")
        val module = modules[idOf(effect)] as? EffectModule<Effect> ?: return Bleed.NONE
        return module.bleed(effect, context)
    }

    fun slotOf(effect: Effect): PassSlot = modules[idOf(effect)]?.slot ?: PassSlot.POST

    fun costOf(effect: Effect): Int {
        @Suppress("UNCHECKED_CAST")
        val module = modules[idOf(effect)] as? EffectModule<Effect> ?: return 1
        return module.cost(effect)
    }

    class Builder {
        private val modules = LinkedHashMap<String, EffectModule<out Effect>>()

        fun register(module: EffectModule<out Effect>): Builder = apply {
            require(modules.put(module.id, module) == null) {
                "an effect module with id '${module.id}' is already registered"
            }
        }

        fun build() = EffectRegistry(modules.toMap())
    }

    /** Every parameter of [effect], the shared ones first, in the order the sheet shows them. */
    fun parametersOf(effect: Effect): List<ParameterSpec> =
        COMMON_PARAMETERS + (modules[idOf(effect)]?.parameters ?: emptyList())

    /** Reads a parameter whether it is one of the shared ones or the effect's own. */
    fun read(effect: Effect, key: String): ParameterValue? {
        readCommon(effect, key)?.let { return it }
        @Suppress("UNCHECKED_CAST")
        val module = modules[idOf(effect)] as? EffectModule<Effect> ?: return null
        return module.read(effect, key)
    }

    fun write(effect: Effect, key: String, value: ParameterValue): Effect {
        if (key in COMMON_KEYS) return writeCommon(effect, key, value)
        @Suppress("UNCHECKED_CAST")
        val module = modules[idOf(effect)] as? EffectModule<Effect> ?: return effect
        return module.write(effect, key, value)
    }

    companion object {

        /**
         * Enabled, blend mode and opacity exist on every effect.
         *
         * Handled here rather than repeated in fourteen modules — and more importantly, so a new
         * effect gets them without doing anything, which is what keeps them consistent.
         */
        val COMMON_PARAMETERS: List<ParameterSpec> = listOf(
            ParameterSpec.Toggle("enabled", "فعال", true),
            ParameterSpec.Slider("opacity", "شفافیت", 0f..1f, 1f, ParameterSpec.Slider.Unit.PERCENT),
            ParameterSpec.Choice(
                "blendMode",
                "حالت ترکیب",
                BlendMode.entries.map { ParameterSpec.Choice.Option(it.name, it.persianLabel) },
                BlendMode.NORMAL.name,
            ),
        )

        private val COMMON_KEYS = COMMON_PARAMETERS.map { it.key }.toSet()

        private fun readCommon(effect: Effect, key: String): ParameterValue? = when (key) {
            "enabled" -> flag(effect.enabled)
            "opacity" -> num(effect.opacity)
            "blendMode" -> ParameterValue.Blend(effect.blendMode)
            else -> null
        }

        /**
         * Sets a shared field.
         *
         * The exhaustive `when` is the price of immutable data classes without reflection, and it is
         * worth paying once here: adding an effect breaks this build until it is listed, which is
         * strictly better than the effect silently ignoring its own opacity slider.
         */
        private fun writeCommon(effect: Effect, key: String, value: ParameterValue): Effect {
            val enabled = value.flag ?: effect.enabled
            val opacity = (value.number ?: effect.opacity).coerceIn(0f, 1f)
            val blend = value.blend ?: value.enumOf<BlendMode>() ?: effect.blendMode
            return when (effect) {
                is Effect.Stroke -> effect.copy(enabled = enabled, opacity = opacity, blendMode = blend)
                is Effect.DropShadow -> effect.copy(enabled = enabled, opacity = opacity, blendMode = blend)
                is Effect.InnerShadow -> effect.copy(enabled = enabled, opacity = opacity, blendMode = blend)
                is Effect.OuterGlow -> effect.copy(enabled = enabled, opacity = opacity, blendMode = blend)
                is Effect.InnerGlow -> effect.copy(enabled = enabled, opacity = opacity, blendMode = blend)
                is Effect.Bevel -> effect.copy(enabled = enabled, opacity = opacity, blendMode = blend)
                is Effect.Satin -> effect.copy(enabled = enabled, opacity = opacity, blendMode = blend)
                is Effect.Overlay -> effect.copy(enabled = enabled, opacity = opacity, blendMode = blend)
                is Effect.Extrude -> effect.copy(enabled = enabled, opacity = opacity, blendMode = blend)
                is Effect.Reflection -> effect.copy(enabled = enabled, opacity = opacity, blendMode = blend)
                is Effect.ChromaticOffset -> effect.copy(enabled = enabled, opacity = opacity, blendMode = blend)
                is Effect.BackdropBlur -> effect.copy(enabled = enabled, opacity = opacity, blendMode = blend)
                is Effect.Noise -> effect.copy(enabled = enabled, opacity = opacity, blendMode = blend)
                is Effect.EdgeRoughen -> effect.copy(enabled = enabled, opacity = opacity, blendMode = blend)
            }
        }

        /** Serialised discriminator for an effect instance. */
        fun idOf(effect: Effect): String = when (effect) {
            is Effect.Stroke -> "stroke"
            is Effect.DropShadow -> "drop_shadow"
            is Effect.InnerShadow -> "inner_shadow"
            is Effect.OuterGlow -> "outer_glow"
            is Effect.InnerGlow -> "inner_glow"
            is Effect.Bevel -> "bevel"
            is Effect.Satin -> "satin"
            is Effect.Overlay -> "overlay"
            is Effect.Extrude -> "extrude"
            is Effect.Reflection -> "reflection"
            is Effect.ChromaticOffset -> "chromatic_offset"
            is Effect.BackdropBlur -> "backdrop_blur"
            is Effect.Noise -> "noise"
            is Effect.EdgeRoughen -> "edge_roughen"
        }

        fun builder() = Builder()
    }
}
