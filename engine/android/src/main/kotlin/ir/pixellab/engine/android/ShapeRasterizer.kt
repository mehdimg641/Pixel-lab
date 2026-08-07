package ir.pixellab.engine.android

import android.graphics.Path
import android.graphics.RectF
import ir.pixellab.core.model.Corners
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.Vec2
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Turns a [ShapeGeometry] into an outline.
 *
 * A path rather than a bitmap, so the same result feeds the silhouette texture, the vector mask and
 * an SVG export without being rasterised three different ways at three different qualities.
 */
object ShapeRasterizer {

    fun path(geometry: ShapeGeometry): Path = when (geometry) {
        is ShapeGeometry.Rectangle -> rectangle(geometry)
        is ShapeGeometry.Ellipse -> Path().apply {
            addOval(RectF(0f, 0f, geometry.size.x, geometry.size.y), Path.Direction.CW)
        }
        is ShapeGeometry.Polygon -> polygon(geometry)
        is ShapeGeometry.Star -> star(geometry)
        is ShapeGeometry.Line -> Path().apply {
            moveTo(geometry.from.x, geometry.from.y)
            lineTo(geometry.to.x, geometry.to.y)
        }
        is ShapeGeometry.Arrow -> arrow(geometry)
        is ShapeGeometry.Path -> contours(geometry)
    }

    /**
     * A rectangle whose corners may each differ.
     *
     * `addRoundRect` takes eight radii but silently misbehaves when one exceeds half the side, so
     * each is clamped first — the alternative is a shape that inverts at large radii, which is what
     * a "squircle" slider dragged to its end would produce.
     */
    private fun rectangle(rectangle: ShapeGeometry.Rectangle): Path {
        val (w, h) = rectangle.size.x to rectangle.size.y
        val c = clampCorners(rectangle.cornerRadius, w, h)
        return Path().apply {
            addRoundRect(
                RectF(0f, 0f, w, h),
                floatArrayOf(
                    c.topLeft, c.topLeft, c.topRight, c.topRight,
                    c.bottomRight, c.bottomRight, c.bottomLeft, c.bottomLeft,
                ),
                Path.Direction.CW,
            )
        }
    }

    fun clampCorners(corners: Corners, width: Float, height: Float): Corners {
        val limit = min(width, height) / 2f
        return Corners(
            corners.topLeft.coerceIn(0f, limit),
            corners.topRight.coerceIn(0f, limit),
            corners.bottomRight.coerceIn(0f, limit),
            corners.bottomLeft.coerceIn(0f, limit),
        )
    }

    private fun polygon(polygon: ShapeGeometry.Polygon): Path {
        val points = (0 until polygon.sides).map { i ->
            // Starting at -90 degrees puts a vertex at the top, which is what every editor draws
            // and what a user expects a hexagon to look like.
            vertex(polygon.size, i.toFloat() / polygon.sides, 1f)
        }
        return rounded(points, polygon.cornerRadius)
    }

    private fun star(star: ShapeGeometry.Star): Path {
        val points = (0 until star.points * 2).map { i ->
            val radius = if (i % 2 == 0) 1f else star.innerRadius
            vertex(star.size, i.toFloat() / (star.points * 2), radius)
        }
        return rounded(points, star.cornerRadius)
    }

    private fun vertex(size: Vec2, turn: Float, radius: Float): Vec2 {
        val angle = turn * 2.0 * PI - PI / 2.0
        return Vec2(
            size.x / 2f + (cos(angle) * size.x / 2f * radius).toFloat(),
            size.y / 2f + (sin(angle) * size.y / 2f * radius).toFloat(),
        )
    }

    /**
     * Closes a polygon, optionally rounding its corners.
     *
     * Rounding is done by walking in from each corner along both its edges and arcing between the
     * two points. Applying a single radius blindly is the obvious implementation and it fails on a
     * star, whose inner corners are far closer together than its outer ones — so each corner is
     * limited by its own shorter edge.
     */
    private fun rounded(points: List<Vec2>, radius: Float): Path {
        val path = Path()
        if (points.isEmpty()) return path
        if (radius <= 0f) {
            path.moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
            path.close()
            return path
        }

        for (i in points.indices) {
            val previous = points[(i - 1 + points.size) % points.size]
            val current = points[i]
            val next = points[(i + 1) % points.size]

            val toPrevious = normalise(previous - current)
            val toNext = normalise(next - current)
            val limit = min(distance(previous, current), distance(current, next)) / 2f
            val inset = min(radius, limit)

            val start = current + toPrevious * inset
            val end = current + toNext * inset
            if (i == 0) path.moveTo(start.x, start.y) else path.lineTo(start.x, start.y)
            path.quadTo(current.x, current.y, end.x, end.y)
        }
        path.close()
        return path
    }

    /**
     * A line with optional heads, curved through its bend.
     *
     * The bend displaces the midpoint perpendicular to the line, which is what the curved arrows in
     * the reference designs are; a straight arrow is simply this with a bend of zero.
     */
    private fun arrow(arrow: ShapeGeometry.Arrow): Path {
        val path = Path()
        val direction = normalise(arrow.to - arrow.from)
        val normal = Vec2(-direction.y, direction.x)
        val middle = Vec2((arrow.from.x + arrow.to.x) / 2f, (arrow.from.y + arrow.to.y) / 2f)
        // A quadratic through a displaced control point passes the midpoint at half the bend, so
        // the control point is doubled to make the slider mean what it says.
        val control = middle + normal * (arrow.bend * 2f)

        path.moveTo(arrow.from.x, arrow.from.y)
        if (arrow.bend == 0f) path.lineTo(arrow.to.x, arrow.to.y) else {
            path.quadTo(control.x, control.y, arrow.to.x, arrow.to.y)
        }

        // Heads point along the tangent at the end they sit on, not along the chord — on a bent
        // arrow those differ, and a head aimed down the chord visibly detaches from the curve.
        val atTip = if (arrow.bend == 0f) direction else normalise(arrow.to - control)
        val atTail = if (arrow.bend == 0f) direction * -1f else normalise(arrow.from - control)
        head(path, arrow.to, atTip, arrow.headSize)
        if (arrow.tailHead) head(path, arrow.from, atTail, arrow.headSize)
        return path
    }

    private fun head(path: Path, tip: Vec2, direction: Vec2, size: Float) {
        if (size <= 0f) return
        val back = tip - direction * size
        val side = Vec2(-direction.y, direction.x) * (size * 0.5f)
        path.moveTo(tip.x, tip.y)
        path.lineTo((back + side).x, (back + side).y)
        path.lineTo((back - side).x, (back - side).y)
        path.close()
    }

    private fun contours(geometry: ShapeGeometry.Path): Path {
        val path = Path()
        for (contour in geometry.contours) {
            val nodes = contour.nodes
            if (nodes.isEmpty()) continue
            path.moveTo(nodes[0].point.x, nodes[0].point.y)
            for (i in 1 until nodes.size) {
                val from = nodes[i - 1]
                val to = nodes[i]
                path.cubicTo(
                    from.controlOut.x, from.controlOut.y,
                    to.controlIn.x, to.controlIn.y,
                    to.point.x, to.point.y,
                )
            }
            if (contour.closed) {
                val last = nodes.last()
                val first = nodes.first()
                path.cubicTo(
                    last.controlOut.x, last.controlOut.y,
                    first.controlIn.x, first.controlIn.y,
                    first.point.x, first.point.y,
                )
                path.close()
            }
        }
        return path
    }

    private fun normalise(v: Vec2): Vec2 {
        val length = hypot(v.x, v.y)
        return if (length < 1e-5f) Vec2(1f, 0f) else Vec2(v.x / length, v.y / length)
    }

    private fun distance(a: Vec2, b: Vec2) = hypot(b.x - a.x, b.y - a.y)
}
