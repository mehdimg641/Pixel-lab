package ir.pixellab.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as UiColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ir.pixellab.core.editor.Collage
import ir.pixellab.core.model.Color

/**
 * Collage: several photographs in one frame.
 *
 * The panel is ordered the way the job is done — pick the pictures, pick the arrangement, then fuss
 * with the spacing — rather than the way the data model is shaped. It matters here more than in most
 * sheets because a collage is often the *first* thing someone does in the app, and a panel that
 * opens on a spacing slider before there is anything to space is a panel that has to be figured out.
 *
 * Every layout is drawn as a diagram of itself rather than named. "۱+۲ راست" means nothing at a
 * glance and the picture means everything; it is also the only way to tell twelve layouts apart in
 * the second someone is willing to spend on it.
 */
@Composable
fun CollageSheetBody(
    model: EditorViewModel,
    onPickPhotos: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        SheetSection("عکس‌ها")
        SheetAction(
            if (model.collage.isEmpty()) "انتخاب عکس‌ها" else "افزودن عکس",
            onClick = onPickPhotos,
        )
        if (model.collage.isEmpty()) {
            SheetHint("چند عکس انتخاب کنید؛ چیدمان خودش با تعدادشان جور می‌شود.")
        } else {
            SheetHint("برای برداشتن یک عکس، روی آن بزنید. جای خالی‌اش می‌ماند.")
            PhotoStrip(model)
        }

        SheetSection("چیدمان")
        LayoutGrid(model)

        SheetSection("فاصله و قاب")
        val style = model.collageStyle
        SheetSlider(
            label = "فاصلهٔ بین خانه‌ها",
            value = style.spacing,
            range = 0f..0.1f,
            onChange = { value, _ -> model.updateCollageStyle(style.copy(spacing = value)) },
        )
        SheetSlider(
            label = "حاشیهٔ بیرونی",
            value = style.margin,
            range = 0f..0.1f,
            onChange = { value, _ -> model.updateCollageStyle(style.copy(margin = value)) },
        )
        SheetSlider(
            label = "گردی گوشه‌ها",
            value = style.cornerRadius,
            range = 0f..0.15f,
            onChange = { value, _ -> model.updateCollageStyle(style.copy(cornerRadius = value)) },
        )

        SheetSection("رنگ زمینه")
        BackgroundSwatches(model)
    }
}

/**
 * What has been picked, in the order it will be laid out.
 *
 * Numbered, because the order is the only thing about the strip a user needs to reason about and it
 * is invisible otherwise: cell three is the third picture, and without the number nobody can predict
 * which cell a given photograph is about to land in.
 */
@Composable
private fun PhotoStrip(model: EditorViewModel) {
    LazyRow(
        Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.tight),
        horizontalArrangement = Arrangement.spacedBy(Space.small),
    ) {
        items(model.collage.size) { index ->
            val photo = model.collage[index]
            Column(
                Modifier
                    .width(THUMB)
                    .clip(Corners.small)
                    .clickable { model.removeCollagePhoto(index) }
                    .semantics { role = Role.Button }
                    .padding(Space.tight),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.tight),
            ) {
                Box(
                    Modifier
                        .size(THUMB)
                        .clip(Corners.small)
                        .background(Ink.ChromeSunken)
                        .border(1.dp, Ink.Divider, Corners.small),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${index + 1}", style = MaterialTheme.typography.titleMedium, color = Ink.Text)
                }
                Text(
                    photo.name.ifBlank { "عکس" },
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink.TextMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The layouts, each drawn as itself.
 *
 * A wrapping row of diagrams rather than a horizontal scroller, for the reason every chip row in
 * this app wraps: a sideways scroller hides its own contents, and a layout the user never sees is a
 * layout they will never choose.
 */
@Composable
private fun LayoutGrid(model: EditorViewModel) {
    SheetChips {
        for (layout in Collage.LAYOUTS) {
            LayoutSwatch(
                layout = layout,
                chosen = layout.name == model.collageLayout.name,
                onClick = { model.chooseCollageLayout(layout) },
            )
        }
    }
}

@Composable
private fun LayoutSwatch(layout: Collage.Layout, chosen: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(SWATCH)
            .clip(Corners.small)
            .background(if (chosen) Ink.Accent.copy(alpha = CHOSEN_TINT) else UiColor.Transparent)
            .border(
                width = if (chosen) 1.5.dp else 1.dp,
                color = if (chosen) Ink.Accent else Ink.Outline,
                shape = Corners.small,
            )
            .clickable(onClick = onClick)
            .semantics {
                role = Role.RadioButton
                selected = chosen
                // The layout's own name. Without it these are eleven identical buttons to a screen
                // reader — and this is the one control in the sheet that is *entirely* a picture,
                // so there is no text anywhere in it to fall back on.
                contentDescription = layout.persianLabel
            }
            .padding(CELL_INSET),
    ) {
        // The cells drawn at their real proportions, so the diagram and the result agree. Anything
        // approximate here — evenly-sized boxes standing in for a 1+2 — would be a picture of a
        // different layout than the one the press applies.
        for (cell in layout.cells) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(SWATCH - CELL_INSET * 2)
                    .padding(
                        start = (SWATCH - CELL_INSET * 2) * cell.left,
                        top = (SWATCH - CELL_INSET * 2) * cell.top,
                        end = (SWATCH - CELL_INSET * 2) * (1f - cell.right),
                        bottom = (SWATCH - CELL_INSET * 2) * (1f - cell.bottom),
                    ),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height((SWATCH - CELL_INSET * 2) * (cell.bottom - cell.top))
                        .padding(CELL_GAP)
                        .background(if (chosen) Ink.Accent else Ink.TextMuted, Corners.small),
                )
            }
        }
    }
}

/**
 * What shows between the cells.
 *
 * Five swatches rather than a full picker: a collage background is chosen in a second from a short
 * list of things that work, and the two that matter — white and black — are what almost every
 * printed collage uses. The full picker is a tap away on the layer itself for anyone who wants it.
 */
@Composable
private fun BackgroundSwatches(model: EditorViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.gutter, vertical = Space.tight),
        horizontalArrangement = Arrangement.spacedBy(Space.small),
    ) {
        for ((name, colour) in BACKGROUNDS) {
            val chosen = colour == model.collageStyle.background
            Box(
                Modifier
                    .size(Space.touch)
                    .clip(Corners.small)
                    .background(UiColor(colour.r, colour.g, colour.b, 1f))
                    .border(
                        width = if (chosen) 2.dp else 1.dp,
                        color = if (chosen) Ink.Accent else Ink.Divider,
                        shape = Corners.small,
                    )
                    .clickable { model.updateCollageStyle(model.collageStyle.copy(background = colour)) }
                    .semantics {
                        role = Role.RadioButton
                        selected = chosen
                        contentDescription = name
                    },
            )
        }
    }
}

/**
 * The grounds a collage can sit on, each with the word for it.
 *
 * Not [BASIC_SWATCHES]: these are *paper*, not ink. A collage's ground is chosen from a handful of
 * neutrals plus one warm accent, which is a different question from picking a colour to draw with,
 * and offering nine saturated hues here would be offering the wrong nine things.
 */
private val BACKGROUNDS = listOf(
    "سفید" to Color.WHITE,
    "کرم" to Color(0.94f, 0.92f, 0.88f),
    "زغالی" to Color(0.15f, 0.15f, 0.16f),
    "سیاه" to Color.BLACK,
    "آجری" to Color(0.85f, 0.35f, 0.30f),
)

private val THUMB = 56.dp
private val SWATCH = 64.dp
private val CELL_INSET = 4.dp
private val CELL_GAP = 1.dp

/** The same value [SheetChip] uses, so a chosen layout reads as chosen the way a chosen chip does. */
private const val CHOSEN_TINT = 0.16f
