package ir.pixellab.core.codec

import ir.pixellab.core.model.Adjustment
import ir.pixellab.core.model.Curve
import ir.pixellab.core.model.Vec2

/**
 * Photoshop's `.acv` curve presets.
 *
 * A tiny format, and worth supporting out of proportion to its size: every colour-grading pack sold
 * or given away for the last twenty years ships as a folder of these, and reading them means a
 * user's existing looks work here on the first day rather than being redrawn by hand.
 *
 * The layout is four big-endian shorts of header and then, per curve, a count followed by that many
 * `(output, input)` pairs in 0..255. The reversed order of the pair is the one thing that trips
 * every implementation of this format, and it fails silently: a curve read the wrong way round is
 * still a valid curve, just the inverse of the one the author drew.
 */
object CurvePreset {

    /** The only version Photoshop has ever written, and the only one any file in the wild has. */
    const val VERSION = 4

    /**
     * Reads a preset.
     *
     * Returns null rather than throwing on anything malformed. A preset pack routinely contains a
     * stray file, and a user who picked a folder of two hundred curves wants the other 199 applied,
     * not an error.
     */
    fun read(bytes: ByteArray): Adjustment.Curves? {
        if (bytes.size < HEADER_BYTES) return null
        val reader = ByteReader(bytes)
        if (reader.u16() != VERSION) return null

        val count = reader.u16()
        // Composite plus up to four channels in Photoshop's own files; anything else is not an
        // .acv whatever its extension says.
        if (count !in 1..MAX_CURVES) return null

        val curves = ArrayList<Curve>(count)
        repeat(count) {
            curves += readCurve(reader) ?: return null
        }
        return Adjustment.Curves(
            rgb = curves.getOrElse(0) { Curve.LINEAR },
            red = curves.getOrElse(1) { Curve.LINEAR },
            green = curves.getOrElse(2) { Curve.LINEAR },
            blue = curves.getOrElse(3) { Curve.LINEAR },
        )
    }

    /** Writes one, so a look built here leaves in the format everything else can read. */
    fun write(curves: Adjustment.Curves): ByteArray {
        val ordered = listOf(curves.rgb, curves.red, curves.green, curves.blue)
        val out = ArrayList<Byte>()
        fun short(value: Int) {
            out += ((value shr 8) and 0xFF).toByte()
            out += (value and 0xFF).toByte()
        }
        short(VERSION)
        short(ordered.size)
        for (curve in ordered) {
            val points = curve.points
            short(points.size)
            for (point in points) {
                // Output first, then input — the file's order, not the one that reads naturally.
                short((point.y.coerceIn(0f, 1f) * FULL + 0.5f).toInt())
                short((point.x.coerceIn(0f, 1f) * FULL + 0.5f).toInt())
            }
        }
        return out.toByteArray()
    }

    private fun readCurve(reader: ByteReader): Curve? {
        if (!reader.hasRemaining(2)) return null
        val points = reader.u16()
        // Photoshop's own limit. A larger count is the surest sign the cursor has drifted, and
        // reading on from a drifted cursor produces a curve that looks deliberate and is noise.
        if (points !in 0..MAX_POINTS) return null
        if (!reader.hasRemaining(points * 4)) return null

        val read = ArrayList<Vec2>(points)
        repeat(points) {
            val output = reader.u16()
            val input = reader.u16()
            read += Vec2(input / FULL, output / FULL)
        }
        // A channel Photoshop left alone is written as zero points, and the model's Curve needs
        // two — so "untouched" has to become the identity rather than an empty list.
        if (read.size < 2) return Curve.LINEAR
        // Sorted because the model's evaluation walks the points in order, and a file may list a
        // dragged endpoint before the point it was dragged past.
        return Curve(read.sortedBy { it.x })
    }

    private const val HEADER_BYTES = 4
    private const val MAX_CURVES = 5
    private const val MAX_POINTS = 19
    private const val FULL = 255f
}
