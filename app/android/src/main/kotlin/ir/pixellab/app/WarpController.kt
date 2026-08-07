package ir.pixellab.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ir.pixellab.core.model.Vec2

/** What a liquify drag does. */
enum class WarpKind { PUSH, BLOAT, PUCKER, TWIRL }

/**
 * The strokes of a liquify session, before they are solved.
 *
 * Gathered rather than applied per drag, for two reasons. Solving a moving-least-squares warp over
 * a portrait takes long enough that doing it on every touch event would fall behind the finger; and
 * applying warps one after another resamples the image each time, so four small nudges come out
 * blurrier than one large one. Collecting the control points and solving once avoids both.
 */
class WarpController {

    var kind: WarpKind by mutableStateOf(WarpKind.PUSH)
    var brushSize: Float by mutableStateOf(80f)

    /** Where each stroke started and ended, in canvas units. */
    var strokes: List<Pair<Vec2, Vec2>> by mutableStateOf(emptyList())
        private set

    private var anchor: Vec2? = null

    val isEmpty: Boolean get() = strokes.isEmpty()

    fun begin(at: Vec2) {
        anchor = at
    }

    fun end(at: Vec2) {
        val from = anchor ?: return
        anchor = null
        // A stroke that went nowhere is a tap, and a tap should not warp anything — the user was
        // almost certainly aiming at a control and missed.
        if (kotlin.math.hypot(at.x - from.x, at.y - from.y) < MINIMUM_TRAVEL && kind == WarpKind.PUSH) return
        strokes = strokes + (from to at)
    }

    fun reset() {
        strokes = emptyList()
        anchor = null
    }

    private companion object {
        /** Below this a drag is a mis-aimed tap rather than an intention to move pixels. */
        const val MINIMUM_TRAVEL = 3f
    }
}
