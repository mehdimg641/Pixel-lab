package ir.pixellab.core.editor

import ir.pixellab.core.model.Adjustment
import ir.pixellab.core.model.Document
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId
import kotlinx.serialization.Serializable

/**
 * A Look — the colour treatment of one picture, saved so it can be put on the next twenty.
 *
 * Every reference app has this and calls it something different (Preset, Filter, Recipe, Look), and
 * in all of them it is the single feature that turns an editor into something someone uses for a
 * *set* of photographs rather than for one. Editing thirty pictures from one shoot by dragging the
 * same six sliders thirty times is the work this removes.
 *
 * **A Look is the adjustments and nothing else.** Not the layers, not the crop, not the text. That
 * sounds like an arbitrary line and it is the only one that works: a Look carrying layers would
 * replace the photograph it was applied to, and a Look carrying a crop would impose one picture's
 * framing on another's composition. What travels between two photographs is the grade.
 */
@Serializable
data class Look(
    val name: String,
    /**
     * A stable ASCII handle, separate from the display name — the same split [StylePreset] makes and
     * for the same reason: the name is Persian and meant to be read, this is meant to be written
     * into a file name and into a document that refers to it, and neither can be derived from the
     * other.
     */
    val id: String,
    /**
     * In the order they are stacked, bottom first.
     *
     * The order is not decoration. Adjustments do not commute — a curve after a saturation boost is
     * a different picture from a curve before it — so a Look that lost the order would apply a grade
     * nobody had ever seen, least of all the person who saved it.
     */
    val adjustments: List<Entry> = emptyList(),
) {
    @Serializable
    data class Entry(val name: String, val adjustment: Adjustment)

    val isEmpty: Boolean get() = adjustments.isEmpty()

    companion object {

        /**
         * Captures the adjustments already on a document.
         *
         * Nested adjustments inside groups are taken too, flattened into the stack: a user who put
         * their grade in a folder does not expect "save this look" to save half of it. What is lost
         * is which folder they were in, which is a property of the document rather than of the
         * grade.
         */
        fun from(document: Document, name: String, id: String = slug(name)): Look = Look(
            name = name,
            id = id,
            adjustments = document.walk()
                .filterIsInstance<Layer.AdjustmentLayer>()
                .filter { it.visible }
                .mapNotNull { layer -> portable(layer.adjustment)?.let { Entry(layer.name, it) } }
                .toList(),
        )

        /**
         * What of an adjustment can honestly travel to another photograph.
         *
         * Three of the twenty-two carry a **frozen measurement of the picture beneath them**, taken
         * once and stored so the correction does not drift while the user works (see
         * `EditorViewModel.measureAdjustment`). Carrying those numbers to a different photograph is
         * the subtle way to get this wrong, and it is the way a naive implementation gets it wrong
         * every time: the second picture would be equalised by the first picture's histogram and
         * clipped at the first picture's percentiles. It would look plausible and be meaningless.
         *
         * So the measurement is cleared and the adjustment travels as its *settings*, to be measured
         * again against whatever it lands on. That is what the user meant by saving it.
         *
         * Match Color is dropped outright rather than cleared. Its whole content is a reference to
         * another image asset, and an asset id means nothing in a document that does not contain it
         * — a Look carrying one would apply a correction with no source, which is not a weaker
         * version of the effect but an arbitrary one.
         */
        fun portable(adjustment: Adjustment): Adjustment? = when (adjustment) {
            is Adjustment.MatchColor -> null
            is Adjustment.Equalize -> adjustment.copy(table = emptyList())
            is Adjustment.ShadowsHighlights -> adjustment.copy(blackPoint = 0f, whitePoint = 1f)
            else -> adjustment
        }

        /**
         * A file-safe handle for a Persian name.
         *
         * A slug of Persian text is a row of dashes, so this does not attempt one: it keeps whatever
         * ASCII the name happens to contain and falls back to a counter-free constant that the
         * caller is expected to make unique. Guessing a transliteration would produce handles that
         * look meaningful and collide.
         */
        fun slug(name: String): String =
            name.map { if (it.isLetterOrDigit() && it.code < ASCII_LIMIT) it else '-' }
                .joinToString("")
                .trim('-')
                .ifBlank { "look" }

        private const val ASCII_LIMIT = 128

        /** Namespaces an applied layer, so applying a Look twice replaces rather than doubles it. */
        fun layerId(look: String, index: Int) = LayerId("look-$look-$index")
    }
}

/**
 * Puts a Look on a document.
 *
 * Adjustment layers go **on top of everything**, which is where a grade belongs: an adjustment
 * beneath a photograph corrects the empty space under it.
 *
 * Applying a second time replaces the first application rather than stacking on it. Without that,
 * a user comparing two Looks ends up with both at once and no way to tell which produced what they
 * are looking at — and the obvious repair, telling them to undo first, is a rule nobody remembers
 * at the moment it matters.
 */
fun Document.withLook(look: Look): Document {
    val applied = look.adjustments.mapIndexed { index, entry ->
        Layer.AdjustmentLayer(
            id = Look.layerId(look.id, index),
            adjustment = entry.adjustment,
            name = entry.name,
        )
    }
    return copy(layers = layers.filterNot { it.id.value.startsWith("look-${look.id}-") } + applied)
}

/** Takes a Look back off, for the panel's own undo and for comparing two of them. */
fun Document.withoutLook(look: Look): Document =
    copy(layers = layers.filterNot { it.id.value.startsWith("look-${look.id}-") })

/** Whether this document is currently wearing [look] — what a chip reads to show itself chosen. */
fun Document.wearing(look: Look): Boolean =
    !look.isEmpty && layers.any { it.id.value.startsWith("look-${look.id}-") }

/**
 * The filter gallery's four media.
 *
 * In `core:editor` rather than in `core:imaging` because it is a *menu*, not an algorithm: the panel
 * reads the Persian labels off it and the engine switches on it, and neither of those is imaging
 * work. Keeping it here is also what lets the label and the implementation be changed independently,
 * which matters because the labels are the part that gets revised.
 */
enum class ArtStyle(val persianLabel: String) {
    OIL_PAINT("رنگ روغن"),
    WATERCOLOUR("آبرنگ"),
    PENCIL("مداد رنگی"),
    CRYSTALLIZE("شیشه‌ای"),
}
