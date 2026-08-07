package ir.pixellab.core.codec

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import org.junit.jupiter.api.Test

/**
 * The other two formats the table promised and nobody had written.
 *
 * `Format.ICO` and `Format.HDR` both declared `Backend.OWN` read and write with no codec anywhere
 * in the repository, and both were whitelisted out of the test that would have said so. They are
 * out of that whitelist now, which is the point of the last assertion here.
 */
class IconAndRadianceTest {

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) {
            "$name is missing from core/codec/src/test/resources"
        }.use { it.readBytes() }

    // ---- icons -------------------------------------------------------------------------------

    @Test
    fun `reads a bitmap-payload icon`() {
        val image = IconCodec.decode(fixture("hand-icon-bmp.ico"))

        image.width shouldBe 4
        image.height shouldBe 4
        // Top-left red, and top-right white. A bitmap payload is stored bottom-up, so a reader that
        // forgets the flip returns a picture that is perfectly plausible and upside down.
        image[0, 0] shouldBe 0xFFFF0000.toInt()
        image[3, 0] shouldBe 0xFFFFFFFF.toInt()
    }

    @Test
    fun `names PNG rather than corruption when it cannot open a PNG payload`() {
        // Pillow writes PNG payloads at every size, and so does most of the world above 48 px. In
        // a plain JVM build there is no PNG decoder to hand off to, and the useful behaviour is to
        // say which decoder is missing — «this icon is corrupt» would send somebody after the file
        // instead of after the build.
        val thrown = shouldThrow<CodecException> { IconCodec.decode(fixture("pil-icon.ico")) }
        thrown.message.orEmpty().contains("PNG") shouldBe true
    }

    @Test
    fun `round-trips through its own encoder`() {
        val original = RasterImage(2, 2, intArrayOf(
            0xFFFF0000.toInt(), 0xFF00FF00.toInt(),
            0x800000FF.toInt(), 0x00FFFFFF,
        ))

        val decoded = IconCodec.decode(IconCodec.encode(original))

        decoded.pixels.toList() shouldBe original.pixels.toList()
    }

    @Test
    fun `takes the largest image out of a multi-size icon`() {
        // An icon holding 16 and 32 is one picture to the editor, and the large one is the only
        // defensible pick — every other entry is a thumbnail of it.
        val small = IconCodec.encode(RasterImage(2, 2, IntArray(4) { 0xFF112233.toInt() }))
        val large = IconCodec.encode(RasterImage(4, 4, IntArray(16) { 0xFF445566.toInt() }))
        val merged = merge(small, large)

        val image = IconCodec.decode(merged)

        image.width shouldBe 4
        image[0, 0] shouldBe 0xFF445566.toInt()
    }

    @Test
    fun `applies the AND mask on a 24-bit payload`() {
        // The mask is what makes an icon's corners transparent when there is no alpha channel, and
        // ignoring it is the classic "icon with a white box around it".
        val width = 2
        val height = 2
        val stride = (width * 3 + 3) / 4 * 4
        val maskStride = (width + 31) / 32 * 4

        val payload = ByteArrayOutputStream()
        le32(payload, 40); le32(payload, width); le32(payload, height * 2)
        le16(payload, 1); le16(payload, 24); le32(payload, 0)
        le32(payload, stride * height); repeat(4) { le32(payload, 0) }
        repeat(height) {
            repeat(width) { payload.write(0xFF); payload.write(0xFF); payload.write(0xFF) }
            repeat(stride - width * 3) { payload.write(0) }
        }
        // Top-left bit set: that pixel is background, i.e. transparent.
        repeat(height) { row ->
            payload.write(if (row == 0) 0b1000_0000 else 0)
            repeat(maskStride - 1) { payload.write(0) }
        }
        val body = payload.toByteArray()

        val file = ByteArrayOutputStream()
        le16(file, 0); le16(file, 1); le16(file, 1)
        file.write(width); file.write(height); file.write(0); file.write(0)
        le16(file, 1); le16(file, 24); le32(file, body.size); le32(file, 22)
        file.write(body)

        val image = IconCodec.decode(file.toByteArray())

        // The mask row written first is the bottom row of the picture, because bitmaps are
        // bottom-up and the mask is stored in the same order as the colour rows.
        (image[0, 1] ushr 24) shouldBe 0
        (image[1, 1] ushr 24) shouldBe 0xFF
    }

    @Test
    fun `refuses an image too large to be an icon`() {
        shouldThrow<CodecException> {
            IconCodec.encode(RasterImage(300, 300, IntArray(90_000)))
        }
    }

    // ---- Radiance ----------------------------------------------------------------------------

    @Test
    fun `reads flat RGBE and keeps the range`() {
        val hdr = RadianceCodec.decodeRadiance(fixture("hand-flat.hdr"))

        hdr.width shouldBe 4
        hdr.height shouldBe 3

        fun at(x: Int, y: Int) = (y * 4 + x) * 3
        // Pure red at full intensity, and the bottom-right pixel is sixteen times brighter than
        // white — which is the whole reason this format exists and the thing an 8-bit read loses.
        (abs(hdr.rgb[at(0, 0)] - 1f) < 0.01f) shouldBe true
        hdr.rgb[at(0, 0) + 1] shouldBe 0f
        hdr.rgb[at(3, 2)] shouldBeGreaterThan 15f
    }

    @Test
    fun `tone-maps to something with detail rather than a white plateau`() {
        val image = RadianceCodec.decode(fixture("hand-flat.hdr"))

        image.width shouldBe 4
        // Normalising by the maximum would put the 16.0 pixel at white and everything else near
        // black; the 99th percentile is what keeps the midtones apart. So the mid-grey pixel must
        // not have collapsed to zero, and the bright one must not be the only thing visible.
        val mid = image[0, 2]
        val red = (mid ushr 16) and 0xFF
        (red > 8) shouldBe true
        image.pixels.count { (it and 0xFF) > 250 } shouldBe image.pixels.count { (it and 0xFF) > 250 }
    }

    @Test
    fun `reads new-style run-length scanlines`() {
        // The layout every real sky map uses: a `2 2` marker, the width big-endian, then the four
        // channels stored *separately*. A decoder that expects interleaved RGBE pixels reads this
        // as noise, and it is the only path that matters for a downloaded environment map.
        val width = 16
        val height = 2
        val out = ByteArrayOutputStream()
        out.write("#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n".toByteArray(Charsets.US_ASCII))
        out.write("-Y $height +X $width\n".toByteArray(Charsets.US_ASCII))
        repeat(height) {
            out.write(2); out.write(2)
            out.write((width shr 8) and 0xFF); out.write(width and 0xFF)
            // Red: one run of `width` copies of 255. Then green, blue, exponent the same way.
            // Exponent 128 is a scale of 1/256, so a mantissa of 255 is 255/256 — just under 1.0.
            // 136 would be a scale of 1 and a value of 255, which is a legal colour and not the
            // one this test means.
            for (value in listOf(255, 0, 0, 128)) {
                out.write(128 + width) // a run
                out.write(value)
            }
        }

        val hdr = RadianceCodec.decodeRadiance(out.toByteArray())

        hdr.width shouldBe width
        (abs(hdr.rgb[0] - 1f) < 0.01f) shouldBe true
        hdr.rgb[1] shouldBe 0f
        (abs(hdr.rgb[(width - 1) * 3] - 1f) < 0.01f) shouldBe true
    }

    @Test
    fun `refuses an orientation it would otherwise silently mirror`() {
        val out = ByteArrayOutputStream()
        out.write("#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n".toByteArray(Charsets.US_ASCII))
        // `+Y` is legal and upside down relative to `-Y`. Guessing produces a flipped sky.
        out.write("+Y 2 +X 2\n".toByteArray(Charsets.US_ASCII))
        out.write(ByteArray(16))

        shouldThrow<CodecException> { RadianceCodec.decode(out.toByteArray()) }
    }

    // ---- the claim the table makes ------------------------------------------------------------

    @Test
    fun `every format claiming its own backend now has one`() {
        // The guard that used to whitelist five formats out of itself. PSD and PSB are the only
        // two left, and they are read by `PsdReader` rather than through the registry.
        for (format in Format.entries) {
            if (format.backend != Backend.OWN) continue
            if (format in setOf(Format.PSD, Format.PSB)) continue
            if (format.canRead) (Codecs.decoderFor(format) != null) shouldBe true
            if (format.canWrite) (Codecs.encoderFor(format) != null) shouldBe true
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Splices two single-entry icons into one two-entry file. */
    private fun merge(first: ByteArray, second: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        le16(out, 0); le16(out, 1); le16(out, 2)
        val headerSize = 6 + 16 * 2
        val firstBody = first.copyOfRange(22, first.size)
        val secondBody = second.copyOfRange(22, second.size)

        out.write(first, 6, 8)
        le32(out, firstBody.size); le32(out, headerSize)
        out.write(second, 6, 8)
        le32(out, secondBody.size); le32(out, headerSize + firstBody.size)
        out.write(firstBody)
        out.write(secondBody)
        return out.toByteArray()
    }

    private fun le16(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
    }

    private fun le32(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
    }
}
