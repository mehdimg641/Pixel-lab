package ir.pixellab.engine.android

import ir.pixellab.core.fonts.FontFile
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.ShapeGeometry

/**
 * Converting a text layer into a shape.
 *
 * Photoshop's Convert to Shape, and the important thing about it is that it is a **one-way door**.
 * Once the words are outlines they are geometry: every node can be dragged, the counters of a
 * letter can be cut away, the letterforms can be combined with a boolean — and the string is gone.
 * Retyping means starting again.
 *
 * That is not a limitation to hide. The conversion here keeps the layer's name, transform, style and
 * effects so nothing about how it looks changes at the moment of conversion, and it is the caller's
 * job to say plainly, before it happens, that the text is about to stop being text. A tool that
 * quietly discards a user's editable headline is one they only forgive once.
 *
 * Why it works at all is the same reason Persian renders correctly here in the first place: the
 * outline comes from the whole shaped run, so the letters are already joined inside the geometry.
 * Outlining glyph by glyph would produce a shape whose letters have come apart — which is precisely
 * the failure this app exists to avoid.
 */
object TextToShape {

    /**
     * @return the equivalent shape layer, or null when the text cannot be shaped — no font resolved,
     *   or an empty string, both of which would silently produce an invisible empty layer.
     */
    fun convert(
        layer: Layer.Text,
        fonts: FontResolver,
        rasterizer: TextRasterizer = TextRasterizer(),
        id: ir.pixellab.core.model.LayerId = layer.id,
    ): Layer.Shape? {
        if (layer.spec.text.isBlank()) return null
        val font = fonts.resolve(layer.spec.font) ?: return null
        val outline = rasterizer.rasterize(layer.spec, font).outline
        val geometry = outline.toModelPath()
        if (geometry.contours.isEmpty()) return null

        return Layer.Shape(
            id = id,
            geometry = geometry,
            // Everything the layer already had, so the canvas does not change at the moment of
            // conversion. The only difference the user should see is what the tools can now do.
            name = layer.name,
            transform = layer.transform,
            opacity = layer.opacity,
            blendMode = layer.blendMode,
            visible = layer.visible,
            locked = layer.locked,
            style = layer.style,
            mask = layer.mask,
            vectorMask = layer.vectorMask,
            clipped = layer.clipped,
        )
    }

    /** Whether [convert] would produce anything, for a UI that should not offer a dead button. */
    fun canConvert(layer: Layer, fonts: FontResolver): Boolean =
        layer is Layer.Text && layer.spec.text.isNotBlank() && fonts.resolve(layer.spec.font) != null

    /** The outline alone, for callers that want the geometry without a layer around it. */
    fun outlineOf(
        spec: ir.pixellab.core.model.TextSpec,
        font: FontFile,
        rasterizer: TextRasterizer = TextRasterizer(),
    ): ShapeGeometry.Path = rasterizer.rasterize(spec, font).outline.toModelPath()
}
