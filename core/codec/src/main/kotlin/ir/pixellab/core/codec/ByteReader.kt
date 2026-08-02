package ir.pixellab.core.codec

/**
 * Big-endian cursor over a byte array.
 *
 * Every format here is big-endian except BMP and half the TIFF variants, and all of them are
 * offset-driven — a section says how long it is and the next one starts after it. Reading with a
 * cursor that tracks position makes "skip to the end of this block whatever it contained" a single
 * call, which is what keeps an unknown sub-block from derailing the rest of the file.
 */
class ByteReader(private val bytes: ByteArray, var position: Int = 0) {

    val size: Int get() = bytes.size

    val remaining: Int get() = bytes.size - position

    fun hasRemaining(count: Int = 1) = remaining >= count

    fun u8(): Int = bytes[position++].toInt() and 0xFF

    fun i8(): Int = bytes[position++].toInt()

    fun u16(): Int = (u8() shl 8) or u8()

    fun i16(): Int = u16().toShort().toInt()

    fun u32(): Long = (u16().toLong() shl 16) or u16().toLong()

    fun i32(): Int = ((u16() shl 16) or u16())

    fun i64(): Long = (u32() shl 32) or u32()

    fun f32(): Float = Float.fromBits(i32())

    fun f64(): Double = Double.fromBits(i64())

    fun bytes(count: Int): ByteArray {
        val out = bytes.copyOfRange(position, (position + count).coerceAtMost(bytes.size))
        position += count
        return out
    }

    fun skip(count: Int) {
        position += count
    }

    /** Fixed-length ASCII, used for the four-character codes that key every Photoshop block. */
    fun ascii(count: Int): String = buildString(count) {
        repeat(count) { append(u8().toChar()) }
    }

    /**
     * A length-prefixed byte string padded to a multiple of [pad].
     *
     * Photoshop pads almost everything to two or four bytes and *does not* include the padding in
     * the declared length. Reading the declared length and moving on leaves the cursor one byte off,
     * and every subsequent block is garbage — the single most common way a PSD parser fails.
     */
    fun pascalString(pad: Int = 2): String {
        val length = u8()
        val text = ascii(length)
        val consumed = length + 1
        val padding = (pad - consumed % pad) % pad
        skip(padding)
        return text
    }

    /** Photoshop's Unicode string: a character count followed by UTF-16BE code units. */
    fun unicodeString(): String {
        val count = i32()
        if (count <= 0 || count > remaining / 2) return ""
        return buildString(count) {
            repeat(count) {
                val unit = u16()
                // Trailing nulls are common and are padding rather than content.
                if (unit != 0) append(unit.toChar())
            }
        }
    }

    fun align(pad: Int) {
        val over = position % pad
        if (over != 0) skip(pad - over)
    }

    /** Runs [body] over a bounded section and leaves the cursor exactly at its end regardless. */
    fun <T> section(length: Int, body: (ByteReader) -> T): T {
        val end = position + length
        val result = try {
            body(this)
        } finally {
            // Even a section this build does not understand must not shift everything after it.
            position = end
        }
        return result
    }
}

/** Reads a big-endian value from a fresh cursor, for callers that only need one. */
fun ByteArray.readerAt(offset: Int) = ByteReader(this, offset)
