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

    /**
     * Per-colour-family CMYK correction — Photoshop's Selective Color.
     *
     * The one adjustment that can push the greens of a landscape without touching a face, because
     * it works on colour *families* rather than on a channel or a hue wheel. Retouchers reach for
     * it when Hue/Saturation is too blunt: shifting the yellows towards magenta warms skin and
     * leaves a blue sky exactly where it was.
     *
     * The three achromatic ranges are the reason it beats a curve for grading. Whites, neutrals and
     * blacks each take their own CMYK, so a cool shadow and a warm highlight are two sliders rather
     * than a hand-drawn three-channel curve.
     */
    @Serializable @SerialName("selective_color")
    data class SelectiveColor(
        val ranges: List<ColorRange> = ColorFamily.entries.map { ColorRange(it) },
        /**
         * Photoshop's Relative/Absolute switch. Relative scales each correction by how much of that
         * ink the pixel already has, so it cannot invent cyan in a pixel that has none — which is
         * what keeps it safe on skin. Absolute adds the full amount regardless, and is what the
         * heavy-handed looks are made of.
         */
        val absolute: Boolean = false,
    ) : Adjustment

    /**
     * Builds each output channel from a weighted sum of all three inputs.
     *
     * Its real use is monochrome conversion: a red filter on black-and-white film is exactly
     * `red = 1, green = 0, blue = 0` here, and that is how a pale sky becomes dramatic. It is also
     * the only sane way to swap or rebalance channels — doing it with curves means matching three
     * of them by eye.
     */
    @Serializable @SerialName("channel_mixer")
    data class ChannelMixer(
        val red: ChannelRecipe = ChannelRecipe(red = 1f),
        val green: ChannelRecipe = ChannelRecipe(green = 1f),
        val blue: ChannelRecipe = ChannelRecipe(blue = 1f),
        /** When set, [gray] drives all three outputs and the per-channel recipes are ignored. */
        val monochrome: Boolean = false,
        val gray: ChannelRecipe = ChannelRecipe(red = 0.4f, green = 0.4f, blue = 0.2f),
    ) : Adjustment

    /**
     * Opens the shadows and pulls back the highlights, each by how dark or bright the pixel's
     * *neighbourhood* is.
     *
     * The one correction a curve genuinely cannot do. Lifting shadows with a curve lifts every dark
     * pixel, including the ones inside a bright subject, so a backlit portrait comes back with a
     * grey face. This asks "is this *region* dark?", and only that question has a useful answer.
     *
     * Photoshop offers it under Image only, never as a layer, because the answer at a pixel depends
     * on pixels a radius away and its adjustment layers are per-pixel. Ours is a layer anyway: the
     * neighbourhood is a blur of the backdrop, and a blur is one more pass. Being non-destructive is
     * strictly better, and there was no reason to inherit the limitation along with the maths.
     *
     * Every field is normalised: Photoshop's 0..100 sliders are 0..1 here, its −100..+100 are
     * −1..+1, and its radii stay in pixels. Defaults are Photoshop's own.
     */
    @Serializable @SerialName("shadows_highlights")
    data class ShadowsHighlights(
        val shadowAmount: Float = 0.35f,
        val shadowTone: Float = 0.5f,
        val shadowRadius: Float = 30f,
        val highlightAmount: Float = 0f,
        val highlightTone: Float = 0.5f,
        val highlightRadius: Float = 30f,
        /** Saturation put back in proportion to how far a pixel moved; Photoshop's Color slider. */
        val color: Float = 0.2f,
        val midtoneContrast: Float = 0f,
        /** What fraction of the darkest and lightest pixels is allowed to clip outright. */
        val blackClip: Float = 0.0001f,
        val whiteClip: Float = 0.0001f,
        /**
         * Where those two fractions actually fall, measured once and frozen.
         *
         * A clip is a *percentile*, and a percentile needs the histogram of everything beneath the
         * layer — which is the one thing a per-pixel compositor cannot count. So the editor measures
         * it when the layer is added or its clips change, and stores the answer here. The defaults
         * are the whole range, which is the identity, so an unmeasured layer clips nothing rather
         * than crushing the picture.
         */
        val blackPoint: Float = 0f,
        val whitePoint: Float = 1f,
    ) : Adjustment

    /**
     * Tone mapping — the operator that fits a scene with more range than the screen onto the screen.
     *
     * [HdrMethod.LOCAL_ADAPTATION] is the one worth having and the one Photoshop opens on: it
     * divides the picture into a slowly-varying base and the detail riding on it, compresses only
     * the base, and puts the detail back untouched. That is why it can flatten a twelve-stop scene
     * without flattening a face — and why the other three methods, which are global curves, cannot.
     *
     * The split is done with a guided filter rather than a Gaussian. A Gaussian base blurs across
     * every edge in the picture, so compressing it leaves a bright halo along each one, and that
     * halo is the single thing that gives bad HDR away.
     */
    @Serializable @SerialName("hdr_toning")
    data class HdrToning(
        val method: HdrMethod = HdrMethod.LOCAL_ADAPTATION,
        val radius: Float = 30f,
        /** How hard the base is compressed. Photoshop's 0..4; one is neutral. */
        val strength: Float = 1f,
        val gamma: Float = 1f,
        /** Stops, as in the Exposure panel. */
        val exposure: Float = 0f,
        /** How much of the detail layer is put back. Photoshop's −300..+300 %, normalised. */
        val detail: Float = 0.3f,
        val shadow: Float = 0f,
        val highlight: Float = 0f,
        val vibrance: Float = 0.2f,
        val saturation: Float = 0.2f,
        /** Photoshop's Toning Curve, applied last, on the composite. */
        val toningCurve: Curve = Curve.LINEAR,
    ) : Adjustment

    /**
     * Photoshop's Desaturate, which is **not** a luminance conversion.
     *
     * It is Hue/Saturation with the saturation at −100, so the grey it produces is HSL lightness —
     * the midpoint of the brightest and darkest channel — not a weighted luma. Pure red becomes mid
     * grey rather than the dark grey a luma conversion gives. Getting this wrong is invisible until
     * a file is opened in both applications side by side, at which point every saturated colour is
     * off, so it is written out here rather than folded into one of the luma paths.
     */
    @Serializable @SerialName("desaturate")
    data object Desaturate : Adjustment

    /**
     * Transplants the colour and tone statistics of another image onto this one.
     *
     * Reinhard's transfer: match the mean and the spread of each channel and two pictures shot under
     * different light agree. Which is why it is the tool for a set of frames that have to cut
     * together, and why it works on a whole image rather than a selection.
     *
     * [statistics] is measured once, when the source is chosen, and frozen into the layer. That is
     * what turns a whole-image analysis into a per-pixel correction the compositor can run, and it
     * is also what makes the result stable — a live measurement would drift every time a layer
     * beneath it changed.
     */
    @Serializable @SerialName("match_color")
    data class MatchColor(
        /**
         * The picture whose look is being transplanted — Photoshop's Source dropdown.
         *
         * An asset rather than a layer because that is what survives: a source layer could be
         * deleted, renamed or merged out from under the adjustment, and the correction would then
         * quietly stop meaning anything. The asset is also what [statistics] was measured from, so
         * the two are always talking about the same pixels.
         */
        val source: AssetId? = null,
        val statistics: ColorStatistics = ColorStatistics(),
        /** Photoshop's Luminance, Color Intensity and Fade, all 0..2, 0..2 and 0..1. */
        val luminance: Float = 1f,
        val colorIntensity: Float = 1f,
        val fade: Float = 0f,
        /** Removes a colour cast by pulling each channel's mean back to the others'. */
        val neutralize: Boolean = false,
    ) : Adjustment

    /**
     * Selects one colour family by similarity and moves it, in one panel.
     *
     * Hue/Saturation's six fixed ranges cannot select a particular blue and leave the rest of the
     * sky; this can, because the selection is a distance from a colour the user picked rather than a
     * slice of the hue wheel. [fuzziness] is the width of that distance, feathered rather than
     * switched so the replaced region does not acquire a hard edge.
     */
    @Serializable @SerialName("replace_color")
    data class ReplaceColor(
        val target: Color = Color.WHITE,
        /** Photoshop's 0..200, normalised. Zero selects only exact matches. */
        val fuzziness: Float = 0.2f,
        /**
         * Photoshop's Localized Color Clusters. Off, the mask is one falloff from the target; on,
         * the falloff is squared, which tightens it around the picked colour and stops a wide
         * fuzziness from leaking into neighbouring hues.
         */
        val localized: Boolean = false,
        val hue: Float = 0f,
        val saturation: Float = 0f,
        val lightness: Float = 0f,
    ) : Adjustment

    /**
     * Redistributes tones so the histogram is flat.
     *
     * Not a slider — the mapping *is* the picture's own cumulative histogram, so every adjustment of
     * this kind is a different curve. [table] holds that curve, measured once and frozen, for the
     * same reason [MatchColor] freezes its statistics: a per-pixel shader cannot count a histogram,
     * and a live count would change under the layer every time anything beneath it moved.
     *
     * An empty table means "not yet measured" and is the identity, so a freshly added layer shows
     * the picture unchanged rather than black.
     */
    @Serializable @SerialName("equalize")
    data class Equalize(val table: List<Float> = emptyList()) : Adjustment
}

/** Photoshop's four tone-mapping methods, in the order its own dropdown lists them. */
@Serializable
enum class HdrMethod {
    EXPOSURE_AND_GAMMA, HIGHLIGHT_COMPRESSION, EQUALIZE_HISTOGRAM, LOCAL_ADAPTATION,
    ;

    val persianLabel: String
        get() = when (this) {
            EXPOSURE_AND_GAMMA -> "نوردهی و گاما"
            HIGHLIGHT_COMPRESSION -> "فشرده‌سازی روشنایی"
            EQUALIZE_HISTOGRAM -> "یکنواخت‌سازی هیستوگرام"
            LOCAL_ADAPTATION -> "انطباق موضعی"
        }
}

/**
 * The mean and spread of an image, per channel, plus its mean luminance.
 *
 * Everything Match Color needs from a source picture, and small enough to live in the document —
 * which matters, because the alternative is the document holding a copy of the source image.
 */
@Serializable
data class ColorStatistics(
    val mean: Vec3 = Vec3(0.5f, 0.5f, 0.5f),
    val deviation: Vec3 = Vec3(0.25f, 0.25f, 0.25f),
    /**
     * Whether a source was ever measured.
     *
     * A flag rather than inferring it from the numbers. Zero spread in a channel is a perfectly
     * ordinary measurement — a photograph of a clear sky has almost none in blue — so treating it
     * as "nothing was measured" would silently disable the whole adjustment on exactly the pictures
     * it is most often pointed at.
     */
    val measured: Boolean = false,
)

/** The nine families Selective Color divides the spectrum into, in Photoshop's own menu order. */
@Serializable
enum class ColorFamily {
    REDS, YELLOWS, GREENS, CYANS, BLUES, MAGENTAS, WHITES, NEUTRALS, BLACKS,
    ;

    val persianLabel: String
        get() = when (this) {
            REDS -> "قرمزها"
            YELLOWS -> "زردها"
            GREENS -> "سبزها"
            CYANS -> "فیروزه‌ای‌ها"
            BLUES -> "آبی‌ها"
            MAGENTAS -> "سرخابی‌ها"
            WHITES -> "سفیدها"
            NEUTRALS -> "خنثی‌ها"
            BLACKS -> "سیاه‌ها"
        }
}

/** One family's ink shift, each component in -1..1. */
@Serializable
data class ColorRange(
    val family: ColorFamily,
    val cyan: Float = 0f,
    val magenta: Float = 0f,
    val yellow: Float = 0f,
    val black: Float = 0f,
) {
    val isIdentity: Boolean get() = cyan == 0f && magenta == 0f && yellow == 0f && black == 0f
}

/** One output channel of the mixer: how much of each input, plus a constant offset. */
@Serializable
data class ChannelRecipe(
    val red: Float = 0f,
    val green: Float = 0f,
    val blue: Float = 0f,
    val constant: Float = 0f,
)

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
