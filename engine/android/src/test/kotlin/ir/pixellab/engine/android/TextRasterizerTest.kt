package ir.pixellab.engine.android

import io.kotest.matchers.shouldBe
import ir.pixellab.core.fonts.FontFile
import ir.pixellab.core.fonts.Script
import ir.pixellab.core.model.FontRef
import ir.pixellab.core.model.KashidaMode
import ir.pixellab.core.model.ParagraphStyle
import ir.pixellab.core.model.TextAlign
import ir.pixellab.core.model.TextDirection
import ir.pixellab.core.model.TextPath
import ir.pixellab.core.model.TextSpec
import ir.pixellab.core.model.Vec2
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs against Robolectric's native graphics backend, which is real Skia rather than a stub. Text
 * shaping, measurement and outline extraction are therefore genuinely exercised: a regression in
 * Persian joining would show up as a changed outline, not pass silently.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fa")
class TextRasterizerTest {

    private val rasterizer = TextRasterizer()

    /** A font entry with no file behind it; the rasterizer falls back to the system typeface. */
    private fun stubFont(
        family: String = "Stub",
        axes: Map<String, ClosedFloatingPointRange<Float>> = emptyMap(),
        persianDigits: Boolean = true,
        script: Script = Script.BOTH,
    ) = FontFile(
        path = "/nonexistent/$family.ttf",
        family = family,
        subfamily = "Regular",
        postScriptName = "$family-Regular",
        fullName = "$family Regular",
        weight = 400,
        italic = false,
        axes = axes,
        features = emptySet(),
        script = script,
        hasPersianDigits = persianDigits,
        hasTatweel = true,
    )

    private fun spec(
        text: String,
        size: Float = 64f,
        paragraph: ParagraphStyle = ParagraphStyle(),
        path: TextPath? = null,
        boxSize: Vec2? = null,
        font: FontRef = FontRef("Stub"),
    ) = TextSpec(text = text, font = font, size = size, paragraph = paragraph, path = path, boxSize = boxSize)

    @Test
    fun `latin text produces a non-empty outline`() {
        val out = rasterizer.rasterize(spec("Handgloves"), stubFont())
        out.outline.isEmpty shouldBe false
        (out.bounds.width() > 0f) shouldBe true
        (out.bounds.height() > 0f) shouldBe true
    }

    @Test
    fun `persian text produces a non-empty outline`() {
        val out = rasterizer.rasterize(spec("سلام دنیا"), stubFont())
        out.outline.isEmpty shouldBe false
        (out.bounds.width() > 0f) shouldBe true
    }

    @Test
    fun `a joined persian word is narrower than its letters spaced apart`() {
        // Joining is what makes the connected form compact. If shaping were bypassed and the
        // letters rendered in isolation, the run would be wider than the joined one.
        val joined = rasterizer.rasterize(spec("سلسل"), stubFont()).bounds.width()
        val separated = rasterizer.rasterize(spec("س ل س ل"), stubFont()).bounds.width()
        (joined < separated) shouldBe true
    }

    @Test
    fun `elongation widens the run without changing the text`() {
        val plain = rasterizer.rasterize(
            spec("سلام", paragraph = ParagraphStyle(kashida = KashidaMode.NONE)),
            stubFont(),
        )
        val stretched = rasterizer.rasterize(
            spec("سلام", paragraph = ParagraphStyle(kashida = KashidaMode.TATWEEL, kashidaAmount = 1f)),
            stubFont(),
        )
        stretched.kashida shouldBe KashidaMode.TATWEEL
        (stretched.bounds.width() > plain.bounds.width()) shouldBe true
    }

    @Test
    fun `the variable axis path leaves the string untouched`() {
        val font = stubFont(axes = mapOf("KASH" to 0f..100f))
        val out = rasterizer.rasterize(
            spec(
                "سلام",
                paragraph = ParagraphStyle(kashida = KashidaMode.AUTO, kashidaAmount = 0.6f),
                font = FontRef("Stub"),
            ),
            font,
        )
        out.kashida shouldBe KashidaMode.VARIABLE_AXIS
        out.textPreserved shouldBe true
        out.lines.single().text shouldBe "سلام"
    }

    @Test
    fun `tatweel mode reports that the text was modified`() {
        val out = rasterizer.rasterize(
            spec("سلام", paragraph = ParagraphStyle(kashida = KashidaMode.TATWEEL, kashidaAmount = 1f)),
            stubFont(),
        )
        out.textPreserved shouldBe false
        (out.lines.single().text.length > 4) shouldBe true
    }

    @Test
    fun `persian digits are substituted when the font provides them`() {
        val out = rasterizer.rasterize(
            spec("قیمت 250", paragraph = ParagraphStyle(persianDigits = true)),
            stubFont(persianDigits = true),
        )
        out.lines.single().text shouldBe "قیمت ۲۵۰"
    }

    @Test
    fun `digits are left alone when the font lacks persian numerals`() {
        val out = rasterizer.rasterize(
            spec("قیمت 250", paragraph = ParagraphStyle(persianDigits = true)),
            stubFont(persianDigits = false),
        )
        out.lines.single().text shouldBe "قیمت 250"
    }

    @Test
    fun `right-to-left start alignment pushes the line to the right edge`() {
        val box = Vec2(600f, 200f)
        val rtl = rasterizer.rasterize(
            spec("سلام", paragraph = ParagraphStyle(align = TextAlign.START, direction = TextDirection.RTL), boxSize = box),
            stubFont(),
        )
        val ltr = rasterizer.rasterize(
            spec("Hello", paragraph = ParagraphStyle(align = TextAlign.START, direction = TextDirection.LTR), boxSize = box),
            stubFont(),
        )
        (rtl.lines.single().x > 0f) shouldBe true
        ltr.lines.single().x shouldBe 0f
    }

    @Test
    fun `centring places the line symmetrically inside the box`() {
        val out = rasterizer.rasterize(
            spec("Hi", paragraph = ParagraphStyle(align = TextAlign.CENTER), boxSize = Vec2(400f, 100f)),
            stubFont(),
        )
        val line = out.lines.single()
        val rightGap = 400f - (line.x + line.width)
        (kotlin.math.abs(line.x - rightGap) < 1f) shouldBe true
    }

    @Test
    fun `multiple lines stack downwards by the line height`() {
        val out = rasterizer.rasterize(
            spec("یک\nدو\nسه", paragraph = ParagraphStyle(lineHeight = 1.5f)),
            stubFont(),
        )
        out.lines.size shouldBe 3
        val gap1 = out.lines[1].baseline - out.lines[0].baseline
        val gap2 = out.lines[2].baseline - out.lines[1].baseline
        (gap1 > 0f) shouldBe true
        (kotlin.math.abs(gap1 - gap2) < 0.01f) shouldBe true
    }

    @Test
    fun `larger text sizes produce proportionally larger outlines`() {
        val small = rasterizer.rasterize(spec("سلام", size = 32f), stubFont()).bounds.width()
        val large = rasterizer.rasterize(spec("سلام", size = 64f), stubFont()).bounds.width()
        val ratio = large / small
        (ratio > 1.8f && ratio < 2.2f) shouldBe true
    }

    @Test
    fun `bending onto an arc moves the outline off the straight baseline`() {
        val straight = rasterizer.rasterize(spec("سلام دنیا"), stubFont())
        val curved = rasterizer.rasterize(
            spec("سلام دنیا", path = TextPath.Arc(radius = 200f)),
            stubFont(),
        )
        curved.outline.isEmpty shouldBe false
        // A curved run occupies more vertical space than a flat one of the same string.
        (curved.bounds.height() > straight.bounds.height()) shouldBe true
    }

    @Test
    fun `a curved run keeps roughly the width of the straight one`() {
        // Warping the outline preserves arc length, so the run does not collapse or explode.
        val straight = rasterizer.rasterize(spec("Handgloves"), stubFont())
        val curved = rasterizer.rasterize(
            spec("Handgloves", path = TextPath.Arc(radius = 400f)),
            stubFont(),
        )
        val ratio = curved.bounds.width() / straight.bounds.width()
        (ratio > 0.7f && ratio < 1.4f) shouldBe true
    }

    @Test
    fun `empty text yields an empty outline rather than failing`() {
        val out = rasterizer.rasterize(spec(""), stubFont())
        out.outline.isEmpty shouldBe true
        out.lines.size shouldBe 1
    }

    @Test
    fun `mixed persian and latin lays out as one line`() {
        // "سالن c3" from the reference screenshots.
        val out = rasterizer.rasterize(spec("سالن c3"), stubFont())
        out.lines.size shouldBe 1
        (out.bounds.width() > 0f) shouldBe true
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TypefaceLoaderTest {

    private val loader = TypefaceLoader()

    private fun font(axes: Map<String, ClosedFloatingPointRange<Float>>) = FontFile(
        path = "/nonexistent/x.ttf",
        family = "X", subfamily = "Regular", postScriptName = "X-Regular", fullName = "X Regular",
        weight = 400, italic = false, axes = axes, features = emptySet(), script = Script.BOTH,
    )

    @Test
    fun `axis values are clamped to the ranges the font declares`() {
        val resolved = loader.resolveVariations(
            font(mapOf("wght" to 100f..900f, "KASH" to 0f..100f)),
            mapOf("wght" to 2000f, "KASH" to -50f),
        )
        resolved["wght"] shouldBe 900f
        resolved["KASH"] shouldBe 0f
    }

    @Test
    fun `axes the font does not declare are dropped`() {
        val resolved = loader.resolveVariations(font(mapOf("wght" to 100f..900f)), mapOf("KASH" to 50f))
        resolved.containsKey("KASH") shouldBe false
    }

    @Test
    fun `axis settings serialise in the format android expects`() {
        TypefaceLoader.format(mapOf("wght" to 700f, "KASH" to 65.5f)) shouldBe "'KASH' 65.5, 'wght' 700"
        TypefaceLoader.format(emptyMap()) shouldBe ""
    }

    @Test
    fun `feature settings serialise in the format paint expects`() {
        TypefaceLoader.formatFeatures(mapOf("ss01" to 1, "salt" to 1)) shouldBe "'salt' 1, 'ss01' 1"
    }

    @Test
    fun `the cache key separates two axis settings of the same file`() {
        val a = TypefaceLoader.cacheKey("/f.ttf", mapOf("KASH" to 0f))
        val b = TypefaceLoader.cacheKey("/f.ttf", mapOf("KASH" to 80f))
        (a == b) shouldBe false
        TypefaceLoader.cacheKey("/f.ttf", emptyMap()) shouldBe "/f.ttf"
    }

    @Test
    fun `a missing font file yields null rather than throwing`() {
        loader.load(font(emptyMap())) shouldBe null
    }
}
