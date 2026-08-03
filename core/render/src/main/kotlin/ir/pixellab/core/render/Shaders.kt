package ir.pixellab.core.render

import ir.pixellab.core.model.Tone

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
            // Layer-texture UV to fill UV. Identity for a solid; for a placed photograph it lands
            // the pixels exactly on the layer's own box, which is smaller than the texture whenever
            // an effect has grown it. Sampling at vUv instead slides the image inside its own frame
            // by however much bleed the effect stack asked for.
            uniform mat3 uFillMap;

            void main() {
                vec2 uv = (uFillMap * vec3(vUv, 1.0)).xy;
                vec4 fill = (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0)
                    ? vec4(0.0)
                    : texture(uFill, uv);
                float layer = texture(uSource, vUv).a;
                fragColor = premultiply(vec4(fill.rgb, fill.a * layer * uFillOpacity));
            }
        """,
        floats = setOf("uFillOpacity"),
        samplers = setOf("uSource", "uFill"),
    )

    /**
     * Puts a finished layer onto the canvas.
     *
     * The step that was missing while every other shader worked: the graph produces a layer's
     * appearance in its own texture, and without this the texture is simply discarded. It carries
     * the three things a layer has that its effects do not — where it sits, how opaque it is, and
     * how it blends — and it reads the canvas as [uBackdrop] rather than relying on fixed-function
     * blending, because the four non-separable modes need the destination as a value.
     *
     * [uMap] converts this pass's own coordinates into the layer texture's. Doing the placement in
     * the fragment stage keeps the single full-screen triangle: no vertex buffer, no second
     * geometry path.
     */
    val COMPOSITE = ShaderProgram(
        id = "composite",
        fragment = BlendShaders.compositeFragment,
        floatUniforms = setOf("uOpacity", "uMaskDensity"),
        intUniforms = setOf("uBlendMode", "uMaskFlags"),
        samplers = setOf("uSource", "uBackdrop", "uMask", "uVectorMask", "uClip"),
    )

    /**
     * Draws the finished canvas on screen under the camera.
     *
     * Separate from compositing so that panning and zooming re-run one textured quad rather than
     * every layer's effect stack — the difference between a canvas that tracks the finger and one
     * that does not.
     */
    val PRESENT = program(
        id = "present",
        body = """
            uniform mat3 uMap;
            uniform vec4 uSurround;

            void main() {
                vec3 mapped = uMap * vec3(vUv, 1.0);
                vec2 uv = mapped.xy / (abs(mapped.z) < 0.000001 ? 1.0 : mapped.z);
                // Outside the artboard is the neutral surround, not black and not the edge pixel
                // smeared outwards by clamping.
                if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
                    fragColor = uSurround;
                    return;
                }
                vec4 c = texture(uSource, uv);
                fragColor = vec4(mix(uSurround.rgb, c.rgb, c.a), 1.0);
            }
        """,
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

    /**
     * A straight copy, alpha included.
     *
     * Needed because [PRESENT] deliberately flattens onto the surround colour and so cannot be used
     * to duplicate a buffer: an isolated group and a clipping group both have to keep a buffer's
     * alpha exactly as it was, and a copy that returns opaque pixels turns a group's transparent
     * margin into a grey rectangle.
     */
    val COPY = program(
        id = "copy",
        body = """
            void main() {
                fragColor = texture(uSource, vUv);
            }
        """,
    )


    /**
     * The twenty-two colour corrections, as one program.
     *
     * An adjustment layer does not add pixels, it re-reads what is beneath it — so this samples the
     * composited backdrop and writes a corrected copy, which the compositor then blends back with
     * the layer's own opacity and mask. That is exactly Photoshop's model, and it is what makes a
     * half-opacity Curves layer mean "half the correction" rather than "half the picture".
     *
     * Every branch works on **unpremultiplied** colour. The backdrop arrives premultiplied, and
     * correcting premultiplied values darkens everything the further it is from opaque — visible as
     * a grey halo around every soft edge in the document.
     */
    val ADJUST = program(
        id = "adjust",
        body = """
            uniform int  uMode;
            uniform vec4 uP0;
            uniform vec4 uP1;
            uniform vec4 uP2;

            // r: composite curve, g/b/a: the three channel curves. One table rather than four
            // textures, because a Curves layer with all four in use is the normal case.
            uniform sampler2D uCurves;
            uniform sampler2D uRamp;
            uniform sampler2D uLut;

            // The base layer the two neighbourhood corrections read: a blurred copy of the same
            // backdrop, at each one's own radius. Shadows/Highlights uses both; HDR Toning's local
            // adaptation uses the first, holding an edge-following base in log luminance.
            uniform sampler2D uLocal;
            uniform sampler2D uLocal2;

            // Interpolated from the model rather than written twice, because the CPU path computes
            // the same corrections and the two have to agree to the last digit.
            const float SHADOW_HEADROOM = ${Tone.SHADOW_HEADROOM};
            const float HIGHLIGHT_DEPTH = ${Tone.HIGHLIGHT_DEPTH};
            const float LOG_KNEE = ${Tone.LOG_KNEE};
            const float LOG_FLOOR_VALUE = ${Tone.LOG_FLOOR_VALUE};
            const float LOG_SPAN = ${Tone.LOG_SPAN};

            float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

            /** Straight colour from a premultiplied sample; the base layers arrive premultiplied. */
            vec3 straight(vec4 c) { return c.a > 0.0 ? c.rgb / c.a : c.rgb; }

            /**
             * How strongly a tone belongs to the range being corrected.
             *
             * The parameter is a *width*, read the way the panel's slider reads: at half, a shadow
             * correction reaches the tones below half and stops. Smoothstep rather than a straight
             * ramp because a linear ramp is continuous but its slope is not, and that kink shows as
             * a faint edge across a clear sky.
             */
            float toneFalloff(float distanceIntoRange, float tone) {
                float width = max(tone, 0.05);
                float t = clamp((clamp(distanceIntoRange, 0.0, 1.0) - (1.0 - width)) / width, 0.0, 1.0);
                return t * t * (3.0 - 2.0 * t);
            }

            /** Vibrance protects what is already saturated; saturation does not, and rides on top. */
            vec3 vibrance(vec3 c, float vib, float sat) {
                float spread = max(c.r, max(c.g, c.b)) - min(c.r, min(c.g, c.b));
                float amount = max(1.0 + vib * (1.0 - spread) + sat, 0.0);
                return mix(vec3(luma(c)), c, amount);
            }

            vec3 rgbToHsl(vec3 c) {
                float mx = max(c.r, max(c.g, c.b));
                float mn = min(c.r, min(c.g, c.b));
                float l = (mx + mn) * 0.5;
                float d = mx - mn;
                if (d < 0.00001) return vec3(0.0, 0.0, l);
                float s = l > 0.5 ? d / (2.0 - mx - mn) : d / (mx + mn);
                float h;
                if (mx == c.r)      h = (c.g - c.b) / d + (c.g < c.b ? 6.0 : 0.0);
                else if (mx == c.g) h = (c.b - c.r) / d + 2.0;
                else                h = (c.r - c.g) / d + 4.0;
                return vec3(h / 6.0, s, l);
            }

            float hueChannel(float p, float q, float t) {
                if (t < 0.0) t += 1.0;
                if (t > 1.0) t -= 1.0;
                if (t < 1.0 / 6.0) return p + (q - p) * 6.0 * t;
                if (t < 1.0 / 2.0) return q;
                if (t < 2.0 / 3.0) return p + (q - p) * (2.0 / 3.0 - t) * 6.0;
                return p;
            }

            vec3 hslToRgb(vec3 hsl) {
                if (hsl.y < 0.00001) return vec3(hsl.z);
                float q = hsl.z < 0.5 ? hsl.z * (1.0 + hsl.y) : hsl.z + hsl.y - hsl.z * hsl.y;
                float p = 2.0 * hsl.z - q;
                return vec3(
                    hueChannel(p, q, hsl.x + 1.0 / 3.0),
                    hueChannel(p, q, hsl.x),
                    hueChannel(p, q, hsl.x - 1.0 / 3.0)
                );
            }

            float levelsChannel(float v, float inBlack, float inWhite, float gamma, float outBlack, float outWhite) {
                float range = max(inWhite - inBlack, 0.0001);
                float n = clamp((v - inBlack) / range, 0.0, 1.0);
                n = pow(n, 1.0 / max(gamma, 0.0001));
                return outBlack + n * (outWhite - outBlack);
            }

            /** Photoshop's colour balance shifts each range along three opposed axes. */
            vec3 balance(vec3 c, vec3 shadows, vec3 midtones, vec3 highlights) {
                float l = luma(c);
                // Overlapping weights, so a change to the midtones does not stop dead at a boundary
                // and leave a visible band across a gradient.
                float sw = clamp(1.0 - l * 2.0, 0.0, 1.0);
                float hw = clamp(l * 2.0 - 1.0, 0.0, 1.0);
                float mw = 1.0 - sw - hw;
                return c + shadows * sw + midtones * mw + highlights * hw;
            }

            /** Six-way monochrome mixing, as the Black and White panel does it. */
            float blackWhite(vec3 c, vec4 w0, vec2 w1) {
                float mx = max(c.r, max(c.g, c.b));
                float mn = min(c.r, min(c.g, c.b));
                float weight =
                      w0.x * max(0.0, min(c.r - c.g, c.r - c.b))
                    + w0.y * max(0.0, min(c.r, c.g) - c.b)
                    + w0.z * max(0.0, min(c.g - c.r, c.g - c.b))
                    + w0.w * max(0.0, min(c.g, c.b) - c.r)
                    + w1.x * max(0.0, min(c.b - c.r, c.b - c.g))
                    + w1.y * max(0.0, min(c.r, c.b) - c.g);
                return clamp(mn + weight, 0.0, 1.0);
            }

            /**
             * One family's ink shift, unbiased back to -1..1.
             *
             * The table is the same texture the curves use, read a texel at a time: nine families
             * of four inks would otherwise be nine vec4 uniforms for something one fetch answers.
             */
            vec4 selectiveInk(int family) {
                return texture(uCurves, vec2((float(family) + 0.5) / 256.0, 0.5)) * 2.0 - 1.0;
            }

            /** A 512x512 strip holding a 64-cube, which is what every LUT file on disk is. */
            vec3 lookup(vec3 c) {
                float slices = 64.0;
                float blue = clamp(c.b, 0.0, 1.0) * (slices - 1.0);
                float lower = floor(blue);
                float upper = min(lower + 1.0, slices - 1.0);
                float mixAmount = blue - lower;

                vec2 texel = vec2(1.0 / 512.0);
                vec2 uvLower = vec2(
                    (mod(lower, 8.0) * 64.0 + clamp(c.r, 0.0, 1.0) * 63.0 + 0.5) * texel.x,
                    (floor(lower / 8.0) * 64.0 + clamp(c.g, 0.0, 1.0) * 63.0 + 0.5) * texel.y
                );
                vec2 uvUpper = vec2(
                    (mod(upper, 8.0) * 64.0 + clamp(c.r, 0.0, 1.0) * 63.0 + 0.5) * texel.x,
                    (floor(upper / 8.0) * 64.0 + clamp(c.g, 0.0, 1.0) * 63.0 + 0.5) * texel.y
                );
                return mix(texture(uLut, uvLower).rgb, texture(uLut, uvUpper).rgb, mixAmount);
            }

            void main() {
                vec4 src = texture(uSource, vUv);
                // Nothing beneath means nothing to correct. Running the branch anyway would tint the
                // empty margin of the artboard, which then shows up in an export.
                if (src.a <= 0.0) { fragColor = src; return; }

                vec3 c = src.rgb / src.a;

                if (uMode == 0) {
                    c = c + uP0.x;
                    c = (c - 0.5) * (1.0 + uP0.y) + 0.5;
                } else if (uMode == 1) {
                    c = vec3(
                        levelsChannel(c.r, uP0.x, uP0.y, uP0.z, uP0.w, uP1.x),
                        levelsChannel(c.g, uP0.x, uP0.y, uP0.z, uP0.w, uP1.x),
                        levelsChannel(c.b, uP0.x, uP0.y, uP0.z, uP0.w, uP1.x)
                    );
                    if (uP1.y > 0.5) {
                        c = vec3(
                            texture(uCurves, vec2(c.r, 0.5)).g,
                            texture(uCurves, vec2(c.g, 0.5)).b,
                            texture(uCurves, vec2(c.b, 0.5)).a
                        );
                    }
                } else if (uMode == 2) {
                    // Per channel first, then the composite, which is the order the panel implies
                    // and the order that makes a composite S-curve behave the same however the
                    // channels were set.
                    c = vec3(
                        texture(uCurves, vec2(clamp(c.r, 0.0, 1.0), 0.5)).g,
                        texture(uCurves, vec2(clamp(c.g, 0.0, 1.0), 0.5)).b,
                        texture(uCurves, vec2(clamp(c.b, 0.0, 1.0), 0.5)).a
                    );
                    c = vec3(
                        texture(uCurves, vec2(clamp(c.r, 0.0, 1.0), 0.5)).r,
                        texture(uCurves, vec2(clamp(c.g, 0.0, 1.0), 0.5)).r,
                        texture(uCurves, vec2(clamp(c.b, 0.0, 1.0), 0.5)).r
                    );
                } else if (uMode == 3) {
                    vec3 hsl = rgbToHsl(clamp(c, 0.0, 1.0));
                    if (uP0.w > 0.5) {
                        // Colorize replaces the hue outright rather than rotating it, which is the
                        // whole point of the checkbox.
                        hsl.x = fract(uP0.x);
                        hsl.y = clamp(uP0.y, 0.0, 1.0);
                    } else {
                        hsl.x = fract(hsl.x + uP0.x);
                        hsl.y = clamp(hsl.y * (1.0 + uP0.y), 0.0, 1.0);
                    }
                    hsl.z = clamp(hsl.z + uP0.z * (uP0.z > 0.0 ? (1.0 - hsl.z) : hsl.z), 0.0, 1.0);
                    c = hslToRgb(hsl);
                } else if (uMode == 4) {
                    c = pow(max(c * pow(2.0, uP0.x) + uP0.y, vec3(0.0)), vec3(1.0 / max(uP0.z, 0.0001)));
                } else if (uMode == 5) {
                    c = vibrance(c, uP0.x, uP0.y);
                } else if (uMode == 6) {
                    vec3 balanced = balance(c, uP0.rgb, uP1.rgb, uP2.rgb);
                    if (uP0.w > 0.5) {
                        float before = luma(c);
                        float after = max(luma(balanced), 0.0001);
                        balanced *= before / after;
                    }
                    c = balanced;
                } else if (uMode == 7) {
                    c = vec3(blackWhite(clamp(c, 0.0, 1.0), uP0, uP1.xy));
                } else if (uMode == 8) {
                    float l = clamp(luma(c), 0.0, 1.0);
                    if (uP0.x > 0.5) {
                        // A ramp across a large area bands visibly at eight bits; a pixel-stable
                        // dither breaks it up without adding noise that moves between frames.
                        l = clamp(l + (hash(gl_FragCoord.xy) - 0.5) * (1.0 / 255.0), 0.0, 1.0);
                    }
                    c = texture(uRamp, vec2(l, 0.5)).rgb;
                } else if (uMode == 9) {
                    vec3 filtered = mix(c, c * uP0.rgb, clamp(uP0.w, 0.0, 1.0));
                    if (uP1.x > 0.5) {
                        float before = luma(c);
                        filtered *= before / max(luma(filtered), 0.0001);
                    }
                    c = filtered;
                } else if (uMode == 10) {
                    c = 1.0 - c;
                } else if (uMode == 11) {
                    float levels = max(uP0.x, 2.0);
                    c = floor(clamp(c, 0.0, 1.0) * levels) / (levels - 1.0);
                } else if (uMode == 12) {
                    c = vec3(luma(c) >= uP0.x ? 1.0 : 0.0);
                } else if (uMode == 13) {
                    c = mix(c, lookup(c), clamp(uP0.x, 0.0, 1.0));
                } else if (uMode == 14) {
                    vec3 rgb = clamp(c, 0.0, 1.0);
                    float mx = max(rgb.r, max(rgb.g, rgb.b));
                    float mn = min(rgb.r, min(rgb.g, rgb.b));

                    // The six chromatic weights sum to exactly the pixel's saturation, so a fully
                    // desaturated pixel is untouched by every colour slider and belongs entirely to
                    // the three achromatic ranges. That partition is what lets the greens of a
                    // landscape move without dragging a grey road along with them.
                    float w[9];
                    w[0] = max(0.0, min(rgb.r - rgb.g, rgb.r - rgb.b));
                    w[1] = max(0.0, min(rgb.r, rgb.g) - rgb.b);
                    w[2] = max(0.0, min(rgb.g - rgb.r, rgb.g - rgb.b));
                    w[3] = max(0.0, min(rgb.g, rgb.b) - rgb.r);
                    w[4] = max(0.0, min(rgb.b - rgb.r, rgb.b - rgb.g));
                    w[5] = max(0.0, min(rgb.r, rgb.b) - rgb.g);
                    float whites = clamp((mn - 0.5) * 2.0, 0.0, 1.0);
                    float blacks = clamp((0.5 - mx) * 2.0, 0.0, 1.0);
                    w[6] = whites;
                    w[7] = clamp(1.0 - whites - blacks, 0.0, 1.0);
                    w[8] = blacks;

                    vec4 ink = vec4(0.0);
                    for (int i = 0; i < 9; i++) ink += w[i] * selectiveInk(i);

                    // Into CMYK with full black generation and back out again. Without the K
                    // separation the black slider has nothing to act on, and it is the slider that
                    // makes a shadow deeper rather than merely darker.
                    float k = 1.0 - mx;
                    vec3 cmy = (vec3(mx) - rgb) / max(mx, 0.0001);
                    if (uP0.x > 0.5) {
                        cmy += ink.rgb;
                        k += ink.a;
                    } else {
                        // Relative: a ten per cent push on a pixel holding half its cyan adds five,
                        // so the correction cannot invent ink where there was none — which is the
                        // whole reason this mode is the safe one on skin.
                        cmy += cmy * ink.rgb;
                        k += k * ink.a;
                    }
                    c = (1.0 - clamp(cmy, 0.0, 1.0)) * (1.0 - clamp(k, 0.0, 1.0));
                } else if (uMode == 15) {
                    // Monochrome arrives as the same recipe in all three rows, so there is no flag
                    // and no second path.
                    c = vec3(
                        dot(c, uP0.rgb) + uP0.w,
                        dot(c, uP1.rgb) + uP1.w,
                        dot(c, uP2.rgb) + uP2.w
                    );
                } else if (uMode == 16) {
                    // Shadows/Highlights. The gain is driven by the *blurred* luminance, not the
                    // pixel's own, and that single substitution is the whole difference between
                    // this and a curve: it asks "is this region dark?" rather than "is this pixel
                    // dark?", and only the first question has a useful answer. Two base layers
                    // because the panel has two radii, and they want different values.
                    float darkness = 1.0 - clamp(luma(straight(texture(uLocal, vUv))), 0.0, 1.0);
                    float brightness = clamp(luma(straight(texture(uLocal2, vUv))), 0.0, 1.0);

                    float gain = 1.0;
                    if (uP0.x > 0.0) gain *= 1.0 + uP0.x * SHADOW_HEADROOM * toneFalloff(darkness, uP0.y);
                    if (uP0.z > 0.0) gain *= 1.0 - uP0.z * HIGHLIGHT_DEPTH * toneFalloff(brightness, uP0.w);

                    vec3 lifted = c * gain;
                    if (uP1.y != 0.0) lifted = clamp((lifted - 0.5) * (1.0 + uP1.y) + 0.5, 0.0, 1.0);
                    if (uP1.x != 0.0 && gain != 1.0) {
                        // Lifting a shadow desaturates it, because the colours were compressed down
                        // there. Put back only what the lift took out, in proportion to how far the
                        // pixel actually moved, so an untouched midtone keeps what it was given.
                        float l = luma(lifted);
                        float strength = 1.0 + uP1.x * min(abs(gain - 1.0), 1.0);
                        lifted = vec3(l) + (lifted - vec3(l)) * strength;
                    }
                    // The clip last, between the two points the editor measured. At Photoshop's own
                    // default of a hundredth of a per cent this is the identity.
                    c = (lifted - uP1.z) / max(uP1.w - uP1.z, 0.00001);
                } else if (uMode == 17) {
                    int method = int(uP0.x + 0.5);
                    vec3 t = max(c * exp2(uP0.w), vec3(0.0));

                    if (method == 1) {
                        // Highlight compression: fit the top of the range onto the screen and leave
                        // the bottom alone, which is why it never needs a radius.
                        float l = max(luma(t), 0.00001);
                        t *= (l / (1.0 + l)) / l;
                    } else if (method == 2) {
                        float l = max(luma(t), 0.00001);
                        t *= (log(1.0 + l * LOG_KNEE) / log(1.0 + LOG_KNEE)) / l;
                    } else if (method == 3) {
                        // Local adaptation. The log luminance splits into a slowly-varying base and
                        // the detail riding on it; only the base is compressed, and the detail goes
                        // back untouched. That is why it can flatten a twelve-stop scene without
                        // flattening a face.
                        float l = max(luma(t), LOG_FLOOR_VALUE);
                        float logL = log(l);
                        // uLocal holds the guided filter's per-window coefficients, so the base is
                        // one multiply-add away rather than another full pass.
                        float x = clamp(1.0 + logL / LOG_SPAN, 0.0, 1.0);
                        vec4 coefficients = texture(uLocal, vUv);
                        float logBase = (coefficients.r * x + coefficients.g) * LOG_SPAN - LOG_SPAN;
                        float detail = logL - logBase;
                        t *= exp(logBase / max(uP0.y, 0.00001) + detail * (1.0 + uP1.x)) / l;
                    }

                    // Shadow and Highlight ride on whichever method ran, exactly as the panel has
                    // them: two more sliders below the dropdown, acting on the already-mapped tone.
                    if (uP1.y != 0.0 || uP1.z != 0.0) {
                        float l = clamp(luma(t), 0.0, 1.0);
                        t *= 1.0 + uP1.y * (1.0 - l) * (1.0 - l) + uP1.z * l * l;
                    }
                    if (uP0.z != 1.0) t = pow(max(t, vec3(0.0)), vec3(1.0 / max(uP0.z, 0.00001)));
                    t = vibrance(t, uP1.w, uP2.x);
                    if (uP2.y > 0.5) {
                        t = clamp(t, 0.0, 1.0);
                        t = vec3(
                            texture(uCurves, vec2(t.r, 0.5)).r,
                            texture(uCurves, vec2(t.g, 0.5)).r,
                            texture(uCurves, vec2(t.b, 0.5)).r
                        );
                    }
                    c = t;
                } else if (uMode == 18) {
                    // Photoshop's Desaturate is Hue/Saturation at -100, so its grey is HSL
                    // lightness — the midpoint of the brightest and darkest channel — and *not* a
                    // weighted luma. Reaching for luma() here is the obvious mistake, and it is
                    // invisible until the same file is opened in both applications.
                    c = vec3((max(c.r, max(c.g, c.b)) + min(c.r, min(c.g, c.b))) * 0.5);
                } else if (uMode == 19) {
                    // Match Color. Reinhard's transfer: move the mean, then rescale the spread.
                    // uP2.z says whether a source was ever measured; without one this is a no-op
                    // rather than a picture collapsed onto a flat grey.
                    if (uP2.z > 0.5) {
                        float l = luma(c);
                        float target = luma(uP0.rgb);
                        float moved = l + (target - l) * uP0.w;

                        vec3 deviation = max(uP1.rgb, vec3(0.00001));
                        float average = max((deviation.r + deviation.g + deviation.b) / 3.0, 0.00001);
                        // Neutralize pulls every channel onto one spread, which is what removes a
                        // cast: a cast *is* one channel sitting apart from the others.
                        vec3 scale = uP2.y > 0.5 ? vec3(average) : deviation;
                        vec3 matched = vec3(moved) + (c - vec3(l)) * (vec3(1.0) + (scale / average - 1.0) * uP1.w);
                        c = mix(matched, c, clamp(uP2.x, 0.0, 1.0));
                    }
                } else if (uMode == 20) {
                    // Replace Color. The selection is a distance from a picked colour rather than a
                    // slice of the hue wheel, which is what lets it take one blue out of a sky and
                    // leave the rest. Feathered, because a hard mask outlines everything it touches.
                    float distance = length(c - uP0.rgb) / 1.7320508;
                    float mask = clamp(1.0 - distance / max(uP0.w, 0.00001), 0.0, 1.0);
                    if (uP1.x > 0.5) mask *= mask;
                    if (mask > 0.0) {
                        vec3 hsl = rgbToHsl(clamp(c, 0.0, 1.0));
                        hsl.x = fract(hsl.x + uP1.y);
                        hsl.y = clamp(hsl.y * (1.0 + uP1.z), 0.0, 1.0);
                        hsl.z = clamp(hsl.z + uP1.w * (uP1.w > 0.0 ? (1.0 - hsl.z) : hsl.z), 0.0, 1.0);
                        c = mix(c, hslToRgb(hsl), mask);
                    }
                } else if (uMode == 21) {
                    // Equalize. The mapping *is* this picture's own cumulative histogram, measured
                    // once and frozen into the curve table — a per-pixel shader cannot count one.
                    if (uP0.x > 0.5) {
                        float l = clamp(luma(c), 0.0, 1.0);
                        float mapped = texture(uCurves, vec2(l, 0.5)).r;
                        // Scaled rather than replaced, so the hue survives the redistribution. A
                        // per-channel equalisation is the textbook version and it puts a cast on
                        // every neutral in the frame.
                        c *= mapped / max(l, 0.00001);
                    }
                }

                fragColor = vec4(clamp(c, 0.0, 1.0) * src.a, src.a);
            }
        """,
        floats = emptySet(),
        ints = setOf("uMode"),
        samplers = setOf("uSource", "uCurves", "uRamp", "uLut", "uLocal", "uLocal2"),
    )

    /**
     * The first half of a guided filter: the guide and its square, ready to be blurred.
     *
     * HDR Toning's local adaptation needs a base layer that follows edges. A Gaussian one does not,
     * and compressing a Gaussian base leaves a bright halo along every edge in the picture — which
     * is the single thing that makes tone-mapped work look tone-mapped.
     *
     * The guided filter gets there in box blurs rather than in a bilateral's quadratic tap count,
     * and it factors into exactly two extra programs: this one writes the guide and its square, the
     * blur averages them, and [HDR_BASE_COEFF] turns those averages into a line per window. The
     * final multiply-add is folded into the adjustment branch itself, so there is no third pass.
     *
     * The guide is **normalised** log luminance. The regularisation is compared against the local
     * variance, so what counts as an edge is set by the guide's units; raw natural-log luminance
     * spans five and a half, which puts every ordinary gradient far above any sensible epsilon and
     * leaves the filter passing the picture through unchanged.
     */
    val HDR_BASE_SEED = program(
        id = "hdr_base_seed",
        body = """
            void main() {
                vec4 src = texture(uSource, vUv);
                vec3 c = src.a > 0.0 ? src.rgb / src.a : src.rgb;
                float l = max(dot(c, vec3(0.2126, 0.7152, 0.0722)), ${Tone.LOG_FLOOR_VALUE});
                float x = clamp(1.0 + log(l) / ${Tone.LOG_SPAN}, 0.0, 1.0);
                fragColor = vec4(x, x * x, 0.0, 1.0);
            }
        """,
    )

    /**
     * The second half: each window's line, from the means the blur produced.
     *
     * `a` is how much of the guide the base keeps and `b` is what is left of the mean once it has.
     * Blurring the pair and evaluating `a * guide + b` is what makes the result follow edges instead
     * of crossing them.
     */
    val HDR_BASE_COEFF = program(
        id = "hdr_base_coeff",
        body = """
            void main() {
                vec2 means = texture(uSource, vUv).rg;
                float variance = max(means.y - means.x * means.x, 0.0);
                float a = variance / (variance + ${Tone.GUIDE_EPSILON});
                fragColor = vec4(a, means.x - a * means.x, 0.0, 1.0);
            }
        """,
    )

    /** Every program, keyed by the id an effect module puts in its descriptor. */
    val ALL: Map<String, ShaderProgram> = listOf(
        SDF_SEED, SDF_FLOOD, SDF_RESOLVE, STROKE, SHADOW, GLOW, INNER_SHADOW, BEVEL, SATIN, OVERLAY,
        EXTRUDE_STEP, REFLECTION, CHROMATIC_OFFSET, BACKDROP_BLUR, NOISE, EDGE_ROUGHEN, BLUR, FILL,
        COMPOSITE, PRESENT, COPY, ADJUST, HDR_BASE_SEED, HDR_BASE_COEFF,
    ).associateBy { it.id }

    operator fun get(id: String): ShaderProgram? = ALL[id]
}
