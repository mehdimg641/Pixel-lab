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
        floatUniforms = floats + "uTexelSizeUnused".let { emptySet() },
        vec2Uniforms = vec2s,
        intUniforms = ints,
        samplers = samplers,
    )

    /**
     * Builds the signed distance field the outline-based effects share.
     *
     * Jump flooding is used rather than an exact transform because it runs entirely on the GPU in
     * log(n) passes; the CPU-side exact transform in `core:imaging` is reserved for offline work
     * where its accuracy matters more than its latency.
     */
    val SDF = program(
        id = "sdf_jump_flood",
        body = """
            uniform sampler2D uSeed;
            uniform float uStep;

            void main() {
                vec4 best = texture(uSeed, vUv);
                float bestDist = best.z;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        vec2 offset = vec2(float(dx), float(dy)) * uStep * uTexelSize;
                        vec4 candidate = texture(uSeed, vUv + offset);
                        if (candidate.w < 0.5) continue;
                        float d = distance(vUv, candidate.xy);
                        if (d < bestDist) { best = vec4(candidate.xy, d, 1.0); bestDist = d; }
                    }
                }
                fragColor = best;
            }
        """,
        floats = setOf("uStep"),
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
                fragColor = vec4(0.0, 0.0, 0.0, shadowAlpha);
            }
        """,
        floats = setOf("uBlur", "uSpread"),
        vec2s = setOf("uOffset"),
        ints = setOf("uKnockOut"),
        samplers = setOf("uSource", "uBlurred"),
    )

    val GLOW = program(
        id = "glow",
        body = """
            uniform sampler2D uBlurred;
            uniform float uBlur;
            uniform float uSpread;
            uniform int uInner;
            uniform int uSource_;

            void main() {
                float blurred = texture(uBlurred, vUv).a;
                float layer = texture(uSource, vUv).a;
                // An inner glow lives inside the shape, an outer one outside it.
                float coverage = uInner == 1 ? (1.0 - blurred) * layer : blurred * (1.0 - layer);
                fragColor = vec4(0.0, 0.0, 0.0, clamp(coverage, 0.0, 1.0));
            }
        """,
        floats = setOf("uBlur", "uSpread"),
        ints = setOf("uInner", "uSource_"),
        samplers = setOf("uSource", "uBlurred"),
    )

    val INNER_SHADOW = program(
        id = "inner_shadow",
        body = """
            uniform sampler2D uBlurred;
            uniform vec2 uOffset;
            uniform float uBlur;
            uniform float uChoke;

            void main() {
                float outside = 1.0 - texture(uBlurred, vUv - uOffset * uTexelSize).a;
                float layer = texture(uSource, vUv).a;
                fragColor = vec4(0.0, 0.0, 0.0, clamp(outside * layer, 0.0, 1.0));
            }
        """,
        floats = setOf("uBlur", "uChoke"),
        vec2s = setOf("uOffset"),
        samplers = setOf("uSource", "uBlurred"),
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
                float d = texture(uSdf, uv).r;
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
            uniform float uDistance;
            uniform float uBlur;
            uniform int uInvert;

            void main() {
                vec2 o = vec2(uDistance, uDistance) * uTexelSize;
                float a = texture(uBlurred, vUv + o).a;
                float b = texture(uBlurred, vUv - o).a;
                float v = abs(a - b);
                if (uInvert == 1) v = 1.0 - v;
                fragColor = vec4(0.0, 0.0, 0.0, v * texture(uSource, vUv).a);
            }
        """,
        floats = setOf("uDistance", "uBlur"),
        ints = setOf("uInvert"),
        samplers = setOf("uSource", "uBlurred"),
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
            uniform float uStepIndex;

            void main() {
                float t = uStepCount > 1.0 ? uStepIndex / (uStepCount - 1.0) : 0.0;
                vec2 shifted = vUv - uStepOffset * uStepIndex * uTexelSize;
                float alpha = texture(uSource, shifted).a;
                vec4 near = texture(uNearFill, vUv);
                vec4 far = texture(uFarFill, vUv);
                vec3 colour = mix(near.rgb, far.rgb, t);
                float opacity = mix(1.0, uFarOpacity, t);
                fragColor = premultiply(vec4(colour, alpha * opacity));
            }
        """,
        floats = setOf("uFarOpacity", "uStepCount", "uStepIndex"),
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
                fragColor = vec4(c.rgb, c.a * smoothstep(-0.5, 0.5, d));
            }
        """,
        floats = setOf("uAmount", "uDetail", "uSeed"),
        samplers = setOf("uSource", "uSdf"),
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
        SDF, STROKE, SHADOW, GLOW, INNER_SHADOW, BEVEL, SATIN, OVERLAY,
        EXTRUDE_STEP, REFLECTION, CHROMATIC_OFFSET, BACKDROP_BLUR, NOISE, EDGE_ROUGHEN, BLUR,
    ).associateBy { it.id }

    operator fun get(id: String): ShaderProgram? = ALL[id]
}
