package ir.pixellab.core.text

/**
 * Arabic joining behaviour, from the Unicode `ArabicShaping.txt` joining types.
 *
 * Shaping itself is HarfBuzz's job — Android does it inside Skia and we never re-implement it. What
 * we need here is narrower and not available from the platform: knowing *where* a tatweel may
 * legally be inserted, which requires knowing whether the letters on both sides of a gap join.
 */
enum class JoiningType {
    /** Joins on both sides: ب, ت, س … */
    DUAL,

    /** Joins only to the preceding letter: ا, د, ر, و … */
    RIGHT,

    /** Never joins: ء and most punctuation. */
    NON_JOINING,

    /** Transparent — diacritics; they do not interrupt a join. */
    TRANSPARENT,

    /** U+0640 itself, which joins on both sides and may be repeated. */
    JOIN_CAUSING,
}

object ArabicJoining {

    const val TATWEEL = 'ـ'
    const val ZWNJ = '‌'
    const val ZWJ = '‍'

    /**
     * Letters that connect only to their right: after one of these a word breaks, so no tatweel may
     * follow. Getting this wrong is the classic Persian elongation bug — a stretched "ا" or "و"
     * detaches from the word and reads as a typo.
     */
    private val RIGHT_JOINING = buildSet {
        addAll("ادذرزژوؤأإآاٱٲٳٵﺍﺁ".toSet())
        addAll(listOf('ا', 'آ', 'أ', 'إ', 'ة', 'د', 'ذ'))
        addAll(listOf('ر', 'ز', 'و', 'ؤ', 'ژ', 'ۀ', 'ۋ', 'ە'))
    }

    private val NON_JOINING = setOf('ء', '،', '؛', '؟', '۔')

    fun typeOf(ch: Char): JoiningType = when {
        ch == TATWEEL -> JoiningType.JOIN_CAUSING
        isTransparent(ch) -> JoiningType.TRANSPARENT
        ch in NON_JOINING -> JoiningType.NON_JOINING
        ch in RIGHT_JOINING -> JoiningType.RIGHT
        isArabicLetter(ch) -> JoiningType.DUAL
        else -> JoiningType.NON_JOINING
    }

    /** Combining marks and Persian vowel signs, which sit above or below without breaking a join. */
    fun isTransparent(ch: Char): Boolean {
        val c = ch.code
        return c in 0x064B..0x065F || c in 0x0610..0x061A || c == 0x0670 ||
            c in 0x06D6..0x06DC || c in 0x06DF..0x06E8 || c in 0x06EA..0x06ED ||
            c == 0x200C || c == 0x200D
    }

    fun isArabicLetter(ch: Char): Boolean {
        val c = ch.code
        return c in 0x0620..0x064A || c in 0x066E..0x06D3 || c in 0x06FA..0x06FF ||
            c in 0x0750..0x077F || c in 0xFB50..0xFDFF || c in 0xFE70..0xFEFF
    }

    fun isArabicScript(text: String): Boolean = text.any { isArabicLetter(it) }

    /**
     * Positions where a tatweel may be inserted, as indices into [text].
     *
     * A gap qualifies when the letter before it joins forwards and the letter after it joins
     * backwards. Returned in ascending order.
     */
    fun elongationPoints(text: String): List<Int> {
        val points = mutableListOf<Int>()
        for (i in 1 until text.length) {
            val before = previousSignificant(text, i) ?: continue
            val after = text[i]
            if (isTransparent(after)) continue
            val joinsForward = typeOf(before) == JoiningType.DUAL || typeOf(before) == JoiningType.JOIN_CAUSING
            val joinsBackward = typeOf(after).let {
                it == JoiningType.DUAL || it == JoiningType.RIGHT || it == JoiningType.JOIN_CAUSING
            }
            if (joinsForward && joinsBackward) points += i
        }
        return points
    }

    private fun previousSignificant(text: String, index: Int): Char? {
        var i = index - 1
        while (i >= 0 && isTransparent(text[i])) i--
        return if (i >= 0) text[i] else null
    }

    /**
     * Distributes [total] tatweel characters across the legal positions of [text].
     *
     * Insertions are spread evenly rather than piled into one gap, matching how a calligrapher
     * balances a stretched word. Returns [text] unchanged when the word cannot be elongated.
     */
    fun elongate(text: String, total: Int): String {
        if (total <= 0) return text
        val points = elongationPoints(text)
        if (points.isEmpty()) return text

        val perPoint = IntArray(points.size)
        // Spread from the end of the word backwards: Persian elongates the later joins first.
        for (n in 0 until total) perPoint[points.size - 1 - (n % points.size)]++

        val out = StringBuilder(text.length + total)
        var next = 0
        for (i in text.indices) {
            if (next < points.size && points[next] == i) {
                repeat(perPoint[next]) { out.append(TATWEEL) }
                next++
            }
            out.append(text[i])
        }
        return out.toString()
    }

    /** Strips elongation, recovering the text the user actually typed. */
    fun deElongate(text: String): String = text.filter { it != TATWEEL }
}
