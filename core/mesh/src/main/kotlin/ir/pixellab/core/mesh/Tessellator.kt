package ir.pixellab.core.mesh

import ir.pixellab.core.model.Vec2
import kotlin.math.abs

/**
 * Turns glyph outlines into triangles.
 *
 * Ear clipping, with holes bridged into their outer contour first. Ear clipping is O(n²) in the
 * worst case and a sweep-line tessellator is asymptotically better, but a glyph is a few hundred
 * points after flattening and the constant factor decides the real cost at that size. What actually
 * matters here is the two things ear clipping gets right without ceremony: it needs no exact
 * arithmetic, and it never produces a triangle outside the outline — which a scanline fill will,
 * at the exact places where a Persian letter's stroke doubles back on itself.
 *
 * Holes are the part that has to be right. The counter of a ه or the two bowls of a ۸ are separate
 * contours wound the other way, and a tessellator that ignored winding would fill them in solid.
 */
object Tessellator {

    /**
     * Signed area, doubled. Positive means counter-clockwise in a y-up frame.
     *
     * The sign is how a hole is told from an outline, and it is the only reliable way — testing
     * containment point by point fails on a glyph whose counter touches its stroke.
     */
    fun signedArea(polygon: List<Vec2>): Float {
        if (polygon.size < 3) return 0f
        var sum = 0f
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            sum += a.x * b.y - b.x * a.y
        }
        return sum / 2f
    }

    /** Even-odd containment, used to decide which outline a hole belongs to. */
    fun contains(polygon: List<Vec2>, point: Vec2): Boolean {
        var inside = false
        var j = polygon.size - 1
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[j]
            if ((a.y > point.y) != (b.y > point.y)) {
                val t = (point.y - a.y) / (b.y - a.y)
                if (point.x < a.x + t * (b.x - a.x)) inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * Triangulates one set of contours.
     *
     * @return flat index triples into a single vertex list, which is also returned. The vertex list
     *   is not the input: bridging a hole duplicates two points, and a caller that assumed the
     *   input indices still meant something would silently draw the wrong triangles.
     */
    fun triangulate(contours: List<List<Vec2>>): Triangulation {
        val usable = contours.map { dedupe(it) }.filter { it.size >= 3 }
        if (usable.isEmpty()) return Triangulation(emptyList(), IntArray(0))

        // Largest first, so an outline is always seen before the holes that sit inside it.
        val byArea = usable.sortedByDescending { abs(signedArea(it)) }
        val outlines = ArrayList<MutableList<Vec2>>()
        val pending = ArrayList<List<Vec2>>()

        for (contour in byArea) {
            val owner = outlines.firstOrNull { contains(it, contour[0]) }
            if (owner == null) {
                outlines += orient(contour, counterClockwise = true).toMutableList()
            } else {
                pending += orient(contour, counterClockwise = false)
            }
        }
        if (outlines.isEmpty()) return Triangulation(emptyList(), IntArray(0))

        for (hole in pending) {
            val owner = outlines.firstOrNull { contains(it, hole[0]) } ?: outlines[0]
            bridge(owner, hole)
        }

        val vertices = ArrayList<Vec2>()
        val indices = ArrayList<Int>()
        for (outline in outlines) {
            val base = vertices.size
            vertices += outline
            for (index in earClip(outline)) indices += base + index
        }
        return Triangulation(vertices, indices.toIntArray())
    }

    /**
     * Cuts a hole into its outline with a two-way bridge.
     *
     * The hole is walked from its rightmost point and spliced in at the outline vertex nearest to
     * it, then the outline resumes. Both bridge vertices are duplicated on purpose — the seam has
     * to be a zero-width slit, and a single shared vertex would leave the polygon pinched at a
     * point, which ear clipping cannot cut through.
     */
    private fun bridge(outline: MutableList<Vec2>, hole: List<Vec2>) {
        val start = hole.indices.maxByOrNull { hole[it].x } ?: return
        val anchor = hole[start]
        val join = outline.indices.minByOrNull { squaredDistance(outline[it], anchor) } ?: return

        val spliced = ArrayList<Vec2>(outline.size + hole.size + 2)
        spliced += outline.subList(0, join + 1)
        for (i in hole.indices) spliced += hole[(start + i) % hole.size]
        spliced += anchor
        spliced += outline[join]
        spliced += outline.subList(join + 1, outline.size)

        outline.clear()
        outline += spliced
    }

    /**
     * Ear clipping over a simple polygon, assumed counter-clockwise.
     *
     * The loop gives up rather than spinning if no ear is found — which happens on a
     * self-intersecting outline, and self-intersecting outlines exist in real fonts. Emitting the
     * triangles found so far leaves a glyph with a nick in it; looping forever hangs the app.
     */
    private fun earClip(polygon: List<Vec2>): List<Int> {
        val remaining = polygon.indices.toMutableList()
        val out = ArrayList<Int>((polygon.size - 2) * 3)
        var guard = polygon.size * polygon.size

        while (remaining.size > 3 && guard-- > 0) {
            var clipped = false
            for (i in remaining.indices) {
                val prev = remaining[(i + remaining.size - 1) % remaining.size]
                val current = remaining[i]
                val next = remaining[(i + 1) % remaining.size]

                if (!isEar(polygon, remaining, prev, current, next)) continue
                out += prev
                out += current
                out += next
                remaining.removeAt(i)
                clipped = true
                break
            }
            if (!clipped) break
        }
        if (remaining.size == 3) {
            out += remaining[0]
            out += remaining[1]
            out += remaining[2]
        }
        return out
    }

    private fun isEar(
        polygon: List<Vec2>,
        remaining: List<Int>,
        prev: Int,
        current: Int,
        next: Int,
    ): Boolean {
        val a = polygon[prev]
        val b = polygon[current]
        val c = polygon[next]
        // A reflex corner is not an ear: cutting it would take a bite out of the outside.
        if (cross(a, b, c) <= 0f) return false

        for (index in remaining) {
            if (index == prev || index == current || index == next) continue
            if (insideTriangle(a, b, c, polygon[index])) return false
        }
        return true
    }

    private fun cross(a: Vec2, b: Vec2, c: Vec2): Float =
        (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

    private fun insideTriangle(a: Vec2, b: Vec2, c: Vec2, p: Vec2): Boolean {
        val d1 = cross(a, b, p)
        val d2 = cross(b, c, p)
        val d3 = cross(c, a, p)
        // Strictly inside: a point exactly on an edge is shared geometry, not an obstruction, and
        // treating it as one stalls the clip on every glyph with a tangent counter.
        return d1 > 0f && d2 > 0f && d3 > 0f
    }

    private fun squaredDistance(a: Vec2, b: Vec2): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return dx * dx + dy * dy
    }

    private fun orient(polygon: List<Vec2>, counterClockwise: Boolean): List<Vec2> {
        val positive = signedArea(polygon) > 0f
        return if (positive == counterClockwise) polygon else polygon.reversed()
    }

    /**
     * Drops repeated points.
     *
     * Flattening a curve emits a point per step and the last step of one segment lands exactly on
     * the first of the next. A duplicate is a zero-length edge, and a zero-length edge is a corner
     * with no direction — which makes the ear test's cross product zero and stalls the clip.
     */
    private fun dedupe(polygon: List<Vec2>): List<Vec2> {
        if (polygon.isEmpty()) return polygon
        val out = ArrayList<Vec2>(polygon.size)
        for (point in polygon) {
            val last = out.lastOrNull()
            if (last == null || squaredDistance(last, point) > MERGE * MERGE) out += point
        }
        while (out.size >= 2 && squaredDistance(out.first(), out.last()) <= MERGE * MERGE) {
            out.removeAt(out.size - 1)
        }
        return out
    }

    /** A hundredth of a font unit at a typical size: below any real detail, above float noise. */
    private const val MERGE = 1e-3f
}

/** Triangles, and the vertices they index — which are not the caller's, because holes are bridged. */
data class Triangulation(val vertices: List<Vec2>, val indices: IntArray) {
    val triangleCount: Int get() = indices.size / 3

    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}
