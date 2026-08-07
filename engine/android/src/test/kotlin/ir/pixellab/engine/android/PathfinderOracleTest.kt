package ir.pixellab.engine.android

import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Contour
import ir.pixellab.core.model.PathNode
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.Vec2
import ir.pixellab.core.vector.Pathfinder as CpuPathfinder
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Skia Pathfinder against an independent implementation.
 *
 * `PathOperation` has been wired to `android.graphics.Path.op` since wave 6 and **no test in this
 * project has ever checked what it produces** — only that the right shapes reached it. That is the
 * same gap `Adjust` was written to close for the adjustment shader, and it has the same shape: a
 * boolean operation that returns the wrong region returns it in a perfectly well-formed path.
 *
 * So `core:vector`'s `Pathfinder` is the oracle. It is a completely different algorithm — flatten,
 * split at crossings, keep by membership, stitch — written without reference to Skia's, which is
 * what makes agreement between them evidence rather than coincidence. It is also the reason the
 * pure-Kotlin core can answer a boolean question at all, without Android present.
 *
 * Compared on **coverage**, not on vertices. Two correct results differ in where a loop starts,
 * which way it winds, and how many collinear points survive; only the region is the answer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PathfinderOracleTest {

    private fun rect(left: Float, top: Float, right: Float, bottom: Float) =
        ShapeGeometry.Path(
            listOf(
                Contour(
                    listOf(
                        PathNode(Vec2(left, top)),
                        PathNode(Vec2(right, top)),
                        PathNode(Vec2(right, bottom)),
                        PathNode(Vec2(left, bottom)),
                    ),
                    closed = true,
                ),
            ),
        )

    private fun triangle(cx: Float, cy: Float, r: Float) =
        ShapeGeometry.Path(
            listOf(
                Contour(
                    listOf(
                        PathNode(Vec2(cx, cy - r)),
                        PathNode(Vec2(cx + r, cy + r)),
                        PathNode(Vec2(cx - r, cy + r)),
                    ),
                    closed = true,
                ),
            ),
        )

    /** Fraction of sampled points where the two implementations disagree about coverage. */
    private fun disagreement(
        a: ShapeGeometry.Path,
        b: ShapeGeometry.Path,
        skia: PathOperation,
        cpu: CpuPathfinder.Operation,
    ): Float {
        val fromSkia = Pathfinder.apply(listOf(a, b), skia)
        val fromCpu = CpuPathfinder.combine(a, b, cpu)

        val skiaPolygons = CpuPathfinder.polygonsOf(fromSkia)
        val cpuPolygons = fromCpu?.let { CpuPathfinder.polygonsOf(it) } ?: emptyList()

        var differing = 0
        var total = 0
        var x = -10f
        while (x <= 30f) {
            var y = -10f
            while (y <= 30f) {
                val point = Vec2(x, y)
                // Points within a hair of either outline are skipped: the two implementations may
                // legitimately place a boundary a tolerance apart, and a sample sitting exactly on
                // it is not evidence of a wrong region.
                if (!nearAnyEdge(skiaPolygons, point) && !nearAnyEdge(cpuPolygons, point)) {
                    val inSkia = CpuPathfinder.contains(skiaPolygons, point)
                    val inCpu = CpuPathfinder.contains(cpuPolygons, point)
                    if (inSkia != inCpu) differing++
                    total++
                }
                y += 0.5f
            }
            x += 0.5f
        }
        return if (total == 0) 0f else differing.toFloat() / total
    }

    private fun nearAnyEdge(polygons: List<List<Vec2>>, point: Vec2): Boolean {
        for (polygon in polygons) {
            for (i in polygon.indices) {
                val a = polygon[i]
                val b = polygon[(i + 1) % polygon.size]
                if (distanceToSegment(a, b, point) < EDGE_MARGIN) return true
            }
        }
        return false
    }

    private fun distanceToSegment(a: Vec2, b: Vec2, p: Vec2): Float {
        val vx = b.x - a.x
        val vy = b.y - a.y
        val lengthSquared = vx * vx + vy * vy
        if (lengthSquared < 1e-6f) return kotlin.math.hypot(p.x - a.x, p.y - a.y)
        val t = (((p.x - a.x) * vx + (p.y - a.y) * vy) / lengthSquared).coerceIn(0f, 1f)
        return kotlin.math.hypot(p.x - (a.x + vx * t), p.y - (a.y + vy * t))
    }

    private val overlapping = rect(0f, 0f, 10f, 10f) to rect(5f, 5f, 15f, 15f)
    private val nested = rect(0f, 0f, 20f, 20f) to rect(5f, 5f, 15f, 15f)
    private val disjoint = rect(0f, 0f, 8f, 8f) to rect(12f, 12f, 20f, 20f)
    private val mixed = rect(0f, 0f, 14f, 14f) to triangle(10f, 10f, 8f)

    private fun everyPair() = listOf(overlapping, nested, disjoint, mixed)

    @Test
    fun `union agrees with the oracle on every pair`() {
        for ((a, b) in everyPair()) {
            (disagreement(a, b, PathOperation.UNITE, CpuPathfinder.Operation.UNITE) < TOLERANCE) shouldBe true
        }
    }

    @Test
    fun `intersection agrees with the oracle on every pair`() {
        for ((a, b) in everyPair()) {
            (disagreement(a, b, PathOperation.INTERSECT, CpuPathfinder.Operation.INTERSECT) < TOLERANCE) shouldBe true
        }
    }

    @Test
    fun `subtraction agrees with the oracle on every pair`() {
        for ((a, b) in everyPair()) {
            (disagreement(a, b, PathOperation.MINUS_FRONT, CpuPathfinder.Operation.SUBTRACT) < TOLERANCE) shouldBe true
        }
    }

    @Test
    fun `exclude agrees with the oracle on every pair`() {
        for ((a, b) in everyPair()) {
            (disagreement(a, b, PathOperation.EXCLUDE, CpuPathfinder.Operation.EXCLUDE) < TOLERANCE) shouldBe true
        }
    }

    @Test
    fun `subtracting an enclosing shape leaves nothing in either implementation`() {
        // The case where the two could most easily disagree about *nothing*: Skia returns an empty
        // path and the oracle returns null, and a caller that treated one as "unchanged" would
        // silently leave the original shape on the canvas.
        val small = rect(5f, 5f, 10f, 10f)
        val big = rect(0f, 0f, 20f, 20f)
        Pathfinder.apply(listOf(small, big), PathOperation.MINUS_FRONT).contours.isEmpty() shouldBe true
        CpuPathfinder.combine(small, big, CpuPathfinder.Operation.SUBTRACT) shouldBe null
    }

    /** Half a per cent of samples, which is float noise at a boundary rather than a wrong region. */
    private companion object {
        const val TOLERANCE = 0.005f
        const val EDGE_MARGIN = 0.35f
    }
}
