package ir.pixellab.core.text

import ir.pixellab.core.model.FontRef
import ir.pixellab.core.model.KashidaMode
import ir.pixellab.core.model.ParagraphStyle
import kotlin.math.roundToInt

/** What a resolved font can do, as reported by the platform font loader. */
data class FontCapabilities(
    val axes: Map<String, ClosedFloatingPointRange<Float>> = emptyMap(),
    val features: Set<String> = emptySet(),
    val hasTatweel: Boolean = true,
) {
    val kashidaAxisTag: String? get() = FontRef.KASHIDA_AXES.firstOrNull { it in axes }
    val supportsKashidaAxis: Boolean get() = kashidaAxisTag != null
}

/**
 * The outcome of planning elongation for one text run.
 *
 * Exactly one mechanism is chosen. [text] is what gets shaped and [variations] is what gets applied
 * to the typeface, so the caller never has to know which path was taken.
 */
data class KashidaPlan(
    val text: String,
    val variations: Map<String, Float>,
    val mode: KashidaMode,
) {
    /** True when the plan left the user's string untouched — the better of the two mechanisms. */
    val preservesText: Boolean get() = mode != KashidaMode.TATWEEL
}

/**
 * Chooses how to elongate Persian text.
 *
 * Two mechanisms exist and they are not equivalent. Driving a variable font's `KASH` axis is
 * continuous, is drawn by the type designer, and leaves the string exactly as typed — copying the
 * result yields clean text. Inserting U+0640 works on any font but is stepped and corrupts the
 * content. Six of the supplied typefaces expose the axis, so [KashidaMode.AUTO] prefers it and
 * falls back to tatweel for the rest.
 */
object KashidaPlanner {

    /** Tatweel characters inserted at full strength. Beyond this a word stops reading as a word. */
    private const val MAX_TATWEEL = 12

    fun plan(
        text: String,
        font: FontRef,
        paragraph: ParagraphStyle,
        capabilities: FontCapabilities,
    ): KashidaPlan {
        val amount = paragraph.kashidaAmount.coerceIn(0f, 1f)
        val base = font.variations
        val requested = paragraph.kashida

        if (requested == KashidaMode.NONE || amount <= 0f || !ArabicJoining.isArabicScript(text)) {
            return KashidaPlan(text, base, KashidaMode.NONE)
        }

        val axisTag = capabilities.kashidaAxisTag
        val useAxis = when (requested) {
            KashidaMode.VARIABLE_AXIS -> axisTag != null
            KashidaMode.AUTO -> axisTag != null
            else -> false
        }

        if (useAxis && axisTag != null) {
            val range = capabilities.axes.getValue(axisTag)
            val value = range.start + (range.endInclusive - range.start) * amount
            return KashidaPlan(text, base + (axisTag to value), KashidaMode.VARIABLE_AXIS)
        }

        if (requested == KashidaMode.VARIABLE_AXIS) {
            // Explicitly asked for the axis but this font has none. Do nothing rather than silently
            // rewriting the user's text with a mechanism they did not choose.
            return KashidaPlan(text, base, KashidaMode.NONE)
        }

        if (!capabilities.hasTatweel) return KashidaPlan(text, base, KashidaMode.NONE)

        val perWord = (MAX_TATWEEL * amount).roundToInt()
        if (perWord <= 0) return KashidaPlan(text, base, KashidaMode.NONE)
        return KashidaPlan(elongateWords(text, perWord), base, KashidaMode.TATWEEL)
    }

    /** Applies elongation word by word so spacing between words is left alone. */
    private fun elongateWords(text: String, perWord: Int): String =
        text.split(' ').joinToString(" ") { ArabicJoining.elongate(it, perWord) }
}
