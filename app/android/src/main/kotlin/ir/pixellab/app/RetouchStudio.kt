package ir.pixellab.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ir.pixellab.core.model.Layer

/**
 * Retouching, with the face it is working on in the room.
 *
 * ### The split down the middle
 *
 * «چهره» is `PortraitSheetBody` and «اندام» is `RetouchSheetBody`, and that is not a division
 * invented for this screen — it is the one `SheetContent` already argues for: portrait work needs a
 * *detected face* and offers what a face makes possible; retouching is manual and works on any
 * picture. Merging them would put half the panel behind a precondition the other half does not
 * have, which is precisely how a feature ends up looking broken to somebody whose photograph has no
 * face in it.
 *
 * ### The mesh badge says only what is true
 *
 * The mock-up carries `FACE MESH · 468 PTS` as a permanent label. Ours appears only once the
 * detector has actually returned something, and the number is the count of points it returned — for
 * one face or for four. Before that there is nothing to say, so nothing is said. A badge announcing
 * a mesh over a photograph the model has never looked at is a lie drawn in the interface, and it is
 * the same lie as a histogram of sample data.
 */
@Composable
fun RetouchStudioScreen(model: EditorViewModel, onBack: () -> Unit) {
    val state = model.state
    var tab by remember { mutableStateOf(RetouchTab.FACE) }
    val faces = model.faces

    Column(Modifier.fillMaxSize().background(Ink.Ground).systemBarsPadding()) {
        StudioHeader("رتوش", onBack = onBack) {
            // Enabled only when there is something to put back. A reset that is always live invites
            // the press, and the press either does nothing or throws away work — and the user
            // cannot tell which before they try it.
            val canReset = model.canUndo
            Text(
                "بازنشانی",
                style = MaterialTheme.typography.labelLarge,
                color = if (canReset) Ink.Text else Ink.TextDisabled,
                maxLines = 1,
                modifier = Modifier
                    .clip(Corners.button)
                    .clickable(enabled = canReset, onClickLabel = "بازنشانی") { model.undo() }
                    .padding(horizontal = Space.medium, vertical = Space.small)
                    .semantics { role = Role.Button },
            )
        }

        StudioCanvas(model, Modifier.weight(1f).fillMaxWidth()) {
            if (faces.isNotEmpty()) {
                val points = faces.sumOf { it.points.size }
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(Space.small)
                        .clip(Corners.button)
                        .background(Ink.Overlay)
                        .padding(horizontal = Space.small, vertical = Space.tight),
                ) {
                    Text(
                        "مشِ چهره · ${Digits.technical(points)} نقطه" +
                            if (faces.size > 1) " · ${Digits.technical(faces.size)} چهره" else "",
                        style = MeasureStyle,
                        color = Ink.Accent,
                        maxLines = 1,
                    )
                }
            }
        }

        Column(Modifier.fillMaxWidth().background(Ink.Chrome).weight(PANEL_SHARE)) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.Divider))

            // A segmented pair rather than two chips: these are two halves of one screen, and a
            // chip row would read as "two things you can turn on" rather than "which half you are
            // looking at".
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.gutter, vertical = Space.small)
                    .clip(Corners.card)
                    .background(Ink.ChromeRaised)
                    .padding(Space.tight),
                horizontalArrangement = Arrangement.spacedBy(Space.tight),
            ) {
                for (entry in RetouchTab.entries) {
                    val chosen = entry == tab
                    Box(
                        Modifier
                            .weight(1f)
                            .heightIn(min = Space.touch)
                            .clip(Corners.button)
                            .background(if (chosen) Ink.Accent else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable(onClickLabel = entry.persianLabel) { tab = entry }
                            .semantics {
                                role = Role.Tab
                                selected = chosen
                                contentDescription = entry.persianLabel
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            entry.persianLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (chosen) Ink.OnAccent else Ink.TextMuted,
                            maxLines = 1,
                        )
                    }
                }
            }

            if (state.primaryLayer !is Layer.Image) {
                MissingSubject(
                    message = "رتوش روی پیکسل کار می‌کند — یک لایهٔ تصویر انتخاب کنید",
                    action = "افزودن عکس",
                    onAct = LocalEditorActions.current.pickImage,
                )
                return@Column
            }

            when (tab) {
                RetouchTab.FACE -> PortraitSheetBody(state, model, Modifier.fillMaxSize())
                RetouchTab.BODY -> RetouchSheetBody(state, model, Modifier.fillMaxSize())
            }
        }
    }
}

/** The two halves, and the words on them. */
enum class RetouchTab(val persianLabel: String) {
    FACE("چهره"),
    BODY("اندام و پوست"),
}

/**
 * Rather more of the screen than the editor's panel takes, and on purpose.
 *
 * Both bodies here are lists of sliders you work down, and the canvas above is there to be *glanced
 * at* between them rather than worked on directly — nothing in this room is a drag on the artwork.
 * The editor is the other way round, which is why its panel is smaller.
 */
private const val PANEL_SHARE = 1.1f
