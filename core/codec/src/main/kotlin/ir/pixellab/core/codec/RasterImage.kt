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

    /**
     * @param quality 1–100 for the lossy formats, ignored by the lossless ones.
     *
     * A parameter rather than a property of the encoder, because it is a decision the *user* makes
     * per export and not a property of the build. It had been fixed at 92 since the encoder was
     * written: a JPEG for a chat app and a JPEG for print came out the same size, and the only way
     * to make a smaller file was to make a smaller image. Defaulted so the lossless encoders and
     * every existing call site are unaffected.
     */
    fun encode(image: RasterImage, quality: Int = DEFAULT_QUALITY): ByteArray

    companion object {
        /**
         * High but not lossless.
         *
         * A design export is flat colour and hard type edges, where JPEG artefacts are far more
         * visible than in a photograph; anything below this shows ringing around text. Photoshop's
         * "Maximum" sits about here, and its ×12 is around 96 — roughly double the file for a
         * difference nobody can see without a difference blend.
         */
        const val DEFAULT_QUALITY = 95

        /** Below this, chroma subsampling artefacts are visible on flat colour, which text is. */
        const val MIN_QUALITY = 1

        const val MAX_QUALITY = 100
    }
}
