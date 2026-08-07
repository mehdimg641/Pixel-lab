package ir.pixellab.engine.android

import android.graphics.Bitmap

/**
 * Reuses bitmaps instead of allocating them.
 *
 * The reason is not allocation cost, it is the garbage collector. A 1080-square ARGB bitmap is four
 * and a half megabytes; allocating one per frame while a finger is dragging fills the heap in a
 * second or two and every collection that follows is a pause the user sees as a stutter. Reusing
 * the same buffer makes that whole class of jank disappear.
 *
 * Keyed on width, height and config together, because Android will only reuse a bitmap whose byte
 * count matches exactly — a pool that ignored the config would hand back a buffer that
 * `Bitmap.reconfigure` then rejects, and the fallback allocation would defeat the point.
 *
 * Not thread-safe by design: it is owned by whatever draws, and a lock on the hot path of a render
 * loop costs more than the allocation it is protecting.
 */
class BitmapPool(val limitBytes: Long = DEFAULT_LIMIT) {

    private data class Key(val width: Int, val height: Int, val config: Bitmap.Config)

    private val free = LinkedHashMap<Key, ArrayDeque<Bitmap>>()

    var heldBytes: Long = 0L
        private set

    /** How many requests were answered from the pool. Reported so the pool can be shown to work. */
    var hits: Int = 0
        private set

    var misses: Int = 0
        private set

    /**
     * A bitmap of exactly this shape, reused if one is free.
     *
     * Erased before it is handed back. A recycled buffer still holds the previous drawing, and a
     * caller that only paints part of it would otherwise composite last frame's picture underneath
     * — which shows up as a ghost that moves when the canvas does.
     */
    fun obtain(width: Int, height: Int, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap {
        require(width > 0 && height > 0) { "a bitmap must be positive, got ${width}x$height" }
        val key = Key(width, height, config)
        val queue = free[key]
        val reused = queue?.removeFirstOrNull()
        if (reused != null && !reused.isRecycled) {
            if (queue.isEmpty()) free.remove(key)
            heldBytes -= reused.allocationByteCount.toLong()
            hits++
            reused.eraseColor(0)
            return reused
        }
        misses++
        return Bitmap.createBitmap(width, height, config)
    }

    /**
     * Hands a bitmap back.
     *
     * A bitmap larger than the whole budget is dropped rather than kept: holding it would evict
     * every useful buffer to store one the caller is unlikely to ask for twice.
     */
    fun recycle(bitmap: Bitmap) {
        if (bitmap.isRecycled || !bitmap.isMutable) return
        val bytes = bitmap.allocationByteCount.toLong()
        if (bytes > limitBytes) return

        val key = Key(bitmap.width, bitmap.height, bitmap.config ?: Bitmap.Config.ARGB_8888)
        free.getOrPut(key) { ArrayDeque() }.addLast(bitmap)
        heldBytes += bytes
        trim()
    }

    /**
     * Drops everything, freeing the memory now.
     *
     * Called on a memory warning and when the editor closes. Waiting for the collector at that
     * point is what turns a low-memory warning into a kill.
     */
    fun clear() {
        for (queue in free.values) for (bitmap in queue) bitmap.recycle()
        free.clear()
        heldBytes = 0
    }

    /** Evicts oldest-first until the pool is inside its budget. */
    private fun trim() {
        while (heldBytes > limitBytes && free.isNotEmpty()) {
            // LinkedHashMap iterates in insertion order, so the first key is the least recently
            // *added* — which for a render loop is the size that has gone out of use.
            val key = free.keys.first()
            val queue = free.getValue(key)
            val bitmap = queue.removeFirstOrNull()
            if (bitmap == null) {
                free.remove(key)
                continue
            }
            heldBytes -= bitmap.allocationByteCount.toLong()
            bitmap.recycle()
            if (queue.isEmpty()) free.remove(key)
        }
    }

    companion object {
        /**
         * A quarter of the raster budget.
         *
         * The pool exists to avoid collections, not to cache the document. Given more than this it
         * starts holding buffers nobody will ask for again, and the memory it is saving the
         * collector from is memory it has taken itself.
         */
        const val DEFAULT_LIMIT = 100L * 1024 * 1024
    }
}
