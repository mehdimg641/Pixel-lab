package ir.pixellab.core.mesh

import ir.pixellab.core.model.Curve
import ir.pixellab.core.model.Vec2
import ir.pixellab.core.model.Vec3
import kotlin.math.sqrt

/**
 * Gives a flat outline depth, a bevelled edge and real surface normals.
 *
 * This is what separates 3D text from a stack of offset copies. A stacked extrusion — the trick the
 * reference PSDs use, twenty-nine duplicates of a smart object — cannot do two things this does:
 * the bevel cannot catch a specular highlight that moves as the letter turns, and the side walls
 * cannot darken towards the back, because in a stack every copy is lit identically.
 *
 * The bevel is built as a sequence of rings offset inward and forward along a profile curve. Its
 * normals are the whole point: a ring's normal blends from facing the viewer to facing sideways, and
 * that blend is what a highlight slides along.
 */
object Extruder {

    /**
     * @param contours flattened outlines in the glyph's own units, y **up**. A caller working in
     *   screen coordinates must flip first — extruding a y-down outline turns every letter inside
     *   out, and the result looks like correct geometry lit from behind.
     * @param depth how far back the letter goes.
     * @param bevelSize how far in from the outline the bevel reaches. Clamped, because a bevel
     *   wider than the thinnest stroke of the letter turns that stroke into a ridge with no face.
     * @param bevelSegments rings across the bevel. Two is a chamfer, eight is a rounded edge; the
     *   difference is entirely in how the highlight travels.
     */
    fun extrude(
        contours: List<List<Vec2>>,
        depth: Float,
        bevelSize: Float = 0f,
        bevelProfile: Curve = Curve.LINEAR,
        bevelSegments: Int = 6,
    ): Mesh {
        val cleaned = contours.filter { it.size >= 3 }
        if (cleaned.isEmpty()) return Mesh.EMPTY

        val builder = MeshBuilder()
        val bevel = bevelSize.coerceAtLeast(0f)
        val segments = if (bevel <= 0f) 0 else bevelSegments.coerceIn(1, MAX_SEGMENTS)

        // The face sits at the front of the bevel, inset by its full width. Tessellating the
        // original outline and *then* insetting would leave the face and the bevel disagreeing
        // about where the letter's edge is, which shows as a hairline crack all the way round.
        val faceContours = if (bevel <= 0f) cleaned else cleaned.map { inset(it, bevel) }
        val faceZ = if (bevel <= 0f) 0f else bevelDepth(bevel)

        addCap(builder, faceContours, z = faceZ, front = true, surface = Surface.FACE)
        addCap(builder, cleaned, z = -depth, front = false, surface = Surface.BACK)

        for (contour in cleaned) {
            val rims = rimsOf(contour) ?: continue
            val rings = buildRings(rims, bevel, segments, bevelProfile)
            stitch(builder, rings, depth)
        }
        return builder.build()
    }

    /**
     * The rings the side of a letter is made of: the bevel's, then the back.
     *
     * Each ring carries its own normal per point rather than one normal for the whole ring, because
     * a corner of a letter is where two edges meet at an angle and the normal has to turn with them.
     */
    private fun buildRings(
        rims: List<Rim>,
        bevel: Float,
        segments: Int,
        profile: Curve,
    ): List<List<Pair<Vec3, Vec3>>> {
        val rings = ArrayList<List<Pair<Vec3, Vec3>>>(segments + 2)

        for (step in segments downTo 0) {
            val t = if (segments == 0) 0f else step.toFloat() / segments
            // t = 1 is the outline itself, t = 0 is where the bevel meets the face.
            val inward = bevel * (1f - t)
            val forward = bevelDepth(bevel) * profileAt(profile, 1f - t)
            rings += rims.map { rim ->
                val position = Vec3(
                    rim.point.x - rim.outward.x * inward,
                    rim.point.y - rim.outward.y * inward,
                    forward,
                )
                // Blended between facing the viewer and facing outward. At the face end the bevel
                // is nearly flat on, at the outline end nearly sideways — and that gradient is the
                // thing a highlight slides along.
                val normal = Vec3(
                    rim.outward.x * t,
                    rim.outward.y * t,
                    (1f - t) + BEVEL_FACE_BIAS,
                ).normalised()
                position to normal
            }
        }
        // Reversed so the list runs front to back, which is the direction the walls are stitched in.
        rings.reverse()

        // The side wall: straight out from the outline, all the way to the back.
        rings += rims.map { rim ->
            Vec3(rim.point.x, rim.point.y, 0f) to Vec3(rim.outward.x, rim.outward.y, 0f)
        }
        return rings
    }

    private fun stitch(builder: MeshBuilder, rings: List<List<Pair<Vec3, Vec3>>>, depth: Float) {
        if (rings.isEmpty()) return
        val count = rings[0].size

        var previous = rings[0].map { builder.vertex(it.first, it.second) }
        for (ring in 1 until rings.size) {
            val surface = if (ring < rings.size - 1) Surface.BEVEL else Surface.SIDE
            val current = rings[ring].map { builder.vertex(it.first, it.second) }
            for (i in 0 until count) {
                val j = (i + 1) % count
                builder.quad(previous[i], previous[j], current[j], current[i], surface)
            }
            previous = current
        }

        // The back of the side wall, dropped to the full depth. A separate ring rather than moving
        // the last one, so the wall's normal stays horizontal along its whole height instead of
        // tilting to meet the back face.
        val back = rings.last().map { builder.vertex(Vec3(it.first.x, it.first.y, -depth), it.second) }
        for (i in 0 until count) {
            val j = (i + 1) % count
            builder.quad(previous[i], previous[j], back[j], back[i], Surface.SIDE)
        }
    }

    private fun addCap(
        builder: MeshBuilder,
        contours: List<List<Vec2>>,
        z: Float,
        front: Boolean,
        surface: Surface,
    ) {
        val triangulated = Tessellator.triangulate(contours)
        if (triangulated.triangleCount == 0) return

        val normal = if (front) Vec3(0f, 0f, 1f) else Vec3(0f, 0f, -1f)
        val base = builder.vertexCount
        for (point in triangulated.vertices) builder.vertex(Vec3(point.x, point.y, z), normal)

        for (t in 0 until triangulated.triangleCount) {
            val a = base + triangulated.indices[t * 3]
            val b = base + triangulated.indices[t * 3 + 1]
            val c = base + triangulated.indices[t * 3 + 2]
            // The back is wound the other way so both caps face outward, which is what lets the
            // rasteriser cull back faces instead of drawing every letter twice.
            if (front) builder.triangle(a, b, c, surface) else builder.triangle(a, c, b, surface)
        }
    }

    /**
     * Each point's outward direction: the angle bisector of its two edges.
     *
     * The bisector rather than either edge's normal, because at a corner the two disagree and using
     * one of them opens a gap on the outside of every turn. Scaled by the miter length so the
     * offset ring stays parallel to the original — an unscaled bisector pulls corners in too far
     * and rounds off every serif.
     */
    private fun rimsOf(contour: List<Vec2>): List<Rim>? {
        val n = contour.size
        if (n < 3) return null
        val counterClockwise = Tessellator.signedArea(contour) > 0f
        val points = if (counterClockwise) contour else contour.reversed()

        return List(n) { i ->
            val previous = points[(i + n - 1) % n]
            val point = points[i]
            val next = points[(i + 1) % n]

            val into = normalise(point.x - previous.x, point.y - previous.y)
            val outOf = normalise(next.x - point.x, next.y - point.y)
            // For a counter-clockwise outline the outward side is to the right of travel.
            val na = Vec2(into.y, -into.x)
            val nb = Vec2(outOf.y, -outOf.x)

            var bisector = normalise(na.x + nb.x, na.y + nb.y)
            if (bisector.x == 0f && bisector.y == 0f) bisector = na

            // Miter scale: 1/cos(half the turn). Capped, or a needle-sharp corner throws its offset
            // ring hundreds of units away and the letter grows a spike.
            val cosHalf = (bisector.x * na.x + bisector.y * na.y).coerceAtLeast(MIN_COS)
            val scale = (1f / cosHalf).coerceAtMost(MITER_LIMIT)
            Rim(point, Vec2(bisector.x * scale, bisector.y * scale))
        }
    }

    /** The face contour, pulled in by the bevel's width. */
    private fun inset(contour: List<Vec2>, by: Float): List<Vec2> {
        val rims = rimsOf(contour) ?: return contour
        val moved = rims.map { Vec2(it.point.x - it.outward.x * by, it.point.y - it.outward.y * by) }
        // If the inset turned the contour inside out the stroke was thinner than the bevel, and the
        // honest answer is to leave the face where it was rather than emit a self-crossing outline.
        val before = Tessellator.signedArea(contour)
        val after = Tessellator.signedArea(moved)
        return if (before == 0f || after / before <= 0f) contour else moved
    }

    /** A bevel is as deep as it is wide, which is what makes a 45° chamfer the default shape. */
    private fun bevelDepth(bevel: Float) = bevel

    private fun profileAt(profile: Curve, t: Float): Float = profile.evaluate(t.coerceIn(0f, 1f))

    private fun normalise(x: Float, y: Float): Vec2 {
        val l = sqrt(x * x + y * y)
        return if (l < 1e-6f) Vec2(0f, 0f) else Vec2(x / l, y / l)
    }

    /**
     * A touch of forward lean in every bevel normal.
     *
     * Without it the ring at the outline has a normal exactly in the plane of the screen, so it
     * catches no light from a key light in front and reads as a black outline round the letter.
     */
    private const val BEVEL_FACE_BIAS = 0.15f

    private const val MITER_LIMIT = 4f
    private const val MIN_COS = 0.25f
    private const val MAX_SEGMENTS = 24
}
