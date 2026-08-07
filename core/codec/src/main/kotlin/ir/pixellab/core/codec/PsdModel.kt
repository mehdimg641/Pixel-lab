package ir.pixellab.core.codec

import ir.pixellab.core.model.BlendMode

/** Photoshop's colour modes, in the order the file format numbers them. */
enum class PsdColorMode(val code: Int, val channels: Int) {
    BITMAP(0, 1),
    GRAYSCALE(1, 1),
    INDEXED(2, 1),
    RGB(3, 3),
    CMYK(4, 4),
    MULTICHANNEL(7, 1),
    DUOTONE(8, 1),
    LAB(9, 3),
    ;

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: RGB
    }
}

/**
 * Which channel a block of pixels belongs to.
 *
 * The negative identifiers are not a quirk to work around: -1 is the layer's transparency and -2
 * its mask, and confusing the two puts a layer's mask into its alpha, which hides exactly the parts
 * the mask was meant to reveal.
 */
enum class PsdChannel(val id: Int) {
    RED(0), GREEN(1), BLUE(2), ALPHA(-1), USER_MASK(-2), REAL_MASK(-3);

    companion object {
        fun of(id: Int) = entries.firstOrNull { it.id == id }
    }
}

enum class PsdCompression(val code: Int) {
    RAW(0), RLE(1), ZIP(2), ZIP_PREDICTED(3);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: RAW
    }
}

/** A layer's rectangle in document coordinates. Photoshop stores it top, left, bottom, right. */
data class PsdBounds(val top: Int, val left: Int, val bottom: Int, val right: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0
}

/** One channel's pixel data, already decompressed to one byte or two per sample. */
data class PsdChannelData(val channel: PsdChannel, val bounds: PsdBounds, val samples: ByteArray) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/**
 * A layer as the file describes it.
 *
 * Deliberately a faithful record of what was read rather than a translation: the mapping into the
 * editor's own model is a separate step, so an import that loses something loses it visibly at a
 * point that can be tested, instead of inside the parser.
 */
data class PsdLayer(
    val name: String,
    val bounds: PsdBounds,
    val opacity: Float,
    val fillOpacity: Float,
    val blendMode: BlendMode,
    val blendKey: String,
    val visible: Boolean,
    val clipping: Boolean,
    val locked: Boolean,
    val channels: List<PsdChannelData>,
    /** Section divider type: 0 normal, 1 open group, 2 closed group, 3 the group's closing marker. */
    val sectionType: Int,
    /** Raw additional-info blocks by their four-character key, for what this build cannot yet map. */
    val extras: Map<String, ByteArray>,
    val effects: List<PsdEffect>,
    val text: PsdText?,
) {
    val isGroupStart: Boolean get() = sectionType == 1 || sectionType == 2
    val isGroupEnd: Boolean get() = sectionType == 3
    val isGroupOpen: Boolean get() = sectionType == 1
}

/** A layer effect, keyed by Photoshop's own four-character name. */
data class PsdEffect(
    val key: String,
    val enabled: Boolean,
    val values: Map<String, PsdValue>,
)

/** Text on a layer: the string plus the transform Photoshop applies to it. */
data class PsdText(
    val text: String,
    /** Affine transform, in the order Photoshop stores it: xx, xy, yx, yy, tx, ty. */
    val transform: DoubleArray,
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/**
 * A value from Photoshop's descriptor format.
 *
 * Descriptors are how everything expressive is stored — every effect parameter, every text run,
 * every smart-object reference — and they are self-describing, so reading them generically is what
 * makes it possible to recover a bevel's depth without hand-coding each effect's byte layout.
 */
sealed interface PsdValue {
    data class Number(val value: Double) : PsdValue

    /** A number with a unit; Photoshop distinguishes pixels, percent, degrees and points. */
    data class Unit(val value: Double, val unit: String) : PsdValue

    data class Text(val value: String) : PsdValue

    data class Bool(val value: Boolean) : PsdValue

    /** An enumerated choice: its type and the constant chosen. */
    data class Enumerated(val type: String, val value: String) : PsdValue

    data class Integer(val value: Int) : PsdValue

    data class Descriptor(val classId: String, val fields: Map<String, PsdValue>) : PsdValue

    data class Items(val values: List<PsdValue>) : PsdValue

    data object Unknown : PsdValue
}

/** The whole file. */
data class PsdDocument(
    val width: Int,
    val height: Int,
    val channels: Int,
    val depth: Int,
    val colorMode: PsdColorMode,
    val large: Boolean,
    val layers: List<PsdLayer>,
    /** The flattened composite Photoshop stores for programs that cannot read layers. */
    val composite: List<PsdChannelData>,
    val resolutionDpi: Float,
    /** Anything the reader recognised but this build does not yet act on. */
    val warnings: List<String>,
)
