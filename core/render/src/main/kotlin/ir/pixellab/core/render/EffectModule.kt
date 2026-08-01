package ir.pixellab.core.render

import ir.pixellab.core.model.Effect

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
        val options: List<String>,
        val default: String,
    ) : ParameterSpec
}

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

    companion object {
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
