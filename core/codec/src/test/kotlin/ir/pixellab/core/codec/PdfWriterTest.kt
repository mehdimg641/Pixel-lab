package ir.pixellab.core.codec

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The single-page PDF writer.
 *
 * Two kinds of failure to guard against, and they behave very differently. A wrong page size still
 * opens and prints the design at the wrong physical dimensions, which nobody notices until it is on
 * paper. A wrong cross-reference offset does not open at all. Both are checked here, because a
 * visual inspection of the first is impossible and the second is invisible in a hex dump.
 */
class PdfWriterTest {

    private fun image(width: Int = 300, height: Int = 150, alpha: Int = 0xFF): RasterImage =
        RasterImage(width, height, IntArray(width * height) { (alpha shl 24) or 0x336699 })

    private fun text(bytes: ByteArray) = String(bytes, Charsets.ISO_8859_1)

    @Test
    fun `the output is a PDF a reader will recognise`() {
        val pdf = PdfWriter.write(image())
        text(pdf).startsWith("%PDF-1.4") shouldBe true
        text(pdf).trimEnd().endsWith("%%EOF") shouldBe true
    }

    @Test
    fun `the page is the artwork's real size in points`() {
        // The whole reason to support this format over a PNG. At 300 dpi a 300-pixel image is one
        // inch, and an inch is 72 points — get this wrong and a print shop produces the design at
        // the wrong size with no warning at all.
        val pdf = text(PdfWriter.write(image(width = 300, height = 150), dpi = 300))
        pdf.contains("/MediaBox [0 0 72 36]") shouldBe true
    }

    @Test
    fun `at 72 dpi a point is a pixel`() {
        val pdf = text(PdfWriter.write(image(width = 300, height = 150), dpi = 72))
        pdf.contains("/MediaBox [0 0 300 150]") shouldBe true
    }

    @Test
    fun `the cross-reference offsets point at the objects they claim to`() {
        // A reader seeks straight to these. One byte out is not a degraded render, it is a file
        // that does not open — and nothing in the bytes looks wrong.
        val pdf = PdfWriter.write(image())
        val body = text(pdf)

        val xrefAt = body.lastIndexOf("startxref")
        val declared = body.substring(xrefAt).lines()[1].trim().toInt()
        body.substring(declared).startsWith("xref") shouldBe true

        val table = body.substring(declared).lines()
        val count = table[1].trim().split(" ")[1].toInt()
        for (index in 1 until count) {
            // Two lines of preamble — "xref" and the range — and then object *zero*, which is
            // always the free-list head and never a real object. Real objects start at index 3.
            val offset = table[index + 2].trim().split(" ")[0].toInt()
            // Every entry must land exactly on its object's header.
            body.substring(offset).startsWith("$index 0 obj") shouldBe true
        }
    }

    @Test
    fun `an opaque image carries no soft mask`() {
        // A mask that says "fully opaque everywhere" is a second full-size image for no reason, and
        // on a cover that is megabytes.
        val pdf = text(PdfWriter.write(image(alpha = 0xFF)))
        pdf.contains("/SMask") shouldBe false
    }

    @Test
    fun `transparency becomes a separate greyscale mask`() {
        // A PDF has no fourth channel. A writer that packs alpha into the colour stream produces a
        // file every reader renders wrong — usually as a colour shift rather than as an error.
        val pdf = text(PdfWriter.write(image(alpha = 0x80)))
        pdf.contains("/SMask") shouldBe true
        pdf.contains("/DeviceGray") shouldBe true
    }

    @Test
    fun `the image is drawn scaled to the page`() {
        // A PDF image is always drawn into the unit square, so the transform in the content stream
        // *is* the placement. Without it the artwork appears as a single point.
        val pdf = text(PdfWriter.write(image(width = 300, height = 150), dpi = 72))
        pdf.contains("300 0 0 150 0 0 cm") shouldBe true
    }

    @Test
    fun `a title with brackets in it does not break the file`() {
        // Parentheses delimit a PDF string. An unescaped one ends the title early and the rest of
        // the object is parsed as syntax.
        val pdf = text(PdfWriter.write(image(), title = "طرح (نسخهٔ ۲)"))
        pdf.contains("\\(") shouldBe true
        pdf.contains("\\)") shouldBe true
    }

    @Test
    fun `an invalid resolution is refused rather than dividing by zero`() {
        runCatching { PdfWriter.write(image(), dpi = 0) }.isFailure shouldBe true
    }

    @Test
    fun `the image data is compressed`() {
        // A 300x150 image is 135 KB raw. Flate on flat colour is dramatic, and skipping it would
        // make a multi-megabyte file out of a design that is mostly one shade.
        val pdf = PdfWriter.write(image(width = 300, height = 150))
        text(pdf).contains("/FlateDecode") shouldBe true
        (pdf.size < 300 * 150 * 3) shouldBe true
    }
}
