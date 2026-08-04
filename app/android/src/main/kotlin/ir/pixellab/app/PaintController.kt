package ir.pixellab.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ir.pixellab.core.codec.RasterImage
import ir.pixellab.engine.android.toImage
import ir.pixellab.engine.android.toRaster
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

    /**
     * The stroke's points, kept only for the smudge brush.
     *
     * Smudge is the one mode that cannot be answered from the coverage mask: dragging colour depends
     * on the *order* the dabs were laid and the direction the finger moved, and a mask has neither.
     * So the path is recorded — and only when it will be used, because holding a few thousand points
     * per stroke for the other six modes would be pure waste.
     */
    private val smudgePath = ArrayList<Vec2>()

    var preset: BrushPreset by mutableStateOf(BrushPreset.HARD)

    /** Narrows every stroke, when the user has chosen pixels. */
    var selection: PixelSelection? by mutableStateOf(null)

    /**
     * Where the clone stamp copies from.
     *
     * Held across strokes, the way Photoshop's aligned mode works: the user sets the source once and
     * then paints in as many passes as they like, with the offset between finger and source staying
     * fixed so the copied region is continuous. Re-anchoring on every stroke would make the second
     * pass copy a different part of the image and the repair would not line up with itself.
     */
    private var anchor: Vec2? by mutableStateOf(null)

    val cloneSource: Vec2? get() = anchor

    /** True once the stamp knows where to copy from, so the UI can stop asking. */
    val cloneReady: Boolean get() = anchor != null

    /**
     * True while the next canvas tap means "copy from here" rather than "paint here".
     *
     * A touch screen has no alt-click, and the alternative — a long press — is already the gesture
     * that picks a buried layer. An explicitly armed state is slower by one press and is never
     * ambiguous about what the next touch will do.
     */
    var armingCloneSource: Boolean by mutableStateOf(false)

    private var cloneOffset: Vec2? = null

    /**
     * The layer as it was when the stroke began.
     *
     * Read from rather than the live layer: a stamp that samples its own output smears the copied
     * texture into a spiral the moment the brush crosses where it has already painted.
     */
    private var cloneSnapshot: android.graphics.Bitmap? = null

    /**
     * Points the stamp somewhere new.
     *
     * The offset is cleared with it: keeping the old one would mean the next stroke copied from
     * wherever the *previous* source happened to sit relative to the finger, which is the one thing
     * a user setting a source is trying to control.
     */
    fun setCloneSource(at: Vec2?) {
        anchor = at
        cloneOffset = null
        armingCloneSource = false
    }

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
        // A clone stamp with nowhere to copy from would paint nothing and look broken; refusing the
        // stroke leaves the sheet's "set the source" instruction on screen, which is the answer.
        val source = anchor
        if (preset.clone) {
            if (source == null) return false
            // Fixed at the first stroke and kept: the offset is what makes several passes line up.
            if (cloneOffset == null) cloneOffset = at - source
            cloneSnapshot = android.graphics.Bitmap.createBitmap(
                pixels.pixels, pixels.width, pixels.height, android.graphics.Bitmap.Config.ARGB_8888,
            )
        }

        target = image.asset
        before = pixels
        smudgePath.clear()
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
        if (preset.mode == ir.pixellab.core.paint.BrushMode.SMUDGE) smudgePath += at
        lay(current, plan.plan(listOf(StrokePoint(at, pressure))))
        publish()
    }

    /**
     * Draws a planned batch of dabs, with whatever supplies their colour.
     *
     * The one place the clone stamp differs from every other brush. Everything before this — the
     * spacing, the dynamics, the jitter — has already happened identically for both.
     */
    private fun lay(current: BrushRasterizer.Stroke, stamps: List<ir.pixellab.core.paint.Stamp>) {
        val offset = cloneOffset
        val snapshot = cloneSnapshot
        if (preset.clone && offset != null && snapshot != null) {
            rasterizer.addClone(current, stamps, snapshot, offset)
        } else {
            rasterizer.add(current, stamps)
        }
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
        lay(current, plan.finish(StrokePoint(at, pressure)))

        val asset = target
        val previous = before
        publish()
        current.recycle()
        releaseSnapshot()
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
        releaseSnapshot()
        before?.let { image -> target?.let { assets.put(it, image) } }
        stroke = null
        planner = null
        target = null
        before = null
        generation++
    }

    /** Tells the canvas an operation outside the brush has repainted a layer. */
    fun bumpGeneration() {
        generation++
    }

    /** Puts a previous or subsequent version of a painted layer back. */
    fun restore(edit: PaintEdit, redo: Boolean) {
        assets.put(edit.asset, if (redo) edit.after else edit.before)
        generation++
    }

    private fun releaseSnapshot() {
        cloneSnapshot?.recycle()
        cloneSnapshot = null
    }

    private fun publish() {
        val asset = target ?: return
        val current = stroke ?: return
        val base = before ?: return
        // Recomputed from `before` every time rather than accumulated, which is what lets a smudge
        // be replayed along the whole path so far without each frame smearing the previous frame's
        // output — the same reason the other modes can re-derive their result from the coverage.
        val painted = if (preset.mode == ir.pixellab.core.paint.BrushMode.SMUDGE) {
            ir.pixellab.core.imaging.ToneBrush
                .smudge(base.toRaster(), smudgePath.toList(), preset.size / 2f, preset.flow)
                .toImage()
        } else {
            rasterizer.commit(base, current, selection)
        }
        assets.put(asset, painted)
        generation++
    }
}

/** A finished stroke, as the two versions of the layer it sits between. */
data class PaintEdit(val asset: AssetId, val before: RasterImage, val after: RasterImage) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}
