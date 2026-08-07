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
    /**
     * Drops the points that describe nothing: repeats, and points on the line between neighbours.
     *
     * Public because the caller that builds *walls* along an outline has to use exactly the same
     * points as the caller that caps it. That is not a tidiness argument — it is the difference
     * between a closed solid and a surface with holes in it. Simplifying inside [triangulate] alone
     * left the cap a coarse polygon while the wall still followed every flattened point, so the two
     * shared no vertices and the extrusion had a wedge of nothing between them everywhere the
     * outline curved. A straight extrusion hides those edge-on; leaning it swings them into view.
     */
    fun clean(contour: List<Vec2>): List<Vec2> = simplify(dedupe(contour))

    fun triangulate(contours: List<List<Vec2>>): Triangulation {
        val usable = contours.map { clean(it) }.filter { it.size >= 3 }
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

        // Every hole's owner is decided against the *pristine* outlines, before any of them is
        // bridged. Deciding as we go was wrong in a way that only shows on a word rather than on a
        // letter: bridging splices the hole's points into its outline and leaves a zero-width slit,
        // and an even-odd containment test against a polygon that touches itself along a slit
        // answers unreliably. So the second counter of a word could be judged to live inside the
        // first letter, get bridged into it, and draw a seam clean across the artwork to reach it.
        val owners = pending.map { hole -> outlines.indexOfFirst { contains(it, hole[0]) } }
        for (i in pending.indices) {
            val owner = owners[i]
            if (owner < 0) {
                // Inside nothing. It was taken for a hole because it sat inside some contour when
                // that contour was still whole, and it is not one — so it is an outline of its own.
                // Bridging it into an arbitrary outline, which is what used to happen here, cuts a
                // slit between two unrelated letters.
                outlines += orient(pending[i], counterClockwise = true).toMutableList()
                continue
            }
            bridge(outlines[owner], pending[i])
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
     * **This must never return a partial triangulation, and for a long time it did.** A scan that
     * found no ear anywhere abandoned the polygon and emitted whatever it had, which draws as a
     * letter with holes in its front — on real glyph outlines it was losing between a fifth and two
     * thirds of every cap. The failure is silent by construction: the mesh is still closed, still
     * correctly wound, still renders. Only a picture shows it.
     *
     * Two things make the scan fail, and both are ordinary rather than exotic. A flattened curve
     * emits points along a straight stem that are collinear to within float error, so the sign of
     * their cross product is noise — [simplify] removes those before we get here. And a genuinely
     * degenerate outline, which real fonts do contain, can leave a state with no valid ear at all.
     *
     * So the loop can no longer give up. If a full pass finds no ear it removes the flattest vertex
     * and carries on: dropping a vertex from a polygon that has no ear costs a sliver of area at a
     * place already too thin to see, and it guarantees progress, which is what stops the choice
     * being between a broken letter and a hung app.
     *
     * The scan resumes where the last ear was cut rather than restarting from the beginning. Cutting
     * an ear only changes the ear-status of its two neighbours, so restarting rescans a whole
     * polygon to re-reject vertices it rejected a moment ago — the difference between quadratic and
     * cubic, which at two thousand points per letter is the difference between usable and not.
     */
    private fun earClip(polygon: List<Vec2>): List<Int> {
        val remaining = polygon.indices.toMutableList()
        val out = ArrayList<Int>((polygon.size - 2) * 3)
        var cursor = 0

        while (remaining.size > 3) {
            var clipped = false
            for (step in remaining.indices) {
                val i = (cursor + step) % remaining.size
                val prev = remaining[(i + remaining.size - 1) % remaining.size]
                val current = remaining[i]
                val next = remaining[(i + 1) % remaining.size]

                if (!isEar(polygon, remaining, prev, current, next)) continue
                out += prev
                out += current
                out += next
                remaining.removeAt(i)
                // Back one, so the neighbour whose status just changed is the next thing examined.
                cursor = if (i == 0) remaining.size - 1 else i - 1
                clipped = true
                break
            }
            if (clipped) continue

            // No ear anywhere. Drop the vertex that bends the least, which is the one whose removal
            // changes the outline least, and try again.
            var flattest = 0
            var smallest = Float.MAX_VALUE
            for (i in remaining.indices) {
                val a = polygon[remaining[(i + remaining.size - 1) % remaining.size]]
                val b = polygon[remaining[i]]
                val c = polygon[remaining[(i + 1) % remaining.size]]
                val bend = abs(cross(a, b, c))
                if (bend < smallest) {
                    smallest = bend
                    flattest = i
                }
            }
            remaining.removeAt(flattest)
            cursor = 0
        }
        if (remaining.size == 3) {
            out += remaining[0]
            out += remaining[1]
            out += remaining[2]
        }
        return out
    }

    /**
     * Drops points that sit on the line between their neighbours.
     *
     * A glyph outline arrives flattened, and flattening is generous: a straight stem at headline
     * size comes through as scores of points strung along it, and a five-letter word as nearly ten
     * thousand. None of them change the shape, and all of them cost. Two costs, and the second is
     * the one that mattered.
     *
     * The obvious cost is time — ear clipping is quadratic in the point count at best, so ten
     * thousand points is a hundred million comparisons for a cap that a thousand points describes
     * exactly as well.
     *
     * The real cost is that collinear points *break* the clip. Whether three points strung along a
     * straight line come out convex, reflex or exactly flat is decided by float error in the last
     * bits, and a vertex that reads as reflex is never a valid ear. A run of them is a stretch of
     * outline the clip cannot cut anywhere, and that is what emptied the caps.
     *
     * The tolerance is relative to the outline's own size, because this runs on glyphs at any point
     * size and an absolute one would be invisible on a poster and destructive on a caption.
     */
    private fun simplify(polygon: List<Vec2>): List<Vec2> {
        if (polygon.size < 4) return polygon

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (point in polygon) {
            if (point.x < minX) minX = point.x
            if (point.y < minY) minY = point.y
            if (point.x > maxX) maxX = point.x
            if (point.y > maxY) maxY = point.y
        }
        val diagonal = kotlin.math.hypot(maxX - minX, maxY - minY)
        if (diagonal <= 0f) return polygon
        val tolerance = diagonal * FLATNESS

        val out = ArrayList<Vec2>(polygon.size)
        for (i in polygon.indices) {
            // Against the last point *kept* rather than the previous point of the input, so a long
            // run of gentle steps is collapsed as one arc instead of surviving a step at a time.
            val a = out.lastOrNull() ?: polygon[polygon.size - 1]
            val b = polygon[i]
            val c = polygon[(i + 1) % polygon.size]
            val span = kotlin.math.hypot(c.x - a.x, c.y - a.y)
            if (span <= 0f) continue
            if (abs(cross(a, b, c)) / span > tolerance) out += b
        }
        return if (out.size >= 3) out else polygon
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

    /**
     * How far off the line between its neighbours a point must sit to be worth keeping, against the
     * outline's own diagonal.
     *
     * A ten-thousandth, which on a headline-sized word is a fraction of a pixel — below anything the
     * bevel or the shading can express — and still coarse enough to collapse the flattener's straight
     * runs, which is the whole point. Loosening it rounds off the corners of a letter; tightening it
     * lets the collinear runs back in, and with them the empty caps.
     */
    private const val FLATNESS = 1e-4f
}

/** Triangles, and the vertices they index — which are not the caller's, because holes are bridged. */
data class Triangulation(val vertices: List<Vec2>, val indices: IntArray) {
    val triangleCount: Int get() = indices.size / 3

    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}
