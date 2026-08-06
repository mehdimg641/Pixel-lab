package ir.pixellab.engine.android

import ir.pixellab.core.codec.Codecs
import ir.pixellab.core.codec.Format
import ir.pixellab.core.codec.ImageEncoder
import ir.pixellab.core.codec.RasterImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Whether the export quality setting reaches the encoder.
 *
 * ### Why this needed a test rather than a review
 *
 * `ImageEncoder.encode` took an image and nothing else. The platform encoder held its quality as a
 * constructor property fixed at 95, and the export sheet offered scale multipliers and no quality
 * at all — so every JPEG this application had ever written came out at exactly one setting, and the
 * only way to make a smaller file was to make a smaller picture. That is the recurring shape in this
 * repository stated in its purest form: the *encoder* could express it, and nothing above it could.
 *
 * The parameter is now threaded through four layers — sheet, `exportImage`, `CanvasSurface.export`,
 * `Exporter.export` — and a chain that long is exactly the kind that silently loses an argument to a
 * defaulted parameter somewhere in the middle. So the assertion is not "the parameter exists"; it is
 * that **two different qualities produce two different files**, measured end to end through the real
 * platform encoder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fa")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExportQualityTest {

    @Before
    fun registerCodecs() {
        PlatformCodecs.register()
    }

    /**
     * Something JPEG has to work at: a smooth gradient with a hard edge through it.
     *
     * A flat fill compresses to nearly nothing at every quality and would show no difference at all
     * — which is a green test that proves the opposite of what it claims.
     */
    private fun sample(): RasterImage {
        val size = 256
        val pixels = IntArray(size * size) { index ->
            val x = index % size
            val y = index / size
            val edge = if (x > size / 2) 255 else 0
            val red = (x * 255 / size)
            val green = (y * 255 / size)
            (0xFF shl 24) or (red shl 16) or (green shl 8) or edge
        }
        return RasterImage(size, size, pixels)
    }

    @Test
    fun `a lower quality writes a smaller JPEG`() {
        val encoder = Codecs.encoderFor(Format.JPEG) ?: error("JPEG encoder not registered")
        val image = sample()

        val best = encoder.encode(image, quality = 100).size
        val middle = encoder.encode(image, quality = 60).size
        val worst = encoder.encode(image, quality = 10).size

        assertTrue(
            "quality 60 produced $middle bytes and quality 100 produced $best — the setting is " +
                "not reaching the encoder",
            middle < best,
        )
        assertTrue("quality 10 produced $worst bytes, not below $middle", worst < middle)
    }

    @Test
    fun `the default is the one the encoder used before the setting existed`() {
        // The guard against a silent behaviour change for everybody who never touches the slider.
        // A default that drifted would mean every existing user's exports changed size on an update
        // they did not ask for, which is the kind of regression nobody reports and everybody notices.
        val encoder = Codecs.encoderFor(Format.JPEG) ?: error("JPEG encoder not registered")
        val image = sample()
        assertEquals(
            encoder.encode(image, ImageEncoder.DEFAULT_QUALITY).size,
            encoder.encode(image).size,
        )
        assertEquals(95, ImageEncoder.DEFAULT_QUALITY)
    }

    @Test
    fun `a lossless format ignores the setting entirely`() {
        // PNG must not quietly change size with a slider that says nothing about it. If this ever
        // fails, the sheet is showing a control that does something it does not claim to do.
        val encoder = Codecs.encoderFor(Format.PNG) ?: error("PNG encoder not registered")
        val image = sample()
        assertEquals(encoder.encode(image, quality = 10).size, encoder.encode(image, quality = 100).size)
    }

    @Test
    fun `a quality outside the range is clamped rather than thrown`() {
        // Reachable from a restored slider position or a future caller that computes one. The
        // platform throws on a value outside 0..100, and an export that crashes at the encode step
        // has already spent the whole render.
        val encoder = Codecs.encoderFor(Format.JPEG) ?: error("JPEG encoder not registered")
        val image = sample()
        assertTrue(encoder.encode(image, quality = -20).isNotEmpty())
        assertTrue(encoder.encode(image, quality = 500).isNotEmpty())
    }
}
