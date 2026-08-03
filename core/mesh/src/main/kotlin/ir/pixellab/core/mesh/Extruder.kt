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
     * @param bevelSize how far in from the outline the bevel reaches, at most. Where the letter is
     *   too thin to give up that much from both sides, [BevelGuard] quietly takes less.
     * @param bevelSegments rings across the bevel. Two is a chamfer, eight is a rounded edge; the
     *   difference is entirely in how the highlight travels.
     * @param protectThinStrokes measure the letter and limit the bevel per point rather than
     *   applying one width everywhere. On by default, and it should stay on for Persian: without it
     *   a bevel sized for a bowl passes straight through the join beside it. Turning it off is for
     *   comparing against the unguarded result, which is what the tests do.
     * @param marks how the letter's dots and vowel marks differ from its body. The default leaves
     *   them identical, which is what every other tool can do; see [MarkStyle].
     */
    fun extrude(
        contours: List<List<Vec2>>,
        depth: Float,
        bevelSize: Float = 0f,
        bevelProfile: Curve = Curve.LINEAR,
        bevelSegments: Int = 6,
        protectThinStrokes: Boolean = true,
        marks: MarkStyle = MarkStyle.FLUSH,
    ): Mesh {
        // Cleaned *here*, once, so that everything downstream is built on the same points.
        //
        // The caps and the walls have to agree vertex for vertex or the solid is not closed, and
        // they used to disagree: the tessellator dropped the flattener's redundant points and the
        // wall builder did not, so a cap was a coarse polygon while its wall followed every point
        // of the original curve. Nothing shared an edge. The result was a surface with some twenty
        // thousand boundary edges pretending to be a solid — invisible while the extrusion ran
        // straight back, since every gap was edge-on, and unmistakable the moment it leaned.
        //
        // It is also most of the cost. A five-letter word arrives with near ten thousand points and
        // describes itself perfectly well with a few hundred; the walls are two quads per point, so
        // this is the difference between forty thousand triangles and one thousand.
        val cleaned = contours.map { Tessellator.clean(it) }.filter { it.size >= 3 }
        if (cleaned.isEmpty()) return Mesh.EMPTY

        val builder = MeshBuilder()
        val bevel = bevelSize.coerceAtLeast(0f)
        val segments = if (bevel <= 0f) 0 else bevelSegments.coerceIn(1, MAX_SEGMENTS)

        // Which contours are dots and which are the letter. Decided once, against the whole set,
        // because the question "is this inside another contour" cannot be answered by one alone.
        val ornament = if (marks.separated) Ornaments.classify(cleaned) else BooleanArray(cleaned.size)

        // Measured against the whole letter rather than against one contour at a time, because a
        // stroke's thickness is decided by every outline near it: the wall between a counter and the
        // outside is thin on account of where *both* run, and measuring against one alone would
        // report it as solid.
        val prepared = cleaned.mapIndexedNotNull { index, contour ->
            val rims = rimsOf(contour) ?: return@mapIndexedNotNull null
            val limits = when {
                bevel <= 0f -> FloatArray(rims.size)
                !protectThinStrokes -> FloatArray(rims.size) { bevel }
                else -> BevelGuard.limits(rims, cleaned, bevel)
            }
            Bevelled(rims, limits, ornament[index])
        }
        if (prepared.isEmpty()) return Mesh.EMPTY

        // The face sits at the front of the bevel, inset by the bevel's width *at each point*.
        // Tessellating the original outline and *then* insetting would leave the face and the bevel
        // disagreeing about where the letter's edge is, which shows as a hairline crack all round.
        val faceContours = if (bevel <= 0f) cleaned else prepared.map { inset(it) }
        val faceZ = if (bevel <= 0f) 0f else bevelDepth(bevel)

        // Body and ornaments are capped separately because they sit at different depths. Tessellating
        // them together would be cheaper and would put every dot back on the body's plane, which is
        // the whole thing being avoided.
        addCaps(builder, faceContours, prepared, ornament, faceZ, depth, marks, front = true)
        addCaps(builder, cleaned, prepared, ornament, faceZ, depth, marks, front = false)

        for (contour in prepared) {
            builder.building = contour.mark
            // A lifted dot's wall runs from its own front face down to its own back, wherever the
            // lift has put both — the body's depth says nothing about how thick a dot is.
            val lift = if (contour.mark) depth * marks.lift else 0f
            val thickness = if (contour.mark) depth * marks.depth else depth
            val rings = buildRings(contour, segments, bevelProfile, bevelDepth(bevel), lift)
            stitch(builder, rings, thickness, lift)
        }
        builder.building = false
        return builder.build()
    }

    /**
     * Caps the body and the ornaments, each at its own depth.
     *
     * Split into two passes over the same list rather than one, because the front cap of a dot and
     * the front cap of the letter are at different z once the dots are lifted, and a tessellation is
     * flat by construction.
     */
    private fun addCaps(
        builder: MeshBuilder,
        contours: List<List<Vec2>>,
        prepared: List<Bevelled>,
        ornament: BooleanArray,
        faceZ: Float,
        depth: Float,
        marks: MarkStyle,
        front: Boolean,
    ) {
        for (isMark in booleanArrayOf(false, true)) {
            val subset = contours.filterIndexed { i, _ -> ornament.getOrElse(i) { false } == isMark }
            if (subset.isEmpty()) continue
            builder.building = isMark

            val lift = if (isMark) depth * marks.lift else 0f
            val thickness = if (isMark) depth * marks.depth else depth
            val z = if (front) faceZ + lift else lift - thickness

            addCap(
                builder = builder,
                contours = subset,
                z = z,
                front = front,
                surface = if (front) Surface.FACE else Surface.BACK,
            )
        }
        builder.building = false
    }

    /** One outline, with how far the bevel may reach at each of its points. */
    private class Bevelled(val rims: List<Rim>, val limits: FloatArray, val mark: Boolean = false)

    /**
     * The rings the side of a letter is made of: the bevel's, then the back.
     *
     * Each ring carries its own normal per point rather than one normal for the whole ring, because
     * a corner of a letter is where two edges meet at an angle and the normal has to turn with them.
     */
    private fun buildRings(
        contour: Bevelled,
        segments: Int,
        profile: Curve,
        depth: Float,
        lift: Float,
    ): List<List<Pair<Vec3, Vec3>>> {
        val rims = contour.rims
        val rings = ArrayList<List<Pair<Vec3, Vec3>>>(segments + 2)

        for (step in segments downTo 0) {
            val t = if (segments == 0) 0f else step.toFloat() / segments
            // t = 1 is the outline itself, t = 0 is where the bevel meets the face.
            //
            // The inward reach varies per point and the forward one does not, which makes the bevel
            // steeper wherever the guard has taken width away. That asymmetry is deliberate: the
            // face has to stay in one plane — it is the front surface of the letter, and a face that
            // wandered in z would shade as a dent — so the depth is what stays fixed. A steep
            // micro-bevel on a thin join still turns its normal through the full sweep below, which
            // is what keeps a highlight on it rather than leaving it black.
            val forward = lift + depth * profileAt(profile, 1f - t)
            rings += rims.mapIndexed { index, rim ->
                val inward = contour.limits[index] * (1f - t)
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
            Vec3(rim.point.x, rim.point.y, lift) to Vec3(rim.outward.x, rim.outward.y, 0f)
        }
        return rings
    }

    private fun stitch(
        builder: MeshBuilder,
        rings: List<List<Pair<Vec3, Vec3>>>,
        depth: Float,
        lift: Float,
    ) {
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
        val back = rings.last().map { builder.vertex(Vec3(it.first.x, it.first.y, lift - depth), it.second) }
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

    /**
     * The face contour, pulled in by the bevel's width at each point.
     *
     * The area check at the end is a net under the guard rather than the guard itself. With
     * [BevelGuard] on it should never fire, because no point is allowed to reach past the middle of
     * its own stroke; it stays because the guard can be turned off, and because an outline that was
     * already self-intersecting in the font survives no amount of correct arithmetic. Falling back
     * to the original leaves a letter with no bevel, which is worse than intended and far better
     * than a face turned inside out.
     */
    private fun inset(contour: Bevelled): List<Vec2> {
        val original = contour.rims.map { it.point }
        val moved = contour.rims.mapIndexed { index, rim ->
            val by = contour.limits[index]
            Vec2(rim.point.x - rim.outward.x * by, rim.point.y - rim.outward.y * by)
        }
        val before = Tessellator.signedArea(original)
        val after = Tessellator.signedArea(moved)
        return if (before == 0f || after / before <= 0f) original else moved
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
