package ir.pixellab.core.codec

/**
 * What this build can actually do with a file.
 *
 * The registry is separate from [Format] because the answer depends on the build: `core:codec`
 * implements the formats no platform provides, and the Android layer registers the ones the system
 * decodes. Asking the registry rather than the enum is what lets the interface offer exactly what
 * will work on the device in hand, instead of a fixed list that fails on some of them.
 */
object Codecs {

    private val decoders = LinkedHashMap<Format, ImageDecoder>()
    private val encoders = LinkedHashMap<Format, ImageEncoder>()

    init {
        register(TgaCodec)
        register(BmpCodec)
    }

    fun registerDecoder(decoder: ImageDecoder) {
        decoders[decoder.format] = decoder
    }

    fun registerEncoder(encoder: ImageEncoder) {
        encoders[encoder.format] = encoder
    }

    /**
     * Registers a codec that both reads and writes.
     *
     * Named separately from the two halves rather than overloaded: generic bounds erase to the same
     * JVM signature, and the platform decoders on Android register only one side.
     */
    fun <T> register(codec: T) where T : ImageDecoder, T : ImageEncoder {
        decoders[codec.format] = codec
        encoders[codec.format] = codec
    }

    fun decoderFor(format: Format): ImageDecoder? = decoders[format]

    fun encoderFor(format: Format): ImageEncoder? = encoders[format]

    /** Formats this build can open right now, as opposed to ones it knows the name of. */
    val readable: Set<Format> get() = decoders.keys + Format.PSD + Format.PSB

    val writable: Set<Format> get() = encoders.keys

    /**
     * Decodes by sniffing the content.
     *
     * @throws CodecException when the format is recognised but unsupported, so the caller can say
     *   *which* format it could not open rather than "unsupported file".
     */
    fun decode(bytes: ByteArray): RasterImage {
        val format = Format.detect(bytes) ?: throw CodecException("unrecognised file")
        val decoder = decoders[format] ?: throw CodecException("${format.label} cannot be opened in this build")
        return decoder.decode(bytes)
    }

    /**
     * Explains what would happen to a file, before any of it is read.
     *
     * A user choosing between two exports needs to know that one keeps their layers and the other
     * flattens them. Discovering that afterwards is discovering it too late.
     */
    fun describe(format: Format): String = buildString {
        append(format.label)
        append(": ")
        append(
            when {
                format.isVideo ->
                    "ویدیو در این اپ پشتیبانی نمی‌شود"
                format.read == Support.NONE && format.write == Support.NONE ->
                    "هنوز پشتیبانی نمی‌شود"
                else -> buildList {
                    when (format.read) {
                        Support.EDITABLE -> add("خواندن کامل")
                        Support.LAYERED -> add("خواندن با لایه‌ها")
                        Support.FLAT -> add("خواندن تخت")
                        Support.NONE -> Unit
                    }
                    when (format.write) {
                        Support.EDITABLE -> add("نوشتن کامل")
                        Support.LAYERED -> add("نوشتن با لایه‌ها")
                        Support.FLAT -> add("نوشتن تخت")
                        Support.NONE -> Unit
                    }
                }.joinToString("، ")
            },
        )
    }
}
