package ir.pixellab.core.mesh

import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Vec2
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * The Persian bevel guard.
 *
 * What is asserted is the property the guard exists for rather than the numbers inside it: after
 * bevelling, a thin stroke still has a face, and a thick one still gets the whole bevel it asked
 * for. Both halves matter — a guard that protected thin strokes by quietly shrinking every bevel
 * would pass the first and fail the product.
 *
 * The shapes are deliberately the ones Persian type actually breaks on. A bar the width of a kashida,
 * a join between two bowls, and a counter close to an outer edge — each is a place where the local
 * thickness is a fraction of the letter's overall weight, and each is where a single global bevel
 * width goes through the letter rather than around it.
 */
class BevelGuardTest {

    // ---- the guard ---------------------------------------------------------------------------

    @Test
    fun `a bevel wider than the stroke is cut down to fit it`() {
        // A kashida: four units thick, and a bevel of five asked for. Unguarded that is more than
        // twice what the stroke can give, and half of five from each side leaves nothing at all.
        val kashida = subdivide(
            listOf(Vec2(0f, 0f), Vec2(60f, 0f), Vec2(60f, 4f), Vec2(0f, 4f)),
            every = 1f,
        )
        val rims = rimsOf(kashida)
        val limits = BevelGuard.limits(rims, listOf(kashida), requested = 5f)

        // Along the sides — the only direction a bevel can eat this stroke away — half the stroke
        // is two units, so nothing may reach further than that, and rather less, because the face
        // has to survive with some width rather than none.
        val onSide = limitNear(rims, limits, Vec2(30f, 0f))
        (onSide < 2f) shouldBe true
        (onSide > 0f) shouldBe true

        // The ends are a different question and deliberately not limited to the same figure: the
        // bevel on an end cap eats *along* the stroke, where sixty units are available, so holding
        // it to the sides' width would take a bevel away for no reason.
        (limitNear(rims, limits, Vec2(60f, 2f)) > onSide) shouldBe true
    }

    @Test
    fun `a stroke with room keeps the bevel it asked for`() {
        // The guard only ever takes width away. A shape with room everywhere must come out of it
        // unchanged, or every letter in the font quietly loses its bevel to a safety margin.
        val slab = listOf(Vec2(0f, 0f), Vec2(80f, 0f), Vec2(80f, 60f), Vec2(0f, 60f))
        val rims = rimsOf(slab)
        val limits = BevelGuard.limits(rims, listOf(slab), requested = 3f)

        for (limit in limits) closeTo(limit, 3f, 0.01f)
    }

    @Test
    fun `the limit follows the thickness along a letter that changes weight`() {
        // The shape a Persian letter actually is: a thick bowl on the right, a thin join running
        // left. One bevel width cannot serve both, which is the whole reason this exists.
        //
        //   ┌──────────┐
        //   │          │            bowl: 30 tall
        //   │          ├────────┐
        //   │          │        │   join: 4 tall
        //   │          ├────────┘
        //   └──────────┘
        // Subdivided, because the guard answers per outline point and the raw eight-point version
        // has no point in the middle of an edge to ask about — a real flattened glyph emits one
        // every few units.
        val letter = subdivide(
            listOf(
                Vec2(0f, 0f), Vec2(30f, 0f), Vec2(30f, 13f), Vec2(70f, 13f),
                Vec2(70f, 17f), Vec2(30f, 17f), Vec2(30f, 30f), Vec2(0f, 30f),
            ),
            every = 1f,
        )
        val rims = rimsOf(letter)
        val limits = BevelGuard.limits(rims, listOf(letter), requested = 4f)

        val onBowl = limitNear(rims, limits, Vec2(15f, 0f))
        val onJoin = limitNear(rims, limits, Vec2(60f, 13f))

        // The bowl has fifteen units of half-thickness and can afford everything asked for.
        closeTo(onBowl, 4f, 0.5f)
        // The join has two, so it gets appreciably less — and still gets something, because a join
        // with no bevel at all is a flat card edge next to a rounded one.
        (onJoin < onBowl * 0.75f) shouldBe true
        (onJoin > 0f) shouldBe true
    }

    @Test
    fun `the limit changes gradually rather than in a step`() {
        val letter = listOf(
            Vec2(0f, 0f), Vec2(30f, 0f), Vec2(30f, 13f), Vec2(70f, 13f),
            Vec2(70f, 17f), Vec2(30f, 17f), Vec2(30f, 30f), Vec2(0f, 30f),
        )
        val dense = subdivide(letter, every = 1f)
        val rims = rimsOf(dense)
        val limits = BevelGuard.limits(rims, listOf(dense), requested = 4f)

        // A step in bevel width is a facet running across the letter. Between neighbouring points a
        // unit apart, the width may not jump by more than a quarter of the bevel.
        var worst = 0f
        for (i in limits.indices) {
            val next = limits[(i + 1) % limits.size]
            worst = maxOf(worst, abs(next - limits[i]))
        }
        (worst < 1f) shouldBe true
    }

    // ---- the whole extrusion -----------------------------------------------------------------

    @Test
    fun `a guarded thin stroke keeps a face and an unguarded one loses it`() {
        // The end-to-end claim, and the reason for the feature. Same stroke, same bevel; the only
        // difference is the guard. Unguarded, the inset crosses itself and the extruder falls back
        // to leaving the face on the outline — a letter with no bevel at all. Guarded, the bevel
        // is real and the face survives it.
        val kashida = listOf(Vec2(0f, 0f), Vec2(60f, 0f), Vec2(60f, 4f), Vec2(0f, 4f))

        val guarded = Extruder.extrude(listOf(kashida), depth = 6f, bevelSize = 5f)
        val unguarded = Extruder.extrude(
            listOf(kashida),
            depth = 6f,
            bevelSize = 5f,
            protectThinStrokes = false,
        )

        // Both produce geometry — the failure being fixed was never a crash.
        (guarded.triangleCount > 0) shouldBe true
        (unguarded.triangleCount > 0) shouldBe true

        // The guarded face is inset, so it is narrower than the outline it came from. The
        // unguarded one gave up and left the face on the outline, so it is exactly as wide.
        val guardedFace = faceHeight(guarded)
        val unguardedFace = faceHeight(unguarded)
        (guardedFace > 0f) shouldBe true
        (guardedFace < 4f) shouldBe true
        closeTo(unguardedFace, 4f, 0.01f)
    }

    @Test
    fun `guarding a letter with room changes nothing`() {
        // The guard must be invisible where it is not needed, or it is a quality regression on
        // every Latin word and every heavy Persian display face.
        val slab = listOf(Vec2(0f, 0f), Vec2(80f, 0f), Vec2(80f, 60f), Vec2(0f, 60f))
        val guarded = Extruder.extrude(listOf(slab), depth = 8f, bevelSize = 3f)
        val unguarded = Extruder.extrude(
            listOf(slab),
            depth = 8f,
            bevelSize = 3f,
            protectThinStrokes = false,
        )
        closeTo(faceHeight(guarded), faceHeight(unguarded), 0.05f)
    }

    // ---- helpers -----------------------------------------------------------------------------

    /**
     * The rims the extruder would build, so a test can drive the guard directly.
     *
     * Rebuilt here rather than exposed from [Extruder], because the guard's contract is about rims
     * and a test that could only reach it through a whole extrusion could not tell a limit that was
     * wrong from a mesh that was assembled wrongly.
     */
    private fun rimsOf(contour: List<Vec2>): List<Rim> {
        val n = contour.size
        val counterClockwise = Tessellator.signedArea(contour) > 0f
        val points = if (counterClockwise) contour else contour.reversed()
        return List(n) { i ->
            val previous = points[(i + n - 1) % n]
            val point = points[i]
            val next = points[(i + 1) % n]
            val into = unit(point.x - previous.x, point.y - previous.y)
            val outOf = unit(next.x - point.x, next.y - point.y)
            val na = Vec2(into.y, -into.x)
            val nb = Vec2(outOf.y, -outOf.x)
            var bisector = unit(na.x + nb.x, na.y + nb.y)
            if (bisector.x == 0f && bisector.y == 0f) bisector = na
            val cosHalf = (bisector.x * na.x + bisector.y * na.y).coerceAtLeast(0.25f)
            val scale = (1f / cosHalf).coerceAtMost(4f)
            Rim(point, Vec2(bisector.x * scale, bisector.y * scale))
        }
    }

    private fun unit(x: Float, y: Float): Vec2 {
        val l = kotlin.math.sqrt(x * x + y * y)
        return if (l < 1e-6f) Vec2(0f, 0f) else Vec2(x / l, y / l)
    }

    /** The limit at whichever rim point sits closest to [near]. */
    private fun limitNear(rims: List<Rim>, limits: FloatArray, near: Vec2): Float {
        var best = 0
        var bestDistance = Float.MAX_VALUE
        for (i in rims.indices) {
            val dx = rims[i].point.x - near.x
            val dy = rims[i].point.y - near.y
            val d = dx * dx + dy * dy
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return limits[best]
    }

    /** Splits every edge so the outline is sampled at least this densely. */
    private fun subdivide(contour: List<Vec2>, every: Float): List<Vec2> {
        val out = ArrayList<Vec2>()
        for (i in contour.indices) {
            val a = contour[i]
            val b = contour[(i + 1) % contour.size]
            val length = kotlin.math.sqrt((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y))
            val steps = maxOf(1, kotlin.math.ceil(length / every).toInt())
            for (s in 0 until steps) {
                val t = s.toFloat() / steps
                out += Vec2(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
            }
        }
        return out
    }

    /**
     * How tall the front face of the mesh is.
     *
     * The face is the surface the bevel eats into, so its height is the direct measure of whether
     * the stroke survived. Read from the vertices marked [Surface.FACE] rather than from the mesh's
     * overall bounds, which include the outline the side walls still run along.
     */
    private fun faceHeight(mesh: Mesh): Float {
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (t in 0 until mesh.triangleCount) {
            if (mesh.surfaces[t] != Surface.FACE) continue
            for (k in 0 until 3) {
                val y = mesh.position(mesh.indices[t * 3 + k]).y
                if (y < min) min = y
                if (y > max) max = y
            }
        }
        return if (max < min) 0f else max - min
    }

    private fun closeTo(actual: Float, expected: Float, tolerance: Float) {
        (abs(actual - expected) <= tolerance) shouldBe true
    }
}
