package ir.pixellab.core.render

/**
 * A compiled-shader identity: the program source plus the uniforms it expects.
 *
 * Declaring the uniform names alongside the source lets a test check that what an effect module
 * sends in its [PassDescriptor] is actually what the shader reads. A misspelled uniform is
 * otherwise silent — the effect simply renders with a zeroed parameter.
 */
data class ShaderProgram(
    val id: String,
    val fragment: String,
    val floatUniforms: Set<String> = emptySet(),
    val vec2Uniforms: Set<String> = emptySet(),
    val intUniforms: Set<String> = emptySet(),
    val samplers: Set<String> = emptySet(),
) {
    val allUniforms: Set<String> get() = floatUniforms + vec2Uniforms + intUniforms + samplers
}

/**
 * The effect shader library.
 *
 * Several effects share a signed distance field derived from the layer's alpha: stroke needs the
 * distance to the edge, shadows and glows need it to grow the silhouette before blurring, and bevel
 * turns it into a height field. Computing it once and sampling it from each of those passes is the
 * single biggest saving in the pipeline, and it is why the shaders below take `uSdf` rather than
 * re-deriving an edge from alpha each time.
 */
object Shaders {

    /** Shared prologue: sRGB transfer plus helpers every pass uses. */
    private val COMMON = """
        #version 300 es
        precision highp float;

        in vec2 vUv;

        // Which instance of an instanced pass this is; extrusion is the only user, but it has to be
        // declared here because the shared vertex stage always writes it.
        flat in float vInstance;

        out vec4 fragColor;

        uniform sampler2D uSource;
        uniform vec2 uTexelSize;

        // The compositor works in linear light; effect colours arrive gamma-encoded.
        vec3 toLinear(vec3 c) {
            return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c));
        }
        vec3 toGamma(vec3 c) {
            return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
        }

        vec4 premultiply(vec4 c) { return vec4(c.rgb * c.a, c.a); }
        vec4 unpremultiply(vec4 c) { return c.a > 0.0 ? vec4(c.rgb / c.a, c.a) : c; }

        // Cheap hash for grain and roughening; deterministic per pixel.
        float hash(vec2 p) {
            return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453123);
        }
    """.trimIndent()

    private fun program(
        id: String,
        body: String,
        floats: Set<String> = emptySet(),
        vec2s: Set<String> = emptySet(),
        ints: Set<String> = emptySet(),
        samplers: Set<String> = setOf("uSource"),
    ) = ShaderProgram(
        id = id,
        fragment = COMMON + "\n\n" + body.trimIndent(),
        floatUniforms = floats,
        vec2Uniforms = vec2s,
        intUniforms = ints,
        samplers = samplers,
    )

    /** Stands in for "no seed found yet"; comfortably inside the half-float range. */
    const val SDF_FAR = 8192f

    /**
     * Seeds the jump flood by marking the pixels the silhouette's edge passes through.
     *
     * Seeding the edge rather than the interior means one flood produces the unsigned distance for
     * both sides; the sign is recovered at resolve time from the layer's own alpha. Two floods —
     * inside and outside — is the obvious implementation and twice the work.
     */
    val SDF_SEED = program(
        id = "sdf_seed",
        body = """
            void main() {
                bool inside = texture(uSource, vUv).a >= 0.5;
                bool edge =
                    inside != (texture(uSource, vUv - vec2(uTexelSize.x, 0.0)).a >= 0.5) ||
                    inside != (texture(uSource, vUv + vec2(uTexelSize.x, 0.0)).a >= 0.5) ||
                    inside != (texture(uSource, vUv - vec2(0.0, uTexelSize.y)).a >= 0.5) ||
                    inside != (texture(uSource, vUv + vec2(0.0, uTexelSize.y)).a >= 0.5);
                // xy is the offset in pixels to the nearest seed, z its length, w whether one is known.
                fragColor = edge ? vec4(0.0, 0.0, 0.0, 1.0) : vec4(0.0, 0.0, 8192.0, 0.0);
            }
        """,
    )

    /**
     * One jump-flooding step, run with a halving stride.
     *
     * Jump flooding is used rather than the exact transform because it runs entirely on the GPU in
     * log(n) passes; the CPU-side exact transform in `core:imaging` is reserved for offline work
     * where its accuracy matters more than its latency.
     *
     * The seed position is stored **relative to the current pixel**, not as an absolute coordinate.
     * Absolute coordinates are the obvious encoding and they do not survive half float: at 4096 px a
     * texel is 1/4096 of the UV range, which is below half float's resolution near 1.0, so the field
     * would quantise into visible steps along the right and bottom edges. Relative offsets stay
     * small and are exact.
     */
    val SDF_FLOOD = program(
        id = "sdf_flood",
        body = """
            uniform sampler2D uSeed;
            uniform float uStep;

            void main() {
                vec4 best = texture(uSeed, vUv);
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        vec2 jump = vec2(float(dx), float(dy)) * uStep;
                        vec4 candidate = texture(uSeed, vUv + jump * uTexelSize);
                        if (candidate.w < 0.5) continue;
                        // The neighbour's seed, re-expressed as an offset from this pixel.
                        vec2 offset = jump + candidate.xy;
                        float d = length(offset);
                        if (d < best.z) best = vec4(offset, d, 1.0);
                    }
                }
                fragColor = best;
            }
        """,
        floats = setOf("uStep"),
        samplers = setOf("uSeed"),
    )

    /** Turns the flood result into the signed pixel distance every outline effect samples. */
    val SDF_RESOLVE = program(
        id = "sdf_resolve",
        body = """
            uniform sampler2D uSeed;

            void main() {
                vec4 s = texture(uSeed, vUv);
                float d = s.w > 0.5 ? s.z : 8192.0;
                // Negative inside the shape, positive outside — the convention every consumer reads.
                float inside = texture(uSource, vUv).a >= 0.5 ? -1.0 : 1.0;
                fragColor = vec4(inside * d, 0.0, 0.0, 1.0);
            }
        """,
        samplers = setOf("uSource", "uSeed"),
    )

    val STROKE = program(
        id = "stroke",
        body = """
            uniform sampler2D uSdf;
            uniform sampler2D uFill;
            uniform float uWidth;
            uniform float uOpacity;
            uniform int uPosition;   // 0 inside, 1 centre, 2 outside

            void main() {
                float d = texture(uSdf, vUv).r;
                // Shift the band according to where the stroke sits relative to the edge.
                float inner = uPosition == 0 ? -uWidth : (uPosition == 1 ? -uWidth * 0.5 : 0.0);
                float outer = uPosition == 0 ? 0.0    : (uPosition == 1 ?  uWidth * 0.5 : uWidth);
                float coverage = smoothstep(inner - 0.5, inner + 0.5, d) *
                                 (1.0 - smoothstep(outer - 0.5, outer + 0.5, d));
                vec4 fill = texture(uFill, vUv);
                fragColor = premultiply(vec4(fill.rgb, fill.a * coverage * uOpacity));
            }
        """,
        floats = setOf("uWidth", "uOpacity"),
        ints = setOf("uPosition"),
        samplers = setOf("uSource", "uSdf", "uFill"),
    )

    val SHADOW = program(
        id = "shadow",
        body = """
            uniform sampler2D uBlurred;
            uniform sampler2D uFill;
            uniform vec2 uOffset;
            uniform float uBlur;
            uniform float uSpread;
            uniform int uKnockOut;

            void main() {
                vec2 shifted = vUv - uOffset * uTexelSize;
                float shadowAlpha = texture(uBlurred, shifted).a;
                float layerAlpha = texture(uSource, vUv).a;
                // The layer punches a hole through its own shadow unless told otherwise.
                if (uKnockOut == 1) shadowAlpha *= (1.0 - layerAlpha);
                // The shadow's own colour. Writing black here is the obvious implementation and it
                // silently discards every coloured shadow a style asks for.
                vec4 fill = texture(uFill, vUv);
                fragColor = premultiply(vec4(fill.rgb, fill.a * shadowAlpha));
            }
        """,
        floats = setOf("uBlur", "uSpread"),
        vec2s = setOf("uOffset"),
        ints = setOf("uKnockOut"),
        samplers = setOf("uSource", "uBlurred", "uFill"),
    )

    val GLOW = program(
        id = "glow",
        body = """
            uniform sampler2D uBlurred;
            uniform sampler2D uFill;
            uniform float uBlur;
            uniform float uSpread;
            uniform int uInner;
            uniform int uGlowSource;   // 0 centre, 1 edge

            void main() {
                float blurred = texture(uBlurred, vUv).a;
                float layer = texture(uSource, vUv).a;
                // An inner glow lives inside the shape, an outer one outside it.
                float coverage = uInner == 1 ? (1.0 - blurred) * layer : blurred * (1.0 - layer);
                // Sourced from the centre, the glow fills the shape and fades towards the edge.
                if (uInner == 1 && uGlowSource == 0) coverage = blurred * layer;
                vec4 fill = texture(uFill, vUv);
                fragColor = premultiply(vec4(fill.rgb, fill.a * clamp(coverage, 0.0, 1.0)));
            }
        """,
        floats = setOf("uBlur", "uSpread"),
        ints = setOf("uInner", "uGlowSource"),
        samplers = setOf("uSource", "uBlurred", "uFill"),
    )

    val INNER_SHADOW = program(
        id = "inner_shadow",
        body = """
            uniform sampler2D uBlurred;
            uniform sampler2D uFill;
            uniform vec2 uOffset;
            uniform float uBlur;
            uniform float uChoke;

            void main() {
                float outside = 1.0 - texture(uBlurred, vUv - uOffset * uTexelSize).a;
                float layer = texture(uSource, vUv).a;
                vec4 fill = texture(uFill, vUv);
                fragColor = premultiply(vec4(fill.rgb, fill.a * clamp(outside * layer, 0.0, 1.0)));
            }
        """,
        floats = setOf("uBlur", "uChoke"),
        vec2s = setOf("uOffset"),
        samplers = setOf("uSource", "uBlurred", "uFill"),
    )

    /**
     * Bevel and emboss.
     *
     * The shape's distance field becomes a height field through the profile curve, the height is
     * differentiated into a normal, and the normal is lit. The profile is what separates a rounded
     * shoulder from a hard chamfer, and the gloss contour is what turns a plain highlight into
     * metal.
     */
    val BEVEL = program(
        id = "bevel",
        body = """
            uniform sampler2D uSdf;
            uniform sampler2D uProfile;   // 1D curve LUT
            uniform sampler2D uGloss;     // 1D curve LUT
            uniform float uDepth;
            uniform float uSize;
            uniform float uSoften;
            uniform float uAngle;
            uniform float uAltitude;
            uniform int uStyle;
            uniform int uTechnique;

            float heightAt(vec2 uv) {
                // The field is negative inside; an inner bevel climbs as it goes deeper, an outer
                // one climbs going outwards.
                float d = texture(uSdf, uv).r * (uStyle == 0 ? 1.0 : -1.0);
                float t = clamp(d / max(uSize, 0.001), 0.0, 1.0);
                return texture(uProfile, vec2(t, 0.5)).r;
            }

            void main() {
                vec2 e = uTexelSize * max(uSoften, 1.0);
                float dx = (heightAt(vUv + vec2(e.x, 0.0)) - heightAt(vUv - vec2(e.x, 0.0))) * uDepth;
                float dy = (heightAt(vUv + vec2(0.0, e.y)) - heightAt(vUv - vec2(0.0, e.y))) * uDepth;
                vec3 normal = normalize(vec3(-dx, -dy, 1.0));

                float a = radians(uAngle);
                float el = radians(uAltitude);
                vec3 light = vec3(cos(a) * cos(el), -sin(a) * cos(el), sin(el));
                float ndl = dot(normal, light);

                // Signed: the positive half feeds the highlight, the negative half the shadow.
                float shaped = texture(uGloss, vec2(ndl * 0.5 + 0.5, 0.5)).r * 2.0 - 1.0;
                float layer = texture(uSource, vUv).a;
                fragColor = vec4(vec3(shaped), layer);
            }
        """,
        floats = setOf("uDepth", "uSize", "uSoften", "uAngle", "uAltitude"),
        ints = setOf("uStyle", "uTechnique"),
        samplers = setOf("uSource", "uSdf", "uProfile", "uGloss"),
    )

    val SATIN = program(
        id = "satin",
        body = """
            uniform sampler2D uBlurred;
            uniform sampler2D uFill;
            uniform float uDistance;
            uniform float uBlur;
            uniform int uInvert;

            void main() {
                vec2 o = vec2(uDistance, uDistance) * uTexelSize;
                float a = texture(uBlurred, vUv + o).a;
                float b = texture(uBlurred, vUv - o).a;
                float v = abs(a - b);
                if (uInvert == 1) v = 1.0 - v;
                vec4 fill = texture(uFill, vUv);
                fragColor = premultiply(vec4(fill.rgb, fill.a * v * texture(uSource, vUv).a));
            }
        """,
        floats = setOf("uDistance", "uBlur"),
        ints = setOf("uInvert"),
        samplers = setOf("uSource", "uBlurred", "uFill"),
    )

    val OVERLAY = program(
        id = "overlay",
        body = """
            uniform sampler2D uFill;
            uniform float uOpacity;

            void main() {
                vec4 fill = texture(uFill, vUv);
                float layer = texture(uSource, vUv).a;
                fragColor = premultiply(vec4(fill.rgb, fill.a * layer * uOpacity));
            }
        """,
        floats = setOf("uOpacity"),
        samplers = setOf("uSource", "uFill"),
    )

    /**
     * One step of a parametric extrusion.
     *
     * Instanced: the step index arrives through the vertex stage, so 29 steps are one draw call
     * rather than 29 duplicated layers as the reference PSDs use.
     */
    val EXTRUDE_STEP = program(
        id = "extrude_step",
        body = """
            uniform sampler2D uNearFill;
            uniform sampler2D uFarFill;
            uniform vec2 uStepOffset;
            uniform float uFarOpacity;
            uniform float uStepCount;

            void main() {
                // Instance 0 draws the *farthest* step. GL blends primitives in instance order, so
                // counting down is what puts the near face on top; counting up buries it.
                float index = max(uStepCount - 1.0 - vInstance, 0.0);
                float t = uStepCount > 1.0 ? index / (uStepCount - 1.0) : 0.0;
                vec2 shifted = vUv - uStepOffset * index * uTexelSize;
                float alpha = texture(uSource, shifted).a;
                vec4 near = texture(uNearFill, vUv);
                vec4 far = texture(uFarFill, vUv);
                vec3 colour = mix(near.rgb, far.rgb, t);
                float opacity = mix(1.0, uFarOpacity, t);
                fragColor = premultiply(vec4(colour, alpha * opacity));
            }
        """,
        floats = setOf("uFarOpacity", "uStepCount"),
        vec2s = setOf("uStepOffset"),
        samplers = setOf("uSource", "uNearFill", "uFarFill"),
    )

    val REFLECTION = program(
        id = "reflection",
        body = """
            uniform float uGap;
            uniform float uHeight;
            uniform float uStartOpacity;
            uniform float uEndOpacity;

            void main() {
                float mirrored = 1.0 - vUv.y;
                vec4 c = texture(uSource, vec2(vUv.x, mirrored));
                float t = clamp(vUv.y / max(uHeight, 0.001), 0.0, 1.0);
                fragColor = premultiply(vec4(c.rgb, c.a * mix(uStartOpacity, uEndOpacity, t)));
            }
        """,
        floats = setOf("uGap", "uHeight", "uStartOpacity", "uEndOpacity"),
    )

    val CHROMATIC_OFFSET = program(
        id = "chromatic_offset",
        body = """
            uniform vec2 uRed;
            uniform vec2 uGreen;
            uniform vec2 uBlue;

            void main() {
                float r = texture(uSource, vUv - uRed * uTexelSize).r;
                float g = texture(uSource, vUv - uGreen * uTexelSize).g;
                float b = texture(uSource, vUv - uBlue * uTexelSize).b;
                float a = max(max(texture(uSource, vUv - uRed * uTexelSize).a,
                                  texture(uSource, vUv - uGreen * uTexelSize).a),
                              texture(uSource, vUv - uBlue * uTexelSize).a);
                fragColor = vec4(r, g, b, a);
            }
        """,
        vec2s = setOf("uRed", "uGreen", "uBlue"),
    )

    /**
     * Frosted glass.
     *
     * The only effect that reads the destination rather than the layer, which is why it cannot be
     * cached per layer and why the compositor has to copy the backdrop before this pass runs.
     */
    val BACKDROP_BLUR = program(
        id = "backdrop_blur",
        body = """
            uniform sampler2D uBackdrop;
            uniform float uRadius;
            uniform float uSaturation;
            uniform float uBrightness;
            uniform float uGrain;

            void main() {
                vec4 c = texture(uBackdrop, vUv);
                float luma = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722));
                vec3 saturated = mix(vec3(luma), c.rgb, uSaturation) * uBrightness;
                // Real frosted glass is never perfectly smooth.
                float grain = (hash(vUv * 1024.0) - 0.5) * uGrain;
                float mask = texture(uSource, vUv).a;
                fragColor = premultiply(vec4(saturated + grain, mask));
            }
        """,
        floats = setOf("uRadius", "uSaturation", "uBrightness", "uGrain"),
        samplers = setOf("uSource", "uBackdrop"),
    )

    val NOISE = program(
        id = "noise",
        body = """
            uniform float uAmount;
            uniform float uScale;
            uniform int uMono;

            void main() {
                vec4 c = texture(uSource, vUv);
                vec2 p = vUv / max(uScale, 0.001) * 1024.0;
                float n = hash(p) - 0.5;
                vec3 noise = uMono == 1
                    ? vec3(n)
                    : vec3(n, hash(p + 17.0) - 0.5, hash(p + 43.0) - 0.5);
                fragColor = vec4(clamp(c.rgb + noise * uAmount, 0.0, 1.0), c.a);
            }
        """,
        floats = setOf("uAmount", "uScale"),
        ints = setOf("uMono"),
    )

    val EDGE_ROUGHEN = program(
        id = "edge_roughen",
        body = """
            uniform sampler2D uSdf;
            uniform float uAmount;
            uniform float uDetail;
            uniform float uSeed;

            float fbm(vec2 p) {
                float total = 0.0;
                float amplitude = 0.5;
                for (int i = 0; i < 4; i++) {
                    total += (hash(floor(p) + uSeed) - 0.5) * amplitude;
                    p *= 2.0;
                    amplitude *= mix(0.2, 0.7, uDetail);
                }
                return total;
            }

            void main() {
                // Displacing the distance field erodes the silhouette instead of just fading it.
                float d = texture(uSdf, vUv).r + fbm(vUv * 128.0) * uAmount;
                vec4 c = texture(uSource, vUv);
                // The field is negative inside, so coverage falls off as d rises through zero.
                fragColor = vec4(c.rgb, c.a * (1.0 - smoothstep(-0.5, 0.5, d)));
            }
        """,
        floats = setOf("uAmount", "uDetail", "uSeed"),
        samplers = setOf("uSource", "uSdf"),
    )

    /**
     * The layer's own body.
     *
     * Kept separate from the overlay effect because fill opacity is not layer opacity: dropping the
     * fill to zero has to leave every effect at full strength, which is what makes hollow text —
     * stroke and shadow with nothing between them — possible at all.
     */
    val FILL = program(
        id = "fill",
        body = """
            uniform sampler2D uFill;
            uniform float uFillOpacity;

            void main() {
                vec4 fill = texture(uFill, vUv);
                float layer = texture(uSource, vUv).a;
                fragColor = premultiply(vec4(fill.rgb, fill.a * layer * uFillOpacity));
            }
        """,
        floats = setOf("uFillOpacity"),
        samplers = setOf("uSource", "uFill"),
    )

    /** Separable Gaussian, run twice; the workhorse behind shadows and glows. */
    val BLUR = program(
        id = "blur",
        body = """
            uniform vec2 uDirection;
            uniform float uRadius;

            void main() {
                float sigma = max(uRadius, 0.0001) / 3.0;
                float total = 0.0;
                vec4 sum = vec4(0.0);
                int taps = int(min(ceil(uRadius), 64.0));
                for (int i = -64; i <= 64; i++) {
                    if (i < -taps || i > taps) continue;
                    float x = float(i);
                    float w = exp(-(x * x) / (2.0 * sigma * sigma));
                    sum += texture(uSource, vUv + uDirection * x * uTexelSize) * w;
                    total += w;
                }
                fragColor = sum / max(total, 0.0001);
            }
        """,
        floats = setOf("uRadius"),
        vec2s = setOf("uDirection"),
    )

    /** Every program, keyed by the id an effect module puts in its descriptor. */
    val ALL: Map<String, ShaderProgram> = listOf(
        SDF_SEED, SDF_FLOOD, SDF_RESOLVE, STROKE, SHADOW, GLOW, INNER_SHADOW, BEVEL, SATIN, OVERLAY,
        EXTRUDE_STEP, REFLECTION, CHROMATIC_OFFSET, BACKDROP_BLUR, NOISE, EDGE_ROUGHEN, BLUR, FILL,
    ).associateBy { it.id }

    operator fun get(id: String): ShaderProgram? = ALL[id]
}
