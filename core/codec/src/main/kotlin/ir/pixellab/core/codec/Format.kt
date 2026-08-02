package ir.pixellab.core.codec

/**
 * How completely a format is handled.
 *
 * Stated per format rather than as one "supported" flag because the difference matters to the user
 * before they open a file, not after. A PSD that arrives as a flat picture is a very different
 * outcome from one that arrives as an editable stack, and an editor that does not say which it did
 * has quietly thrown away the user's work.
 */
enum class Support {
    /** Not handled at all. */
    NONE,

    /** Pixels only: the image arrives as a single layer. */
    FLAT,

    /** Layers, groups, masks and blend modes survive. */
    LAYERED,

    /** Layers plus their editable parameters — text, effects, smart objects. */
    EDITABLE,
    ;

    val isUsable: Boolean get() = this != NONE
}

/** Who does the work. Kept explicit because it decides what a build can promise on a given device. */
enum class Backend {
    /** Implemented here, in Kotlin, so behaviour is identical on every device. */
    OWN,

    /** Delegated to the operating system's decoders; availability varies by version. */
    PLATFORM,

    NOT_IMPLEMENTED,
}

/**
 * Every file format the editor knows about.
 *
 * The list is deliberately complete rather than restricted to what works today: a format present
 * with [Support.NONE] is a promise the interface can read and honour — it can grey out an export
 * option and say why, instead of offering it and failing after the user has waited.
 */
enum class Format(
    val label: String,
    val extensions: List<String>,
    val mime: String,
    val read: Support,
    val write: Support,
    val backend: Backend,
) {
    // ---- Photoshop's own -------------------------------------------------------------------
    PSD("Photoshop", listOf("psd"), "image/vnd.adobe.photoshop", Support.LAYERED, Support.LAYERED, Backend.OWN),

    /** The same format with 64-bit offsets, for documents past 30000 px or 2 GB. */
    PSB("Photoshop Large", listOf("psb"), "image/vnd.adobe.photoshop", Support.LAYERED, Support.LAYERED, Backend.OWN),

    // ---- Everyday raster -------------------------------------------------------------------
    JPEG("JPEG", listOf("jpg", "jpeg", "jpe"), "image/jpeg", Support.FLAT, Support.FLAT, Backend.PLATFORM),
    PNG("PNG", listOf("png"), "image/png", Support.FLAT, Support.FLAT, Backend.PLATFORM),
    WEBP("WebP", listOf("webp"), "image/webp", Support.FLAT, Support.FLAT, Backend.PLATFORM),
    GIF("GIF", listOf("gif"), "image/gif", Support.FLAT, Support.FLAT, Backend.PLATFORM),
    HEIF("HEIF", listOf("heif", "heic"), "image/heif", Support.FLAT, Support.FLAT, Backend.PLATFORM),
    BMP("BMP", listOf("bmp", "dib"), "image/bmp", Support.FLAT, Support.FLAT, Backend.OWN),
    ICO("Icon", listOf("ico"), "image/x-icon", Support.FLAT, Support.FLAT, Backend.OWN),
    TGA("Targa", listOf("tga", "icb", "vda", "vst"), "image/x-tga", Support.FLAT, Support.FLAT, Backend.OWN),
    TIFF("TIFF", listOf("tif", "tiff"), "image/tiff", Support.FLAT, Support.FLAT, Backend.OWN),

    // ---- High dynamic range ----------------------------------------------------------------
    HDR("Radiance HDR", listOf("hdr", "rgbe"), "image/vnd.radiance", Support.FLAT, Support.FLAT, Backend.OWN),
    EXR("OpenEXR", listOf("exr"), "image/x-exr", Support.NONE, Support.NONE, Backend.NOT_IMPLEMENTED),

    // ---- Camera raw ------------------------------------------------------------------------
    /**
     * The one raw format with a published specification; the rest are per-manufacturer variants of
     * TIFF with undocumented tags, which is why they are separated.
     */
    DNG("Adobe DNG", listOf("dng"), "image/x-adobe-dng", Support.FLAT, Support.NONE, Backend.PLATFORM),
    RAW_CANON("Canon Raw", listOf("cr2", "cr3", "crw"), "image/x-canon-cr2", Support.NONE, Support.NONE, Backend.NOT_IMPLEMENTED),
    RAW_NIKON("Nikon Raw", listOf("nef", "nrw"), "image/x-nikon-nef", Support.NONE, Support.NONE, Backend.NOT_IMPLEMENTED),
    RAW_SONY("Sony Raw", listOf("arw", "srf", "sr2"), "image/x-sony-arw", Support.NONE, Support.NONE, Backend.NOT_IMPLEMENTED),
    RAW_OTHER("Other Raw", listOf("raf", "orf", "rw2", "pef", "srw", "3fr"), "image/x-dcraw", Support.NONE, Support.NONE, Backend.NOT_IMPLEMENTED),

    /** Photoshop's own headerless dump; the reader needs dimensions supplied out of band. */
    PHOTOSHOP_RAW("Photoshop Raw", listOf("raw"), "application/octet-stream", Support.NONE, Support.FLAT, Backend.OWN),

    // ---- Vector and page -------------------------------------------------------------------
    PDF("PDF", listOf("pdf"), "application/pdf", Support.FLAT, Support.FLAT, Backend.PLATFORM),
    SVG("SVG", listOf("svg"), "image/svg+xml", Support.NONE, Support.NONE, Backend.NOT_IMPLEMENTED),
    AI("Illustrator", listOf("ai"), "application/postscript", Support.NONE, Support.NONE, Backend.NOT_IMPLEMENTED),
    EPS("EPS", listOf("eps"), "application/postscript", Support.NONE, Support.NONE, Backend.NOT_IMPLEMENTED),

    // ---- Other -----------------------------------------------------------------------------
    DDS("DirectDraw Surface", listOf("dds"), "image/vnd-ms.dds", Support.NONE, Support.NONE, Backend.NOT_IMPLEMENTED),
    PXR("Pixar", listOf("pxr"), "image/x-pxr", Support.NONE, Support.NONE, Backend.NOT_IMPLEMENTED),
    DICOM("DICOM", listOf("dcm", "dic"), "application/dicom", Support.NONE, Support.NONE, Backend.NOT_IMPLEMENTED),

    // ---- Sequences -------------------------------------------------------------------------
    /** Not a file format: a numbered set written by the exporter. */
    PNG_SEQUENCE("PNG Sequence", emptyList(), "image/png", Support.NONE, Support.NONE, Backend.NOT_IMPLEMENTED),
    JPEG_SEQUENCE("JPEG Sequence", emptyList(), "image/jpeg", Support.NONE, Support.NONE, Backend.NOT_IMPLEMENTED),

    // ---- Moving image ----------------------------------------------------------------------
    /**
     * Video is out of scope for this app by an explicit decision, not an oversight — it was set
     * aside for a separate one. The formats are listed so the interface can say so rather than
     * appear to have forgotten them.
     */
    MP4("MP4", listOf("mp4", "m4v"), "video/mp4", Support.NONE, Support.NONE, Backend.NOT_IMPLEMENTED),
    MOV("QuickTime", listOf("mov"), "video/quicktime", Support.NONE, Support.NONE, Backend.NOT_IMPLEMENTED),
    AVI("AVI", listOf("avi"), "video/x-msvideo", Support.NONE, Support.NONE, Backend.NOT_IMPLEMENTED),
    ANIMATED_GIF("Animated GIF", emptyList(), "image/gif", Support.NONE, Support.NONE, Backend.NOT_IMPLEMENTED),
    ;

    val canRead: Boolean get() = read.isUsable
    val canWrite: Boolean get() = write.isUsable

    /** True when the file is a moving image, which this app deliberately does not handle. */
    val isVideo: Boolean get() = this == MP4 || this == MOV || this == AVI || this == ANIMATED_GIF

    companion object {
        /**
         * Identifies a file from its leading bytes.
         *
         * Content, not extension: files arrive from a share sheet with names like `image` and no
         * suffix at all, and a mis-named PSD opened as a JPEG is a confusing failure rather than an
         * informative one. [fromExtension] stays available for the save dialog, where there is no
         * content yet.
         */
        fun detect(header: ByteArray): Format? {
            fun at(offset: Int, vararg bytes: Int): Boolean {
                if (header.size < offset + bytes.size) return false
                return bytes.withIndex().all { (i, b) -> header[offset + i].toInt() and 0xFF == b }
            }

            fun ascii(offset: Int, text: String): Boolean {
                if (header.size < offset + text.length) return false
                return text.indices.all { header[offset + it].toInt().toChar() == text[it] }
            }

            return when {
                // Photoshop's version field is what separates PSD from PSB, not the extension.
                ascii(0, "8BPS") -> if (at(4, 0x00, 0x02)) PSB else PSD

                at(0, 0xFF, 0xD8, 0xFF) -> JPEG
                at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> PNG
                ascii(0, "GIF87a") || ascii(0, "GIF89a") -> GIF
                ascii(0, "RIFF") && ascii(8, "WEBP") -> WEBP
                ascii(4, "ftyp") && (ascii(8, "heic") || ascii(8, "heix") || ascii(8, "mif1")) -> HEIF
                ascii(4, "ftyp") -> MP4
                ascii(4, "moov") || ascii(4, "mdat") || ascii(4, "free") -> MOV
                ascii(0, "RIFF") && ascii(8, "AVI ") -> AVI

                ascii(0, "BM") -> BMP
                at(0, 0x00, 0x00, 0x01, 0x00) -> ICO
                ascii(0, "#?RADIANCE") || ascii(0, "#?RGBE") -> HDR
                at(0, 0x76, 0x2F, 0x31, 0x01) -> EXR
                ascii(0, "DDS ") -> DDS
                ascii(0, "%PDF") -> PDF
                ascii(0, "%!PS") -> EPS
                ascii(0, "<svg") || ascii(0, "<?xml") -> SVG
                ascii(128, "DICM") -> DICOM

                // TIFF and its raw descendants share a byte order mark plus 42; DNG is
                // distinguished by a tag the others do not carry, so a plain TIFF check would
                // claim every camera file.
                at(0, 0x49, 0x49, 0x2A, 0x00) || at(0, 0x4D, 0x4D, 0x00, 0x2A) -> TIFF
                at(0, 0x49, 0x49, 0x2B, 0x00) || at(0, 0x4D, 0x4D, 0x00, 0x2B) -> TIFF
                ascii(0, "FUJIFILMCCD-RAW") -> RAW_OTHER

                else -> null
            }
        }

        fun fromExtension(name: String): Format? {
            val extension = name.substringAfterLast('.', "").lowercase()
            if (extension.isEmpty()) return null
            return entries.firstOrNull { extension in it.extensions }
        }

        /** Formats offered in the open dialog. */
        val readable: List<Format> get() = entries.filter { it.canRead }

        /** Formats offered in the export dialog. */
        val writable: List<Format> get() = entries.filter { it.canWrite }
    }
}
