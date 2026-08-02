package ir.pixellab.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ir.pixellab.core.editor.Library
import ir.pixellab.core.editor.TemplatePreset
import java.io.File

/**
 * What the app opens on.
 *
 * The app used to launch straight into the editor with a grey rectangle on the canvas, which asked
 * the user to understand nine tools before they had decided what they were making. This is the Canva
 * half of the brief: a screen whose only job is to get someone from "I want a cover" to a canvas of
 * the right size, in one press.
 *
 * The order down the page is the order the decisions actually happen in. What am I making (a size),
 * or what am I doing (a job), or what was I already working on (a recent file). Everything else —
 * every filter, every adjustment, every one of the twenty-seven blend modes — is behind the canvas,
 * because none of it is a thing a person opens the app *to do*.
 */
@Composable
fun HomeScreen(
    projects: List<File>,
    onNew: (TemplatePreset) -> Unit,
    onOpen: (File) -> Unit,
    onQuickAction: (QuickAction) -> Unit,
    onSettings: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize().background(Ink.Ground).systemBarsPadding(),
        contentPadding = PaddingValues(
            start = Space.gutter,
            end = Space.gutter,
            // Enough that the last card clears the gesture bar and does not sit against it.
            bottom = Space.huge,
        ),
        verticalArrangement = Arrangement.spacedBy(Space.small),
    ) {
        item { Masthead(onSettings) }
        item { QuickActionRow(onQuickAction) }
        item { SectionHeader("شروع تازه") }
        item { TemplateStrip(onNew) }
        item { SectionHeader("کارهای اخیر") }

        if (projects.isEmpty()) {
            item { EmptyRecents() }
        } else {
            items(projects, key = { it.absolutePath }) { file ->
                ProjectRow(file, onOpen = { onOpen(file) })
            }
        }
    }
}

/**
 * The name, and the one action the screen is about.
 *
 * A blank canvas rather than a template, because the primary action must not require a decision
 * first — the strip below is where a size gets chosen, and a person who already knows they want a
 * square starts here and resizes later.
 */
@Composable
private fun Masthead(onSettings: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = Space.medium)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("پیکسل‌لب", style = MaterialTheme.typography.displaySmall, color = Ink.Text)
                Text(
                    "طراحی کاور، متن سه‌بعدی فارسی، ویرایش عکس",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink.TextMuted,
                )
            }
            BarIcon(Icons.Filled.Settings, "تنظیمات", onClick = onSettings)
        }
    }
}

/**
 * The jobs, as opposed to the tools.
 *
 * Named for the outcome — "پس‌زمینه رو حذف کن" — rather than for the machinery that produces it.
 * The engine underneath is a segmentation pass and an edge refinement, and a row that said so would
 * be accurate and useless. Every one of these lands on a canvas with the right tool already open.
 */
@Composable
private fun QuickActionRow(onQuickAction: (QuickAction) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = Space.large)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Space.small),
    ) {
        QuickAction.entries.forEach { action ->
            IconTile(action.icon, action.label) { onQuickAction(action) }
        }
    }
}

/** What a person opens the app to do. Each one opens a canvas with the right sheet already up. */
enum class QuickAction(val icon: ImageVector, val label: String) {
    /** The reason this app exists. First, and it stays first. */
    DIMENSIONAL(Icons.Filled.ViewInAr, "متن سه‌بعدی"),
    PHOTO(Icons.Filled.Image, "ویرایش عکس"),
    CUTOUT(Icons.Filled.ContentCut, "حذف پس‌زمینه"),
    RETOUCH(Icons.Filled.Face, "روتوش چهره"),
    TEXT(Icons.Filled.TextFields, "متن"),
    PAINT(Icons.Filled.Brush, "نقاشی"),
    EFFECTS(Icons.Filled.AutoAwesome, "افکت"),
}

/**
 * The sizes, as pictures of themselves.
 *
 * Each card is drawn at the template's own aspect ratio, so a story is tall and a business card is
 * wide without anybody reading the numbers. That shape is the fastest thing on the screen to
 * recognise, and it is why the dimensions can stay small and grey underneath rather than competing.
 */
@Composable
private fun TemplateStrip(onNew: (TemplatePreset) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.medium),
    ) {
        item { BlankCard { onNew(BLANK) } }
        items(Library.templates, key = { it.name }) { template ->
            TemplateCard(template) { onNew(template) }
        }
    }
}

/** The first card in the strip: no size decided, start drawing. */
@Composable
private fun BlankCard(onClick: () -> Unit) {
    Column(
        Modifier
            .width(CARD)
            .clip(Corners.card)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button },
        verticalArrangement = Arrangement.spacedBy(Space.small),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(PREVIEW)
                .clip(Corners.card)
                .background(Ink.AccentSoft)
                .border(1.dp, Ink.Accent, Corners.card),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = null,
                tint = Ink.Accent,
                modifier = Modifier.size(28.dp),
            )
        }
        Text("بوم خالی", style = MaterialTheme.typography.labelLarge, color = Ink.Text, maxLines = 1)
        Text("۱۰۸۰ × ۱۰۸۰", style = MaterialTheme.typography.labelSmall, color = Ink.TextMuted)
    }
}

@Composable
private fun TemplateCard(template: TemplatePreset, onClick: () -> Unit) {
    Column(
        Modifier
            .width(CARD)
            .clip(Corners.card)
            .clickable(onClick = onClick)
            .semantics { role = Role.Button },
        verticalArrangement = Arrangement.spacedBy(Space.small),
    ) {
        // A fixed-height well with the proportional page floating inside it, rather than a card that
        // changes height with its aspect. A strip of cards whose tops and bottoms do not line up
        // reads as broken layout, however correct each individual card is.
        Box(
            Modifier
                .fillMaxWidth()
                .height(PREVIEW)
                .clip(Corners.card)
                .background(Ink.ChromeRaised),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .padding(Space.small)
                    .fillMaxSize()
                    .wrapToAspect(template.width.toFloat() / template.height)
                    .clip(Corners.small)
                    .background(Ink.Text),
            )
        }
        Text(
            template.name,
            style = MaterialTheme.typography.labelLarge,
            color = Ink.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${digits(template.width)} × ${digits(template.height)}",
            style = MaterialTheme.typography.labelSmall,
            color = Ink.TextMuted,
            maxLines = 1,
        )
    }
}

/**
 * Fits a box of the given ratio inside whatever space is left, centred.
 *
 * `aspectRatio` alone grows to fill one axis and overflows the other, which for a 2480×3508 poster
 * means a page taller than its own card. `matchHeightConstraintsFirst` picks the axis by which
 * constraint binds, which is what "fit inside" means.
 */
private fun Modifier.wrapToAspect(ratio: Float): Modifier =
    this.aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f)

@Composable
private fun EmptyRecents() {
    Panel {
        Text("هنوز پروژه‌ای ذخیره نکرده‌اید", style = MaterialTheme.typography.titleMedium, color = Ink.Text)
        Note("هر طرحی که ذخیره کنید اینجا می‌آید و با یک ضربه باز می‌شود.")
    }
}

/**
 * One saved project.
 *
 * A row rather than a grid tile, and the reason is that there is no thumbnail to show. A grid of
 * identical placeholder squares is worse than a list: it promises a picture and delivers a shape,
 * and the name — which is the only thing that distinguishes two projects — ends up truncated to fit
 * a tile it did not need to be in.
 */
@Composable
private fun ProjectRow(file: File, onOpen: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Corners.card)
            .background(Ink.ChromeRaised)
            .clickable(onClick = onOpen)
            .padding(Space.large)
            .semantics { role = Role.Button },
        horizontalArrangement = Arrangement.spacedBy(Space.large),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(Space.touch)
                .clip(Corners.small)
                .background(Ink.ChromeSunken),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Description, contentDescription = null, tint = Ink.TextMuted, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.hair)) {
            Text(
                file.nameWithoutExtension,
                style = MaterialTheme.typography.titleMedium,
                color = Ink.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${relativeTime(file.lastModified())}، ${sizeOf(file.length())}",
                style = MaterialTheme.typography.labelSmall,
                color = Ink.TextMuted,
                maxLines = 1,
            )
        }
    }
}

/**
 * How long ago, in words.
 *
 * A timestamp is the wrong answer to "which of these was I working on". Nobody remembers that they
 * saved at 14:32; they remember that it was this morning.
 */
internal fun relativeTime(millis: Long, now: Long = System.currentTimeMillis()): String {
    val elapsed = (now - millis).coerceAtLeast(0)
    val minutes = elapsed / 60_000
    val hours = minutes / 60
    val days = hours / 24
    return when {
        minutes < 1 -> "همین حالا"
        minutes < 60 -> "${digits(minutes)} دقیقه پیش"
        hours < 24 -> "${digits(hours)} ساعت پیش"
        days < 30 -> "${digits(days)} روز پیش"
        else -> "${digits(days / 30)} ماه پیش"
    }
}

/** Bytes, at the precision a person actually wants: none. */
internal fun sizeOf(bytes: Long): String = when {
    bytes < 1024 -> "${digits(bytes)} بایت"
    bytes < 1024 * 1024 -> "${digits(bytes / 1024)} کیلوبایت"
    else -> "${digits(bytes / (1024 * 1024))} مگابایت"
}

/**
 * Latin digits rendered as Persian ones.
 *
 * Done by hand rather than through a locale-aware formatter, because the app forces its own locale
 * and a device set to English would otherwise show ۱۰۸۰ as 1080 in a sentence that is Persian on
 * both sides of it. Mixed numerals inside one line are the detail that makes a Persian interface
 * read as translated rather than as written.
 */
internal fun digits(value: Long): String = buildString {
    val text = value.toString(10)
    for (character in text) {
        append(if (character in '0'..'9') PERSIAN_DIGITS[character - '0'] else character)
    }
}

internal fun digits(value: Int): String = digits(value.toLong())

private const val PERSIAN_DIGITS = "۰۱۲۳۴۵۶۷۸۹"

/** The blank start. Square, because a cover is square more often than it is anything else. */
private val BLANK = TemplatePreset("سند تازه", 1080, 1080, "عمومی")

private val CARD = 132.dp
private val PREVIEW = 132.dp

/**
 * Reads the saved projects, off the main thread, whenever the screen comes back.
 *
 * Re-read on every appearance rather than cached: the user returns here after saving, and a list
 * that still shows what was there before the save is a list that looks like the save failed.
 */
@Composable
fun rememberProjects(reloadKey: Any): List<File> {
    val context = LocalContext.current
    var projects by remember { mutableStateOf(emptyList<File>()) }
    LaunchedEffect(reloadKey) {
        projects = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            Storage.listProjects(context)
        }
    }
    return projects
}
