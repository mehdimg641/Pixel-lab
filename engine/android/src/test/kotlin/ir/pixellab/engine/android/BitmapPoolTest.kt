package ir.pixellab.engine.android

import android.graphics.Bitmap
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The bitmap pool.
 *
 * Its purpose is not to save allocation time but to stop the collector from running: a 1080-square
 * ARGB bitmap is four and a half megabytes, and allocating one per frame during a drag fills the
 * heap in a second or two. Every test here is about a way the pool could quietly fail to do that.
 */
@RunWith(RobolectricTestRunner::class)
class BitmapPoolTest {

    @Test
    fun `a returned bitmap is handed back rather than reallocated`() {
        val pool = BitmapPool()
        val first = pool.obtain(64, 64)
        pool.recycle(first)
        val second = pool.obtain(64, 64)

        (first === second) shouldBe true
        pool.hits shouldBe 1
    }

    @Test
    fun `a reused bitmap comes back erased`() {
        // A recycled buffer still holds the last drawing. A caller that paints only part of it
        // would composite the previous frame underneath — a ghost that moves with the canvas.
        val pool = BitmapPool()
        val first = pool.obtain(16, 16)
        first.eraseColor(0xFFFF0000.toInt())
        pool.recycle(first)

        val second = pool.obtain(16, 16)
        second.getPixel(8, 8) shouldBe 0
    }

    @Test
    fun `a different size is not confused for a match`() {
        // Android only reuses a bitmap whose byte count matches exactly, so a pool that returned a
        // near-enough buffer would be handing back one the platform then rejects.
        val pool = BitmapPool()
        pool.recycle(pool.obtain(64, 64))
        val other = pool.obtain(32, 32)

        other.width shouldBe 32
        pool.misses shouldBe 2
    }

    @Test
    fun `a different config is not confused for a match either`() {
        val pool = BitmapPool()
        pool.recycle(pool.obtain(32, 32, Bitmap.Config.ARGB_8888))
        val other = pool.obtain(32, 32, Bitmap.Config.RGB_565)
        other.config shouldBe Bitmap.Config.RGB_565
    }

    @Test
    fun `the pool stays inside its budget`() {
        // Without this the pool is a memory leak with a helpful name: it would hold every buffer
        // the app ever finished with.
        val pool = BitmapPool(limitBytes = 64L * 1024)
        repeat(20) { pool.recycle(pool.obtain(64, 64)) }
        (pool.heldBytes <= 64L * 1024) shouldBe true
    }

    @Test
    fun `a bitmap larger than the whole budget is dropped`() {
        // Keeping it would evict every useful buffer to store one nobody will ask for twice.
        val pool = BitmapPool(limitBytes = 4L * 1024)
        pool.recycle(pool.obtain(256, 256))
        pool.heldBytes shouldBe 0L
    }

    @Test
    fun `clearing frees everything now`() {
        // On a low-memory warning, waiting for the collector is what turns a warning into a kill.
        val pool = BitmapPool()
        repeat(4) { pool.recycle(pool.obtain(32, 32)) }
        pool.clear()
        pool.heldBytes shouldBe 0L
    }

    @Test
    fun `a recycled bitmap is never handed out`() {
        val pool = BitmapPool()
        val bitmap = pool.obtain(32, 32)
        pool.recycle(bitmap)
        pool.clear()
        val fresh = pool.obtain(32, 32)
        fresh.isRecycled shouldBe false
    }

    @Test
    fun `several buffers of the same shape are all kept`() {
        // A render pass holds a source and a destination at once, so the pool has to be able to
        // return two of the same size — a map of one bitmap per key would serve only every other
        // request.
        val pool = BitmapPool()
        val a = pool.obtain(32, 32)
        val b = pool.obtain(32, 32)
        pool.recycle(a)
        pool.recycle(b)

        pool.obtain(32, 32)
        pool.obtain(32, 32)
        pool.hits shouldBe 2
    }
}
