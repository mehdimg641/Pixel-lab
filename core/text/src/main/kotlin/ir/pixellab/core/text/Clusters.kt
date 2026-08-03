package ir.pixellab.core.text

/**
 * Splits Persian text into the units it can actually be taken apart at.
 *
 * **The whole point in one example.** «سلام» is four characters and *two* pieces: «سلا» and «م». Cut
 * it into four and you have not separated four letters, you have broken a word — the س loses its
 * initial form, the ل its medial form, and what appears on screen is ﺱ ﻝ ﺍ ﻡ, which no reader will
 * accept. Every tool that animates or extrudes text letter by letter does exactly this, and it is
 * why Persian type is not used in them.
 *
 * So the unit of separation is the **connected cluster**: a run of letters that the script draws
 * joined. It is what the transform tools address, what the glyph ribbon shows one chip per, and what
 * a per-letter 3D effect is applied to. The specification calls it out twice — §۶.۹.۱ for the rule
 * and §۱۵ for the risk, where it is marked *certain to break if unsolved*.
 *
 * Latin gets characters, because in Latin that is the same thing.
 */
enum class Granularity {
    /** Everything, as one. */
    ALL,

    /** A line, split at hard breaks only. */
    LINE,

    /** A word, split at spaces. */
    WORD,

    /**
     * The connected cluster. The default for Persian, and the reason this file exists.
     *
     * In Latin text a cluster is one character, so this and [CHARACTER] agree there and the caller
     * does not need to know which script it is holding.
     */
    CLUSTER,

    /**
     * One character.
     *
     * Offered because it is right for Latin and occasionally wanted for a deliberate effect, and it
     * is *not* the default: choosing it on Persian breaks words, which is precisely the failure the
     * rest of this file exists to prevent.
     */
    CHARACTER,
}

/**
 * One piece of the text, as a range of the original string.
 *
 * A range rather than a copied substring, because the caller almost always needs to map back: the
 * ribbon has to know which characters to elongate when a chip is dragged, and the 3D path has to
 * know which glyphs belong to the piece it is extruding.
 */
data class TextCluster(
    val start: Int,
    val end: Int,
    val text: String,
    /**
     * Whether this piece is joined script at all.
     *
     * False for a space run, for Latin, for digits and for punctuation. The ribbon uses it to decide
     * whether a chip can be stretched — dragging a kashida out of an English word is meaningless.
     */
    val joined: Boolean,
) {
    val length: Int get() = end - start

    /**
     * Where a kashida may be inserted inside this piece, as offsets from [start].
     *
     * Empty when the piece cannot stretch, which is the case for a single non-connecting letter such
     * as «و» or «د» standing alone, for every Latin word, and for punctuation.
     */
    val elongationPoints: List<Int> get() = if (joined) ArabicJoining.elongationPoints(text) else emptyList()

    val stretchable: Boolean get() = elongationPoints.isNotEmpty()
}

object Clusters {

    /**
     * Splits [text] at the requested granularity.
     *
     * Whitespace is kept as its own piece rather than dropped, so that the pieces concatenate back
     * into the original string. A ribbon that silently discarded the spaces would renumber every
     * chip the moment a user typed one, and a transform applied by index would land on the wrong
     * letter.
     */
    fun of(text: String, granularity: Granularity = Granularity.CLUSTER): List<TextCluster> = when (granularity) {
        Granularity.ALL -> whole(text)
        Granularity.LINE -> splitOn(text) { _, at -> text[at - 1] == '\n' }
        Granularity.WORD -> words(text)
        Granularity.CLUSTER -> clusters(text)
        Granularity.CHARACTER -> characters(text)
    }

    private fun whole(text: String): List<TextCluster> =
        if (text.isEmpty()) emptyList() else listOf(cluster(text, 0, text.length))

    /**
     * The connected clusters.
     *
     * The rule is local and needs only two characters at a time: a break falls between *i-1* and *i*
     * when the letter before does not join forwards, or the letter after does not join backwards.
     * Everything else — the shaping itself, the choice of initial or medial form — belongs to
     * HarfBuzz and is not re-derived here.
     *
     * Four cases produce a break, and each is a real failure if missed:
     *
     * - **A right-joining letter.** After ا, د, ر, ز, و and their relatives the script stops. «دارد»
     *   is three clusters, not one, and a tool that treats it as one cannot separate its letters at
     *   all.
     * - **A zero-width non-joiner.** The user typed a break on purpose: «می‌رود» is «می» and «رود».
     *   Ignoring it merges what they explicitly separated.
     * - **A space or any non-letter.** Between scripts too — «KAR20 مدیا» must not fuse its Latin
     *   into its Persian.
     * - **A change of script.** A digit next to a letter is two pieces even with no space.
     */
    private fun clusters(text: String): List<TextCluster> = splitOn(text) { before, at ->
        breaksBetween(text, before, at)
    }

    private fun breaksBetween(text: String, before: Int, at: Int): Boolean {
        val previous = text[before]
        val next = text[at]

        // A mark belongs to whatever it sits on. Breaking before a fatha would leave the vowel
        // orphaned in its own cluster, floating beside the letter it belongs to.
        if (ArabicJoining.isTransparent(next) && next != ArabicJoining.ZWNJ) return false

        // The user's own break, and the most important one to honour: a half-space is a decision.
        if (previous == ArabicJoining.ZWNJ || next == ArabicJoining.ZWNJ) return true

        val previousLetter = ArabicJoining.isArabicLetter(previous)
        val nextLetter = ArabicJoining.isArabicLetter(next)
        if (!previousLetter || !nextLetter) return true

        val joinsForward = when (ArabicJoining.typeOf(lastSignificant(text, before))) {
            JoiningType.DUAL, JoiningType.JOIN_CAUSING -> true
            else -> false
        }
        val joinsBackward = when (ArabicJoining.typeOf(next)) {
            JoiningType.DUAL, JoiningType.RIGHT, JoiningType.JOIN_CAUSING -> true
            else -> false
        }
        return !(joinsForward && joinsBackward)
    }

    /**
     * The last character at or before [index] that decides a join.
     *
     * Marks are skipped: in «مُحَمَّد» the ح joins the م after it even though a fatha sits between them
     * in the string, because a vowel sign is drawn above the line and the join runs underneath it.
     */
    private fun lastSignificant(text: String, index: Int): Char {
        var i = index
        while (i > 0 && ArabicJoining.isTransparent(text[i]) && text[i] != ArabicJoining.ZWNJ) i--
        return text[i]
    }

    /** Words, split at whitespace, with the whitespace kept as pieces of its own. */
    private fun words(text: String): List<TextCluster> = splitOn(text) { before, at ->
        text[before].isWhitespace() != text[at].isWhitespace()
    }

    /**
     * Characters — but never splitting a surrogate pair or orphaning a mark.
     *
     * "One character" is already not one `Char`: an emoji is two, and a letter with a vowel sign is
     * two that have to move together. Even the granularity that exists for Latin cannot be a plain
     * index walk.
     */
    private fun characters(text: String): List<TextCluster> = splitOn(text) { before, at ->
        !(text[before].isHighSurrogate() && text[at].isLowSurrogate()) &&
            !ArabicJoining.isTransparent(text[at])
    }

    /**
     * The shared walk: emit a piece wherever [breaks] says the run ends.
     *
     * One traversal for all five granularities, so a fix to how ranges are built — an off-by-one at
     * the last piece, say — cannot be fixed in one and missed in the other four.
     */
    private inline fun splitOn(text: String, breaks: (before: Int, at: Int) -> Boolean): List<TextCluster> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<TextCluster>()
        var start = 0
        for (i in 1 until text.length) {
            if (!breaks(i - 1, i)) continue
            out += cluster(text, start, i)
            start = i
        }
        out += cluster(text, start, text.length)
        return out
    }

    private fun cluster(text: String, start: Int, end: Int): TextCluster {
        val piece = text.substring(start, end)
        // Joined means "the script draws this as connected letters", which needs at least one
        // Arabic letter. A lone space, a number or an English word is a piece but not a joined one.
        return TextCluster(start, end, piece, piece.any { ArabicJoining.isArabicLetter(it) })
    }

    /**
     * Elongates one cluster by [amount] tatweel characters, returning the whole text.
     *
     * Here rather than on the caller because the offsets are the fiddly part: the cluster knows its
     * own elongation points as offsets from its start, and the string being rebuilt is the entire
     * text. Getting that translation wrong stretches a different word from the one under the finger.
     */
    fun elongate(text: String, cluster: TextCluster, amount: Int): String {
        if (amount <= 0 || !cluster.stretchable) return text
        val stretched = ArabicJoining.elongate(cluster.text, amount)
        return text.substring(0, cluster.start) + stretched + text.substring(cluster.end)
    }

    /** Removes every kashida from one cluster, leaving the rest of the text alone. */
    fun resetElongation(text: String, cluster: TextCluster): String {
        val plain = ArabicJoining.deElongate(cluster.text)
        if (plain == cluster.text) return text
        return text.substring(0, cluster.start) + plain + text.substring(cluster.end)
    }
}
