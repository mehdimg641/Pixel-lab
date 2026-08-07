package ir.pixellab.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import ir.pixellab.core.editor.Library
import ir.pixellab.core.editor.TemplatePreset

/**
 * Every size the application knows, on a page of its own.
 *
 * ### Why this is a screen and not the strip on the home page
 *
 * The home page shows the sizes because the first decision anybody makes is how big — but it shows
 * them *inside* a page that also carries the jobs and the recent work, so it can afford about eight
 * before the rest of the screen is pushed out of sight. The library has considerably more than
 * eight, and the ones past the fold are the print sizes, which are exactly the ones somebody goes
 * looking for on purpose.
 *
 * A person browsing sizes and a person starting work are doing different things. The strip serves
 * the second; this serves the first, which is why it has room for the categories to matter.
 *
 * ### The three shapes are not written here
 *
 * `templateSection` and `TemplateCategories` are the home page's, unchanged — a two-column grid on
 * Ember, staggered heights on Iris, a one-line-per-size list on Console. Writing a second copy of
 * that logic for this screen is how the two would end up disagreeing about what Console looks like.
 */
@Composable
fun TemplatesScreen(onNew: (TemplatePreset) -> Unit, onBack: () -> Unit) {
    val layout = LocalThemeSkin.current.layout
    var category by remember { mutableStateOf(ALL_TEMPLATES) }
    val templates = remember(category) {
        if (category == ALL_TEMPLATES) Library.templates else Library.templates.filter { it.group == category }
    }

    LazyColumn(
        Modifier.fillMaxSize().background(Ink.Ground).systemBarsPadding(),
        contentPadding = PaddingValues(
            start = Space.gutter,
            end = Space.gutter,
            // Enough that the last card clears the gesture bar rather than sitting against it.
            bottom = Space.huge,
        ),
        verticalArrangement = Arrangement.spacedBy(Space.small),
    ) {
        item {
            StudioHeader("قالب‌ها", onBack = onBack) {
                // The count, and it changes with the filter — which is the whole reason it is here
                // rather than being the constant total. «۶ از ۲۴» is the answer to "did that chip
                // do anything", asked of a grid too long to see the end of.
                Text(
                    "${Digits.technical(templates.size)} از ${Digits.technical(Library.templates.size)}",
                    style = MeasureStyle,
                    color = Ink.TextMuted,
                    maxLines = 1,
                    modifier = Modifier.padding(end = Space.small),
                )
            }
        }
        item { TemplateCategories(chosen = category, onPick = { category = it }) }
        templateSection(layout, templates, onNew)

        // Not silence when a group turns out to be empty. Every group in the shipped library has
        // entries, so this only fires if somebody adds a group and forgets its sizes — which is
        // precisely the moment a blank page is indistinguishable from a broken filter.
        if (templates.isEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = Space.wide),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "در «$category» اندازه‌ای نیست",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink.TextMuted,
                    )
                }
            }
        }
    }
}
