package ir.pixellab.core.codec

import java.io.ByteArrayOutputStream

/**
 * TIFF — baseline, plus the two compressions real files actually use.
 *
 * ### Why this exists now
 *
 * [Format] has declared TIFF readable *and* writable with `Backend.OWN` since the table was
 * written, and no codec existed. The test that would have caught it
 * (`a format that declares it can be read has a decoder`) carries a comment saying exactly why that
 * is bad — «a capability table that promises more than the build delivers is worse than no table» —
 * and then whitelisted TIFF out of its own guard. Nothing broke, because the export sheet filters
 * through `Codecs.writable` and TIFF silently vanished from it. A promise kept by never being
 * tested is not a promise.
 *
 * ### What baseline means here
 *
 * Strip-based, 8 bits per sample, 1/3/4 samples per pixel, chunky planar layout. That is what
 * Photoshop, ImageMagick, scanners and every camera tether write. What is deliberately *not* here:
 * tiles, 16-bit samples, CMYK separations, JPEG-in-TIFF and planar layout — each is a real format
 * in its own right and each throws by name rather than producing a plausible wrong picture.
 *
 * Both byte orders, because TIFF is the format where that genuinely varies: `II` is Intel and `MM`
 * is Motorola, and a reader that assumes either one opens exactly half the world's files.
 */
object TiffCodec : ImageDecoder, ImageEncoder {

    override val format = Format.TIFF

    override fun probe(bytes: ByteArray): Pair<Int, Int>? = runCatching {
        val d = directory(bytes)
        d.int(TAG_WIDTH)!! to d.int(TAG_HEIGHT)!!
    }.getOrNull()

    override fun decode(bytes: ByteArray): RasterImage {
        val ifd = directory(bytes)
        val width = ifd.int(TAG_WIDTH) ?: throw CodecException("TIFF has no image width")
        val height = ifd.int(TAG_HEIGHT) ?: throw CodecException("TIFF has no image height")
        val samples = ifd.int(TAG_SAMPLES_PER_PIXEL) ?: 1
        val depths = ifd.ints(TAG_BITS_PER_SAMPLE) ?: listOf(1)
        val compression = ifd.int(TAG_COMPRESSION) ?: COMPRESSION_NONE
        val photometric = ifd.int(TAG_PHOTOMETRIC) ?: PHOTOMETRIC_BLACK_IS_ZERO
        val planar = ifd.int(TAG_PLANAR) ?: 1
        val predictor = ifd.int(TAG_PREDICTOR) ?: 1

        if (planar != 1) throw CodecException("planar TIFF is not supported; re-save as chunky")
        if (ifd.int(TAG_TILE_WIDTH) != null) throw CodecException("tiled TIFF is not supported")
        if (depths.any { it != 8 }) {
            throw CodecException("only 8-bit TIFF is supported, this file is ${depths.joinToString("/")}-bit")
        }
        if (samples !in 1..4) throw CodecException("unsupported TIFF sample count $samples")

        val offsets = ifd.ints(TAG_STRIP_OFFSETS) ?: throw CodecException("TIFF has no strip offsets")
        val counts = ifd.ints(TAG_STRIP_BYTE_COUNTS) ?: throw CodecException("TIFF has no strip sizes")
        // The default is "one strip holds the whole image", which is what a file written in one
        // pass usually does. Reading it as one row per strip would decode the first row and stop.
        val rowsPerStrip = ifd.int(TAG_ROWS_PER_STRIP) ?: height
        if (rowsPerStrip <= 0) throw CodecException("TIFF declares $rowsPerStrip rows per strip")

        val stride = width * samples
        val raw = ByteArray(stride * height)
        var written = 0
        for (i in offsets.indices) {
            val offset = offsets[i]
            val count = counts.getOrNull(i) ?: break
            if (offset < 0 || count < 0 || offset + count > bytes.size) {
                throw CodecException("TIFF strip $i runs past the end of the file")
            }
            val strip = bytes.copyOfRange(offset, offset + count)
            val rows = minOf(rowsPerStrip, height - i * rowsPerStrip).coerceAtLeast(0)
            if (rows == 0) break
            val expanded = when (compression) {
                COMPRESSION_NONE -> strip
                COMPRESSION_LZW -> lzw(strip, stride * rows)
                COMPRESSION_PACKBITS -> packBits(strip, stride * rows)
                COMPRESSION_DEFLATE, COMPRESSION_DEFLATE_ADOBE -> inflate(strip, stride * rows)
                else -> throw CodecException("TIFF compression $compression is not supported")
            }
            val take = minOf(expanded.size, raw.size - written)
            if (take <= 0) break
            expanded.copyInto(raw, written, 0, take)
            written += take
        }

        // Horizontal differencing. Applied per row and per sample, before any colour conversion —
        // a predictor undone after the channels are interleaved shifts every colour by its
        // neighbour and produces an image that looks like a smear rather than like noise.
        if (predictor == PREDICTOR_HORIZONTAL) {
            for (y in 0 until height) {
                val row = y * stride
                for (x in samples until stride) {
                    raw[row + x] = (raw[row + x] + raw[row + x - samples]).toByte()
                }
            }
        }

        val pixels = IntArray(width * height)
        val invert = photometric == PHOTOMETRIC_WHITE_IS_ZERO
        for (i in pixels.indices) {
            val at = i * samples
            if (at + samples > raw.size) break
            val r: Int
            val g: Int
            val b: Int
            var a = 0xFF
            when (samples) {
                1 -> {
                    val v = raw[at].toInt() and 0xFF
                    val grey = if (invert) 255 - v else v
                    r = grey; g = grey; b = grey
                }
                2 -> {
                    val v = raw[at].toInt() and 0xFF
                    val grey = if (invert) 255 - v else v
                    r = grey; g = grey; b = grey
                    a = raw[at + 1].toInt() and 0xFF
                }
                else -> {
                    r = raw[at].toInt() and 0xFF
                    g = raw[at + 1].toInt() and 0xFF
                    b = raw[at + 2].toInt() and 0xFF
                    if (samples == 4) a = raw[at + 3].toInt() and 0xFF
                }
            }
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return RasterImage(width, height, pixels)
    }

    /**
     * Writes uncompressed 8-bit RGBA in one strip.
     *
     * No LZW on the way out, deliberately. The patent expired long ago so that is not the reason —
     * the reason is that a TIFF export exists to hand a file to another program, and uncompressed
     * baseline is the one variant that has never failed to open anywhere. Anyone who wants it small
     * is reaching for PNG.
     */
    override fun encode(image: RasterImage, quality: Int): ByteArray {
        val out = ByteArrayOutputStream(image.pixels.size * 4 + 256)
        // `II`, 42, and the directory sits after the pixels so the pixel offset is known up front.
        out.write(byteArrayOf(0x49, 0x49, 42, 0))
        val pixelOffset = 8
        val dataSize = image.pixels.size * 4
        writeLe32(out, pixelOffset + dataSize)

        for (argb in image.pixels) {
            out.write((argb ushr 16) and 0xFF)
            out.write((argb ushr 8) and 0xFF)
            out.write(argb and 0xFF)
            out.write((argb ushr 24) and 0xFF)
        }

        // Twelve entries, ascending by tag — a directory out of order is legal by the letter of the
        // specification and rejected by a good half of the readers in the world.
        val entries = ByteArrayOutputStream()
        var count = 0
        fun entry(tag: Int, type: Int, n: Int, value: Int) {
            writeLe16(entries, tag); writeLe16(entries, type)
            writeLe32(entries, n); writeLe32(entries, value)
            count++
        }
        // BitsPerSample needs four shorts, which do not fit in the four-byte value slot, so they
        // live after the directory and the slot holds their offset.
        val directorySize = 2 + 12 * 12 + 4
        val bitsOffset = pixelOffset + dataSize + directorySize

        entry(TAG_WIDTH, TYPE_LONG, 1, image.width)
        entry(TAG_HEIGHT, TYPE_LONG, 1, image.height)
        entry(TAG_BITS_PER_SAMPLE, TYPE_SHORT, 4, bitsOffset)
        entry(TAG_COMPRESSION, TYPE_SHORT, 1, COMPRESSION_NONE)
        entry(TAG_PHOTOMETRIC, TYPE_SHORT, 1, PHOTOMETRIC_RGB)
        entry(TAG_STRIP_OFFSETS, TYPE_LONG, 1, pixelOffset)
        entry(TAG_SAMPLES_PER_PIXEL, TYPE_SHORT, 1, 4)
        entry(TAG_ROWS_PER_STRIP, TYPE_LONG, 1, image.height)
        entry(TAG_STRIP_BYTE_COUNTS, TYPE_LONG, 1, dataSize)
        entry(TAG_PLANAR, TYPE_SHORT, 1, 1)
        entry(TAG_PREDICTOR, TYPE_SHORT, 1, 1)
        // Without this an alpha channel is "unspecified extra data" and readers composite the image
        // over black — which is the difference between a cut-out that keeps its edge and one that
        // arrives with a hard rectangle around it.
        entry(TAG_EXTRA_SAMPLES, TYPE_SHORT, 1, EXTRA_UNASSOCIATED_ALPHA)

        writeLe16(out, count)
        out.write(entries.toByteArray())
        writeLe32(out, 0)
        repeat(4) { writeLe16(out, 8) }
        return out.toByteArray()
    }

    // ---- directory ------------------------------------------------------------------------

    /** One image file directory, flattened to tag → values. */
    private class Ifd(val fields: Map<Int, List<Int>>) {
        fun int(tag: Int): Int? = fields[tag]?.firstOrNull()
        fun ints(tag: Int): List<Int>? = fields[tag]
    }

    private fun directory(bytes: ByteArray): Ifd {
        if (bytes.size < 8) throw CodecException("truncated TIFF header")
        val little = when {
            bytes[0] == 0x49.toByte() && bytes[1] == 0x49.toByte() -> true
            bytes[0] == 0x4D.toByte() && bytes[1] == 0x4D.toByte() -> false
            else -> throw CodecException("not a TIFF: byte order mark is missing")
        }
        if (u16(bytes, 2, little) != 42) throw CodecException("not a TIFF: magic 42 is missing")
        val start = u32(bytes, 4, little)
        if (start < 8 || start + 2 > bytes.size) throw CodecException("TIFF directory offset is out of range")

        val n = u16(bytes, start, little)
        val fields = HashMap<Int, List<Int>>(n)
        for (i in 0 until n) {
            val at = start + 2 + i * 12
            if (at + 12 > bytes.size) break
            val tag = u16(bytes, at, little)
            val type = u16(bytes, at + 2, little)
            val count = u32(bytes, at + 4, little)
            val size = sizeOf(type)
            if (size == 0 || count <= 0) continue
            val total = size * count
            // Four bytes or fewer live in the slot itself; anything larger puts an offset there.
            val from = if (total <= 4) at + 8 else u32(bytes, at + 8, little)
            if (from < 0 || from + total > bytes.size) continue
            fields[tag] = (0 until count).map { k ->
                when (size) {
                    1 -> bytes[from + k].toInt() and 0xFF
                    2 -> u16(bytes, from + k * 2, little)
                    else -> u32(bytes, from + k * 4, little)
                }
            }
        }
        return Ifd(fields)
    }

    private fun sizeOf(type: Int) = when (type) {
        TYPE_BYTE, TYPE_ASCII, TYPE_SBYTE, TYPE_UNDEFINED -> 1
        TYPE_SHORT, TYPE_SSHORT -> 2
        TYPE_LONG, TYPE_SLONG -> 4
        else -> 0
    }

    // ---- compression ----------------------------------------------------------------------

    /**
     * PackBits: a signed run-length count byte, then either a literal run or one repeated byte.
     *
     * `-128` is explicitly a no-op rather than a run of 129, which is the one case a naive reader
     * gets wrong and which Photoshop does emit.
     */
    private fun packBits(input: ByteArray, expected: Int): ByteArray {
        val out = ByteArray(expected)
        var read = 0
        var write = 0
        while (read < input.size && write < expected) {
            val n = input[read++].toInt()
            when {
                n >= 0 -> {
                    val run = n + 1
                    for (i in 0 until run) {
                        if (read >= input.size || write >= expected) break
                        out[write++] = input[read++]
                    }
                }
                n != -128 -> {
                    if (read >= input.size) break
                    val value = input[read++]
                    repeat(-n + 1) { if (write < expected) out[write++] = value }
                }
            }
        }
        return out
    }

    /**
     * TIFF's LZW: MSB-first codes, early change, and clear/end markers at 256 and 257.
     *
     * "Early change" is the detail that decides whether this works. TIFF widens the code one entry
     * *before* the dictionary is actually full — an off-by-one against the GIF variant of the same
     * algorithm, and the reason a decoder ported from GIF produces a correct first hundred bytes
     * and then garbage.
     */
    private fun lzw(input: ByteArray, expected: Int): ByteArray {
        val out = ByteArrayOutputStream(expected)
        var dictionary = arrayOfNulls<ByteArray>(4096)
        for (i in 0 until 256) dictionary[i] = byteArrayOf(i.toByte())
        var next = 258
        var width = 9
        var previous: ByteArray? = null

        var bitPosition = 0
        val totalBits = input.size * 8
        while (bitPosition + width <= totalBits) {
            var code = 0
            for (i in 0 until width) {
                val at = bitPosition + i
                val bit = (input[at ushr 3].toInt() shr (7 - (at and 7))) and 1
                code = (code shl 1) or bit
            }
            bitPosition += width

            if (code == LZW_CLEAR) {
                dictionary = arrayOfNulls(4096)
                for (i in 0 until 256) dictionary[i] = byteArrayOf(i.toByte())
                next = 258
                width = 9
                previous = null
                continue
            }
            if (code == LZW_END) break

            val entry = dictionary[code]
                ?: (previous?.let { it + it[0] } ?: throw CodecException("corrupt LZW stream in TIFF"))
            out.write(entry)
            if (previous != null && next < 4096) {
                dictionary[next++] = previous + entry[0]
            }
            previous = entry
            // The early change: widen one short of full.
            if (next + 1 >= (1 shl width) && width < 12) width++
            if (out.size() >= expected) break
        }
        return out.toByteArray()
    }

    /** Adobe writes Deflate under two different tag values; both are the same zlib stream. */
    private fun inflate(input: ByteArray, expected: Int): ByteArray {
        val inflater = java.util.zip.Inflater()
        inflater.setInput(input)
        val out = ByteArray(expected)
        var written = 0
        try {
            while (!inflater.finished() && written < expected) {
                val n = inflater.inflate(out, written, expected - written)
                if (n == 0) break
                written += n
            }
        } catch (e: java.util.zip.DataFormatException) {
            throw CodecException("corrupt Deflate stream in TIFF: ${e.message}")
        } finally {
            inflater.end()
        }
        return out
    }

    // ---- primitives -----------------------------------------------------------------------

    private fun u16(b: ByteArray, at: Int, little: Boolean): Int {
        val lo = b[at].toInt() and 0xFF
        val hi = b[at + 1].toInt() and 0xFF
        return if (little) (hi shl 8) or lo else (lo shl 8) or hi
    }

    private fun u32(b: ByteArray, at: Int, little: Boolean): Int {
        val a = b[at].toInt() and 0xFF
        val c = b[at + 1].toInt() and 0xFF
        val d = b[at + 2].toInt() and 0xFF
        val e = b[at + 3].toInt() and 0xFF
        return if (little) (e shl 24) or (d shl 16) or (c shl 8) or a
        else (a shl 24) or (c shl 16) or (d shl 8) or e
    }

    private fun writeLe16(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
    }

    private fun writeLe32(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF); out.write((v ushr 24) and 0xFF)
    }

    private const val TAG_WIDTH = 256
    private const val TAG_HEIGHT = 257
    private const val TAG_BITS_PER_SAMPLE = 258
    private const val TAG_COMPRESSION = 259
    private const val TAG_PHOTOMETRIC = 262
    private const val TAG_STRIP_OFFSETS = 273
    private const val TAG_SAMPLES_PER_PIXEL = 277
    private const val TAG_ROWS_PER_STRIP = 278
    private const val TAG_STRIP_BYTE_COUNTS = 279
    private const val TAG_PLANAR = 284
    private const val TAG_PREDICTOR = 317
    private const val TAG_TILE_WIDTH = 322
    private const val TAG_EXTRA_SAMPLES = 338

    private const val TYPE_BYTE = 1
    private const val TYPE_ASCII = 2
    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4
    private const val TYPE_SBYTE = 6
    private const val TYPE_UNDEFINED = 7
    private const val TYPE_SSHORT = 8
    private const val TYPE_SLONG = 9

    private const val COMPRESSION_NONE = 1
    private const val COMPRESSION_LZW = 5
    private const val COMPRESSION_DEFLATE_ADOBE = 8
    private const val COMPRESSION_PACKBITS = 32773
    private const val COMPRESSION_DEFLATE = 32946

    private const val PHOTOMETRIC_WHITE_IS_ZERO = 0
    private const val PHOTOMETRIC_BLACK_IS_ZERO = 1
    private const val PHOTOMETRIC_RGB = 2

    private const val PREDICTOR_HORIZONTAL = 2
    private const val EXTRA_UNASSOCIATED_ALPHA = 2

    private const val LZW_CLEAR = 256
    private const val LZW_END = 257
}
