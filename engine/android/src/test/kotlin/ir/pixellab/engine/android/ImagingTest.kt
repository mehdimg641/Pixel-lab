package ir.pixellab.engine.android

import io.kotest.matchers.shouldBe
import ir.pixellab.core.codec.RasterImage
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.Vec2
import ir.pixellab.core.paint.Marquee
import ir.pixellab.core.paint.PixelSelection
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Cutting out, retouching and warping.
 *
 * The algorithms themselves are tested in `core:imaging`; what is checked here is that they are
 * being *used* correctly — the right guide handed to the matte solver, alpha kept out of a
 * frequency split, a warp that stays where it was told. Every one of those is a mistake that
 * produces a plausible picture rather than an error.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImagingTest {

    private val size = 48

    /** A red disc on green, which is the shape of every cut-out problem: two similar luminances. */
    private fun subject(): RasterImage {
        val pixels = IntArray(size * size) { i ->
            val x = i % size
            val y = i / size
            val dx = x - size / 2f
            val dy = y - size / 2f
            if (dx * dx + dy * dy < (size / 3f) * (size / 3f)) 0xFFCC3322.toInt() else 0xFF22AA55.toInt()
        }
        return RasterImage(size, size, pixels)
    }

    private fun disc(radius: Float) =
        Marquee.ellipse(size, size, Rect(size / 2f - radius, size / 2f - radius, size / 2f + radius, size / 2f + radius))

    private fun alphaAt(image: RasterImage, x: Int, y: Int) = (image.pixels[y * size + x] ushr 24) and 0xFF

    // ---- the bridge --------------------------------------------------------------------------

    @Test
    fun `pixels survive a round trip through the float buffer`() {
        val original = RasterImage(4, 4, IntArray(16) { 0xFF3366CC.toInt() })
        val back = original.toRaster().toImage()
        // Every stage of every algorithm runs on these floats; a conversion that lost a bit here
        // would show as a slow drift across a chain of filters rather than as an obvious fault.
        back.pixels.toList() shouldBe original.pixels.toList()
    }

    @Test
    fun `transparency survives the round trip`() {
        val original = RasterImage(2, 2, intArrayOf(0, 0x80FF0000.toInt(), -1, 0x40000000))
        original.toRaster().toImage().pixels.toList() shouldBe original.pixels.toList()
    }

    @Test
    fun `a selection becomes a mask the algorithms can read`() {
        val selection = Marquee.rectangle(16, 16, Rect(4f, 4f, 12f, 12f))
        val raster = selection.toRaster()
        raster.channels shouldBe 1
        (raster[8, 8] > 0.9f) shouldBe true
        (raster[1, 1] < 0.1f) shouldBe true
    }

    @Test
    fun `a solved matte becomes a greyscale mask image`() {
        val mask = PixelSelection.everything(8, 8).toRaster().toMaskImage()
        // Grey *and* opaque: the renderer reads a mask through its alpha, and a mask written with
        // only its luminance set would hide every layer it was attached to.
        ((mask.pixels[0] ushr 24) and 0xFF) shouldBe 255
        (mask.pixels[0] and 0xFF) shouldBe 255
    }

    // ---- cutout ------------------------------------------------------------------------------

    @Test
    fun `refining keeps the subject and drops the background`() {
        val result = Cutout.refine(subject(), disc(size / 3f))
        (alphaAt(result.decontaminated, size / 2, size / 2) > 200) shouldBe true
        alphaAt(result.decontaminated, 1, 1) shouldBe 0
    }

    @Test
    fun `the edge comes back soft rather than binary`() {
        val result = Cutout.refine(subject(), disc(size / 3f))
        var partial = 0
        for (i in result.decontaminated.pixels.indices) {
            val a = (result.decontaminated.pixels[i] ushr 24) and 0xFF
            if (a in 1..254) partial++
        }
        // A binary matte is exactly what makes a cut-out look pasted on, and the whole reason the
        // trimap and the alpha solve exist.
        (partial > 20) shouldBe true
    }

    @Test
    fun `a wider band leaves more of the image unresolved`() {
        val narrow = Cutout.unknownFraction(disc(size / 3f), band = 2f)
        val wide = Cutout.unknownFraction(disc(size / 3f), band = 8f)
        // Hair needs a much wider band than a shoulder; the refine brush exists because one global
        // width either misses strands or spends its time solving over flat regions.
        (wide > narrow) shouldBe true
    }

    @Test
    fun `refine strokes widen the band where they were painted`() {
        val strokes = Marquee.rectangle(size, size, Rect(0f, 0f, size.toFloat(), 4f))
        val plain = Cutout.refine(subject(), disc(size / 3f))
        val brushed = Cutout.refine(subject(), disc(size / 3f), refineStrokes = strokes)
        // The top strip was declared unknown, so it is solved rather than taken from the rough
        // selection — which is what "look harder here" has to mean.
        (brushed.decontaminated.pixels.toList() != plain.decontaminated.pixels.toList()) shouldBe true
    }

    @Test
    fun `decontamination removes the background's colour from the edge`() {
        val result = Cutout.refine(subject(), disc(size / 3f))
        // Sampled just inside the edge, where a naive cut-out leaves a green fringe from the
        // background it was photographed against.
        val edge = result.decontaminated.pixels[(size / 2) * size + (size / 2 + size / 3 - 2)]
        val green = (edge shr 8) and 0xFF
        val red = (edge shr 16) and 0xFF
        (red > green) shouldBe true
    }

    @Test
    fun `an empty selection produces an empty matte rather than throwing`() {
        val result = Cutout.refine(subject(), PixelSelection.nothing(size, size))
        alphaAt(result.decontaminated, size / 2, size / 2) shouldBe 0
    }

    // ---- retouch -----------------------------------------------------------------------------

    @Test
    fun `smoothing at zero leaves the image untouched`() {
        val original = subject()
        Retouch.smoothSkin(original, amount = 0f) shouldBe original
    }

    @Test
    fun `smoothing evens out tone`() {
        val noisy = RasterImage(
            size, size,
            IntArray(size * size) { i ->
                // Alternating light and dark, which is what blotchy skin looks like to the split.
                val v = if ((i / size + i % size) % 2 == 0) 0xC0 else 0x60
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            },
        )
        val smoothed = Retouch.smoothSkin(noisy, amount = 1f, radius = 4f)
        val spread = { image: RasterImage ->
            val values = image.pixels.map { (it shr 16) and 0xFF }
            (values.max() - values.min())
        }
        (spread(smoothed) < spread(noisy)) shouldBe true
    }

    @Test
    fun `smoothing keeps transparency exactly`() {
        val partly = RasterImage(size, size, IntArray(size * size) { 0x80112233.toInt() })
        val smoothed = Retouch.smoothSkin(partly, amount = 1f)
        // Alpha is not a frequency. Separating and recombining it leaves a halo wherever the layer
        // is partly transparent — visible on every soft-edged element in a design.
        smoothed.pixels.all { (it ushr 24) and 0xFF == 0x80 } shouldBe true
    }

    @Test
    fun `healing replaces a blemish with what surrounds it`() {
        // A blue patch inside the red disc. Healing a *flat* region would prove nothing — a correct
        // inpaint reproduces the surrounding colour, so the pixel would come back unchanged and the
        // test would pass whether or not anything ran.
        val blemished = subject().let { base ->
            val pixels = base.pixels.copyOf()
            for (y in 21 until 27) for (x in 21 until 27) pixels[y * size + x] = 0xFF2233CC.toInt()
            RasterImage(size, size, pixels)
        }
        val hole = Marquee.rectangle(size, size, Rect(20f, 20f, 28f, 28f))
        val healed = Retouch.heal(blemished, hole, passes = 2)

        val pixel = healed.pixels[24 * size + 24]
        val red = (pixel shr 16) and 0xFF
        val blue = pixel and 0xFF
        (red > blue) shouldBe true
        // And nothing outside the painted region moved.
        healed.pixels[2 * size + 2] shouldBe blemished.pixels[2 * size + 2]
    }

    @Test
    fun `healing nothing is not an operation`() {
        val original = subject()
        Retouch.heal(original, PixelSelection.nothing(size, size)) shouldBe original
    }

    @Test
    fun `sharpening raises local contrast`() {
        val soft = RasterImage(
            size, size,
            IntArray(size * size) { i ->
                val x = i % size
                val v = ((x * 255) / size).coerceIn(0, 255)
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            },
        )
        val sharp = Retouch.sharpen(soft, amount = 2f)
        val gradientOf = { image: RasterImage ->
            var total = 0
            for (x in 1 until size) {
                total += kotlin.math.abs(
                    ((image.pixels[10 * size + x] shr 16) and 0xFF) -
                        ((image.pixels[10 * size + x - 1] shr 16) and 0xFF),
                )
            }
            total
        }
        (gradientOf(sharp) >= gradientOf(soft)) shouldBe true
    }

    // ---- liquify -----------------------------------------------------------------------------

    @Test
    fun `an untouched warp returns the same image`() {
        val original = subject()
        Liquify(original).apply() shouldBe original
    }

    @Test
    fun `a push moves pixels`() {
        val original = subject()
        val warp = Liquify(original).apply { push(Vec2(24f, 24f), Vec2(30f, 24f)) }
        (warp.apply().pixels.toList() != original.pixels.toList()) shouldBe true
    }

    @Test
    fun `a push does not move the far corner`() {
        val original = subject()
        val warp = Liquify(original).apply {
            brushSize = 10f
            push(Vec2(24f, 24f), Vec2(28f, 24f))
        }
        val warped = warp.apply()
        // The falloff of a least-squares solve never quite reaches zero, so without a ring of fixed
        // anchors a nudge to a chin also moves the shoulder.
        warped.pixels[0] shouldBe original.pixels[0]
    }

    @Test
    fun `bloat and pucker are the same control in two directions`() {
        val original = subject()
        val bloated = Liquify(original).apply { scale(Vec2(24f, 24f), 0.3f) }.apply()
        val puckered = Liquify(original).apply { scale(Vec2(24f, 24f), -0.3f) }.apply()
        (bloated.pixels.toList() != puckered.pixels.toList()) shouldBe true
    }

    @Test
    fun `a twirl rotates around its centre`() {
        val original = subject()
        val twirled = Liquify(original).apply { twirl(Vec2(24f, 24f), 30f) }.apply()
        (twirled.pixels.toList() != original.pixels.toList()) shouldBe true
    }

    @Test
    fun `resetting a warp throws the strokes away`() {
        val original = subject()
        val warp = Liquify(original).apply {
            push(Vec2(24f, 24f), Vec2(30f, 24f))
            reset()
        }
        warp.isEmpty shouldBe true
        warp.apply() shouldBe original
    }
}
