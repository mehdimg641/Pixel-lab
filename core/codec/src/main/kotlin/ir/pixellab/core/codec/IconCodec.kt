package ir.pixellab.core.codec

import java.io.ByteArrayOutputStream

/**
 * Windows icon.
 *
 * ### The two payloads
 *
 * An `.ico` is a directory of images, and each entry holds one of two entirely different things: a
 * headerless BMP info header followed by pixels, or a whole PNG file. Sizes up to 48 use the first;
 * 256-pixel icons almost always use the second, because a 256×256 BGRA bitmap is 256 KB and the
 * PNG is a tenth of that.
 *
 * This reads both. The PNG half delegates to whatever decoder the build has registered for
 * [Format.PNG] — on Android that is the platform, and in a plain JVM build there may be none, in
 * which case the failure names PNG rather than pretending the icon is corrupt.
 *
 * ### The mask
 *
 * A BMP payload stores its height doubled: the colour rows, then a 1-bit AND mask of the same
 * width. The mask is what makes an icon's corners transparent on a 24-bit payload, and a reader
 * that takes the declared height at face value decodes the colour half plus a band of mask read as
 * pixels — the classic "icon with garbage along the bottom".
 *
 * A 32-bit payload carries its own alpha, and there the mask is redundant and frequently wrong, so
 * it is ignored rather than intersected.
 */
object IconCodec : ImageDecoder, ImageEncoder {

    override val format = Format.ICO

    override fun probe(bytes: ByteArray): Pair<Int, Int>? {
        val entry = largest(bytes) ?: return null
        return entry.width to entry.height
    }

    override fun decode(bytes: ByteArray): RasterImage {
        val entry = largest(bytes) ?: throw CodecException("not an icon, or it holds no images")
        if (entry.offset + entry.size > bytes.size) throw CodecException("icon image runs past the end of the file")
        val payload = bytes.copyOfRange(entry.offset, entry.offset + entry.size)

        if (payload.size >= 8 && payload.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) {
            val decoder = Codecs.decoderFor(Format.PNG)
                ?: throw CodecException("this icon holds a PNG and no PNG decoder is registered")
            return decoder.decode(payload)
        }
        return decodeDib(payload, entry)
    }

    /**
     * Writes one 32-bit BGRA image, in the BMP form rather than the PNG one.
     *
     * A single entry, because an icon *file* holding several sizes is a packaging decision and this
     * encoder is handed one picture. The BMP form because it needs no other codec to be registered
     * — a PNG payload would make icon export depend on a decoder that a plain JVM build does not
     * have.
     */
    override fun encode(image: RasterImage, quality: Int): ByteArray {
        if (image.width > 256 || image.height > 256) {
            throw CodecException("an icon is at most 256×256, this is ${image.width}×${image.height}")
        }
        val out = ByteArrayOutputStream(image.pixels.size * 4 + 128)
        // Reserved, type 1 (icon), one image.
        le16(out, 0); le16(out, 1); le16(out, 1)

        val maskStride = (image.width + 31) / 32 * 4
        val pixelBytes = image.pixels.size * 4
        val payloadSize = DIB_HEADER + pixelBytes + maskStride * image.height

        // 256 is written as 0 — the field is one byte and the format predates the size it now has
        // to express.
        out.write(if (image.width == 256) 0 else image.width)
        out.write(if (image.height == 256) 0 else image.height)
        out.write(0) // no colour table
        out.write(0) // reserved
        le16(out, 1) // planes
        le16(out, 32) // bits per pixel
        le32(out, payloadSize)
        le32(out, ICONDIR + ICONDIRENTRY)

        // The info header, with the doubled height the format requires even when the mask is empty.
        le32(out, DIB_HEADER)
        le32(out, image.width)
        le32(out, image.height * 2)
        le16(out, 1)
        le16(out, 32)
        le32(out, 0) // BI_RGB
        le32(out, pixelBytes)
        repeat(4) { le32(out, 0) }

        // Bottom-up, BGRA.
        for (y in image.height - 1 downTo 0) {
            for (x in 0 until image.width) {
                val argb = image[x, y]
                out.write(argb and 0xFF)
                out.write((argb ushr 8) and 0xFF)
                out.write((argb ushr 16) and 0xFF)
                out.write((argb ushr 24) and 0xFF)
            }
        }
        // An all-zero AND mask: every pixel opaque as far as the mask is concerned, with the real
        // transparency in the alpha channel. Writing it is not optional even when it says nothing —
        // readers compute the colour height from the declared height minus the mask.
        repeat(maskStride * image.height) { out.write(0) }
        return out.toByteArray()
    }

    // ---- directory ---------------------------------------------------------------------------

    private class Entry(val width: Int, val height: Int, val depth: Int, val size: Int, val offset: Int)

    /**
     * The biggest image in the file, by area then by depth.
     *
     * An icon holding 16, 32, 48 and 256 is offered to the editor as one picture, and the largest
     * is the only defensible choice — every other one is a thumbnail of it.
     */
    private fun largest(bytes: ByteArray): Entry? {
        if (bytes.size < ICONDIR) return null
        if (le16(bytes, 0) != 0) return null
        val type = le16(bytes, 2)
        // 1 is an icon and 2 is a cursor; the layouts are identical past this field.
        if (type != 1 && type != 2) return null
        val count = le16(bytes, 4)
        if (count <= 0) return null

        var best: Entry? = null
        for (i in 0 until count) {
            val at = ICONDIR + i * ICONDIRENTRY
            if (at + ICONDIRENTRY > bytes.size) break
            // Zero means 256 in both axes.
            val w = (bytes[at].toInt() and 0xFF).let { if (it == 0) 256 else it }
            val h = (bytes[at + 1].toInt() and 0xFF).let { if (it == 0) 256 else it }
            val depth = le16(bytes, at + 6)
            val size = le32(bytes, at + 8)
            val offset = le32(bytes, at + 12)
            if (size <= 0 || offset <= 0) continue
            val entry = Entry(w, h, depth, size, offset)
            if (best == null || w * h > best!!.width * best!!.height ||
                (w * h == best!!.width * best!!.height && depth > best!!.depth)
            ) {
                best = entry
            }
        }
        return best
    }

    private fun decodeDib(payload: ByteArray, entry: Entry): RasterImage {
        if (payload.size < DIB_HEADER) throw CodecException("truncated icon bitmap header")
        val width = le32(payload, 4)
        // Doubled to cover the AND mask. The colour image is the top half.
        val storedHeight = le32(payload, 8)
        val height = storedHeight / 2
        val depth = le16(payload, 14)
        val compression = le32(payload, 16)

        if (width <= 0 || height <= 0) throw CodecException("icon bitmap has no area")
        if (compression != 0) throw CodecException("compressed icon bitmaps are not supported")
        if (depth != 24 && depth != 32) throw CodecException("unsupported icon depth $depth")

        val bytesPerPixel = depth / 8
        val stride = (width * bytesPerPixel + 3) / 4 * 4
        val start = DIB_HEADER
        val pixels = IntArray(width * height)

        for (row in 0 until height) {
            // Bottom-up, like every other bitmap.
            val source = start + row * stride
            val target = (height - 1 - row) * width
            for (x in 0 until width) {
                val at = source + x * bytesPerPixel
                if (at + bytesPerPixel > payload.size) break
                val b = payload[at].toInt() and 0xFF
                val g = payload[at + 1].toInt() and 0xFF
                val r = payload[at + 2].toInt() and 0xFF
                val a = if (bytesPerPixel == 4) payload[at + 3].toInt() and 0xFF else 0xFF
                pixels[target + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        // Only for 24-bit. A 32-bit icon carries real alpha, and a great many of them ship with a
        // stale mask that would punch holes in a perfectly good image.
        if (bytesPerPixel == 3) {
            val maskStride = (width + 31) / 32 * 4
            val maskStart = start + stride * height
            for (row in 0 until height) {
                val source = maskStart + row * maskStride
                val target = (height - 1 - row) * width
                for (x in 0 until width) {
                    val at = source + (x shr 3)
                    if (at >= payload.size) break
                    val bit = (payload[at].toInt() shr (7 - (x and 7))) and 1
                    // A set bit means "leave the background alone" — that is, transparent.
                    if (bit == 1) pixels[target + x] = pixels[target + x] and 0x00FFFFFF
                }
            }
        }
        return RasterImage(width, height, pixels)
    }

    private fun le16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or
        ((b[at + 1].toInt() and 0xFF) shl 8) or
        ((b[at + 2].toInt() and 0xFF) shl 16) or
        ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun le16(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
    }

    private fun le32(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
    }

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    private const val ICONDIR = 6
    private const val ICONDIRENTRY = 16
    private const val DIB_HEADER = 40
}
