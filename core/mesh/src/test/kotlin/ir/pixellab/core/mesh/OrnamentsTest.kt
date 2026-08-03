package ir.pixellab.core.mesh

import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Vec2
import org.junit.jupiter.api.Test

/**
 * Telling a dot from a body and from a counter.
 *
 * The three fixtures are the three cases, and the middle one is the one that would do real damage:
 * mistaking the hole in a ه for a dot and floating it away from the letter. A missed dot is a
 * missing feature; a floated counter is a broken glyph.
 */
class OrnamentsTest {

    private fun box(x: Float, y: Float, w: Float, h: Float) =
        listOf(Vec2(x, y), Vec2(x + w, y), Vec2(x + w, y + h), Vec2(x, y + h))

    @Test
    fun `a small free-standing contour is a dot`() {
        // The shape of a ب: a wide shallow body with one small dot below it, unattached.
        val body = box(0f, 20f, 80f, 30f)
        val dot = box(38f, 4f, 8f, 8f)

        Ornaments.classify(listOf(body, dot)).toList() shouldBe listOf(false, true)
    }

    @Test
    fun `three dots are all dots`() {
        // ث and ش carry three. Nothing about the rule depends on there being one.
        val body = box(0f, 20f, 80f, 30f)
        val dots = listOf(box(30f, 56f, 8f, 8f), box(42f, 56f, 8f, 8f), box(36f, 66f, 8f, 8f))

        Ornaments.classify(listOf(body) + dots).toList() shouldBe listOf(false, true, true, true)
    }

    @Test
    fun `a counter is never a dot however small it is`() {
        // The hole in a ه, و, ق, ف, م or ط. It is small and free of the body's outline in every
        // sense except the one that matters: it is *inside* it. Floating it would open a hole in
        // the letter and hang its middle in mid-air.
        val body = box(0f, 0f, 80f, 80f)
        val counter = box(36f, 36f, 8f, 8f)

        Ornaments.classify(listOf(body, counter)).toList() shouldBe listOf(false, false)
    }

    @Test
    fun `a letter with no dots has no ornaments`() {
        Ornaments.classify(listOf(box(0f, 0f, 60f, 60f))).toList() shouldBe listOf(false)
    }

    @Test
    fun `two bodies of similar size are both bodies`() {
        // Two clusters extruded together — «سلا» and «م» — are two large contours and neither is an
        // ornament. A rule that only looked at "is it the largest" would have called one of them a
        // dot and floated half the word away.
        val a = box(0f, 0f, 60f, 40f)
        val b = box(70f, 0f, 50f, 40f)

        Ornaments.classify(listOf(a, b)).toList() shouldBe listOf(false, false)
    }

    // ---- the extrusion -------------------------------------------------------------------------

    private fun letter() = listOf(
        box(0f, 20f, 80f, 30f),
        box(38f, 4f, 8f, 8f),
    )

    @Test
    fun `a flush style leaves the dot where the body is`() {
        // The guarantee that makes this an addition rather than a change: every document that never
        // asked for a mark style renders exactly as it did.
        val plain = Extruder.extrude(letter(), depth = 10f, bevelSize = 2f)
        val flush = Extruder.extrude(letter(), depth = 10f, bevelSize = 2f, marks = MarkStyle.FLUSH)

        plain.triangleCount shouldBe flush.triangleCount
        depthRange(plain) shouldBe depthRange(flush)
    }

    @Test
    fun `a lifted dot sits in front of the letter it belongs to`() {
        val lifted = Extruder.extrude(
            letter(),
            depth = 10f,
            bevelSize = 2f,
            marks = MarkStyle(depth = 0.5f, lift = 1f),
        )

        // The dot's frontmost point is ahead of the body's, which is what "floating" means and what
        // lets it cast its own shadow.
        val bodyFront = lifted.frontOf(mark = false)
        val markFront = lifted.frontOf(mark = true)
        (markFront > bodyFront) shouldBe true

        // And it is thinner, because that is the other half of the style.
        (span(lifted, mark = true) < span(lifted, mark = false)) shouldBe true
    }

    @Test
    fun `the dot's triangles are the ones marked`() {
        val lifted = Extruder.extrude(letter(), depth = 10f, bevelSize = 2f, marks = MarkStyle.FLOATING)

        lifted.hasMarks shouldBe true

        // Checked by *where* the flagged triangles are, not by how many.
        //
        // Counting was the obvious test and it is meaningless here: the rings are stitched per
        // outline point, so a four-cornered dot and a four-cornered body produce exactly the same
        // number of triangles however different their sizes. Half the triangles being flagged is
        // correct for this fixture and would also be correct if the flags had been assigned to the
        // wrong contour entirely.
        //
        // The dot occupies x 38..46 and the body x 0..80, so asking which side of x = 38 every
        // flagged vertex falls on separates them exactly.
        for (t in 0 until lifted.triangleCount) {
            for (k in 0..2) {
                val x = lifted.position(lifted.indices[t * 3 + k]).x
                val y = lifted.position(lifted.indices[t * 3 + k]).y
                if (lifted.marks[t]) {
                    // Inside the dot, allowing for the bevel's inset and the miter at its corners.
                    (x > 30f && x < 54f && y < 20f) shouldBe true
                } else {
                    // The body sits above the dot and spans the full width.
                    (y > 15f) shouldBe true
                }
            }
        }
    }

    @Test
    fun `a letter with no dots is unaffected by any style`() {
        val plain = listOf(box(0f, 0f, 60f, 60f))
        val floated = Extruder.extrude(plain, depth = 10f, bevelSize = 2f, marks = MarkStyle.FLOATING)
        floated.hasMarks shouldBe false
    }

    private fun Mesh.frontOf(mark: Boolean): Float {
        var front = -Float.MAX_VALUE
        for (t in 0 until triangleCount) {
            if (marks[t] != mark) continue
            for (k in 0..2) front = maxOf(front, position(indices[t * 3 + k]).z)
        }
        return front
    }

    private fun span(mesh: Mesh, mark: Boolean): Float {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (t in 0 until mesh.triangleCount) {
            if (mesh.marks[t] != mark) continue
            for (k in 0..2) {
                val z = mesh.position(mesh.indices[t * 3 + k]).z
                lo = minOf(lo, z)
                hi = maxOf(hi, z)
            }
        }
        return hi - lo
    }

    private fun depthRange(mesh: Mesh): Pair<Float, Float> {
        val (min, max) = mesh.bounds()
        return min.z to max.z
    }
}
