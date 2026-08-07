package ir.pixellab.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * The live document, drawn inside a studio.
 *
 * ### Why the studios draw the real canvas rather than a picture of it
 *
 * Every one of the three is a room you go into to change how something *looks*, and the only
 * honest way to show that is the thing itself, updating. The alternative — a rendered thumbnail
 * refreshed on each change — is a frame behind at best, and at worst it is a still image of a
 * document the user has since edited, which is the exact failure that makes a preview worse than
 * no preview.
 *
 * ### The handle is per-screen, deliberately
 *
 * `CanvasHandle` binds one GL surface. The editor has one; each studio has its own, created when
 * the screen is composed and cleared when it leaves — which `EditorCanvas` already does in its own
 * `DisposableEffect`. Sharing one handle across screens would mean whichever screen composed last
 * owns the surface and the other holds a dangling reference, and the symptom of that is an export
 * from the wrong screen quietly rendering nothing.
 *
 * ### The chrome insets are reset on the way in *and on the way out*
 *
 * The editor's canvas measures itself and needs no correction, and so does this one — but the
 * document's *framing* is per-viewport, and a studio's canvas is a different size from the
 * editor's. `onScreenSize` refits when the user has not framed by hand, which is what puts the
 * artwork in the middle of the smaller box. Somebody who pinched to a corner keeps their framing,
 * here as everywhere, because throwing away a chosen zoom is never the friendlier answer.
 */
@Composable
fun StudioCanvas(
    model: EditorViewModel,
    modifier: Modifier = Modifier,
    overlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
) {
    val handle = remember { CanvasHandle() }
    LaunchedEffect(Unit) { model.onChromeInsets(top = 0f, bottom = 0f) }
    // On the way out the editor takes its own measurements again, and it does so from its own
    // `LaunchedEffect(Unit)`. Nothing is needed here — stated because "reset it back" is the
    // instinct, and doing it would fight the screen that is arriving.
    DisposableEffect(Unit) { onDispose { } }

    Box(modifier.background(Ink.Surround)) {
        EditorCanvas(
            state = model.state,
            bounds = model.bounds,
            fonts = model.fonts,
            assets = model.assets,
            assetGeneration = model.paint.generation,
            selection = SelectionOverlay(
                model.select.outline,
                model.select.draft,
                mask = if (model.quickMask) model.select.selection else null,
            ),
            pen = PenOverlay(model.pen.path, model.pen.active),
            handle = handle,
            onGesture = model::onGesture,
            onSize = model::onScreenSize,
            modifier = Modifier.fillMaxSize(),
        )
        overlay()
    }
}
