package ir.pixellab.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import ir.pixellab.core.editor.SheetContent
import ir.pixellab.core.editor.TextSection
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId

/**
 * The text studio, as a room rather than a drawer.
 *
 * ### Why this one had to move first
 *
 * It is the reason the application exists. Setting Persian 3D type is fourteen sections of work —
 * the words, the face, the colour, the metrics, the stroke, the shadow, the glow, the extrusion,
 * the material, the background, the curve, the reflection, the blend, the rest — and all of it is
 * judged against the same headline, on the same artwork, over and over.
 *
 * As a sheet it covered that artwork. The canvas panned out from under it, which helped, but the
 * space left was the top third of a phone and the panel was the other two. Here the canvas is a
 * fixed band that never moves and never gets covered: you change the bevel, you look up, you change
 * it again. That loop is the whole job, and it is the loop a sheet cannot support.
 *
 * ### `TextStudioBody` is not touched
 *
 * The fourteen sections, the target bar, the section strip and every control in them are the same
 * code. What changed is that the section is now this screen's own state rather than a field on a
 * `SheetContent` — because there is no sheet any more to carry it.
 *
 * The old sheet stays reachable and renders the same body. `MissingSubject` and a handful of other
 * places open it directly, and cutting those in the same change that adds this one would trade four
 * working routes for one new one.
 */
@Composable
fun TextStudioScreen(
    model: EditorViewModel,
    layer: LayerId,
    onDone: () -> Unit,
    initialSection: TextSection = TextSection.CONTENT,
) {
    val state = model.state
    var section by remember(layer) { mutableStateOf(initialSection) }
    val text = state.document.findLayer(layer) as? Layer.Text

    Column(Modifier.fillMaxSize().background(Ink.Ground).systemBarsPadding()) {
        StudioHeader("استودیو متن", onBack = onDone) {
            // The count of live effects, which is the one number that says how far this headline
            // has been taken. Real: it is the layer's own effect list, not a tally of which
            // sections have been visited.
            text?.let {
                Text(
                    "${Digits.technical(it.style.effects.size)} افکت" +
                        if (it.geometry3D != null) " · سه‌بعدی" else "",
                    style = MeasureStyle,
                    color = Ink.TextMuted,
                    maxLines = 1,
                    modifier = Modifier.padding(end = Space.small),
                )
            }
            AccentButton("تمام", onClick = onDone)
        }

        if (text == null) {
            // The layer went away underneath — deleted from the inspector, or undone. Saying so and
            // offering the way back is the whole of the contract `MissingSubject` exists for.
            MissingSubject(
                message = "این لایهٔ متنی دیگر نیست",
                action = "بازگشت به ویرایشگر",
                onAct = onDone,
            )
            return@Column
        }

        // A fixed band, not a fraction. The point of this screen is that the artwork stays exactly
        // where it was while you work — a canvas that resized as sections changed height would put
        // the headline somewhere different every time you switched panel.
        StudioCanvas(model, Modifier.fillMaxWidth().height(CANVAS_BAND)) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(Space.small)
                    .clip(Corners.button)
                    .background(Ink.Overlay)
                    .padding(horizontal = Space.small, vertical = Space.tight),
            ) {
                Text(
                    "${text.spec.font.family} · ${Digits.technical(text.spec.size.toInt())}pt",
                    style = NumericStyle,
                    color = Ink.TextMuted,
                    maxLines = 1,
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.Divider))

        TextStudioBody(
            state = state,
            content = SheetContent.TextStudio(layer, section),
            model = model,
            modifier = Modifier.fillMaxSize(),
            onPickSection = { section = it },
        )
    }
}

/**
 * Three hundred, which is the brief's number and survives the arithmetic.
 *
 * On the shortest phone this application supports it leaves about 240dp for the strip and the
 * controls, which is the section strip plus four rows — enough that every section opens on
 * something rather than on a scrollbar.
 */
private val CANVAS_BAND = 300.dp
