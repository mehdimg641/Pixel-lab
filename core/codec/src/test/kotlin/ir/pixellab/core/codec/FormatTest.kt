package ir.pixellab.core.codec

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class FormatTest {

    private fun header(vararg bytes: Int) = ByteArray(200) { i ->
        if (i < bytes.size) bytes[i].toByte() else 0
    }

    private fun header(text: String, at: Int = 0) = ByteArray(200) { i ->
        if (i >= at && i - at < text.length) text[i - at].code.toByte() else 0
    }

    @Test
    fun `formats are identified by content, not by name`() {
        // Files arrive from a share sheet called "image" with no suffix at all, and a mis-named
        // PSD opened as a JPEG fails confusingly rather than informatively.
        Format.detect(header(0xFF, 0xD8, 0xFF, 0xE0)) shouldBe Format.JPEG
        Format.detect(header(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) shouldBe Format.PNG
        Format.detect(header("GIF89a")) shouldBe Format.GIF
        Format.detect(header("BM")) shouldBe Format.BMP
        Format.detect(header("%PDF-1.7")) shouldBe Format.PDF
        Format.detect(header("#?RADIANCE")) shouldBe Format.HDR
        Format.detect(header(0x76, 0x2F, 0x31, 0x01)) shouldBe Format.EXR
        Format.detect(header(0x00, 0x00, 0x01, 0x00)) shouldBe Format.ICO
        Format.detect(header(0x49, 0x49, 0x2A, 0x00)) shouldBe Format.TIFF
        Format.detect(header(0x4D, 0x4D, 0x00, 0x2A)) shouldBe Format.TIFF
    }

    @Test
    fun `WebP and AVI are told apart despite sharing a container`() {
        val webp = ByteArray(200).also {
            "RIFF".forEachIndexed { i, c -> it[i] = c.code.toByte() }
            "WEBP".forEachIndexed { i, c -> it[8 + i] = c.code.toByte() }
        }
        val avi = ByteArray(200).also {
            "RIFF".forEachIndexed { i, c -> it[i] = c.code.toByte() }
            "AVI ".forEachIndexed { i, c -> it[8 + i] = c.code.toByte() }
        }
        // Both begin with the same four bytes; the form type eight bytes in is the only difference.
        Format.detect(webp) shouldBe Format.WEBP
        Format.detect(avi) shouldBe Format.AVI
    }

    @Test
    fun `HEIC and MP4 are told apart by their brand`() {
        fun ftyp(brand: String) = ByteArray(200).also {
            "ftyp".forEachIndexed { i, c -> it[4 + i] = c.code.toByte() }
            brand.forEachIndexed { i, c -> it[8 + i] = c.code.toByte() }
        }
        Format.detect(ftyp("heic")) shouldBe Format.HEIF
        Format.detect(ftyp("isom")) shouldBe Format.MP4
    }

    @Test
    fun `an unrecognised header returns nothing rather than a guess`() {
        Format.detect(header("hello there")) shouldBe null
        Format.detect(ByteArray(2)) shouldBe null
    }

    @Test
    fun `extensions resolve for the save dialog, where there is no content yet`() {
        Format.fromExtension("cover.PSD") shouldBe Format.PSD
        Format.fromExtension("photo.jpeg") shouldBe Format.JPEG
        Format.fromExtension("shot.CR3") shouldBe Format.RAW_CANON
        Format.fromExtension("icon.ico") shouldBe Format.ICO
        Format.fromExtension("noextension") shouldBe null
        Format.fromExtension("archive.zip") shouldBe null
    }

    @Test
    fun `every format claims a support level and every extension is unique`() {
        val extensions = Format.entries.flatMap { it.extensions }
        // Two formats claiming the same extension makes fromExtension pick by declaration order,
        // which is arbitrary and silently wrong for one of them.
        extensions.size shouldBe extensions.distinct().size
    }

    @Test
    fun `a format that declares it can be read has a decoder, and the reverse`() {
        for (format in Format.entries) {
            val claimsOwnRead = format.canRead && format.backend == Backend.OWN
            if (claimsOwnRead && format !in setOf(Format.PSD, Format.PSB, Format.TIFF, Format.HDR, Format.ICO)) {
                // A capability table that promises more than the build delivers is worse than no
                // table: the interface offers the option and the user finds out on failure.
                (Codecs.decoderFor(format) != null) shouldBe true
            }
            if (format.backend == Backend.NOT_IMPLEMENTED) {
                format.canRead shouldBe false
                format.canWrite shouldBe false
            }
        }
    }

    @Test
    fun `video is declared and refused rather than quietly missing`() {
        // The scope decision was explicit — video belongs to a separate app — so the formats are
        // listed in order to say so, not forgotten.
        Format.MP4.isVideo shouldBe true
        Format.MP4.canRead shouldBe false
        Codecs.describe(Format.MP4).contains("ویدیو") shouldBe true
    }

    @Test
    fun `describing a format says what will survive`() {
        Codecs.describe(Format.PSD).contains("لایه") shouldBe true
        Codecs.describe(Format.JPEG).contains("تخت") shouldBe true
        Codecs.describe(Format.EXR).contains("پشتیبانی نمی‌شود") shouldBe true
    }

    @Test
    fun `decoding an unrecognised file names the problem`() {
        assertThrows<CodecException> { Codecs.decode("just some text".toByteArray()) }
    }

    @Test
    fun `decoding a recognised but unsupported format says which format it was`() {
        val exr = ByteArray(64).also {
            intArrayOf(0x76, 0x2F, 0x31, 0x01).forEachIndexed { i, b -> it[i] = b.toByte() }
        }
        val message = assertThrows<CodecException> { Codecs.decode(exr) }.message.orEmpty()
        message.contains("OpenEXR") shouldBe true
    }
}

class SimpleCodecTest {

    private fun sample() = RasterImage(
        4, 3,
        IntArray(12) { i -> (0xFF shl 24) or (i * 20 shl 16) or (255 - i * 10 shl 8) or (i * 5) },
    )

    @Test
    fun `Targa survives a round trip`() {
        val original = sample()
        val back = TgaCodec.decode(TgaCodec.encode(original))
        back.width shouldBe original.width
        back.height shouldBe original.height
        back.pixels.toList() shouldBe original.pixels.toList()
    }

    @Test
    fun `bitmap survives a round trip`() {
        val original = sample()
        val back = BmpCodec.decode(BmpCodec.encode(original))
        back.pixels.toList() shouldBe original.pixels.toList()
    }

    @Test
    fun `a bottom-up bitmap comes back the right way up`() {
        val original = sample()
        val encoded = BmpCodec.encode(original)
        // Flip the declared height back to positive, which is how most writers store it, and
        // reverse the rows to match. A reader that ignores the sign returns a mirrored image.
        val height = original.height
        writeLe32At(encoded, 22, height)
        val flipped = encoded.copyOf()
        val stride = original.width * 4
        for (row in 0 until height) {
            System.arraycopy(encoded, 54 + row * stride, flipped, 54 + (height - 1 - row) * stride, stride)
        }
        BmpCodec.decode(flipped).pixels.toList() shouldBe original.pixels.toList()
    }

    @Test
    fun `a top-left Targa is not flipped again`() {
        // The encoder sets the top-left origin bit, so decoding must leave the rows alone.
        val original = sample()
        TgaCodec.decode(TgaCodec.encode(original))[0, 0] shouldBe original[0, 0]
    }

    @Test
    fun `a run-length Targa decodes`() {
        // Hand-built: a run of four identical pixels then two literals, which is what a real
        // exporter emits and what the uncompressed path would silently misread.
        val out = java.io.ByteArrayOutputStream()
        out.write(0); out.write(0); out.write(10)
        repeat(5) { out.write(0) }
        repeat(4) { out.write(0) }
        writeLe16(out, 3); writeLe16(out, 2)
        out.write(32); out.write(0x08 or 0x20)
        out.write(0x83) // run of four
        listOf(0x10, 0x20, 0x30, 0xFF).forEach(out::write)
        out.write(0x01) // two literals
        listOf(0x40, 0x50, 0x60, 0xFF, 0x70, 0x80, 0x90, 0xFF).forEach(out::write)

        val image = TgaCodec.decode(out.toByteArray())
        image.width shouldBe 3
        // Runs cross row boundaries — the run of four covers all of row 0 and the first pixel of
        // row 1, so a decoder that restarted at each row would put the literals one pixel early.
        image[0, 0] shouldBe 0xFF302010.toInt()
        image[2, 0] shouldBe 0xFF302010.toInt()
        image[0, 1] shouldBe 0xFF302010.toInt()
        image[1, 1] shouldBe 0xFF605040.toInt()
        image[2, 1] shouldBe 0xFF908070.toInt()
    }

    @Test
    fun `probing reads the size without decoding the pixels`() {
        TgaCodec.probe(TgaCodec.encode(sample())) shouldBe (4 to 3)
        BmpCodec.probe(BmpCodec.encode(sample())) shouldBe (4 to 3)
        TgaCodec.probe(ByteArray(4)) shouldBe null
    }

    @Test
    fun `a truncated file is refused rather than read as garbage`() {
        assertThrows<CodecException> { TgaCodec.decode(ByteArray(4)) }
        assertThrows<CodecException> { BmpCodec.decode(ByteArray(4)) }
    }

    @Test
    fun `a colour-mapped Targa is refused with a reason`() {
        val bytes = TgaCodec.encode(sample()).copyOf()
        bytes[1] = 1 // colour map present
        assertThrows<CodecException> { TgaCodec.decode(bytes) }
    }

    @Test
    fun `an image with no area is rejected at construction`() {
        assertThrows<IllegalArgumentException> { RasterImage(0, 5, IntArray(0)) }
        assertThrows<IllegalArgumentException> { RasterImage(2, 2, IntArray(3)) }
    }

    private fun writeLe32At(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = (value and 0xFF).toByte()
        bytes[at + 1] = ((value shr 8) and 0xFF).toByte()
        bytes[at + 2] = ((value shr 16) and 0xFF).toByte()
        bytes[at + 3] = ((value shr 24) and 0xFF).toByte()
    }
}
