package ir.pixellab.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import ir.pixellab.core.codec.RasterImage
import ir.pixellab.core.editor.Look
import ir.pixellab.core.editor.withLook
import ir.pixellab.core.model.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The preset row, with each entry showing what it does to *this* photograph.
 *
 * A row of names is the version this replaces and it is barely a feature: nobody remembers what
 * "گرم ۲" did to a picture they graded last week, so the only way to use it was to apply each in
 * turn and undo. Every reference app shows a live thumbnail instead, and it is the single thing that
 * makes a preset library usable rather than merely present.
 *
 * Three decisions carry the whole thing:
 *
 * **The preview is the user's own picture, not a stock swatch.** A grade that looks good on a stock
 * portrait can be wrong on the photograph in front of them, and a swatch would be an illustration of
 * the preset rather than an answer to the question they are actually asking.
 *
 * **Rendered once and cached against the document.** Each thumbnail is a full pass through the
 * effect pipeline, so redoing them on every recomposition would make the panel unusable on exactly
 * the documents that need it most. The cache key is the document's own identity, so moving a slider
 * invalidates them and nothing else does.
 *
 * **Rendered one at a time, smallest work first.** Twelve simultaneous renders of a 4000-pixel
 * canvas is several gigabytes; they go through a single sequential pass at thumbnail size, and the
 * row fills in left to right while the user is still reading the first label.
 */
@Composable
fun LookStrip(
    looks: List<Look>,
    document: Document,
    wearing: (Look) -> Boolean,
    render: suspend (Document) -> RasterImage?,
    onChoose: (Look) -> Unit,
    onDelete: (Look) -> Unit,
) {
    if (looks.isEmpty()) return

    // Keyed by the document rather than by a counter, so the previews refresh when the picture
    // changes and survive every recomposition that leaves it alone.
    val cache = remember(document) { mutableStateMapOf<String, ImageBitmap>() }

    LaunchedEffect(document, looks) {
        val small = thumbnailOf(document) ?: return@LaunchedEffect
        for (look in looks) {
            if (cache.containsKey(look.id)) continue
            // Sequential on purpose: see the class note. A fan-out here is what turns a panel into
            // an out-of-memory crash on a large document.
            val image = render(small.withLook(look)) ?: continue
            val bitmap = withContext(Dispatchers.Default) { image.toImageBitmap() }
            cache[look.id] = bitmap
        }
    }

    LazyRow(
        Modifier.padding(horizontal = Space.gutter, vertical = Space.tight),
        horizontalArrangement = Arrangement.spacedBy(Space.small),
    ) {
        items(looks.size) { index ->
            val look = looks[index]
            LookTile(
                look = look,
                preview = cache[look.id],
                chosen = wearing(look),
                onChoose = { onChoose(look) },
                onDelete = { onDelete(look) },
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun LookTile(
    look: Look,
    preview: ImageBitmap?,
    chosen: Boolean,
    onChoose: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        Modifier.size(width = TILE, height = TILE + LABEL),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.tight),
    ) {
        Box(
            Modifier
                .size(TILE)
                .clip(Corners.small)
                .background(Ink.ChromeSunken)
                .border(
                    width = if (chosen) 2.dp else 1.dp,
                    color = if (chosen) Ink.Accent else Ink.Divider,
                    shape = Corners.small,
                )
                .combinedClickable(onClick = onChoose, onLongClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            if (preview != null) {
                Image(
                    bitmap = preview,
                    contentDescription = look.name,
                    // Cropped to fill rather than fitted: a letterboxed thumbnail wastes half the
                    // tile on background at exactly the size where every pixel counts.
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(TILE).clip(Corners.small),
                )
            } else {
                // A muted initial rather than a spinner. Twelve spinners in a row read as an
                // application that is stuck, and the tile is replaced within a frame or two anyway.
                Text(
                    look.name.take(1),
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink.TextMuted,
                )
            }
        }
        Text(
            look.name,
            style = MaterialTheme.typography.labelSmall,
            color = if (chosen) Ink.Accent else Ink.TextMuted,
            maxLines = 1,
        )
    }
}

/**
 * The document at thumbnail size.
 *
 * The *canvas* is shrunk and the layers are left alone, which works because every layer's transform
 * is in canvas coordinates and the renderer maps them through — so the whole composition arrives
 * scaled, at a fraction of the pixels. Rendering the full canvas and scaling the bitmap afterwards
 * would produce the same picture for a hundred times the work, which on a large document is the
 * difference between a panel that fills in and one that locks the application.
 */
private fun thumbnailOf(document: Document): Document? {
    val canvas = document.canvas
    if (canvas.width <= 0 || canvas.height <= 0) return null
    val scale = (TILE_PIXELS.toFloat() / maxOf(canvas.width, canvas.height)).coerceAtMost(1f)
    if (scale >= 1f) return document
    return document.copy(
        canvas = canvas.copy(
            width = (canvas.width * scale).toInt().coerceAtLeast(1),
            height = (canvas.height * scale).toInt().coerceAtLeast(1),
        ),
    )
}

private fun RasterImage.toImageBitmap(): ImageBitmap =
    android.graphics.Bitmap.createBitmap(pixels, width, height, android.graphics.Bitmap.Config.ARGB_8888)
        .asImageBitmap()

/** Large enough to judge a grade by, small enough that twelve of them render while you look. */
private const val TILE_PIXELS = 192

private val TILE = 72.dp
private val LABEL = 18.dp
