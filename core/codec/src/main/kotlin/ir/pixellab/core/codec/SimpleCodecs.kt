package ir.pixellab.core.codec

import java.io.ByteArrayOutputStream

/**
 * Targa.
 *
 * Still the interchange format for game and 3D work, and one of the few Photoshop writes that no
 * mobile platform decodes. Two details decide whether a file from another program opens: the origin
 * bit, because Targa is bottom-up unless told otherwise and half the world's exporters set it the
 * other way; and the run-length variant, which is what almost every real file uses.
 */
object TgaCodec : ImageDecoder, ImageEncoder {

    override val format = Format.TGA

    override fun probe(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < HEADER) return null
        val width = le16(bytes, 12)
        val height = le16(bytes, 14)
        return if (width > 0 && height > 0) width to height else null
    }

    override fun decode(bytes: ByteArray): RasterImage {
        if (bytes.size < HEADER) throw CodecException("truncated Targa header")
        val idLength = bytes[0].toInt() and 0xFF
        val colorMapType = bytes[1].toInt() and 0xFF
        val imageType = bytes[2].toInt() and 0xFF
        val width = le16(bytes, 12)
        val height = le16(bytes, 14)
        val depth = bytes[16].toInt() and 0xFF
        val descriptor = bytes[17].toInt() and 0xFF

        if (colorMapType != 0) throw CodecException("colour-mapped Targa is not supported")
        if (imageType != TYPE_TRUECOLOR && imageType != TYPE_TRUECOLOR_RLE) {
            throw CodecException("unsupported Targa image type $imageType")
        }
        if (depth != 24 && depth != 32) throw CodecException("unsupported Targa depth $depth")

        val bytesPerPixel = depth / 8
        val out = IntArray(width * height)
        var source = HEADER + idLength
        var written = 0

        if (imageType == TYPE_TRUECOLOR) {
            while (written < out.size && source + bytesPerPixel <= bytes.size) {
                out[written++] = pixel(bytes, source, bytesPerPixel)
                source += bytesPerPixel
            }
        } else {
            while (written < out.size && source < bytes.size) {
                val header = bytes[source++].toInt() and 0xFF
                val count = (header and 0x7F) + 1
                if (header and 0x80 != 0) {
                    if (source + bytesPerPixel > bytes.size) break
                    val value = pixel(bytes, source, bytesPerPixel)
                    source += bytesPerPixel
                    repeat(count.coerceAtMost(out.size - written)) { out[written++] = value }
                } else {
                    repeat(count.coerceAtMost(out.size - written)) {
                        if (source + bytesPerPixel > bytes.size) return@repeat
                        out[written++] = pixel(bytes, source, bytesPerPixel)
                        source += bytesPerPixel
                    }
                }
            }
        }

        // Bit 5 of the descriptor sets a top-left origin. Unset — the default — means the rows
        // arrived bottom-up, and a reader that ignores this produces a vertically mirrored image.
        if (descriptor and 0x20 == 0) flipVertically(out, width, height)
        return RasterImage(width, height, out)
    }

    // Lossless: there is nothing for a quality setting to trade away.
    override fun encode(image: RasterImage, quality: Int): ByteArray {
        val out = ByteArrayOutputStream(HEADER + image.pixels.size * 4)
        out.write(0) // no id field
        out.write(0) // no colour map
        out.write(TYPE_TRUECOLOR)
        repeat(5) { out.write(0) } // colour map specification
        repeat(4) { out.write(0) } // origin
        writeLe16(out, image.width)
        writeLe16(out, image.height)
        out.write(32)
        // Eight alpha bits, top-left origin — written explicitly so the file does not depend on a
        // reader guessing the default.
        out.write(0x08 or 0x20)

        for (argb in image.pixels) {
            out.write(argb and 0xFF)
            out.write((argb shr 8) and 0xFF)
            out.write((argb shr 16) and 0xFF)
            out.write((argb ushr 24) and 0xFF)
        }
        return out.toByteArray()
    }

    private fun pixel(bytes: ByteArray, at: Int, bytesPerPixel: Int): Int {
        val b = bytes[at].toInt() and 0xFF
        val g = bytes[at + 1].toInt() and 0xFF
        val r = bytes[at + 2].toInt() and 0xFF
        val a = if (bytesPerPixel == 4) bytes[at + 3].toInt() and 0xFF else 0xFF
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private const val HEADER = 18
    private const val TYPE_TRUECOLOR = 2
    private const val TYPE_TRUECOLOR_RLE = 10
}

/**
 * Windows bitmap.
 *
 * Written with the 40-byte info header rather than the newer variants because that is what every
 * reader accepts, and read leniently because the ones in the wild are not all written that way. The
 * two traps are that rows are padded to four bytes and that, like Targa, the format is bottom-up
 * unless the height is negative.
 */
object BmpCodec : ImageDecoder, ImageEncoder {

    override val format = Format.BMP

    override fun probe(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 26 || bytes[0] != 'B'.code.toByte() || bytes[1] != 'M'.code.toByte()) return null
        return le32(bytes, 18) to kotlin.math.abs(le32(bytes, 22))
    }

    override fun decode(bytes: ByteArray): RasterImage {
        if (bytes.size < 54) throw CodecException("truncated bitmap header")
        if (bytes[0] != 'B'.code.toByte() || bytes[1] != 'M'.code.toByte()) throw CodecException("not a bitmap")

        val dataOffset = le32(bytes, 10)
        val width = le32(bytes, 18)
        val rawHeight = le32(bytes, 22)
        val height = kotlin.math.abs(rawHeight)
        val depth = le16(bytes, 28)
        val compression = le32(bytes, 30)

        if (width <= 0 || height <= 0) throw CodecException("bitmap has no area")
        if (compression != 0 && compression != 3) throw CodecException("compressed bitmaps are not supported")
        if (depth != 24 && depth != 32) throw CodecException("unsupported bitmap depth $depth")

        val bytesPerPixel = depth / 8
        // Every row is padded up to a four-byte boundary, and the padding is not in the width.
        val stride = (width * bytesPerPixel + 3) / 4 * 4
        val out = IntArray(width * height)

        for (row in 0 until height) {
            val source = dataOffset + row * stride
            if (source + width * bytesPerPixel > bytes.size) break
            // A positive height means the first row in the file is the *bottom* of the image.
            val target = if (rawHeight > 0) (height - 1 - row) * width else row * width
            for (x in 0 until width) {
                val at = source + x * bytesPerPixel
                val b = bytes[at].toInt() and 0xFF
                val g = bytes[at + 1].toInt() and 0xFF
                val r = bytes[at + 2].toInt() and 0xFF
                val a = if (bytesPerPixel == 4) bytes[at + 3].toInt() and 0xFF else 0xFF
                out[target + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return RasterImage(width, height, out)
    }

    // Lossless: there is nothing for a quality setting to trade away.
    override fun encode(image: RasterImage, quality: Int): ByteArray {
        val stride = image.width * 4
        val size = HEADER_SIZE + stride * image.height
        val out = ByteArrayOutputStream(size)

        out.write('B'.code)
        out.write('M'.code)
        writeLe32(out, size)
        writeLe32(out, 0)
        writeLe32(out, HEADER_SIZE)

        writeLe32(out, 40) // info header size
        writeLe32(out, image.width)
        // Negative height declares a top-down image, which spares every reader the flip.
        writeLe32(out, -image.height)
        writeLe16(out, 1)
        writeLe16(out, 32)
        writeLe32(out, 0) // uncompressed
        writeLe32(out, stride * image.height)
        writeLe32(out, PIXELS_PER_METRE)
        writeLe32(out, PIXELS_PER_METRE)
        writeLe32(out, 0)
        writeLe32(out, 0)

        for (argb in image.pixels) {
            out.write(argb and 0xFF)
            out.write((argb shr 8) and 0xFF)
            out.write((argb shr 16) and 0xFF)
            out.write((argb ushr 24) and 0xFF)
        }
        return out.toByteArray()
    }

    private const val HEADER_SIZE = 54

    /** 72 dpi expressed the way BMP stores it. */
    private const val PIXELS_PER_METRE = 2835
}

// ---- shared little-endian helpers ------------------------------------------------------------

internal fun le16(bytes: ByteArray, at: Int): Int =
    (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

internal fun le32(bytes: ByteArray, at: Int): Int =
    (bytes[at].toInt() and 0xFF) or
        ((bytes[at + 1].toInt() and 0xFF) shl 8) or
        ((bytes[at + 2].toInt() and 0xFF) shl 16) or
        ((bytes[at + 3].toInt() and 0xFF) shl 24)

internal fun writeLe16(out: ByteArrayOutputStream, value: Int) {
    out.write(value and 0xFF)
    out.write((value shr 8) and 0xFF)
}

internal fun writeLe32(out: ByteArrayOutputStream, value: Int) {
    writeLe16(out, value and 0xFFFF)
    writeLe16(out, (value shr 16) and 0xFFFF)
}

internal fun flipVertically(pixels: IntArray, width: Int, height: Int) {
    val row = IntArray(width)
    for (y in 0 until height / 2) {
        val top = y * width
        val bottom = (height - 1 - y) * width
        System.arraycopy(pixels, top, row, 0, width)
        System.arraycopy(pixels, bottom, pixels, top, width)
        System.arraycopy(row, 0, pixels, bottom, width)
    }
}
