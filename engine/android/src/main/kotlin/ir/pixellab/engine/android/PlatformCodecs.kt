package ir.pixellab.engine.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.annotation.RequiresApi
import ir.pixellab.core.codec.CodecException
import ir.pixellab.core.codec.Codecs
import ir.pixellab.core.codec.Format
import ir.pixellab.core.codec.ImageDecoder
import ir.pixellab.core.codec.ImageEncoder
import ir.pixellab.core.codec.RasterImage
import java.io.ByteArrayOutputStream

/**
 * The formats the operating system already decodes.
 *
 * Delegated rather than reimplemented: Android's JPEG and PNG paths are hardware-accelerated and
 * handle the malformed files that exist in the wild, and no hand-written decoder is going to match
 * that. What is *not* delegated is anything the platform lacks — Targa, BMP writing, PSD — which is
 * why `core:codec` implements those itself.
 *
 * Registered at startup rather than declared statically, because availability is a property of the
 * device: HEIF needs API 28, and a build that promised it everywhere would fail on a phone the user
 * already owns.
 */
object PlatformCodecs {

    /** Wires every format this device can handle into the shared registry. */
    fun register() {
        Codecs.register(PlatformCodec(Format.JPEG, Bitmap.CompressFormat.JPEG))
        Codecs.register(PlatformCodec(Format.PNG, Bitmap.CompressFormat.PNG))
        Codecs.register(PlatformCodec(Format.WEBP, webpFormat()))
        // GIF and BMP decode on every version but neither can be written back, so only the
        // decoding half is registered; BMP writing comes from core:codec.
        Codecs.registerDecoder(PlatformCodec(Format.GIF, Bitmap.CompressFormat.PNG))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Codecs.register(PlatformCodec(Format.HEIF, heifFormat()))
        }
    }

    /**
     * WebP's lossy and lossless constants replaced the single one in API 30.
     *
     * Lossless is the right default for a design tool: an export is usually flat colour and text
     * edges, which is exactly where lossy WebP shows its artefacts.
     */
    private fun webpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSLESS
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun heifFormat(): Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG
}

/**
 * One format handled by the platform.
 *
 * @param compress the format `Bitmap.compress` writes; decoding is by content, so it is only used
 *   on the way out
 */
class PlatformCodec(
    override val format: Format,
    private val compress: Bitmap.CompressFormat,
) : ImageDecoder, ImageEncoder {

    /**
     * Reads the header only.
     *
     * `inJustDecodeBounds` is what makes a folder of 40-megapixel photos previewable: it reads the
     * dimensions without allocating the pixels, so a picker can lay out a grid for the cost of a
     * few hundred bytes each.
     */
    override fun probe(bytes: ByteArray): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else {
            null
        }
    }

    override fun decode(bytes: ByteArray): RasterImage {
        val options = BitmapFactory.Options().apply {
            // ARGB_8888 straight through: the editor's pipeline is straight alpha, and letting the
            // platform hand back RGB_565 to save memory would silently drop every gradient to
            // sixteen bits.
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw CodecException("${format.label} could not be decoded on this device")
        return try {
            bitmap.toRaster()
        } finally {
            bitmap.recycle()
        }
    }

    override fun encode(image: RasterImage, quality: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream(image.pixels.size).also { out ->
                if (!bitmap.compress(compress, quality.coerceIn(ImageEncoder.MIN_QUALITY, ImageEncoder.MAX_QUALITY), out)) {
                    throw CodecException("${format.label} encoding failed on this device")
                }
            }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

}

/**
 * Copies a bitmap into the codec layer's representation.
 *
 * `getPixels` always yields straight ARGB regardless of the bitmap's own config, which is what the
 * rest of the pipeline expects — a premultiplied source would otherwise arrive already darkened at
 * every soft edge.
 */
fun Bitmap.toRaster(): RasterImage {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    return RasterImage(width, height, pixels)
}

fun RasterImage.toBitmap(): Bitmap =
    Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
