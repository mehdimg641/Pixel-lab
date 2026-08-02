package ir.pixellab.core.render

import ir.pixellab.core.model.Adjustment

/**
 * Which correction the adjustment shader is running.
 *
 * One program with a branch rather than fourteen programs: a document routinely stacks half a dozen
 * adjustment layers, and fourteen programs would mean fourteen compiles at startup and a pipeline
 * change between every layer. The branch is uniform across the draw, so the hardware takes one path.
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
    ;

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
    /** True when the shader needs the four-channel curve table bound. */
    val needsCurves: Boolean = false,
    /** True when it needs a gradient ramp. */
    val needsRamp: Boolean = false,
    /** True when it needs a colour lookup strip. */
    val needsLut: Boolean = false,
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
            }
        }

        private fun pack(mode: AdjustmentMode, p0: FloatArray, p1: FloatArray = FloatArray(4)) =
            AdjustmentUniforms(mode, p0, p1, FloatArray(4))
    }
}
