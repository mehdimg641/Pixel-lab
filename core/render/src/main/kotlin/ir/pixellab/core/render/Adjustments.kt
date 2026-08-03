package ir.pixellab.core.render

import ir.pixellab.core.model.Adjustment
import ir.pixellab.core.model.Curve
import ir.pixellab.core.model.HdrMethod

/**
 * Which correction the adjustment shader is running.
 *
 * One program with a branch rather than twenty-two programs: a document routinely stacks half a
 * dozen adjustment layers, and twenty-two programs would mean twenty-two compiles at startup and a
 * pipeline change between every layer. The branch is uniform across the draw, so the hardware takes
 * one path.
 *
 * **The ordinal is the wire format.** It is what goes into `uMode`, so new modes are appended and
 * never inserted — reordering this list silently turns every saved document's Curves layer into
 * something else.
 */
enum class AdjustmentMode {
    BRIGHTNESS_CONTRAST,
    LEVELS,
    CURVES,
    HUE_SATURATION,
    EXPOSURE,
    VIBRANCE,
    COLOR_BALANCE,
    BLACK_WHITE,
    GRADIENT_MAP,
    PHOTO_FILTER,
    INVERT,
    POSTERIZE,
    THRESHOLD,
    COLOR_LOOKUP,
    SELECTIVE_COLOR,
    CHANNEL_MIXER,
    SHADOWS_HIGHLIGHTS,
    HDR_TONING,
    DESATURATE,
    MATCH_COLOR,
    REPLACE_COLOR,
    EQUALIZE,
    ;

    /**
     * Whether this mode reads its neighbourhood rather than only the pixel under it.
     *
     * The two that do are the two Photoshop refuses to offer as adjustment layers at all, and this
     * flag is what lets ours be layers anyway: the renderer sees it, builds the base layer the mode
     * needs, and binds it. Nothing else in the pipeline has to know.
     */
    val needsLocal: Boolean get() = this == SHADOWS_HIGHLIGHTS || this == HDR_TONING

    companion object {
        fun of(adjustment: Adjustment): AdjustmentMode = when (adjustment) {
            is Adjustment.BrightnessContrast -> BRIGHTNESS_CONTRAST
            is Adjustment.Levels -> LEVELS
            is Adjustment.Curves -> CURVES
            is Adjustment.HueSaturation -> HUE_SATURATION
            is Adjustment.Exposure -> EXPOSURE
            is Adjustment.Vibrance -> VIBRANCE
            is Adjustment.ColorBalance -> COLOR_BALANCE
            is Adjustment.BlackWhite -> BLACK_WHITE
            is Adjustment.GradientMap -> GRADIENT_MAP
            is Adjustment.PhotoFilter -> PHOTO_FILTER
            Adjustment.Invert -> INVERT
            is Adjustment.Posterize -> POSTERIZE
            is Adjustment.Threshold -> THRESHOLD
            is Adjustment.ColorLookup -> COLOR_LOOKUP
            is Adjustment.SelectiveColor -> SELECTIVE_COLOR
            is Adjustment.ChannelMixer -> CHANNEL_MIXER
            is Adjustment.ShadowsHighlights -> SHADOWS_HIGHLIGHTS
            is Adjustment.HdrToning -> HDR_TONING
            Adjustment.Desaturate -> DESATURATE
            is Adjustment.MatchColor -> MATCH_COLOR
            is Adjustment.ReplaceColor -> REPLACE_COLOR
            is Adjustment.Equalize -> EQUALIZE
        }
    }
}

/**
 * An adjustment's parameters, packed for the shader.
 *
 * Packed in Kotlin rather than in the renderer so the packing is testable without a GPU — and it is
 * exactly the kind of code that goes wrong silently, because a parameter written into the wrong slot
 * produces a plausible-looking picture rather than an error.
 */
data class AdjustmentUniforms(
    val mode: AdjustmentMode,
    val p0: FloatArray,
    val p1: FloatArray,
    val p2: FloatArray,
    /**
     * True when the shader needs the 256-sample table bound.
     *
     * Usually the four curves, but Selective Color rides in the same texture: nine families of four
     * inks is thirty-six numbers, and thirty-six numbers is nine vec4 uniforms for something one
     * texture read answers.
     */
    val needsCurves: Boolean = false,
    /** True when it needs a gradient ramp. */
    val needsRamp: Boolean = false,
    /** True when it needs a colour lookup strip. */
    val needsLut: Boolean = false,
    /**
     * How wide a base layer this correction needs, in pixels; zero when it needs none.
     *
     * Two radii because Shadows/Highlights has two, and it has two because they want different
     * values: a wide one on the shadows keeps a lifted face from haloing, and a narrow one on the
     * highlights is what actually recovers a blown window frame.
     */
    val localRadius: Float = 0f,
    val localRadius2: Float = 0f,
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)

    companion object {

        fun of(adjustment: Adjustment): AdjustmentUniforms {
            val mode = AdjustmentMode.of(adjustment)
            return when (adjustment) {
                is Adjustment.BrightnessContrast -> pack(
                    mode,
                    // Photoshop's sliders run -150..150 and -50..100; the model stores them
                    // normalised, and the shader wants them that way too.
                    floatArrayOf(adjustment.brightness, adjustment.contrast, 0f, 0f),
                )

                is Adjustment.Levels -> {
                    val red = adjustment.perChannel.getOrNull(0)
                    val green = adjustment.perChannel.getOrNull(1)
                    val blue = adjustment.perChannel.getOrNull(2)
                    AdjustmentUniforms(
                        mode = mode,
                        p0 = floatArrayOf(
                            adjustment.inputBlack, adjustment.inputWhite,
                            adjustment.gamma, adjustment.outputBlack,
                        ),
                        p1 = floatArrayOf(
                            adjustment.outputWhite,
                            // Per-channel levels are folded into the curve table instead of into
                            // more uniforms: three more sets of five would be fifteen floats, and a
                            // table is one texture read.
                            if (adjustment.perChannel.isEmpty()) 0f else 1f,
                            red?.gamma ?: 1f,
                            green?.gamma ?: 1f,
                        ),
                        p2 = floatArrayOf(blue?.gamma ?: 1f, 0f, 0f, 0f),
                        needsCurves = adjustment.perChannel.isNotEmpty(),
                    )
                }

                is Adjustment.Curves -> AdjustmentUniforms(
                    mode = mode,
                    p0 = FloatArray(4),
                    p1 = FloatArray(4),
                    p2 = FloatArray(4),
                    needsCurves = true,
                )

                is Adjustment.HueSaturation -> pack(
                    mode,
                    floatArrayOf(
                        adjustment.hue,
                        adjustment.saturation,
                        adjustment.lightness,
                        if (adjustment.colorize) 1f else 0f,
                    ),
                )

                is Adjustment.Exposure -> pack(
                    mode,
                    floatArrayOf(adjustment.exposure, adjustment.offset, adjustment.gamma, 0f),
                )

                is Adjustment.Vibrance -> pack(
                    mode,
                    floatArrayOf(adjustment.vibrance, adjustment.saturation, 0f, 0f),
                )

                is Adjustment.ColorBalance -> AdjustmentUniforms(
                    mode = mode,
                    p0 = floatArrayOf(
                        adjustment.shadows.x, adjustment.shadows.y, adjustment.shadows.z,
                        if (adjustment.preserveLuminosity) 1f else 0f,
                    ),
                    p1 = floatArrayOf(adjustment.midtones.x, adjustment.midtones.y, adjustment.midtones.z, 0f),
                    p2 = floatArrayOf(
                        adjustment.highlights.x, adjustment.highlights.y, adjustment.highlights.z, 0f,
                    ),
                )

                is Adjustment.BlackWhite -> AdjustmentUniforms(
                    mode = mode,
                    // Photoshop's six sliders: red, yellow, green, cyan, blue, magenta. Six weights
                    // rather than three is what lets a red jumper and a green hedge of the same
                    // luminance be told apart in monochrome, which three cannot do at all.
                    p0 = floatArrayOf(
                        adjustment.weights.getOrElse(0) { 0.4f },
                        adjustment.weights.getOrElse(1) { 0.6f },
                        adjustment.weights.getOrElse(2) { 0.4f },
                        adjustment.weights.getOrElse(3) { 0.6f },
                    ),
                    p1 = floatArrayOf(
                        adjustment.weights.getOrElse(4) { 0.2f },
                        adjustment.weights.getOrElse(5) { 0.8f },
                        0f, 0f,
                    ),
                    p2 = FloatArray(4),
                )

                is Adjustment.GradientMap -> AdjustmentUniforms(
                    mode = mode,
                    p0 = floatArrayOf(if (adjustment.dither) 1f else 0f, 0f, 0f, 0f),
                    p1 = FloatArray(4),
                    p2 = FloatArray(4),
                    needsRamp = true,
                )

                is Adjustment.PhotoFilter -> pack(
                    mode,
                    floatArrayOf(
                        adjustment.color.r, adjustment.color.g, adjustment.color.b, adjustment.density,
                    ),
                    floatArrayOf(if (adjustment.preserveLuminosity) 1f else 0f, 0f, 0f, 0f),
                )

                Adjustment.Invert -> pack(mode, FloatArray(4))

                is Adjustment.Posterize -> pack(
                    mode,
                    floatArrayOf(adjustment.levels.coerceAtLeast(2).toFloat(), 0f, 0f, 0f),
                )

                is Adjustment.Threshold -> pack(mode, floatArrayOf(adjustment.level, 0f, 0f, 0f))

                is Adjustment.ColorLookup -> AdjustmentUniforms(
                    mode = mode,
                    p0 = floatArrayOf(adjustment.amount, 0f, 0f, 0f),
                    p1 = FloatArray(4),
                    p2 = FloatArray(4),
                    needsLut = true,
                )

                is Adjustment.SelectiveColor -> AdjustmentUniforms(
                    mode = mode,
                    p0 = floatArrayOf(if (adjustment.absolute) 1f else 0f, 0f, 0f, 0f),
                    p1 = FloatArray(4),
                    p2 = FloatArray(4),
                    needsCurves = true,
                )

                is Adjustment.ChannelMixer -> {
                    // Monochrome is not a shader flag: it *is* the same recipe in all three rows,
                    // and writing it that way means the branch has one path instead of two and the
                    // preview of a monochrome mix is literally the mix it will export.
                    val red = if (adjustment.monochrome) adjustment.gray else adjustment.red
                    val green = if (adjustment.monochrome) adjustment.gray else adjustment.green
                    val blue = if (adjustment.monochrome) adjustment.gray else adjustment.blue
                    AdjustmentUniforms(
                        mode = mode,
                        p0 = red.toFloats(),
                        p1 = green.toFloats(),
                        p2 = blue.toFloats(),
                    )
                }

                is Adjustment.ShadowsHighlights -> AdjustmentUniforms(
                    mode = mode,
                    p0 = floatArrayOf(
                        adjustment.shadowAmount, adjustment.shadowTone,
                        adjustment.highlightAmount, adjustment.highlightTone,
                    ),
                    p1 = floatArrayOf(
                        adjustment.color, adjustment.midtoneContrast,
                        adjustment.blackPoint, adjustment.whitePoint,
                    ),
                    p2 = FloatArray(4),
                    localRadius = adjustment.shadowRadius,
                    localRadius2 = adjustment.highlightRadius,
                )

                is Adjustment.HdrToning -> AdjustmentUniforms(
                    mode = mode,
                    p0 = floatArrayOf(
                        adjustment.method.ordinal.toFloat(), adjustment.strength,
                        adjustment.gamma, adjustment.exposure,
                    ),
                    p1 = floatArrayOf(
                        adjustment.detail, adjustment.shadow, adjustment.highlight, adjustment.vibrance,
                    ),
                    p2 = floatArrayOf(
                        adjustment.saturation,
                        if (adjustment.toningCurve == Curve.LINEAR) 0f else 1f,
                        0f, 0f,
                    ),
                    needsCurves = adjustment.toningCurve != Curve.LINEAR,
                    // Only Local Adaptation reads a base layer; the other three methods are global
                    // curves, and building a base for them would be several passes for nothing.
                    localRadius = if (adjustment.method == HdrMethod.LOCAL_ADAPTATION) adjustment.radius else 0f,
                )

                Adjustment.Desaturate -> pack(mode, FloatArray(4))

                is Adjustment.MatchColor -> AdjustmentUniforms(
                    mode = mode,
                    p0 = floatArrayOf(
                        adjustment.statistics.mean.x, adjustment.statistics.mean.y,
                        adjustment.statistics.mean.z, adjustment.luminance,
                    ),
                    p1 = floatArrayOf(
                        adjustment.statistics.deviation.x, adjustment.statistics.deviation.y,
                        adjustment.statistics.deviation.z, adjustment.colorIntensity,
                    ),
                    p2 = floatArrayOf(
                        adjustment.fade,
                        if (adjustment.neutralize) 1f else 0f,
                        if (adjustment.statistics.measured) 1f else 0f,
                        0f,
                    ),
                )

                is Adjustment.ReplaceColor -> AdjustmentUniforms(
                    mode = mode,
                    p0 = floatArrayOf(
                        adjustment.target.r, adjustment.target.g, adjustment.target.b,
                        adjustment.fuzziness,
                    ),
                    p1 = floatArrayOf(
                        if (adjustment.localized) 1f else 0f,
                        adjustment.hue, adjustment.saturation, adjustment.lightness,
                    ),
                    p2 = FloatArray(4),
                )

                // The frozen cumulative histogram rides in the curve table's red channel: it is a
                // 256-sample tone mapping, which is exactly what that texture already carries.
                is Adjustment.Equalize -> AdjustmentUniforms(
                    mode = mode,
                    p0 = floatArrayOf(if (adjustment.table.isEmpty()) 0f else 1f, 0f, 0f, 0f),
                    p1 = FloatArray(4),
                    p2 = FloatArray(4),
                    needsCurves = adjustment.table.isNotEmpty(),
                )
            }
        }

        private fun ir.pixellab.core.model.ChannelRecipe.toFloats() =
            floatArrayOf(red, green, blue, constant)

        /**
         * Selective Color's thirty-six numbers as the first nine texels of the table.
         *
         * Signed values in an unsigned texture, so they are stored biased by a half. Eight bits over
         * a range of two is a step of 0.008, and the panel's own sliders move in steps of 0.01 — the
         * table is finer than the control that feeds it, which is where the line has to be.
         */
        fun selectiveColorTable(adjustment: Adjustment.SelectiveColor, size: Int): IntArray {
            val byFamily = adjustment.ranges.associateBy { it.family }
            return IntArray(size) { texel ->
                val family = ir.pixellab.core.model.ColorFamily.entries.getOrNull(texel)
                    ?: return@IntArray NEUTRAL_TEXEL
                val range = byFamily[family] ?: return@IntArray NEUTRAL_TEXEL
                (biased(range.black) shl 24) or (biased(range.cyan) shl 16) or
                    (biased(range.magenta) shl 8) or biased(range.yellow)
            }
        }

        private fun biased(value: Float) =
            ((value.coerceIn(-1f, 1f) + 1f) * 0.5f * 255f + 0.5f).toInt().coerceIn(0, 255)

        /** Zero shift, biased: the value every texel past the nine families has to hold. */
        private val NEUTRAL_TEXEL = (128 shl 24) or (128 shl 16) or (128 shl 8) or 128

        private fun pack(mode: AdjustmentMode, p0: FloatArray, p1: FloatArray = FloatArray(4)) =
            AdjustmentUniforms(mode, p0, p1, FloatArray(4))
    }
}
