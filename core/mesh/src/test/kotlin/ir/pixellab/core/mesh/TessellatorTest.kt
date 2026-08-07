package ir.pixellab.core.mesh

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Vec2
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Turning outlines into triangles.
 *
 * The measure used throughout is **total triangle area**, because it is the one number that catches
 * every way a tessellator goes wrong at once: a missing triangle makes it too small, an overlapping
 * one too large, and filling a hole in solid too large by exactly the hole.
 */
class TessellatorTest {

    private fun square(size: Float, at: Vec2 = Vec2(0f, 0f)) = listOf(
        Vec2(at.x, at.y),
        Vec2(at.x + size, at.y),
        Vec2(at.x + size, at.y + size),
        Vec2(at.x, at.y + size),
    )

    private fun area(t: Triangulation): Float {
        var total = 0f
        for (i in 0 until t.triangleCount) {
            val a = t.vertices[t.indices[i * 3]]
            val b = t.vertices[t.indices[i * 3 + 1]]
            val c = t.vertices[t.indices[i * 3 + 2]]
            total += abs((b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)) / 2f
        }
        return total
    }

    @Test
    fun `a square becomes two triangles covering it exactly`() {
        val result = Tessellator.triangulate(listOf(square(10f)))
        result.triangleCount shouldBe 2
        area(result) shouldBe 100f.plusOrMinus(0.01f)
    }

    @Test
    fun `winding does not matter`() {
        // A font's outlines arrive in whichever direction the designer drew them, and half of them
        // are the other way round from the other half.
        val clockwise = Tessellator.triangulate(listOf(square(10f).reversed()))
        area(clockwise) shouldBe 100f.plusOrMinus(0.01f)
    }

    @Test
    fun `a hole is cut out rather than filled in`() {
        // The counter of a ه, in miniature. A tessellator that ignored winding fills this solid and
        // every letter with a bowl comes out as a blob — and it looks deliberate enough that it is
        // easy to blame the font.
        val outer = square(10f)
        val inner = square(4f, Vec2(3f, 3f)).reversed()
        val result = Tessellator.triangulate(listOf(outer, inner))

        // 100 minus the hole's 16.
        area(result) shouldBe 84f.plusOrMinus(0.5f)
    }

    @Test
    fun `two separate outlines are both filled`() {
        // The two bowls of a ۸, or the dots of a پ: separate contours, neither inside the other.
        val result = Tessellator.triangulate(listOf(square(4f), square(4f, Vec2(20f, 0f))))
        area(result) shouldBe 32f.plusOrMinus(0.1f)
    }

    @Test
    fun `a concave outline is not bridged across its own notch`() {
        // An L. A convex-hull tessellator fills the notch and the letter loses its shape, which is
        // exactly what an ear-clipper must not do.
        val l = listOf(
            Vec2(0f, 0f), Vec2(10f, 0f), Vec2(10f, 3f),
            Vec2(3f, 3f), Vec2(3f, 10f), Vec2(0f, 10f),
        )
        val result = Tessellator.triangulate(listOf(l))
        // 30 for the foot plus 21 for the upright, not the hull's 100.
        area(result) shouldBe 51f.plusOrMinus(0.5f)
    }

    @Test
    fun `duplicate points do not stall the clip`() {
        // Flattening a curve emits a point per step and the last of one segment lands on the first
        // of the next. A zero-length edge has no direction, which makes the ear test undecidable.
        val doubled = listOf(
            Vec2(0f, 0f), Vec2(0f, 0f), Vec2(10f, 0f), Vec2(10f, 0f),
            Vec2(10f, 10f), Vec2(0f, 10f), Vec2(0f, 10f),
        )
        area(Tessellator.triangulate(listOf(doubled))) shouldBe 100f.plusOrMinus(0.01f)
    }

    @Test
    fun `a degenerate contour produces nothing rather than throwing`() {
        Tessellator.triangulate(listOf(listOf(Vec2(0f, 0f), Vec2(1f, 1f)))).triangleCount shouldBe 0
        Tessellator.triangulate(emptyList()).triangleCount shouldBe 0
    }

    @Test
    fun `signed area carries the winding`() {
        (Tessellator.signedArea(square(4f)) > 0f) shouldBe true
        (Tessellator.signedArea(square(4f).reversed()) < 0f) shouldBe true
    }

    @Test
    fun `containment finds a point inside and rejects one outside`() {
        Tessellator.contains(square(10f), Vec2(5f, 5f)) shouldBe true
        Tessellator.contains(square(10f), Vec2(15f, 5f)) shouldBe false
    }

    @Test
    fun `a nested hole inside a second outline goes to the right one`() {
        // Two letters side by side, one of which has a counter. Assigning that counter to the wrong
        // outline cuts a hole out of the letter next to it.
        val first = square(10f)
        val second = square(10f, Vec2(30f, 0f))
        val counter = square(4f, Vec2(33f, 3f)).reversed()

        val result = Tessellator.triangulate(listOf(first, second, counter))
        area(result) shouldBe 184f.plusOrMinus(1f)
    }
}
