package ir.pixellab.core.fonts

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Builds byte-exact OpenType tables so the parser is tested against the specification rather than
 * against whatever a particular font happens to contain.
 */
private class FontBuilder {
    private val tables = LinkedHashMap<String, ByteArray>()

    fun table(tag: String, bytes: ByteArray) = apply { tables[tag] = bytes }

    fun build(sfntVersion: Long = 0x00010000L): ByteArray {
        val n = tables.size
        val headerSize = 12 + n * 16
        var offset = headerSize
        val placed = tables.map { (tag, body) ->
            val at = offset
            offset += (body.size + 3) / 4 * 4
            Triple(tag, at, body)
        }
        val out = ByteArray(offset)
        writeU32(out, 0, sfntVersion)
        writeU16(out, 4, n)
        placed.forEachIndexed { i, (tag, at, body) ->
            val rec = 12 + i * 16
            for (c in 0 until 4) out[rec + c] = tag[c].code.toByte()
            writeU32(out, rec + 8, at.toLong())
            writeU32(out, rec + 12, body.size.toLong())
            body.copyInto(out, at)
        }
        return out
    }

    companion object {
        fun writeU16(a: ByteArray, at: Int, v: Int) {
            a[at] = (v ushr 8).toByte(); a[at + 1] = v.toByte()
        }

        fun writeU32(a: ByteArray, at: Int, v: Long) {
            a[at] = (v ushr 24).toByte(); a[at + 1] = (v ushr 16).toByte()
            a[at + 2] = (v ushr 8).toByte(); a[at + 3] = v.toByte()
        }

        fun writeFixed(a: ByteArray, at: Int, v: Float) {
            val fixed = (v * 65536f).toLong()
            writeU32(a, at, fixed and 0xFFFFFFFFL)
        }

        /** A `name` table carrying Windows/Unicode/English records. */
        fun nameTable(entries: Map<Int, String>): ByteArray {
            val count = entries.size
            val recordsSize = 6 + count * 12
            val strings = entries.values.map { s ->
                ByteArray(s.length * 2).also { b ->
                    s.forEachIndexed { i, c -> writeU16(b, i * 2, c.code) }
                }
            }
            val out = ByteArray(recordsSize + strings.sumOf { it.size })
            writeU16(out, 0, 0)
            writeU16(out, 2, count)
            writeU16(out, 4, recordsSize)
            var stringAt = 0
            entries.keys.forEachIndexed { i, nameId ->
                val rec = 6 + i * 12
                writeU16(out, rec, 3)       // platform: Windows
                writeU16(out, rec + 2, 1)   // encoding: Unicode BMP
                writeU16(out, rec + 4, 0x0409) // language: US English
                writeU16(out, rec + 6, nameId)
                writeU16(out, rec + 8, strings[i].size)
                writeU16(out, rec + 10, stringAt)
                strings[i].copyInto(out, recordsSize + stringAt)
                stringAt += strings[i].size
            }
            return out
        }

        fun os2Table(weight: Int, italic: Boolean): ByteArray {
            val out = ByteArray(96)
            writeU16(out, 0, 4)
            writeU16(out, 4, weight)
            writeU16(out, 62, if (italic) 0x01 else 0x40)
            return out
        }

        /** A format 4 cmap covering the given contiguous ranges. */
        fun cmap4(ranges: List<IntRange>): ByteArray {
            val segments = ranges.sortedBy { it.first } + listOf(0xFFFF..0xFFFF)
            val segCount = segments.size
            val subSize = 14 + segCount * 8 + 2
            val sub = ByteArray(subSize)
            writeU16(sub, 0, 4)
            writeU16(sub, 2, subSize)
            writeU16(sub, 4, 0)
            writeU16(sub, 6, segCount * 2)
            val endAt = 14
            val startAt = endAt + segCount * 2 + 2
            val deltaAt = startAt + segCount * 2
            val rangeAt = deltaAt + segCount * 2
            segments.forEachIndexed { i, seg ->
                writeU16(sub, endAt + i * 2, seg.last)
                writeU16(sub, startAt + i * 2, seg.first)
                // Delta 1 maps every character to a non-zero glyph id.
                writeU16(sub, deltaAt + i * 2, if (seg.first == 0xFFFF) 1 else 1)
                writeU16(sub, rangeAt + i * 2, 0)
            }

            val out = ByteArray(4 + 8 + sub.size)
            writeU16(out, 0, 0)
            writeU16(out, 2, 1)
            writeU16(out, 4, 3)      // Windows
            writeU16(out, 6, 1)      // Unicode BMP
            writeU32(out, 8, 12L)
            sub.copyInto(out, 12)
            return out
        }

        fun fvarTable(axes: List<Triple<String, Float, Float>>): ByteArray {
            val axisSize = 20
            val axesOffset = 16
            val out = ByteArray(axesOffset + axes.size * axisSize)
            writeU16(out, 0, 1)
            writeU16(out, 2, 0)
            writeU16(out, 4, axesOffset)
            writeU16(out, 6, 2)
            writeU16(out, 8, axes.size)
            writeU16(out, 10, axisSize)
            axes.forEachIndexed { i, (tag, min, max) ->
                val rec = axesOffset + i * axisSize
                for (c in 0 until 4) out[rec + c] = tag[c].code.toByte()
                writeFixed(out, rec + 4, min)
                writeFixed(out, rec + 8, min)
                writeFixed(out, rec + 12, max)
            }
            return out
        }

        fun gsubTable(features: List<String>): ByteArray {
            val featureListAt = 10
            val out = ByteArray(featureListAt + 2 + features.size * 6)
            writeU16(out, 0, 1)
            writeU16(out, 2, 0)
            writeU16(out, 4, 0)
            writeU16(out, 6, featureListAt)
            writeU16(out, 8, 0)
            writeU16(out, featureListAt, features.size)
            features.forEachIndexed { i, tag ->
                val rec = featureListAt + 2 + i * 6
                for (c in 0 until 4) out[rec + c] = tag[c].code.toByte()
                writeU16(out, rec + 4, 0)
            }
            return out
        }

        fun maxpTable(glyphs: Int): ByteArray = ByteArray(6).also {
            writeU32(it, 0, 0x00010000L)
            writeU16(it, 4, glyphs)
        }
    }
}

private fun persianFont(
    family: String = "Dana",
    weight: Int = 400,
    axes: List<Triple<String, Float, Float>> = emptyList(),
    features: List<String> = emptyList(),
    withLatin: Boolean = true,
    withPersianDigits: Boolean = true,
): ByteArray {
    val ranges = buildList {
        add(0x0620..0x064A)          // Arabic letters
        add(0x0640..0x0640)          // tatweel
        add(0x067E..0x06CC)          // Persian additions
        add(0x200C..0x200C)          // ZWNJ
        if (withPersianDigits) add(0x06F0..0x06F9)
        if (withLatin) { add(0x41..0x5A); add(0x61..0x7A) }
    }
    val b = FontBuilder()
        .table("OS/2", FontBuilder.os2Table(weight, italic = false))
        .table("cmap", FontBuilder.cmap4(ranges))
        .table("maxp", FontBuilder.maxpTable(512))
        .table(
            "name",
            FontBuilder.nameTable(
                mapOf(1 to family, 2 to "Regular", 4 to "$family Regular", 6 to "$family-Regular"),
            ),
        )
    if (axes.isNotEmpty()) b.table("fvar", FontBuilder.fvarTable(axes))
    if (features.isNotEmpty()) b.table("GSUB", FontBuilder.gsubTable(features))
    return b.build()
}

class OpenTypeParserTest {

    @Test
    fun `names weight and glyph count are read`() {
        val font = OpenTypeParser.parse(persianFont(family = "Kalameh", weight = 700), "/f.ttf")
        font.shouldNotBeNull()
        font.family shouldBe "Kalameh"
        font.subfamily shouldBe "Regular"
        font.postScriptName shouldBe "Kalameh-Regular"
        font.fullName shouldBe "Kalameh Regular"
        font.weight shouldBe 700
        font.glyphCount shouldBe 512
        font.path shouldBe "/f.ttf"
    }

    @Test
    fun `script comes from glyph coverage not from the file name`() {
        OpenTypeParser.parse(persianFont(withLatin = true), "/a.ttf")!!.script shouldBe Script.BOTH
        OpenTypeParser.parse(persianFont(withLatin = false), "/b.ttf")!!.script shouldBe Script.ARABIC
    }

    @Test
    fun `a numerals-only font is not reported as usable for text`() {
        // The supplied library contains six of these; letting one into a picker renders boxes.
        val digitsOnly = FontBuilder()
            .table("OS/2", FontBuilder.os2Table(400, false))
            .table("cmap", FontBuilder.cmap4(listOf(0x06F0..0x06F9, 0x30..0x39)))
            .table("name", FontBuilder.nameTable(mapOf(1 to "IRANSansOnlyNumeral", 2 to "Regular")))
            .build()
        val font = OpenTypeParser.parse(digitsOnly, "/n.ttf")
        font.shouldNotBeNull()
        font.script shouldBe Script.NEITHER
        font.hasPersianDigits shouldBe true
    }

    @Test
    fun `variable axes are read with their ranges`() {
        val font = OpenTypeParser.parse(
            persianFont(axes = listOf(Triple("wght", 10f, 990f), Triple("KASH", 0f, 100f))),
            "/v.ttf",
        )
        font.shouldNotBeNull()
        font.isVariable shouldBe true
        font.axes.keys shouldContain "KASH"
        font.axes.getValue("wght").start shouldBe 10f
        font.axes.getValue("wght").endInclusive shouldBe 990f
        font.axes.getValue("KASH").endInclusive shouldBe 100f
        font.hasKashidaAxis shouldBe true
    }

    @Test
    fun `the lowercase kashida axis is recognised too`() {
        val font = OpenTypeParser.parse(
            persianFont(family = "Morabba", axes = listOf(Triple("kash", 0f, 100f))),
            "/m.ttf",
        )
        font.shouldNotBeNull().hasKashidaAxis shouldBe true
    }

    @Test
    fun `stylistic sets are extracted from GSUB`() {
        val font = OpenTypeParser.parse(
            persianFont(features = listOf("init", "medi", "fina", "ss01", "ss02", "salt")),
            "/s.ttf",
        )
        font.shouldNotBeNull()
        font.features shouldContain "ss01"
        font.features shouldContain "salt"
        FontGrouper.group(listOf(font)).single().stylisticSets shouldBe listOf("salt", "ss01", "ss02")
    }

    @Test
    fun `tatweel and persian digit coverage are detected`() {
        OpenTypeParser.parse(persianFont(), "/t.ttf")!!.hasTatweel shouldBe true
        OpenTypeParser.parse(persianFont(withPersianDigits = false), "/u.ttf")!!
            .hasPersianDigits shouldBe false
    }

    @Test
    fun `CFF-flavoured fonts parse the same way`() {
        val otto = FontBuilder()
            .table("OS/2", FontBuilder.os2Table(400, false))
            .table("cmap", FontBuilder.cmap4(listOf(0x41..0x5A, 0x61..0x7A)))
            .table("name", FontBuilder.nameTable(mapOf(1 to "Morabba", 2 to "Regular")))
            .build(sfntVersion = 0x4F54544FL) // 'OTTO'
        OpenTypeParser.parse(otto, "/o.otf").shouldNotBeNull().family shouldBe "Morabba"
    }

    @Test
    fun `non-font data is rejected rather than throwing`() {
        OpenTypeParser.parse(ByteArray(0), "/empty") shouldBe null
        OpenTypeParser.parse(ByteArray(64) { 0x7F }, "/junk") shouldBe null
        OpenTypeParser.parse("not a font at all".toByteArray(), "/text") shouldBe null
    }

    @Test
    fun `a truncated file does not crash the parser`() {
        val full = persianFont()
        for (cut in listOf(4, 12, 40, full.size / 2, full.size - 1)) {
            runCatching { OpenTypeParser.parse(full.copyOf(cut), "/cut") }.isSuccess shouldBe true
        }
    }

    @Test
    fun `a font missing its name table is skipped`() {
        val noName = FontBuilder()
            .table("OS/2", FontBuilder.os2Table(400, false))
            .table("cmap", FontBuilder.cmap4(listOf(0x41..0x5A)))
            .build()
        OpenTypeParser.parse(noName, "/x.ttf") shouldBe null
    }

    @Test
    fun `a missing OS2 table falls back to a sane weight`() {
        val noOs2 = FontBuilder()
            .table("cmap", FontBuilder.cmap4(listOf(0x41..0x5A, 0x61..0x7A)))
            .table("name", FontBuilder.nameTable(mapOf(1 to "Javan", 2 to "Bold")))
            .build()
        val font = OpenTypeParser.parse(noOs2, "/j.ttf")
        font.shouldNotBeNull().weight shouldBe 400
        font.subfamily shouldBe "Bold"
    }
}

class FontScannerTest {

    @Test
    fun `unreadable and malformed files are reported without stopping the scan`() {
        val good = persianFont(family = "Dana")
        val scanner = FontScanner { path ->
            when (path) {
                "/ok.ttf" -> good
                "/broken.ttf" -> ByteArray(32) { 0x11 }
                else -> null
            }
        }
        val result = scanner.scan(listOf("/ok.ttf", "/broken.ttf", "/gone.ttf"))
        result.fonts.size shouldBe 1
        result.failed shouldBe listOf("/broken.ttf", "/gone.ttf")
        result.catalog.size shouldBe 1
    }

    @Test
    fun `the scan feeds straight into a catalogue`() {
        val scanner = FontScanner { path ->
            when (path) {
                "/dana.ttf" -> persianFont(family = "Dana", weight = 400)
                "/dana-bold.ttf" -> persianFont(family = "Dana", weight = 700)
                "/danafanum.ttf" -> persianFont(family = "DanaFaNum", weight = 400)
                else -> null
            }
        }
        val catalog = scanner.scan(listOf("/dana.ttf", "/dana-bold.ttf", "/danafanum.ttf")).catalog
        catalog.fileCount shouldBe 3
        // All three collapse into one typeface, which is the whole point of grouping.
        catalog.size shouldBe 1
        catalog.typefaces.single().weights shouldBe listOf(400, 700)
    }

    @Test
    fun `font extensions are recognised case-insensitively`() {
        FontScanner.isFontFile("/a/B.TTF") shouldBe true
        FontScanner.isFontFile("/a/b.otf") shouldBe true
        FontScanner.isFontFile("/a/b.ttc") shouldBe true
        FontScanner.isFontFile("/a/b.png") shouldBe false
        FontScanner.isFontFile("/a/b") shouldBe false
    }
}
