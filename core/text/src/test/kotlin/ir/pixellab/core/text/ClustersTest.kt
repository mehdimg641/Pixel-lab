package ir.pixellab.core.text

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The connected cluster.
 *
 * The fixtures are the specification's own mandatory Persian test strings (§۹), not invented ones.
 * They were chosen there because each breaks a different naive implementation: one tests the
 * half-space, one tests a required ligature, one tests vowel marks, one tests bidirectional runs
 * with two kinds of digit.
 *
 * The assertions are written as *the pieces the text should come apart into*, spelled out, rather
 * than as counts. A count passing tells you almost nothing — «سلام» splitting into two pieces could
 * be «سلا»+«م», which is right, or «س»+«لام», which is wrong — and the spelled-out form is also the
 * only way a Persian reader can check the test itself.
 */
class ClustersTest {

    private fun pieces(text: String, granularity: Granularity = Granularity.CLUSTER): List<String> =
        Clusters.of(text, granularity).map { it.text }

    // ---- the rule ----------------------------------------------------------------------------

    @Test
    fun `salaam is two clusters and not four characters`() {
        // The example the specification leads with. Four characters, two connected pieces: the
        // seen-laam-alef draws joined, and the meem stands alone because alef does not join
        // forwards. Any tool that cuts this four ways has broken the word.
        pieces("سلام") shouldBe listOf("سلا", "م")
    }

    @Test
    fun `a right-joining letter ends the cluster it is in`() {
        // «دارد» — daal joins only backwards, so it ends a piece the moment it appears, and so does
        // the alef and the raa. Four letters, four pieces, and every one of them correct.
        pieces("دارد") shouldBe listOf("د", "ا", "ر", "د")
    }

    @Test
    fun `a half-space is the user's own break and is honoured`() {
        // The zero-width non-joiner is a decision, not whitespace. «می‌رود» is two pieces because
        // the writer said so — and shaping already agrees, which is why «میرود» without it looks
        // wrong to a Persian reader.
        pieces("می‌رود") shouldBe listOf("می", "‌", "ر", "و", "د")
        // Without it the yeh reaches the raa and «میر» draws joined. Everything after is unchanged,
        // because raa and waw do not join forwards — «رود» is three separate shapes either way.
        pieces("میرود") shouldBe listOf("میر", "و", "د")
    }

    @Test
    fun `the specification's half-space fixtures come apart at the half-spaces`() {
        // «می‌گرداندند» — the fixture §۹ names first.
        val pieces = pieces("می‌گرداندند")
        pieces.first() shouldBe "می"
        pieces.contains("‌") shouldBe true
        // Whatever else it does, it must not have merged across the half-space.
        pieces.none { it.startsWith("می") && it.length > 2 } shouldBe true
    }

    @Test
    fun `several half-spaces in one phrase each break`() {
        // «نمی‌شود بی‌نظیر» — two half-spaces and a real space.
        val text = "نمی‌شود بی‌نظیر"
        val joiners = Clusters.of(text).count { it.text == "‌" }
        joiners shouldBe 2
        // And the pieces still concatenate back to exactly what was typed.
        Clusters.of(text).joinToString("") { it.text } shouldBe text
    }

    // ---- marks and ligatures -----------------------------------------------------------------

    @Test
    fun `vowel marks stay with the letter they sit on`() {
        // «مُحَمَّد» — four letters carrying four marks. A mark is drawn above or below the line while
        // the join runs underneath it, so it must never start a piece of its own; a floating fatha
        // beside its letter is what happens when the break rule reads the string naively.
        for (piece in Clusters.of("مُحَمَّد")) {
            ArabicJoining.isTransparent(piece.text.first()) shouldBe false
        }
        Clusters.of("مُحَمَّد").joinToString("") { it.text } shouldBe "مُحَمَّد"
    }

    @Test
    fun `a required ligature is not split down its middle`() {
        // «لا» is drawn as one glyph — the laam-alef ligature is required, not optional — so a break
        // may never fall between its two characters. It does not, because laam joins forwards and
        // alef joins backwards, which is the same rule that produces the ligature.
        pieces("لا") shouldBe listOf("لا")

        // In the phrase §۹ names, every laam-alef survives whole.
        val phrase = pieces("لا اله الا الله")
        phrase shouldBe listOf("لا", " ", "ا", "له", " ", "ا", "لا", " ", "ا", "لله")
        // The alefs standing alone are the *definite article's*, each preceded by a space — a
        // different thing from the alef inside a ligature, and correctly its own piece because
        // nothing joins forwards into it.
    }

    // ---- mixed scripts and digits ------------------------------------------------------------

    @Test
    fun `latin inside persian does not fuse with it`() {
        // «KAR20 مدیا» — the specification's bidirectional fixture. Latin, digits and Persian are
        // three different things and a cluster may never span two of them.
        //
        // The Persian half is two pieces, not one: daal joins only backwards, so «مدیا» draws as
        // «مد» then «یا». Spelled out rather than counted, because "two pieces" would also be
        // satisfied by a wrong split.
        pieces("KAR20 مدیا") shouldBe listOf("K", "A", "R", "2", "0", " ", "مد", "یا")

        // No piece mixes the two scripts, which is the property that actually matters.
        Clusters.of("KAR20 مدیا").none { piece ->
            piece.text.any { ArabicJoining.isArabicLetter(it) } &&
                piece.text.any { it.isLetter() && !ArabicJoining.isArabicLetter(it) }
        } shouldBe true
    }

    @Test
    fun `a date keeps its digits out of the words around it`() {
        val text = "تاریخ ۱۴۰۵/۰۵/۱۱ ساعت 14:30"
        Clusters.of(text).joinToString("") { it.text } shouldBe text
        // No piece mixes an Arabic letter with a digit.
        Clusters.of(text).none { piece ->
            piece.text.any { ArabicJoining.isArabicLetter(it) } && piece.text.any { it.isDigit() }
        } shouldBe true
    }

    // ---- the other granularities ---------------------------------------------------------------

    @Test
    fun `words split at spaces and keep them`() {
        pieces("سلام دنیا", Granularity.WORD) shouldBe listOf("سلام", " ", "دنیا")
    }

    @Test
    fun `characters keep a letter and its mark together`() {
        // Even the granularity that exists for Latin cannot be a plain index walk: «مُ» is two chars
        // that have to move as one.
        pieces("مُحَمَّد", Granularity.CHARACTER).first() shouldBe "مُ"
    }

    @Test
    fun `everything reassembles into what was typed`() {
        // The property that makes indices usable at all. If any granularity dropped or duplicated a
        // character, a transform applied by index would land on the wrong letter.
        val fixtures = listOf(
            "می‌گرداندند",
            "نمی‌شود بی‌نظیر",
            "کتاب‌های دانش‌آموزان",
            "سلام",
            "لا اله الا الله",
            "مُحَمَّد",
            "تاریخ ۱۴۰۵/۰۵/۱۱ ساعت 14:30",
            "شماره تماس: +98 913 123 4567",
            "KAR20 مدیا",
            "قیمت: ۱٬۲۵۰٬۰۰۰ تومان",
            "کاربیست دیزاین",
        )
        for (text in fixtures) {
            for (granularity in Granularity.entries) {
                Clusters.of(text, granularity).joinToString("") { it.text } shouldBe text
            }
        }
    }

    @Test
    fun `ranges point back at the original text`() {
        val text = "کتاب‌های دانش‌آموزان"
        for (piece in Clusters.of(text)) {
            text.substring(piece.start, piece.end) shouldBe piece.text
        }
    }

    @Test
    fun `an empty string produces no pieces`() {
        for (granularity in Granularity.entries) Clusters.of("", granularity) shouldBe emptyList()
    }

    // ---- stretching --------------------------------------------------------------------------

    @Test
    fun `a joined cluster can be stretched and a lone letter cannot`() {
        // What the ribbon needs in order to decide whether a chip's edge may be dragged. «سلا» has
        // a join to stretch; a bare alef has none, and offering a handle on it would promise
        // something the script cannot do.
        val salaam = Clusters.of("سلام")
        salaam[0].stretchable shouldBe true
        salaam[1].stretchable shouldBe false
    }

    @Test
    fun `latin is never stretchable`() {
        Clusters.of("KAR20 مدیا").filter { !it.joined }.none { it.stretchable } shouldBe true
    }

    @Test
    fun `stretching one cluster leaves the rest of the text alone`() {
        val text = "سلام دنیا"
        val first = Clusters.of(text).first()
        val stretched = Clusters.elongate(text, first, amount = 2)

        // Longer, and only at the front.
        (stretched.length > text.length) shouldBe true
        stretched.endsWith("م دنیا") shouldBe true
        // And reversible, exactly.
        val again = Clusters.of(stretched).first()
        Clusters.resetElongation(stretched, again) shouldBe text
    }

    @Test
    fun `stretching an unstretchable cluster changes nothing`() {
        val text = "سلام"
        val meem = Clusters.of(text).last()
        Clusters.elongate(text, meem, amount = 3) shouldBe text
    }
}
