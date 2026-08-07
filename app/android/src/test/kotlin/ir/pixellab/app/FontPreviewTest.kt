package ir.pixellab.app

import ir.pixellab.core.fonts.FontFile
import ir.pixellab.core.fonts.Script
import ir.pixellab.core.fonts.Typeface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Whether the font list shows you the fonts.
 *
 * ### The defect
 *
 * `TypefaceRow` drew its sample with `Text(face.previewText, fontSize = 22.sp)` and **no font
 * family**. Compose falls back to the ambient face when none is given, so every typeface in the
 * picker rendered in the *interface* font: twelve Persian designs previewed as twelve identical
 * lines of Vazirmatn, and the only way to find out what a face looked like was to apply it and
 * watch the canvas change. The list of fonts was the one screen in the application that could not
 * show a font.
 *
 * It is the same shape as every other defect in this repository's gap analysis — the model
 * expresses it, the renderer honours it, and the control never reaches it — and it is precisely
 * what "the text preview and the picture preview disagree" feels like from the outside: they *did*
 * disagree, because only one of them was reading the file.
 *
 * ### What is checked
 *
 * Two things, and the split is deliberate.
 *
 * The **choice of file** is a pure function and is tested as one, because it is the part that can
 * be subtly wrong in a way nobody notices: previewing a display family with its Black cut is not an
 * obviously broken screen, it is a screen that quietly misrepresents every face that has more than
 * one weight.
 *
 * The **family actually reaching the `Text`** cannot be tested here — building a `FontFamily` needs
 * a real file on disk and a Compose runtime, and this environment has neither, which is exactly how
 * the bug survived. So it is asserted against the source. A check that reads the code is worth less
 * than one that renders a pixel and considerably more than the nothing that was there before.
 */
class FontPreviewTest {

    private fun file(
        name: String,
        weight: Int,
        variable: Boolean = false,
    ) = FontFile(
        path = "/fonts/$name.ttf",
        family = "Dana",
        subfamily = name,
        postScriptName = "Dana-$name",
        fullName = "Dana $name",
        weight = weight,
        italic = false,
        axes = if (variable) mapOf("wght" to 100f..900f) else emptyMap(),
        script = Script.ARABIC,
    )

    @Test
    fun `a variable face previews from its variable file`() {
        // One file that can be any weight is the truthful representation of a variable design, and
        // it is also the file the kashida axis lives in — so previewing from it is the only way the
        // «کشیده» badge and the sample can be describing the same thing.
        val face = Typeface(
            name = "Dana",
            files = listOf(file("Black", 900), file("Variable", 400, variable = true), file("Regular", 400)),
        )
        assertEquals("/fonts/Variable.ttf", previewFile(face)?.path)
    }

    @Test
    fun `a static family previews from its regular cut, whatever order the scan returned`() {
        // The scan returns whatever the filesystem hands back. A family whose Black happened to
        // sort first previewed as a slab, and somebody choosing a text face would reject a design
        // for being too heavy when they had never been shown it.
        val face = Typeface(name = "Dana", files = listOf(file("Black", 900), file("Regular", 400)))
        assertEquals("/fonts/Regular.ttf", previewFile(face)?.path)
    }

    @Test
    fun `a family with no regular cut previews from its nearest weight rather than nothing`() {
        // Plenty of Persian display faces ship one cut at 700 and nothing else. Falling back to
        // null here would mean the picker silently returned to drawing that face in the interface
        // font — the original bug, reintroduced for the families most likely to be display type.
        val face = Typeface(name = "Titr", files = listOf(file("Bold", 700)))
        assertEquals("/fonts/Bold.ttf", previewFile(face)?.path)
    }

    @Test
    fun `a typeface with no files at all previews as nothing rather than throwing`() {
        // Reachable: a face is grouped from files that existed at scan time, and the user can
        // delete the folder from a file manager while the picker is open. A throw during
        // composition takes the whole sheet down instead of one row of it.
        assertNull(previewFile(Typeface(name = "Gone", files = emptyList())))
    }

    @Test
    fun `the sample is drawn in the face it names`() {
        val source = File("src/main/kotlin/ir/pixellab/app/FontPicker.kt")
            .takeIf { it.exists() }
            ?.readText()
            ?: error("FontPicker.kt not found; this test runs from the app module's directory")

        val row = source.substringAfter("private fun TypefaceRow(").substringBefore("\n@Composable")
        val sample = row.substringAfter("face.previewText,").substringBefore(")")

        assertTrue(
            "the typeface row no longer passes a fontFamily to its sample. Without one, Compose " +
                "falls back to the interface face and every font in the picker previews as the " +
                "same font — which is the whole bug this test exists for.",
            "fontFamily = family" in sample,
        )
        assertTrue(
            "the row no longer resolves a preview family from the face's own file, so the sample " +
                "and the canvas would stop reading the same bytes",
            "previewFamily(face)" in row,
        )
    }
}
