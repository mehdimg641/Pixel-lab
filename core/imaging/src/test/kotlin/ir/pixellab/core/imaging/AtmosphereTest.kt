package ir.pixellab.core.imaging

import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Vec2
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Dehaze, local contrast and light rays.
 *
 * Three controls Hypic has in the same scrolling row as brightness, and the three of the twenty-five
 * in that row that a curve cannot imitate. Each is checked against the property it exists for, not
 * against a golden image: a golden image would pass with the effect scaled to a tenth.
 */
class AtmosphereTest {

    /**
     * A synthetic hazy photograph: a dark checker under a white veil that thickens to the right.
     *
     * The veil is exactly the physical model — `I = J·t + A·(1−t)` — so the test is asking whether
     * the estimator recovers the thing that was actually done to the picture.
     */
    private fun hazy(width: Int = 64, height: Int = 64): Raster {
        val r = Raster(width, height, 3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val scene = if (((x / 4) + (y / 4)) % 2 == 0) 0.1f else 0.6f
                val transmission = 1f - 0.7f * (x.toFloat() / (width - 1))
                val at = (y * width + x) * 3
                for (c in 0 until 3) {
                    r.data[at + c] = scene * transmission + 1f * (1f - transmission)
                }
            }
        }
        return r
    }

    private fun contrastOf(src: Raster, fromX: Int, toX: Int): Float {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (y in 0 until src.height) {
            for (x in fromX until toX) {
                val v = src[x, y, 0]
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
        }
        return hi - lo
    }

    @Test
    fun `dehaze puts back the contrast the veil took away`() {
        val src = hazy()
        val clear = Dehaze.apply(src, 1f)
        // The right-hand side is the hazy end; that is where the recovery has work to do.
        val before = contrastOf(src, 48, 64)
        val after = contrastOf(clear, 48, 64)
        after shouldBeGreaterThan before * 1.5f
    }

    @Test
    fun `dehaze does most of its work where the haze is`() {
        // The property that separates it from a contrast slider. A curve raises contrast everywhere
        // by the same amount; haze is depth-dependent, and so is its removal.
        val src = hazy()
        val clear = Dehaze.apply(src, 1f)
        val nearGain = contrastOf(clear, 0, 16) - contrastOf(src, 0, 16)
        val farGain = contrastOf(clear, 48, 64) - contrastOf(src, 48, 64)
        farGain shouldBeGreaterThan nearGain
    }

    @Test
    fun `a haze-free picture is left almost alone`() {
        // The dark channel of an image that already has deep shadows is near zero, so the estimated
        // transmission is near one and the recovery is near the identity. If this fails, the filter
        // is a contrast slider wearing the prior as a costume.
        val plain = Raster(48, 48, 3)
        for (y in 0 until 48) {
            for (x in 0 until 48) {
                val v = if (((x / 4) + (y / 4)) % 2 == 0) 0.02f else 0.9f
                val at = (y * 48 + x) * 3
                for (c in 0 until 3) plain.data[at + c] = v
            }
        }
        val out = Dehaze.apply(plain, 1f)
        RasterMath.meanAbsDiff(plain, out) shouldBeLessThan 0.08f
    }

    @Test
    fun `zero strength is the identity`() {
        val src = hazy(32, 32)
        RasterMath.meanAbsDiff(src, Dehaze.apply(src, 0f)) shouldBe 0f
    }

    @Test
    fun `the airlight is taken from the haze, not from the brightest pixel`() {
        // A single blown highlight in an otherwise unhazed picture must not become the airlight —
        // if it does, every shadow in the result takes on its colour.
        val src = Raster(32, 32, 3)
        for (i in 0 until src.pixelCount) {
            val at = i * 3
            src.data[at] = 0.2f
            src.data[at + 1] = 0.2f
            src.data[at + 2] = 0.2f
        }
        // One pure-red blowout.
        src[16, 16, 0] = 1f
        val dark = Dehaze.darkChannel(src, 3)
        val air = Dehaze.atmosphere(src, dark)
        // Not the red pixel: its dark channel is still 0.2, the same as everywhere else, so it has
        // no claim on being "the most distant thing in the frame".
        abs(air[0] - air[1]) shouldBeLessThan 0.3f
    }

    // ---- local contrast ---------------------------------------------------------------------------

    /** A soft edge: exactly the thing clarity is meant to find and texture is meant to ignore. */
    private fun softEdge(width: Int = 64, height: Int = 16, softness: Float = 10f): Raster {
        val r = Raster(width, height, 3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val t = ((x - width / 2f) / softness).coerceIn(-1f, 1f)
                val v = 0.5f + 0.25f * t
                val at = (y * width + x) * 3
                for (c in 0 until 3) r.data[at + c] = v
            }
        }
        return r
    }

    @Test
    fun `local contrast steepens an edge without moving it`() {
        val src = softEdge()
        val out = LocalContrast.apply(src, LocalContrast.Scale.CLARITY, 1f)
        val mid = src.width / 2
        // Steeper across the transition…
        val before = src[mid + 6, 8, 0] - src[mid - 6, 8, 0]
        val after = out[mid + 6, 8, 0] - out[mid - 6, 8, 0]
        after shouldBeGreaterThan before
        // …and the midpoint has not shifted, which is what separates it from a brightness change.
        abs(out[mid, 8, 0] - src[mid, 8, 0]) shouldBeLessThan 0.02f
    }

    @Test
    fun `a negative amount flattens instead of sharpening`() {
        val src = softEdge()
        val out = LocalContrast.apply(src, LocalContrast.Scale.CLARITY, -1f)
        val mid = src.width / 2
        val before = src[mid + 6, 8, 0] - src[mid - 6, 8, 0]
        val after = out[mid + 6, 8, 0] - out[mid - 6, 8, 0]
        after shouldBeLessThan before
    }

    @Test
    fun `the three scales are genuinely different sizes of detail`() {
        // If they were not, five sliders across the reference apps would be one slider with three
        // labels — and this class would be a lie told three times.
        val src = softEdge(softness = 3f)
        val texture = LocalContrast.apply(src, LocalContrast.Scale.TEXTURE, 1f)
        val brilliance = LocalContrast.apply(src, LocalContrast.Scale.BRILLIANCE, 1f)
        RasterMath.meanAbsDiff(texture, brilliance) shouldBeGreaterThan 0.005f
    }

    @Test
    fun `local contrast does not drag saturation with it`() {
        // Pushing in RGB is what produces the over-cooked HDR look. Working on luminance and
        // restoring the ratio keeps the hue where the photographer put it.
        val src = Raster(48, 16, 3)
        for (y in 0 until 16) {
            for (x in 0 until 48) {
                val t = ((x - 24f) / 8f).coerceIn(-1f, 1f)
                val at = (y * 48 + x) * 3
                src.data[at] = 0.6f + 0.15f * t
                src.data[at + 1] = 0.35f + 0.15f * t
                src.data[at + 2] = 0.3f + 0.15f * t
            }
        }
        val out = LocalContrast.apply(src, LocalContrast.Scale.CLARITY, 1f)
        fun ratio(r: Raster, x: Int) = r[x, 8, 0] / r[x, 8, 1]
        abs(ratio(out, 30) - ratio(src, 30)) shouldBeLessThan 0.05f
    }

    @Test
    fun `nothing is pushed past black or white`() {
        // The halo test. A step that already reaches the ends of the range has nowhere for its
        // detail to go, and amplifying it anyway is exactly how a black rim appears along a skyline.
        val src = Raster(64, 8, 3)
        for (y in 0 until 8) {
            for (x in 0 until 64) {
                val v = if (x < 32) 0f else 1f
                val at = (y * 64 + x) * 3
                for (c in 0 until 3) src.data[at + c] = v
            }
        }
        val out = LocalContrast.apply(src, LocalContrast.Scale.CLARITY, 1f)
        RasterMath.meanAbsDiff(src, out) shouldBeLessThan 0.02f
    }

    @Test
    fun `zero amount is the identity`() {
        val src = softEdge()
        RasterMath.meanAbsDiff(src, LocalContrast.apply(src, LocalContrast.Scale.CLARITY, 0f)) shouldBe 0f
    }

    // ---- light rays -------------------------------------------------------------------------------

    /** A bright disc on a dark ground, off to one side — a sun behind a treeline. */
    private fun sun(width: Int = 64, height: Int = 64, cx: Int = 16, cy: Int = 16): Raster {
        val r = Raster(width, height, 3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val d = kotlin.math.hypot((x - cx).toFloat(), (y - cy).toFloat())
                val v = if (d < 5f) 1f else 0.15f
                val at = (y * width + x) * 3
                for (c in 0 until 3) r.data[at + c] = v
            }
        }
        return r
    }

    @Test
    fun `rays brighten the picture along the way out from the source`() {
        val src = sun()
        val out = LightRays.apply(src, Vec2(16f, 16f), length = 0.5f, intensity = 1f)
        // A point on the line from the source, outside the disc, gains light.
        out[30, 30, 0] shouldBeGreaterThan src[30, 30, 0]
    }

    @Test
    fun `rays fade with distance rather than stopping at a ring`() {
        val src = sun()
        val out = LightRays.apply(src, Vec2(16f, 16f), length = 0.5f, intensity = 1f)
        val near = out[26, 26, 0] - src[26, 26, 0]
        val far = out[56, 56, 0] - src[56, 56, 0]
        far shouldBeLessThan near
    }

    @Test
    fun `only the highlights are scattered`() {
        // Smearing everything is a zoom blur, which is a different effect and looks like camera
        // shake. With no pixel above the threshold there is nothing to scatter and nothing changes.
        val flat = Raster(48, 48, 3).fill(0.3f)
        val out = LightRays.apply(flat, Vec2(24f, 24f), threshold = 0.9f, intensity = 1f)
        RasterMath.meanAbsDiff(flat, out) shouldBeLessThan 0.001f
    }

    @Test
    fun `light adds rather than replacing, so nothing gets darker`() {
        val src = sun()
        val out = LightRays.apply(src, Vec2(16f, 16f), length = 0.5f, intensity = 1f)
        var darkened = 0
        for (i in src.data.indices) if (out.data[i] < src.data[i] - 1e-4f) darkened++
        darkened shouldBe 0
    }

    @Test
    fun `zero intensity is the identity`() {
        val src = sun(32, 32)
        RasterMath.meanAbsDiff(src, LightRays.apply(src, Vec2(8f, 8f), intensity = 0f)) shouldBe 0f
    }
}
