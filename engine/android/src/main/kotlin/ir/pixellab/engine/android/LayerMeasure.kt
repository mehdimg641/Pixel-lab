package ir.pixellab.engine.android

import ir.pixellab.core.canvas.Handles
import ir.pixellab.core.editor.LayerBounds
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.Vec2

/** How large a placed image is, in canvas units. Supplied by whatever owns the decoded assets. */
fun interface ImageSizes {
    fun sizeOf(asset: ir.pixellab.core.model.AssetId): Vec2?

    companion object {
        val NONE = ImageSizes { null }
    }
}

/**
 * Where a layer's content sits before its transform.
 *
 * One implementation, used by the selection chrome and by the renderer alike. That is the whole
 * point of it existing: the handles are drawn in Compose and the artwork in GL, and when the two
 * measure text differently the selection box floats somewhere near the letters instead of around
 * them. Every editor that gets this wrong gets it wrong exactly here.
 *
 * Text is measured by shaping it — there is no shortcut, because Persian letter widths depend on
 * joining, and a per-character sum is wrong by a wide margin on the very scripts this app is for.
 * The result is cached against the layer's revision so a drag does not re-shape a paragraph sixty
 * times a second.
 */
class LayerMeasure(
    private val text: TextRasterizer = TextRasterizer(),
    private val images: ImageSizes = ImageSizes.NONE,
) : LayerBounds {

    /**
     * Resolves a smart object's source.
     *
     * Supplied rather than held, because the document changes on every edit and a measurer holding
     * a stale copy would size an instance from a layer that has since been resized.
     */
    var sources: (LayerId) -> Layer? = { null }

    /** Replaced when the font library is rescanned; every cached text measurement then goes stale. */
    var fonts: FontResolver = FontResolver.NONE
        set(value) {
            if (value === field) return
            field = value
            measured.clear()
        }

    private val measured = HashMap<LayerId, Pair<TextSpecKey, Rect>>()

    /** What a measurement depends on: the string, the font and everything that moves the letters. */
    private data class TextSpecKey(val spec: ir.pixellab.core.model.TextSpec, val font: String?)

    override fun of(layer: Layer): Rect = when (layer) {
        is Layer.Shape -> Rect.of(sizeOf(layer.geometry)).let {
            // A line from (0,0) to (0,0) has no extent and would give a selection box with no
            // grabbable edge, so a degenerate shape still reports something touchable.
            if (it.width <= 0f || it.height <= 0f) Rect.of(PLACEHOLDER) else it
        }
        is Layer.Text -> measure(layer)
        is Layer.Image -> images.sizeOf(layer.asset)?.let(Rect::of) ?: Rect.of(PLACEHOLDER)
        // An instance is its source's shape wearing its own transform, so it measures the source.
        // Falling back to the placeholder would put the selection box around the wrong thing on
        // every one of the reference file's 125 instances.
        is Layer.Instance -> sources(layer.source)?.takeIf { it.id != layer.id }?.let(::of)
            ?: Rect.of(PLACEHOLDER)
        // A group has no extent of its own; it is exactly what its children cover, each in the
        // group's space rather than in its own.
        is Layer.Group -> layer.children
            .map { child -> Handles.canvasBounds(of(child), child.transform) }
            .reduceOrNull(Rect::union)
            ?: Rect.of(PLACEHOLDER)
        else -> Rect.of(PLACEHOLDER)
    }

    private fun measure(layer: Layer.Text): Rect {
        val font = fonts.resolve(layer.spec.font)
        val key = TextSpecKey(layer.spec, font?.path)
        measured[layer.id]?.let { (cached, bounds) -> if (cached == key) return bounds }

        // Before the library has been scanned there is no font to shape with. A placeholder box is
        // better than nothing: the layer still selects and still drags, and the real extent arrives
        // with the scan rather than the user having to re-create the layer.
        val bounds = if (font == null) {
            Rect.of(PLACEHOLDER)
        } else {
            val box = text.rasterize(layer.spec, font).bounds
            Rect(box.left, box.top, box.right, box.bottom)
        }
        measured[layer.id] = key to bounds
        return bounds
    }

    /** Forgets a layer's cached extent, for an edit the key cannot see. */
    fun invalidate(id: LayerId) {
        measured.remove(id)
    }

    private fun sizeOf(geometry: ShapeGeometry): Vec2 = when (geometry) {
        is ShapeGeometry.Rectangle -> geometry.size
        is ShapeGeometry.Ellipse -> geometry.size
        is ShapeGeometry.Polygon -> geometry.size
        is ShapeGeometry.Star -> geometry.size
        is ShapeGeometry.Line -> Vec2(
            kotlin.math.abs(geometry.to.x - geometry.from.x),
            kotlin.math.abs(geometry.to.y - geometry.from.y),
        )
        is ShapeGeometry.Arrow -> Vec2(
            kotlin.math.abs(geometry.to.x - geometry.from.x) + geometry.headSize,
            kotlin.math.abs(geometry.to.y - geometry.from.y) + geometry.headSize +
                kotlin.math.abs(geometry.bend),
        )
        is ShapeGeometry.Path -> geometry.contours
            .flatMap { it.nodes }
            .fold(null as Vec2?) { acc, node ->
                Vec2(maxOf(acc?.x ?: 0f, node.point.x), maxOf(acc?.y ?: 0f, node.point.y))
            } ?: PLACEHOLDER
    }

    private companion object {
        /** Stand-in extent for a layer whose real one is not knowable yet, so it still selects. */
        val PLACEHOLDER = Vec2(320f, 160f)
    }
}
