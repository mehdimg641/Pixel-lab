package ir.pixellab.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A greyscale mask attached to a layer. */
@Serializable
data class LayerMask(
    val asset: AssetId,
    val enabled: Boolean = true,
    val inverted: Boolean = false,
    /** Softens the mask edge without destroying it. */
    val feather: Float = 0f,
    /** Scales the mask's effect; 0 disables it entirely. */
    val density: Float = 1f,
    /** Keeps the mask fixed to the canvas while the layer moves. */
    val unlinked: Boolean = false,
)

/** A resolution-independent mask defined by a closed path. */
@Serializable
data class VectorMask(
    val path: ShapeGeometry,
    val enabled: Boolean = true,
    val inverted: Boolean = false,
    val feather: Float = 0f,
)

@Serializable
sealed interface ShapeGeometry {
    @Serializable @SerialName("rect")
    data class Rectangle(val size: Vec2, val cornerRadius: Corners = Corners.ZERO) : ShapeGeometry

    @Serializable @SerialName("ellipse")
    data class Ellipse(val size: Vec2) : ShapeGeometry

    @Serializable @SerialName("polygon")
    data class Polygon(val size: Vec2, val sides: Int = 6, val cornerRadius: Float = 0f) : ShapeGeometry {
        init { require(sides >= 3) { "a polygon needs at least three sides, got $sides" } }
    }

    @Serializable @SerialName("star")
    data class Star(
        val size: Vec2,
        val points: Int = 5,
        val innerRadius: Float = 0.5f,
        val cornerRadius: Float = 0f,
    ) : ShapeGeometry {
        init { require(points >= 3) { "a star needs at least three points, got $points" } }
    }

    @Serializable @SerialName("line")
    data class Line(val from: Vec2, val to: Vec2) : ShapeGeometry

    /** A line with head and/or tail ornaments, optionally curved through [bend]. */
    @Serializable @SerialName("arrow")
    data class Arrow(
        val from: Vec2,
        val to: Vec2,
        /** Perpendicular displacement of the midpoint; non-zero gives the curved arrows. */
        val bend: Float = 0f,
        val headSize: Float = 16f,
        val tailHead: Boolean = false,
    ) : ShapeGeometry

    /** Arbitrary cubic Bézier outline. */
    @Serializable @SerialName("path")
    data class Path(val contours: List<Contour>) : ShapeGeometry
}

@Serializable
data class Corners(
    val topLeft: Float = 0f,
    val topRight: Float = 0f,
    val bottomRight: Float = 0f,
    val bottomLeft: Float = 0f,
) {
    companion object {
        val ZERO = Corners()
        fun all(radius: Float) = Corners(radius, radius, radius, radius)
    }
}

@Serializable
data class Contour(val nodes: List<PathNode>, val closed: Boolean = true)

@Serializable
data class PathNode(
    val point: Vec2,
    /** Control point leading into [point]; equal to it for a corner node. */
    val controlIn: Vec2 = point,
    val controlOut: Vec2 = point,
)

/** Non-destructive colour operations, evaluated at composite time. */
@Serializable
sealed interface Adjustment {
    @Serializable @SerialName("brightness_contrast")
    data class BrightnessContrast(val brightness: Float = 0f, val contrast: Float = 0f) : Adjustment

    @Serializable @SerialName("levels")
    data class Levels(
        val inputBlack: Float = 0f,
        val inputWhite: Float = 1f,
        val gamma: Float = 1f,
        val outputBlack: Float = 0f,
        val outputWhite: Float = 1f,
        /** Optional per-channel overrides in R, G, B order. */
        val perChannel: List<Levels> = emptyList(),
    ) : Adjustment

    @Serializable @SerialName("curves")
    data class Curves(
        val rgb: Curve = Curve.LINEAR,
        val red: Curve = Curve.LINEAR,
        val green: Curve = Curve.LINEAR,
        val blue: Curve = Curve.LINEAR,
    ) : Adjustment

    @Serializable @SerialName("hue_saturation")
    data class HueSaturation(
        val hue: Float = 0f,
        val saturation: Float = 0f,
        val lightness: Float = 0f,
        val colorize: Boolean = false,
    ) : Adjustment

    @Serializable @SerialName("exposure")
    data class Exposure(val exposure: Float = 0f, val offset: Float = 0f, val gamma: Float = 1f) : Adjustment

    @Serializable @SerialName("vibrance")
    data class Vibrance(val vibrance: Float = 0f, val saturation: Float = 0f) : Adjustment

    @Serializable @SerialName("color_balance")
    data class ColorBalance(
        val shadows: Vec3 = Vec3.ZERO,
        val midtones: Vec3 = Vec3.ZERO,
        val highlights: Vec3 = Vec3.ZERO,
        val preserveLuminosity: Boolean = true,
    ) : Adjustment

    @Serializable @SerialName("black_white")
    data class BlackWhite(val weights: List<Float> = listOf(0.4f, 0.6f, 0.4f, 0.6f, 0.2f, 0.8f)) : Adjustment

    /** Remaps luminance through a gradient; the workhorse for monochrome and duotone grading. */
    @Serializable @SerialName("gradient_map")
    data class GradientMap(val gradient: Fill.Gradient, val dither: Boolean = false) : Adjustment

    @Serializable @SerialName("photo_filter")
    data class PhotoFilter(
        val color: Color,
        val density: Float = 0.25f,
        val preserveLuminosity: Boolean = true,
    ) : Adjustment

    @Serializable @SerialName("invert")
    data object Invert : Adjustment

    @Serializable @SerialName("posterize")
    data class Posterize(val levels: Int = 4) : Adjustment

    @Serializable @SerialName("threshold")
    data class Threshold(val level: Float = 0.5f) : Adjustment

    @Serializable @SerialName("lut")
    data class ColorLookup(val asset: AssetId, val amount: Float = 1f) : Adjustment
}

/**
 * A node in the document tree.
 *
 * [style] and [mask] live here rather than on individual subtypes so a group can carry effects
 * applied after its children flatten — which is exactly how the reference PSDs assemble their
 * layered looks.
 */
@Serializable
sealed interface Layer {
    val id: LayerId
    val name: String
    val transform: Transform
    val opacity: Float
    val blendMode: BlendMode
    val visible: Boolean
    val locked: Boolean
    val style: Style
    val mask: LayerMask?
    val vectorMask: VectorMask?

    /** When true this layer is clipped to the first unclipped layer below it. */
    val clipped: Boolean

    @Serializable @SerialName("text")
    data class Text(
        override val id: LayerId,
        val spec: TextSpec,
        /** Non-null switches this layer to the PBR renderer. */
        val geometry3D: Geometry3D? = null,
        override val name: String = spec.text.take(32),
        override val transform: Transform = Transform.IDENTITY,
        override val opacity: Float = 1f,
        override val blendMode: BlendMode = BlendMode.NORMAL,
        override val visible: Boolean = true,
        override val locked: Boolean = false,
        override val style: Style = Style.PLAIN_BLACK,
        override val mask: LayerMask? = null,
        override val vectorMask: VectorMask? = null,
        override val clipped: Boolean = false,
    ) : Layer

    @Serializable @SerialName("image")
    data class Image(
        override val id: LayerId,
        val asset: AssetId,
        /** Crop within the source image, in normalised coordinates. */
        val crop: Rect? = null,
        override val name: String = "Image",
        override val transform: Transform = Transform.IDENTITY,
        override val opacity: Float = 1f,
        override val blendMode: BlendMode = BlendMode.NORMAL,
        override val visible: Boolean = true,
        override val locked: Boolean = false,
        override val style: Style = Style(fill = Fill.Solid(Color.TRANSPARENT)),
        override val mask: LayerMask? = null,
        override val vectorMask: VectorMask? = null,
        override val clipped: Boolean = false,
    ) : Layer

    @Serializable @SerialName("shape")
    data class Shape(
        override val id: LayerId,
        val geometry: ShapeGeometry,
        override val name: String = "Shape",
        override val transform: Transform = Transform.IDENTITY,
        override val opacity: Float = 1f,
        override val blendMode: BlendMode = BlendMode.NORMAL,
        override val visible: Boolean = true,
        override val locked: Boolean = false,
        override val style: Style = Style.PLAIN_BLACK,
        override val mask: LayerMask? = null,
        override val vectorMask: VectorMask? = null,
        override val clipped: Boolean = false,
    ) : Layer

    @Serializable @SerialName("group")
    data class Group(
        override val id: LayerId,
        val children: List<Layer> = emptyList(),
        /**
         * Pass-through lets children blend with what is below the group. Any other mode, or a
         * non-empty effect stack, forces the group to flatten first.
         */
        val passThrough: Boolean = true,
        val expanded: Boolean = true,
        override val name: String = "Group",
        override val transform: Transform = Transform.IDENTITY,
        override val opacity: Float = 1f,
        override val blendMode: BlendMode = BlendMode.NORMAL,
        override val visible: Boolean = true,
        override val locked: Boolean = false,
        override val style: Style = Style(fill = Fill.Solid(Color.TRANSPARENT)),
        override val mask: LayerMask? = null,
        override val vectorMask: VectorMask? = null,
        override val clipped: Boolean = false,
    ) : Layer {
        val isolates: Boolean get() = !passThrough || style.activeEffects.isNotEmpty()
    }

    @Serializable @SerialName("adjustment")
    data class AdjustmentLayer(
        override val id: LayerId,
        val adjustment: Adjustment,
        override val name: String = "Adjustment",
        override val transform: Transform = Transform.IDENTITY,
        override val opacity: Float = 1f,
        override val blendMode: BlendMode = BlendMode.NORMAL,
        override val visible: Boolean = true,
        override val locked: Boolean = false,
        override val style: Style = Style(fill = Fill.Solid(Color.TRANSPARENT)),
        override val mask: LayerMask? = null,
        override val vectorMask: VectorMask? = null,
        override val clipped: Boolean = false,
    ) : Layer

    /**
     * A live reference to another layer subtree, Photoshop's smart object.
     *
     * The reference PSDs contain 125 of these; a single source is instanced up to 29 times to build
     * one effect. Import needs it, and it is what makes "edit once, update everywhere" work.
     */
    @Serializable @SerialName("instance")
    data class Instance(
        override val id: LayerId,
        val source: LayerId,
        override val name: String = "Instance",
        override val transform: Transform = Transform.IDENTITY,
        override val opacity: Float = 1f,
        override val blendMode: BlendMode = BlendMode.NORMAL,
        override val visible: Boolean = true,
        override val locked: Boolean = false,
        override val style: Style = Style(fill = Fill.Solid(Color.TRANSPARENT)),
        override val mask: LayerMask? = null,
        override val vectorMask: VectorMask? = null,
        override val clipped: Boolean = false,
    ) : Layer
}

/**
 * Copies a layer's shared properties without knowing which kind it is.
 *
 * The properties on [Layer] are declared `val`, so every edit that touches one — moving a layer,
 * locking it, restyling it — would otherwise need its own six-branch `when` at the call site. There
 * is one here instead, and adding a layer kind breaks this build until it is handled.
 *
 * A `null` mask is a real value, so passing one clears the mask; leaving the argument out keeps it.
 */
fun Layer.with(
    name: String = this.name,
    transform: Transform = this.transform,
    opacity: Float = this.opacity,
    blendMode: BlendMode = this.blendMode,
    visible: Boolean = this.visible,
    locked: Boolean = this.locked,
    style: Style = this.style,
    mask: LayerMask? = this.mask,
    vectorMask: VectorMask? = this.vectorMask,
    clipped: Boolean = this.clipped,
): Layer = when (this) {
    is Layer.Text -> copy(
        name = name, transform = transform, opacity = opacity, blendMode = blendMode,
        visible = visible, locked = locked, style = style, mask = mask,
        vectorMask = vectorMask, clipped = clipped,
    )
    is Layer.Image -> copy(
        name = name, transform = transform, opacity = opacity, blendMode = blendMode,
        visible = visible, locked = locked, style = style, mask = mask,
        vectorMask = vectorMask, clipped = clipped,
    )
    is Layer.Shape -> copy(
        name = name, transform = transform, opacity = opacity, blendMode = blendMode,
        visible = visible, locked = locked, style = style, mask = mask,
        vectorMask = vectorMask, clipped = clipped,
    )
    is Layer.Group -> copy(
        name = name, transform = transform, opacity = opacity, blendMode = blendMode,
        visible = visible, locked = locked, style = style, mask = mask,
        vectorMask = vectorMask, clipped = clipped,
    )
    is Layer.AdjustmentLayer -> copy(
        name = name, transform = transform, opacity = opacity, blendMode = blendMode,
        visible = visible, locked = locked, style = style, mask = mask,
        vectorMask = vectorMask, clipped = clipped,
    )
    is Layer.Instance -> copy(
        name = name, transform = transform, opacity = opacity, blendMode = blendMode,
        visible = visible, locked = locked, style = style, mask = mask,
        vectorMask = vectorMask, clipped = clipped,
    )
}

fun Layer.withTransform(transform: Transform): Layer = with(transform = transform)

fun Layer.withStyle(style: Style): Layer = with(style = style)

/**
 * Re-identifies a layer and, for a group, everything inside it.
 *
 * Duplication needs this: leaving a copy sharing its source's ids makes every later lookup ambiguous
 * and edits land on whichever one the walk reaches first.
 */
fun Layer.withId(id: LayerId, freshId: (LayerId) -> LayerId = { LayerId(it.value + "'") }): Layer =
    when (this) {
        is Layer.Text -> copy(id = id)
        is Layer.Image -> copy(id = id)
        is Layer.Shape -> copy(id = id)
        is Layer.AdjustmentLayer -> copy(id = id)
        is Layer.Instance -> copy(id = id)
        is Layer.Group -> copy(
            id = id,
            children = children.map { it.withId(freshId(it.id), freshId) },
        )
    }
