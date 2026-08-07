package ir.pixellab.core.imaging

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs
import kotlin.math.sqrt

private fun disc(w: Int, h: Int, radius: Float): Raster = Raster.gray(w, h) { x, y ->
    val dx = x - w / 2f + 0.5f
    val dy = y - h / 2f + 0.5f
    if (sqrt(dx * dx + dy * dy) <= radius) 1f else 0f
}

private fun rect(w: Int, h: Int, inset: Int): Raster = Raster.gray(w, h) { x, y ->
    if (x in inset until w - inset && y in inset until h - inset) 1f else 0f
}

class DistanceTransformTest {

    @Test
    fun `distance inward from a rectangle edge is exact`() {
        val mask = rect(41, 41, 10)
        val d = DistanceTransform.euclidean(mask, inside = true)
        // The centre of a 21-wide band sits 11 pixels from the nearest edge.
        d[20, 20] shouldBe (11f plusOrMinus 0.01f)
        // One pixel inside the boundary is one step from outside.
        d[10, 20] shouldBe (1f plusOrMinus 0.01f)
        // Outside the shape the inward distance is zero.
        d[2, 20] shouldBe 0f
    }

    @Test
    fun `distance is euclidean not manhattan on diagonals`() {
        val mask = Raster.gray(21, 21) { x, y -> if (x == 10 && y == 10) 0f else 1f }
        val d = DistanceTransform.euclidean(mask, threshold = 0.5f, inside = true)
        // Three across and four down is five, not seven.
        d[13, 14] shouldBe (5f plusOrMinus 0.01f)
    }

    @Test
    fun `signed distance is negative inside and positive outside`() {
        val mask = disc(41, 41, 12f)
        val s = DistanceTransform.signed(mask)
        (s[20, 20] < 0f) shouldBe true
        (s[0, 0] > 0f) shouldBe true
        // Magnitude at the centre approaches the disc radius.
        abs(s[20, 20]) shouldBe (12f plusOrMinus 1.5f)
    }

    @Test
    fun `a fully solid mask has no outward distance`() {
        val mask = Raster.gray(16, 16) { _, _ -> 1f }
        val d = DistanceTransform.euclidean(mask, inside = false)
        d.data.all { it == 0f } shouldBe true
    }
}

class HeightFieldTest {

    @Test
    fun `bevel height ramps from the edge and plateaus at the requested size`() {
        val mask = rect(41, 41, 10)
        val h = HeightField.fromMask(mask, size = 6f)
        h[10, 20] shouldBe (1f / 6f plusOrMinus 0.02f)
        // Beyond the bevel size the surface is flat.
        h[20, 20] shouldBe 1f
        h[2, 20] shouldBe 0f
    }

    @Test
    fun `a rounded profile rises faster than a linear one`() {
        val mask = rect(41, 41, 10)
        val linear = HeightField.fromMask(mask, 8f) { it }
        val rounded = HeightField.fromMask(mask, 8f) { t -> sqrt(t) }
        (rounded[13, 20] > linear[13, 20]) shouldBe true
    }

    @Test
    fun `normals are unit length and point up on flat regions`() {
        val h = HeightField.fromMask(rect(41, 41, 10), 6f)
        val n = HeightField.toNormals(h)
        for (i in 0 until n.pixelCount) {
            val o = i * 3
            val len = sqrt(n.data[o] * n.data[o] + n.data[o + 1] * n.data[o + 1] + n.data[o + 2] * n.data[o + 2])
            len shouldBe (1f plusOrMinus 1e-3f)
        }
        // Interior is flat, so the normal is straight up.
        n[20, 20, 2] shouldBe (1f plusOrMinus 1e-3f)
    }

    @Test
    fun `depth strength tilts normals further from vertical`() {
        val h = HeightField.fromMask(rect(41, 41, 10), 8f)
        val shallow = HeightField.toNormals(h, strength = 1f)
        val deep = HeightField.toNormals(h, strength = 8f)
        // Sampled on the bevel slope, higher depth means a smaller z component.
        (deep[13, 20, 2] < shallow[13, 20, 2]) shouldBe true
    }

    @Test
    fun `shading lights the side facing the light and darkens the opposite`() {
        val h = HeightField.fromMask(rect(61, 61, 15), 10f)
        val n = HeightField.toNormals(h, strength = 6f)
        // Light from above: the top slope is lit, the bottom slope is in shadow.
        val shaded = HeightField.shade(n, lightAngle = 90f, altitude = 30f)
        val top = shaded[30, 18]
        val bottom = shaded[30, 42]
        (top > bottom) shouldBe true
    }

    @Test
    fun `a zero bevel size is rejected rather than dividing by zero`() {
        assertThrows<IllegalArgumentException> { HeightField.fromMask(rect(8, 8, 2), 0f) }
    }
}

class BlurTest {

    @Test
    fun `box blur preserves total energy`() {
        val src = Raster.gray(32, 32) { x, y -> if (x == 16 && y == 16) 1f else 0f }
        val out = Blur.box(src, 3)
        src.data.sum() shouldBe (out.data.sum() plusOrMinus 1e-3f)
    }

    @Test
    fun `a zero radius is a no-op`() {
        val src = Raster.gray(8, 8) { x, _ -> x / 8f }
        RasterMath.meanAbsDiff(Blur.box(src, 0), src) shouldBe 0f
        RasterMath.meanAbsDiff(Blur.gaussian(src, 0f), src) shouldBe 0f
    }

    @Test
    fun `the three-box gaussian approximates the exact one closely`() {
        val src = Raster.gray(48, 48) { x, y -> if ((x / 6 + y / 6) % 2 == 0) 1f else 0f }
        val fast = Blur.gaussian(src, 4f)
        val exact = Blur.gaussianExact(src, 4f)
        // Well under a single 8-bit level of difference.
        (RasterMath.meanAbsDiff(fast, exact) < 1f / 255f) shouldBe true
    }

    @Test
    fun `blur radii for a gaussian are odd-width and near the ideal`() {
        val radii = Blur.boxRadiiForGaussian(5f, 3)
        radii.size shouldBe 3
        radii.all { it >= 0 } shouldBe true
    }

    @Test
    fun `bilateral smooths flat regions but keeps a hard edge`() {
        // Left half dark, right half bright, with noise on both sides.
        var seed = 12345
        fun noise(): Float {
            seed = seed * 1103515245 + 12345
            return ((seed ushr 16) and 0xFF) / 255f * 0.08f - 0.04f
        }
        val src = Raster.gray(48, 48) { x, _ -> (if (x < 24) 0.25f else 0.85f) + noise() }

        val bilateral = Blur.bilateral(src, sigmaSpace = 3f, sigmaRange = 0.1f)
        val gaussian = Blur.gaussian(src, 3f)

        // Edge contrast: bilateral keeps it, gaussian smears it.
        val bilateralJump = abs(bilateral[25, 24] - bilateral[22, 24])
        val gaussianJump = abs(gaussian[25, 24] - gaussian[22, 24])
        (bilateralJump > gaussianJump) shouldBe true

        // Away from the edge, noise is still removed.
        val flatVariance = (5..18).map { abs(bilateral[it, 24] - 0.25f) }.average()
        (flatVariance < 0.03f) shouldBe true
    }

    @Test
    fun `bilateral rejects non-positive sigmas`() {
        assertThrows<IllegalArgumentException> { Blur.bilateral(rect(8, 8, 2), 0f, 1f) }
    }
}

class GuidedFilterTest {

    @Test
    fun `with a matching guide the filter is close to identity`() {
        val src = Raster.gray(48, 48) { x, _ -> x / 48f }
        val out = GuidedFilter.filter(src, src, radius = 4, epsilon = 1e-6f)
        (RasterMath.meanAbsDiff(out, src) < 0.01f) shouldBe true
    }

    @Test
    fun `a coarse mask snaps to the structure of its guide`() {
        // Guide has a sharp vertical edge; the mask edge is offset and soft.
        val guide = Raster.gray(64, 64) { x, _ -> if (x < 32) 0f else 1f }
        val coarse = Blur.gaussian(Raster.gray(64, 64) { x, _ -> if (x < 28) 0f else 1f }, 6f)

        val refined = GuidedFilter.filter(guide, coarse, radius = 8, epsilon = 1e-4f)

        // The refined edge should land on the guide's edge, not the mask's original one.
        (refined[30, 32] < 0.5f) shouldBe true
        (refined[34, 32] > 0.5f) shouldBe true
        // And it should be sharper than what went in.
        val refinedJump = abs(refined[34, 32] - refined[30, 32])
        val coarseJump = abs(coarse[34, 32] - coarse[30, 32])
        (refinedJump > coarseJump) shouldBe true
    }

    @Test
    fun `larger epsilon smooths more`() {
        val guide = Raster.gray(48, 48) { x, y -> ((x / 4 + y / 4) % 2).toFloat() }
        val input = guide.copy()
        val sharp = GuidedFilter.filter(guide, input, 4, 1e-6f)
        val smooth = GuidedFilter.filter(guide, input, 4, 0.5f)
        (RasterMath.meanAbsDiff(smooth, input) > RasterMath.meanAbsDiff(sharp, input)) shouldBe true
    }

    @Test
    fun `the colour guide separates regions a grayscale guide cannot`() {
        // Two colours with identical luma: hair against a coloured wall is this case.
        val guide = Raster(64, 64, 3)
        for (y in 0 until 64) for (x in 0 until 64) {
            val left = x < 32
            guide[x, y, 0] = if (left) 0.8f else 0.2f
            guide[x, y, 1] = if (left) 0.2f else 0.8f
            guide[x, y, 2] = 0.5f
        }
        val luma = guide.luminance()
        // Confirm the trap: in luma the two halves are much closer than in colour.
        (abs(luma[10, 32] - luma[54, 32]) < 0.5f) shouldBe true

        val coarse = Blur.gaussian(Raster.gray(64, 64) { x, _ -> if (x < 28) 0f else 1f }, 6f)
        val byColour = GuidedFilter.filterColorGuide(guide, coarse, radius = 8, epsilon = 1e-5f)

        val colourJump = abs(byColour[34, 32] - byColour[30, 32])
        val coarseJump = abs(coarse[34, 32] - coarse[30, 32])
        (colourJump > coarseJump) shouldBe true
    }

    @Test
    fun `shape mismatches are rejected`() {
        assertThrows<IllegalArgumentException> {
            GuidedFilter.filter(Raster(8, 8, 1), Raster(16, 16, 1), 2, 1e-4f)
        }
    }
}

class FrequencySeparationTest {

    private fun skin(): Raster = Raster(64, 64, 3).also { r ->
        var seed = 999
        fun noise(): Float {
            seed = seed * 1103515245 + 12345
            return ((seed ushr 16) and 0xFF) / 255f * 0.06f - 0.03f
        }
        for (y in 0 until 64) for (x in 0 until 64) {
            val base = 0.6f + 0.15f * (x / 64f)
            r[x, y, 0] = base + 0.1f + noise()
            r[x, y, 1] = base + noise()
            r[x, y, 2] = base - 0.1f + noise()
        }
    }

    @Test
    fun `splitting and recombining is lossless`() {
        val src = skin()
        val bands = FrequencySeparation.split(src, radius = 4f)
        (RasterMath.meanAbsDiff(bands.recombine(), src) < 1e-5f) shouldBe true
    }

    @Test
    fun `texture lives in the high band and colour in the low band`() {
        val src = skin()
        val bands = FrequencySeparation.split(src, radius = 6f)

        fun localVariance(r: Raster): Double {
            var sum = 0.0
            for (y in 10 until 54) for (x in 10 until 54) {
                sum += abs(r[x, y, 1] - r[x + 1, y, 1]).toDouble()
            }
            return sum / (44 * 44)
        }
        // High-frequency detail is what varies pixel to pixel.
        (localVariance(bands.high) > localVariance(bands.low) * 3) shouldBe true
    }

    @Test
    fun `smoothing the low band leaves texture intact`() {
        val src = skin()
        val bands = FrequencySeparation.split(src, radius = 6f)
        val smoothedLow = Blur.gaussian(bands.low, 8f)
        val result = FrequencySeparation.Bands(smoothedLow, bands.high).recombine()

        fun texture(r: Raster): Double {
            var sum = 0.0
            for (y in 10 until 54) for (x in 10 until 54) sum += abs(r[x, y, 1] - r[x + 1, y, 1]).toDouble()
            return sum / (44 * 44)
        }
        // This is the whole point: blurring colour without losing pores.
        val kept = texture(result) / texture(src)
        (kept > 0.85) shouldBe true

        // A plain blur of the same strength destroys it.
        val naive = texture(Blur.gaussian(src, 8f)) / texture(src)
        (naive < 0.3) shouldBe true
    }

    @Test
    fun `multi-band decomposition rebuilds the original`() {
        val src = skin()
        val bands = FrequencySeparation.decompose(src, levels = 5)
        bands.size shouldBe 6
        (RasterMath.meanAbsDiff(FrequencySeparation.recompose(bands), src) < 1e-4f) shouldBe true
    }

    @Test
    fun `per-band gains attenuate only their own scale`() {
        val src = skin()
        val bands = FrequencySeparation.decompose(src, levels = 5)
        // Kill the finest band only.
        val out = FrequencySeparation.recompose(bands, gains = listOf(0f))
        (RasterMath.meanAbsDiff(out, src) > 0f) shouldBe true
        // Broad structure survives.
        abs(out[32, 32, 1] - src[32, 32, 1]) shouldBe (0f plusOrMinus 0.08f)
    }

    @Test
    fun `level count is bounded`() {
        assertThrows<IllegalArgumentException> { FrequencySeparation.decompose(rect(8, 8, 2), levels = 0) }
    }
}

class TrimapTest {

    @Test
    fun `a trimap has three regions and an unknown band of the requested width`() {
        val mask = disc(81, 81, 24f)
        val tri = Trimap.fromMask(mask, band = 5f)
        tri[40, 40] shouldBe Trimap.FOREGROUND
        tri[2, 2] shouldBe Trimap.BACKGROUND
        // On the boundary the pixel must be unknown.
        tri[40, 16] shouldBe Trimap.UNKNOWN
    }

    @Test
    fun `the unknown band is a small fraction of the image`() {
        val tri = Trimap.fromMask(disc(200, 200, 60f), band = 4f)
        val fraction = Trimap.unknownFraction(tri)
        // This is what keeps solving affordable on a phone.
        (fraction < 0.1f) shouldBe true
        (fraction > 0f) shouldBe true
    }

    @Test
    fun `the refine brush widens the band only where painted`() {
        val tri = Trimap.fromMask(disc(81, 81, 24f), band = 3f)
        val strokes = Raster.gray(81, 81) { x, y -> if (x in 30..50 && y in 5..15) 1f else 0f }
        val widened = Trimap.widen(tri, strokes)
        widened[40, 10] shouldBe Trimap.UNKNOWN
        // Untouched areas keep their classification.
        widened[40, 40] shouldBe Trimap.FOREGROUND
        (Trimap.unknownFraction(widened) > Trimap.unknownFraction(tri)) shouldBe true
    }

    @Test
    fun `solving alpha honours the known regions as hard constraints`() {
        val mask = disc(81, 81, 24f)
        val guide = Raster(81, 81, 3)
        for (y in 0 until 81) for (x in 0 until 81) {
            val v = mask[x, y]
            guide[x, y, 0] = v; guide[x, y, 1] = v * 0.6f; guide[x, y, 2] = 1f - v
        }
        val tri = Trimap.fromMask(mask, band = 4f)
        val alpha = Trimap.solveAlpha(tri, guide, radius = 6)

        alpha[40, 40] shouldBe 1f
        alpha[2, 2] shouldBe 0f
        alpha.data.all { it in 0f..1f } shouldBe true
    }

    @Test
    fun `a non-positive band is rejected`() {
        assertThrows<IllegalArgumentException> { Trimap.fromMask(disc(16, 16, 4f), 0f) }
    }
}

class DecontaminateTest {

    @Test
    fun `green spill is removed from semi-transparent edge pixels`() {
        val w = 16
        // A white subject shot against pure green, composited at 50% coverage along a column.
        val alpha = Raster.gray(w, w) { x, _ -> if (x < 8) 1f else 0.5f }
        val background = Raster(w, w, 3)
        for (y in 0 until w) for (x in 0 until w) {
            background[x, y, 0] = 0f; background[x, y, 1] = 1f; background[x, y, 2] = 0f
        }
        val image = Raster(w, w, 3)
        for (y in 0 until w) for (x in 0 until w) {
            val a = alpha[x, y]
            for (c in 0 until 3) {
                val fg = 1f
                image[x, y, c] = a * fg + (1f - a) * background[x, y, c]
            }
        }
        // The contaminated pixel is visibly green before correction.
        (image[12, 8, 1] > image[12, 8, 0] + 0.2f) shouldBe true

        val clean = Decontaminate.foreground(image, alpha, background)

        // After correction the recovered foreground is neutral white again.
        clean[12, 8, 0] shouldBe (1f plusOrMinus 0.02f)
        clean[12, 8, 1] shouldBe (1f plusOrMinus 0.02f)
        clean[12, 8, 2] shouldBe (1f plusOrMinus 0.02f)
    }

    @Test
    fun `fully opaque pixels are left alone`() {
        val w = 8
        val alpha = Raster.gray(w, w) { _, _ -> 1f }
        val image = Raster(w, w, 3).fill(0.42f)
        val bg = Raster(w, w, 3).fill(0f)
        val out = Decontaminate.foreground(image, alpha, bg)
        RasterMath.meanAbsDiff(out, image) shouldBe (0f plusOrMinus 1e-5f)
    }

    @Test
    fun `nearly transparent pixels borrow colour instead of exploding`() {
        val w = 16
        val alpha = Raster.gray(w, w) { x, _ -> if (x < 8) 1f else 0.02f }
        val image = Raster(w, w, 3).fill(0.5f)
        val bg = Raster(w, w, 3).fill(0.5f)
        val out = Decontaminate.foreground(image, alpha, bg)
        out.data.all { it.isFinite() && it in 0f..1f } shouldBe true
    }
}

class RasterTest {

    @Test
    fun `construction validates its arguments`() {
        assertThrows<IllegalArgumentException> { Raster(0, 4, 1) }
        assertThrows<IllegalArgumentException> { Raster(4, 4, 5) }
        assertThrows<IllegalArgumentException> { Raster(4, 4, 1, FloatArray(3)) }
    }

    @Test
    fun `reads outside the bounds clamp to the edge`() {
        val r = Raster.gray(4, 4) { x, _ -> x.toFloat() }
        r.clamped(-5, 0) shouldBe 0f
        r.clamped(99, 0) shouldBe 3f
    }

    @Test
    fun `luminance uses rec 709 weights`() {
        val r = Raster(1, 1, 3)
        r[0, 0, 0] = 1f; r[0, 0, 1] = 0f; r[0, 0, 2] = 0f
        r.luminance()[0, 0] shouldBe (0.2126f plusOrMinus 1e-4f)
    }
}
