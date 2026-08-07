package ir.pixellab.core.codec

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

/**
 * Writes a single-page PDF holding one image.
 *
 * Small on purpose. A cover is one page and a full PDF library — fonts, vector content, colour
 * profiles — is a large dependency for something whose entire job here is "put this picture on a
 * page of the right physical size". What a PDF buys over a PNG is exactly that: a page has real
 * dimensions in points, so a print shop receives a file that says how big the artwork is instead of
 * a pixel count they have to be told the resolution of.
 *
 * The page size comes from the image's pixels and its DPI. That relationship is the whole reason
 * this format is worth supporting, and getting it wrong prints the design at the wrong size — which
 * is a mistake nobody notices until it is on paper.
 */
object PdfWriter {

    /**
     * @param dpi pixels per inch. 72 makes a point equal a pixel; 300 is what a printer expects.
     * @param quality unused for now — the image is written with lossless Flate rather than DCT,
     *   because a design's flat colour and sharp type are exactly what JPEG damages most.
     */
    fun write(image: RasterImage, dpi: Int = 72, title: String = ""): ByteArray {
        require(dpi > 0) { "dpi must be positive, got $dpi" }

        // A point is 1/72 inch, always. The page is the artwork's real size.
        val widthPoints = image.width * POINTS_PER_INCH / dpi
        val heightPoints = image.height * POINTS_PER_INCH / dpi

        val rgb = ByteArray(image.width * image.height * 3)
        val alpha = ByteArray(image.width * image.height)
        var hasTransparency = false
        for (i in image.pixels.indices) {
            val pixel = image.pixels[i]
            val a = (pixel ushr 24) and 0xFF
            if (a != 255) hasTransparency = true
            rgb[i * 3] = ((pixel shr 16) and 0xFF).toByte()
            rgb[i * 3 + 1] = ((pixel shr 8) and 0xFF).toByte()
            rgb[i * 3 + 2] = (pixel and 0xFF).toByte()
            alpha[i] = a.toByte()
        }

        val objects = ArrayList<ByteArray>()
        // Object numbering is one-based and the cross-reference table below depends on the order
        // these are appended in, so they are built in exactly the order they are written.
        objects += ascii("<< /Type /Catalog /Pages 2 0 R >>")
        objects += ascii("<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
        objects += ascii(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $widthPoints $heightPoints] " +
                "/Resources << /XObject << /Im0 5 0 R >> >> /Contents 4 0 R >>",
        )

        // The content stream: scale the unit image up to the page and draw it. A PDF image is
        // always drawn into the unit square, so the transform *is* the placement.
        val content = "q\n$widthPoints 0 0 $heightPoints 0 0 cm\n/Im0 Do\nQ\n"
        objects += stream("<< /Length ${content.length} >>", content.toByteArray(Charsets.US_ASCII))

        val smaskRef = if (hasTransparency) " /SMask 6 0 R" else ""
        objects += stream(
            "<< /Type /XObject /Subtype /Image /Width ${image.width} /Height ${image.height} " +
                "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /FlateDecode$smaskRef " +
                "/Length @LENGTH@ >>",
            deflate(rgb),
        )
        if (hasTransparency) {
            // Transparency in a PDF is a separate greyscale image, not a fourth channel. A writer
            // that packs alpha into the colour stream produces a file every reader renders wrong.
            objects += stream(
                "<< /Type /XObject /Subtype /Image /Width ${image.width} /Height ${image.height} " +
                    "/ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /FlateDecode " +
                    "/Length @LENGTH@ >>",
                deflate(alpha),
            )
        }
        if (title.isNotBlank()) objects += ascii("<< /Title (${escape(title)}) >>")

        return assemble(objects, infoObject = if (title.isNotBlank()) objects.size else 0)
    }

    /**
     * Lays the objects out and builds the cross-reference table.
     *
     * The offsets have to be byte-exact. A reader seeks straight to them, so an offset that is one
     * byte out does not degrade the rendering — it fails to open the file at all.
     */
    private fun assemble(objects: List<ByteArray>, infoObject: Int): ByteArray {
        val out = ByteArrayOutputStream()
        // The binary comment on the second line marks the file as containing binary data, so a
        // transfer that would otherwise "helpfully" convert line endings leaves it alone.
        out.write("%PDF-1.4\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1))

        val offsets = IntArray(objects.size + 1)
        for ((index, body) in objects.withIndex()) {
            offsets[index + 1] = out.size()
            out.write("${index + 1} 0 obj\n".toByteArray(Charsets.US_ASCII))
            out.write(body)
            out.write("\nendobj\n".toByteArray(Charsets.US_ASCII))
        }

        val xref = out.size()
        out.write("xref\n0 ${objects.size + 1}\n".toByteArray(Charsets.US_ASCII))
        // The free-list head, and its exact spelling matters: every entry in this table is twenty
        // bytes wide including the line ending, and a reader indexes into it arithmetically.
        out.write("0000000000 65535 f \n".toByteArray(Charsets.US_ASCII))
        for (index in 1..objects.size) {
            out.write(String.format("%010d 00000 n \n", offsets[index]).toByteArray(Charsets.US_ASCII))
        }

        val info = if (infoObject > 0) " /Info $infoObject 0 R" else ""
        out.write(
            ("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R$info >>\nstartxref\n$xref\n%%EOF\n")
                .toByteArray(Charsets.US_ASCII),
        )
        return out.toByteArray()
    }

    private fun stream(dictionary: String, data: ByteArray): ByteArray {
        val header = dictionary.replace("@LENGTH@", data.size.toString())
        val out = ByteArrayOutputStream()
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write("\nstream\n".toByteArray(Charsets.US_ASCII))
        out.write(data)
        out.write("\nendstream".toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size / 2)
        val buffer = ByteArray(1 shl 16)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    /** Parentheses and backslashes delimit a PDF string, so a title containing one must escape it. */
    private fun escape(text: String) = text
        .replace("\\", "\\\\")
        .replace("(", "\\(")
        .replace(")", "\\)")

    private const val POINTS_PER_INCH = 72
}
