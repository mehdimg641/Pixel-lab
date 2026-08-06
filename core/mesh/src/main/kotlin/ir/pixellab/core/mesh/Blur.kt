package ir.pixellab.core.mesh

/**
 * A soft edge, applied to a coverage buffer.
 *
 * Shared because both shadows need one and they need the same one: a cast shadow's penumbra and an
 * inner shadow's falloff are the same operation on the same kind of buffer, and two copies of a
 * sliding-window box blur is two places for an off-by-one to live.
 *
 * Three box passes rather than a true Gaussian. Three boxes approximate one to within about a
 * percent — the central limit theorem doing the work — and cost four adds per pixel regardless of
 * radius, where a real Gaussian at the radii these shadows use would be a kernel hundreds of samples
 * wide. Under something that is soft by definition, the difference cannot be seen.
 */
internal object Blur {

    fun box(buffer: FloatArray, width: Int, height: Int, radius: Int) {
        if (radius <= 0) return
        val scratch = FloatArray(buffer.size)
        repeat(PASSES) {
            horizontal(buffer, scratch, width, height, radius)
            vertical(scratch, buffer, width, height, radius)
        }
    }

    private fun horizontal(src: FloatArray, dst: FloatArray, width: Int, height: Int, radius: Int) {
        val span = radius * 2 + 1
        for (y in 0 until height) {
            val row = y * width
            // Edges clamp rather than treating the outside as empty. For a cast shadow that stops a
            // dark band being drawn down the border of a shadow that runs off the frame; for an
            // inner shadow it is what makes a letter touching the edge behave like one that is not.
            var sum = 0f
            for (i in -radius..radius) sum += src[row + i.coerceIn(0, width - 1)]
            for (x in 0 until width) {
                dst[row + x] = sum / span
                sum -= src[row + (x - radius).coerceIn(0, width - 1)]
                sum += src[row + (x + radius + 1).coerceIn(0, width - 1)]
            }
        }
    }

    private fun vertical(src: FloatArray, dst: FloatArray, width: Int, height: Int, radius: Int) {
        val span = radius * 2 + 1
        for (x in 0 until width) {
            var sum = 0f
            for (i in -radius..radius) sum += src[i.coerceIn(0, height - 1) * width + x]
            for (y in 0 until height) {
                dst[y * width + x] = sum / span
                sum -= src[(y - radius).coerceIn(0, height - 1) * width + x]
                sum += src[(y + radius + 1).coerceIn(0, height - 1) * width + x]
            }
        }
    }

    private const val PASSES = 3
}
