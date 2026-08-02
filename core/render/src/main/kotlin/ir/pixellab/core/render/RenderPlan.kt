package ir.pixellab.core.render

import ir.pixellab.core.model.Effect
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.Style
import ir.pixellab.core.model.Vec2
import kotlin.math.abs
import kotlin.math.max

/**
 * Where an effect sits in the draw order.
 *
 * Photoshop composites layer effects in a fixed sequence regardless of the order they appear in the
 * panel, and imported documents only look right if that sequence is reproduced exactly.
 */
enum class PassSlot {
    /**
     * Preparation that runs before anything is drawn — building the shared distance field, for
     * example. Kept out of the effect order so it cannot be mistaken for a drawing step.
     */
    PREPARE,

    BACKDROP,
    DROP_SHADOW,
    OUTER_GLOW,
    EXTRUDE,
    REFLECTION,
    FILL,
    OVERLAY,
    SATIN,
    INNER_GLOW,
    INNER_SHADOW,
    BEVEL,
    STROKE,
    POST,
}

/** One draw operation. [order] is the index within the whole plan, back to front. */
data class RenderPass(val slot: PassSlot, val effect: Effect?, val order: Int) {
    val isFill: Boolean get() = slot == PassSlot.FILL
}

/**
 * How far an effect reaches beyond the shape it decorates, in canvas units.
 *
 * The layer is rendered into an offscreen texture, so this is what decides that texture's padding.
 * Underestimate it and shadows get clipped at the edge — the defect is subtle at small blur radii
 * and glaring at the large ones real styles use, where one reference file ramps a shadow out to
 * distance 71 with blur 111.
 */
data class Bleed(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun union(o: Bleed) = Bleed(
        max(left, o.left), max(top, o.top), max(right, o.right), max(bottom, o.bottom),
    )

    fun expand(rect: Rect) = Rect(rect.left - left, rect.top - top, rect.right + right, rect.bottom + bottom)

    val maxExtent: Float get() = maxOf(left, top, right, bottom)

    companion object {
        val NONE = Bleed(0f, 0f, 0f, 0f)

        fun uniform(v: Float) = Bleed(v, v, v, v)

        /** Bleed from a directional effect: an offset plus a symmetric grow. */
        fun directional(offset: Vec2, grow: Float) = Bleed(
            left = max(0f, grow - offset.x),
            top = max(0f, grow - offset.y),
            right = max(0f, grow + offset.x),
            bottom = max(0f, grow + offset.y),
        )
    }
}

/**
 * Turns a [Style] into an ordered list of passes plus the padding its texture needs.
 *
 * Effects keep their relative order inside a slot, so a stack of three strokes still paints widest
 * first and the concentric outline survives.
 */
object RenderPlanner {

    /**
     * @param shapeHeight height of the shape being decorated, needed because a reflection extends
     *   below it by a fraction of its own size. Pass 0 when the shape is not measured yet; the
     *   reflection then contributes only its gap and blur.
     */
    fun plan(style: Style, globalLightAngle: Float = 90f, shapeHeight: Float = 0f): RenderPlan {
        val active = style.activeEffects
        val slotted = active.map { it to slotOf(it) }

        val ordered = buildList {
            for (slot in PassSlot.entries) {
                if (slot == PassSlot.FILL) {
                    add(RenderPass(PassSlot.FILL, null, size))
                    continue
                }
                for ((effect, s) in slotted) if (s == slot) add(RenderPass(slot, effect, size))
            }
        }

        val bleed = active.fold(Bleed.NONE) { acc, e -> acc.union(bleedOf(e, globalLightAngle, shapeHeight)) }
        return RenderPlan(ordered, bleed, style)
    }

    private fun slotOf(effect: Effect): PassSlot = when (effect) {
        is Effect.BackdropBlur -> PassSlot.BACKDROP
        is Effect.DropShadow -> PassSlot.DROP_SHADOW
        is Effect.OuterGlow -> PassSlot.OUTER_GLOW
        is Effect.Extrude -> PassSlot.EXTRUDE
        is Effect.Reflection -> PassSlot.REFLECTION
        is Effect.Overlay -> PassSlot.OVERLAY
        is Effect.Satin -> PassSlot.SATIN
        is Effect.InnerGlow -> PassSlot.INNER_GLOW
        is Effect.InnerShadow -> PassSlot.INNER_SHADOW
        is Effect.Bevel -> PassSlot.BEVEL
        is Effect.Stroke -> PassSlot.STROKE
        is Effect.ChromaticOffset, is Effect.Noise, is Effect.EdgeRoughen -> PassSlot.POST
    }

    /** Blur kernels reach roughly three standard deviations; anything less visibly clips. */
    private const val BLUR_REACH = 3f

    private fun bleedOf(effect: Effect, globalLightAngle: Float, shapeHeight: Float): Bleed = when (effect) {
        is Effect.DropShadow -> Bleed.directional(
            offset = offsetOf(effect.distance, effect.angle, effect.useGlobalLight, globalLightAngle),
            grow = effect.spread + effect.blur * BLUR_REACH,
        )
        is Effect.OuterGlow -> Bleed.uniform(effect.spread + effect.blur * BLUR_REACH)
        is Effect.Stroke -> when (effect.position) {
            ir.pixellab.core.model.StrokePosition.OUTSIDE -> Bleed.uniform(effect.width)
            ir.pixellab.core.model.StrokePosition.CENTER -> Bleed.uniform(effect.width / 2f)
            ir.pixellab.core.model.StrokePosition.INSIDE -> Bleed.NONE
        }
        is Effect.Extrude -> {
            val total = Vec2(effect.stepOffset.x * effect.steps, effect.stepOffset.y * effect.steps)
            Bleed(
                left = max(0f, -total.x), top = max(0f, -total.y),
                right = max(0f, total.x), bottom = max(0f, total.y),
            )
        }
        is Effect.Reflection ->
            Bleed(0f, 0f, 0f, effect.gap + shapeHeight * effect.height + effect.blur * BLUR_REACH)
        is Effect.ChromaticOffset -> listOf(effect.redOffset, effect.greenOffset, effect.blueOffset)
            .fold(Bleed.NONE) { acc, o ->
                acc.union(Bleed(max(0f, -o.x), max(0f, -o.y), max(0f, o.x), max(0f, o.y)))
            }
        is Effect.EdgeRoughen -> Bleed.uniform(effect.amount)
        is Effect.Bevel -> if (effect.style == ir.pixellab.core.model.BevelStyle.OUTER_BEVEL) {
            Bleed.uniform(effect.size + effect.soften)
        } else {
            Bleed.NONE
        }
        // Inner effects, overlays and backdrop sampling stay within the shape.
        is Effect.InnerShadow, is Effect.InnerGlow, is Effect.Satin,
        is Effect.Overlay, is Effect.BackdropBlur, is Effect.Noise,
        -> Bleed.NONE
    }

    private fun offsetOf(distance: Float, angle: Float, useGlobal: Boolean, globalAngle: Float): Vec2 {
        val a = if (useGlobal) globalAngle else angle
        val dir = Vec2.fromAngle(a)
        // Screen y grows downwards while the light angle is measured mathematically, so y inverts.
        return Vec2(dir.x * distance, -dir.y * distance)
    }
}

data class RenderPlan(val passes: List<RenderPass>, val bleed: Bleed, val style: Style) {

    val passCount: Int get() = passes.size

    /** Passes that sample the already-composited destination and so cannot be cached per layer. */
    val readsBackdrop: Boolean
        get() = passes.any { it.slot == PassSlot.BACKDROP } ||
            style.fill is ir.pixellab.core.model.Fill.Backdrop

    /** Rough cost signal used to warn before a stack becomes a performance problem. */
    val estimatedCost: Int
        get() = passes.sumOf { pass ->
            when (val e = pass.effect) {
                is Effect.Extrude -> e.steps
                is Effect.DropShadow, is Effect.OuterGlow, is Effect.InnerGlow -> 4
                is Effect.BackdropBlur -> 6
                is Effect.Bevel -> 3
                else -> 1
            }
        }

    fun textureBounds(shapeBounds: Rect): Rect = bleed.expand(shapeBounds)

    /** True when the shape itself contributes nothing and only its effects are visible. */
    val fillIsInvisible: Boolean get() = style.fillOpacity <= 0f
}

/** Rounds a bleed up to whole pixels so texture allocation never truncates a fractional edge. */
fun Bleed.toPixels(scale: Float): Bleed = Bleed(
    left = ceilAbs(left * scale), top = ceilAbs(top * scale),
    right = ceilAbs(right * scale), bottom = ceilAbs(bottom * scale),
)

private fun ceilAbs(v: Float): Float = kotlin.math.ceil(abs(v))
