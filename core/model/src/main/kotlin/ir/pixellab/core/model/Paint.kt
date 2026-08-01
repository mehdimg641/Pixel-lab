package ir.pixellab.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Straight (non-premultiplied) sRGB colour with components in 0..1. */
@Serializable
data class Color(val r: Float, val g: Float, val b: Float, val a: Float = 1f) {
    fun withAlpha(alpha: Float) = copy(a = alpha.coerceIn(0f, 1f))

    companion object {
        val TRANSPARENT = Color(0f, 0f, 0f, 0f)
        val BLACK = Color(0f, 0f, 0f)
        val WHITE = Color(1f, 1f, 1f)

        /** Parses `#RGB`, `#RRGGBB` or `#AARRGGBB`. */
        fun parse(hex: String): Color {
            val s = hex.removePrefix("#")
            fun byte(i: Int) = s.substring(i, i + 2).toInt(16) / 255f
            return when (s.length) {
                3 -> Color(
                    s.substring(0, 1).repeat(2).toInt(16) / 255f,
                    s.substring(1, 2).repeat(2).toInt(16) / 255f,
                    s.substring(2, 3).repeat(2).toInt(16) / 255f,
                )
                6 -> Color(byte(0), byte(2), byte(4))
                8 -> Color(byte(2), byte(4), byte(6), byte(0))
                else -> throw IllegalArgumentException("unsupported colour literal: $hex")
            }
        }
    }
}

@Serializable
data class GradientStop(
    /** Position along the ramp, 0..1. */
    val position: Float,
    val color: Color,
    /**
     * Midpoint bias towards the next stop, 0..1, default 0.5. Photoshop exposes this as the small
     * diamond between stops; without it, imported gradients do not match.
     */
    val midpoint: Float = 0.5f,
)

@Serializable
enum class GradientType { LINEAR, RADIAL, ANGULAR, REFLECTED, DIAMOND }

@Serializable
enum class TileMode { CLAMP, REPEAT, MIRROR }

/**
 * How a shape's interior is painted.
 *
 * [Backdrop] is the odd one out: it samples the already-composited pixels underneath the layer
 * instead of producing its own colour. It is what makes text read as carved into its background.
 */
@Serializable
sealed interface Fill {
    @Serializable @SerialName("solid")
    data class Solid(val color: Color) : Fill

    @Serializable @SerialName("gradient")
    data class Gradient(
        val type: GradientType = GradientType.LINEAR,
        val stops: List<GradientStop>,
        /** Degrees, clockwise from the positive x axis. */
        val angle: Float = 0f,
        val scale: Float = 1f,
        /** Centre offset in normalised layer space; (0,0) is the layer centre. */
        val offset: Vec2 = Vec2.ZERO,
        val reverse: Boolean = false,
        /** Applied to the ramp parameter before sampling; shapes the falloff. */
        val interpolation: Curve = Curve.LINEAR,
        /** Small amount of ordered noise, 0..1, to break up banding on large ramps. */
        val dither: Float = 0f,
    ) : Fill {
        init {
            require(stops.size >= 2) { "a gradient needs at least two stops, got ${stops.size}" }
        }
    }

    @Serializable @SerialName("pattern")
    data class Pattern(
        val asset: AssetId,
        val scale: Vec2 = Vec2.ONE,
        val offset: Vec2 = Vec2.ZERO,
        val rotation: Float = 0f,
        val tileMode: TileMode = TileMode.REPEAT,
        /** Links the pattern to the canvas instead of the layer, so it stays put when the layer moves. */
        val linkToCanvas: Boolean = false,
    ) : Fill

    @Serializable @SerialName("backdrop")
    data class Backdrop(
        val blurRadius: Float = 0f,
        /** 1.0 keeps backdrop saturation; below 1 desaturates, above 1 intensifies. */
        val saturation: Float = 1f,
        val brightness: Float = 1f,
        val tint: Color = Color.TRANSPARENT,
    ) : Fill
}

/**
 * Photoshop's full blend set. The renderer implements each as a shader branch; the enum order is
 * the order Photoshop lists them so imported documents and the UI agree.
 */
@Serializable
enum class BlendMode {
    NORMAL, DISSOLVE,
    DARKEN, MULTIPLY, COLOR_BURN, LINEAR_BURN, DARKER_COLOR,
    LIGHTEN, SCREEN, COLOR_DODGE, LINEAR_DODGE, LIGHTER_COLOR,
    OVERLAY, SOFT_LIGHT, HARD_LIGHT, VIVID_LIGHT, LINEAR_LIGHT, PIN_LIGHT, HARD_MIX,
    DIFFERENCE, EXCLUSION, SUBTRACT, DIVIDE,
    HUE, SATURATION, COLOR, LUMINOSITY,
    ;

    /** True for the four modes that operate on HSL components rather than per channel. */
    val isNonSeparable: Boolean
        get() = this == HUE || this == SATURATION || this == COLOR || this == LUMINOSITY
}
