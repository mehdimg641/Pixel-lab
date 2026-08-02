package ir.pixellab.core.imaging

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The histogram.
 *
 * The readout that turns judging an image by eye into measuring it. Two of its decisions are the
 * ones that decide whether it is usable at all: skipping transparent pixels, and scaling the
 * display logarithmically. Both are invisible until they are wrong, and then the histogram
 * describes something other than the picture.
 */
class HistogramTest {

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    private fun image(vararg pixels: Int) = pixels

    @Test
    fun `a flat image is a single spike`() {
        val histogram = Histogram.of(IntArray(100) { argb(255, 128, 128, 128) }, HistogramChannel.RED)
        histogram.counts[128] shouldBe 100
        histogram.total shouldBe 100L
        histogram.peak shouldBe 100
    }

    @Test
    fun `each channel is counted on its own`() {
        val pixels = IntArray(10) { argb(255, 200, 100, 50) }
        Histogram.of(pixels, HistogramChannel.RED).counts[200] shouldBe 10
        Histogram.of(pixels, HistogramChannel.GREEN).counts[100] shouldBe 10
        Histogram.of(pixels, HistogramChannel.BLUE).counts[50] shouldBe 10
    }

    @Test
    fun `luminance uses the same weights the renderer does`() {
        // Pure green reads far brighter than pure blue. A histogram computed with different weights
        // than the pipeline would disagree with the picture it describes, which is worse than none.
        val green = Histogram.of(image(argb(255, 0, 255, 0)), HistogramChannel.LUMINANCE)
        val blue = Histogram.of(image(argb(255, 0, 0, 255)), HistogramChannel.LUMINANCE)
        (green.mean() > blue.mean() * 5f) shouldBe true
    }

    @Test
    fun `transparent pixels are skipped for colour but counted for alpha`() {
        val pixels = image(
            argb(255, 200, 200, 200),
            argb(0, 0, 0, 0),
            argb(0, 0, 0, 0),
        )
        val red = Histogram.of(pixels, HistogramChannel.RED)
        // Counting them means a cut-out subject's histogram is dominated by whatever colour was
        // left behind the transparency — usually black — so the reading describes the file rather
        // than the picture.
        red.total shouldBe 1L
        red.counts[0] shouldBe 0

        val alpha = Histogram.of(pixels, HistogramChannel.ALPHA)
        alpha.total shouldBe 3L
        alpha.counts[0] shouldBe 2
    }

    @Test
    fun `clipping is reported at both ends`() {
        val pixels = image(
            argb(255, 0, 0, 0),
            argb(255, 0, 0, 0),
            argb(255, 255, 255, 255),
            argb(255, 128, 128, 128),
        )
        val histogram = Histogram.of(pixels, HistogramChannel.RED)
        // A screen cannot show the difference between 254 and 255; this is the only way to see it
        // before the export is written.
        histogram.clippedBlacks shouldBe 2
        histogram.clippedWhites shouldBe 1
    }

    @Test
    fun `the display is scaled logarithmically`() {
        // One dominant tone and a scattering of others, which is what any real photograph with a
        // sky or a studio backdrop looks like.
        val counts = IntArray(Histogram.BINS)
        counts[128] = 100_000
        counts[64] = 100
        val heights = Histogram(counts, HistogramChannel.LUMINANCE).normalised()

        heights[128] shouldBe 1f.plusOrMinus(1e-5f)
        // Linearly this bin would be one thousandth of the peak and invisible. Logarithmically it
        // is still readable, which is the whole point.
        (heights[64] > 0.35f) shouldBe true
        heights[200] shouldBe 0f
    }

    @Test
    fun `an empty histogram normalises to nothing rather than dividing by zero`() {
        Histogram(IntArray(Histogram.BINS), HistogramChannel.RED).normalised().all { it == 0f } shouldBe true
    }

    @Test
    fun `percentiles walk the distribution`() {
        val counts = IntArray(Histogram.BINS)
        for (bin in 100..199) counts[bin] = 1
        val histogram = Histogram(counts, HistogramChannel.LUMINANCE)

        histogram.percentile(0f) shouldBe 100
        histogram.percentile(0.5f) shouldBe 149
        histogram.percentile(1f) shouldBe 199
    }

    @Test
    fun `auto levels ignores a handful of stray pixels`() {
        val counts = IntArray(Histogram.BINS)
        for (bin in 60..190) counts[bin] = 1000
        // One hot pixel at the top and one dead pixel at the bottom.
        counts[0] = 1
        counts[255] = 1
        val (black, white) = Histogram.autoLevels(Histogram(counts, HistogramChannel.LUMINANCE))!!

        // Clipping from the extremes instead would let a single stray pixel decide the white point
        // for the whole image, and auto-levels would do nothing at all.
        (black in 55..65) shouldBe true
        (white in 185..195) shouldBe true
    }

    @Test
    fun `auto levels refuses an image with no range to stretch`() {
        val counts = IntArray(Histogram.BINS)
        counts[128] = 5000
        // Stretching this would only amplify noise, and dividing by the range would divide by zero.
        Histogram.autoLevels(Histogram(counts, HistogramChannel.LUMINANCE)) shouldBe null
        Histogram.autoLevels(Histogram(IntArray(Histogram.BINS), HistogramChannel.RED)) shouldBe null
    }

    @Test
    fun `the mean answers is this too dark`() {
        val dark = Histogram.of(IntArray(50) { argb(255, 30, 30, 30) }, HistogramChannel.LUMINANCE)
        val light = Histogram.of(IntArray(50) { argb(255, 220, 220, 220) }, HistogramChannel.LUMINANCE)
        dark.mean() shouldBe 30f.plusOrMinus(1f)
        light.mean() shouldBe 220f.plusOrMinus(1f)
    }

    @Test
    fun `every channel has its own Persian name`() {
        HistogramChannel.entries.map { it.persianLabel }.distinct().size shouldBe
            HistogramChannel.entries.size
    }

    @Test
    fun `a histogram must have the right number of bins`() {
        runCatching { Histogram(IntArray(10), HistogramChannel.RED) }.isFailure shouldBe true
    }
}
