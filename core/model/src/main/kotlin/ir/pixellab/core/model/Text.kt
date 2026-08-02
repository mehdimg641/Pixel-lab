package ir.pixellab.core.model

import kotlinx.serialization.Serializable

/**
 * A font referenced by identity, never by file path.
 *
 * The user adds and rearranges font files continuously, so a document that stored a path would
 * break the moment a folder moved. Resolution matches on [postScriptName] first, then falls back to
 * [family] plus the nearest weight, and finally to a system font with a warning. A project always
 * opens.
 */
@Serializable
data class FontRef(
    val family: String,
    val postScriptName: String? = null,
    /** OS/2 usWeightClass, 1..1000. Used to pick the closest static file when the exact one is gone. */
    val weight: Int = 400,
    val italic: Boolean = false,
    /**
     * Variable-font axis settings, keyed by four-character tag.
     *
     * This is how elongation is applied on fonts that support it: `KASH` (or `kash`) runs 0..100 as
     * a continuous axis, which keeps the text content clean instead of padding it with U+0640.
     */
    val variations: Map<String, Float> = emptyMap(),
    /**
     * OpenType feature toggles such as `ss01`, `salt`, `calt`. Six of the supplied typefaces ship
     * stylistic sets carrying alternate Persian letterforms; no mobile editor exposes them.
     */
    val features: Map<String, Int> = emptyMap(),
) {
    companion object {
        const val AXIS_WEIGHT = "wght"
        const val AXIS_WIDTH = "wdth"
        const val AXIS_SLANT = "slnt"

        /** Both casings occur in the wild: Dana ships `KASH`, Morabba ships `kash`. */
        val KASHIDA_AXES = listOf("KASH", "kash")
    }

    /** The elongation axis value if this font exposes one. */
    val kashidaAxis: Float?
        get() = KASHIDA_AXES.firstNotNullOfOrNull { variations[it] }
}

@Serializable
enum class TextAlign { START, CENTER, END, JUSTIFY }

@Serializable
enum class TextDirection {
    /** Resolved per paragraph from the first strong character. Correct for mixed Persian/Latin. */
    AUTO,
    LTR,
    RTL,
}

/**
 * How to elongate Persian and Arabic text.
 *
 * Two mechanisms are needed because only some fonts support the good one. [VARIABLE_AXIS] drives a
 * font axis and leaves the string untouched; [TATWEEL] inserts U+0640 at legal joining positions,
 * which works everywhere but alters the text content.
 */
@Serializable
enum class KashidaMode { NONE, VARIABLE_AXIS, TATWEEL, AUTO }

@Serializable
data class ParagraphStyle(
    val align: TextAlign = TextAlign.START,
    val direction: TextDirection = TextDirection.AUTO,
    /** Multiplier on the font's default line height. */
    val lineHeight: Float = 1.2f,
    /** Extra space between characters, in em units. */
    val letterSpacing: Float = 0f,
    val wordSpacing: Float = 0f,
    val paragraphSpacing: Float = 0f,
    val indent: Float = 0f,
    val kashida: KashidaMode = KashidaMode.AUTO,
    /** Elongation strength 0..1, mapped onto the font axis range or tatweel run length. */
    val kashidaAmount: Float = 0f,
    /** Prefer Persian digits when the font provides both. */
    val persianDigits: Boolean = true,
)

/**
 * Photoshop's Warp Text.
 *
 * Applied to the *outline* after shaping, not to the baseline before it. Warping the baseline bends
 * where the letters sit and leaves each one upright, which is the flag-shaped result every naive
 * implementation produces; warping the outline bends the letters themselves, which is what the
 * panel actually does.
 */
@Serializable
enum class WarpStyle {
    NONE,
    ARC, ARC_LOWER, ARC_UPPER, ARCH, BULGE,
    SHELL_LOWER, SHELL_UPPER,
    FLAG, WAVE, FISH, RISE,
    FISHEYE, INFLATE, SQUEEZE, TWIST,
    ;

    /** Photoshop's own Persian wording, which is what the user already reads these as. */
    val persianLabel: String
        get() = when (this) {
            NONE -> "بدون"
            ARC -> "کمان"
            ARC_LOWER -> "کمان پایین"
            ARC_UPPER -> "کمان بالا"
            ARCH -> "طاق"
            BULGE -> "برآمدگی"
            SHELL_LOWER -> "صدف پایین"
            SHELL_UPPER -> "صدف بالا"
            FLAG -> "پرچم"
            WAVE -> "موج"
            FISH -> "ماهی"
            RISE -> "خیزش"
            FISHEYE -> "چشم‌ماهی"
            INFLATE -> "باد شده"
            SQUEEZE -> "فشرده"
            TWIST -> "پیچش"
        }
}

/** The Warp Text panel: a style and its three sliders. */
@Serializable
data class TextWarp(
    val style: WarpStyle = WarpStyle.NONE,
    /** -1..1, Photoshop's Bend. */
    val bend: Float = 0.5f,
    /** -1..1, horizontal distortion. */
    val horizontal: Float = 0f,
    /** -1..1, vertical distortion. */
    val vertical: Float = 0f,
    /** False warps down the page instead of across it. */
    val horizontalAxis: Boolean = true,
) {
    val isActive: Boolean get() = style != WarpStyle.NONE

    companion object {
        val NONE = TextWarp()
    }
}

/**
 * How a text box behaves.
 *
 * Point type grows with the string; area type wraps inside a fixed box and is what a paragraph
 * actually needs. The distinction is not cosmetic — it decides whether typing makes the layer wider
 * or makes it taller.
 */
@Serializable
enum class TextBoxMode { POINT, AREA }

/** Text laid out along a path rather than a straight baseline. */
@Serializable
sealed interface TextPath {
    /** Bends the baseline into an arc. Negative [radius] curves the other way. */
    @Serializable
    data class Arc(val radius: Float, val startAngle: Float = -90f, val flip: Boolean = false) : TextPath

    @Serializable
    data class Custom(val points: List<Vec2>, val closed: Boolean = false) : TextPath
}

/** Physically-based material for a 3D text or shape layer. */
@Serializable
data class Material(
    val baseColor: Color = Color.WHITE,
    val baseColorTexture: AssetId? = null,
    /** 0 = dielectric, 1 = metal. Gold edges sit at 1.0 with low roughness. */
    val metallic: Float = 0f,
    val roughness: Float = 0.4f,
    val normalTexture: AssetId? = null,
    val normalStrength: Float = 1f,
    /** Clear lacquer over the base; what separates glossy plastic from bare colour. */
    val clearCoat: Float = 0f,
    val clearCoatRoughness: Float = 0.1f,
    val emissive: Color = Color.TRANSPARENT,
) {
    companion object {
        val GLOSSY_WHITE = Material(baseColor = Color.WHITE, roughness = 0.25f, clearCoat = 1f)
        val GOLD = Material(baseColor = Color(1f, 0.77f, 0.34f), metallic = 1f, roughness = 0.25f)
        val CHROME = Material(baseColor = Color(0.95f, 0.95f, 0.97f), metallic = 1f, roughness = 0.05f)
        val MATTE = Material(roughness = 0.9f)
    }
}

@Serializable
data class Light(
    val direction: Vec3 = Vec3(-0.4f, -0.7f, -0.6f),
    val color: Color = Color.WHITE,
    /**
     * Radiance, not a 0..1 dimmer.
     *
     * The default is above one on purpose. A physically-based diffuse term divides the albedo by π,
     * so a white surface lit head-on by a light of intensity 1 comes back at 0.32 — mid grey. Every
     * "why is my PBR render so dark" begins there, and the answer is that a real key light is
     * several times brighter than the number 1 suggests.
     */
    val intensity: Float = 3.2f,
    val castsShadow: Boolean = true,
)

@Serializable
data class LightRig(
    val key: Light = Light(),
    val fill: Light = Light(direction = Vec3(0.5f, -0.2f, -0.8f), intensity = 1.1f, castsShadow = false),
    val rim: Light? = null,
    /** Image-based lighting. Chrome and polished metal are unreachable without it. */
    val environment: AssetId? = null,
    val environmentIntensity: Float = 1f,
    val environmentRotation: Float = 0f,
)

/**
 * True 3D geometry for a layer, rendered by the PBR path rather than the effect stack.
 *
 * Reserved for what stacked extrusion genuinely cannot fake: free rotation about all three axes,
 * environment reflections, and curvature-following speculars. Flat and 2.5D styles leave this null
 * and go through [Effect.Extrude], which is far cheaper.
 */
@Serializable
data class Geometry3D(
    val depth: Float = 20f,
    val bevelProfile: Curve = Curve.ROUNDED,
    val bevelSize: Float = 4f,
    /**
     * Separate materials for the three surfaces of an extruded glyph. The reference styles paint
     * gold on the bevel and side walls while keeping the face white, which a single material
     * cannot express.
     */
    val faceMaterial: Material = Material.GLOSSY_WHITE,
    val bevelMaterial: Material = Material.GOLD,
    val sideMaterial: Material = Material.GOLD,
    /** Degrees about each axis. */
    val rotation: Vec3 = Vec3.ZERO,
    val lighting: LightRig = LightRig(),
    /** Camera field of view in degrees; lower values flatten the perspective. */
    val fieldOfView: Float = 35f,
)

/** Everything that turns a string into rendered artwork. */
@Serializable
data class TextSpec(
    val text: String,
    val font: FontRef,
    val size: Float = 64f,
    val paragraph: ParagraphStyle = ParagraphStyle(),
    val path: TextPath? = null,
    /** Fixed layout box; null lets the text size itself. */
    val boxSize: Vec2? = null,
    val autoFit: Boolean = false,
    val warp: TextWarp = TextWarp.NONE,
    /**
     * Point type grows with the string; area type wraps inside [boxSize].
     *
     * Kept separate from whether [boxSize] is set, because an area frame with no size yet is a real
     * state — it is what exists between the user starting a drag and finishing it.
     */
    val boxMode: TextBoxMode = TextBoxMode.POINT,
)
