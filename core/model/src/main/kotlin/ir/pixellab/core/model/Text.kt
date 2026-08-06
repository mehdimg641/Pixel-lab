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
    /**
     * Paint this surface its base colour and do not light it.
     *
     * Not a shortcut — it is how a *stroke* is expressed on real geometry, and without it a whole
     * family of type treatment is unreachable. The bright rim tracing a letter in a poster title is
     * a constant colour: it reads the same on the edge facing the light and the edge facing away,
     * because in the Photoshop stack it came from a stroke rather than from a surface. Shade that
     * same rim physically and it can only be bright where it faces the key light, which is correct
     * and is not the look. A designer asking for a gold line round their letter is not asking for
     * gold; they are asking for a line.
     */
    val unlit: Boolean = false,
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
    /**
     * The dots and vowel marks, treated separately from the letter's body.
     *
     * Persian carries meaning off the stroke — ب پ ت ث are one shape and one, two or three dots —
     * and the specification (§۶.۹.۳) asks for those to have their own depth, their own material and
     * the ability to float clear of the body. Every value here is a *multiple* of the body's, so the
     * treatment survives a change of depth instead of needing to be re-tuned each time.
     *
     * Both default to leaving the dots exactly as they were, so an existing document renders
     * unchanged.
     */
    val markDepth: Float = 1f,
    val markLift: Float = 0f,
    /** Null means the dots take whichever material their surface would have taken anyway. */
    val markMaterial: Material? = null,

    /**
     * A picture painted across the letter's face, in place of [faceFill].
     *
     * The pattern the layer-effect path already understands, now reaching the mesh renderer too.
     * Before this, a face could carry a photograph *or* real extruded geometry and not both — the
     * effect path fakes its depth with offset copies, so a painted face came with no perspective and
     * no lit walls, and the mesh path could only ramp between colours. Every commercial title of
     * this kind is a painted face inside real metal, and having to choose between the two halves was
     * the largest single reason our renders read as imitations of one.
     *
     * A pattern rather than any [Fill] because that is the case the mesh cannot already express: a
     * solid is a material's base colour and a gradient is [faceFill]. Wins over [faceFill] when both
     * are set — a caller who supplies a picture asked for the picture.
     */
    val facePattern: Fill.Pattern? = null,

    /**
     * A gradient painted across the letter's face, in place of [faceMaterial]'s flat base colour.
     *
     * Only the face, and that is the whole reason it exists here rather than as a layer effect. A
     * gradient overlay on the finished render would wash over the bevel and the side walls too,
     * and every reference treatment of this kind — a coloured face inside metal edges — depends on
     * the two being painted differently. The other surfaces keep their own materials.
     *
     * Mapped across the letter's own bounds rather than the canvas, so the ramp reads the same
     * whether the word sits in a corner or fills the page, and travels with the text if it moves.
     * Everything else about the material — its roughness, its metalness, its coat — still applies;
     * this replaces the colour being lit, not the lighting.
     */
    val faceFill: Fill.Gradient? = null,

    /**
     * A gradient painted along the depth of the side walls, in place of [sideMaterial]'s colour.
     *
     * Ramped from the front of the extrusion to the back rather than across the letter, because that
     * is the axis the look lives on: a poster title's block runs bright where it leaves the face and
     * falls into shadow at the far end, evenly, on every letter and every edge.
     *
     * A physically shaded metal cannot be asked for that. Its brightness is decided by where each
     * wall faces relative to the light, so one side of a letter blazes and the opposite side goes
     * black — correct, and not what the treatment is. This makes the falloff something the designer
     * states rather than something the lighting happens to produce.
     */
    val sideFill: Fill.Gradient? = null,
    /**
     * How far the back of the extrusion leans sideways, per unit of depth.
     *
     * Zero extrudes straight back, which is what the renderer has always done and which hides the
     * side walls exactly when the face is squarest to the camera. The only way to reveal depth then
     * is [rotation], and turning a letter foreshortens and skews its face in the same movement.
     *
     * The block-letter treatment on almost every poster title is this instead: the letters stand
     * upright and frontal while their depth runs off at an angle. It is a shear, not a rotation, and
     * the two are not interchangeable at any angle.
     *
     * There is no safe range to respect. This carried a warning that gaps opened in the block past
     * roughly a third, which turned out to be a misreading: the extrusion had never been a closed
     * surface, and leaning it was only the first thing to make that visible. The surface is closed
     * now and the lean is bounded by taste alone.
     */
    val extrusionTilt: Vec2 = Vec2.ZERO,

    /** Degrees about each axis. */
    val rotation: Vec3 = Vec3.ZERO,
    val lighting: LightRig = LightRig(),
    /** Camera field of view in degrees; lower values flatten the perspective. */
    val fieldOfView: Float = 35f,

    /**
     * The shadow the letters throw onto whatever is behind them.
     *
     * Null means none, and that was the only behaviour available until now — [Light.castsShadow] had
     * been in the model since the rig was written, set at two call sites, and read by no renderer at
     * all. A document could say the key light cast a shadow, save it, reload it, and no pixel ever
     * changed.
     *
     * It belongs to the *look* rather than to the renderer's arguments for the same reason the bevel
     * does: the same letters with a tight dark shadow and with a wide soft one are two different
     * pieces of design, and the difference has to survive being saved.
     */
    val shadow: ShadowCast? = ShadowCast(),

    /**
     * The shadow the letter's own edge throws *inward*, across its face.
     *
     * The one thing every reference cover of this kind has that a plain extrusion does not. It reads
     * as the face being set slightly *inside* a raised rim, and it is what stops a coloured face
     * looking like paint applied to the front of a block. Without it the face is uniformly lit right
     * up to the edge, which is the single clearest tell between our render and a designed one.
     *
     * **Off by default**, unlike the cast shadow, and the difference is worth stating. Every solid
     * object in the world throws a shadow onto what is behind it, so a render without one is wrong;
     * an inner shadow is a *decision* a designer makes about how the face should sit. Photoshop
     * defaults its own Inner Shadow off for the same reason. Switching it on for everybody would
     * silently darken the face of every 3D letter in every document that already exists.
     */
    val innerShadow: InnerShadow? = null,
)

/**
 * A shadow cast onto a surface by its own boundary.
 *
 * Photoshop's Inner Shadow, and computed the same way, because the same way is the right one: the
 * face is flat, so the occluder is its own outline and the answer is the outline offset, blurred,
 * and kept where the face is. Nothing about the mesh produces this on its own — the face is the
 * front-most surface and nothing on the letter stands above it to cast — so it is a piece of design
 * expressed in the model rather than a consequence of the geometry, and it is described here as such
 * rather than dressed up as physics.
 *
 * Both lengths are fractions of the **letter's own height on screen**, not of the frame and not in
 * pixels. That is what makes a look survive being re-rendered for export: a nine-pixel inner shadow
 * that was right on a preview is invisible at print size, and one measured against the frame changes
 * the moment the type is reframed.
 */
@Serializable
data class InnerShadow(
    /** Where the light comes from, in degrees anticlockwise from the right. */
    val angle: Float = 120f,
    /** How far the edge's shadow reaches in, as a fraction of the letters' height. */
    val distance: Float = 0.035f,
    /** How far it fades, same units. Zero is a hard band, which is a legitimate poster look. */
    val size: Float = 0.05f,
    val opacity: Float = 0.45f,
    val color: Color = Color.BLACK,
) {
    init {
        require(distance >= 0f) { "distance is a fraction of the letters' height, got $distance" }
        require(size >= 0f) { "size is a fraction of the letters' height, got $size" }
        require(opacity in 0f..1f) { "opacity is 0..1, got $opacity" }
    }
}

/**
 * How far behind the type its shadow falls, and how soft it is when it lands.
 *
 * Everything here is a *ratio* rather than a length, so a look survives being re-rendered at export
 * size. A softness in pixels that was right on a 400px preview is a smear at 4000, which is the
 * usual way a 3D style stops surviving the trip to print.
 */
@Serializable
data class ShadowCast(
    /**
     * How far the shadow is thrown, as a fraction of the type's own height.
     *
     * **Not the depth of the receiving plane, which is what this was first written as and what it
     * physically is.** The plane depth is the honest quantity and a useless control: the throw it
     * produces also depends on the extrusion's depth and on how far off-axis the key light sits, so
     * the same number gave a discreet shadow on one style and a grey slab twice the size of the
     * letters on the next. A designer setting this is asking "how far behind does it fall", and the
     * renderer solves for the plane that answers it. The projection stays exact either way — this
     * chooses which end of it the user holds.
     *
     * A tenth or so reads as a poster; past a third the type starts to float off the page.
     */
    val distance: Float = 0.12f,
    /**
     * The penumbra, as a fraction of the frame.
     *
     * A real shadow's edge softens with distance from what casts it, which is why this and
     * [distance] are separate: a card standing on a table has a sharp edge at its foot and a soft
     * one at its top, and one number cannot say both.
     */
    val softness: Float = 0.18f,
    /** How dark it gets where it is fully occluded. One would be pitch black, which nothing is. */
    val opacity: Float = 0.55f,
    val color: Color = Color.BLACK,

    /**
     * Which way the light that throws it comes from — the shadow's own, not the key's.
     *
     * Photoshop's Drop Shadow has carried a separate Angle since the layer-style panel existed, with
     * "Use Global Light" as an opt-in rather than the rule, and it is worth saying why rather than
     * treating it as a quirk to copy. A shadow's *reach* is the light's lateral direction divided by
     * its axial one, multiplied by the depth of the thing casting it. A key light placed where the
     * metal looks best is usually well off-axis, and an extruded title is deep — so a physically
     * exact shadow from the key comes out longer than the letters are tall. That is not a bug in the
     * arithmetic; it is what a real lamp in that position would do, and it is why nobody lights a
     * poster with one lamp.
     *
     * So this defaults to a near-frontal source, which is what actually throws the compact shadow
     * under a printed title, and the key light is left free to be wherever the highlight wants it.
     *
     * **[distance] cannot go below what the geometry allows.** The receiving surface can sit at most
     * flush against the back of the letters, so a deep extrusion has a shortest possible shadow. Ask
     * for less and you get that.
     */
    val direction: Vec3 = Vec3(0.35f, -0.45f, -1.6f),
) {
    init {
        require(distance >= 0f) { "a shadow cannot fall in front of what casts it, got $distance" }
        require(softness >= 0f) { "softness is a fraction of the frame, got $softness" }
        require(opacity in 0f..1f) { "opacity is 0..1, got $opacity" }
    }
}

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
