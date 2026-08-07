package ir.pixellab.core.fonts

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.FontRef
import org.junit.jupiter.api.Test

/**
 * Fixtures use the real family names read out of the supplied library with fontTools, so the
 * grouping rules are exercised against the names that actually occur rather than tidy invented ones.
 */
private object Library {

    /** Every distinct `name` ID 1 in the 312 supplied files. */
    val REAL_FAMILIES = listOf(
        "Dana", "Dana-FaNum", "Dana2_Adobe", "Dana2_Variable", "Dana_Adobe", "Dana_Variable",
        "DanaFaNum", "DanaNoEn", "DanaVF",
        "Fedra Arabic Display AR+LT",
        "IRAN SansMobileNoEn", "Irancell", "Irancell_SemiBold",
        "IRANSans", "IRANSansDN", "IRANSansDNFaNum", "IRANSansFaNum", "IRANSansMobile",
        "IRANSansMobile(FaNum)", "IRANSansMobileFaNum", "IRANSansMobileNoEn",
        "IRANSansMonoSpacedNum", "IRANSansOnlyNumeral", "IRANSansOnlyNumral", "IRANSansSmall",
        "IRANSansWeb", "IRANSansWebFaNum", "IRANSansWebNoEn",
        "IRANSansX", "IRANSansXFaNum", "IRANSansXNoEn", "IRANSansXV",
        "IRANYekan", "Javan", "Kalameh", "Kalameh(FaNum)", "Kalameh(NoEn)",
        "Morabba", "MorabbaVF", "Pelak FA", "Peyda", "Ray",
    )

    fun file(
        family: String,
        weight: Int = 400,
        axes: Map<String, ClosedFloatingPointRange<Float>> = emptyMap(),
        features: Set<String> = emptySet(),
        script: Script = Script.BOTH,
        ps: String = "$family-${weight}",
    ) = FontFile(
        path = "/fonts/$ps.ttf",
        family = family,
        subfamily = "Regular",
        postScriptName = ps,
        fullName = "$family Regular",
        weight = weight,
        italic = false,
        axes = axes,
        features = features,
        script = script,
        hasPersianDigits = true,
    )

    fun wholeLibrary(): List<FontFile> = REAL_FAMILIES.flatMap { family ->
        listOf(300, 400, 700).map { w -> file(family, w, ps = "$family-$w") }
    }
}

class FontGrouperTest {

    @Test
    fun `the 312-file library collapses to a short list of typefaces`() {
        val faces = FontGrouper.group(Library.wholeLibrary())
        // 45 raw family names, 12 real designs. Allow a little slack for names we deliberately keep
        // apart, but the picker must never be anywhere near the raw count.
        (faces.size <= 15) shouldBe true
        faces.map { it.name } shouldContainAll listOf("Dana", "IRANSans", "IRANSansX", "Kalameh", "Morabba")
    }

    @Test
    fun `every Dana variant lands in one typeface`() {
        val danaNames = listOf(
            "Dana", "Dana-FaNum", "DanaFaNum", "DanaNoEn", "DanaVF", "Dana_Variable", "Dana_Adobe",
        )
        danaNames.map { FontGrouper.groupKey(it) }.distinct() shouldBe listOf("dana")
    }

    @Test
    fun `spacing differences do not split a family`() {
        FontGrouper.groupKey("IRAN SansMobileNoEn") shouldBe FontGrouper.groupKey("IRANSans")
    }

    @Test
    fun `parenthesised qualifiers are stripped`() {
        FontGrouper.groupKey("Kalameh(FaNum)") shouldBe "kalameh"
        FontGrouper.groupKey("Kalameh(NoEn)") shouldBe "kalameh"
    }

    @Test
    fun `weight words are stripped from the family name`() {
        FontGrouper.groupKey("Irancell_SemiBold") shouldBe "irancell"
        FontGrouper.groupKey("Peyda Black") shouldBe "peyda"
        FontGrouper.groupKey("Dana ExtraBlack") shouldBe "dana"
    }

    @Test
    fun `IRANSans and IRANSansX stay apart because they are different designs`() {
        val a = FontGrouper.groupKey("IRANSansWeb")
        val b = FontGrouper.groupKey("IRANSansXFaNum")
        (a == b) shouldBe false
    }

    @Test
    fun `a variable cut folds into its base family only when the base exists`() {
        // IRANSansXV belongs with IRANSansX.
        val withBase = FontGrouper.group(
            listOf(Library.file("IRANSansX"), Library.file("IRANSansXV", axes = mapOf("wght" to 100f..900f))),
        )
        withBase.size shouldBe 1

        // A standalone name ending in V must not be mangled into something shorter.
        val alone = FontGrouper.group(listOf(Library.file("Chevronv")))
        alone.single().name shouldBe "Chevronv"
    }
}

class TypefaceTest {

    @Test
    fun `a typeface reports the kashida axis when any file carries it`() {
        val face = FontGrouper.group(
            listOf(
                Library.file("Dana", 400),
                Library.file("DanaVF", axes = mapOf("wght" to 10f..990f, "KASH" to 0f..100f)),
            ),
        ).single()
        face.hasKashidaAxis shouldBe true
        face.variableFile.shouldNotBeNull()
    }

    @Test
    fun `stylistic sets are collected across the family`() {
        val face = FontGrouper.group(
            listOf(
                Library.file("Kalameh", 400, features = setOf("ss01", "calt")),
                Library.file("Kalameh", 700, features = setOf("ss02", "salt")),
            ),
        ).single()
        face.stylisticSets shouldBe listOf("salt", "ss01", "ss02")
    }

    @Test
    fun `a variable file answers any weight inside its range`() {
        val face = FontGrouper.group(
            listOf(Library.file("Dana", axes = mapOf(FontRef.AXIS_WEIGHT to 10f..900f))),
        ).single()
        face.resolve(register(650)).shouldNotBeNull().isVariable shouldBe true
    }

    @Test
    fun `static files resolve to the nearest available weight`() {
        val face = FontGrouper.group(
            listOf(Library.file("Peyda", 300), Library.file("Peyda", 700), Library.file("Peyda", 900)),
        ).single()
        face.resolve(register(650))!!.weight shouldBe 700
        face.resolve(register(100))!!.weight shouldBe 300
    }

    @Test
    fun `preview text matches the script the font can render`() {
        val arabic = FontGrouper.group(listOf(Library.file("Dana", script = Script.ARABIC))).single()
        val latin = FontGrouper.group(listOf(Library.file("Anton", script = Script.LATIN))).single()
        arabic.previewText shouldBe "آفتاب ۱۳۴"
        latin.previewText shouldBe "Handgloves 134"
    }

    private fun register(weight: Int) = weight
}

class FontCatalogTest {

    private val danaVf = Library.file(
        "DanaVF",
        axes = mapOf(FontRef.AXIS_WEIGHT to 10f..990f, "KASH" to 0f..100f),
        ps = "DanaVF-Regular",
    )
    private val catalog = FontCatalog(
        listOf(
            Library.file("Dana", 400, ps = "Dana-Regular"),
            Library.file("Dana", 700, ps = "Dana-Bold"),
            danaVf,
            Library.file("Peyda", 400, ps = "Peyda-Regular"),
            Library.file("Anton", 700, script = Script.LATIN, ps = "Anton-Regular"),
            Library.file("IRANSansOnlyNumeral", 400, script = Script.NEITHER, ps = "IRANSansOnlyNumeral"),
        ),
    )

    @Test
    fun `an exact PostScript name wins`() {
        val r = catalog.resolve(FontRef("Dana", postScriptName = "Dana-Bold"))
        r.match shouldBe FontMatch.EXACT
        r.file!!.weight shouldBe 700
        r.isDegraded shouldBe false
    }

    @Test
    fun `a moved file degrades to the same family instead of failing`() {
        val r = catalog.resolve(FontRef("Dana", postScriptName = "Dana-DeletedByUser", weight = 700))
        r.file.shouldNotBeNull()
        r.match shouldBe FontMatch.SAME_FAMILY
        // The project still opens; that is the whole point.
        r.file!!.family.startsWith("Dana") shouldBe true
    }

    @Test
    fun `an unknown family substitutes and warns rather than throwing`() {
        val r = catalog.resolve(FontRef("SomeFontIDeleted", weight = 400))
        r.match shouldBe FontMatch.SUBSTITUTED
        r.file.shouldNotBeNull()
        r.warning.shouldNotBeNull().contains("SomeFontIDeleted") shouldBe true
        r.isDegraded shouldBe true
    }

    @Test
    fun `an empty catalogue still returns a resolution rather than crashing`() {
        val r = FontCatalog(emptyList()).resolve(FontRef("Dana"))
        r.match shouldBe FontMatch.MISSING
        r.file shouldBe null
        r.warning.shouldNotBeNull()
    }

    @Test
    fun `search matches on family name case-insensitively`() {
        catalog.search("dana").map { it.name } shouldContain "Dana"
        catalog.search("DANA").isNotEmpty() shouldBe true
        catalog.search("").size shouldBe catalog.size
        catalog.search("nothinglikethis") shouldBe emptyList()
    }

    @Test
    fun `usableFor keeps a numerals-only font out of a persian headline`() {
        val usable = catalog.usableFor("سلام").map { it.name }
        usable shouldContain "Dana"
        (usable.contains("IRANSans")) shouldBe false
    }

    @Test
    fun `usableFor excludes an arabic-only face from latin text`() {
        val arabicOnly = FontCatalog(listOf(Library.file("Vazir", script = Script.ARABIC)))
        arabicOnly.usableFor("Hello") shouldBe emptyList()
        arabicOnly.usableFor("سلام").size shouldBe 1
    }

    @Test
    fun `the catalogue reports both file and typeface counts`() {
        catalog.fileCount shouldBe 6
        (catalog.size < catalog.fileCount) shouldBe true
    }
}
