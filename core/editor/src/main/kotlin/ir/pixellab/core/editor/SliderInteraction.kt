package ir.pixellab.core.editor

import ir.pixellab.core.render.ParameterSpec
import kotlin.math.abs
import kotlin.math.pow

/**
 * Maps a fraction of the track to a value.
 *
 * [ParameterSpec.Slider.skew] bends the response so the useful part of a range gets most of the
 * travel. Blur runs to 500 px and is almost always under 40; a linear track would put every value
 * anyone uses in its first eighth.
 */
fun ParameterSpec.Slider.valueAt(fraction: Float): Float {
    val t = fraction.coerceIn(0f, 1f)
    val shaped = if (skew == 1f) t else t.toDouble().pow(skew.toDouble()).toFloat()
    return range.start + (range.endInclusive - range.start) * shaped
}

/** The inverse of [valueAt], for placing the knob. */
fun ParameterSpec.Slider.fractionOf(value: Float): Float {
    val span = range.endInclusive - range.start
    if (span <= 0f) return 0f
    val t = ((value - range.start) / span).coerceIn(0f, 1f)
    return if (skew == 1f) t else t.toDouble().pow(1.0 / skew.toDouble()).toFloat()
}

/**
 * One slider drag.
 *
 * Three behaviours are non-negotiable and all three are here rather than in the view, because they
 * are what separates a slider you can explore with from one you can work with:
 *
 * - **Precision falls off with distance from the track.** Dragging away from the slider magnifies
 *   the range the finger covers, so a 0–500 blur can still be set to 37. A phone slider is about
 *   300 pixels wide; without this, one pixel of finger movement is nearly two units of blur and
 *   exact values are unreachable.
 * - **A double tap restores the default**, which is the only cheap way back after exploring.
 * - **The whole drag is one undo step.** [Editor.setEffectParameter] takes `continuous` for this;
 *   without it a one-second scrub buries everything before it under several hundred entries.
 *
 * ### Why this accumulates instead of measuring from the anchor
 *
 * It used to compute the value from the finger's *total* displacement since the press, multiplied
 * by the gain at wherever the finger currently was. That is subtly wrong and it feels wrong: moving
 * down mid-drag re-scaled the whole journey retroactively, so the value slid **backwards** towards
 * where it started while the finger was still going forwards. Gain belongs to the step, not to the
 * history — leaving the slider anywhere the user let go of it, and slowing rather than reversing
 * when they reach for precision.
 *
 * The state that makes that possible is the reason this is a class with a cursor in it rather than
 * a pure function of the finger's position. One drag, one instance.
 */
class SliderDrag(
    val spec: ParameterSpec.Slider,
    /** Where the value was when the finger went down. */
    startValue: Float,
    startScreen: Pair<Float, Float>,
    val trackWidth: Float,
    /** Screen y of the track itself, from which vertical distance is measured. */
    val trackY: Float,
    /** True for a right-to-left interface, where dragging left must raise the value. */
    val rightToLeft: Boolean = false,
    /**
     * Vertical travel, **in pixels**, before precision engages — and the caller converts it.
     *
     * These were constants in this file and they were in raw pixels, which made the feature's onset
     * depend on the screen. Half of a 48dp touch strip is 72px on a three-times phone, so a finger
     * that never even left the control was already down to 0.71 gain and falling; on a one-times
     * screen the same gesture stayed at 1.0. The precision behaviour was effectively always on for
     * anybody with a modern display, which is most of the reason the sliders felt like glue.
     */
    val deadZone: Float,
    val falloff: Float,
) {
    /** Where the finger was last seen, so each event contributes only its own step. */
    private var lastX = startScreen.first

    /** The position along the track, kept as a fraction so the skew is applied once, at the end. */
    private var fraction = spec.fractionOf(startValue)

    /** What the last step was scaled by, for the readout that shows precision is engaged. */
    var gain: Float = 1f
        private set

    /** Advances the drag to a finger now at [x], [y], and returns the value it lands on. */
    fun advance(x: Float, y: Float): Float {
        if (trackWidth <= 0f) return spec.valueAt(fraction)
        val step = (x - lastX) * (if (rightToLeft) -1f else 1f)
        lastX = x
        gain = gainAt(y)
        // Clamped here rather than at the ends: without it, dragging past the end banks up travel
        // that has to be paid back before the value moves again, which reads as the slider sticking.
        fraction = (fraction + step / trackWidth * gain).coerceIn(0f, 1f)
        return spec.valueAt(fraction)
    }

    /** 1 on the track, approaching [MINIMUM_GAIN] far from it. */
    fun gainAt(y: Float): Float {
        val distance = abs(y - trackY)
        if (distance <= deadZone) return 1f
        val gain = 1f / (1f + (distance - deadZone) / falloff)
        return gain.coerceAtLeast(MINIMUM_GAIN)
    }

    companion object {
        /** Fingers wander; below this the drag is still full speed. In **dp**. */
        const val DEAD_ZONE_DP = 24f

        /** Vertical travel that halves the sensitivity. In **dp**. */
        const val FALLOFF_DP = 120f

        /** Even at arm's length the slider still moves, or it reads as broken. */
        const val MINIMUM_GAIN = 0.02f
    }
}

/**
 * A numeric entry parsed from the keyboard the long press opens.
 *
 * Persian digits are accepted because the interface shows them, and a value outside the slider's
 * range is clamped rather than rejected — a typed 3200% on a 0–1000 depth means "as far as it goes",
 * not a validation error to argue with.
 */
fun ParameterSpec.Slider.parseEntry(text: String): Float? {
    val latin = text.map { ch ->
        when (ch) {
            in '۰'..'۹' -> '0' + (ch - '۰')
            in '٠'..'٩' -> '0' + (ch - '٠')
            '٫', '،' -> '.'
            else -> ch
        }
    }.joinToString("").trim().removeSuffix("%").trim()

    val parsed = latin.toFloatOrNull() ?: return null
    // A percentage control is stored 0..1 but shown 0..100, so a typed 75 means 0.75.
    val scaled = if (unit == ParameterSpec.Slider.Unit.PERCENT && range.endInclusive <= 1f) {
        parsed / 100f
    } else {
        parsed
    }
    return scaled.coerceIn(range.start, range.endInclusive)
}

/** How a value is shown next to its label. */
fun ParameterSpec.Slider.format(value: Float): String = when (unit) {
    ParameterSpec.Slider.Unit.PERCENT ->
        "${((if (range.endInclusive <= 1f) value * 100f else value)).roundedString()}٪"
    ParameterSpec.Slider.Unit.DEGREES -> "${value.roundedString()}°"
    ParameterSpec.Slider.Unit.PIXELS -> value.roundedString()
    ParameterSpec.Slider.Unit.NONE -> value.roundedString()
}

/** Whole numbers show without a decimal point; anything finer keeps one place. */
private fun Float.roundedString(): String {
    val rounded = kotlin.math.round(this * 10f) / 10f
    return if (rounded == kotlin.math.truncate(rounded)) rounded.toInt().toString() else rounded.toString()
}
