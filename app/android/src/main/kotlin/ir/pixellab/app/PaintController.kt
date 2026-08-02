package ir.pixellab.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ir.pixellab.core.codec.RasterImage
import ir.pixellab.core.model.AssetId
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.Vec2
import ir.pixellab.core.paint.BrushPreset
import ir.pixellab.core.paint.PixelSelection
import ir.pixellab.core.paint.StampPlanner
import ir.pixellab.core.paint.StrokePoint
import ir.pixellab.engine.android.BrushRasterizer

/**
 * One painting session: the brush, the layer it is painting on, and the stroke in progress.
 *
 * Kept apart from the view model so the rules about *when* a stroke starts and ends are in one
 * place. Painting is the only gesture in the app that mutates pixels rather than a document field,
 * which makes its edges — where undo is recorded, where the canvas is told to refresh — the part
 * most worth having somewhere readable.
 */
class PaintController(private val assets: AssetStore) {

    private val rasterizer = BrushRasterizer()
    private var stroke: BrushRasterizer.Stroke? = null
    private var planner: StampPlanner? = null
    private var target: AssetId? = null
    private var before: RasterImage? = null
    private var strokes = 0

    var preset: BrushPreset by mutableStateOf(BrushPreset.HARD)

    /** Narrows every stroke, when the user has chosen pixels. */
    var selection: PixelSelection? by mutableStateOf(null)

    /**
     * Bumped whenever painted pixels change.
     *
     * The renderer caches a layer's texture against its asset id, and a painted layer keeps the
     * same id from the first stroke to the last — so nothing else about it tells the cache that the
     * pixels moved.
     */
    var generation: Int by mutableStateOf(0)
        private set

    val isPainting: Boolean get() = stroke != null

    /**
     * Starts a stroke on a paint layer.
     *
     * Returns false when there is nothing to paint on. Silently creating a layer instead reads as
     * the brush having failed, because the mark appears somewhere the user was not looking — in the
     * layer panel.
     */
    fun begin(layer: Layer?, at: Vec2, pressure: Float): Boolean {
        val image = layer as? Layer.Image ?: return false
        val pixels = assets.source.load(image.asset) ?: return false

        target = image.asset
        before = pixels
        // Seeded from a counter rather than the clock, so an undone and redone stroke scatters
        // exactly as it did the first time.
        planner = StampPlanner(preset, seed = strokes++)
        stroke = rasterizer.begin(pixels.width, pixels.height, preset)

        extend(at, pressure)
        return true
    }

    /** Adds to the stroke and publishes it, so the mark appears under the finger. */
    fun extend(at: Vec2, pressure: Float) {
        val current = stroke ?: return
        val plan = planner ?: return
        rasterizer.add(current, plan.plan(listOf(StrokePoint(at, pressure))))
        publish()
    }

    /**
     * Ends the stroke and returns the pixels before it, for undo.
     *
     * The previous image rather than a diff: a stroke can touch any part of the layer, and a diff
     * that had to be computed would cost a full comparison at the exact moment the user lifts their
     * finger and expects the tool to be ready again.
     */
    fun end(at: Vec2, pressure: Float): PaintEdit? {
        val current = stroke ?: return null
        val plan = planner ?: return null
        rasterizer.add(current, plan.finish(StrokePoint(at, pressure)))

        val asset = target
        val previous = before
        publish()
        current.recycle()
        stroke = null
        planner = null
        target = null
        before = null

        if (asset == null || previous == null) return null
        val after = assets.source.load(asset) ?: return null
        return PaintEdit(asset, previous, after)
    }

    fun cancel() {
        stroke?.recycle()
        before?.let { image -> target?.let { assets.put(it, image) } }
        stroke = null
        planner = null
        target = null
        before = null
        generation++
    }

    /** Puts a previous or subsequent version of a painted layer back. */
    fun restore(edit: PaintEdit, redo: Boolean) {
        assets.put(edit.asset, if (redo) edit.after else edit.before)
        generation++
    }

    private fun publish() {
        val asset = target ?: return
        val current = stroke ?: return
        val base = before ?: return
        assets.put(asset, rasterizer.commit(base, current, selection))
        generation++
    }
}

/** A finished stroke, as the two versions of the layer it sits between. */
data class PaintEdit(val asset: AssetId, val before: RasterImage, val after: RasterImage) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}
