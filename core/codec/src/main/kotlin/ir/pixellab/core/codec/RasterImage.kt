package ir.pixellab.core.codec

/**
 * Decoded pixels, straight (non-premultiplied) ARGB.
 *
 * One representation for every codec so the editor never has to ask which format a picture came
 * from. Straight rather than premultiplied because a codec that premultiplies on the way in cannot
 * give the original colour back once alpha has quantised — the loss shows as dark fringing on the
 * edge of anything cut out.
 */
class RasterImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0) { "image must be positive, got ${width}x$height" }
        require(pixels.size == width * height) {
            "expected ${width * height} pixels for ${width}x$height, got ${pixels.size}"
        }
    }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]

    operator fun set(x: Int, y: Int, argb: Int) {
        pixels[y * width + x] = argb
    }

    companion object {
        fun blank(width: Int, height: Int, argb: Int = 0) =
            RasterImage(width, height, IntArray(width * height) { argb })
    }
}

/** Thrown when a file is malformed rather than merely unsupported. */
class CodecException(message: String) : Exception(message)

/** Decodes one format into pixels. */
interface ImageDecoder {
    val format: Format

    /** Cheap enough to run over a directory: reads the header only. */
    fun probe(bytes: ByteArray): Pair<Int, Int>?

    fun decode(bytes: ByteArray): RasterImage
}

interface ImageEncoder {
    val format: Format

    fun encode(image: RasterImage): ByteArray
}
