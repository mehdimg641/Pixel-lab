package ir.pixellab.engine.android

import ir.pixellab.core.codec.CodecException
import ir.pixellab.core.codec.Codecs
import ir.pixellab.core.codec.Format
import ir.pixellab.core.codec.ImageEncoder
import ir.pixellab.core.codec.RasterImage
import ir.pixellab.core.model.Document
import ir.pixellab.core.render.MemoryBudget

/** What an export produced, or why it could not. */
sealed interface ExportResult {
    data class Success(val bytes: ByteArray, val width: Int, val height: Int) : ExportResult {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    /**
     * Refused before rendering.
     *
     * [advice] carries what the memory budget suggested — lower precision, tiling, lower
     * resolution — so the caller can offer a way forward rather than a dead end.
     */
    data class TooLarge(val requiredBytes: Long, val availableBytes: Long, val advice: List<String>) : ExportResult

    data class Failed(val reason: String) : ExportResult
}

/**
 * Renders a document to a file.
 *
 * Runs the same pipeline the canvas does, at a chosen scale and with no camera — an export is the
 * artwork, not the artwork as the user happens to be looking at it.
 *
 * The size check happens *first*. A 4K export of a ten-shadow style is hundreds of megabytes of
 * texture, and discovering that halfway through means the user waited and then lost the render;
 * refusing up front with a suggestion costs them a second.
 */
class Exporter(
    private val context: GlContext,
    private val device: AndroidGlDevice,
    private val renderer: DocumentRenderer,
) {
    fun export(
        document: Document,
        format: Format,
        scale: Float = 1f,
        availableBytes: Long = Long.MAX_VALUE,
        /** 1–100, and only the lossy formats read it. See [ImageEncoder.encode]. */
        quality: Int = ImageEncoder.DEFAULT_QUALITY,
    ): ExportResult {
        val encoder = Codecs.encoderFor(format)
            ?: return ExportResult.Failed("${format.label} cannot be written in this build")

        val required = renderer.estimateBytes(document, scale)
        if (required > availableBytes) {
            return ExportResult.TooLarge(
                requiredBytes = required,
                availableBytes = availableBytes,
                advice = MemoryBudget.advise(required, availableBytes, document.color),
            )
        }

        val width = kotlin.math.ceil(document.canvas.width * scale).toInt().coerceAtLeast(1)
        val height = kotlin.math.ceil(document.canvas.height * scale).toInt().coerceAtLeast(1)
        if (width > device.maxTextureSize || height > device.maxTextureSize) {
            return ExportResult.Failed(
                "این اندازه از سقف بافت دستگاه (${device.maxTextureSize}) بیشتر است",
            )
        }

        return try {
            context.makeCurrent()
            // No viewport: the camera belongs to the screen, and applying it here would export
            // whatever happened to be visible, cropped and at the wrong scale.
            val target = device.createTexture(width, height, document.color.precision.bytesPerPixel)
            device.bindTarget(target)
            device.clearTarget()
            renderer.render(document, scale = scale, viewport = null)
            renderer.blitTo(document, target)

            val image = device.readPixelsAsImage(target, width, height)
            device.deleteTexture(target)

            val errors = renderer.lastErrors
            if (errors.isNotEmpty()) {
                ExportResult.Failed(errors.first().let { "${it.shaderId}: ${it.reason}" })
            } else {
                ExportResult.Success(encoder.encode(image, quality), width, height)
            }
        } catch (e: CodecException) {
            ExportResult.Failed(e.message ?: "encoding failed")
        }
    }

    /** File name for an export, with the extension the format actually uses. */
    fun fileNameFor(document: Document, format: Format): String {
        val stem = document.name.ifBlank { "untitled" }.replace(Regex("[/\\\\:*?\"<>|]"), "_")
        return "$stem.${format.extensions.firstOrNull() ?: "png"}"
    }
}

/** Reads a target back as a decoded image, ready for any encoder in the registry. */
fun AndroidGlDevice.readPixelsAsImage(handle: ir.pixellab.core.render.TextureHandle, width: Int, height: Int): RasterImage {
    val buffer = readPixels(handle, width, height)
    val pixels = IntArray(width * height)
    for (i in pixels.indices) {
        val at = i * 4
        val r = buffer.get(at).toInt() and 0xFF
        val g = buffer.get(at + 1).toInt() and 0xFF
        val b = buffer.get(at + 2).toInt() and 0xFF
        val a = buffer.get(at + 3).toInt() and 0xFF
        pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
    }
    // GL's origin is bottom-left and every image format's is top-left; skipping this flip is the
    // classic upside-down export.
    flipRows(pixels, width, height)
    return RasterImage(width, height, pixels)
}

private fun flipRows(pixels: IntArray, width: Int, height: Int) {
    val row = IntArray(width)
    for (y in 0 until height / 2) {
        val top = y * width
        val bottom = (height - 1 - y) * width
        System.arraycopy(pixels, top, row, 0, width)
        System.arraycopy(pixels, bottom, pixels, top, width)
        System.arraycopy(row, 0, pixels, bottom, width)
    }
}
