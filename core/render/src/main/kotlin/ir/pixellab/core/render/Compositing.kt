package ir.pixellab.core.render

import ir.pixellab.core.model.Affine
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.Transform
import ir.pixellab.core.model.Vec2


/**
 * The mappings the compositor needs.
 *
 * Kept in pure Kotlin, and expressed as "where does this target pixel come from", because that is
 * the direction the shader asks in and the direction that is easy to get backwards.
 */
object Compositing {

    /**
     * Maps a point in the layer's own coordinates onto the canvas.
     *
     * The same arithmetic as `Handles.localToCanvas`, as a matrix: scale, skew and rotate about the
     * anchor, then translate.
     *
     * **The order is rotate ∘ skew ∘ scale**, and it is not interchangeable. Skewing after rotating
     * slants along the *screen's* axes rather than the layer's, so a rotated layer's skew handle
     * would drag it in a direction unrelated to the edge being pulled. Scaling last would have a
     * non-uniform scale change the skew angle, since a shear and a scale do not commute — which
     * shows as an italic that leans further the wider the layer is stretched.
     */
    fun layerToCanvas(bounds: Rect, transform: Transform): Affine =
        Affine.warpAware(bounds, transform)


    /**
     * Maps canvas UV to the layer texture's UV, which is what the composite shader samples with.
     *
     * **The placement is built against the *shape*, not against the texture.** A four-corner warp is
     * stored as four points in the layer's own coordinates, and those points were authored against
     * the shape's rectangle — so normalising them against anything else silently rescales the whole
     * placement. Handing over the bleed-grown rectangle is what used to happen, and the moment a
     * perspective existed the artwork shrank by the ratio between the two and slid off register,
     * while the selection handles stayed where they were: `Handles.localToCanvas` has always passed
     * the shape bounds. Two implementations of one placement disagreeing is precisely the failure
     * both files' comments promise cannot happen, and it happened because they were given different
     * rectangles rather than because they computed different things.
     *
     * @param shapeBounds the layer's own rectangle, which is what the transform is expressed against
     * @param textureBounds the layer texture's rectangle, already grown by the effects' bleed — so
     *   the shadow that reaches past the shape is inside the texture too
     */
    fun canvasUvToLayerUv(
        canvasSize: Vec2,
        shapeBounds: Rect,
        textureBounds: Rect,
        transform: Transform,
    ): Affine {
        val canvasUvToCanvas = Affine.scale(canvasSize.x, canvasSize.y)
        val canvasToLayer = layerToCanvas(shapeBounds, transform).inverse()
        val layerToTextureUv = Affine.scale(1f / textureBounds.width, 1f / textureBounds.height) *
            Affine.translate(-textureBounds.left, -textureBounds.top)
        return layerToTextureUv * canvasToLayer * canvasUvToCanvas
    }

    /**
     * Maps a layer texture's UV to the UV of the shape inside it.
     *
     * A layer's texture is grown by whatever bleed its effects need — a shadow reaching fifty units
     * past the shape means fifty units of margin on every side. A paint that belongs to the *shape*
     * rather than to the texture has to be sampled through this, or a placed photograph slides
     * inside its own frame by exactly the bleed, and a gradient overlay spans the margin too.
     */
    fun textureUvToShapeUv(textureBounds: Rect, shapeBounds: Rect): Affine {
        if (shapeBounds.width <= 0f || shapeBounds.height <= 0f) return Affine.IDENTITY
        val textureUvToTexture = Affine.scale(textureBounds.width, textureBounds.height)
        val textureToCanvas = Affine.translate(textureBounds.left, textureBounds.top)
        val canvasToShapeUv = Affine.scale(1f / shapeBounds.width, 1f / shapeBounds.height) *
            Affine.translate(-shapeBounds.left, -shapeBounds.top)
        return canvasToShapeUv * textureToCanvas * textureUvToTexture
    }

    /**
     * Maps screen UV to canvas UV, for the pass that puts the finished canvas on screen.
     *
     * This is where the camera finally applies. Rendering every layer through the camera instead
     * would re-run the whole effect stack on every pan, which is exactly what makes a canvas lag
     * behind a finger.
     */
    fun screenUvToCanvasUv(screenSize: Vec2, canvasSize: Vec2, offset: Vec2, zoom: Float, rotation: Float): Affine {
        val screenUvToScreen = Affine.scale(screenSize.x, screenSize.y)
        val screenToCanvas = Affine.scale(1f / zoom, 1f / zoom) *
            Affine.rotate(-rotation) *
            Affine.translate(-offset.x, -offset.y)
        val canvasToUv = Affine.scale(1f / canvasSize.x, 1f / canvasSize.y)
        return canvasToUv * screenToCanvas * screenUvToScreen
    }
}
