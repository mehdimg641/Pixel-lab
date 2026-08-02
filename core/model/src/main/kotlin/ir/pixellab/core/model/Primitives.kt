package ir.pixellab.core.model

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.math.cos
import kotlin.math.sin

/**
 * Stable identifiers. Every id is a plain string so a document survives a round trip through
 * JSON without the model depending on a UUID implementation.
 */
@JvmInline @Serializable value class LayerId(val value: String)

@JvmInline @Serializable value class AssetId(val value: String)

@JvmInline @Serializable value class DocumentId(val value: String)

@Serializable
data class Vec2(val x: Float = 0f, val y: Float = 0f) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Float) = Vec2(x * s, y * s)
    operator fun div(s: Float) = Vec2(x / s, y / s)

    /** Component-wise, for applying a [Transform]'s two-axis scale. */
    operator fun times(o: Vec2) = Vec2(x * o.x, y * o.y)

    val length: Float get() = kotlin.math.hypot(x, y)

    fun dot(o: Vec2): Float = x * o.x + y * o.y

    /**
     * Rotates clockwise by [degrees].
     *
     * Screen and canvas coordinates both grow downwards, so this is the ordinary rotation matrix —
     * which *looks* clockwise under a downward y axis. Every rotation in the editor uses this
     * convention, including [Transform.rotation].
     */
    fun rotated(degrees: Float): Vec2 {
        if (degrees == 0f) return this
        val r = Math.toRadians(degrees.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        return Vec2(x * c - y * s, x * s + y * c)
    }

    /** Angle to the positive x axis in degrees, clockwise, in -180..180. */
    val angle: Float get() = Math.toDegrees(kotlin.math.atan2(y.toDouble(), x.toDouble())).toFloat()

    companion object {
        val ZERO = Vec2(0f, 0f)
        val ONE = Vec2(1f, 1f)

        /** Unit vector for [degrees], measured clockwise from the positive x axis. */
        fun fromAngle(degrees: Float): Vec2 {
            val r = Math.toRadians(degrees.toDouble())
            return Vec2(cos(r).toFloat(), sin(r).toFloat())
        }
    }
}

@Serializable
data class Vec3(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f) {
    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
    }
}

@Serializable
data class Rect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun inflate(by: Float) = Rect(left - by, top - by, right + by, bottom + by)

    fun union(o: Rect) = Rect(
        minOf(left, o.left),
        minOf(top, o.top),
        maxOf(right, o.right),
        maxOf(bottom, o.bottom),
    )

    companion object {
        val EMPTY = Rect(0f, 0f, 0f, 0f)

        fun of(size: Vec2) = Rect(0f, 0f, size.x, size.y)
    }
}

/**
 * Affine placement of a layer on the canvas, kept as separable components rather than a matrix.
 *
 * Storing translation/scale/rotation/skew separately is what lets the UI show a rotation value the
 * user set, round-trip it through save/load, and animate a single component. A collapsed 3x3 matrix
 * cannot be decomposed back without ambiguity.
 */
@Serializable
data class Transform(
    val translation: Vec2 = Vec2.ZERO,
    val scale: Vec2 = Vec2.ONE,
    /** Degrees, clockwise. */
    val rotation: Float = 0f,
    /** Degrees of horizontal and vertical shear. */
    val skew: Vec2 = Vec2.ZERO,
    /** Normalised anchor within the layer bounds; (0.5, 0.5) is the centre. */
    val anchor: Vec2 = Vec2(0.5f, 0.5f),
    /**
     * Free-form four-corner warp applied after the affine part. Non-null only when the user has
     * dragged corners, so the common case stays a cheap affine transform.
     */
    val perspective: Perspective? = null,
) {
    val isIdentity: Boolean
        get() = translation == Vec2.ZERO && scale == Vec2.ONE && rotation == 0f &&
            skew == Vec2.ZERO && perspective == null

    companion object {
        val IDENTITY = Transform()
    }
}

/** Destination of the layer's four corners, in canvas space. */
@Serializable
data class Perspective(
    val topLeft: Vec2,
    val topRight: Vec2,
    val bottomRight: Vec2,
    val bottomLeft: Vec2,
)

/**
 * A monotonic 0..1 -> 0..1 response curve, used for bevel contours, gradient interpolation and
 * glow falloff. Photoshop calls this a "contour"; every effect that shapes a ramp reuses it.
 */
@Serializable
data class Curve(val points: List<Vec2> = LINEAR_POINTS) {
    init {
        require(points.size >= 2) { "a curve needs at least two points, got ${points.size}" }
    }

    /** Piecewise-linear evaluation. Callers that need smoothing interpolate at a finer step. */
    fun evaluate(t: Float): Float {
        val clamped = t.coerceIn(0f, 1f)
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            if (clamped in a.x..b.x) {
                val span = b.x - a.x
                if (span <= 0f) return b.y
                return a.y + (b.y - a.y) * ((clamped - a.x) / span)
            }
        }
        return points.last().y
    }

    companion object {
        private val LINEAR_POINTS = listOf(Vec2(0f, 0f), Vec2(1f, 1f))
        val LINEAR = Curve(LINEAR_POINTS)
        val EASE_IN_OUT = Curve(listOf(Vec2(0f, 0f), Vec2(0.35f, 0.08f), Vec2(0.65f, 0.92f), Vec2(1f, 1f)))
        /** Rounded bevel shoulder, the profile behind the "puffy" style. */
        val ROUNDED = Curve(listOf(Vec2(0f, 0f), Vec2(0.3f, 0.72f), Vec2(0.6f, 0.94f), Vec2(1f, 1f)))
        /** Hard chamfer, the profile behind the faceted esports look. */
        val CHAMFER = Curve(listOf(Vec2(0f, 0f), Vec2(0.5f, 0.5f), Vec2(1f, 1f)))
    }
}
