package ir.pixellab.core.text

import ir.pixellab.core.model.CharacterStyle
import ir.pixellab.core.model.StyleRun

/**
 * One stretch of the text and the style it is drawn with, covering the string with no gaps.
 *
 * The difference from [StyleRun] is that a run is *what the user asked for* and a segment is *what
 * the renderer draws*. Runs are sparse — an empty list is the common case — while segments always
 * tile the whole string, so a renderer can walk them without ever asking whether it is inside a run.
 */
data class StyleSegment(
    val start: Int,
    val end: Int,
    val style: CharacterStyle,
) {
    val length: Int get() = end - start

    /** True where the segment falls back to the layer's own settings. */
    val isPlain: Boolean get() = style.isDefault
}

/**
 * Everything that happens to a range of styled text before it reaches a shaper.
 *
 * ### Why this is a whole file and not three helper functions
 *
 * Styling part of a Persian string is the one operation in this application where the obvious
 * implementation is *always* wrong, and it is wrong in a way that looks fine in Latin and fails
 * only for the users this project exists for. Given «کابینت» and a request to tint its middle, the
 * obvious move is to cut the string into three and style each piece. Cut like that, the ب and ی
 * lose their medial forms and the word appears on screen as four disconnected shapes where the
 * reader expects one word. Editors that offer per-character colour on Arabic script do exactly
 * this, and it is why Persian titles get set in desktop software or not at all.
 *
 * So the rule this file enforces is: **a range is metadata about the string, never a cut in it.**
 * Ranges are normalised, snapped, merged and shifted here as pure index arithmetic; the string is
 * handed to the shaper whole, and where the renderer genuinely must draw one segment at a time it
 * asks [padded] for the joining context first.
 *
 * ### The one that is always forgotten
 *
 * [shift]. The user tints a word, then types a letter earlier in the sentence, and every stored
 * range is now one character out — the colour has slid onto the wrong letters. It is invisible in a
 * screenshot and immediate in real use, and it is the first thing tested.
 */
object StyleRuns {

    /**
     * Puts a list of runs into the only shape the rest of the code may assume.
     *
     * Sorted, clipped to the text, non-overlapping with later runs winning, default styles dropped,
     * and identical neighbours merged. Later-wins rather than first-wins because the list is built
     * by successive edits, and the last edit is the user's most recent intent.
     *
     * Merging is not cosmetic. Without it, tinting a word one cluster at a time leaves a run per
     * cluster, so the renderer's "do all the segments on this line agree on metrics" fast path has
     * a longer question to answer on every frame, and the undo history fills with states that look
     * identical to each other.
     */
    fun normalise(text: String, runs: List<StyleRun>): List<StyleRun> {
        if (runs.isEmpty() || text.isEmpty()) return emptyList()

        // Painting into a per-character array and reading the stretches back makes overlap
        // resolution one line instead of a case analysis over boundaries. The string is a headline;
        // the sweep that would be asymptotically better is not worth the ways it can be wrong.
        val styles = arrayOfNulls<CharacterStyle>(text.length)
        for (run in runs) {
            val from = run.start.coerceIn(0, text.length)
            val to = run.end.coerceIn(0, text.length)
            for (i in from until to) styles[i] = run.style.takeIf { !it.isDefault }
        }

        val out = ArrayList<StyleRun>()
        var i = 0
        while (i < text.length) {
            val style = styles[i]
            if (style == null) {
                i++
                continue
            }
            var j = i + 1
            while (j < text.length && styles[j] == style) j++
            out += StyleRun(i, j, style)
            i = j
        }
        return out
    }

    /**
     * The full cover of [text]: every character in exactly one segment, in order.
     *
     * Gaps between runs become plain segments rather than being skipped, so a caller never has to
     * track where the last run ended. An empty run list gives exactly one segment, which is how the
     * untouched case stays on the old code path without a special case at the call site.
     */
    fun segments(text: String, runs: List<StyleRun>): List<StyleSegment> {
        if (text.isEmpty()) return emptyList()
        val normalised = normalise(text, runs)
        if (normalised.isEmpty()) return listOf(StyleSegment(0, text.length, CharacterStyle.NONE))

        val out = ArrayList<StyleSegment>(normalised.size * 2 + 1)
        var at = 0
        for (run in normalised) {
            if (run.start > at) out += StyleSegment(at, run.start, CharacterStyle.NONE)
            out += StyleSegment(run.start, run.end, run.style)
            at = run.end
        }
        if (at < text.length) out += StyleSegment(at, text.length, CharacterStyle.NONE)
        return out
    }

    /**
     * Segments restricted to `[from, to)` and re-based so the indices are relative to it.
     *
     * The renderer works a line at a time and a line is a substring, so without this every caller
     * would repeat the same clip-and-subtract and one of them would get it wrong.
     */
    fun segmentsIn(text: String, runs: List<StyleRun>, from: Int, to: Int): List<StyleSegment> =
        segments(text, runs).mapNotNull { segment ->
            val start = maxOf(segment.start, from)
            val end = minOf(segment.end, to)
            if (start >= end) null else StyleSegment(start - from, end - from, segment.style)
        }

    /**
     * Widens a raw selection to whole units of [granularity].
     *
     * The glyph ribbon selects clusters, so a selection made there arrives already aligned; one
     * dragged across the canvas does not. Snapping outward rather than inward, because a user who
     * has half-covered a word means that word — shrinking to nothing gives a control that appears
     * not to respond at all.
     */
    fun snap(text: String, range: IntRange, granularity: Granularity = Granularity.CLUSTER): IntRange {
        if (text.isEmpty()) return IntRange.EMPTY
        val from = range.first.coerceIn(0, text.length)
        val to = (range.last + 1).coerceIn(0, text.length)
        if (from >= to) return IntRange.EMPTY

        val pieces = Clusters.of(text, granularity)
        val start = pieces.lastOrNull { it.start <= from }?.start ?: 0
        val end = pieces.firstOrNull { it.end >= to }?.end ?: text.length
        return start until end
    }

    /**
     * Applies [edit] to whatever style already covers [range], returning the new run list.
     *
     * **The single door.** Every control in the text panel goes through here rather than building
     * runs of its own, which keeps two rules true everywhere at once: changing one property never
     * silently clears the others the user set, and the result is always normalised.
     *
     * The edit is a function of the *existing* style for the first reason. Passing a whole
     * [CharacterStyle] instead would make "set the colour" also reset the size, and that is exactly
     * the bug that makes per-word styling feel unusable — every second adjustment undoes the last.
     * Where [range] spans several existing runs each keeps its own starting point, so tinting a
     * phrase that already contains one differently-sized word preserves that word's size.
     *
     * An edit that returns the default clears the range, because [normalise] drops default styles.
     */
    fun apply(
        text: String,
        runs: List<StyleRun>,
        range: IntRange,
        edit: (CharacterStyle) -> CharacterStyle,
    ): List<StyleRun> {
        val from = range.first.coerceIn(0, text.length)
        val to = (range.last + 1).coerceIn(0, text.length)
        if (from >= to) return normalise(text, runs)

        val out = ArrayList<StyleRun>()
        for (segment in segments(text, runs)) {
            // Cut each existing segment at the two range boundaries; the piece that falls inside
            // gets the edit, the pieces outside keep what they had. Three pieces at most, and the
            // `distinct` collapses the common case where a boundary lands on a segment edge.
            val cuts = listOf(
                segment.start,
                from.coerceIn(segment.start, segment.end),
                to.coerceIn(segment.start, segment.end),
                segment.end,
            ).distinct()
            for (i in 0 until cuts.size - 1) {
                val start = cuts[i]
                val end = cuts[i + 1]
                if (end <= start) continue
                val inside = start >= from && end <= to
                val style = if (inside) edit(segment.style) else segment.style
                if (!style.isDefault) out += StyleRun(start, end, style)
            }
        }
        return normalise(text, out)
    }

    /** Removes all styling from [range], leaving it drawn with the layer's own settings. */
    fun clear(text: String, runs: List<StyleRun>, range: IntRange): List<StyleRun> =
        apply(text, runs, range) { CharacterStyle.NONE }

    /**
     * Moves runs to follow an edit to the string: [delta] characters inserted at [at], or removed
     * from `[at, at - delta)` when negative.
     *
     * **This is the one that is always missing**, and its absence is not subtle. Tint a word, then
     * correct a typo earlier in the sentence, and every colour lands one letter off. It cannot be
     * seen in a screenshot and cannot be missed in use.
     *
     * The rules are the ones a text editor uses for its own marks, and the asymmetry at the seams
     * is deliberate: typing immediately *before* a tinted word writes plain text, because that is
     * how you write the preceding word; typing immediately *after* it does too. Typing inside it
     * inherits the tint, because that is how you correct a spelling. Getting either seam backwards
     * gives a control that behaves differently at its two edges for no reason the user can see.
     */
    fun shift(runs: List<StyleRun>, at: Int, delta: Int): List<StyleRun> {
        if (delta == 0 || runs.isEmpty()) return runs
        val removedTo = if (delta < 0) at - delta else at

        return runs.mapNotNull { run ->
            val start = move(run.start, at, removedTo, delta, movesAtSeam = true)
            val end = move(run.end, at, removedTo, delta, movesAtSeam = false)
            if (end > start) StyleRun(start, end, run.style) else null
        }
    }

    /**
     * Where one index lands after the edit.
     *
     * [movesAtSeam] is the only difference between the two ends of a run, and it applies to
     * insertions alone: a run's start sitting exactly at the insertion point moves along so the new
     * characters fall outside it, while a run's end sitting there stays put for the same reason.
     * A deletion needs no such distinction — both ends collapse onto the start of what was removed.
     */
    private fun move(index: Int, at: Int, removedTo: Int, delta: Int, movesAtSeam: Boolean): Int = when {
        delta > 0 -> if (index > at || (index == at && movesAtSeam)) index + delta else index
        index <= at -> index
        index <= removedTo -> at
        else -> index - (removedTo - at)
    }

    /**
     * Moves runs across an arbitrary rewrite of the string, from [before] to [after].
     *
     * The text panel hands back a whole new string rather than a stream of keystrokes — the editor
     * is a dialog with a text field in it — so there is no insertion point to pass to [shift]. This
     * recovers one: whatever prefix and suffix the two strings share is unchanged, and everything
     * between them is one replacement. That is exactly right for the edits people actually make
     * (typing, deleting, correcting a word) and never worse than leaving the ranges where they were.
     *
     * Without it, fixing a typo at the start of a sentence slides every colour along by a letter,
     * which is the sort of thing that is noticed once and then never trusted again.
     */
    fun retarget(runs: List<StyleRun>, before: String, after: String): List<StyleRun> {
        if (runs.isEmpty() || before == after) return normalise(after, runs)

        var head = 0
        val shortest = minOf(before.length, after.length)
        while (head < shortest && before[head] == after[head]) head++

        var tail = 0
        while (tail < shortest - head && before[before.length - 1 - tail] == after[after.length - 1 - tail]) tail++

        val removed = before.length - head - tail
        val inserted = after.length - head - tail

        var moved = runs
        if (removed > 0) moved = shift(moved, head, -removed)
        if (inserted > 0) moved = shift(moved, head, inserted)
        return normalise(after, moved)
    }

    /**
     * Moves runs from [source] indices onto the [shaped] string the renderer actually lays out.
     *
     * The two are usually the same string and this returns immediately. They differ when elongation
     * ran as tatweel: `KashidaPlanner` inserts U+0640 into the text before shaping, so every index
     * after the first stretched word is out by however many were added. Without this, tinting a
     * word and *then* pulling the kashida slider repaints a different word — and the two controls
     * sit in the same panel, so it would be found in the first minute.
     *
     * The walk is exact because elongation only ever inserts characters: characters that match
     * advance both cursors, and anything left over in [shaped] is inserted padding to be skipped.
     */
    fun remap(runs: List<StyleRun>, source: String, shaped: String): List<StyleRun> {
        if (runs.isEmpty() || source == shaped) return runs

        val map = IntArray(source.length + 1)
        var i = 0
        var j = 0
        while (i < source.length && j < shaped.length) {
            if (source[i] == shaped[j]) {
                map[i] = j
                i++
            }
            j++
        }
        // Anything the walk could not place lands at the end, which keeps indices monotonic rather
        // than leaving zeros in the middle of the map.
        while (i <= source.length) {
            map[i] = shaped.length
            i++
        }
        map[source.length] = shaped.length

        return runs.mapNotNull { run ->
            val start = map[run.start.coerceIn(0, source.length)]
            val end = map[run.end.coerceIn(0, source.length)]
            if (end > start) StyleRun(start, end, run.style) else null
        }
    }

    /**
     * The substring for `[start, end)`, padded with zero-width joiners wherever the cut falls
     * inside a connected cluster.
     *
     * **Why the padding exists.** A shaper handed «بین» alone has no way to know it came from the
     * middle of «کابینت», so it produces isolated forms and the word visibly comes apart. U+200D is
     * the character that says *something joins here*: it draws nothing and takes no width, but it
     * makes the shaper choose the medial form. Padding only where the cut is genuinely inside a
     * cluster leaves the string identical to the original everywhere else, so a segment that is a
     * whole word — which is what the ribbon's selection produces — is shaped exactly as it always
     * was, with no padding at all.
     *
     * The result carries how many characters were added at the front, which the caller must
     * subtract when it positions the piece.
     */
    fun padded(text: String, start: Int, end: Int): Padded {
        val head = if (Clusters.joinsBefore(text, start)) ZWJ else ""
        val tail = if (Clusters.joinsBefore(text, end)) ZWJ else ""
        return Padded(head + text.substring(start, end) + tail, head.length, tail.length)
    }

    /** A segment's text with its joining context restored, and how much of it is padding. */
    data class Padded(val text: String, val leading: Int, val trailing: Int) {
        /** False when the substring was already safe to shape alone — the common case. */
        val hasPadding: Boolean get() = leading > 0 || trailing > 0
    }

    private const val ZWJ = "‍"
}
