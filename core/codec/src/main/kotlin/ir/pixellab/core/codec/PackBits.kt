package ir.pixellab.core.codec

/**
 * Adobe's run-length scheme, shared by everything of theirs that stores rows.
 *
 * ### Why this is its own file
 *
 * It lived inside [PsdReader] until `.abr` needed it too, and the two are the same scheme down to
 * the row-count table that precedes the data — not similar, the same. Writing it a second time for
 * brushes would have produced a decoder with no real files behind it, sitting next to one that has
 * been read against Photoshop's own output for as long as the PSD importer has existed. The bugs
 * this avoids are the quiet kind: an off-by-one in a run header shifts every row after it, which
 * looks like a texture rather than like a failure.
 *
 * So brushes decode through the code the PSD fixtures already exercise, and any fix to one is a fix
 * to both.
 */
internal object PackBits {

    /** @param large PSB widens the per-row byte counts from 16 to 32 bits. */
    fun rowCounts(reader: ByteReader, rows: Int, large: Boolean = false): IntArray =
        IntArray(rows) { if (large) reader.i32() else reader.u16() }

    /**
     * A signed run header, then either that many literal bytes or one byte repeated.
     *
     * The row lengths are read from the table rather than trusting the run headers to land exactly
     * on the row end. Real files contain rows whose runs overshoot by a byte, and following the
     * runs alone shifts every subsequent row — the classic diagonal-tear artefact.
     */
    fun decode(
        reader: ByteReader,
        counts: IntArray,
        firstRow: Int,
        rows: Int,
        bytesPerRow: Int,
    ): ByteArray {
        val out = ByteArray(bytesPerRow * rows)
        var offset = 0
        for (row in 0 until rows) {
            val end = reader.position + counts[firstRow + row]
            val rowEnd = offset + bytesPerRow
            while (reader.position < end && offset < rowEnd) {
                val header = reader.i8()
                when {
                    header >= 0 -> {
                        val count = (header + 1).coerceAtMost(rowEnd - offset)
                        repeat(count) { out[offset++] = reader.i8().toByte() }
                    }
                    header > -128 -> {
                        val value = reader.i8().toByte()
                        val count = (1 - header).coerceAtMost(rowEnd - offset)
                        repeat(count) { out[offset++] = value }
                    }
                    // -128 is a no-op the specification reserves; treating it as a run would
                    // consume a byte that belongs to the next header.
                    else -> Unit
                }
            }
            reader.position = end
            offset = rowEnd
        }
        return out
    }
}
