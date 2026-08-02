package ir.pixellab.core.mesh

import ir.pixellab.core.model.Vec2
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Stops a bevel from destroying a thin Persian stroke.
 *
 * The problem, stated exactly. A bevel of width *b* eats *b* inward from **both** sides of a stroke,
 * so a stroke of width *w* keeps a flat face only while `2b < w`. Latin type has one stem weight and
 * a single global bevel is therefore safe. Persian type does not: within one letter the bowl may be
 * five times the thickness of the join beside it, and a kashida stretched to justify a line is
 * thinner still. A bevel chosen for the bowl passes straight through the join, the inset outline
 * crosses itself, and the letter comes out with a stroke that is a ridge — no face, no highlight,
 * and a bright crease where the crossed outline tessellates on top of itself.
 *
 * Every 3D tool the reference research covers has this defect, which is why Iranian designers convert
 * text to outlines and unite it in Illustrator before importing. This is the fix, and it is the piece
 * of the specification marked *certain to break if unsolved*.
 *
 * **The rule is one line:** at each point of the outline, the bevel may reach at most half way
 * across the stroke it sits on.
 *
 * The user never sees any of it. They move one slider, and the letter survives.
 */
object BevelGuard {

    /**
     * How far the bevel may reach inward at each rim point.
     *
     * @param rims the outline's points and their outward directions, in order. They must be sampled
     *   at least as finely as the bevel is wide — a long edge with no point on it has nothing to
     *   limit, and it is the first thing that goes wrong here.
     * @param contours every contour of the letter, this one included. All of them, because the wall
     *   between a counter and the outside is thin on account of where *both* run, and measuring
     *   against one alone would report it as solid.
     * @param requested the bevel width the user asked for. No result ever exceeds it — this only
     *   ever takes width away, so a letter with room everywhere is bevelled exactly as asked.
     */
    internal fun limits(rims: List<Rim>, contours: List<List<Vec2>>, requested: Float): FloatArray {
        val n = rims.size
        if (n == 0 || requested <= 0f) return FloatArray(n)

        // Nothing past this can change any answer, so it is also how far a ray is cast.
        val reach = requested / SAFETY * 2f
        val (arc, perimeter) = arcLengths(rims)

        // Measured far more finely than it is applied, and the split between those two is what
        // makes this work at all.
        //
        // The outline is where the answer has to *land*, because the extruder offsets its points.
        // But the outline is a terrible place to *take* the measurement: a font emits two points
        // for a straight stem however long it is, so an edge running the length of a thin arm has
        // nothing on it to measure, and the arm's two ends — which sit on the thick bowl at one
        // side and the thin cap at the other — report all the room in the world.
        //
        // An earlier attempt fixed that by resampling the outline itself, and it cost more than it
        // bought: offsetting a resampled corner folds the outline back on itself for about a
        // bevel's width, because the corner's own miter travels `b·√2` while its new neighbours
        // travel `b`. Ear clipping cannot triangulate a folded outline and quietly returns a
        // fraction of it, which renders as a letter with no front. Measuring finely and applying
        // coarsely avoids the whole class of problem: no point is added, so no point can fold.
        val samples = probe(rims, arc, contours, requested, reach)

        // Each point takes the smallest room found anywhere near it, plus an allowance for how far
        // away that measurement was.
        //
        // The smallest rather than its own, because a bevel is not a local event: it removes
        // material along a band of the outline roughly its own width, so a point with room to spare
        // one bevel-width from a point without still has its face cut away by its neighbour's
        // bevel. This is what makes the guard hold along a join rather than only at the one
        // cross-section that happens to have a vertex on it.
        //
        // The allowance is what turns that into a taper instead of a cliff. A plain minimum over a
        // fixed window gives a plateau with a hard step at each end, and a step in bevel width is a
        // facet running across the letter — visible, and on a curve it catches light like a crease.
        // Letting the width grow by at most [TAPER] per unit travelled along the outline bounds the
        // slope of the bevel surface directly, which is the property that was actually wanted; the
        // window falls out of it, since a measurement further away than [reach] can no longer bind.
        val bind = requested / TAPER
        return FloatArray(n) { i ->
            var smallest = requested
            for (s in samples.indices) {
                val away = separation(arc[i], samples[s].at, perimeter)
                if (away > bind) continue
                val allowed = samples[s].room + away * TAPER
                if (allowed < smallest) smallest = allowed
            }
            smallest
        }
    }

    /** One measurement: how much room there is, and where along the outline it was taken. */
    private class Probe(val at: Float, val room: Float)

    /**
     * Measures the letter along its whole outline, at every vertex and between them.
     *
     * Between them at no more than a fraction of a bevel apart, which is the sampling rate the
     * *answer* needs even though the geometry is left alone. Along an edge the inward direction is
     * that edge's own normal rather than a bisector — a bisector belongs to a corner, and using one
     * along a straight would aim the ray off at an angle and measure a longer stroke than there is.
     */
    private fun probe(
        rims: List<Rim>,
        arc: FloatArray,
        contours: List<List<Vec2>>,
        requested: Float,
        reach: Float,
    ): List<Probe> {
        val n = rims.size
        val spacing = requested / SAMPLES_PER_BEVEL
        val out = ArrayList<Probe>(n * 2)

        for (i in 0 until n) {
            val a = rims[i]
            val b = rims[(i + 1) % n]

            // The vertex, along its bisector.
            val length = sqrt(a.outward.x * a.outward.x + a.outward.y * a.outward.y)
            if (length > 1e-6f) {
                out += Probe(
                    arc[i],
                    room(a.point, -a.outward.x / length, -a.outward.y / length, contours, requested, reach),
                )
            }

            // The edge, along its own normal. The outline is counter-clockwise by the time it gets
            // here, so the material is to the left of travel and the inward normal is (-dy, dx).
            val ex = b.point.x - a.point.x
            val ey = b.point.y - a.point.y
            val edge = sqrt(ex * ex + ey * ey)
            if (edge < 1e-6f) continue
            val inX = -ey / edge
            val inY = ex / edge
            // Ends included, so each vertex is measured along both of its edges as well as along
            // its bisector. Without them the narrowest cross-section of a short arm is only ever
            // read a sample's width away from the vertex that has to act on it, and the taper hands
            // back most of what the guard just took — the arm was left a face a tenth of its width
            // instead of a third.
            val steps = max(1, ceil(edge / spacing).toInt())
            for (s in 0..steps) {
                val t = s.toFloat() / steps
                out += Probe(
                    arc[i] + edge * t,
                    room(
                        Vec2(a.point.x + ex * t, a.point.y + ey * t),
                        inX,
                        inY,
                        contours,
                        requested,
                        reach,
                    ),
                )
            }
        }
        return out
    }

    /**
     * Half the stroke's thickness at one point, capped at what was asked for.
     *
     * Thickness measured as the **chord**: how far a ray along the inward normal travels before it
     * leaves the letter. That is the material the bevel actually eats through, and measuring it any
     * other way was the mistake that took two attempts to find.
     *
     * The attempt before this one built a distance field and read the largest circle that fits at
     * each point. That is a real and useful quantity — it is the medial-axis radius — and it is the
     * wrong one here, because it is the distance to the nearest boundary in *any* direction. Four
     * units from the left edge of a bowl a hundred units tall it reports four; the bevel eating
     * upward has a hundred units of room and was told it had four, so every letter came out with a
     * visible dip in its bevel around each corner. A chord is directional, which is what the
     * question actually is.
     */
    private fun room(
        origin: Vec2,
        dx: Float,
        dy: Float,
        contours: List<List<Vec2>>,
        requested: Float,
        reach: Float,
    ): Float {
        val chord = cast(origin, dx, dy, contours, reach)

        // Half the stroke is the mathematical limit, where the two bevels meet at a knife edge and
        // the face has exactly zero area. The safety factor is what leaves the stroke a face to
        // catch light on — without it a thin join renders as a crease rather than as a stroke.
        return min(requested, chord * 0.5f * SAFETY)
    }

    /**
     * Distance from [origin] along a unit direction to the first outline crossed, capped at [reach].
     *
     * A plain segment-by-segment cast with a bounding-box reject in front of it. A glyph after
     * resampling is a couple of thousand points and this runs once per extrusion rather than per
     * frame, so the reject is enough to keep it off the profile; an acceleration structure would be
     * more code than the problem is asking for.
     */
    private fun cast(
        origin: Vec2,
        dx: Float,
        dy: Float,
        contours: List<List<Vec2>>,
        reach: Float,
    ): Float {
        val endX = origin.x + dx * reach
        val endY = origin.y + dy * reach
        val loX = min(origin.x, endX)
        val hiX = max(origin.x, endX)
        val loY = min(origin.y, endY)
        val hiY = max(origin.y, endY)

        // Edges nearer than this to the origin are the ones the ray starts on.
        //
        // Excluding them by distance along the ray does not work, and the way it fails is worth
        // recording. The ray leaves a point that lies exactly on two edges, so both cross it at
        // zero — in exact arithmetic. In floats at glyph coordinates the cross products are
        // products of numbers in the hundreds, so their error is around a thousandth, and the
        // crossing lands just far enough along the ray to look real. Every point then measured its
        // own edge as the far wall of a stroke a thousandth of a unit thick, every bevel was
        // limited to nothing, and the letter rendered with no face at all.
        val touching = reach * TOUCHING
        var nearest = reach
        for (contour in contours) {
            val size = contour.size
            if (size < 2) continue
            for (i in 0 until size) {
                val a = contour[i]
                val b = contour[(i + 1) % size]
                // Cheap reject first. Most edges of a glyph are nowhere near any given ray, and the
                // four comparisons cost a fraction of the divide below.
                if (max(a.x, b.x) < loX || min(a.x, b.x) > hiX) continue
                if (max(a.y, b.y) < loY || min(a.y, b.y) > hiY) continue
                if (near(a, origin, touching) || near(b, origin, touching)) continue

                val ex = b.x - a.x
                val ey = b.y - a.y
                val denominator = dx * ey - dy * ex
                // Parallel. A ray running along an edge has no crossing to report, and the edges
                // either side of it answer for that stretch of the outline.
                if (denominator > -PARALLEL && denominator < PARALLEL) continue

                val ox = a.x - origin.x
                val oy = a.y - origin.y
                val t = (ox * ey - oy * ex) / denominator
                if (t <= START || t >= nearest) continue
                val u = (ox * dy - oy * dx) / denominator
                if (u < 0f || u > 1f) continue
                nearest = t
            }
        }
        return nearest
    }

    private fun near(point: Vec2, origin: Vec2, tolerance: Float): Boolean {
        val dx = point.x - origin.x
        val dy = point.y - origin.y
        return dx * dx + dy * dy < tolerance * tolerance
    }

    /**
     * Where each point sits along the outline, and how long the outline is all the way round.
     *
     * The closing edge is measured rather than estimated from the average step. Estimating it is a
     * quiet disaster: the wrap-around distance is `perimeter − direct`, so a perimeter that comes
     * out short makes that difference *negative*, distances between points on the last edge and the
     * first go negative, and a limit computed from one comes out below zero. A negative bevel width
     * turns the letter inside out.
     */
    private fun arcLengths(rims: List<Rim>): Pair<FloatArray, Float> {
        val n = rims.size
        val arc = FloatArray(n)
        var total = 0f
        for (i in 1 until n) {
            val a = rims[i - 1].point
            val b = rims[i].point
            total += sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y))
            arc[i] = total
        }
        val last = rims[n - 1].point
        val first = rims[0].point
        val closing = sqrt((first.x - last.x) * (first.x - last.x) + (first.y - last.y) * (first.y - last.y))
        return arc to total + closing
    }

    /** Distance between two positions along the outline, the short way round. */
    private fun separation(a: Float, b: Float, perimeter: Float): Float {
        val direct = abs(a - b)
        return if (perimeter <= 0f) direct else min(direct, perimeter - direct)
    }

    /**
     * How far along the ray a crossing has to be to count.
     *
     * The ray starts *on* the outline, so the two edges meeting at its origin cross it at zero. A
     * tolerance rather than an exact zero because the origin is a stored float and the edge is
     * rebuilt from two more.
     */
    private const val START = 1e-4f

    /**
     * How close a vertex has to be to the ray's origin to count as the same point.
     *
     * A thousandth of the ray's reach, which is far below the spacing of a resampled outline — the
     * resampler puts points half a bevel apart and the reach is nearly three bevels — so this can
     * never swallow a genuine neighbouring vertex.
     */
    private const val TOUCHING = 1e-3f

    private const val PARALLEL = 1e-9f

    /**
     * What fraction of half the stroke a bevel may take.
     *
     * At 1.0 the bevels from the two sides meet exactly and the face is a line — allowed by the
     * arithmetic, and in practice a stroke that reads as a crease instead of a stroke. Seven tenths
     * leaves the thinnest join a face about a third of its width, which is enough to hold a
     * highlight and read as a surface.
     */
    private const val SAFETY = 0.7f

    /**
     * How many measurements are taken across one bevel's width of outline.
     *
     * Four, which puts a sample every quarter of a bevel. Finer than that measures the same stroke
     * repeatedly; coarser lets a narrow waist slip between two samples, and a waist that slips
     * through is the whole failure this exists to prevent.
     */
    private const val SAMPLES_PER_BEVEL = 4f

    /**
     * How fast the bevel may widen as the outline leaves a thin place, per unit of arc length.
     *
     * A half, so the width recovers over about two units of outline for every unit of bevel. Steeper
     * and the taper reads as a step; gentler and a bevel is still climbing back at the far end of a
     * short stroke, which quietly narrows bevels the letter could have afforded.
     */
    private const val TAPER = 0.5f
}
