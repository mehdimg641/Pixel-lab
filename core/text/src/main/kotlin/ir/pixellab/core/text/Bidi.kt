package ir.pixellab.core.text

import ir.pixellab.core.model.TextAlign
import ir.pixellab.core.model.TextDirection
import java.text.Bidi as JdkBidi

/** A maximal stretch of text with a single resolved direction. */
data class DirectionalRun(val start: Int, val end: Int, val rightToLeft: Boolean) {
    val length: Int get() = end - start
}

/**
 * Bidirectional text resolution.
 *
 * A Persian design almost always mixes scripts — a price with Latin digits, a hall number like
 * "سالن c3", an English handle under a Persian headline. Laying that out by measuring characters
 * left to right produces reversed digit groups, so run splitting goes through the Unicode
 * bidirectional algorithm rather than any hand-rolled heuristic.
 */
object BidiAnalyzer {

    /**
     * Resolves the paragraph's base direction. [TextDirection.AUTO] takes it from the first strong
     * character, which is what the Unicode algorithm prescribes and what users expect when they
     * start typing.
     */
    fun resolveBaseDirection(text: String, requested: TextDirection): Boolean = when (requested) {
        TextDirection.LTR -> false
        TextDirection.RTL -> true
        TextDirection.AUTO -> firstStrongIsRtl(text)
    }

    /** Splits [text] into visual-order runs. Returns a single run for uniform text. */
    fun runs(text: String, baseRtl: Boolean): List<DirectionalRun> {
        if (text.isEmpty()) return emptyList()
        val flag = if (baseRtl) JdkBidi.DIRECTION_RIGHT_TO_LEFT else JdkBidi.DIRECTION_LEFT_TO_RIGHT
        val bidi = JdkBidi(text, flag)
        if (bidi.isLeftToRight) return listOf(DirectionalRun(0, text.length, false))
        if (bidi.isRightToLeft) return listOf(DirectionalRun(0, text.length, true))
        return (0 until bidi.runCount).map { i ->
            DirectionalRun(bidi.getRunStart(i), bidi.getRunLimit(i), bidi.getRunLevel(i) % 2 == 1)
        }
    }

    fun isMixed(text: String, baseRtl: Boolean): Boolean = runs(text, baseRtl).size > 1

    /** Maps a logical [TextAlign] onto a physical edge for the resolved direction. */
    fun physicalAlign(align: TextAlign, baseRtl: Boolean): PhysicalAlign = when (align) {
        TextAlign.CENTER -> PhysicalAlign.CENTER
        TextAlign.JUSTIFY -> PhysicalAlign.JUSTIFY
        TextAlign.START -> if (baseRtl) PhysicalAlign.RIGHT else PhysicalAlign.LEFT
        TextAlign.END -> if (baseRtl) PhysicalAlign.LEFT else PhysicalAlign.RIGHT
    }

    private fun firstStrongIsRtl(text: String): Boolean {
        for (ch in text) {
            if (ArabicJoining.isArabicLetter(ch)) return true
            if (ch.isLetter() && ch.code < 0x0590) return false
            val c = ch.code
            if (c in 0x0590..0x05FF || c in 0x07C0..0x08FF) return true
        }
        return false
    }
}

enum class PhysicalAlign { LEFT, CENTER, RIGHT, JUSTIFY }

/** Digit shaping. Persian designs normally want ۰-۹ even when the keyboard produced 0-9. */
object DigitShaper {
    private const val PERSIAN_ZERO = '۰'
    private const val ARABIC_ZERO = '٠'

    fun toPersian(text: String): String =
        text.map { if (it in '0'..'9') PERSIAN_ZERO + (it - '0') else it }.joinToString("")

    fun toLatin(text: String): String = text.map {
        when (it) {
            in PERSIAN_ZERO..'۹' -> '0' + (it - PERSIAN_ZERO)
            in ARABIC_ZERO..'٩' -> '0' + (it - ARABIC_ZERO)
            else -> it
        }
    }.joinToString("")
}
