package ir.pixellab.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ir.pixellab.core.editor.EditorState
import ir.pixellab.core.fonts.Script
import ir.pixellab.core.fonts.Typeface
import ir.pixellab.core.model.Layer

/**
 * The font sheet.
 *
 * Lists *typefaces*, not files. The user's own library is 312 files but only 12 designs — the rest
 * are weights and `FaNum`/`NoEn`/`Web` cuts of the same faces — so a file list is three screens of
 * near-duplicates with no way to tell them apart. Grouping is what makes the sheet usable at all.
 *
 * Tapping a face with nothing selected creates a text layer; tapping with a text layer selected
 * restyles it. That is one control doing the two things the user actually wants from a font list,
 * rather than a picker that only works during creation.
 */
@Composable
fun FontPickerBody(state: EditorState, model: EditorViewModel, modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf("") }
    val store = model.fontStore
    // Only a *text* layer can be restyled. Checked by kind rather than by whether its family
    // resolves, or a layer using a font the user has since removed would silently spawn a second
    // layer instead of being repaired.
    val target = state.selectedLayers.firstOrNull { it.id == state.selection.primary } as? Layer.Text
    val current = target?.let { model.typefaceOf(it.id) }

    val visible = remember(store.catalog, query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            store.catalog.typefaces
        } else {
            // Matched on the family name in either script: the user knows a face as "دانا" or as
            // "Dana" depending on where they got it, and often does not know which the file says.
            store.catalog.typefaces.filter { face ->
                face.name.contains(trimmed, ignoreCase = true) ||
                    face.files.any { it.fullName.contains(trimmed, ignoreCase = true) }
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        SearchField(query, onChange = { query = it })

        // The way through to the measurements. Picking a face and setting its tracking are
        // different activities on different timescales, so they are different sheets — but the one
        // that leads to the other has to say so, or the second is undiscoverable.
        if (target != null) {
            SheetAction("پنل نویسه و بند") {
                model.act {
                    openSheet(ir.pixellab.core.editor.SheetContent.Typography, ir.pixellab.core.editor.SheetDetent.FULL)
                }
            }
        }

        when {
            store.scanning && store.catalog.typefaces.isEmpty() ->
                Centred { CircularProgressIndicator(color = Ink.Accent) }

            store.catalog.typefaces.isEmpty() ->
                Centred {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("فونتی پیدا نشد", color = Ink.Text)
                        Text(
                            "فایل‌های فونت را در پوشهٔ fonts بریزید و دوباره اسکن کنید",
                            style = MaterialTheme.typography.labelSmall,
                            color = Ink.TextMuted,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }

            visible.isEmpty() -> Centred { Text("چیزی مطابق «$query» نبود", color = Ink.TextMuted) }

            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            ) {
                items(visible, key = { it.name }) { face ->
                    TypefaceRow(
                        face = face,
                        chosen = face.name == current?.name,
                        onClick = {
                            if (target != null) model.setTextFont(target.id, face) else model.addText(face)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    BasicTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        cursorBrush = SolidColor(Ink.Accent),
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ink.Text),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            // The field had no name at all: its only label was the placeholder, which disappears
            // the moment anybody types. A placeholder is not a label — it is the hint that goes
            // *with* one — and a screen reader announcing an anonymous edit box is what that
            // shortcut costs.
            .semantics { contentDescription = "جست‌وجوی فونت" }
            .clip(RoundedCornerShape(12.dp))
            .background(Ink.Chrome),
        // Height and inset inside the decoration rather than wrapped around the field. Wrapped, the
        // well stood 44dp tall and only the 24dp holding the text answered a tap — the pointer
        // handling of a Compose field sits inside whatever padding you put around it.
        decorationBox = { field ->
            Box(
                Modifier
                    .heightIn(min = Space.touch)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (query.isEmpty()) {
                    Text("جست‌وجوی فونت", color = Ink.TextMuted, style = MaterialTheme.typography.bodyMedium)
                }
                field()
            }
        },
    )
}

/**
 * One typeface.
 *
 * The preview is the face's own name plus a sample in its own script — previewing an Arabic face
 * with Latin text tells the user nothing about the only thing they are choosing between. The badges
 * carry what no other mobile picker shows: whether the face has a kashida axis, stylistic sets, or
 * Persian digits, all of which decide whether a given design is even possible.
 */
@Composable
private fun TypefaceRow(face: Typeface, chosen: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (chosen) Ink.Accent.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                face.previewText,
                // Large enough that a Persian face's actual letterforms are legible; at label size
                // every Naskh design looks identical.
                fontSize = 22.sp,
                color = if (chosen) Ink.Accent else Ink.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                face.name,
                style = MaterialTheme.typography.labelSmall,
                color = Ink.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (face.hasKashidaAxis) Badge("کشیده")
            if (face.stylisticSets.isNotEmpty()) Badge("ss${face.stylisticSets.size}")
            if (face.supportsPersianDigits) Badge("۱۲۳")
            if (face.script == Script.BOTH) Badge("دوزبانه")
            Badge("${face.weights.size}")
        }
    }
}

@Composable
private fun Badge(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = Ink.TextMuted,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Ink.Chrome)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun Centred(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
