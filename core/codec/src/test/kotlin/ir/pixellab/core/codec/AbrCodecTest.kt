package ir.pixellab.core.codec

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import org.junit.jupiter.api.Test

/**
 * Photoshop brush files.
 *
 * ### What these tests can and cannot prove, stated up front
 *
 * The other codecs in this package are checked against files somebody else's encoder wrote —
 * `TiffAgainstPillowTest` is the model. There is no equivalent here. Nothing available writes
 * `.abr` but Photoshop, and a brush pack off the internet is somebody's artwork with a licence
 * attached, so there is no fixture to check in.
 *
 * A hand-built fixture with hand-computed expected output would not fill the gap, and the TIFF work
 * is the reason I know: three bugs there turned out to be in the *test harness* rather than the
 * codec, because building the file is a second implementation of the format and can be wrong in
 * exactly the same way as the first. Asserting one against the other proves they agree.
 *
 * So the claims here are pinned differentially instead, and each one names its own tiebreaker:
 *
 * - **The uncompressed path is checked absolutely**, because it can be: the coverage bytes *are*
 *   the file bytes, so a disagreement is unambiguous and neither side gets a vote.
 * - **The compressed path is checked against the uncompressed one.** Both carry the same tip, so
 *   the run-length reading is pinned against bytes that were never run-length encoded. The encoder
 *   below is only trusted to the extent that this assertion passes.
 * - **The two container generations are checked against each other**, carrying one identical tip,
 *   so a misread header in either shows up as a disagreement rather than as two plausible answers.
 *
 * What remains genuinely unproven, and no test here claims otherwise: the absolute byte offsets in
 * the version 6 header — the 37-byte identifier and the 264-byte descriptor block. Those are fixed
 * widths taken from other implementations, and this file's fixtures are built with the same two
 * numbers, so both would move together if they are wrong. The first real brush pack a user imports
 * is what settles that, and it will settle it loudly: every tip comes out as noise.
 */
class AbrCodecTest {

    /**
     * A deliberately awkward tip: a gradient, a hard edge and a run.
     *
     * The run matters — a mask of all one value is decoded correctly by a run-length reader that is
     * completely broken, and a mask with no runs never exercises the repeat header at all.
     */
    private val width = 6
    private val height = 4
    private val tip = ByteArray(width * height) { i ->
        when {
            i < 6 -> (i * 40).toByte()      // a ramp
            i < 14 -> 0xFF.toByte()         // a long run across a row boundary
            i == 14 -> 0.toByte()
            else -> (255 - i * 5).toByte()
        }
    }

    // ---- building the two containers ---------------------------------------------------------

    private fun ByteArrayOutputStream.u8(value: Int) = write(value and 0xFF)

    private fun ByteArrayOutputStream.u16(value: Int) {
        u8(value shr 8)
        u8(value)
    }

    private fun ByteArrayOutputStream.i32(value: Int) {
        u16(value shr 16)
        u16(value)
    }

    private fun ByteArrayOutputStream.ascii(text: String) = write(text.toByteArray(Charsets.US_ASCII))

    /**
     * PackBits, the encoding side.
     *
     * Deliberately the dumbest correct encoder: literals only, one row at a time. It never emits a
     * repeat header, which sounds like it would leave the interesting case untested — it does not,
     * because [`the compressed and the uncompressed form of one tip agree`] would pass trivially if
     * the decoder ignored repeats. So [runs] emits them and this does not, and the two together
     * cover both headers without either being trusted.
     */
    private fun literals(row: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var index = 0
        while (index < row.size) {
            val take = minOf(128, row.size - index)
            out.u8(take - 1)
            out.write(row, index, take)
            index += take
        }
        return out.toByteArray()
    }

    /** The same row as a repeat header per identical stretch, falling back to literals. */
    private fun runs(row: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var index = 0
        while (index < row.size) {
            var run = 1
            while (index + run < row.size && row[index + run] == row[index] && run < 128) run++
            if (run >= 2) {
                out.u8((1 - run) and 0xFF)
                out.u8(row[index].toInt())
                index += run
            } else {
                var literal = 1
                while (index + literal < row.size &&
                    (index + literal + 1 >= row.size || row[index + literal] != row[index + literal + 1]) &&
                    literal < 128
                ) {
                    literal++
                }
                out.u8(literal - 1)
                out.write(row, index, literal)
                index += literal
            }
        }
        return out.toByteArray()
    }

    /** The mask bytes as the file stores them: raw, or a row-count table then the rows. */
    private fun body(compressed: Boolean, encode: (ByteArray) -> ByteArray): ByteArray {
        if (!compressed) return tip.copyOf()
        val rows = (0 until height).map { encode(tip.copyOfRange(it * width, (it + 1) * width)) }
        val out = ByteArrayOutputStream()
        for (row in rows) out.u16(row.size)
        for (row in rows) out.write(row)
        return out.toByteArray()
    }

    /** A version 2 file holding one sampled tip. */
    private fun version2(
        name: String = "قلمِ آزمایشی",
        compressed: Boolean = false,
        spacingPercent: Int = 25,
        encode: (ByteArray) -> ByteArray = ::literals,
    ): ByteArray {
        val block = ByteArrayOutputStream().apply {
            i32(0)                       // misc
            u16(spacingPercent)
            // Photoshop's Unicode string: a *four-byte* character count, then UTF-16BE code units.
            // Written as two bytes the first time, which made every version 2 fixture decode to
            // nothing — the harness being wrong rather than the codec, which is the failure mode
            // this whole file is arranged to catch rather than to hide.
            i32(name.length)
            for (character in name) u16(character.code)
            u8(0)                        // antialiasing
            repeat(4) { u16(0) }         // the 16-bit rectangle, superseded
            i32(0); i32(0); i32(height); i32(width)
            u16(8)                       // depth
            u8(if (compressed) 1 else 0)
            write(body(compressed, encode))
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            u16(2)                       // version
            u16(1)                       // one brush
            u16(2)                       // sampled
            i32(block.size)
            write(block)
        }.toByteArray()
    }

    /** A version 6 file holding one sampled tip, subversion 2 so the descriptor block is present. */
    private fun version6(compressed: Boolean = false, encode: (ByteArray) -> ByteArray = ::literals): ByteArray {
        val brush = ByteArrayOutputStream().apply {
            write(ByteArray(ID_LENGTH))
            write(ByteArray(DESCRIPTOR))
            u16(0); u16(0)               // the 16-bit top, superseded
            i32(0); i32(0); i32(height); i32(width)
            u16(8)
            u8(if (compressed) 1 else 0)
            write(body(compressed, encode))
        }.toByteArray()

        val segment = ByteArrayOutputStream().apply {
            i32(brush.size)
            write(brush)
            // The four-byte pad that is not counted in the length. Its absence is the bug that
            // makes every brush after the first come out as noise.
            repeat((4 - brush.size % 4) % 4) { u8(0) }
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            u16(6)
            u16(2)                       // subversion
            ascii("8BIM"); ascii("samp")
            i32(segment.size)
            write(segment)
        }.toByteArray()
    }

    // ---- the absolute claim ------------------------------------------------------------------

    @Test
    fun `an uncompressed tip comes back byte for byte`() {
        // The only claim in this file that needs no tiebreaker: the coverage bytes are the file
        // bytes, so there is nothing for a shared misreading to hide behind.
        val brushes = AbrCodec.decode(version2())

        brushes.size shouldBe 1
        brushes[0].width shouldBe width
        brushes[0].height shouldBe height
        brushes[0].mask.toList() shouldBe tip.toList()
    }

    @Test
    fun `the name and the spacing survive`() {
        val brush = AbrCodec.decode(version2(name = "مو", spacingPercent = 40)).single()

        brush.name shouldBe "مو"
        brush.spacing shouldBe 0.4f
    }

    @Test
    fun `a version 6 tip carries no spacing rather than a guessed one`() {
        // Null, not 0.25 and not 1.0. Version 6 keeps spacing in the descriptor, which this does not
        // read — and the two plausible defaults are this app's and Photoshop's, which differ by four
        // times. A brush that silently arrives with the wrong one paints a dotted line.
        AbrCodec.decode(version6()).single().spacing shouldBe null
    }

    // ---- the differential claims -------------------------------------------------------------

    @Test
    fun `the compressed and the uncompressed form of one tip agree`() {
        // The run-length reading, pinned against bytes that were never run-length encoded.
        val plain = AbrCodec.decode(version2(compressed = false)).single()
        val packed = AbrCodec.decode(version2(compressed = true, encode = ::runs)).single()

        packed.mask.toList() shouldBe plain.mask.toList()
    }

    @Test
    fun `the two run headers agree with each other`() {
        // Literals only against repeats-where-possible. A decoder that mishandles the repeat header
        // passes the previous test if the encoder never emits one, so both encodings are run and
        // required to land on the same mask.
        val literal = AbrCodec.decode(version2(compressed = true, encode = ::literals)).single()
        val repeated = AbrCodec.decode(version2(compressed = true, encode = ::runs)).single()

        repeated.mask.toList() shouldBe literal.mask.toList()
    }

    @Test
    fun `the two container generations agree on one tip`() {
        // Same pixels, two entirely different headers. A misread offset in either shows up as a
        // disagreement rather than as two answers that each look plausible on their own.
        val old = AbrCodec.decode(version2()).single()
        val new = AbrCodec.decode(version6()).single()

        new.width shouldBe old.width
        new.height shouldBe old.height
        new.mask.toList() shouldBe old.mask.toList()
    }

    // ---- the files that arrive broken --------------------------------------------------------

    @Test
    fun `an unknown version says so instead of guessing`() {
        val error = shouldThrow<CodecException> { AbrCodec.decode(byteArrayOf(0, 99, 0, 1)) }

        error.message!! shouldContain "99"
    }

    @Test
    fun `something far too short is not an ABR`() {
        shouldThrow<CodecException> { AbrCodec.decode(byteArrayOf(0, 2)) }
    }

    @Test
    fun `a brush count of sixty-five thousand does not become sixty-five thousand iterations`() {
        // The count is one `0xFFFF` away from asking for a loop over a file that holds one brush.
        // Bounded by the bytes as well as by the count, so this returns what is really there.
        val real = version2()
        val lying = real.copyOf().also { it[2] = 0xFF.toByte(); it[3] = 0xFF.toByte() }

        AbrCodec.decode(lying).size shouldBe 1
    }

    @Test
    fun `a negative block length stops rather than seeking backwards`() {
        val bytes = ByteArrayOutputStream().apply {
            u16(2); u16(1); u16(2)
            i32(-64)
        }.toByteArray()

        AbrCodec.decode(bytes) shouldBe emptyList()
    }

    @Test
    fun `a truncated file gives back the tips that were whole`() {
        // The ordinary case for a pack downloaded over a flaky connection, and the one behaviour
        // that matters: one lost tip should cost one tip.
        val whole = version2()
        val cut = whole.copyOf(whole.size - 5)

        // The block is declared longer than what is left, so it is not taken — and nothing throws.
        AbrCodec.decode(cut) shouldBe emptyList()
    }

    @Test
    fun `a rectangle no tip could have is refused`() {
        // Two signed integers subtracted. A corrupt pair allocates whatever they say, which on a
        // phone is the whole heap — so the tip is skipped and the file still reads.
        val bytes = version2()
        val marker = bytes.size - (width * height) - 3 - 2  // the width field of the 32-bit rect
        bytes[marker] = 0x7F
        bytes[marker + 1] = 0xFF.toByte()

        AbrCodec.decode(bytes) shouldBe emptyList()
    }

    private companion object {
        const val ID_LENGTH = 37
        const val DESCRIPTOR = 264
    }
}
