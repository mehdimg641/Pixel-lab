package ir.pixellab.engine.android

import android.graphics.Path
import android.graphics.PathMeasure
import ir.pixellab.core.model.Contour
import ir.pixellab.core.model.PathNode
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.Vec2
import ir.pixellab.core.vector.PathMath

/** Illustrator's Pathfinder panel. */
enum class PathOperation {
    /** Everything either shape covers. */
    UNITE,

    /** The first shape with the rest cut out of it. */
    MINUS_FRONT,

    /** Only what every shape covers. */
    INTERSECT,

    /** Everything except the overlap — an odd number of shapes deep. */
    EXCLUDE,
}

/**
 * Boolean operations on paths.
 *
 * Delegated to Skia's path ops rather than implemented here, and that is a considered choice rather
 * than a shortcut. A correct boolean operation on Bézier curves needs exact curve-curve intersection,
 * a sweep line that survives tangencies, and a winding resolution that does not fall apart on
 * self-intersecting input — and self-intersecting input is normal, because it is what an outlined
 * stroke of a curve that doubles back produces. Skia's implementation is the same one Chrome and
 * Flutter ship; a hand-written Bentley–Ottmann would be worse in exactly the cases that matter.
 *
 * The results come back as polylines. Skia's op works on the flattened representation internally,
 * so the curves are already gone by then; re-fitting Béziers to the output would invent control
 * points that were never in the input and is a separate piece of work from the operation itself.
 */
object Pathfinder {

    /**
     * Applies an operation across a list of shapes, front to back.
     *
     * Front to back matters for [PathOperation.MINUS_FRONT]: Illustrator subtracts everything above
     * from the bottom shape, so the order the layers are stacked in decides the result.
     */
    fun apply(shapes: List<ShapeGeometry.Path>, operation: PathOperation): ShapeGeometry.Path {
        if (shapes.isEmpty()) return ShapeGeometry.Path(emptyList())
        if (shapes.size == 1) return shapes.first()

        val op = when (operation) {
            PathOperation.UNITE -> Path.Op.UNION
            PathOperation.MINUS_FRONT -> Path.Op.DIFFERENCE
            PathOperation.INTERSECT -> Path.Op.INTERSECT
            PathOperation.EXCLUDE -> Path.Op.XOR
        }

        var result = shapes.first().toAndroidPath()
        for (shape in shapes.drop(1)) {
            val next = Path()
            // The three-argument form writes into a fresh path rather than mutating the receiver,
            // which is what makes the loop safe when a shape appears in the list twice.
            if (!next.op(result, shape.toAndroidPath(), op)) return ShapeGeometry.Path(emptyList())
            result = next
        }
        return result.toModelPath()
    }

    /**
     * Illustrator's Divide: every region the shapes cut each other into, as its own path.
     *
     * Built from the pairwise intersections and differences rather than from a planar subdivision.
     * A full arrangement would give the same regions and needs the very intersection machinery this
     * object exists to avoid writing.
     */
    fun divide(shapes: List<ShapeGeometry.Path>): List<ShapeGeometry.Path> {
        if (shapes.size < 2) return shapes
        val regions = ArrayList<ShapeGeometry.Path>()

        for ((i, shape) in shapes.withIndex()) {
            var remainder = shape.toAndroidPath()
            for ((j, other) in shapes.withIndex()) {
                if (i == j) continue
                val overlap = Path()
                if (!overlap.op(shape.toAndroidPath(), other.toAndroidPath(), Path.Op.INTERSECT)) continue
                if (overlap.isEmpty) continue
                // Each overlap is emitted once, by the lower-indexed shape, or every shared region
                // would appear twice and stack up in the layer panel.
                if (i < j) regions += overlap.toModelPath()

                val cut = Path()
                if (cut.op(remainder, other.toAndroidPath(), Path.Op.DIFFERENCE)) remainder = cut
            }
            if (!remainder.isEmpty) regions += remainder.toModelPath()
        }
        return regions.filter { it.contours.isNotEmpty() }
    }

    /** Illustrator's Outline: the shapes' edges as open paths, with their interiors discarded. */
    fun outline(shapes: List<ShapeGeometry.Path>): ShapeGeometry.Path =
        ShapeGeometry.Path(
            shapes.flatMap { shape -> shape.contours.map { it.copy(closed = false) } },
        )

    /**
     * Offsets a path outwards or inwards.
     *
     * Illustrator's Offset Path. Done by stroking at twice the distance and taking the union or the
     * difference, which is the standard construction and avoids the self-intersection an analytic
     * offset produces at every concave corner.
     */
    fun offset(path: ShapeGeometry.Path, distance: Float): ShapeGeometry.Path {
        if (distance == 0f) return path
        val source = path.toAndroidPath()
        val stroked = Path()
        android.graphics.Paint().apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = kotlin.math.abs(distance) * 2f
            strokeJoin = android.graphics.Paint.Join.ROUND
            strokeCap = android.graphics.Paint.Cap.ROUND
        }.getFillPath(source, stroked)

        val result = Path()
        val op = if (distance > 0f) Path.Op.UNION else Path.Op.DIFFERENCE
        if (!result.op(source, stroked, op)) return path
        return result.toModelPath()
    }
}

/** Converts a model path into the platform's, so Skia can operate on it. */
fun ShapeGeometry.Path.toAndroidPath(): Path {
    val out = Path()
    for (contour in contours) {
        val nodes = contour.nodes
        if (nodes.isEmpty()) continue
        out.moveTo(nodes.first().point.x, nodes.first().point.y)
        for ((from, to) in PathMath.segments(contour)) {
            out.cubicTo(
                from.controlOut.x, from.controlOut.y,
                to.controlIn.x, to.controlIn.y,
                to.point.x, to.point.y,
            )
        }
        if (contour.closed) out.close()
    }
    return out
}

/**
 * Reads a platform path back into the model.
 *
 * Sampled rather than read segment by segment: `PathIterator` only arrived in API 34, and this app
 * runs from 26. `PathMeasure` is available everywhere and gives the same geometry to within the
 * sampling distance, which is finer than a pixel at any zoom a phone can show.
 */
fun Path.toModelPath(step: Float = SAMPLE_STEP): ShapeGeometry.Path {
    val contours = ArrayList<Contour>()
    val measure = PathMeasure(this, false)
    val position = FloatArray(2)

    do {
        val length = measure.length
        if (length <= PathMath.EPSILON) continue
        val steps = (length / step).toInt().coerceIn(2, MAX_SAMPLES)
        val nodes = ArrayList<PathNode>(steps + 1)
        for (i in 0..steps) {
            measure.getPosTan(length * i / steps, position, null)
            nodes += PathNode(Vec2(position[0], position[1]))
        }
        // The last sample of a closed contour lands back on the first; keeping it gives a
        // zero-length segment, which shows as a blob at the join on any stroked path.
        val closed = measure.isClosed
        contours += Contour(if (closed) nodes.dropLast(1) else nodes, closed = closed)
    } while (measure.nextContour())

    return ShapeGeometry.Path(contours)
}

/** Half a pixel at 1:1, which is finer than any phone display resolves. */
private const val SAMPLE_STEP = 0.5f

/** A guard against a pathological path with an enormous perimeter. */
private const val MAX_SAMPLES = 4096
