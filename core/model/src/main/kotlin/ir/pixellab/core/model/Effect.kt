package ir.pixellab.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class StrokePosition { INSIDE, CENTER, OUTSIDE }

@Serializable
enum class BevelStyle { OUTER_BEVEL, INNER_BEVEL, EMBOSS, PILLOW_EMBOSS, STROKE_EMBOSS }

@Serializable
enum class BevelTechnique { SMOOTH, CHISEL_HARD, CHISEL_SOFT }

@Serializable
enum class BevelDirection { UP, DOWN }

@Serializable
enum class GlowSource { CENTER, EDGE }

/**
 * A single entry in a layer's effect stack.
 *
 * Two properties of this model matter more than any individual effect:
 *
 * 1. **Effects are an ordered list and every type may repeat.** Commercial styles rely on this —
 *    the reference PSDs stack ten drop shadows on one layer to fake a soft realistic shadow, and
 *    three strokes to build concentric outlines. Modelling effects as boolean fields would make
 *    those styles impossible to represent.
 * 2. **Effects live on [Layer], not on text.** A group carries effects too, applied after its
 *    children are flattened, which is how the reference files build their 3D looks.
 */
@Serializable
sealed interface Effect {
    val enabled: Boolean
    val blendMode: BlendMode
    val opacity: Float

    @Serializable @SerialName("stroke")
    data class Stroke(
        val width: Float,
        val fill: Fill,
        val position: StrokePosition = StrokePosition.OUTSIDE,
        /** Dash pattern in canvas units; empty means a solid line. */
        val dash: List<Float> = emptyList(),
        val dashOffset: Float = 0f,
        override val enabled: Boolean = true,
        override val blendMode: BlendMode = BlendMode.NORMAL,
        override val opacity: Float = 1f,
    ) : Effect

    @Serializable @SerialName("drop_shadow")
    data class DropShadow(
        val color: Color = Color.BLACK,
        /** Degrees, clockwise. */
        val angle: Float = 90f,
        val distance: Float = 0f,
        /** Grows the shadow before blurring. Photoshop labels this "spread". */
        val spread: Float = 0f,
        val blur: Float = 0f,
        val contour: Curve = Curve.LINEAR,
        val noise: Float = 0f,
        /** When true the layer's own pixels punch a hole through its shadow. */
        val knockOut: Boolean = true,
        /** Ties [angle] to the document light source so all effects move together. */
        val useGlobalLight: Boolean = true,
        override val enabled: Boolean = true,
        override val blendMode: BlendMode = BlendMode.MULTIPLY,
        override val opacity: Float = 0.75f,
    ) : Effect

    @Serializable @SerialName("inner_shadow")
    data class InnerShadow(
        val color: Color = Color.BLACK,
        val angle: Float = 90f,
        val distance: Float = 0f,
        val choke: Float = 0f,
        val blur: Float = 0f,
        val contour: Curve = Curve.LINEAR,
        val noise: Float = 0f,
        val useGlobalLight: Boolean = true,
        override val enabled: Boolean = true,
        override val blendMode: BlendMode = BlendMode.MULTIPLY,
        override val opacity: Float = 0.75f,
    ) : Effect

    @Serializable @SerialName("outer_glow")
    data class OuterGlow(
        val fill: Fill = Fill.Solid(Color.WHITE),
        val spread: Float = 0f,
        val blur: Float = 8f,
        val contour: Curve = Curve.LINEAR,
        val noise: Float = 0f,
        override val enabled: Boolean = true,
        override val blendMode: BlendMode = BlendMode.SCREEN,
        override val opacity: Float = 0.75f,
    ) : Effect

    @Serializable @SerialName("inner_glow")
    data class InnerGlow(
        val fill: Fill = Fill.Solid(Color.WHITE),
        val source: GlowSource = GlowSource.EDGE,
        val choke: Float = 0f,
        val blur: Float = 8f,
        val contour: Curve = Curve.LINEAR,
        val noise: Float = 0f,
        override val enabled: Boolean = true,
        override val blendMode: BlendMode = BlendMode.SCREEN,
        override val opacity: Float = 0.75f,
    ) : Effect

    /**
     * Bevel and emboss. The backbone of the reference styles — 99 instances across six PSDs, with
     * depths from 174% to 317%.
     */
    @Serializable @SerialName("bevel")
    data class Bevel(
        val style: BevelStyle = BevelStyle.INNER_BEVEL,
        val technique: BevelTechnique = BevelTechnique.SMOOTH,
        /** Percentage; values well above 100 are normal and produce the inflated look. */
        val depth: Float = 100f,
        val direction: BevelDirection = BevelDirection.UP,
        val size: Float = 8f,
        val soften: Float = 0f,
        val angle: Float = 90f,
        /** Light elevation in degrees; 90 is straight on. */
        val altitude: Float = 30f,
        val useGlobalLight: Boolean = true,
        /** Shapes the bevel shoulder — the difference between rounded and chiselled. */
        val profile: Curve = Curve.ROUNDED,
        /** Applied to the specular response, producing metallic ringing when non-linear. */
        val glossContour: Curve = Curve.LINEAR,
        val highlightColor: Color = Color.WHITE,
        val highlightBlend: BlendMode = BlendMode.SCREEN,
        val highlightOpacity: Float = 0.75f,
        val shadowColor: Color = Color.BLACK,
        val shadowBlend: BlendMode = BlendMode.MULTIPLY,
        val shadowOpacity: Float = 0.75f,
        /** Optional relief texture; supplies the normal detail for leather, stone and fabric. */
        val texture: Fill.Pattern? = null,
        val textureDepth: Float = 0f,
        val textureInvert: Boolean = false,
        override val enabled: Boolean = true,
        override val blendMode: BlendMode = BlendMode.NORMAL,
        override val opacity: Float = 1f,
    ) : Effect

    @Serializable @SerialName("satin")
    data class Satin(
        val color: Color = Color.BLACK,
        val angle: Float = 90f,
        val distance: Float = 8f,
        val blur: Float = 8f,
        val contour: Curve = Curve.EASE_IN_OUT,
        val invert: Boolean = false,
        override val enabled: Boolean = true,
        override val blendMode: BlendMode = BlendMode.MULTIPLY,
        override val opacity: Float = 0.5f,
    ) : Effect

    @Serializable @SerialName("overlay")
    data class Overlay(
        val fill: Fill,
        override val enabled: Boolean = true,
        override val blendMode: BlendMode = BlendMode.NORMAL,
        override val opacity: Float = 1f,
    ) : Effect

    /**
     * Parametric multi-step extrusion.
     *
     * The reference PSDs achieve their depth by duplicating a smart object up to 29 times at a few
     * pixels' offset, each copy carrying its own gradient and bevel. Expressing that as one
     * parameterised effect collapses those 29 layers into a single editable layer, and turns depth
     * into a slider instead of a copy-paste chore.
     */
    @Serializable @SerialName("extrude")
    data class Extrude(
        val steps: Int = 12,
        /** Offset applied per step, in canvas units. */
        val stepOffset: Vec2 = Vec2(-2f, 2f),
        /** Fill of the step nearest the face. */
        val nearFill: Fill = Fill.Solid(Color.BLACK),
        /** Fill of the deepest step; steps in between interpolate. */
        val farFill: Fill = Fill.Solid(Color.BLACK),
        /** Shapes how quickly [nearFill] gives way to [farFill] across the steps. */
        val falloff: Curve = Curve.LINEAR,
        /** Opacity of the deepest step, letting the extrusion fade into shadow. */
        val farOpacity: Float = 1f,
        /** Optional bevel applied to every step, producing ridged sides. */
        val stepBevel: Bevel? = null,
        override val enabled: Boolean = true,
        override val blendMode: BlendMode = BlendMode.NORMAL,
        override val opacity: Float = 1f,
    ) : Effect {
        init {
            require(steps in 1..512) { "extrude steps must be 1..512, got $steps" }
        }
    }

    @Serializable @SerialName("reflection")
    data class Reflection(
        val gap: Float = 8f,
        val height: Float = 0.5f,
        val startOpacity: Float = 0.5f,
        val endOpacity: Float = 0f,
        val blur: Float = 0f,
        override val enabled: Boolean = true,
        override val blendMode: BlendMode = BlendMode.NORMAL,
        override val opacity: Float = 1f,
    ) : Effect

    /** Per-channel offset, the RGB split seen on retro and glitch styles. */
    @Serializable @SerialName("chromatic_offset")
    data class ChromaticOffset(
        val redOffset: Vec2 = Vec2(-2f, 0f),
        val greenOffset: Vec2 = Vec2.ZERO,
        val blueOffset: Vec2 = Vec2(2f, 0f),
        override val enabled: Boolean = true,
        override val blendMode: BlendMode = BlendMode.NORMAL,
        override val opacity: Float = 1f,
    ) : Effect

    /**
     * Frosted-glass blur of everything already composited beneath this layer.
     *
     * Unlike every other effect here this one reads the destination buffer rather than the layer,
     * which is why it is only practical on the GPU compositor.
     */
    @Serializable @SerialName("backdrop_blur")
    data class BackdropBlur(
        val radius: Float = 24f,
        val saturation: Float = 1f,
        val brightness: Float = 1f,
        val tint: Color = Color.TRANSPARENT,
        /** Grain on the glass surface; real frosted glass is never perfectly smooth. */
        val grain: Float = 0f,
        override val enabled: Boolean = true,
        override val blendMode: BlendMode = BlendMode.NORMAL,
        override val opacity: Float = 1f,
    ) : Effect

    @Serializable @SerialName("noise")
    data class Noise(
        val amount: Float = 0.1f,
        val scale: Float = 1f,
        val monochrome: Boolean = true,
        override val enabled: Boolean = true,
        override val blendMode: BlendMode = BlendMode.NORMAL,
        override val opacity: Float = 1f,
    ) : Effect

    /** Erodes the silhouette with fractal noise — torn paper, grunge edges, distressed type. */
    @Serializable @SerialName("edge_roughen")
    data class EdgeRoughen(
        val amount: Float = 4f,
        val detail: Float = 0.5f,
        val seed: Int = 0,
        override val enabled: Boolean = true,
        override val blendMode: BlendMode = BlendMode.NORMAL,
        override val opacity: Float = 1f,
    ) : Effect
}

/**
 * The complete look of a layer's shape: how its interior is painted plus its ordered effect stack.
 *
 * A saved preset is exactly this object serialised. That equivalence is deliberate — it means a
 * style library costs one small JSON file per entry, an applied preset stays fully editable because
 * it is nothing but the values already exposed in the UI, and a PSD importer can emit presets
 * directly.
 */
@Serializable
data class Style(
    val fill: Fill = Fill.Solid(Color.BLACK),
    /**
     * Opacity of the fill alone, leaving effects at full strength. Separate from
     * [Layer.opacity] so a shape can be hollowed out to leave only its stroke.
     */
    val fillOpacity: Float = 1f,
    val effects: List<Effect> = emptyList(),
) {
    val activeEffects: List<Effect> get() = effects.filter { it.enabled }

    fun withEffect(effect: Effect) = copy(effects = effects + effect)

    companion object {
        val PLAIN_BLACK = Style()
    }
}
