package ir.pixellab.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color as UiColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ir.pixellab.core.editor.EditorState
import ir.pixellab.core.editor.Library
import ir.pixellab.core.editor.StylePreset
import ir.pixellab.core.editor.TemplatePreset
import ir.pixellab.core.model.Fill

/**
 * The library: styles to apply and templates to start from.
 *
 * Styles first, because they are what someone reaches for while they are already working; templates
 * are a once-per-document decision and belong further down. Each style shows a swatch drawn from its
 * own fill rather than a stock thumbnail, so a picker entry cannot go out of step with what it
 * actually applies.
 */
@Composable
fun LibrarySheetBody(state: EditorState, model: EditorViewModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(
            if (state.hasSelection) "روی لایهٔ انتخاب‌شده اعمال می‌شود" else "اول یک لایه انتخاب کنید",
            style = MaterialTheme.typography.labelSmall,
            color = Ink.TextMuted,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )

        Section("استایل‌ها")
        for (preset in Library.styles) {
            StyleRow(preset, enabled = state.hasSelection) { model.applyStyle(preset) }
        }

        Section("قالب‌ها")
        Text(
            "قالب جدید سند فعلی را جایگزین می‌کند — اول ذخیره کنید",
            style = MaterialTheme.typography.labelSmall,
            color = Ink.TextMuted,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        // A grid rather than a list: a template is chosen by its proportions, and a row of names
        // tells the user nothing about the shape they are about to get.
        LazyVerticalGrid(
            columns = GridCells.Fixed(GRID_COLUMNS),
            modifier = Modifier.fillMaxWidth().height(TEMPLATE_GRID_HEIGHT.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(Library.templates) { template ->
                TemplateCard(template) { model.newFromTemplate(template) }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = Ink.TextMuted,
        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun StyleRow(preset: StylePreset, enabled: Boolean, onApply: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onApply)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(swatchOf(preset)),
        )
        Column(Modifier.weight(1f)) {
            Text(
                preset.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) Ink.Text else Ink.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${preset.style.effects.size} افکت",
                style = MaterialTheme.typography.labelSmall,
                color = Ink.TextMuted,
            )
        }
    }
}

/**
 * A swatch drawn from the style's own fill.
 *
 * Not a stock thumbnail: a picture stored alongside a preset goes stale the moment the preset is
 * edited, and the user then chooses by an image that is not what they get.
 */
private fun swatchOf(preset: StylePreset): Brush = when (val fill = preset.style.fill) {
    is Fill.Solid -> Brush.linearGradient(
        listOf(
            UiColor(fill.color.r, fill.color.g, fill.color.b, 1f),
            UiColor(fill.color.r, fill.color.g, fill.color.b, 1f),
        ),
    )
    is Fill.Gradient -> Brush.verticalGradient(
        fill.stops.sortedBy { it.position }.map { UiColor(it.color.r, it.color.g, it.color.b, 1f) },
    )
    // A pattern needs its asset and a backdrop needs the canvas beneath it; neither is available to
    // a swatch, so both show as the chrome rather than as a wrong colour.
    else -> Brush.linearGradient(listOf(Ink.ChromeSunken, Ink.Chrome))
}

@Composable
private fun TemplateCard(template: TemplatePreset, onPick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Ink.Chrome)
            .clickable(onClick = onPick)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The proportions themselves, at a fixed height, which is the one thing a template picker
        // has to communicate.
        val ratio = template.width.toFloat() / template.height
        Box(
            Modifier
                .height(PREVIEW_HEIGHT.dp)
                .fillMaxWidth(ratio.coerceIn(MIN_RATIO, 1f))
                .clip(RoundedCornerShape(4.dp))
                .background(Ink.ChromeSunken),
        )
        Text(
            template.name,
            style = MaterialTheme.typography.labelMedium,
            color = Ink.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            "${template.width}×${template.height}",
            style = MaterialTheme.typography.labelSmall,
            color = Ink.TextMuted,
        )
    }
}

private const val GRID_COLUMNS = 2
private const val TEMPLATE_GRID_HEIGHT = 420
private const val PREVIEW_HEIGHT = 56

/** Below this a portrait preview is a sliver; the label carries the exact size anyway. */
private const val MIN_RATIO = 0.35f
