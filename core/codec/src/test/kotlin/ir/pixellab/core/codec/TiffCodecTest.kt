package ir.pixellab.core.codec

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Test

/**
 * TIFF, against files this codec did not write.
 *
 * A round trip through one implementation proves the two halves agree with each other and nothing
 * more — it passes just as happily when both ends share a misreading of the specification. So most
 * of what is below builds byte-exact files by hand from the specification's own layout and asserts
 * the decoder gets the picture out.
 *
 * The three compressions are each tested on the same four pixels, so a failure names the
 * compression rather than the colour handling.
 */
class TiffCodecTest {

    // Deliberately asymmetric: a red, a green, a blue and a half-transparent white. Any channel
    // swap, row flip or alpha drop changes at least one of them.
    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val ghost = 0x80FFFFFF.toInt()

    // ---- files built by hand from the specification -----------------------------------------

    /**
     * Assembles a little-endian baseline TIFF.
     *
     * Written out longhand rather than by calling the encoder, which is the entire point: if this
     * builder and the encoder shared code they would share bugs, and the test would confirm the
     * codec is self-consistent instead of confirming it is right.
     */
    private fun tiff(
        width: Int,
        height: Int,
        samples: Int,
        photometric: Int,
        compression: Int,
        pixelData: ByteArray,
        predictor: Int = 1,
    ): ByteArray {
        val header = ByteArrayOutputStream()
        header.write(byteArrayOf(0x49, 0x49, 42, 0))
        val pixelOffset = 8
        le32(header, pixelOffset + pixelData.size)
        header.write(pixelData)

        val tags = listOf(
            Triple(256, 4, width),
            Triple(257, 4, height),
            Triple(259, 3, compression),
            Triple(262, 3, photometric),
            Triple(273, 4, pixelOffset),
            Triple(277, 3, samples),
            Triple(278, 4, height),
            Triple(279, 4, pixelData.size),
            Triple(284, 3, 1),
            Triple(317, 3, predictor),
        ).sortedBy { it.first }

        val bitsOffset = pixelOffset + pixelData.size + 2 + 12 * (tags.size + 1) + 4
        le16(header, tags.size + 1)
        val all = (tags + Triple(258, 3, if (samples == 1) 8 else bitsOffset)).sortedBy { it.first }
        for ((tag, type, value) in all) {
            le16(header, tag)
            le16(header, type)
            le32(header, if (tag == 258 && samples > 1) samples else 1)
            le32(header, value)
        }
        le32(header, 0)
        repeat(samples) { le16(header, 8) }
        return header.toByteArray()
    }

    private fun le16(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
    }

    private fun le32(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
    }

    private val rgbBytes = byteArrayOf(
        0xFF.toByte(), 0, 0,
        0, 0xFF.toByte(), 0,
        0, 0, 0xFF.toByte(),
        0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
    )

    @Test
    fun `reads uncompressed RGB`() {
        val image = TiffCodec.decode(tiff(2, 2, 3, 2, 1, rgbBytes))

        image.width shouldBe 2
        image.height shouldBe 2
        image[0, 0] shouldBe red
        image[1, 0] shouldBe green
        image[0, 1] shouldBe blue
    }

    @Test
    fun `reads RGBA and keeps the alpha`() {
        val rgba = byteArrayOf(
            0xFF.toByte(), 0, 0, 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x80.toByte(),
        )
        val image = TiffCodec.decode(tiff(2, 1, 4, 2, 1, rgba))

        image[0, 0] shouldBe red
        // The half-transparent pixel is the one a decoder that assumes opaque quietly ruins, and a
        // cut-out saved as TIFF is exactly where that shows up.
        image[1, 0] shouldBe ghost
    }

    @Test
    fun `reads greyscale, both ways round`() {
        val grey = byteArrayOf(0, 0xFF.toByte())

        val blackIsZero = TiffCodec.decode(tiff(2, 1, 1, 1, 1, grey))
        blackIsZero[0, 0] shouldBe 0xFF000000.toInt()
        blackIsZero[1, 0] shouldBe 0xFFFFFFFF.toInt()

        // WhiteIsZero is what fax and a lot of scanner output uses. Read as BlackIsZero it produces
        // a perfect negative, which is a failure that looks like an artistic choice.
        val whiteIsZero = TiffCodec.decode(tiff(2, 1, 1, 0, 1, grey))
        whiteIsZero[0, 0] shouldBe 0xFFFFFFFF.toInt()
        whiteIsZero[1, 0] shouldBe 0xFF000000.toInt()
    }

    @Test
    fun `reads PackBits`() {
        // A literal run of the four RGB pixels, then the no-op byte Photoshop emits as padding.
        val packed = ByteArrayOutputStream()
        packed.write(rgbBytes.size - 1)
        packed.write(rgbBytes)
        packed.write(0x80)

        val image = TiffCodec.decode(tiff(2, 2, 3, 2, 32773, packed.toByteArray()))

        image[0, 0] shouldBe red
        image[1, 1] shouldBe 0xFFFFFFFF.toInt()
    }

    @Test
    fun `reads Deflate`() {
        val deflater = java.util.zip.Deflater()
        deflater.setInput(rgbBytes)
        deflater.finish()
        val buffer = ByteArray(256)
        val n = deflater.deflate(buffer)
        deflater.end()

        val image = TiffCodec.decode(tiff(2, 2, 3, 2, 8, buffer.copyOf(n)))

        image[0, 0] shouldBe red
        image[1, 0] shouldBe green
    }

    @Test
    fun `undoes horizontal differencing`() {
        // The same four pixels stored as differences from the pixel to their left, which is what a
        // predictor-2 file holds. Left un-undone the picture is a smear rather than noise, so it is
        // the kind of wrong that survives a careless eyeball check.
        // Per row, not across the whole buffer: differencing restarts at every row start, and a
        // test that differences across the row boundary is testing a file no encoder writes.
        val differenced = rgbBytes.copyOf()
        val stride = 2 * 3
        for (y in 0 until 2) {
            for (x in (stride - 1) downTo 3) {
                differenced[y * stride + x] =
                    (differenced[y * stride + x] - differenced[y * stride + x - 3]).toByte()
            }
        }
        val image = TiffCodec.decode(tiff(2, 2, 3, 2, 1, differenced, predictor = 2))

        image[0, 0] shouldBe red
        image[1, 0] shouldBe green
        image[0, 1] shouldBe blue
    }

    @Test
    fun `reads big-endian files`() {
        // `MM` is half the TIFFs in the world; assuming `II` opens exactly the other half.
        val little = tiff(2, 2, 3, 2, 1, rgbBytes)
        val big = little.copyOf()
        big[0] = 0x4D; big[1] = 0x4D
        // Byte-swap the magic, the first offset, and every 12-byte entry's fixed-width fields.
        fun swap16(at: Int) { val t = big[at]; big[at] = big[at + 1]; big[at + 1] = t }
        fun swap32(at: Int) {
            val t0 = big[at]; val t1 = big[at + 1]
            big[at] = big[at + 3]; big[at + 1] = big[at + 2]
            big[at + 2] = t1; big[at + 3] = t0
        }
        swap16(2)
        val ifd = 8 + rgbBytes.size
        swap32(4)
        val count = little[ifd].toInt() and 0xFF
        swap16(ifd)
        for (i in 0 until count) {
            val at = ifd + 2 + i * 12
            val type = little[at + 2].toInt() and 0xFF
            val n = little[at + 4].toInt() and 0xFF
            val width = if (type == 3) 2 else 4
            swap16(at); swap16(at + 2); swap32(at + 4)
            // Two different things live in the four-byte value slot and they byte-swap differently.
            // A value that fits is stored *left-justified*, so a lone SHORT occupies the first two
            // bytes — swapping all four moves it into the padding, which is what made this read
            // PlanarConfiguration as 256 and reject the file as planar. A value that does *not*
            // fit is replaced by a 32-bit offset whatever the field's own type says, which is the
            // case for BitsPerSample's three shorts.
            if (width * n <= 4 && type == 3) swap16(at + 8) else swap32(at + 8)
        }
        // The BitsPerSample shorts live past the directory's terminating zero and are just as
        // byte-ordered as everything else. Missing them reads 8 as 2048 and rejects the file.
        val trailing = ifd + 2 + count * 12 + 4
        var at = trailing
        while (at + 1 < big.size) { swap16(at); at += 2 }

        val image = TiffCodec.decode(big)
        image[0, 0] shouldBe red
        image[1, 0] shouldBe green
    }

    // ---- what it refuses to guess at --------------------------------------------------------

    @Test
    fun `refuses 16-bit rather than producing a plausible wrong picture`() {
        val file = tiff(1, 1, 3, 2, 1, ByteArray(6))
        // Rewrite BitsPerSample to 16. Reading it as 8-bit would halve the width and shift every
        // channel — an image that looks like a decoding bug is better than one that looks like art.
        val at = file.indexOfTag(258)
        file[at + 8] = 16

        val thrown = shouldThrow<CodecException> { TiffCodec.decode(file) }
        thrown.message.orEmpty() shouldContain "8-bit"
    }

    @Test
    fun `refuses tiled files by name`() {
        val image = RasterImage(1, 1, intArrayOf(red))
        val written = TiffCodec.encode(image)
        // Splice in a TileWidth tag by claiming one more directory entry than there is room for is
        // fiddly; instead assert the guard exists on a file that declares it.
        val tiled = tiff(1, 1, 3, 2, 1, ByteArray(3)).withTag(322, 3, 256)

        val thrown = shouldThrow<CodecException> { TiffCodec.decode(tiled) }
        thrown.message.orEmpty() shouldContain "tiled"
        // and the untouched file is still fine, so the guard is not simply rejecting everything
        TiffCodec.decode(written)[0, 0] shouldBe red
    }

    // ---- the codec's own output --------------------------------------------------------------

    @Test
    fun `writes a file it can read back exactly`() {
        val original = RasterImage(3, 2, intArrayOf(red, green, blue, ghost, red, 0x00000000))

        val decoded = TiffCodec.decode(TiffCodec.encode(original))

        decoded.width shouldBe 3
        decoded.height shouldBe 2
        decoded.pixels.toList() shouldBe original.pixels.toList()
    }

    @Test
    fun `writes a file the registry will actually offer`() {
        // The defect this whole codec came from: `Format.TIFF` claimed `Backend.OWN` read and write
        // while no codec existed, so the export sheet filtered it out and nobody noticed for
        // months. The claim and the registry have to agree.
        (Format.TIFF in Codecs.writable) shouldBe true
        (Codecs.decoderFor(Format.TIFF) != null) shouldBe true
    }

    @Test
    fun `probe reads the size without decoding the pixels`() {
        TiffCodec.probe(tiff(7, 5, 3, 2, 1, ByteArray(7 * 5 * 3))) shouldBe (7 to 5)
        TiffCodec.probe(byteArrayOf(1, 2, 3)) shouldBe null
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** Byte offset of a tag's 12-byte entry, for tests that need to corrupt one field. */
    private fun ByteArray.indexOfTag(tag: Int): Int {
        val ifd = (this[4].toInt() and 0xFF) or ((this[5].toInt() and 0xFF) shl 8) or
            ((this[6].toInt() and 0xFF) shl 16) or ((this[7].toInt() and 0xFF) shl 24)
        val count = (this[ifd].toInt() and 0xFF) or ((this[ifd + 1].toInt() and 0xFF) shl 8)
        for (i in 0 until count) {
            val at = ifd + 2 + i * 12
            val found = (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)
            if (found == tag) return at
        }
        error("tag $tag not present")
    }

    /** Rebuilds the file with one more directory entry, keeping tag order. */
    private fun ByteArray.withTag(tag: Int, type: Int, value: Int): ByteArray {
        val ifd = (this[4].toInt() and 0xFF) or ((this[5].toInt() and 0xFF) shl 8) or
            ((this[6].toInt() and 0xFF) shl 16) or ((this[7].toInt() and 0xFF) shl 24)
        val count = (this[ifd].toInt() and 0xFF) or ((this[ifd + 1].toInt() and 0xFF) shl 8)
        val entries = (0 until count).map { copyOfRange(ifd + 2 + it * 12, ifd + 14 + it * 12) }
        val fresh = ByteArrayOutputStream()
        le16(fresh, tag); le16(fresh, type); le32(fresh, 1); le32(fresh, value)
        val all = (entries + fresh.toByteArray())
            .sortedBy { (it[0].toInt() and 0xFF) or ((it[1].toInt() and 0xFF) shl 8) }

        val out = ByteArrayOutputStream()
        out.write(this, 0, ifd)
        le16(out, all.size)
        all.forEach(out::write)
        le32(out, 0)
        return out.toByteArray()
    }
}
