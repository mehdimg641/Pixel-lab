package ir.pixellab.core.codec

import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.abs

class CubeLutTest {

    /** The strip's texel for one input colour, decoded back to 0..255 per channel. */
    private fun RasterImage.lookup(r: Int, g: Int, b: Int): Triple<Int, Int, Int> {
        val x = (b % CubeLut.SLICES_PER_ROW) * CubeLut.SLICES + r
        val y = (b / CubeLut.SLICES_PER_ROW) * CubeLut.SLICES + g
        val pixel = pixels[y * CubeLut.STRIP + x]
        return Triple((pixel shr 16) and 0xFF, (pixel shr 8) and 0xFF, pixel and 0xFF)
    }

    /** An n-level identity cube, written the way the format specifies: red varies fastest. */
    private fun identity(size: Int, header: String = ""): ByteArray {
        val text = StringBuilder()
        if (header.isNotEmpty()) text.append(header).append('\n')
        text.append("LUT_3D_SIZE $size\n")
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) {
                    text.append("${r / (size - 1f)} ${g / (size - 1f)} ${b / (size - 1f)}\n")
                }
            }
        }
        return text.toString().toByteArray()
    }

    @Test
    fun `an identity cube maps every colour to itself`() {
        val strip = CubeLut.read(identity(CubeLut.SLICES))!!
        strip.width shouldBe CubeLut.STRIP
        strip.height shouldBe CubeLut.STRIP

        for ((r, g, b) in listOf(Triple(0, 0, 0), Triple(63, 63, 63), Triple(10, 40, 55), Triple(63, 0, 31))) {
            val (or, og, ob) = strip.lookup(r, g, b)
            val expected = { v: Int -> (v / 63f * 255f + 0.5f).toInt() }
            abs(or - expected(r)) shouldBeGreaterThan -1
            check(abs(or - expected(r)) <= 1 && abs(og - expected(g)) <= 1 && abs(ob - expected(b)) <= 1) {
                "identity moved ($r,$g,$b) to ($or,$og,$ob), wanted (${expected(r)},${expected(g)},${expected(b)})"
            }
        }
    }

    @Test
    fun `red varies fastest, which is what tells a grade from its mirror image`() {
        // The one ordering mistake that still produces a plausible picture: reading the entries as
        // blue-fastest mirrors the whole grade about its neutral axis. A table that maps pure red to
        // pure red and pure blue to pure blue cannot be satisfied by the wrong order.
        val strip = CubeLut.read(identity(17))!!
        val (r, _, _) = strip.lookup(63, 0, 0)
        val (_, _, b) = strip.lookup(0, 0, 63)
        (r > 250) shouldBe true
        (b > 250) shouldBe true
    }

    @Test
    fun `a smaller table is resampled up rather than rejected`() {
        // 17, 25, 32 and 33 are all common. Refusing them would reject most of what anyone owns.
        for (size in listOf(2, 17, 25, 32, 33)) {
            val strip = CubeLut.read(identity(size))
            check(strip != null) { "a $size-level cube was rejected" }
            strip.width shouldBe CubeLut.STRIP
        }
    }

    @Test
    fun `comments, blank lines, a title and Windows endings are all tolerated`() {
        // Every one of these appears in real grading packs, and a parser that assumes the tidy case
        // reads about half of one.
        val messy = identity(9, header = "TITLE \"A Grade With Spaces\"\n\n# a comment\n")
            .toString(Charsets.UTF_8)
            .replace("\n", "\r\n")
            .let { "$it\r\n# trailing comment\r\n" }
        val strip = CubeLut.read(messy.toByteArray())
        check(strip != null) { "a file with comments and CRLF was rejected" }
    }

    @Test
    fun `the domain is honoured`() {
        // A table declaring 0..255 holds the same grade as one declaring 0..1, and reading the
        // numbers raw would drive every channel far past white.
        val text = buildString {
            append("LUT_3D_SIZE 2\n")
            append("DOMAIN_MIN 0 0 0\n")
            append("DOMAIN_MAX 255 255 255\n")
            for (b in 0..1) for (g in 0..1) for (r in 0..1) {
                append("${r * 255} ${g * 255} ${b * 255}\n")
            }
        }
        val strip = CubeLut.read(text.toByteArray())!!
        val (r, g, bb) = strip.lookup(0, 0, 0)
        (r + g + bb) shouldBe 0
        val (wr, wg, wb) = strip.lookup(63, 63, 63)
        check(wr > 250 && wg > 250 && wb > 250) { "white came out ($wr,$wg,$wb)" }
    }

    @Test
    fun `a one dimensional table is three curves rather than a cube`() {
        // It carries size entries, not size cubed, so reading it as a cube would index off the end.
        val text = buildString {
            append("LUT_1D_SIZE 4\n")
            for (i in 0..3) append("${i / 3f} ${i / 3f} ${i / 3f}\n")
        }
        val strip = CubeLut.read(text.toByteArray())
        check(strip != null) { "a 1D table was rejected" }
    }

    @Test
    fun `something that is not a cube is refused rather than thrown`() {
        // Choosing the wrong file in a picker is a normal event, and it belongs in the caller's
        // error path rather than in an exception.
        CubeLut.read("hello".toByteArray()) shouldBe null
        CubeLut.read(ByteArray(0)) shouldBe null
        // A declared size with too few entries is a truncated download, which is worse than a wrong
        // file because it half works.
        CubeLut.read("LUT_3D_SIZE 8\n0 0 0\n".toByteArray()) shouldBe null
    }
}
