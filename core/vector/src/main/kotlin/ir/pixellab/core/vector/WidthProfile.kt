package ir.pixellab.core.vector

import ir.pixellab.core.model.Contour
import ir.pixellab.core.model.PathNode
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.Vec2
import kotlin.math.hypot
import kotlinx.serialization.Serializable

/** How wide a stroke is at one place along it. */
@Serializable
data class WidthStop(
    /** Position along the path, 0 at the start and 1 at the end. */
    val at: Float,
    /** Multiplier on the stroke's nominal width, out to each side. */
    val left: Float = 1f,
    val right: Float = 1f,
)

/**
 * A stroke whose width varies along its length.
 *
 * Illustrator's width tool, and the thing that separates a drawn line from a traced one: a
 * calligraphic stroke, a tapered leaf, a ribbon. A uniform stroke is the special case where the
 * profile has two stops at 1.
 *
 * The two sides are independent so a profile can be asymmetric, which is what makes a stroke read as
 * having been drawn with a nib held at an angle rather than as having been thickened.
 */
@Serializable
data class WidthProfile(val stops: List<WidthStop> = DEFAULT) {

    init {
        require(stops.size >= 2) { "a profile needs at least two stops, got ${stops.size}" }
    }

    fun widthAt(t: Float): Pair<Float, Float> {
        val position = t.coerceIn(0f, 1f)
        val sorted = stops.sortedBy { it.at }
        if (position <= sorted.first().at) return sorted.first().left to sorted.first().right
        if (position >= sorted.last().at) return sorted.last().left to sorted.last().right

        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            if (position in a.at..b.at) {
                val span = (b.at - a.at).coerceAtLeast(PathMath.EPSILON)
                val f = (position - a.at) / span
                return (a.left + (b.left - a.left) * f) to (a.right + (b.right - a.right) * f)
            }
        }
        return sorted.last().left to sorted.last().right
    }

    companion object {
        val DEFAULT = listOf(WidthStop(0f), WidthStop(1f))

        val UNIFORM = WidthProfile()

        /** Thin at both ends: a drawn line, and the profile people reach for first. */
        val TAPERED = WidthProfile(
            listOf(WidthStop(0f, 0.02f, 0.02f), WidthStop(0.5f, 1f, 1f), WidthStop(1f, 0.02f, 0.02f)),
        )

        /** Thin to thick: a stroke that reads as accelerating. */
        val ENTRY = WidthProfile(listOf(WidthStop(0f, 0.05f, 0.05f), WidthStop(1f, 1f, 1f)))

        /** A nib held at an angle — one side fat, the other thin. */
        val CALLIGRAPHIC = WidthProfile(
            listOf(WidthStop(0f, 1f, 0.15f), WidthStop(1f, 1f, 0.15f)),
        )

        val ALL = listOf("یکنواخت" to UNIFORM, "باریک‌شونده" to TAPERED, "ورودی" to ENTRY, "خوش‌نویسی" to CALLIGRAPHIC)
    }
}

/**
 * Turns a stroked path into a filled one.
 *
 * The reason this exists rather than a stroke parameter on the renderer: a variable-width stroke is
 * not something a rasteriser can express — every 2D API strokes at one width. Building the outline
 * makes it an ordinary fill, which then takes gradients, effects and boolean operations like any
 * other shape. That is exactly what Illustrator's "Outline Stroke" does, and it is why the width
 * tool composes with everything else there.
 */
object StrokeOutliner {

    /**
     * @param width the nominal stroke width; the profile multiplies it
     * @param steps how finely the path is sampled. The outline is a polygon, and too few steps show
     *   as facets on the very curves the width tool is used for.
     */
    fun outline(
        contour: Contour,
        width: Float,
        profile: WidthProfile = WidthProfile.UNIFORM,
        steps: Int = DEFAULT_STEPS,
    ): ShapeGeometry.Path {
        val samples = sample(contour, steps)
        if (samples.size < 2) return ShapeGeometry.Path(emptyList())

        val half = width / 2f
        val left = ArrayList<Vec2>(samples.size)
        val right = ArrayList<Vec2>(samples.size)

        for (sample in samples) {
            val (leftScale, rightScale) = profile.widthAt(sample.t)
            // The normal is the tangent turned a quarter turn. Offsetting along the tangent instead
            // is the classic sign error and produces a stroke that runs along the path rather than
            // around it.
            val nx = -sample.tangent.y
            val ny = sample.tangent.x
            left += Vec2(sample.point.x + nx * half * leftScale, sample.point.y + ny * half * leftScale)
            right += Vec2(sample.point.x - nx * half * rightScale, sample.point.y - ny * half * rightScale)
        }

        val ring = if (contour.closed) {
            // A closed path gives two rings, not one: the outside and the hole. Joining them into a
            // single loop would fill the middle in.
            return ShapeGeometry.Path(
                listOf(
                    Contour(left.map { PathNode(it) }, closed = true),
                    Contour(right.reversed().map { PathNode(it) }, closed = true),
                ),
            )
        } else {
            left + right.reversed()
        }
        return ShapeGeometry.Path(listOf(Contour(ring.map { PathNode(it) }, closed = true)))
    }

    fun outline(
        path: ShapeGeometry.Path,
        width: Float,
        profile: WidthProfile = WidthProfile.UNIFORM,
        steps: Int = DEFAULT_STEPS,
    ): ShapeGeometry.Path = ShapeGeometry.Path(
        path.contours.flatMap { outline(it, width, profile, steps).contours },
    )

    /** One place along the path: where it is, which way it points, and how far along it sits. */
    private data class Sample(val point: Vec2, val tangent: Vec2, val t: Float)

    /**
     * Samples the path at even *distances*, not at even parameter values.
     *
     * A Bézier's parameter is not its arc length — a curve with one long handle travels far faster
     * at one end than the other. Sampling by parameter puts the profile's midpoint somewhere that is
     * not the middle of the line, which is immediately visible on a tapered stroke.
     */
    private fun sample(contour: Contour, steps: Int): List<Sample> {
        val raw = ArrayList<Sample>()
        for ((from, to) in PathMath.segments(contour)) {
            for (i in 0..steps) {
                val t = i.toFloat() / steps
                raw += Sample(PathMath.evaluate(from, to, t), PathMath.tangent(from, to, t), 0f)
            }
        }
        if (raw.size < 2) return raw

        val lengths = FloatArray(raw.size)
        for (i in 1 until raw.size) {
            lengths[i] = lengths[i - 1] + hypot(
                raw[i].point.x - raw[i - 1].point.x,
                raw[i].point.y - raw[i - 1].point.y,
            )
        }
        val total = lengths.last().coerceAtLeast(PathMath.EPSILON)
        return raw.mapIndexed { i, sample -> sample.copy(t = lengths[i] / total) }
    }

    /** Fine enough that a tapered curve has no visible facets at a phone's pixel density. */
    const val DEFAULT_STEPS = 24
}
