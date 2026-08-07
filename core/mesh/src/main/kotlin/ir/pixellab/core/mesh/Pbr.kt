package ir.pixellab.core.mesh

import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Light
import ir.pixellab.core.model.LightRig
import ir.pixellab.core.model.Material
import ir.pixellab.core.model.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Physically-based shading — Cook-Torrance with GGX.
 *
 * The reason to use this rather than Phong, on a text effect of all things: metal. A gold letter is
 * the single most requested cover treatment, and Phong cannot produce one at all. Metal has no
 * diffuse response and its specular is *tinted by the base colour*, which is exactly the thing the
 * energy-conserving formulation encodes and the ad-hoc one does not. A Phong "gold" is a yellow
 * plastic letter with a white highlight, and everyone can tell.
 *
 * Everything here runs in linear light. Shading in gamma space is the other half of why cheap 3D
 * text looks wrong — the falloff on a curved bevel comes out too dark in the mid-tones, and no
 * amount of adjusting the lights fixes it because the error is in the multiply, not the values.
 */
object Pbr {

    /**
     * Shades one point.
     *
     * @param normal unit, in world space.
     * @param view unit, pointing from the surface *towards* the camera.
     */
    fun shade(
        normal: Vec3,
        view: Vec3,
        material: Material,
        rig: LightRig,
        ambient: Color = AMBIENT,
        /**
         * Where reflections come from. Defaults to the generated studio, which is why a gold letter
         * looks like gold on a first launch with no assets at all; a document naming a real
         * environment gets that one instead.
         */
        environment: EnvironmentMap = Studio,
    ): Color {
        val n = normal.normalised()
        val v = view.normalised()
        val nDotV = max(n dot v, MIN_DOT)

        val roughness = material.roughness.coerceIn(MIN_ROUGHNESS, 1f)
        val metallic = material.metallic.coerceIn(0f, 1f)
        val base = Vec3(material.baseColor.r, material.baseColor.g, material.baseColor.b)

        // A dielectric reflects about 4% at normal incidence whatever its colour; a metal reflects
        // its own colour. That single line is the whole difference between gold and yellow plastic.
        val f0 = Vec3(
            DIELECTRIC + (base.x - DIELECTRIC) * metallic,
            DIELECTRIC + (base.y - DIELECTRIC) * metallic,
            DIELECTRIC + (base.z - DIELECTRIC) * metallic,
        )
        // Metal has no diffuse response at all, because the light that would scatter is absorbed.
        val diffuseColor = base * (1f - metallic)

        // The environment, and it is not a nicety. Three point lights cannot light a mirror: the
        // chance of a light's mirror direction landing on any given face is nil, so chrome shaded
        // by direct light alone comes out a flat grey slab. A metal's appearance *is* what it
        // reflects, and with no environment there is nothing to reflect.
        val reflected = (n * (2f * nDotV) - v).normalised()
        val specularEnvironment = environment.sample(reflected, roughness)
        // Fresnel at the viewing angle, weakened by roughness so a matte surface does not develop
        // a mirror rim it has no business having.
        val ambientF = fresnelRoughness(nDotV, f0, roughness, metallic)

        // A very rough sample along the normal stands in for the irradiance a diffuse surface
        // gathers. Crude next to a real convolution, and it buys the thing that matters: a face
        // turned away from every light is tinted by the room rather than being black.
        val diffuseEnvironment = environment.sample(n, 1f)

        var result = Vec3(
            diffuseColor.x * diffuseEnvironment.x * ambient.r * AMBIENT_GAIN +
                specularEnvironment.x * ambientF.x * ENVIRONMENT_GAIN,
            diffuseColor.y * diffuseEnvironment.y * ambient.g * AMBIENT_GAIN +
                specularEnvironment.y * ambientF.y * ENVIRONMENT_GAIN,
            diffuseColor.z * diffuseEnvironment.z * ambient.b * AMBIENT_GAIN +
                specularEnvironment.z * ambientF.z * ENVIRONMENT_GAIN,
        )

        for (light in listOfNotNull(rig.key, rig.fill, rig.rim)) {
            result = result + contribution(light, n, v, nDotV, roughness, f0, diffuseColor, material)
        }

        if (material.clearCoat > 0f) result = result + clearCoat(material, n, v, nDotV, rig)

        // What passes *through* rather than bouncing off, blended over the opaque result. Last,
        // because it replaces the body of the surface while leaving its highlights alone — a glass
        // letter still has a specular edge, and losing that is what makes cheap glass look like a
        // hole cut in the picture.
        if (material.transmission > 0f) {
            val amount = material.transmission.coerceIn(0f, 1f) * (1f - metallic)
            val through = refracted(n, v, material, roughness, environment)
            result = Vec3(
                result.x * (1f - amount) + through.x * amount,
                result.y * (1f - amount) + through.y * amount,
                result.z * (1f - amount) + through.z * amount,
            )
        }

        return Color(
            r = result.x + material.emissive.r,
            g = result.y + material.emissive.g,
            b = result.z + material.emissive.b,
            a = 1f,
        )
    }

    private fun contribution(
        light: Light,
        n: Vec3,
        v: Vec3,
        nDotV: Float,
        roughness: Float,
        f0: Vec3,
        diffuseColor: Vec3,
        material: Material,
    ): Vec3 {
        // The rig stores the direction light *travels*; the shading needs the direction towards it.
        val l = (light.direction * -1f).normalised()
        val nDotL = n dot l
        if (nDotL <= 0f) return Vec3.ZERO

        val h = (l + v).normalised()
        val nDotH = max(n dot h, 0f)
        val vDotH = max(v dot h, MIN_DOT)

        val d = if (material.anisotropy != 0f) {
            anisotropicDistribution(n, h, roughness, material.anisotropy)
        } else {
            distribution(nDotH, roughness)
        }
        val g = geometry(nDotL, nDotV, roughness)
        val f = fresnel(vDotH, f0)

        val denominator = 4f * nDotV * nDotL + MIN_DOT
        val specular = Vec3(
            d * g * f.x / denominator,
            d * g * f.y / denominator,
            d * g * f.z / denominator,
        )

        // Whatever is reflected is not transmitted. Skipping this is the most common way a
        // hand-rolled shader ends up brighter than the light that lit it.
        val kd = Vec3(1f - f.x, 1f - f.y, 1f - f.z)
        val radiance = Vec3(
            light.color.r * light.intensity,
            light.color.g * light.intensity,
            light.color.b * light.intensity,
        )

        val lit = Vec3(
            (kd.x * diffuseColor.x / PI.toFloat() + specular.x) * radiance.x * nDotL,
            (kd.y * diffuseColor.y / PI.toFloat() + specular.y) * radiance.y * nDotL,
            (kd.z * diffuseColor.z / PI.toFloat() + specular.z) * radiance.z * nDotL,
        )
        if (material.sheen <= 0f) return lit
        return lit + sheen(material, nDotH, nDotL, nDotV, radiance)
    }

    /**
     * The fuzz lobe — what makes cloth look like cloth.
     *
     * Wool, velvet and felt are brightest at their **silhouette**, not where they face the light,
     * because the fibres standing off the surface catch it side-on. No setting of roughness produces
     * that: a rough dielectric dims evenly in every direction, which is why fabric rendered without
     * this reads as matte plastic and why every attempt to fix it by making the surface rougher
     * makes it worse.
     *
     * The distribution is Estevez–Kulla's "Charlie" — an inverted power of the sine rather than the
     * usual exponential of the cosine — because it is the one that peaks *away* from the normal and
     * therefore actually produces the rim. Its visibility term is approximated by the simple
     * `1/(4(l·n + v·n − l·n·v·n))` that ships with it, which is not energy-conserving and is what
     * everyone shipping this lobe in real time uses.
     */
    private fun sheen(material: Material, nDotH: Float, nDotL: Float, nDotV: Float, radiance: Vec3): Vec3 {
        val amount = material.sheen.coerceIn(0f, 1f)
        // Reusing the surface roughness would tie the fuzz to the body, and they are different
        // things: velvet is a smooth backing under a very diffuse pile.
        val alpha = max(1f - amount, MIN_ROUGHNESS)
        val invR = 1f / alpha
        val sin2 = max(1f - nDotH * nDotH, 0f)
        val d = (2f + invR) * sin2.pow(invR * 0.5f) / TWO_PI
        val visibility = 1f / (4f * (nDotL + nDotV - nDotL * nDotV) + MIN_DOT)
        val strength = d * visibility * amount * nDotL
        return Vec3(
            material.sheenColor.r * radiance.x * strength,
            material.sheenColor.g * radiance.y * strength,
            material.sheenColor.b * radiance.z * strength,
        )
    }

    /**
     * GGX stretched along one axis — brushed metal rather than a mirror.
     *
     * The scratches all run one way, so the reflection smears *across* them into a band instead of
     * staying a point. That band is the entire visual difference between brushed steel and chrome,
     * and neither roughness nor a texture can fake it: a rougher mirror is a blurrier point.
     *
     * The tangent is the world X axis, and the sign of [anisotropy] turns the brushing through 90°
     * by swapping which roughness is the stretched one. Taking it from geometry instead would mean
     * carrying a tangent frame through the extruder for a control whose only meaningful settings on
     * a letter are "along" and "across".
     */
    private fun anisotropicDistribution(n: Vec3, h: Vec3, roughness: Float, anisotropy: Float): Float {
        val strength = abs(anisotropy).coerceIn(0f, ANISOTROPY_LIMIT)
        val alpha = roughness * roughness
        val stretched = alpha / max(1f - strength, MIN_ROUGHNESS)
        val squeezed = alpha * (1f - strength)
        val (alongAlpha, acrossAlpha) = if (anisotropy >= 0f) stretched to squeezed else squeezed to stretched

        // A tangent frame from the world axes, made orthogonal to whatever the normal happens to be.
        val tangent = orthogonalise(Vec3(1f, 0f, 0f), n)
        val bitangent = n cross tangent

        val hDotT = h dot tangent
        val hDotB = h dot bitangent
        val hDotN = max(h dot n, MIN_DOT)

        val term = (hDotT * hDotT) / (alongAlpha * alongAlpha) +
            (hDotB * hDotB) / (acrossAlpha * acrossAlpha) +
            hDotN * hDotN
        return 1f / (PI.toFloat() * alongAlpha * acrossAlpha * term * term + MIN_DOT)
    }

    /**
     * What the environment looks like *through* the surface.
     *
     * The view direction is bent by Snell's law and the environment sampled along it, which is what
     * a real-time renderer does and is honest about its limit: a glass letter shows the *room*
     * behind it distorted, not the geometry behind it. On a title that is the whole effect. It would
     * not be enough for a lens, and this is not a lens.
     *
     * Total internal reflection is handled by falling back to the reflected direction, which is what
     * physically happens and also stops the square root going imaginary.
     */
    private fun refracted(
        n: Vec3,
        v: Vec3,
        material: Material,
        roughness: Float,
        environment: EnvironmentMap,
    ): Vec3 {
        val eta = 1f / max(material.ior, 1f)
        val cosI = (n dot v).coerceIn(-1f, 1f)
        val k = 1f - eta * eta * (1f - cosI * cosI)
        val direction = if (k < 0f) {
            (n * (2f * cosI) - v).normalised()
        } else {
            (v * -eta + n * (eta * cosI - sqrt(k))).normalised()
        }
        val sample = environment.sample(direction, roughness)
        // Tinted by the base colour, so coloured glass is possible at all — clear glass is simply
        // a base colour of white, which is what the preset uses.
        return Vec3(
            sample.x * material.baseColor.r,
            sample.y * material.baseColor.g,
            sample.z * material.baseColor.b,
        )
    }

    /** Gram-Schmidt: the part of [candidate] that is perpendicular to [normal], unit length. */
    private fun orthogonalise(candidate: Vec3, normal: Vec3): Vec3 {
        val projected = candidate - normal * (candidate dot normal)
        // A normal that happens to be the X axis leaves nothing behind; any other axis will do.
        return if (projected dot projected < MIN_DOT) (Vec3(0f, 1f, 0f) cross normal).normalised() else projected.normalised()
    }

    /**
     * A thin lacquer over the base, shaded as a second, always-smooth dielectric layer.
     *
     * What separates glossy plastic from bare colour, and the reason a "candy" letter looks like
     * one: the coat's highlight is white even when the paint beneath it is red, because the coat is
     * a dielectric whatever the layer under it is made of.
     */
    private fun clearCoat(material: Material, n: Vec3, v: Vec3, nDotV: Float, rig: LightRig): Vec3 {
        val roughness = material.clearCoatRoughness.coerceIn(MIN_ROUGHNESS, 1f)
        val strength = material.clearCoat.coerceIn(0f, 1f)
        var total = Vec3.ZERO
        for (light in listOfNotNull(rig.key, rig.fill, rig.rim)) {
            val l = (light.direction * -1f).normalised()
            val nDotL = n dot l
            if (nDotL <= 0f) continue
            val h = (l + v).normalised()
            val d = distribution(max(n dot h, 0f), roughness)
            val g = geometry(nDotL, nDotV, roughness)
            val f = schlick(max(v dot h, MIN_DOT), DIELECTRIC)
            val amount = d * g * f * strength * nDotL / (4f * nDotV * nDotL + MIN_DOT)
            total = total + Vec3(
                light.color.r * light.intensity * amount,
                light.color.g * light.intensity * amount,
                light.color.b * light.intensity * amount,
            )
        }
        return total
    }

    /**
     * GGX / Trowbridge-Reitz normal distribution.
     *
     * Chosen over Beckmann for its tail: GGX keeps energy far from the highlight's centre, which is
     * what gives a metal its long soft falloff instead of a hard disc with nothing around it. On a
     * gold bevel that tail *is* the look.
     */
    internal fun distribution(nDotH: Float, roughness: Float): Float {
        val a = roughness * roughness
        val a2 = a * a
        val d = nDotH * nDotH * (a2 - 1f) + 1f
        // No epsilon added to the denominator. `d` is never below a2, and MIN_ROUGHNESS keeps a2
        // above zero — whereas an epsilon of the size the rest of this file uses is *larger* than
        // d² for a polished surface, and it flattens the very peak the highlight is made of. A
        // mirror shaded that way comes out darker than a matte one.
        return a2 / (PI.toFloat() * d * d).coerceAtLeast(MIN_DENOMINATOR)
    }

    /** Smith's height-correlated masking and shadowing, with the direct-lighting remap of k. */
    internal fun geometry(nDotL: Float, nDotV: Float, roughness: Float): Float {
        val r = roughness + 1f
        val k = r * r / 8f
        val gl = nDotL / (nDotL * (1f - k) + k)
        val gv = nDotV / (nDotV * (1f - k) + k)
        return gl * gv
    }

    /**
     * A studio, computed rather than loaded.
     *
     * The app ships no assets, so the environment a metal reflects has to be generated — and for
     * this job that is barely a constraint. A photographic studio is three things: a big soft
     * source above and slightly to one side, a darker floor, and a mid-grey surround. All three are
     * a function of the reflected direction's height, plus one lobe for the softbox, which is
     * exactly what a letter needs to read as metal.
     *
     * The **gradient across the height is the whole effect**. A chrome letter looks like chrome
     * because its curved bevel sweeps from reflecting the bright ceiling to reflecting the dark
     * floor within a few pixels, and that sharp light-to-dark sweep is what the eye reads as a
     * mirror. A single flat ambient colour cannot produce it at any intensity.
     *
     * @param roughness blurs the reflection by fading towards the environment's average, which is
     *   what a real prefiltered map converges to at its coarsest level.
     */
    internal fun studio(direction: Vec3, roughness: Float): Vec3 {
        val d = direction.normalised()

        // Sky above, floor below, with a soft transition rather than a horizon line — a hard line
        // would be reflected as a hard line, and there is no such edge in a lit room.
        val height = ((d.y + 1f) * 0.5f).coerceIn(0f, 1f)
        val sky = smoothstep(HORIZON_LOW, HORIZON_HIGH, height)
        var colour = Vec3(
            lerp(FLOOR_R, CEILING_R, sky),
            lerp(FLOOR_G, CEILING_G, sky),
            lerp(FLOOR_B, CEILING_B, sky),
        )

        // The softbox: a broad bright lobe up and to the left, placed to agree with the default key
        // light. A highlight that came from somewhere the lights are not would read as an error
        // even though nobody could say why.
        val toBox = (d dot SOFTBOX).coerceAtLeast(0f)
        val box = toBox.pow(SOFTBOX_TIGHTNESS) * SOFTBOX_INTENSITY
        colour = colour + Vec3(box, box * SOFTBOX_WARM, box * SOFTBOX_WARM * SOFTBOX_WARM)

        val blur = roughness.coerceIn(0f, 1f)
        if (blur <= 0f) return colour
        // Towards the average, which is where a prefiltered environment ends up at its blurriest.
        val average = Vec3(AVERAGE_R, AVERAGE_G, AVERAGE_B)
        val t = blur * blur
        return Vec3(
            lerp(colour.x, average.x, t),
            lerp(colour.y, average.y, t),
            lerp(colour.z, average.z, t),
        )
    }

    /**
     * Fresnel for an ambient reflection, weakened by roughness and by metalness.
     *
     * Two corrections to the plain Schlick term, and both are visible rather than theoretical.
     * Roughness caps how far it climbs, or a matte surface grows a bright mirror rim and reads as
     * being wrapped in foil.
     *
     * And the grazing lift applies to the **dielectric part only**. This is the one that matters
     * here: a letter's bevel is seen almost edge-on across most of its width, so if a metal's weak
     * channels are dragged up to a neutral ceiling there, the tint is destroyed exactly where the
     * eye is looking — and a gold letter comes back mauve. A metal keeps its colour at grazing, so
     * this keeps it.
     */
    internal fun fresnelRoughness(nDotV: Float, f0: Vec3, roughness: Float, metallic: Float): Vec3 {
        val lift = (1f - nDotV).coerceIn(0f, 1f).pow(5) * (1f - metallic.coerceIn(0f, 1f))
        val ceiling = (1f - roughness).coerceIn(0f, 1f)
        return Vec3(
            f0.x + (max(ceiling, f0.x) - f0.x) * lift,
            f0.y + (max(ceiling, f0.y) - f0.y) * lift,
            f0.z + (max(ceiling, f0.z) - f0.z) * lift,
        )
    }

    private fun smoothstep(from: Float, to: Float, x: Float): Float {
        val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** Schlick's approximation, per channel, so a metal's tint carries into its highlight. */
    internal fun fresnel(vDotH: Float, f0: Vec3): Vec3 {
        val f = (1f - vDotH).coerceIn(0f, 1f).pow(5)
        return Vec3(
            f0.x + (1f - f0.x) * f,
            f0.y + (1f - f0.y) * f,
            f0.z + (1f - f0.z) * f,
        )
    }

    private fun schlick(vDotH: Float, f0: Float): Float =
        f0 + (1f - f0) * (1f - vDotH).coerceIn(0f, 1f).pow(5)

    /**
     * ACES filmic tone mapping, then gamma.
     *
     * Needed because a specular highlight on metal genuinely exceeds 1.0 and clipping it flat turns
     * the brightest part of a gold letter into a white patch with no shape. The filmic curve rolls
     * it off instead, which is the difference between a highlight that reads as light and one that
     * reads as a hole in the picture.
     */
    fun toneMap(linear: Color): Color = Color(
        r = encode(aces(linear.r)),
        g = encode(aces(linear.g)),
        b = encode(aces(linear.b)),
        a = linear.a,
    )

    private fun aces(x: Float): Float {
        val v = max(x, 0f)
        return ((v * (ACES_A * v + ACES_B)) / (v * (ACES_C * v + ACES_D) + ACES_E)).coerceIn(0f, 1f)
    }

    /** sRGB transfer, not a plain 1/2.2 — the linear toe matters in the shadows of a bevel. */
    private fun encode(linear: Float): Float {
        val v = linear.coerceIn(0f, 1f)
        return if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f
    }

    /** And the way back, for a base colour authored as an sRGB swatch. */
    fun decode(encoded: Float): Float {
        val v = encoded.coerceIn(0f, 1f)
        return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    }

    fun decode(color: Color) = Color(decode(color.r), decode(color.g), decode(color.b), color.a)

    /**
     * A little light from everywhere, standing in for an environment map.
     *
     * Not physically an ambient term, and it is here because the alternative is worse: with only
     * three lights, every surface facing away from all of them is pure black, and a letter with
     * black regions reads as a rendering failure rather than as a dark side.
     */
    val AMBIENT = Color(0.16f, 0.17f, 0.19f)

    /** Below this a highlight becomes a single infinitely bright pixel that aliases into a star. */
    private const val MIN_ROUGHNESS = 0.03f

    /** Every non-metal reflects about 4% straight on, which is where this number comes from. */
    private const val DIELECTRIC = 0.04f

    /** Scales the environment's contribution to the diffuse term against the direct lights. */
    private const val AMBIENT_GAIN = 3.4f

    /**
     * How much of the environment a surface actually reflects.
     *
     * Below one, and the reason is the geometry rather than the physics: a letter's bevel is seen
     * almost edge-on over most of its width, and at grazing angles Fresnel drives every channel
     * towards the same value. Left at full strength the room's neutral grey therefore swamps the
     * tint, and a gold letter comes back mauve. Turning the room down keeps the *direct* highlight
     * — the one that carries the metal's colour — as the thing the eye reads.
     */
    private const val ENVIRONMENT_GAIN = 0.85f

    // The studio, as numbers. A ceiling brighter than the floor by roughly a stop and a half, which
    // is what a softbox over a dark cyclorama actually measures.
    private const val CEILING_R = 0.62f
    private const val CEILING_G = 0.66f
    private const val CEILING_B = 0.74f
    private const val FLOOR_R = 0.07f
    private const val FLOOR_G = 0.07f
    private const val FLOOR_B = 0.08f
    private const val AVERAGE_R = 0.28f
    private const val AVERAGE_G = 0.30f
    private const val AVERAGE_B = 0.34f

    private const val HORIZON_LOW = 0.35f
    private const val HORIZON_HIGH = 0.72f

    /** Up, to the left and towards the viewer — the same quarter the default key light comes from. */
    private val SOFTBOX = Vec3(-0.35f, 0.72f, 0.6f).normalised()
    private const val SOFTBOX_TIGHTNESS = 12f
    private const val SOFTBOX_INTENSITY = 2.6f

    /** A touch warm, because a real softbox is, and a perfectly neutral one looks like a render. */
    private const val SOFTBOX_WARM = 0.97f

    private const val MIN_DOT = 1e-4f

    private const val TWO_PI = (2.0 * PI).toFloat()

    /**
     * How far apart the two roughnesses may be pulled.
     *
     * At 1.0 the squeezed axis reaches zero width and the highlight becomes an infinitely thin line
     * carrying infinite energy — a row of blown-out pixels across a letter. Brushed metal is nowhere
     * near that limit anyway; the look is a band, not a wire.
     */
    private const val ANISOTROPY_LIMIT = 0.92f

    /** Only to keep the division finite; far below any value a real roughness can produce. */
    private const val MIN_DENOMINATOR = 1e-12f

    private const val ACES_A = 2.51f
    private const val ACES_B = 0.03f
    private const val ACES_C = 2.43f
    private const val ACES_D = 0.59f
    private const val ACES_E = 0.14f
}

/** Euclidean length of a colour treated as a vector; used only in tests and diagnostics. */
internal fun Color.magnitude(): Float = sqrt(r * r + g * g + b * b)

internal fun approximately(a: Float, b: Float, tolerance: Float = 1e-4f) = abs(a - b) <= tolerance
