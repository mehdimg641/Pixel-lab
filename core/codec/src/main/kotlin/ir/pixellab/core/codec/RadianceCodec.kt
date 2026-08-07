package ir.pixellab.core.codec

import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Radiance HDR, the format every free environment map on the internet comes in.
 *
 * ### What it stores, and what this returns
 *
 * Four bytes per pixel: three mantissas and one shared exponent. That buys a range far past what
 * eight bits can hold — the sun in a sky map is thousands of times brighter than the ground, and
 * that ratio is the entire reason image-based lighting looks like light rather than like a
 * gradient.
 *
 * [RasterImage] is 8-bit ARGB, so [decode] has to land somewhere in that range and the choice is
 * not neutral. It normalises by the 99th-percentile luminance rather than the maximum: one
 * specular pixel on a chrome ball is routinely a hundred times the next brightest thing in the
 * frame, and dividing by it turns the entire sky into near-black. Then Reinhard, then sRGB
 * transfer — the same two steps the renderer's own tone map uses, so a map previewed here looks
 * like the map that lights the scene.
 *
 * ### The full-range path
 *
 * [decodeRadiance] returns the floats. That is what an environment map wants, and losing the range
 * on the way in would make the prefiltered mip chain a chain of prefiltered guesses. The
 * [ImageDecoder] entry point exists so the format opens like any other picture; the renderer calls
 * the other one.
 */
object RadianceCodec : ImageDecoder, ImageEncoder {

    override val format = Format.HDR

    /** Linear RGB, one triple per pixel, row-major from the top left. */
    class Radiance(val width: Int, val height: Int, val rgb: FloatArray)

    override fun probe(bytes: ByteArray): Pair<Int, Int>? = runCatching {
        val header = readHeader(bytes)
        header.width to header.height
    }.getOrNull()

    override fun decode(bytes: ByteArray): RasterImage {
        val hdr = decodeRadiance(bytes)
        val pixels = IntArray(hdr.width * hdr.height)

        // The 99th percentile, not the maximum. See the class note: one blown specular highlight
        // otherwise sets the scale for the whole frame.
        val luminance = FloatArray(pixels.size) { i ->
            val at = i * 3
            0.2126f * hdr.rgb[at] + 0.7152f * hdr.rgb[at + 1] + 0.0722f * hdr.rgb[at + 2]
        }
        val sorted = luminance.clone().apply { sort() }
        val reference = sorted.getOrElse((sorted.size * 0.99f).toInt().coerceAtMost(sorted.size - 1)) { 1f }
        val scale = if (reference > 1e-6f) 1f / reference else 1f

        for (i in pixels.indices) {
            val at = i * 3
            var r = hdr.rgb[at] * scale
            var g = hdr.rgb[at + 1] * scale
            var b = hdr.rgb[at + 2] * scale
            // Reinhard: everything lands in 0..1 and nothing clips to a flat white plateau.
            r /= 1f + r; g /= 1f + g; b /= 1f + b
            pixels[i] = (0xFF shl 24) or (srgb(r) shl 16) or (srgb(g) shl 8) or srgb(b)
        }
        return RasterImage(hdr.width, hdr.height, pixels)
    }

    /** The pixels as linear floats, with the dynamic range intact. */
    fun decodeRadiance(bytes: ByteArray): Radiance {
        val header = readHeader(bytes)
        val width = header.width
        val height = header.height
        val rgb = FloatArray(width * height * 3)
        var at = header.dataStart
        val scanline = ByteArray(width * 4)

        for (y in 0 until height) {
            at = readScanline(bytes, at, width, scanline)
            for (x in 0 until width) {
                val e = scanline[x * 4 + 3].toInt() and 0xFF
                val target = (y * width + x) * 3
                if (e == 0) continue
                // The exponent is biased by 128, and the extra 8 turns the stored 0..255 mantissa
                // into the 0..1 fraction the format defines.
                val f = 2f.pow(e - (128 + 8))
                rgb[target] = (scanline[x * 4].toInt() and 0xFF) * f
                rgb[target + 1] = (scanline[x * 4 + 1].toInt() and 0xFF) * f
                rgb[target + 2] = (scanline[x * 4 + 2].toInt() and 0xFF) * f
            }
        }
        return Radiance(width, height, rgb)
    }

    /**
     * Writes flat, uncompressed RGBE.
     *
     * No run-length on the way out. The encoder exists so a document can be handed to a renderer
     * that wants floats, and an exporter that produces a file every reader accepts is worth more
     * than one that produces a smaller file some readers argue about.
     */
    override fun encode(image: RasterImage, quality: Int): ByteArray {
        val out = ByteArrayOutputStream(image.pixels.size * 4 + 128)
        out.write("#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n".toByteArray(Charsets.US_ASCII))
        out.write("-Y ${image.height} +X ${image.width}\n".toByteArray(Charsets.US_ASCII))

        for (argb in image.pixels) {
            // Undo sRGB on the way back to linear, so a file written from a decoded one round-trips
            // as the same picture rather than as a progressively brighter one.
            val r = linear(((argb ushr 16) and 0xFF) / 255f)
            val g = linear(((argb ushr 8) and 0xFF) / 255f)
            val b = linear((argb and 0xFF) / 255f)
            val peak = max(r, max(g, b))
            if (peak < 1e-8f) {
                repeat(4) { out.write(0) }
                continue
            }
            var exponent = 0
            var mantissa = peak
            while (mantissa >= 1f) { mantissa /= 2f; exponent++ }
            while (mantissa < 0.5f) { mantissa *= 2f; exponent-- }
            val f = 255.9999f * mantissa / peak
            out.write((r * f).toInt().coerceIn(0, 255))
            out.write((g * f).toInt().coerceIn(0, 255))
            out.write((b * f).toInt().coerceIn(0, 255))
            out.write((exponent + 128).coerceIn(0, 255))
        }
        return out.toByteArray()
    }

    // ---- header ------------------------------------------------------------------------------

    private class Header(val width: Int, val height: Int, val dataStart: Int)

    private fun readHeader(bytes: ByteArray): Header {
        if (bytes.size < 10) throw CodecException("truncated Radiance header")
        val magic = String(bytes, 0, minOf(10, bytes.size), Charsets.US_ASCII)
        if (!magic.startsWith("#?RADIANCE") && !magic.startsWith("#?RGBE")) {
            throw CodecException("not a Radiance file")
        }

        var at = 0
        var line: String
        // Lines of `KEY=value` and comments, terminated by an empty line.
        do {
            val end = bytes.lineEnd(at)
            line = String(bytes, at, end - at, Charsets.US_ASCII).trim()
            at = end + 1
            if (at > bytes.size) throw CodecException("Radiance header has no resolution line")
        } while (line.isNotEmpty())

        val end = bytes.lineEnd(at)
        val resolution = String(bytes, at, end - at, Charsets.US_ASCII).trim()
        at = end + 1

        // `-Y height +X width` is the only orientation in practical use; the seven others are
        // legal and would be silently mirrored or rotated if assumed away.
        val match = Regex("""^-Y\s+(\d+)\s+\+X\s+(\d+)$""").find(resolution)
            ?: throw CodecException("unsupported Radiance orientation: «$resolution»")
        val height = match.groupValues[1].toInt()
        val width = match.groupValues[2].toInt()
        if (width <= 0 || height <= 0) throw CodecException("Radiance file has no area")
        return Header(width, height, at)
    }

    private fun ByteArray.lineEnd(from: Int): Int {
        var i = from
        while (i < size && this[i] != '\n'.code.toByte()) i++
        return i
    }

    // ---- scanlines ---------------------------------------------------------------------------

    /**
     * Reads one scanline, in whichever of the two layouts the file uses.
     *
     * New-style RLE marks itself with `2 2` and a big-endian width, and stores the four channels
     * *separately* — all the reds, then all the greens. Old-style stores whole RGBE pixels and
     * signals a run with `1 1 1 count`. A file can technically mix them line by line, so the
     * decision is made per scanline rather than once.
     */
    private fun readScanline(bytes: ByteArray, start: Int, width: Int, out: ByteArray): Int {
        var at = start
        if (at + 4 > bytes.size) throw CodecException("Radiance data ended early")

        val a = bytes[at].toInt() and 0xFF
        val b = bytes[at + 1].toInt() and 0xFF
        val c = bytes[at + 2].toInt() and 0xFF
        val d = bytes[at + 3].toInt() and 0xFF
        val newStyle = a == 2 && b == 2 && ((c shl 8) or d) == width && width in 8..0x7FFF

        if (!newStyle) {
            // Flat, or old-style RLE.
            var x = 0
            while (x < width) {
                if (at + 4 > bytes.size) throw CodecException("Radiance data ended early")
                val r = bytes[at].toInt() and 0xFF
                val g = bytes[at + 1].toInt() and 0xFF
                val bb = bytes[at + 2].toInt() and 0xFF
                val e = bytes[at + 3].toInt() and 0xFF
                at += 4
                if (r == 1 && g == 1 && bb == 1) {
                    // A repeat of the previous pixel, `e` times. Consecutive markers shift the
                    // count left by eight each time, which is how runs past 255 are expressed.
                    if (x == 0) throw CodecException("Radiance run with nothing to repeat")
                    val previous = (x - 1) * 4
                    repeat(e) {
                        if (x >= width) return@repeat
                        out.copyInto(out, x * 4, previous, previous + 4)
                        x++
                    }
                } else {
                    out[x * 4] = r.toByte()
                    out[x * 4 + 1] = g.toByte()
                    out[x * 4 + 2] = bb.toByte()
                    out[x * 4 + 3] = e.toByte()
                    x++
                }
            }
            return at
        }

        at += 4
        for (channel in 0 until 4) {
            var x = 0
            while (x < width) {
                if (at >= bytes.size) throw CodecException("Radiance data ended early")
                val count = bytes[at++].toInt() and 0xFF
                if (count > 128) {
                    // A run: one value repeated count-128 times.
                    if (at >= bytes.size) throw CodecException("Radiance data ended early")
                    val value = bytes[at++]
                    repeat(count - 128) {
                        if (x < width) out[(x++) * 4 + channel] = value
                    }
                } else {
                    if (count == 0) throw CodecException("Radiance literal run of zero length")
                    repeat(count) {
                        if (at < bytes.size && x < width) out[(x++) * 4 + channel] = bytes[at++]
                    }
                }
            }
        }
        return at
    }

    // ---- transfer ----------------------------------------------------------------------------

    private fun srgb(linear: Float): Int {
        val v = linear.coerceIn(0f, 1f)
        val encoded = if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f
        return (encoded * 255f).roundToInt().coerceIn(0, 255)
    }

    private fun linear(encoded: Float): Float {
        val v = encoded.coerceIn(0f, 1f)
        return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    }
}
