package ir.pixellab.core.imaging

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs

class PatchMatchTest {

    /** Horizontal stripes: a strongly structured texture that a fill must continue, not smear. */
    private fun stripes(w: Int, h: Int, period: Int = 8): Raster {
        val r = Raster(w, h, 3)
        for (y in 0 until h) for (x in 0 until w) {
            val v = if ((y / period) % 2 == 0) 0.85f else 0.2f
            r[x, y, 0] = v; r[x, y, 1] = v; r[x, y, 2] = v
        }
        return r
    }

    private fun holeRect(w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int): Raster =
        Raster.gray(w, h) { x, y -> if (x in x0..x1 && y in y0..y1) 1f else 0f }

    @Test
    fun `pixels outside the hole are never touched`() {
        val src = stripes(64, 64)
        val hole = holeRect(64, 64, 24, 24, 39, 39)
        val out = PatchMatch(patchRadius = 3, iterations = 3).inpaint(src, hole, passes = 2)

        for (y in 0 until 64) for (x in 0 until 64) {
            if (hole[x, y] > 0.5f) continue
            for (c in 0 until 3) out[x, y, c] shouldBe (src[x, y, c] plusOrMinus 1e-5f)
        }
    }

    @Test
    fun `a hole in a striped texture is filled with the same stripe structure`() {
        val src = stripes(64, 64)
        val hole = holeRect(64, 64, 24, 24, 39, 39)
        val out = PatchMatch(patchRadius = 4, iterations = 4).inpaint(src, hole, passes = 3)

        // Inside the hole the pixels should still be near one of the two stripe values, not the
        // average of them — averaging is what a blur-based fill would produce.
        var nearStripe = 0
        var total = 0
        for (y in 26..37) for (x in 26..37) {
            val v = out[x, y, 1]
            total++
            if (abs(v - 0.85f) < 0.18f || abs(v - 0.2f) < 0.18f) nearStripe++
        }
        (nearStripe.toDouble() / total > 0.7) shouldBe true
    }

    @Test
    fun `the fill stays inside the source value range`() {
        val src = stripes(48, 48)
        val hole = holeRect(48, 48, 18, 18, 29, 29)
        val out = PatchMatch(patchRadius = 3, iterations = 3).inpaint(src, hole, passes = 2)
        out.data.all { it.isFinite() && it in -0.01f..1.01f } shouldBe true
    }

    @Test
    fun `results are deterministic for a given seed`() {
        val src = stripes(48, 48)
        val hole = holeRect(48, 48, 18, 18, 29, 29)
        val a = PatchMatch(patchRadius = 3, iterations = 3, seed = 42L).inpaint(src, hole, passes = 2)
        val b = PatchMatch(patchRadius = 3, iterations = 3, seed = 42L).inpaint(src, hole, passes = 2)
        RasterMath.meanAbsDiff(a, b) shouldBe 0f
    }

    @Test
    fun `an empty hole leaves the image unchanged`() {
        val src = stripes(32, 32)
        val hole = Raster.gray(32, 32) { _, _ -> 0f }
        RasterMath.meanAbsDiff(PatchMatch().inpaint(src, hole, passes = 1), src) shouldBe 0f
    }

    @Test
    fun `mismatched hole dimensions are rejected`() {
        assertThrows<IllegalArgumentException> {
            PatchMatch().inpaint(Raster(16, 16, 3), Raster.gray(8, 8) { _, _ -> 0f })
        }
    }
}

class MovingLeastSquaresTest {

    private fun checkerboard(w: Int, h: Int, size: Int = 8): Raster = Raster.gray(w, h) { x, y ->
        if ((x / size + y / size) % 2 == 0) 1f else 0f
    }

    private val identityPoints = listOf(
        MovingLeastSquares.ControlPoint(10f, 10f, 10f, 10f),
        MovingLeastSquares.ControlPoint(54f, 10f, 54f, 10f),
        MovingLeastSquares.ControlPoint(10f, 54f, 10f, 54f),
        MovingLeastSquares.ControlPoint(54f, 54f, 54f, 54f),
    )

    @Test
    fun `control points that do not move leave the image alone`() {
        val src = checkerboard(64, 64)
        val out = MovingLeastSquares.deform(src, identityPoints, gridStep = 4)
        (RasterMath.meanAbsDiff(out, src) < 0.02f) shouldBe true
    }

    @Test
    fun `a control point lands exactly on its destination`() {
        val points = listOf(
            MovingLeastSquares.ControlPoint(20f, 32f, 40f, 32f),
            MovingLeastSquares.ControlPoint(4f, 4f, 4f, 4f),
            MovingLeastSquares.ControlPoint(60f, 60f, 60f, 60f),
        )
        // Asking where destination (40,32) samples from must give the source (20,32).
        val (sx, sy) = MovingLeastSquares.solve(40f, 32f, points, MovingLeastSquares.Mode.RIGID, 2f)
        sx shouldBe (20f plusOrMinus 0.01f)
        sy shouldBe (32f plusOrMinus 0.01f)
    }

    @Test
    fun `influence decays with distance from the moved point`() {
        val points = listOf(
            MovingLeastSquares.ControlPoint(32f, 32f, 42f, 32f),
            MovingLeastSquares.ControlPoint(0f, 0f, 0f, 0f),
            MovingLeastSquares.ControlPoint(64f, 0f, 64f, 0f),
            MovingLeastSquares.ControlPoint(0f, 64f, 0f, 64f),
            MovingLeastSquares.ControlPoint(64f, 64f, 64f, 64f),
        )
        fun displacement(x: Float, y: Float): Float {
            val (sx, _) = MovingLeastSquares.solve(x, y, points, MovingLeastSquares.Mode.RIGID, 2f)
            return abs(sx - x)
        }
        val near = displacement(40f, 32f)
        val far = displacement(60f, 32f)
        (near > far) shouldBe true
    }

    @Test
    fun `deformation is smooth with no torn pixels`() {
        val src = checkerboard(64, 64, size = 4)
        val points = listOf(
            MovingLeastSquares.ControlPoint(32f, 32f, 38f, 30f),
            MovingLeastSquares.ControlPoint(2f, 2f, 2f, 2f),
            MovingLeastSquares.ControlPoint(61f, 2f, 61f, 2f),
            MovingLeastSquares.ControlPoint(2f, 61f, 2f, 61f),
            MovingLeastSquares.ControlPoint(61f, 61f, 61f, 61f),
        )
        val out = MovingLeastSquares.deform(src, points, gridStep = 4)
        out.data.all { it.isFinite() && it in -0.01f..1.01f } shouldBe true
        // The image changed but kept roughly its overall brightness.
        (RasterMath.meanAbsDiff(out, src) > 0f) shouldBe true
        abs(out.data.average() - src.data.average()).toFloat() shouldBe (0f plusOrMinus 0.12f)
    }

    @Test
    fun `no control points is a no-op`() {
        val src = checkerboard(32, 32)
        RasterMath.meanAbsDiff(MovingLeastSquares.deform(src, emptyList()), src) shouldBe 0f
    }

    @Test
    fun `a zero grid step is rejected`() {
        assertThrows<IllegalArgumentException> {
            MovingLeastSquares.deform(checkerboard(16, 16), identityPoints, gridStep = 0)
        }
    }
}
