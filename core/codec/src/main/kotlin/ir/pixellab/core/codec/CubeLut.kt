package ir.pixellab.core.codec

/**
 * Adobe's `.cube` colour lookup table, unpacked into the strip the shader samples.
 *
 * The Color Lookup adjustment has been renderable since wave 4 — the shader branch is there, the
 * uniforms are packed, the texture is bound — and completely unusable, because the panel created it
 * with a placeholder asset id and there was nothing anywhere that could turn a `.cube` file into
 * pixels. A whole adjustment that could not be pointed at anything.
 *
 * **The layout is not a choice.** A three-dimensional table has to reach a fragment shader as a
 * two-dimensional texture, and the arrangement every tool agrees on is slices laid out in a grid:
 * one square per blue level, eight across, with red running along x and green along y inside each.
 * At 64 levels that is exactly 512×512, which is why that number appears in the shader.
 *
 * The format itself is plain text and forgiving, which is the trap. Files in the wild carry comments,
 * blank lines, Windows line endings, `TITLE` strings with spaces, and a `DOMAIN_MIN`/`DOMAIN_MAX`
 * pair that is usually 0..1 and occasionally is not. A parser that assumes the tidy case reads about
 * half of a real grading pack and silently produces a black table for the rest.
 */
object CubeLut {

    /** What the shader wants: a square strip of [SLICES_PER_ROW] × [SLICES_PER_ROW] slices. */
    const val SLICES = 64
    const val SLICES_PER_ROW = 8
    const val STRIP = SLICES * SLICES_PER_ROW

    /**
     * Reads a `.cube` file and resamples it onto the fixed 64-level strip.
     *
     * Resampled rather than required to be 64 already: 17, 25, 32 and 33 are all common sizes, and
     * refusing them would reject most of what a user owns. Trilinear on the way in, because a
     * nearest-neighbour fit from 17 levels to 64 reintroduces exactly the banding the larger table
     * was interpolated to avoid.
     *
     * @return the strip, or null when the text is not a cube at all — a wrong file chosen in a
     *   picker is a normal event and belongs in the caller's error path, not in an exception.
     */
    fun read(bytes: ByteArray): RasterImage? {
        val text = bytes.toString(Charsets.UTF_8)
        var size = 0
        var oneDimensional = false
        var min = floatArrayOf(0f, 0f, 0f)
        var max = floatArrayOf(1f, 1f, 1f)
        val entries = ArrayList<FloatArray>()

        for (raw in text.lineSequence()) {
            // Comments run to the end of the line, and a trailing '\r' from a Windows file would
            // otherwise land inside the last number and fail to parse.
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val parts = line.split(WHITESPACE)
            when (parts[0].uppercase()) {
                "LUT_3D_SIZE" -> size = parts.getOrNull(1)?.toIntOrNull() ?: return null
                "LUT_1D_SIZE" -> {
                    size = parts.getOrNull(1)?.toIntOrNull() ?: return null
                    oneDimensional = true
                }
                "DOMAIN_MIN" -> min = triple(parts) ?: return null
                "DOMAIN_MAX" -> max = triple(parts) ?: return null
                // TITLE and anything else declarative is ignored rather than rejected; the format
                // grows keywords and a reader that fails on an unknown one ages badly.
                "TITLE" -> Unit
                else -> {
                    val entry = triple(parts) ?: continue
                    entries += entry
                }
            }
        }

        if (size < 2) return null
        val expected = if (oneDimensional) size else size * size * size
        if (entries.size < expected) return null

        val span = FloatArray(3) { (max[it] - min[it]).let { d -> if (d == 0f) 1f else d } }
        val sample: (Int, Int, Int) -> FloatArray = if (oneDimensional) {
            // A one-dimensional table is three independent curves. Reading it as a cube would need
            // size³ entries it does not have, so each channel is looked up on its own axis.
            { r, g, b ->
                floatArrayOf(
                    normalise(entries[r][0], min[0], span[0]),
                    normalise(entries[g][1], min[1], span[1]),
                    normalise(entries[b][2], min[2], span[2]),
                )
            }
        } else {
            // Red varies fastest, then green, then blue — the format's own ordering, and getting it
            // backwards mirrors the grade about its neutral axis while still looking like a grade.
            { r, g, b ->
                val e = entries[r + g * size + b * size * size]
                floatArrayOf(
                    normalise(e[0], min[0], span[0]),
                    normalise(e[1], min[1], span[1]),
                    normalise(e[2], min[2], span[2]),
                )
            }
        }

        val pixels = IntArray(STRIP * STRIP)
        for (slice in 0 until SLICES) {
            val originX = (slice % SLICES_PER_ROW) * SLICES
            val originY = (slice / SLICES_PER_ROW) * SLICES
            val blue = slice.toFloat() / (SLICES - 1) * (size - 1)
            for (y in 0 until SLICES) {
                val green = y.toFloat() / (SLICES - 1) * (size - 1)
                for (x in 0 until SLICES) {
                    val red = x.toFloat() / (SLICES - 1) * (size - 1)
                    val c = trilinear(sample, size, red, green, blue)
                    pixels[(originY + y) * STRIP + originX + x] =
                        (0xFF shl 24) or (byte(c[0]) shl 16) or (byte(c[1]) shl 8) or byte(c[2])
                }
            }
        }
        return RasterImage(STRIP, STRIP, pixels)
    }

    /** Eight corners of the containing cell, weighted by how far in the sample point sits. */
    private fun trilinear(
        sample: (Int, Int, Int) -> FloatArray,
        size: Int,
        red: Float,
        green: Float,
        blue: Float,
    ): FloatArray {
        val r0 = red.toInt().coerceIn(0, size - 1)
        val g0 = green.toInt().coerceIn(0, size - 1)
        val b0 = blue.toInt().coerceIn(0, size - 1)
        val r1 = (r0 + 1).coerceAtMost(size - 1)
        val g1 = (g0 + 1).coerceAtMost(size - 1)
        val b1 = (b0 + 1).coerceAtMost(size - 1)
        val fr = red - r0
        val fg = green - g0
        val fb = blue - b0

        val out = FloatArray(3)
        for (channel in 0 until 3) {
            val c000 = sample(r0, g0, b0)[channel]
            val c100 = sample(r1, g0, b0)[channel]
            val c010 = sample(r0, g1, b0)[channel]
            val c110 = sample(r1, g1, b0)[channel]
            val c001 = sample(r0, g0, b1)[channel]
            val c101 = sample(r1, g0, b1)[channel]
            val c011 = sample(r0, g1, b1)[channel]
            val c111 = sample(r1, g1, b1)[channel]

            val front = lerp(lerp(c000, c100, fr), lerp(c010, c110, fr), fg)
            val back = lerp(lerp(c001, c101, fr), lerp(c011, c111, fr), fg)
            out[channel] = lerp(front, back, fb)
        }
        return out
    }

    private fun triple(parts: List<String>): FloatArray? {
        if (parts.size < 3) return null
        val start = if (parts.size > 3) 1 else 0
        val r = parts[start].toFloatOrNull() ?: return null
        val g = parts[start + 1].toFloatOrNull() ?: return null
        val b = parts[start + 2].toFloatOrNull() ?: return null
        return floatArrayOf(r, g, b)
    }

    private fun normalise(v: Float, min: Float, span: Float) = (v - min) / span

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun byte(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    private val WHITESPACE = Regex("\\s+")
}
