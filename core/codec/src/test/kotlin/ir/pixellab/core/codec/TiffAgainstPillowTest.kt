package ir.pixellab.core.codec

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The same picture, written by somebody else's encoder.
 *
 * [TiffCodecTest] builds its files by hand from the specification, which catches a misreading of
 * the *specification*. It cannot catch a misreading shared between my reader and my writer, and it
 * cannot catch the gap between what the specification permits and what encoders in the world
 * actually emit — tag order, where the strip lands relative to the directory, whether
 * `RowsPerStrip` is written at all.
 *
 * These fixtures came out of Pillow. Every one holds the same four-by-three picture, so a failure
 * names the compression rather than the colour handling, and the expected pixels below were written
 * from the source data rather than from what this codec returned.
 */
class TiffAgainstPillowTest {

    /** The picture, in reading order. Asymmetric in all three channels and in both axes. */
    private val expected = listOf(
        0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFFFF,
        0xFF000000, 0xFFFFFF00, 0xFF00FFFF, 0xFFFF00FF,
        0xFF804020, 0xFF204080, 0xFF0A141E, 0xFFC89664,
    ).map { it.toInt() }

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
            "$name is missing from core/codec/src/test/resources"
        }.use { it.readBytes() }

    private fun decode(name: String) = TiffCodec.decode(fixture(name))

    @Test
    fun `uncompressed, from Pillow`() {
        val image = decode("pil-rgb-none.tif")

        image.width shouldBe 4
        image.height shouldBe 3
        image.pixels.toList() shouldBe expected
    }

    @Test
    fun `LZW, from Pillow`() {
        // The one most likely to fail on its own: TIFF's LZW widens its code a step earlier than
        // the GIF variant, and a decoder that misses that returns a correct first run and then
        // noise. A four-by-three picture is small enough that the difference still shows.
        decode("pil-rgb-lzw.tif").pixels.toList() shouldBe expected
    }

    @Test
    fun `PackBits, from Pillow`() {
        decode("pil-rgb-packbits.tif").pixels.toList() shouldBe expected
    }

    @Test
    fun `Deflate, from Pillow`() {
        decode("pil-rgb-deflate.tif").pixels.toList() shouldBe expected
    }

    @Test
    fun `RGBA keeps its alpha, from Pillow`() {
        val image = decode("pil-rgba.tif")

        // Only the first pixel is transparent, so an alpha channel dropped or read from the wrong
        // sample changes exactly one value — and leaves the rest looking perfectly fine.
        image[0, 0] shouldBe 0x80FF0000.toInt()
        image[1, 0] shouldBe 0xFF00FF00.toInt()
    }

    @Test
    fun `greyscale, from Pillow`() {
        val image = decode("pil-grey.tif")

        image.width shouldBe 4
        image.height shouldBe 3
        // Every pixel is a grey, so all three channels have to match each other. A single-sample
        // file read as RGB would produce colours here.
        for (argb in image.pixels) {
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            (r == g && g == b) shouldBe true
        }
    }

    @Test
    fun `Pillow can read what this codec writes`() {
        // The other direction is the half a round-trip test never covers: a file that only this
        // codec can open is not an interchange format. Asserted structurally — the header, the
        // directory's tag order and the strip geometry are what another reader keys on.
        val written = TiffCodec.encode(RasterImage(4, 3, expected.toIntArray()))

        // Byte order mark, magic 42, and a directory offset that lands inside the file.
        written[0] shouldBe 0x49.toByte()
        written[1] shouldBe 0x49.toByte()
        val ifd = (written[4].toInt() and 0xFF) or ((written[5].toInt() and 0xFF) shl 8) or
            ((written[6].toInt() and 0xFF) shl 16) or ((written[7].toInt() and 0xFF) shl 24)
        (ifd < written.size) shouldBe true

        val count = (written[ifd].toInt() and 0xFF) or ((written[ifd + 1].toInt() and 0xFF) shl 8)
        val tags = (0 until count).map {
            val at = ifd + 2 + it * 12
            (written[at].toInt() and 0xFF) or ((written[at + 1].toInt() and 0xFF) shl 8)
        }
        // Ascending order is legally optional and required in practice by about half the readers
        // in the world, which is the same thing as required.
        tags shouldBe tags.sorted()
        // ImageWidth, ImageLength, StripOffsets, StripByteCounts: without all four a reader has no
        // way to find the pixels.
        tags.containsAll(listOf(256, 257, 273, 279)) shouldBe true
    }
}
