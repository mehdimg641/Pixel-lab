package ir.pixellab.core.codec

/**
 * Photoshop Raw — the pixels and nothing else.
 *
 * No header, no dimensions, no channel count. Opening one means telling the reader what it is
 * looking at, which is why [Format.PHOTOSHOP_RAW] declares `Support.NONE` for reading: the registry
 * answers "can this build open a file of this format" and the honest answer here is no, not without
 * three numbers that are not in it.
 *
 * Writing is the whole use. It is the interchange of last resort — the thing you reach for when the
 * program at the other end is a renderer, a plotter or somebody's own script, and it wants a plane
 * of bytes in a known order rather than a container it has to parse.
 *
 * ### Why it needed writing today
 *
 * The table said `Backend.OWN` and `Support.FLAT` for writing and there was no encoder. That made
 * four formats in the same table making a promise nobody kept, and this was the one the guard could
 * not have caught even with its whitelist removed — the test only ever checked the read direction.
 *
 * Interleaved RGBA, eight bits a channel, top row first. That is what Photoshop's own "Raw" export
 * produces at those settings, and it is the only layout worth defaulting to: planar order is a
 * second file format wearing the same extension.
 */
object PhotoshopRawCodec : ImageEncoder {

    override val format = Format.PHOTOSHOP_RAW

    override fun encode(image: RasterImage, quality: Int): ByteArray {
        val out = ByteArray(image.pixels.size * 4)
        var at = 0
        for (argb in image.pixels) {
            out[at++] = ((argb ushr 16) and 0xFF).toByte()
            out[at++] = ((argb ushr 8) and 0xFF).toByte()
            out[at++] = (argb and 0xFF).toByte()
            out[at++] = ((argb ushr 24) and 0xFF).toByte()
        }
        return out
    }
}
