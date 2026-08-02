package ir.pixellab.core.codec

import ir.pixellab.core.model.BlendMode
import ir.pixellab.core.model.Document
import ir.pixellab.core.model.Layer
import java.io.ByteArrayOutputStream

/** A layer as the writer needs it: its pixels, where they sit, and how they blend. */
data class PsdLayerSource(
    val name: String,
    val left: Int,
    val top: Int,
    val image: RasterImage,
    val opacity: Float = 1f,
    val blendMode: BlendMode = BlendMode.NORMAL,
    val visible: Boolean = true,
    val clipped: Boolean = false,
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/**
 * Writes a layered PSD.
 *
 * The point of this is reversibility: work started here has to be finishable in Photoshop, and a
 * flattened export is a one-way door. What is written is the structure Photoshop needs to reopen the
 * file with its layers intact — bounds, channels, blend mode, opacity, clipping and names.
 *
 * Effects are deliberately *not* written as live layer styles. A style Photoshop cannot round-trip
 * exactly is worse than none: the user would open the file, see a shadow at the wrong distance, and
 * have no way to tell which of the two tools was wrong. The pixels carry the appearance instead, and
 * the layer structure carries the editability.
 */
object PsdWriter {

    /**
     * @param flattened the composite, which Photoshop shows to programs that cannot read layers —
     *   and which every preview, every thumbnail and every other tool reads first
     */
    fun write(document: Document, layers: List<PsdLayerSource>, flattened: RasterImage): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = Writer(out)

        writeHeader(writer, document.canvas.width, document.canvas.height)
        // No colour-mode data for RGB; the section is still present with a zero length, and
        // omitting it entirely is the most common reason a hand-written PSD will not open.
        writer.int32(0)
        writeImageResources(writer)
        writeLayerSection(writer, layers)
        writeComposite(writer, flattened)

        return out.toByteArray()
    }

    private fun writeHeader(writer: Writer, width: Int, height: Int) {
        writer.ascii("8BPS")
        writer.int16(1)
        writer.bytes(ByteArray(6))
        writer.int16(CHANNELS)
        writer.int32(height)
        writer.int32(width)
        writer.int16(DEPTH)
        // 3 is RGB. The mode decides how many channels every later section expects, so a mismatch
        // here corrupts everything after it rather than producing a wrong colour.
        writer.int16(RGB_MODE)
    }

    /**
     * The resolution block.
     *
     * Photoshop will open a file without it and will then guess 72 dpi, which silently rescales
     * anything placed into a print document.
     */
    private fun writeImageResources(writer: Writer) {
        val resources = Writer(ByteArrayOutputStream())
        resources.ascii("8BIM")
        resources.int16(RESOLUTION_ID)
        resources.int16(0)
        resources.int32(RESOLUTION_LENGTH)
        resources.int32(DEFAULT_DPI shl 16)
        resources.int16(1)
        resources.int16(1)
        resources.int32(DEFAULT_DPI shl 16)
        resources.int16(1)
        resources.int16(1)

        val bytes = resources.toByteArray()
        writer.int32(bytes.size)
        writer.bytes(bytes)
    }

    private fun writeLayerSection(writer: Writer, layers: List<PsdLayerSource>) {
        if (layers.isEmpty()) {
            writer.int32(0)
            return
        }

        val info = Writer(ByteArrayOutputStream())
        info.int16(layers.size)

        val channelData = ArrayList<ByteArray>()
        for (layer in layers) {
            val right = layer.left + layer.image.width
            val bottom = layer.top + layer.image.height
            info.int32(layer.top)
            info.int32(layer.left)
            info.int32(bottom)
            info.int32(right)

            info.int16(CHANNELS)
            // Alpha first, at id -1, then red, green and blue. The ids are what Photoshop reads;
            // the order is only a convention, but writing the count wrong shifts every later layer.
            val planes = listOf(
                ALPHA_ID to plane(layer.image) { (it ushr 24) and 0xFF },
                0 to plane(layer.image) { (it shr 16) and 0xFF },
                1 to plane(layer.image) { (it shr 8) and 0xFF },
                2 to plane(layer.image) { it and 0xFF },
            )
            for ((id, data) in planes) {
                info.int16(id)
                // Two bytes of compression marker plus the samples themselves.
                info.int32(data.size + 2)
                channelData += data
            }

            info.ascii("8BIM")
            info.ascii(keyOf(layer.blendMode))
            info.byte((layer.opacity.coerceIn(0f, 1f) * 255f).toInt())
            info.byte(if (layer.clipped) 1 else 0)
            // Bit 1 is "hidden", which is the opposite of what the field's name suggests.
            info.byte(if (layer.visible) 0 else 2)
            info.byte(0)

            val extra = Writer(ByteArrayOutputStream())
            extra.int32(0) // no layer mask
            extra.int32(0) // no blending ranges
            extra.pascalString(layer.name)
            writeUnicodeName(extra, layer.name)

            val extraBytes = extra.toByteArray()
            info.int32(extraBytes.size)
            info.bytes(extraBytes)
        }

        for (data in channelData) {
            // Raw rather than RLE. Photoshop reads both, and a wrong RLE run length gives a file
            // that opens as noise — a failure far more expensive than the size saved.
            info.int16(0)
            info.bytes(data)
        }

        val infoBytes = info.toByteArray()
        // The layer *and* mask section wraps the layer info, and its length includes the padding.
        val padded = if (infoBytes.size % 2 == 0) infoBytes else infoBytes + byteArrayOf(0)
        writer.int32(padded.size + 8)
        writer.int32(padded.size)
        writer.bytes(padded)
        writer.int32(0) // global layer mask
    }

    /**
     * The layer's name as UTF-16.
     *
     * The Pascal string above is the original field and cannot hold Persian at all; every modern
     * reader takes the name from this block. Writing only the Pascal one is why layer names come
     * back as mojibake from tools that were never tested outside Latin.
     */
    private fun writeUnicodeName(writer: Writer, name: String) {
        val body = Writer(ByteArrayOutputStream())
        body.int32(name.length)
        for (char in name) body.int16(char.code)

        val bytes = body.toByteArray()
        writer.ascii("8BIM")
        writer.ascii("luni")
        writer.int32(bytes.size)
        writer.bytes(bytes)
        if (bytes.size % 2 != 0) writer.byte(0)
    }

    private fun writeComposite(writer: Writer, image: RasterImage) {
        writer.int16(0)
        writer.bytes(plane(image) { (it shr 16) and 0xFF })
        writer.bytes(plane(image) { (it shr 8) and 0xFF })
        writer.bytes(plane(image) { it and 0xFF })
        writer.bytes(plane(image) { (it ushr 24) and 0xFF })
    }

    private inline fun plane(image: RasterImage, channel: (Int) -> Int): ByteArray {
        val out = ByteArray(image.pixels.size)
        for (i in image.pixels.indices) out[i] = channel(image.pixels[i]).toByte()
        return out
    }

    /**
     * Photoshop's four-character blend keys.
     *
     * Exactly the strings the format uses, spaces included. A key of the wrong length shifts every
     * field after it, so a typo here does not produce a wrong blend — it produces an unreadable file.
     */
    private fun keyOf(mode: BlendMode): String = when (mode) {
        BlendMode.NORMAL -> "norm"
        BlendMode.DISSOLVE -> "diss"
        BlendMode.DARKEN -> "dark"
        BlendMode.MULTIPLY -> "mul "
        BlendMode.COLOR_BURN -> "idiv"
        BlendMode.LINEAR_BURN -> "lbrn"
        BlendMode.DARKER_COLOR -> "dkCl"
        BlendMode.LIGHTEN -> "lite"
        BlendMode.SCREEN -> "scrn"
        BlendMode.COLOR_DODGE -> "div "
        BlendMode.LINEAR_DODGE -> "lddg"
        BlendMode.LIGHTER_COLOR -> "lgCl"
        BlendMode.OVERLAY -> "over"
        BlendMode.SOFT_LIGHT -> "sLit"
        BlendMode.HARD_LIGHT -> "hLit"
        BlendMode.VIVID_LIGHT -> "vLit"
        BlendMode.LINEAR_LIGHT -> "lLit"
        BlendMode.PIN_LIGHT -> "pLit"
        BlendMode.HARD_MIX -> "hMix"
        BlendMode.DIFFERENCE -> "diff"
        BlendMode.EXCLUSION -> "smud"
        BlendMode.SUBTRACT -> "fsub"
        BlendMode.DIVIDE -> "fdiv"
        BlendMode.HUE -> "hue "
        BlendMode.SATURATION -> "sat "
        BlendMode.COLOR -> "colr"
        BlendMode.LUMINOSITY -> "lum "
    }

    /** Which layers of a document the writer can carry, and what it cannot. */
    fun describe(document: Document): List<String> = buildList {
        for (layer in document.walk()) {
            when (layer) {
                is Layer.Text -> add("«${layer.name}»: به‌صورت پیکسل نوشته می‌شود، نه متن قابل ویرایش")
                is Layer.AdjustmentLayer -> add("«${layer.name}»: تنظیم رنگ در پیکسل‌ها پخته می‌شود")
                is Layer.Instance -> add("«${layer.name}»: کپی زنده به لایهٔ معمولی تبدیل می‌شود")
                else -> Unit
            }
            if (layer.style.activeEffects.isNotEmpty()) {
                add("«${layer.name}»: افکت‌ها در پیکسل‌ها پخته می‌شوند، نه به‌صورت Layer Style")
            }
        }
    }

    private const val CHANNELS = 4
    private const val DEPTH = 8
    private const val RGB_MODE = 3
    private const val ALPHA_ID = -1
    private const val RESOLUTION_ID = 0x03ED
    private const val RESOLUTION_LENGTH = 16
    private const val DEFAULT_DPI = 72

    /** Big-endian throughout, which is what the format is. */
    private class Writer(private val out: ByteArrayOutputStream) {
        fun byte(value: Int) = out.write(value and 0xFF)

        fun int16(value: Int) {
            out.write((value shr 8) and 0xFF)
            out.write(value and 0xFF)
        }

        fun int32(value: Int) {
            out.write((value shr 24) and 0xFF)
            out.write((value shr 16) and 0xFF)
            out.write((value shr 8) and 0xFF)
            out.write(value and 0xFF)
        }

        fun ascii(value: String) {
            for (char in value) out.write(char.code and 0xFF)
        }

        fun bytes(value: ByteArray) = out.write(value)

        /**
         * Length-prefixed and padded to a multiple of *four*.
         *
         * Four, not two. The layer name is the one Pascal string in the format with that rule, and
         * getting it wrong shifts every additional-info block after it by two bytes — which shows up
         * as a signature read as "IMlu" instead of "8BIM", and every layer name lost.
         */
        fun pascalString(value: String) {
            val ascii = value.filter { it.code in 32..126 }.take(MAX_PASCAL)
            byte(ascii.length)
            ascii(ascii)
            var written = ascii.length + 1
            while (written % 4 != 0) {
                byte(0)
                written++
            }
        }

        fun toByteArray(): ByteArray = out.toByteArray()

        private companion object {
            const val MAX_PASCAL = 255
        }
    }
}
