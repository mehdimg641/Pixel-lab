package ir.pixellab.core.model

import kotlin.math.ln

/**
 * The constants that decide what an adjustment's sliders *mean*.
 *
 * They live in the model rather than beside either implementation because there are two
 * implementations — a GLSL program on the device and a Kotlin one on the CPU — and the two have to
 * agree to the last digit. A shadow lift worth two and a half stops in one and two in the other is
 * not a rounding difference; it is a preview that does not match the export, and the kind of
 * disagreement that survives for months because both halves look reasonable on their own.
 *
 * The shader interpolates these values into its source, so there is one place to change them and no
 * second copy to forget.
 */
object Tone {

    /** A shadow can be opened by about two and a half stops before the noise beneath it arrives. */
    const val SHADOW_HEADROOM = 2.5f

    /** And a highlight pulled to a fifth of its value is already at the edge of looking grey. */
    const val HIGHLIGHT_DEPTH = 0.8f

    /** How hard HDR Toning's global log curve bends; chosen so a mid grey stays near mid grey. */
    const val LOG_KNEE = 5f

    /**
     * Where the log domain is cut off: one part in 255, the darkest a byte can hold.
     *
     * Below it the logarithm runs away, and a single black pixel would set the scale for the whole
     * picture's normalisation.
     */
    const val LOG_FLOOR_VALUE = 1f / 255f

    /** How wide that domain is, which is what the normalisation to 0..1 divides by. */
    val LOG_SPAN = -ln(LOG_FLOOR_VALUE)

    /**
     * The guided filter's regularisation, against a guide normalised to 0..1.
     *
     * The local variance below which a difference counts as texture rather than as an edge. Too
     * small and the base layer follows every grain, so compressing it compresses the detail too and
     * the local operator degenerates into a global gamma; too large and the base stops following
     * edges and the halo comes back.
     */
    const val GUIDE_EPSILON = 0.05f
}
