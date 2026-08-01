package ir.pixellab.core.text

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import ir.pixellab.core.model.FontRef
import ir.pixellab.core.model.KashidaMode
import ir.pixellab.core.model.ParagraphStyle
import ir.pixellab.core.model.TextAlign
import ir.pixellab.core.model.TextDirection
import org.junit.jupiter.api.Test

class ArabicJoiningTest {

    @Test
    fun `dual-joining letters accept elongation before them`() {
        // سلام: the gap before ل and before م are joinable, the one after ا is not.
        ArabicJoining.elongationPoints("سلام").isNotEmpty() shouldBe true
    }

    @Test
    fun `no tatweel is offered after a right-joining letter`() {
        // ا, د, ر, ز, و break the word — nothing may follow them on the baseline.
        for (word in listOf("اد", "ار", "دا", "رو", "وا")) {
            ArabicJoining.elongationPoints(word) shouldBe emptyList()
        }
    }

    @Test
    fun `elongation is refused for words that cannot stretch`() {
        ArabicJoining.elongate("داد", 6) shouldBe "داد"
        ArabicJoining.elongate("رود", 6) shouldBe "رود"
    }

    @Test
    fun `elongating adds exactly the requested number of tatweels`() {
        val out = ArabicJoining.elongate("سلام", 6)
        out.count { it == ArabicJoining.TATWEEL } shouldBe 6
        ArabicJoining.deElongate(out) shouldBe "سلام"
    }

    @Test
    fun `elongation spreads across every legal gap rather than piling into one`() {
        val word = "مستطیل"
        val points = ArabicJoining.elongationPoints(word)
        (points.size >= 2) shouldBe true
        val out = ArabicJoining.elongate(word, points.size)
        // One per gap means no run of two tatweels anywhere.
        out.contains("${ArabicJoining.TATWEEL}${ArabicJoining.TATWEEL}") shouldBe false
    }

    @Test
    fun `diacritics do not interrupt a join`() {
        // A fatha between two dual-joining letters must not hide the elongation point.
        ArabicJoining.elongationPoints("سَل").isNotEmpty() shouldBe true
        ArabicJoining.isTransparent('َ') shouldBe true
    }

    @Test
    fun `latin text offers no elongation points`() {
        ArabicJoining.elongationPoints("Hello") shouldBe emptyList()
        ArabicJoining.isArabicScript("Hello") shouldBe false
        ArabicJoining.isArabicScript("سلام") shouldBe true
    }

    @Test
    fun `round trip through elongation is lossless`() {
        for (word in listOf("سلام", "صنعت", "شرکت", "خودرو", "سوم")) {
            ArabicJoining.deElongate(ArabicJoining.elongate(word, 5)) shouldBe word
        }
    }
}

class KashidaPlannerTest {

    private val danaVf = FontRef("Dana", postScriptName = "DanaVF-Regular")
    private val peyda = FontRef("Peyda")

    private val withAxis = FontCapabilities(axes = mapOf("KASH" to 0f..100f))
    private val morabbaAxis = FontCapabilities(axes = mapOf("kash" to 0f..100f))
    private val noAxis = FontCapabilities()

    private fun style(mode: KashidaMode, amount: Float) =
        ParagraphStyle(kashida = mode, kashidaAmount = amount)

    @Test
    fun `a font with the axis drives it and never touches the text`() {
        val plan = KashidaPlanner.plan("سلام دنیا", danaVf, style(KashidaMode.AUTO, 0.65f), withAxis)
        plan.mode shouldBe KashidaMode.VARIABLE_AXIS
        plan.text shouldBe "سلام دنیا"
        plan.variations["KASH"] shouldBe 65f
        plan.preservesText shouldBe true
    }

    @Test
    fun `the lowercase axis tag is handled too`() {
        val plan = KashidaPlanner.plan("سلام", FontRef("Morabba"), style(KashidaMode.AUTO, 0.5f), morabbaAxis)
        plan.variations["kash"] shouldBe 50f
    }

    @Test
    fun `a font without the axis falls back to tatweel`() {
        val plan = KashidaPlanner.plan("سلام", peyda, style(KashidaMode.AUTO, 1f), noAxis)
        plan.mode shouldBe KashidaMode.TATWEEL
        plan.text shouldContain ArabicJoining.TATWEEL.toString()
        plan.preservesText shouldBe false
        ArabicJoining.deElongate(plan.text) shouldBe "سلام"
    }

    @Test
    fun `asking explicitly for the axis on a font without one does nothing rather than rewriting text`() {
        val plan = KashidaPlanner.plan("سلام", peyda, style(KashidaMode.VARIABLE_AXIS, 1f), noAxis)
        plan.mode shouldBe KashidaMode.NONE
        plan.text shouldBe "سلام"
    }

    @Test
    fun `latin text is never elongated`() {
        val plan = KashidaPlanner.plan("Hello", danaVf, style(KashidaMode.AUTO, 1f), withAxis)
        plan.mode shouldBe KashidaMode.NONE
        plan.text shouldBe "Hello"
    }

    @Test
    fun `zero amount is a no-op`() {
        KashidaPlanner.plan("سلام", danaVf, style(KashidaMode.AUTO, 0f), withAxis).mode shouldBe KashidaMode.NONE
    }

    @Test
    fun `word spacing is preserved when tatweel is used`() {
        val plan = KashidaPlanner.plan("صنعت خودرو", peyda, style(KashidaMode.AUTO, 0.5f), noAxis)
        plan.text.count { it == ' ' } shouldBe 1
        ArabicJoining.deElongate(plan.text) shouldBe "صنعت خودرو"
    }
}

class BidiTest {

    @Test
    fun `base direction comes from the first strong character`() {
        BidiAnalyzer.resolveBaseDirection("سلام", TextDirection.AUTO) shouldBe true
        BidiAnalyzer.resolveBaseDirection("Hello", TextDirection.AUTO) shouldBe false
        // Leading digits are neutral, so the Persian word decides.
        BidiAnalyzer.resolveBaseDirection("123 سلام", TextDirection.AUTO) shouldBe true
    }

    @Test
    fun `an explicit direction overrides detection`() {
        BidiAnalyzer.resolveBaseDirection("سلام", TextDirection.LTR) shouldBe false
        BidiAnalyzer.resolveBaseDirection("Hello", TextDirection.RTL) shouldBe true
    }

    @Test
    fun `uniform text resolves to a single run`() {
        BidiAnalyzer.runs("سلام دنیا", baseRtl = true) shouldHaveSize 1
        BidiAnalyzer.runs("Hello world", baseRtl = false) shouldHaveSize 1
    }

    @Test
    fun `mixed persian and latin splits into runs`() {
        // "سالن c3" from the reference screenshots: the Latin part must keep its own order.
        BidiAnalyzer.isMixed("سالن c3", baseRtl = true) shouldBe true
        val runs = BidiAnalyzer.runs("سالن c3", baseRtl = true)
        (runs.size >= 2) shouldBe true
        runs.any { !it.rightToLeft } shouldBe true
    }

    @Test
    fun `logical alignment maps onto the correct physical edge`() {
        BidiAnalyzer.physicalAlign(TextAlign.START, baseRtl = true) shouldBe PhysicalAlign.RIGHT
        BidiAnalyzer.physicalAlign(TextAlign.START, baseRtl = false) shouldBe PhysicalAlign.LEFT
        BidiAnalyzer.physicalAlign(TextAlign.END, baseRtl = true) shouldBe PhysicalAlign.LEFT
        BidiAnalyzer.physicalAlign(TextAlign.CENTER, baseRtl = true) shouldBe PhysicalAlign.CENTER
    }

    @Test
    fun `empty text yields no runs`() {
        BidiAnalyzer.runs("", baseRtl = true) shouldContainExactly emptyList()
    }
}

class DigitShaperTest {

    @Test
    fun `digits convert both ways`() {
        DigitShaper.toPersian("قیمت 250 تومان") shouldBe "قیمت ۲۵۰ تومان"
        DigitShaper.toLatin("قیمت ۲۵۰ تومان") shouldBe "قیمت 250 تومان"
    }

    @Test
    fun `arabic-indic digits normalise to latin too`() {
        DigitShaper.toLatin("٤٥٦") shouldBe "456"
    }

    @Test
    fun `non-digits are untouched`() {
        DigitShaper.toPersian("Hello") shouldBe "Hello"
    }
}
