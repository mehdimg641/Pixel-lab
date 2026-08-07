package ir.pixellab.core.imaging

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Noise reduction, and the one thing it must not do.
 *
 * Every test here is paired: it checks that the noise went and that something else stayed. A plain
 * blur passes the first half of every one of them, which is exactly why the second half is written.
 */
class DenoiseTest {

    /**
     * A hard vertical edge with noise laid over it — the classic case a blur destroys.
     *
     * The same shift in all three channels, so this is *luminance* noise and nothing else. Grain
     * that differs per channel is chroma noise by definition, and the chroma pass is what removes
     * it; mixing the two into one fixture would test neither.
     */
    private fun noisyEdge(size: Int = 48, noise: Float = 0.05f, seed: Int = 7): Raster {
        val random = Random(seed)
        val r = Raster(size, size, 3)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val base = if (x < size / 2) 0.3f else 0.7f
                val value = (base + (random.nextFloat() - 0.5f) * 2f * noise).coerceIn(0f, 1f)
                for (c in 0 until 3) r[x, y, c] = value
            }
        }
        return r
    }

    private fun roughness(r: Raster, fromX: Int, toX: Int): Float {
        var total = 0f
        var count = 0
        for (y in 1 until r.height - 1) {
            for (x in fromX until toX) {
                total += abs(r[x, y, 0] - r[x - 1, y, 0])
                count++
            }
        }
        return if (count == 0) 0f else total / count
    }

    @Test
    fun `the noise goes and the edge stays`() {
        val src = noisyEdge()
        val out = Denoise.reduceNoise(src, strength = 1f, colorStrength = 0f, preserveDetail = 0f)

        // Flat region, well clear of the edge: much smoother than it was.
        (roughness(out, 4, 20) < roughness(src, 4, 20) * 0.6f) shouldBe true

        // And the edge itself is still a step, not a ramp. A Gaussian of the same reach would have
        // spread this over several pixels, which is the whole reason for the range weight.
        val step = out[26, 24, 0] - out[21, 24, 0]
        (step > 0.3f) shouldBe true
    }

    @Test
    fun `colour noise is smoothed harder than the brightness`() {
        val size = 32
        val src = Raster(size, size, 3)
        val random = Random(11)
        for (i in 0 until src.pixelCount) {
            // Pure chroma noise: the three channels pull apart, the luminance does not move.
            val shift = (random.nextFloat() - 0.5f) * 0.3f
            src.data[i * 3] = (0.5f + shift).coerceIn(0f, 1f)
            src.data[i * 3 + 1] = 0.5f
            src.data[i * 3 + 2] = (0.5f - shift).coerceIn(0f, 1f)
        }

        val out = Denoise.reduceNoise(src, strength = 0f, colorStrength = 1f)
        fun spread(r: Raster): Float {
            var total = 0f
            for (i in 0 until r.pixelCount) total += abs(r.data[i * 3] - r.data[i * 3 + 2])
            return total / r.pixelCount
        }
        (spread(out) < spread(src) * 0.5f) shouldBe true
    }

    @Test
    fun `preserving detail leaves more of it`() {
        val src = noisyEdge()
        val loose = Denoise.reduceNoise(src, strength = 1f, colorStrength = 0f, preserveDetail = 0f)
        val tight = Denoise.reduceNoise(src, strength = 1f, colorStrength = 0f, preserveDetail = 1f)
        (roughness(tight, 4, 20) > roughness(loose, 4, 20)) shouldBe true
    }

    @Test
    fun `doing nothing costs nothing`() {
        val src = noisyEdge()
        (Denoise.reduceNoise(src, strength = 0f, colorStrength = 0f) === src) shouldBe true
    }

    @Test
    fun `a bilateral average never reaches across an edge`() {
        val size = 24
        val src = Raster(size, size, 1)
        for (y in 0 until size) for (x in 0 until size) src[x, y, 0] = if (x < size / 2) 0f else 1f

        val out = Denoise.bilateral(src, spatial = 4f, range = 0.1f)
        // The pixel right at the edge must not have taken half its value from the other side.
        out[11, 12, 0] shouldBe 0f.plusOrMinus(0.02f)
        out[12, 12, 0] shouldBe 1f.plusOrMinus(0.02f)
    }

    // ---- dust and scratches --------------------------------------------------------------------

    @Test
    fun `a speck is removed`() {
        val src = Raster(24, 24, 3).fill(0.5f)
        for (c in 0 until 3) src[12, 12, c] = 1f

        val out = Denoise.dustAndScratches(src, radius = 2, threshold = 0.1f)
        out[12, 12, 0] shouldBe 0.5f.plusOrMinus(0.001f)
    }

    @Test
    fun `a texture is not`() {
        // The difference between this and the previous case is the entire reason for the threshold.
        // A plain median removes both — dust and every eyelash in the picture with it.
        val size = 24
        val src = Raster(size, size, 3)
        for (y in 0 until size) {
            for (x in 0 until size) {
                val v = if ((x + y) % 2 == 0) 0.4f else 0.6f
                for (c in 0 until 3) src[x, y, c] = v
            }
        }

        val out = Denoise.dustAndScratches(src, radius = 1, threshold = 0.3f)
        out[12, 12, 0] shouldBe src[12, 12, 0].plusOrMinus(0.001f)
    }

    @Test
    fun `a zero radius is a no-op`() {
        val src = Raster(8, 8, 3).fill(0.5f)
        (Denoise.dustAndScratches(src, radius = 0) === src) shouldBe true
    }
}
