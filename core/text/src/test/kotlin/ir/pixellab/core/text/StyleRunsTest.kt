package ir.pixellab.core.text

import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.CharacterStyle
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.StyleRun
import org.junit.jupiter.api.Test

/**
 * The index arithmetic that lets one word in a sentence look different from the rest.
 *
 * Nothing here draws anything. That is the point: every way this feature fails in other editors is
 * a failure of bookkeeping, not of rendering, and bookkeeping can be tested without a screen.
 */
class StyleRunsTest {

    /** «برای اطلاع از قیمت کابینت» — the sentence the whole feature was asked for. */
    private val sentence = "برای اطلاع از قیمت کابینت"
    private val word = sentence.indexOf("کابینت").let { it until it + "کابینت".length }

    private val red = CharacterStyle(fill = Fill.Solid(Color(1f, 0f, 0f)))
    private val blue = CharacterStyle(fill = Fill.Solid(Color(0f, 0f, 1f)))
    private val big = CharacterStyle(sizeScale = 1.5f)

    private fun tint(colour: CharacterStyle) = { existing: CharacterStyle -> existing.copy(fill = colour.fill) }

    // ---- normalise ----------------------------------------------------------------------------

    @Test
    fun `a later run wins where two overlap`() {
        // The list is built by successive edits, so the last one is the user's current intent. A
        // first-wins rule would make the second tap on a colour do nothing, which reads as a broken
        // control rather than as a policy.
        val runs = listOf(StyleRun(0, 10, red), StyleRun(5, 15, blue))
        StyleRuns.normalise(sentence, runs) shouldBe listOf(StyleRun(0, 5, red), StyleRun(5, 15, blue))
    }

    @Test
    fun `neighbours with the same style become one run`() {
        // Tinting a word one cluster at a time must not leave a run per cluster: the renderer asks
        // "do all the segments on this line agree on metrics" every frame, and the undo stack fills
        // with states that are indistinguishable from each other.
        val runs = listOf(StyleRun(0, 4, red), StyleRun(4, 9, red))
        StyleRuns.normalise(sentence, runs) shouldBe listOf(StyleRun(0, 9, red))
    }

    @Test
    fun `a run that changes nothing is not stored`() {
        StyleRuns.normalise(sentence, listOf(StyleRun(0, 5, CharacterStyle.NONE))) shouldBe emptyList()
    }

    @Test
    fun `a run reaching past the end is clipped rather than rejected`() {
        // Reachable from a legitimate sequence: style the last word, then delete some of it. Better
        // to clip than to throw, because the alternative is a document that will not open.
        val runs = listOf(StyleRun(20, 500, red))
        StyleRuns.normalise(sentence, runs) shouldBe listOf(StyleRun(20, sentence.length, red))
    }

    // ---- segments -----------------------------------------------------------------------------

    @Test
    fun `an unstyled string is exactly one plain segment`() {
        // The property the whole no-regression guarantee rests on: with no runs, a renderer walking
        // segments does precisely what it did before segments existed.
        val segments = StyleRuns.segments(sentence, emptyList())
        segments.size shouldBe 1
        segments[0].isPlain shouldBe true
        segments[0].length shouldBe sentence.length
    }

    @Test
    fun `segments tile the string with no gaps and no overlaps`() {
        val runs = listOf(StyleRun(5, 10, red), StyleRun(19, 25, blue))
        val segments = StyleRuns.segments(sentence, runs)
        segments.first().start shouldBe 0
        segments.last().end shouldBe sentence.length
        segments.zipWithNext { a, b -> a.end shouldBe b.start }
    }

    @Test
    fun `a line asks only for the segments it contains, in its own coordinates`() {
        val runs = listOf(StyleRun(19, 25, red))
        val segments = StyleRuns.segmentsIn(sentence, runs, from = 19, to = 25)
        segments.size shouldBe 1
        segments[0].start shouldBe 0
        segments[0].end shouldBe 6
    }

    // ---- apply --------------------------------------------------------------------------------

    @Test
    fun `setting the colour of a word leaves its size alone`() {
        // **The defect this whole signature exists to prevent.** If `apply` took a whole style
        // rather than an edit, the second adjustment would undo the first and per-word styling
        // would feel like it does not work.
        val sized = StyleRuns.apply(sentence, emptyList(), word) { it.copy(sizeScale = 1.5f) }
        val tinted = StyleRuns.apply(sentence, sized, word, tint(red))
        tinted.size shouldBe 1
        tinted[0].style.sizeScale shouldBe 1.5f
        tinted[0].style.fill shouldBe red.fill
    }

    @Test
    fun `styling part of a longer run splits it and keeps the rest`() {
        val whole = StyleRuns.apply(sentence, emptyList(), 0 until sentence.length) { big }
        val part = StyleRuns.apply(sentence, whole, word, tint(red))
        // The untouched head keeps the size and gains no colour.
        part.first().style.fill shouldBe null
        part.first().style.sizeScale shouldBe 1.5f
        // The word keeps the size it inherited and gains the colour.
        val styled = part.single { it.start == word.first }
        styled.style.sizeScale shouldBe 1.5f
        styled.style.fill shouldBe red.fill
    }

    @Test
    fun `editing back to the default erases the run instead of storing an empty one`() {
        val tinted = StyleRuns.apply(sentence, emptyList(), word, tint(red))
        StyleRuns.clear(sentence, tinted, word) shouldBe emptyList()
    }

    @Test
    fun `an empty range changes nothing`() {
        val tinted = StyleRuns.apply(sentence, emptyList(), word, tint(red))
        StyleRuns.apply(sentence, tinted, IntRange.EMPTY, tint(blue)) shouldBe tinted
    }

    // ---- snap ---------------------------------------------------------------------------------

    @Test
    fun `half a word selected means the whole word`() {
        // Snapping inward would let a selection shrink to nothing, and a control that responds to
        // being dragged by doing nothing is indistinguishable from one that is broken.
        val half = word.first until (word.first + 2)
        val snapped = StyleRuns.snap(sentence, half, Granularity.WORD)
        snapped.first shouldBe word.first
        snapped.last shouldBe word.last
    }

    @Test
    fun `a selection already on a cluster boundary is left where it is`() {
        val clusters = Clusters.of(sentence, Granularity.CLUSTER)
        val one = clusters.first { it.joined && it.length > 1 }
        StyleRuns.snap(sentence, one.start until one.end) shouldBe (one.start until one.end)
    }

    // ---- shift: the one that is always missing ------------------------------------------------

    @Test
    fun `typing before a styled word carries the style along with it`() {
        // Tint «کابینت», then fix a typo at the start of the sentence. Without this the colour
        // lands one letter to the left and stays there — invisible in a demo, immediate in use.
        val runs = listOf(StyleRun(word.first, word.last + 1, red))
        val moved = StyleRuns.shift(runs, at = 0, delta = 3)
        moved shouldBe listOf(StyleRun(word.first + 3, word.last + 4, red))
    }

    @Test
    fun `typing after a styled word does not extend it`() {
        // Writing the next word must not inherit the previous word's colour.
        val runs = listOf(StyleRun(5, 10, red))
        StyleRuns.shift(runs, at = 10, delta = 4) shouldBe listOf(StyleRun(5, 10, red))
    }

    @Test
    fun `typing in front of a styled word does not extend it either`() {
        // The matching seam. If this one moved the other way the control would behave differently
        // at its two edges for no reason a user could see.
        val runs = listOf(StyleRun(5, 10, red))
        StyleRuns.shift(runs, at = 5, delta = 4) shouldBe listOf(StyleRun(9, 14, red))
    }

    @Test
    fun `typing inside a styled word extends it`() {
        // Correcting a spelling inside a tinted word keeps the tint on what was typed.
        val runs = listOf(StyleRun(5, 10, red))
        StyleRuns.shift(runs, at = 7, delta = 2) shouldBe listOf(StyleRun(5, 12, red))
    }

    @Test
    fun `deleting across a styled word shortens it`() {
        val runs = listOf(StyleRun(5, 15, red))
        // Four characters removed from index 10.
        StyleRuns.shift(runs, at = 10, delta = -4) shouldBe listOf(StyleRun(5, 11, red))
    }

    @Test
    fun `deleting a styled word entirely removes its run`() {
        val runs = listOf(StyleRun(5, 10, red))
        StyleRuns.shift(runs, at = 4, delta = -8) shouldBe emptyList()
    }

    @Test
    fun `an edit after every run leaves them all alone`() {
        val runs = listOf(StyleRun(0, 4, red), StyleRun(5, 9, blue))
        StyleRuns.shift(runs, at = 20, delta = 5) shouldBe runs
    }

    // ---- padded: the Persian guarantee ---------------------------------------------------------

    @Test
    fun `a cut inside a joined word is padded on both sides`() {
        // «کابینت» is two connected clusters — «کا» and «بینت», because ا joins only to its right.
        // Cutting «ین» out of the second one puts both cuts inside a join, so both get a zero-width
        // joiner and the shaper still produces medial forms.
        val text = "کابینت"
        val padded = StyleRuns.padded(text, 3, 5)
        padded.leading shouldBe 1
        padded.trailing shouldBe 1
        padded.text shouldBe "‍ین‍"
    }

    @Test
    fun `a cut where the script already breaks is left alone`() {
        // The same word, cut at the seam after the ا. Nothing joins across it, so padding there
        // would add a character to the string for no reason — and this is the case the ribbon's own
        // cluster selection produces, so it is the one that has to stay free.
        val text = "کابینت"
        StyleRuns.padded(text, 2, 6).hasPadding shouldBe false
    }

    @Test
    fun `a whole word needs no padding at all`() {
        // What the ribbon's own selection produces, and the reason the common case costs nothing:
        // the substring handed to the shaper is byte-for-byte the one it would have got anyway.
        val padded = StyleRuns.padded(sentence, word.first, word.last + 1)
        padded.hasPadding shouldBe false
        padded.text shouldBe "کابینت"
    }

    @Test
    fun `a cut after a right-joining letter needs no padding`() {
        // «دارد» breaks after the د and after the ر because those letters do not join forwards.
        // Padding there would be harmless but wrong to claim, and this pins the rule to the one
        // implementation of Persian joining rather than to a second guess at it.
        val text = "دارد"
        StyleRuns.padded(text, 1, 4).leading shouldBe 0
    }

    @Test
    fun `the ends of the string are never padded`() {
        val text = "کابینت"
        StyleRuns.padded(text, 0, text.length).hasPadding shouldBe false
    }
}
