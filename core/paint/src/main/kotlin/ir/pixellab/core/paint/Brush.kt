package ir.pixellab.core.paint

import ir.pixellab.core.model.AssetId
import ir.pixellab.core.model.BlendMode
import ir.pixellab.core.model.Color
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The shape a single dab has.
 *
 * Two kinds, because they cover everything a design tool needs and nothing more. A [Round] tip is
 * computed, so it is exact at any size and costs nothing to store; a [Sampled] tip is an image, which
 * is the only way to get a real bristle, chalk or texture edge.
 */
@Serializable
sealed interface BrushTip {
    /**
     * A circle with a soft edge.
     *
     * [hardness] is Photoshop's: 1 is a hard edge, 0 fades from the very centre. It is not a blur
     * radius — the falloff is measured as a fraction of the radius, so a soft brush stays soft when
     * it is resized, which is the behaviour people rely on.
     */
    @Serializable @SerialName("round")
    data class Round(val hardness: Float = 0.8f) : BrushTip {
        init { require(hardness in 0f..1f) { "hardness is 0..1, got $hardness" } }
    }

    /** An image used as the dab. Greyscale is read as coverage; colour is ignored. */
    @Serializable @SerialName("sampled")
    data class Sampled(val asset: AssetId) : BrushTip
}

/**
 * What drives a dynamic value.
 *
 * Pressure and tilt come from the stylus, velocity from the stroke itself, and fade counts dabs from
 * the start. [NONE] leaves the value at its maximum, which is what makes a preset with no stylus
 * still usable with a finger — a brush that only works with a pen is a brush most people cannot use.
 */
@Serializable
enum class ControlSource { NONE, PRESSURE, VELOCITY, TILT, DIRECTION, FADE, RANDOM }

/**
 * One dynamic: how far a value may travel and what moves it.
 *
 * [minimum] is a *fraction* of the base value, matching Photoshop's "Minimum Diameter" and
 * "Minimum Roundness". Storing an absolute floor instead would mean a preset that behaves
 * differently at every brush size.
 */
@Serializable
data class Dynamic(
    val control: ControlSource = ControlSource.NONE,
    val minimum: Float = 0f,
    /** Random spread applied on top of the control, 0..1 of the full range. */
    val jitter: Float = 0f,
    /** Dabs the fade runs over, when [control] is [ControlSource.FADE]. */
    val fadeSteps: Int = 100,
    /** Reverses the control, so pressure can make something smaller rather than larger. */
    val inverted: Boolean = false,
) {
    val isActive: Boolean get() = control != ControlSource.NONE || jitter > 0f

    companion object {
        val NONE = Dynamic()
        val PRESSURE = Dynamic(control = ControlSource.PRESSURE)
    }
}

/**
 * Everything about how a brush lays down dabs.
 *
 * Modelled on Photoshop's brush panel because that is the vocabulary the user already has, and
 * because each group there corresponds to something a real medium does: shape dynamics is how a
 * nib responds to pressure, scattering is how a spray disperses, transfer is how ink loads.
 */
@Serializable
data class BrushPreset(
    val name: String = "قلم",
    val tip: BrushTip = BrushTip.Round(),

    /** Diameter in canvas units. */
    val size: Float = 40f,

    /**
     * Distance between dabs as a fraction of the diameter.
     *
     * Below about 0.05 the dabs pile up and a soft brush turns opaque; above 1 the stroke visibly
     * becomes a row of dots. Photoshop's default of 0.25 is the right default here too.
     */
    val spacing: Float = 0.25f,

    /** Tip rotation in degrees, before any direction control. */
    val angle: Float = 0f,

    /** 1 is circular; below that the tip is squashed along its angle, which is how a nib behaves. */
    val roundness: Float = 1f,

    // ---- shape dynamics ----
    val sizeDynamic: Dynamic = Dynamic.PRESSURE,
    val angleDynamic: Dynamic = Dynamic.NONE,
    val roundnessDynamic: Dynamic = Dynamic.NONE,

    // ---- scattering ----
    /** Sideways spread as a fraction of the diameter. */
    val scatter: Float = 0f,
    /** True spreads along the stroke as well as across it. */
    val scatterBothAxes: Boolean = false,
    /** Dabs laid down at each step. */
    val count: Int = 1,

    // ---- transfer ----
    /**
     * Per-dab alpha.
     *
     * The distinction from [opacity] is the one that matters and the one most implementations
     * miss: flow is how much ink each dab carries, and dabs accumulate, so a low flow builds up as
     * the brush passes over the same place. Opacity is a ceiling on the finished stroke, so a
     * stroke at 50% never gets darker than 50% however many times it crosses itself.
     */
    val flow: Float = 1f,
    val opacity: Float = 1f,
    val flowDynamic: Dynamic = Dynamic.NONE,
    val opacityDynamic: Dynamic = Dynamic.NONE,

    // ---- colour dynamics ----
    val color: Color = Color.BLACK,
    val secondaryColor: Color = Color.WHITE,
    /** Chance per dab of taking the secondary colour instead, 0..1. */
    val colorJitter: Float = 0f,
    val hueJitter: Float = 0f,
    val saturationJitter: Float = 0f,
    val brightnessJitter: Float = 0f,

    // ---- stroke ----
    /**
     * How much the stroke is pulled behind the finger, 0..1.
     *
     * A finger on glass is far shakier than a pen on paper, and every mobile drawing app that feels
     * good has this. It is not a cosmetic filter: the smoothing happens before dabs are placed, so
     * it changes the path rather than blurring the result.
     */
    val smoothing: Float = 0.35f,

    /** Thins the stroke towards its ends, as a fraction of its length. */
    val taperStart: Float = 0f,
    val taperEnd: Float = 0f,

    val blendMode: BlendMode = BlendMode.NORMAL,

    /** Removes rather than adds, which is the same engine with the coverage subtracted. */
    val erase: Boolean = false,
) {
    init {
        require(size > 0f) { "a brush needs a positive size, got $size" }
        require(spacing > 0f) { "spacing must be positive, got $spacing" }
        require(count >= 1) { "a brush lays down at least one dab, got $count" }
    }

    /** Distance between dabs in canvas units. */
    val stepDistance: Float get() = (size * spacing).coerceAtLeast(MIN_STEP)

    companion object {
        /**
         * Below this the planner would emit thousands of dabs for a short stroke.
         *
         * A quarter of a canvas unit is already finer than any display can show, so the cap costs
         * nothing visible and bounds the work.
         */
        const val MIN_STEP = 0.25f

        /** A hard round brush: the one people reach for to fix a mask edge. */
        val HARD = BrushPreset(name = "سخت", tip = BrushTip.Round(hardness = 1f), spacing = 0.1f)

        /** A soft round brush: shading, and every mask that has to blend. */
        val SOFT = BrushPreset(name = "نرم", tip = BrushTip.Round(hardness = 0.1f), size = 80f)

        /** An airbrush: low flow, tight spacing, so passing over a place twice darkens it. */
        val AIRBRUSH = BrushPreset(
            name = "ایربراش",
            tip = BrushTip.Round(hardness = 0f),
            size = 120f,
            spacing = 0.05f,
            flow = 0.06f,
            flowDynamic = Dynamic.PRESSURE,
        )

        /** A marker: opaque, slightly oval, angled — the shape a chisel nib makes. */
        val MARKER = BrushPreset(
            name = "ماژیک",
            tip = BrushTip.Round(hardness = 0.9f),
            size = 60f,
            roundness = 0.35f,
            angle = 45f,
            spacing = 0.05f,
            sizeDynamic = Dynamic.NONE,
        )

        /** A spray: scattered, many dabs a step, colour varying — texture rather than a line. */
        val SPRAY = BrushPreset(
            name = "اسپری",
            tip = BrushTip.Round(hardness = 0f),
            size = 90f,
            spacing = 0.1f,
            scatter = 1.2f,
            scatterBothAxes = true,
            count = 5,
            flow = 0.2f,
            sizeDynamic = Dynamic(control = ControlSource.RANDOM, minimum = 0.3f),
        )

        val ERASER = BrushPreset(name = "پاک‌کن", tip = BrushTip.Round(hardness = 0.9f), erase = true)

        val ALL = listOf(HARD, SOFT, AIRBRUSH, MARKER, SPRAY, ERASER)
    }
}
