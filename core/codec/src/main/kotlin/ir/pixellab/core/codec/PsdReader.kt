package ir.pixellab.core.codec

import ir.pixellab.core.model.BlendMode
import java.util.zip.InflaterInputStream

class PsdException(message: String) : Exception(message)

/**
 * Reads Photoshop documents.
 *
 * PSD and PSB are the same format: PSB raises the version field and widens four length fields to
 * eight bytes. Treating them as one reader with a width flag is what stops a large document from
 * needing a second, drifting implementation.
 *
 * Two decisions shape everything here.
 *
 * **Pixel data is optional.** Layer names, blend modes, effect parameters and text sit in a few
 * hundred kilobytes near the front; the pixels are almost the whole file. A style library import
 * wants the former and not the latter, and reading a 100 MB file to answer "what is in this?" is
 * the difference between a picker that opens instantly and one that does not.
 *
 * **An unrecognised block is skipped, never fatal.** Photoshop writes blocks from features that did
 * not exist when any given reader was written, and every section states its own length precisely so
 * that this is possible. Refusing a file because one block is unfamiliar would reject most real
 * documents.
 */
object PsdReader {

    /** @param pixels false to read structure only, which is what a library preview needs. */
    fun read(bytes: ByteArray, pixels: Boolean = true): PsdDocument {
        val reader = ByteReader(bytes)
        val warnings = ArrayList<String>()

        if (reader.ascii(4) != SIGNATURE) throw PsdException("not a Photoshop document")
        val version = reader.u16()
        val large = when (version) {
            1 -> false
            2 -> true
            else -> throw PsdException("unsupported Photoshop version $version")
        }
        reader.skip(6) // reserved, always zero

        val channels = reader.u16()
        val height = reader.i32()
        val width = reader.i32()
        val depth = reader.u16()
        val colorMode = PsdColorMode.of(reader.u16())

        if (depth !in setOf(1, 8, 16, 32)) throw PsdException("unsupported bit depth $depth")

        // Colour mode data: the palette for indexed images, the curve for duotone.
        reader.section(reader.i32()) { }

        val resolution = readImageResources(reader, warnings)
        val layers = readLayerSection(reader, large, depth, pixels, warnings)

        val composite = if (pixels) {
            readComposite(reader, width, height, channels, depth, warnings)
        } else {
            emptyList()
        }

        return PsdDocument(
            width = width,
            height = height,
            channels = channels,
            depth = depth,
            colorMode = colorMode,
            large = large,
            layers = layers,
            composite = composite,
            resolutionDpi = resolution,
            warnings = warnings,
        )
    }

    /** Reads only the header, for a file picker that needs dimensions without the cost. */
    fun probe(bytes: ByteArray): PsdDocument = read(bytes.copyOf(bytes.size.coerceAtMost(HEADER_PROBE)), pixels = false)

    // ---- image resources --------------------------------------------------------------------

    private fun readImageResources(reader: ByteReader, warnings: MutableList<String>): Float {
        var dpi = DEFAULT_DPI
        val length = reader.i32()
        reader.section(length) { section ->
            val end = section.position + length
            while (section.position + 12 <= end) {
                val signature = section.ascii(4)
                if (signature != RESOURCE_SIGNATURE) {
                    warnings += "unexpected resource signature '$signature'"
                    return@section
                }
                val id = section.u16()
                section.pascalString(2)
                val size = section.i32()
                section.section(size) { body ->
                    // 0x03ED is the resolution block; its horizontal figure is 16.16 fixed point.
                    if (id == RESOURCE_RESOLUTION && body.hasRemaining(4)) {
                        dpi = body.i32() / 65536f
                    }
                }
                // Resource bodies are padded to an even length, and the pad is outside the size.
                section.align(2)
            }
        }
        return dpi
    }

    // ---- layers -----------------------------------------------------------------------------

    private fun readLayerSection(
        reader: ByteReader,
        large: Boolean,
        depth: Int,
        pixels: Boolean,
        warnings: MutableList<String>,
    ): List<PsdLayer> {
        val sectionLength = if (large) reader.i64() else reader.i32().toLong()
        if (sectionLength <= 0) return emptyList()

        return reader.section(sectionLength.toInt()) { section ->
            val sectionEnd = section.position + sectionLength.toInt()
            val infoLength = if (large) section.i64() else section.u32()
            if (infoLength > 0) {
                return@section section.section(infoLength.toInt()) { info ->
                    readLayerRecords(info, large, depth, pixels, warnings)
                }
            }

            // A deep-colour document leaves the normal layer section empty and puts the whole
            // structure in a global Lr16 or Lr32 block instead. Stopping at the empty length is
            // what makes a 16-bit file look as though it were flattened.
            section.section(section.i32()) { } // global layer mask info
            var layers = emptyList<PsdLayer>()
            while (section.position + 12 <= sectionEnd) {
                val signature = section.ascii(4)
                if (signature != BLEND_SIGNATURE && signature != EXTRA_SIGNATURE) break
                val key = section.ascii(4)
                val length = readInfoLength(section, key, large)
                if (length <= 0 || section.position + length > sectionEnd) break
                section.section(length.toInt()) { body ->
                    if (key in DEEP_LAYER_KEYS) {
                        layers = readLayerRecords(body, large, depth, pixels, warnings)
                    }
                }
                section.align(2)
            }
            layers
        }
    }

    private fun readLayerRecords(
        info: ByteReader,
        large: Boolean,
        depth: Int,
        pixels: Boolean,
        warnings: MutableList<String>,
    ): List<PsdLayer> {
        // A negative count means the first alpha channel holds transparency rather than a spot
        // channel. The magnitude is still the layer count.
        val count = kotlin.math.abs(info.i16())
        if (count == 0) return emptyList()

        val records = ArrayList<LayerRecord>(count)
        repeat(count) { records += readLayerRecord(info, large, warnings) }

        return records.map { record ->
            val channels = if (pixels) {
                readChannelData(info, record, depth, warnings)
            } else {
                // Even when skipping, the declared lengths must be consumed or the next layer's
                // data would be read from the middle of this one's.
                record.channelLengths.forEach { info.skip(it.second.toInt()) }
                emptyList()
            }
            record.toLayer(channels)
        }
    }

    private class LayerRecord(
        val bounds: PsdBounds,
        val channelLengths: List<Pair<Int, Long>>,
        val blendKey: String,
        val opacity: Float,
        val clipping: Boolean,
        val visible: Boolean,
        val locked: Boolean,
    ) {
        var name: String = ""
        var unicodeName: String? = null
        var fillOpacity: Float = 1f
        var sectionType: Int = 0
        var extras: Map<String, ByteArray> = emptyMap()
        var effects: List<PsdEffect> = emptyList()
        var text: PsdText? = null

        fun toLayer(channels: List<PsdChannelData>) = PsdLayer(
            // The Unicode name is authoritative: the Pascal one is truncated to 31 bytes in the
            // system encoding, which mangles every Persian layer name in the reference files.
            name = unicodeName?.takeIf { it.isNotBlank() } ?: name,
            bounds = bounds,
            opacity = opacity,
            fillOpacity = fillOpacity,
            blendMode = blendModeOf(blendKey),
            blendKey = blendKey,
            visible = visible,
            clipping = clipping,
            locked = locked,
            channels = channels,
            sectionType = sectionType,
            extras = extras,
            effects = effects,
            text = text,
        )
    }

    private fun readLayerRecord(reader: ByteReader, large: Boolean, warnings: MutableList<String>): LayerRecord {
        val bounds = PsdBounds(reader.i32(), reader.i32(), reader.i32(), reader.i32())
        val channelCount = reader.u16()
        val lengths = ArrayList<Pair<Int, Long>>(channelCount)
        repeat(channelCount) {
            val id = reader.i16()
            val length = if (large) reader.i64() else reader.u32()
            lengths += id to length
        }

        val signature = reader.ascii(4)
        if (signature != BLEND_SIGNATURE) throw PsdException("bad layer signature '$signature'")
        val blendKey = reader.ascii(4)
        val opacity = reader.u8() / 255f
        val clipping = reader.u8() != 0
        val flags = reader.u8()
        reader.skip(1) // filler

        val record = LayerRecord(
            bounds = bounds,
            channelLengths = lengths,
            blendKey = blendKey,
            opacity = opacity,
            clipping = clipping,
            // Bit 1 is *hidden*, so a set bit means invisible — inverting it is a mistake that
            // makes every hidden layer in an imported file appear.
            visible = (flags and 0x02) == 0,
            locked = (flags and 0x01) != 0,
        )

        val extraLength = reader.i32()
        reader.section(extraLength) { extra ->
            val end = extra.position + extraLength
            extra.section(extra.i32()) { } // layer mask data
            extra.section(extra.i32()) { } // blending ranges
            record.name = extra.pascalString(4)
            readAdditionalInfo(extra, end, record, large, warnings)
        }
        return record
    }

    /**
     * Walks the additional-information blocks that follow a layer record.
     *
     * This is where everything worth importing lives: the real name, the fill opacity, whether the
     * layer is a group, its effects and its text. The list is open-ended, which is why the loop
     * skips by declared length rather than by anything it recognises.
     */
    /**
     * Length of an additional-information block.
     *
     * A short list of keys widens to 64 bits — but only in a PSB. Applying the widening to a plain
     * PSD reads the next block's first four bytes as part of the length, which came out as a
     * 68-petabyte `Lr16` section and made a whole 16-bit document look as though it had no layers
     * at all.
     */
    private fun readInfoLength(reader: ByteReader, key: String, large: Boolean): Long =
        if (large && key in LONG_LENGTH_KEYS) reader.i64() else reader.u32()

    private fun readAdditionalInfo(
        reader: ByteReader,
        end: Int,
        record: LayerRecord,
        large: Boolean,
        warnings: MutableList<String>,
    ) {
        val extras = LinkedHashMap<String, ByteArray>()
        while (reader.position + 12 <= end) {
            val signature = reader.ascii(4)
            if (signature != BLEND_SIGNATURE && signature != EXTRA_SIGNATURE) {
                warnings += "unexpected additional-info signature '$signature'"
                return
            }
            val key = reader.ascii(4)
            val length = readInfoLength(reader, key, large)
            if (length < 0 || reader.position + length > end) return

            reader.section(length.toInt()) { body ->
                when (key) {
                    "luni" -> record.unicodeName = body.unicodeString()
                    "lsct", "lsdk" -> record.sectionType = body.i32()
                    "iOpa" -> record.fillOpacity = body.u8() / 255f
                    // Photoshop writes *both* blocks: lrFX for readers older than CS, lfx2 with the
                    // real values. Handling them in one branch lets whichever comes second win, and
                    // when that is the legacy one every effect in the file silently disappears.
                    "lfx2" -> record.effects = readEffects(body, warnings)
                    "lrFX" -> if (record.effects.isEmpty()) {
                        warnings += "layer '${record.unicodeName ?: record.name}' has only the legacy lrFX block"
                    }
                    "TySh" -> record.text = readTypeTool(body, warnings)
                    else -> extras[key] = body.bytes(length.toInt())
                }
            }
            reader.align(2)
        }
        record.extras = extras
    }

    // ---- effects and descriptors -------------------------------------------------------------

    private fun readEffects(reader: ByteReader, warnings: MutableList<String>): List<PsdEffect> {
        reader.i32() // object-effects version
        val descriptorVersion = reader.i32()
        if (descriptorVersion != DESCRIPTOR_VERSION) {
            warnings += "unfamiliar descriptor version $descriptorVersion"
            return emptyList()
        }
        val root = readDescriptor(reader) as? PsdValue.Descriptor ?: return emptyList()

        return root.fields.mapNotNull { (name, value) ->
            val descriptor = value as? PsdValue.Descriptor ?: return@mapNotNull null
            val enabled = (descriptor.fields["enab"] as? PsdValue.Bool)?.value ?: true
            PsdEffect(key = name, enabled = enabled, values = descriptor.fields)
        }
    }

    /**
     * Reads Photoshop's descriptor structure.
     *
     * Self-describing and recursive: every value announces its own four-character type. Reading it
     * generically means a bevel's depth, a gradient's stops and a smart object's reference all
     * arrive without a hand-written layout for each — and a value type this build has never seen
     * ends the descriptor cleanly instead of corrupting the layer after it.
     */
    fun readDescriptor(reader: ByteReader): PsdValue {
        reader.unicodeString() // the descriptor's own name, which Photoshop leaves empty
        val classId = readKey(reader)
        val count = reader.i32()
        if (count < 0 || count > MAX_DESCRIPTOR_FIELDS) return PsdValue.Unknown

        val fields = LinkedHashMap<String, PsdValue>(count)
        repeat(count) {
            if (!reader.hasRemaining(8)) return PsdValue.Descriptor(classId, fields)
            val key = readKey(reader)
            fields[key] = readValue(reader) ?: return PsdValue.Descriptor(classId, fields)
        }
        return PsdValue.Descriptor(classId, fields)
    }

    /** A key is four bytes, unless a length prefix says otherwise — zero means four. */
    private fun readKey(reader: ByteReader): String {
        val length = reader.i32()
        return reader.ascii(if (length == 0) 4 else length)
    }

    private fun readValue(reader: ByteReader): PsdValue? = when (reader.ascii(4)) {
        "doub" -> PsdValue.Number(reader.f64())
        "UntF" -> {
            val unit = reader.ascii(4)
            PsdValue.Unit(reader.f64(), unit)
        }
        "TEXT" -> PsdValue.Text(reader.unicodeString())
        "bool" -> PsdValue.Bool(reader.u8() != 0)
        "long" -> PsdValue.Integer(reader.i32())
        "enum" -> {
            val type = readKey(reader)
            PsdValue.Enumerated(type, readKey(reader))
        }
        "Objc", "GlbO" -> readDescriptor(reader)
        "VlLs" -> {
            val count = reader.i32()
            if (count < 0 || count > MAX_DESCRIPTOR_FIELDS) {
                PsdValue.Unknown
            } else {
                PsdValue.Items((0 until count).mapNotNull { readValue(reader) })
            }
        }
        "tdta" -> {
            reader.skip(reader.i32())
            PsdValue.Unknown
        }
        "Clss", "type", "GlbC" -> {
            reader.unicodeString()
            readKey(reader)
            PsdValue.Unknown
        }
        "alis" -> {
            reader.skip(reader.i32())
            PsdValue.Unknown
        }
        // Anything else has an unknown length, so the descriptor has to stop here rather than
        // guess and desynchronise everything after it.
        else -> null
    }

    private fun readTypeTool(reader: ByteReader, warnings: MutableList<String>): PsdText? {
        reader.u16() // version
        val transform = DoubleArray(6) { reader.f64() }
        reader.u16() // text version
        val descriptorVersion = reader.i32()
        if (descriptorVersion != DESCRIPTOR_VERSION) {
            warnings += "unfamiliar text descriptor version $descriptorVersion"
            return null
        }
        val descriptor = readDescriptor(reader) as? PsdValue.Descriptor ?: return null
        val text = (descriptor.fields["Txt "] as? PsdValue.Text)?.value ?: return null
        return PsdText(text, transform)
    }

    // ---- pixels -----------------------------------------------------------------------------

    private fun readChannelData(
        reader: ByteReader,
        record: LayerRecord,
        depth: Int,
        warnings: MutableList<String>,
    ): List<PsdChannelData> {
        val out = ArrayList<PsdChannelData>(record.channelLengths.size)
        for ((id, length) in record.channelLengths) {
            val channel = PsdChannel.of(id)
            reader.section(length.toInt()) { body ->
                if (channel == null || record.bounds.isEmpty || length < 2) return@section
                val compression = PsdCompression.of(body.u16())
                val samples = decompress(
                    body, compression, record.bounds.width, record.bounds.height, depth, warnings,
                )
                if (samples != null) out += PsdChannelData(channel, record.bounds, samples)
            }
        }
        return out
    }

    private fun readComposite(
        reader: ByteReader,
        width: Int,
        height: Int,
        channels: Int,
        depth: Int,
        warnings: MutableList<String>,
    ): List<PsdChannelData> {
        if (!reader.hasRemaining(2)) return emptyList()
        val compression = PsdCompression.of(reader.u16())
        val bounds = PsdBounds(0, 0, height, width)

        // The composite stores every channel's row counts up front, then every channel's data —
        // not interleaved. Decoding channel by channel with a shared cursor would read the second
        // channel's rows out of the first channel's table.
        val counts = if (compression == PsdCompression.RLE) PackBits.rowCounts(reader, height * channels) else null

        val out = ArrayList<PsdChannelData>(channels)
        for (index in 0 until channels) {
            val samples = when (compression) {
                PsdCompression.RAW -> reader.bytes(width * height * (depth / 8))
                PsdCompression.RLE -> {
                    val rows = counts ?: return out
                    PackBits.decode(reader, rows, index * height, height, width * (depth / 8))
                }
                else -> {
                    warnings += "composite uses ${compression.name}, which is read per layer only"
                    return out
                }
            }
            out += PsdChannelData(PsdChannel.of(index) ?: PsdChannel.ALPHA, bounds, samples)
        }
        return out
    }

    private fun decompress(
        reader: ByteReader,
        compression: PsdCompression,
        width: Int,
        height: Int,
        depth: Int,
        warnings: MutableList<String>,
    ): ByteArray? {
        val bytesPerRow = width * (depth / 8)
        return when (compression) {
            PsdCompression.RAW -> reader.bytes(bytesPerRow * height)
            PsdCompression.RLE -> {
                val counts = PackBits.rowCounts(reader, height)
                PackBits.decode(reader, counts, 0, height, bytesPerRow)
            }
            PsdCompression.ZIP -> inflate(reader.bytes(reader.remaining), bytesPerRow * height)
            PsdCompression.ZIP_PREDICTED -> {
                val flat = inflate(reader.bytes(reader.remaining), bytesPerRow * height) ?: return null
                undoPrediction(flat, width, height, depth)
                flat
            }
        }.also {
            if (it == null) warnings += "could not decompress a ${compression.name} channel"
        }
    }


    private fun inflate(compressed: ByteArray, expected: Int): ByteArray? = try {
        val out = ByteArray(expected)
        InflaterInputStream(compressed.inputStream()).use { stream ->
            var read = 0
            while (read < expected) {
                val n = stream.read(out, read, expected - read)
                if (n <= 0) break
                read += n
            }
        }
        out
    } catch (e: java.util.zip.ZipException) {
        null
    } catch (e: java.io.IOException) {
        null
    }

    /**
     * Reverses the horizontal delta ZIP-with-prediction applies before compressing.
     *
     * Each sample is stored as its difference from the one to its left, which compresses far better
     * and is meaningless until undone. At 16 bits the delta is applied per 16-bit sample, not per
     * byte — doing it bytewise produces an image that is almost right, which is worse than one that
     * is obviously wrong.
     */
    private fun undoPrediction(data: ByteArray, width: Int, height: Int, depth: Int) {
        when (depth) {
            8 -> for (row in 0 until height) {
                val base = row * width
                for (x in 1 until width) {
                    data[base + x] = (data[base + x] + data[base + x - 1]).toByte()
                }
            }
            16 -> for (row in 0 until height) {
                val base = row * width * 2
                for (x in 1 until width) {
                    val here = base + x * 2
                    val previous = here - 2
                    val sum = ((data[here].toInt() and 0xFF shl 8) or (data[here + 1].toInt() and 0xFF)) +
                        ((data[previous].toInt() and 0xFF shl 8) or (data[previous + 1].toInt() and 0xFF))
                    data[here] = (sum ushr 8).toByte()
                    data[here + 1] = sum.toByte()
                }
            }
            else -> Unit
        }
    }

    // ---- constants ---------------------------------------------------------------------------

    /** Photoshop's blend keys, four characters each and space-padded. */
    fun blendModeOf(key: String): BlendMode = when (key.trim()) {
        "norm" -> BlendMode.NORMAL
        "diss" -> BlendMode.DISSOLVE
        "dark" -> BlendMode.DARKEN
        "mul" -> BlendMode.MULTIPLY
        "idiv" -> BlendMode.COLOR_BURN
        "lbrn" -> BlendMode.LINEAR_BURN
        "dkCl" -> BlendMode.DARKER_COLOR
        "lite" -> BlendMode.LIGHTEN
        "scrn" -> BlendMode.SCREEN
        "div" -> BlendMode.COLOR_DODGE
        "lddg" -> BlendMode.LINEAR_DODGE
        "lgCl" -> BlendMode.LIGHTER_COLOR
        "over" -> BlendMode.OVERLAY
        "sLit" -> BlendMode.SOFT_LIGHT
        "hLit" -> BlendMode.HARD_LIGHT
        "vLit" -> BlendMode.VIVID_LIGHT
        "lLit" -> BlendMode.LINEAR_LIGHT
        "pLit" -> BlendMode.PIN_LIGHT
        "hMix" -> BlendMode.HARD_MIX
        "diff" -> BlendMode.DIFFERENCE
        "smud" -> BlendMode.EXCLUSION
        "fsub" -> BlendMode.SUBTRACT
        "fdiv" -> BlendMode.DIVIDE
        "hue" -> BlendMode.HUE
        "sat" -> BlendMode.SATURATION
        "colr" -> BlendMode.COLOR
        "lum" -> BlendMode.LUMINOSITY
        // "pass" is a group's pass-through, which the model expresses on the group itself.
        else -> BlendMode.NORMAL
    }

    private const val SIGNATURE = "8BPS"
    private const val BLEND_SIGNATURE = "8BIM"
    private const val EXTRA_SIGNATURE = "8B64"
    private const val RESOURCE_SIGNATURE = "8BIM"
    private const val RESOURCE_RESOLUTION = 0x03ED
    private const val DESCRIPTOR_VERSION = 16
    private const val DEFAULT_DPI = 72f

    /** Enough for the header, the resources and the start of the layer table. */
    private const val HEADER_PROBE = 1 shl 20

    /** A count beyond this is a misread cursor, not a real descriptor. */
    private const val MAX_DESCRIPTOR_FIELDS = 4096

    /** Global blocks that carry the layer structure of a deep-colour document. */
    private val DEEP_LAYER_KEYS = setOf("Lr16", "Lr32", "Layr")

    /** Keys whose length field widens to 64 bits — in a PSB only. */
    private val LONG_LENGTH_KEYS = setOf("LMsk", "Lr16", "Lr32", "Layr", "Mt16", "Mt32", "Mtrn", "Alph", "FMsk", "lnk2", "FEid", "FXid", "PxSD")
}
