package ir.pixellab.core.codec

/**
 * One brush tip out of an `.abr` file.
 *
 * The mask is coverage, not colour: `0` is nothing and `255` is a full dab. That is what the file
 * stores and what a brush engine wants, and converting it to ARGB here would throw away the
 * distinction between "transparent" and "black" that the caller has to make when it decides whether
 * the dab paints the foreground colour or erases.
 */
data class AbrBrush(
    /** The name Photoshop shows, or a generated one where the file carries none. */
    val name: String,
    val width: Int,
    val height: Int,
    /** `width * height` coverage bytes, row-major. */
    val mask: ByteArray,
    /**
     * Dab spacing as a fraction of the tip's size, or null where the file did not say.
     *
     * Null rather than a default, because 0.25 is this app's default and 1.0 is Photoshop's, and a
     * brush that silently arrives with the wrong one paints a dotted line instead of a stroke. The
     * caller decides, knowing whether it was told.
     */
    val spacing: Float?,
) {
    // Data class over a ByteArray: identity, so two brushes are the same brush and not merely the
    // same pixels. Generated equals would compare the array by reference anyway and hashCode would
    // disagree with it, which is worse than saying so.
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/**
 * Reads Photoshop brush files.
 *
 * ### What is read and what is not
 *
 * **Sampled tips only.** An `.abr` holds two kinds of brush: computed ones, which are a radius and
 * a hardness and a roundness, and sampled ones, which are a bitmap. The computed ones are already
 * expressible — `BrushTip.Round` is exactly that — and importing one would produce a preset
 * indistinguishable from dragging two sliders. The sampled ones are the reason the format matters:
 * a real bristle, chalk or splatter edge cannot be computed, and until now the only way to get one
 * into this app was to draw it.
 *
 * ### The three container generations
 *
 * The format changed shape twice and the versions are not variations on a theme:
 *
 * - **1 and 2** — a bare sequence of `(type, length, payload)` blocks from byte four onwards.
 * - **6, 7, 9, 10** — a Photoshop segment file: `8BIM` blocks with four-character keys, of which
 *   `samp` holds the tips. Version 6 and up also carries a subversion word that decides whether the
 *   payload starts with a 264-byte descriptor block.
 *
 * Anything modern is version 6 or 10, and version 2 is what a decade of free brush packs are, so
 * both paths earn their place.
 *
 * ### Why the row loop looks paranoid
 *
 * These files come off the internet in bulk — a "5000 brushes" pack is a normal thing to download —
 * and a truncated or lightly corrupt one is common. Every length is checked against what is left
 * rather than trusted, and a brush that will not read is skipped rather than taken as proof the
 * whole file is bad. One unreadable tip in a pack of two hundred should cost the user one tip.
 */
object AbrCodec {

    /**
     * Every sampled tip in the file, in file order.
     *
     * @throws CodecException when the container itself is unreadable — a version this does not
     *   know, or a header too short to be an `.abr` at all. A file whose *contents* are partly
     *   broken comes back as a shorter list, because that is recoverable and this is not.
     */
    fun decode(bytes: ByteArray): List<AbrBrush> {
        if (bytes.size < HEADER) throw CodecException("not an ABR file: ${bytes.size} bytes")
        val reader = ByteReader(bytes)
        val version = reader.u16()
        return when (version) {
            1, 2 -> legacy(reader, version, bytes.size)
            6, 7, 9, 10 -> segmented(bytes, reader)
            else -> throw CodecException("ABR version $version is not one this reads")
        }
    }

    /** Versions 1 and 2: a count, then that many `(type, length, payload)` blocks. */
    private fun legacy(reader: ByteReader, version: Int, size: Int): List<AbrBrush> {
        val count = reader.u16()
        val out = ArrayList<AbrBrush>(count)
        // Bounded by the declared count *and* by the bytes: a count field is one `0xFFFF` away from
        // asking for sixty-five thousand iterations over a file that holds three brushes.
        repeat(count) {
            if (!reader.hasRemaining(6)) return out
            val type = reader.u16()
            val length = reader.i32()
            if (length < 0 || !reader.hasRemaining(length)) return out
            val end = reader.position + length
            if (type == SAMPLED) {
                runCatching { sampled(reader, version, out.size) }.getOrNull()?.let { out += it }
            }
            // Always from the block's declared end, never from wherever the body stopped. A brush
            // that read short would otherwise drag every brush after it out of alignment, turning
            // one bad tip into a bad file.
            reader.position = end.coerceAtMost(size)
        }
        return out
    }

    /**
     * One version 1 or 2 sampled tip.
     *
     * The layout is fixed and undocumented by Adobe; these offsets come from the format as it is
     * actually written. Version 2 inserts the brush name before the antialiasing byte, which is the
     * only difference and the reason the version has to be carried this far down.
     */
    private fun sampled(reader: ByteReader, version: Int, index: Int): AbrBrush {
        reader.skip(4) // misc
        val spacing = reader.u16()
        val name = if (version == 2) reader.unicodeString() else ""
        reader.skip(1) // antialiasing
        // Two rectangles, and the second is the one to use. The first is a 16-bit copy kept for
        // older readers and it saturates on a tip wider than 32767 — rare, but silently wrong.
        reader.skip(8)
        val top = reader.i32()
        val left = reader.i32()
        val bottom = reader.i32()
        val right = reader.i32()
        val depth = reader.u16()
        val compressed = reader.u8() == 1
        return mask(
            reader = reader,
            name = name.ifBlank { "قلم ${index + 1}" },
            width = right - left,
            height = bottom - top,
            depth = depth,
            compressed = compressed,
            spacing = spacing / 100f,
        )
    }

    /**
     * Versions 6 and up: `8BIM` segments, of which only `samp` is read.
     *
     * The other keys are the descriptor (`desc`), which holds the dynamics, and `patt` for
     * patterns. The dynamics are deliberately not read — see [decode]'s note on what is imported:
     * a tip is a thing this app cannot otherwise obtain, and a scatter value is two sliders away.
     */
    private fun segmented(bytes: ByteArray, reader: ByteReader): List<AbrBrush> {
        val subversion = reader.u16()
        while (reader.hasRemaining(12)) {
            val signature = reader.ascii(4)
            val key = reader.ascii(4)
            val length = reader.i32()
            if (signature != "8BIM" || length < 0 || !reader.hasRemaining(length)) break
            if (key == "samp") {
                // The limit is passed explicitly rather than left to `hasRemaining`. `section`
                // restores the cursor afterwards but does not narrow it, so a loop inside it that
                // asks "are there four bytes left" is asking about the whole file — and would walk
                // straight out of `samp` into the next `8BIM` block and read its header as a brush
                // length. That misreads a *healthy* file, not merely a broken one.
                val limit = reader.position + length
                return reader.section(length) { tips(it, subversion, limit) }
            }
            reader.skip(length)
        }
        return emptyList()
    }

    /**
     * The brushes inside a `samp` segment, each with its own length prefix.
     *
     * @param limit one past the last byte of the segment. Every bound here is against this rather
     *   than against the end of the file, for the reason given at the call site.
     */
    private fun tips(reader: ByteReader, subversion: Int, limit: Int): List<AbrBrush> {
        val out = ArrayList<AbrBrush>()
        while (reader.position + 4 <= limit) {
            val length = reader.i32()
            if (length <= 0 || reader.position + length > limit) break
            // Padded to four bytes, and the pad is *not* counted in the length. Advancing by the
            // length alone leaves the cursor one to three bytes early, and the next read then takes
            // a plausible-looking length out of the tail of the brush just read.
            val end = (reader.position + ((length + 3) / 4) * 4).coerceAtMost(limit)
            runCatching { tip(reader, subversion, out.size) }.getOrNull()?.let { out += it }
            // From the block's declared end, never from wherever the body stopped — so one tip that
            // reads short costs one tip rather than every tip after it.
            reader.position = end
        }
        return out
    }

    private fun tip(reader: ByteReader, subversion: Int, index: Int): AbrBrush {
        // A 37-byte identifier string. Not the display name — that lives in the descriptor block
        // that follows on the newer subversions, and it is a Pascal string of a different shape.
        reader.skip(ID_LENGTH)
        // Subversion 2 and up prefixes the tip with a descriptor. Its length is not written down
        // anywhere; it is fixed, which is the sort of fact that only comes from the files.
        if (subversion >= 2) reader.skip(DESCRIPTOR)
        reader.skip(4) // top, as 16-bit — superseded below
        val top = reader.i32()
        val left = reader.i32()
        val bottom = reader.i32()
        val right = reader.i32()
        val depth = reader.u16()
        val compressed = reader.u8() == 1
        return mask(
            reader = reader,
            name = "قلم ${index + 1}",
            width = right - left,
            height = bottom - top,
            depth = depth,
            compressed = compressed,
            // Version 6 keeps spacing in the descriptor rather than beside the mask, and this does
            // not read descriptors. Null says so instead of inventing a number.
            spacing = null,
        )
    }

    /**
     * The coverage bytes themselves, raw or PackBits per row.
     *
     * The compressed case goes through [PackBits], which is the decoder the PSD importer has always
     * used — the same scheme down to the row-count table, and already read against Photoshop's own
     * output. Writing a second one here would have meant a run-length decoder with no real files
     * behind it, and its bugs are the quiet kind: one byte off in a run header shifts every row
     * after it, which looks like a texture rather than like a failure.
     */
    private fun mask(
        reader: ByteReader,
        name: String,
        width: Int,
        height: Int,
        depth: Int,
        compressed: Boolean,
        spacing: Float?,
    ): AbrBrush {
        if (width !in 1..MAX_SIDE || height !in 1..MAX_SIDE) {
            throw CodecException("brush tip is ${width}x$height, which is not a tip")
        }
        if (depth != 8) throw CodecException("brush tip depth $depth is not 8-bit coverage")

        val mask = if (compressed) {
            val counts = PackBits.rowCounts(reader, height)
            PackBits.decode(reader, counts, firstRow = 0, rows = height, bytesPerRow = width)
        } else {
            reader.bytes(width * height)
        }
        return AbrBrush(name, width, height, mask, spacing)
    }

    /** Sampled rather than computed; the only block type this reads. */
    private const val SAMPLED = 2

    private const val HEADER = 4

    /** The fixed-width identifier ahead of every version 6 tip. */
    private const val ID_LENGTH = 37

    /** The fixed descriptor block on subversion 2 and up. */
    private const val DESCRIPTOR = 264

    /**
     * Photoshop's own ceiling is 5000; anything past it is a corrupt rectangle rather than a tip.
     *
     * A limit rather than trust, because the width comes from two signed integers subtracted and a
     * corrupt pair allocates whatever they say — on a phone, that is the whole heap.
     */
    private const val MAX_SIDE = 5000
}
