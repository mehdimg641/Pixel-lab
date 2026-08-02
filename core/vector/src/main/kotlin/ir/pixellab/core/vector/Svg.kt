package ir.pixellab.core.vector

import ir.pixellab.core.model.Contour
import ir.pixellab.core.model.PathNode
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.Vec2

/**
 * SVG path data, both directions.
 *
 * The `d` attribute rather than a whole SVG document, because that string is the actual interchange
 * unit: it is what a font editor emits, what an icon set ships, what Illustrator puts on the
 * clipboard, and what every other tool will accept back.
 *
 * Only the path grammar is handled. Reading a full SVG means CSS, transforms, `use`, gradients and a
 * DOM, and a half-implementation of that is worse than an honest boundary — a file that opens and
 * silently drops half its artwork is harder to diagnose than one that refuses.
 */
object SvgPath {

    /**
     * Writes a path.
     *
     * Cubic segments throughout, with `L` for the ones whose handles sit on their endpoints. Writing
     * every segment as a curve would be correct and would double the file, and a straight edge
     * written as a curve loses its straightness the first time another tool rounds the numbers.
     */
    fun write(path: ShapeGeometry.Path, precision: Int = DEFAULT_PRECISION): String = buildString {
        for (contour in path.contours) {
            val nodes = contour.nodes
            if (nodes.isEmpty()) continue

            append('M')
            append(number(nodes.first().point.x, precision))
            append(' ')
            append(number(nodes.first().point.y, precision))

            for ((from, to) in PathMath.segments(contour)) {
                if (isStraight(from, to)) {
                    append('L')
                    append(number(to.point.x, precision))
                    append(' ')
                    append(number(to.point.y, precision))
                } else {
                    append('C')
                    append(number(from.controlOut.x, precision))
                    append(' ')
                    append(number(from.controlOut.y, precision))
                    append(' ')
                    append(number(to.controlIn.x, precision))
                    append(' ')
                    append(number(to.controlIn.y, precision))
                    append(' ')
                    append(number(to.point.x, precision))
                    append(' ')
                    append(number(to.point.y, precision))
                }
            }
            if (contour.closed) append('Z')
        }
    }

    /**
     * Reads path data.
     *
     * Every command in the grammar, absolute and relative, including the shorthand curves and the
     * elliptical arc. The shorthands are not optional in practice: almost every optimised icon uses
     * `s` and `h`/`v` heavily, and a reader without them silently produces a mangled shape rather
     * than an error.
     */
    fun read(data: String): ShapeGeometry.Path {
        val tokens = Tokenizer(data)
        val contours = ArrayList<Contour>()
        var nodes = ArrayList<PathNode>()
        var closed = false

        var current = Vec2.ZERO
        var start = Vec2.ZERO
        // The reflection point for a shorthand curve; null when the previous command was not one.
        var lastControl: Vec2? = null

        fun flush() {
            if (nodes.size >= 2) contours += Contour(nodes.toList(), closed)
            nodes = ArrayList()
            closed = false
        }

        fun lineTo(to: Vec2) {
            nodes += PathNode(to)
            current = to
            lastControl = null
        }

        fun curveTo(control1: Vec2, control2: Vec2, to: Vec2) {
            if (nodes.isNotEmpty()) {
                nodes[nodes.lastIndex] = nodes.last().copy(controlOut = control1)
            }
            nodes += PathNode(point = to, controlIn = control2, controlOut = to)
            current = to
            lastControl = control2
        }

        while (true) {
            val command = tokens.command() ?: break
            val relative = command.isLowerCase()
            val base = { if (relative) current else Vec2.ZERO }

            when (command.uppercaseChar()) {
                'M' -> {
                    flush()
                    val at = tokens.point(base())
                    nodes += PathNode(at)
                    current = at
                    start = at
                    lastControl = null
                    // Extra coordinate pairs after a moveto are implicit linetos, which is how a
                    // polygon is usually written.
                    while (tokens.hasNumber()) lineTo(tokens.point(if (relative) current else Vec2.ZERO))
                }
                'L' -> while (tokens.hasNumber()) lineTo(tokens.point(if (relative) current else Vec2.ZERO))
                'H' -> while (tokens.hasNumber()) {
                    val x = tokens.number() + if (relative) current.x else 0f
                    lineTo(Vec2(x, current.y))
                }
                'V' -> while (tokens.hasNumber()) {
                    val y = tokens.number() + if (relative) current.y else 0f
                    lineTo(Vec2(current.x, y))
                }
                'C' -> while (tokens.hasNumber()) {
                    val origin = if (relative) current else Vec2.ZERO
                    val c1 = tokens.point(origin)
                    val c2 = tokens.point(origin)
                    val to = tokens.point(origin)
                    curveTo(c1, c2, to)
                }
                'S' -> while (tokens.hasNumber()) {
                    val origin = if (relative) current else Vec2.ZERO
                    // The first control is the previous one reflected through the current point;
                    // that reflection is the whole meaning of the shorthand.
                    val c1 = lastControl?.let { Vec2(2f * current.x - it.x, 2f * current.y - it.y) } ?: current
                    val c2 = tokens.point(origin)
                    val to = tokens.point(origin)
                    curveTo(c1, c2, to)
                }
                'Q' -> while (tokens.hasNumber()) {
                    val origin = if (relative) current else Vec2.ZERO
                    val q = tokens.point(origin)
                    val to = tokens.point(origin)
                    val (c1, c2) = quadraticToCubic(current, q, to)
                    curveTo(c1, c2, to)
                    lastControl = q
                }
                'T' -> while (tokens.hasNumber()) {
                    val origin = if (relative) current else Vec2.ZERO
                    val q = lastControl?.let { Vec2(2f * current.x - it.x, 2f * current.y - it.y) } ?: current
                    val to = tokens.point(origin)
                    val (c1, c2) = quadraticToCubic(current, q, to)
                    curveTo(c1, c2, to)
                    lastControl = q
                }
                'A' -> while (tokens.hasNumber()) {
                    val rx = tokens.number()
                    val ry = tokens.number()
                    val rotation = tokens.number()
                    val largeArc = tokens.flag()
                    val sweep = tokens.flag()
                    val to = tokens.point(if (relative) current else Vec2.ZERO)
                    for (segment in Arc.toCubics(current, rx, ry, rotation, largeArc, sweep, to)) {
                        curveTo(segment.control1, segment.control2, segment.to)
                    }
                }
                'Z' -> {
                    closed = true
                    flush()
                    current = start
                    lastControl = null
                }
                else -> break
            }
        }
        flush()
        return ShapeGeometry.Path(contours)
    }

    private fun isStraight(from: PathNode, to: PathNode): Boolean =
        near(from.controlOut, from.point) && near(to.controlIn, to.point)

    private fun near(a: Vec2, b: Vec2) =
        kotlin.math.abs(a.x - b.x) < PathMath.EPSILON && kotlin.math.abs(a.y - b.y) < PathMath.EPSILON

    /** A quadratic is a cubic whose controls sit two thirds of the way to the single control. */
    private fun quadraticToCubic(from: Vec2, control: Vec2, to: Vec2): Pair<Vec2, Vec2> = Pair(
        Vec2(from.x + 2f / 3f * (control.x - from.x), from.y + 2f / 3f * (control.y - from.y)),
        Vec2(to.x + 2f / 3f * (control.x - to.x), to.y + 2f / 3f * (control.y - to.y)),
    )

    private fun number(value: Float, precision: Int): String {
        val rounded = kotlin.math.round(value * pow10(precision)) / pow10(precision)
        // Integers written without a decimal point: an icon set is mostly integers, and ".0" on
        // every one of them is a fifth of the file for nothing.
        return if (rounded == rounded.toLong().toFloat()) rounded.toLong().toString() else rounded.toString()
    }

    private fun pow10(n: Int): Float {
        var result = 1f
        repeat(n) { result *= 10f }
        return result
    }

    private const val DEFAULT_PRECISION = 3

    /**
     * Walks the path grammar.
     *
     * Written by hand rather than with a regular expression because the grammar is not regular: the
     * arc flags are single characters that may run together with the number after them, which no
     * general number pattern will split correctly.
     */
    private class Tokenizer(private val data: String) {
        private var at = 0

        fun command(): Char? {
            skip()
            if (at >= data.length) return null
            val c = data[at]
            if (!c.isLetter()) return null
            at++
            return c
        }

        fun hasNumber(): Boolean {
            skip()
            if (at >= data.length) return false
            val c = data[at]
            return c.isDigit() || c == '-' || c == '+' || c == '.'
        }

        fun number(): Float {
            skip()
            val start = at
            if (at < data.length && (data[at] == '-' || data[at] == '+')) at++
            while (at < data.length && (data[at].isDigit() || data[at] == '.')) {
                // A second decimal point begins the next number: "1.5.5" is two values, which
                // optimised path data relies on.
                if (data[at] == '.' && data.substring(start, at).contains('.')) break
                at++
            }
            if (at < data.length && (data[at] == 'e' || data[at] == 'E')) {
                at++
                if (at < data.length && (data[at] == '-' || data[at] == '+')) at++
                while (at < data.length && data[at].isDigit()) at++
            }
            return data.substring(start, at).toFloatOrNull() ?: 0f
        }

        fun point(origin: Vec2) = Vec2(number() + origin.x, number() + origin.y)

        /** An arc flag is exactly one character and may be followed immediately by a number. */
        fun flag(): Boolean {
            skip()
            if (at >= data.length) return false
            val c = data[at]
            at++
            return c == '1'
        }

        private fun skip() {
            while (at < data.length && (data[at].isWhitespace() || data[at] == ',')) at++
        }
    }
}

/** One cubic segment, as an arc conversion produces it. */
data class CubicSegment(val control1: Vec2, val control2: Vec2, val to: Vec2)

/**
 * Elliptical arcs, turned into cubics.
 *
 * SVG's arc is parameterised by its endpoints and a shape, which no renderer draws directly — every
 * one of them converts to curves first. Doing it here means the rest of the app never sees an arc.
 */
object Arc {

    fun toCubics(
        from: Vec2,
        rx: Float,
        ry: Float,
        rotationDegrees: Float,
        largeArc: Boolean,
        sweep: Boolean,
        to: Vec2,
    ): List<CubicSegment> {
        // Degenerate radii mean a straight line, which the specification says explicitly and which
        // real files do use to draw a line with an arc command.
        if (rx <= PathMath.EPSILON || ry <= PathMath.EPSILON) {
            return listOf(CubicSegment(from, to, to))
        }

        val angle = Math.toRadians(rotationDegrees.toDouble())
        val cos = kotlin.math.cos(angle)
        val sin = kotlin.math.sin(angle)

        val dx = (from.x - to.x) / 2.0
        val dy = (from.y - to.y) / 2.0
        val x1 = cos * dx + sin * dy
        val y1 = -sin * dx + cos * dy

        var radiusX = kotlin.math.abs(rx.toDouble())
        var radiusY = kotlin.math.abs(ry.toDouble())
        // Radii too small to reach are scaled up, as the specification requires; without this an
        // exported file with rounded coordinates produces NaN and the shape vanishes.
        val check = (x1 * x1) / (radiusX * radiusX) + (y1 * y1) / (radiusY * radiusY)
        if (check > 1.0) {
            val scale = kotlin.math.sqrt(check)
            radiusX *= scale
            radiusY *= scale
        }

        val sign = if (largeArc == sweep) -1.0 else 1.0
        val numerator = (radiusX * radiusX * radiusY * radiusY -
            radiusX * radiusX * y1 * y1 - radiusY * radiusY * x1 * x1)
            .coerceAtLeast(0.0)
        val denominator = radiusX * radiusX * y1 * y1 + radiusY * radiusY * x1 * x1
        val coefficient = sign * kotlin.math.sqrt(numerator / denominator.coerceAtLeast(1e-12))

        val cx1 = coefficient * radiusX * y1 / radiusY
        val cy1 = -coefficient * radiusY * x1 / radiusX
        val cx = cos * cx1 - sin * cy1 + (from.x + to.x) / 2.0
        val cy = sin * cx1 + cos * cy1 + (from.y + to.y) / 2.0

        val startAngle = kotlin.math.atan2((y1 - cy1) / radiusY, (x1 - cx1) / radiusX)
        var deltaAngle = kotlin.math.atan2((-y1 - cy1) / radiusY, (-x1 - cx1) / radiusX) - startAngle
        if (!sweep && deltaAngle > 0) deltaAngle -= 2 * Math.PI
        if (sweep && deltaAngle < 0) deltaAngle += 2 * Math.PI

        // A cubic approximates at most a quarter turn to within a pixel; more per segment and the
        // error is visible on a large circle.
        val segments = kotlin.math.ceil(kotlin.math.abs(deltaAngle) / (Math.PI / 2)).toInt().coerceAtLeast(1)
        val step = deltaAngle / segments
        val alpha = 4.0 / 3.0 * kotlin.math.tan(step / 4)

        val out = ArrayList<CubicSegment>(segments)
        var theta = startAngle
        var point = from
        repeat(segments) {
            val next = theta + step
            val cosStart = kotlin.math.cos(theta)
            val sinStart = kotlin.math.sin(theta)
            val cosEnd = kotlin.math.cos(next)
            val sinEnd = kotlin.math.sin(next)

            val endX = cos * radiusX * cosEnd - sin * radiusY * sinEnd + cx
            val endY = sin * radiusX * cosEnd + cos * radiusY * sinEnd + cy

            val dxStart = -radiusX * sinStart
            val dyStart = radiusY * cosStart
            val dxEnd = -radiusX * sinEnd
            val dyEnd = radiusY * cosEnd

            val c1 = Vec2(
                (point.x + alpha * (cos * dxStart - sin * dyStart)).toFloat(),
                (point.y + alpha * (sin * dxStart + cos * dyStart)).toFloat(),
            )
            val c2 = Vec2(
                (endX - alpha * (cos * dxEnd - sin * dyEnd)).toFloat(),
                (endY - alpha * (sin * dxEnd + cos * dyEnd)).toFloat(),
            )
            val end = Vec2(endX.toFloat(), endY.toFloat())
            out += CubicSegment(c1, c2, end)
            point = end
            theta = next
        }
        return out
    }
}
