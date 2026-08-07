package ir.pixellab.core.vector

import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Contour
import ir.pixellab.core.model.PathNode
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.Vec2
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * The four Pathfinder operations.
 *
 * Asserted on **area and membership** rather than on vertex lists. A boolean operation has many
 * correct outputs that differ in where the loop starts, which direction it winds and how many
 * collinear points survive — comparing vertices would test the implementation's habits instead of
 * its result, and would break on every harmless refactor.
 */
class PathfinderTest {

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

    /**
     * Covered area, measured by sampling.
     *
     * Not the shoelace formula. Shoelace has to be told which way a contour winds and what to do
     * with a hole, and a boolean result is entitled to wind either way — so a signed sum would be
     * testing the implementation's habits. Sampling asks the question the renderer asks: is this
     * point covered, under the same even-odd rule.
     */
    private fun area(path: ShapeGeometry.Path?): Float {
        if (path == null) return 0f
        val polygons = Pathfinder.polygonsOf(path)
        if (polygons.isEmpty()) return 0f
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (polygon in polygons) {
            for (p in polygon) {
                if (p.x < minX) minX = p.x
                if (p.y < minY) minY = p.y
                if (p.x > maxX) maxX = p.x
                if (p.y > maxY) maxY = p.y
            }
        }
        val step = 0.1f
        var covered = 0
        var x = minX + step / 2f
        while (x < maxX) {
            var y = minY + step / 2f
            while (y < maxY) {
                if (Pathfinder.contains(polygons, Vec2(x, y))) covered++
                y += step
            }
            x += step
        }
        return covered * step * step
    }

    private fun covers(path: ShapeGeometry.Path?, x: Float, y: Float): Boolean {
        if (path == null) return false
        return Pathfinder.contains(Pathfinder.polygonsOf(path), Vec2(x, y))
    }

    /** Two 10×10 squares overlapping in a 5×5 corner. */
    private val a = rect(0f, 0f, 10f, 10f)
    private val b = rect(5f, 5f, 15f, 15f)

    @Test
    fun `union is the area of both minus the part counted twice`() {
        val united = Pathfinder.combine(a, b, Pathfinder.Operation.UNITE)
        // 100 + 100 − 25.
        abs(area(united) - 175f) shouldBeLessThan 1f
    }

    @Test
    fun `union covers both shapes and nothing outside them`() {
        val united = Pathfinder.combine(a, b, Pathfinder.Operation.UNITE)
        covers(united, 2f, 2f) shouldBe true
        covers(united, 13f, 13f) shouldBe true
        covers(united, 7f, 7f) shouldBe true
        covers(united, 2f, 13f) shouldBe false
    }

    @Test
    fun `intersection is only the overlap`() {
        val overlap = Pathfinder.combine(a, b, Pathfinder.Operation.INTERSECT)
        abs(area(overlap) - 25f) shouldBeLessThan 1f
        covers(overlap, 7f, 7f) shouldBe true
        covers(overlap, 2f, 2f) shouldBe false
        covers(overlap, 13f, 13f) shouldBe false
    }

    @Test
    fun `subtraction takes the second shape out of the first`() {
        val cut = Pathfinder.combine(a, b, Pathfinder.Operation.SUBTRACT)
        abs(area(cut) - 75f) shouldBeLessThan 1f
        covers(cut, 2f, 2f) shouldBe true
        covers(cut, 7f, 7f) shouldBe false
    }

    @Test
    fun `subtraction is not symmetric`() {
        // The operation people get wrong first, and the reason the panel labels which is which.
        val ab = Pathfinder.combine(a, b, Pathfinder.Operation.SUBTRACT)
        val ba = Pathfinder.combine(b, a, Pathfinder.Operation.SUBTRACT)
        covers(ab, 2f, 2f) shouldBe true
        covers(ba, 2f, 2f) shouldBe false
        covers(ba, 13f, 13f) shouldBe true
    }

    @Test
    fun `exclude keeps what exactly one shape covers`() {
        val excluded = Pathfinder.combine(a, b, Pathfinder.Operation.EXCLUDE)
        // 175 − 25: the union without the overlap.
        abs(area(excluded) - 150f) shouldBeLessThan 2f
        covers(excluded, 2f, 2f) shouldBe true
        covers(excluded, 13f, 13f) shouldBe true
        covers(excluded, 7f, 7f) shouldBe false
    }

    @Test
    fun `disjoint shapes unite into two contours and intersect into nothing`() {
        val far = rect(50f, 50f, 60f, 60f)
        val united = Pathfinder.combine(a, far, Pathfinder.Operation.UNITE)
        abs(area(united) - 200f) shouldBeLessThan 1f
        covers(united, 2f, 2f) shouldBe true
        covers(united, 55f, 55f) shouldBe true

        // Null rather than an empty path: "no shape" and "a shape with no contours" are different
        // things to a caller, and the second renders as a layer nobody can select or delete.
        Pathfinder.combine(a, far, Pathfinder.Operation.INTERSECT) shouldBe null
    }

    @Test
    fun `subtracting a shape that swallows the first leaves nothing`() {
        val big = rect(-5f, -5f, 20f, 20f)
        Pathfinder.combine(a, big, Pathfinder.Operation.SUBTRACT) shouldBe null
    }

    @Test
    fun `subtracting from inside makes a hole, and the hole stays empty`() {
        // The even-odd rule earning its place: a shape with a counter — a letter O, a washer — must
        // keep its hole. Non-zero winding would fill it whenever the contours happened to agree.
        val hole = rect(3f, 3f, 7f, 7f)
        val washer = Pathfinder.combine(a, hole, Pathfinder.Operation.SUBTRACT)
        abs(area(washer) - 84f) shouldBeLessThan 1f
        covers(washer, 1f, 1f) shouldBe true
        covers(washer, 5f, 5f) shouldBe false
    }

    @Test
    fun `a shape combined with itself is unchanged by union and intersection`() {
        abs(area(Pathfinder.combine(a, a, Pathfinder.Operation.UNITE)) - 100f) shouldBeLessThan 2f
        abs(area(Pathfinder.combine(a, a, Pathfinder.Operation.INTERSECT)) - 100f) shouldBeLessThan 2f
    }

    @Test
    fun `an empty path is handled rather than crashing`() {
        val empty = ShapeGeometry.Path(emptyList())
        area(Pathfinder.combine(empty, a, Pathfinder.Operation.UNITE)) shouldBeGreaterThan 99f
        Pathfinder.combine(empty, a, Pathfinder.Operation.INTERSECT) shouldBe null
        area(Pathfinder.combine(a, empty, Pathfinder.Operation.SUBTRACT)) shouldBeGreaterThan 99f
    }

    @Test
    fun `shapes that only touch at an edge unite without losing area`() {
        // Collinear overlap is the classic degenerate case: the two shapes share a wall, and both
        // plausible answers for where to cut give the same rendered result.
        val touching = rect(10f, 0f, 20f, 10f)
        val united = Pathfinder.combine(a, touching, Pathfinder.Operation.UNITE)
        abs(area(united) - 200f) shouldBeLessThan 2f
    }

    @Test
    fun `an open contour is treated as closed rather than refused`() {
        // A boolean operation on an open path has no defined inside. Every vector editor closes it,
        // because that is what the user meant by drawing a nearly-closed shape.
        val open = ShapeGeometry.Path(
            listOf(
                Contour(
                    listOf(
                        PathNode(Vec2(5f, 5f)),
                        PathNode(Vec2(15f, 5f)),
                        PathNode(Vec2(15f, 15f)),
                        PathNode(Vec2(5f, 15f)),
                    ),
                    closed = false,
                ),
            ),
        )
        val overlap = Pathfinder.combine(a, open, Pathfinder.Operation.INTERSECT)
        abs(area(overlap) - 25f) shouldBeLessThan 2f
    }
}
