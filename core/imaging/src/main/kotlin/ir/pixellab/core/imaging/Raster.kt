package ir.pixellab.core.imaging

/**
 * A CPU image buffer in straight (non-premultiplied) float components.
 *
 * Float rather than bytes because these algorithms chain — a guided filter feeding a distance
 * transform feeding a normal map — and quantising between stages is exactly the banding the colour
 * pipeline decision was made to avoid.
 */
class Raster(
    val width: Int,
    val height: Int,
    val channels: Int,
    val data: FloatArray = FloatArray(width * height * channels),
) {
    init {
        require(width > 0 && height > 0) { "raster must be positive, got ${width}x$height" }
        require(channels in 1..4) { "channels must be 1..4, got $channels" }
        require(data.size == width * height * channels) {
            "data size ${data.size} does not match ${width}x$height x$channels"
        }
    }

    val pixelCount: Int get() = width * height

    fun index(x: Int, y: Int, c: Int = 0): Int = (y * width + x) * channels + c

    operator fun get(x: Int, y: Int, c: Int = 0): Float = data[index(x, y, c)]

    operator fun set(x: Int, y: Int, c: Int, value: Float) {
        data[index(x, y, c)] = value
    }

    /** Reads with edge clamping, so filters need no boundary special-casing. */
    fun clamped(x: Int, y: Int, c: Int = 0): Float =
        data[index(x.coerceIn(0, width - 1), y.coerceIn(0, height - 1), c)]

    fun copy() = Raster(width, height, channels, data.copyOf())

    fun like(channels: Int = this.channels) = Raster(width, height, channels)

    fun fill(value: Float) = apply { data.fill(value) }

    /** Extracts one channel as a single-channel raster. */
    fun channel(c: Int): Raster {
        require(c in 0 until channels) { "channel $c out of range" }
        val out = Raster(width, height, 1)
        for (i in 0 until pixelCount) out.data[i] = data[i * channels + c]
        return out
    }

    /** Rec. 709 luma of the first three channels. */
    fun luminance(): Raster {
        require(channels >= 3) { "luminance needs at least three channels" }
        val out = Raster(width, height, 1)
        for (i in 0 until pixelCount) {
            val o = i * channels
            out.data[i] = 0.2126f * data[o] + 0.7152f * data[o + 1] + 0.0722f * data[o + 2]
        }
        return out
    }

    fun sameShapeAs(other: Raster): Boolean =
        width == other.width && height == other.height

    companion object {
        fun gray(width: Int, height: Int, init: (x: Int, y: Int) -> Float): Raster {
            val r = Raster(width, height, 1)
            for (y in 0 until height) for (x in 0 until width) r.data[y * width + x] = init(x, y)
            return r
        }
    }
}

/** Elementwise operations shared by several algorithms. */
object RasterMath {

    fun add(a: Raster, b: Raster): Raster = zip(a, b) { x, y -> x + y }

    fun subtract(a: Raster, b: Raster): Raster = zip(a, b) { x, y -> x - y }

    fun multiply(a: Raster, b: Raster): Raster = zip(a, b) { x, y -> x * y }

    fun scale(a: Raster, k: Float): Raster =
        Raster(a.width, a.height, a.channels, FloatArray(a.data.size) { a.data[it] * k })

    fun offset(a: Raster, k: Float): Raster =
        Raster(a.width, a.height, a.channels, FloatArray(a.data.size) { a.data[it] + k })

    fun clamp(a: Raster, lo: Float = 0f, hi: Float = 1f): Raster =
        Raster(a.width, a.height, a.channels, FloatArray(a.data.size) { a.data[it].coerceIn(lo, hi) })

    fun lerp(a: Raster, b: Raster, t: Float): Raster = zip(a, b) { x, y -> x + (y - x) * t }

    /** Mean absolute difference; used by tests to compare against expected output. */
    fun meanAbsDiff(a: Raster, b: Raster): Float {
        require(a.data.size == b.data.size) { "rasters differ in size" }
        var sum = 0.0
        for (i in a.data.indices) sum += kotlin.math.abs(a.data[i] - b.data[i])
        return (sum / a.data.size).toFloat()
    }

    private inline fun zip(a: Raster, b: Raster, op: (Float, Float) -> Float): Raster {
        require(a.data.size == b.data.size) { "rasters differ in size" }
        return Raster(a.width, a.height, a.channels, FloatArray(a.data.size) { op(a.data[it], b.data[it]) })
    }
}
