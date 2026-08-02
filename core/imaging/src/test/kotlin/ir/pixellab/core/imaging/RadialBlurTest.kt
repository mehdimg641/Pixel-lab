package ir.pixellab.core.imaging

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Vec2
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Spin and zoom blur.
 *
 * Both are the same sampler, so the tests are mostly about the properties that distinguish a
 * convincing radial blur from the ghosted version: the centre stays sharp, the edge does not, the
 * image does not drift, and nothing brightens or darkens overall.
 */
class RadialBlurTest {

    private val centre = Vec2(31.5f, 31.5f)

    /** A ripple every thirteen pixels: wide enough to resolve, tight enough for a zoom to cross. */
    private val RIPPLE = 0.5f

    /**
     * Concentric rings, so a spin leaves the image alone and a zoom cannot.
     *
     * A smooth ripple rather than hard bands: a rasterised hard edge has a staircase on it, and
     * sampling along an arc reads across that staircase, so hard rings would measure the
     * rasterisation rather than the filter.
     */
    private fun rings(size: Int = 64): Raster {
        val r = Raster(size, size, 1)
        for (y in 0 until size) for (x in 0 until size) {
            val d = hypot(x - centre.x, y - centre.y)
            r[x, y, 0] = 0.5f + 0.5f * kotlin.math.sin(d * RIPPLE)
        }
        return r
    }

    /** Spokes, so a spin has something to smear and a zoom does not. */
    private fun spokes(size: Int = 64): Raster {
        val r = Raster(size, size, 1)
        for (y in 0 until size) for (x in 0 until size) {
            val angle = kotlin.math.atan2(y - centre.y, x - centre.x)
            r[x, y, 0] = if (((angle + Math.PI) / (Math.PI / 4)).toInt() % 2 == 0) 1f else 0f
        }
        return r
    }

    private fun meanOf(r: Raster): Float = r.data.average().toFloat()

    @Test
    fun `zero amount is the image back`() {
        val src = spokes()
        val out = RadialBlur.apply(src, centre, 0f, RadialBlur.Kind.SPIN)
        out.data.contentEquals(src.data) shouldBe true
    }

    @Test
    fun `a spin leaves the centre sharp and smears the edge`() {
        val src = spokes()
        val out = RadialBlur.apply(src, centre, 0.6f, RadialBlur.Kind.SPIN)

        // Right at the centre a pixel travels nowhere, which is what makes the effect read as
        // rotation rather than as a uniform blur.
        val near = variationAround(out, 32, 32, 2)
        val far = variationAround(out, 60, 32, 2)
        (near > far) shouldBe true
    }

    @Test
    fun `the two methods smear along their own axis`() {
        val src = rings()
        val spun = disturbance(src, RadialBlur.apply(src, centre, 0.5f, RadialBlur.Kind.SPIN))
        val zoomed = disturbance(src, RadialBlur.apply(src, centre, 0.5f, RadialBlur.Kind.ZOOM))

        // A ring is invariant under rotation, so a correct spin has almost nothing to average and
        // leaves it nearly alone; a zoom crosses every ring and wipes the pattern out. A sampler
        // that had the two mixed up — or that drifted off the arc — would not separate them.
        (zoomed > spun * 4f) shouldBe true
    }

    @Test
    fun `neither method changes the overall brightness much`() {
        val src = spokes()
        for (kind in RadialBlur.Kind.entries) {
            val out = RadialBlur.apply(src, centre, 0.7f, kind)
            // A blur that brightens or darkens is one whose samples are weighted wrongly, and it
            // shows up as a halo the moment the layer is composited over anything.
            meanOf(out) shouldBe meanOf(src).plusOrMinus(0.06f)
        }
    }

    @Test
    fun `a zoom does not move the subject`() {
        // A bright dot off-centre. A trail centred on the pixel rather than starting at it would
        // pull it towards the middle of the frame, which on a portrait is instantly visible.
        val src = Raster(64, 64, 1)
        for (y in 24..28) for (x in 44..48) src[x, y, 0] = 1f

        val out = RadialBlur.apply(src, centre, 0.4f, RadialBlur.Kind.ZOOM)
        val brightest = out.data.indices.maxByOrNull { out.data[it] }!!
        val x = brightest % 64
        // The peak may spread, but it must not walk inwards past where it started.
        (x >= 44) shouldBe true
    }

    @Test
    fun `sample count follows how far a pixel actually travels`() {
        // The reason the filter is affordable: the middle of the image costs one tap and the corner
        // costs many, rather than every pixel paying for the worst case.
        RadialBlur.samplesFor(0f) shouldBe 1
        (RadialBlur.samplesFor(30f) > RadialBlur.samplesFor(4f)) shouldBe true
        (RadialBlur.samplesFor(100_000f) < 1000) shouldBe true
    }

    @Test
    fun `colour images keep their channel count`() {
        val src = Raster(32, 32, 4).fill(0.5f)
        val out = RadialBlur.apply(src, Vec2(16f, 16f), 0.5f, RadialBlur.Kind.ZOOM)
        out.channels shouldBe 4
        out.width shouldBe 32
    }

    /** Mean absolute change across the whole image; a max would only report the sharpest edge. */
    private fun disturbance(before: Raster, after: Raster): Float {
        var total = 0f
        for (i in before.data.indices) total += abs(after.data[i] - before.data[i])
        return total / before.data.size
    }

    /** How much a small neighbourhood varies — high where detail survived, low where it smeared. */
    private fun variationAround(r: Raster, x: Int, y: Int, radius: Int): Float {
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (dy in -radius..radius) for (dx in -radius..radius) {
            val v = r.clamped(x + dx, y + dy, 0)
            if (v < min) min = v
            if (v > max) max = v
        }
        return max - min
    }
}
