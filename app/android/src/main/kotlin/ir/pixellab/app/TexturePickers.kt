package ir.pixellab.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as UiColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ir.pixellab.core.codec.RasterImage
import ir.pixellab.core.imaging.Procedural
import ir.pixellab.core.imaging.Raster
import ir.pixellab.core.model.AssetId
import ir.pixellab.core.paint.BrushTip

/**
 * Choosing a brush tip and choosing a pattern.
 *
 * Both offer generated textures rather than a shipped asset pack, and that is a consequence of the
 * app being offline rather than a compromise. It also buys two things a folder of PNGs cannot: a tip
 * is exact at whatever size it is used, instead of resampled from one fixed resolution, and a
 * pattern is seamless because it was constructed to be, not because someone was careful in a
 * painting program.
 *
 * The swatches are rendered from the same generator the paint engine will use. A preview drawn any
 * other way is a promise the stroke then breaks.
 */
@Composable
fun TipPicker(model: EditorViewModel, modifier: Modifier = Modifier) {
    val preset = model.paint.preset
    val chosen = (preset.tip as? BrushTip.Sampled)?.asset

    Column(modifier.fillMaxWidth()) {
        SheetSection("نوک قلم‌مو")
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The computed round tip stays first and stays distinct from the generated one: it costs
            // nothing to store, it is exact at any size, and it is what most work uses.
            Swatch(
                bitmap = remember { coverageBitmap(Procedural.tip(Procedural.Tip.ROUND, SWATCH)) },
                label = "محاسباتی",
                chosen = preset.tip is BrushTip.Round,
                onClick = { model.paint.preset = preset.copy(tip = BrushTip.Round()) },
            )
            for (kind in Procedural.Tip.entries) {
                val id = AssetId("tip-${kind.name.lowercase()}")
                Swatch(
                    bitmap = remember(kind) { coverageBitmap(Procedural.tip(kind, SWATCH, seed = kind.ordinal)) },
                    label = kind.persianLabel,
                    chosen = chosen == id,
                    onClick = { model.useGeneratedTip(kind) },
                )
            }
            // Whatever the user put in `brushes/`, after the generated ones rather than before:
            // the generated tips are exact at any size and are what most work uses, and a folder
            // holding two hundred imported tips should not push them off the left of the row.
            for (tip in model.importedTips) {
                val swatch = remember(tip.asset) {
                    model.assetStore.source.load(tip.asset)?.let(::storedBitmap)
                } ?: continue
                Swatch(
                    bitmap = swatch,
                    label = tip.name,
                    chosen = chosen == tip.asset,
                    onClick = { model.useImportedTip(tip.asset) },
                )
            }
        }
        // Scanned when the panel is drawn, because a brush pack arrives from outside the app and
        // there is no event to react to. Same reasoning `DimensionalSheet` applies to environments.
        LaunchedEffect(Unit) { model.loadImportedTips() }

        SheetHint(
            when {
                model.importingTips -> "در حال خواندن پوشهٔ قلم‌مو…"
                model.importedTips.isEmpty() ->
                    "نوک‌ها ساخته می‌شوند، نه از فایل — در هر اندازه‌ای دقیق‌اند. " +
                        "برای افزودن، فایل ABR یا PNG را در پوشهٔ brushes بریزید"
                else -> "${model.importedTips.size} نوک از پوشهٔ brushes خوانده شد"
            },
        )
    }
}

/**
 * The pattern picker.
 *
 * @param onPick receives the asset the tile was registered under, ready for a `Fill.Pattern`.
 */
@Composable
fun PatternPicker(model: EditorViewModel, onPick: (AssetId) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        SheetSection("الگو")
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (kind in Procedural.Pattern.entries) {
                Swatch(
                    // Drawn at two repeats so the swatch itself shows the tile *repeating*, which is
                    // the only way a seam would be visible in a preview.
                    bitmap = remember(kind) {
                        coverageBitmap(Procedural.pattern(kind, SWATCH, repeats = SWATCH_REPEATS))
                    },
                    label = kind.persianLabel,
                    chosen = false,
                    onClick = { onPick(model.registerPattern(kind)) },
                )
            }
        }
        SheetHint("هر کاشی بی‌درز است — چون این‌طور ساخته شده، نه چون کسی مراقب بوده")
    }
}

@Composable
private fun Swatch(bitmap: ImageBitmap, label: String, chosen: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Image(
            bitmap = bitmap,
            contentDescription = label,
            modifier = Modifier
                .size(SWATCH_SIZE.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Ink.ChromeSunken)
                .border(
                    width = if (chosen) 2.dp else 1.dp,
                    color = if (chosen) Ink.Accent else Ink.Divider,
                    shape = RoundedCornerShape(8.dp),
                )
                // The whole swatch is the target, not the picture inside it: the platform's minimum
                // is 48dp and a smaller hit area is one people miss.
                .clickable(onClickLabel = label, onClick = onClick)
                .semantics { contentDescription = if (chosen) "$label، انتخاب‌شده" else label },
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (chosen) Ink.Accent else Ink.TextMuted,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * A coverage map drawn as white on transparent.
 *
 * White rather than the brush's colour, because coverage is not colour — a swatch tinted with the
 * current colour would read as "this is what you will paint" when the tip decides only *how much*
 * of that colour lands.
 */
private fun coverageBitmap(raster: Raster): ImageBitmap {
    val pixels = IntArray(raster.pixelCount) { i ->
        val alpha = (raster.data[i].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        (alpha shl 24) or 0x00FFFFFF
    }
    return android.graphics.Bitmap
        .createBitmap(pixels, raster.width, raster.height, android.graphics.Bitmap.Config.ARGB_8888)
        .asImageBitmap()
}

/**
 * The same swatch, for a tip that is already in the store rather than generated on the spot.
 *
 * An imported tip is stored exactly as a generated one is — white with the coverage in alpha — so
 * this is a straight handover of the pixels. Re-deriving coverage here would give the picker a
 * second opinion about what the tip looks like, and the swatch's whole job is to be the same
 * opinion the stroke will have.
 */
private fun storedBitmap(image: RasterImage): ImageBitmap =
    android.graphics.Bitmap
        .createBitmap(image.pixels, image.width, image.height, android.graphics.Bitmap.Config.ARGB_8888)
        .asImageBitmap()

/** A tip found in the user's folder: what to draw with, and what to call it. */
data class ImportedTip(val asset: AssetId, val name: String)

/**
 * A brush file's coverage mask as an image the asset store can hold.
 *
 * White with the mask in alpha, matching [Raster.toCoverageImage] exactly — the rasteriser reads
 * alpha and ignores the colour, so a mask stored as grey would paint at the right shape and the
 * wrong strength. The two conversions produce the same thing on purpose: a tip out of an `.abr` and
 * a generated one have to be interchangeable, or the picker offers two kinds of swatch that behave
 * differently.
 */
fun ir.pixellab.core.codec.AbrBrush.toCoverageImage(): RasterImage = RasterImage(
    width = width,
    height = height,
    pixels = IntArray(width * height) { i ->
        ((mask[i].toInt() and 0xFF) shl 24) or 0x00FFFFFF
    },
)

/**
 * A decoded picture read as coverage.
 *
 * Luminance rather than the alpha channel, because a brush tip saved as a PNG is almost always
 * black on white with no transparency at all — reading its alpha would make every such file a
 * fully opaque square. Where the file *does* carry alpha it is multiplied in, so a tip cut out on
 * transparency still works.
 */
fun RasterImage.asCoverage(): RasterImage = RasterImage(
    width = width,
    height = height,
    pixels = IntArray(width * height) { i ->
        val argb = pixels[i]
        val red = (argb shr 16) and 0xFF
        val green = (argb shr 8) and 0xFF
        val blue = argb and 0xFF
        // Rec. 601 luma, and inverted: ink is dark in the file and opaque in the tip.
        val luma = (red * 77 + green * 151 + blue * 28) shr 8
        val alpha = (255 - luma) * ((argb ushr 24) and 0xFF) / 255
        (alpha shl 24) or 0x00FFFFFF
    },
)

/** The same conversion for the asset store, which stores images rather than coverage. */
fun Raster.toCoverageImage(): RasterImage = RasterImage(
    width = width,
    height = height,
    pixels = IntArray(pixelCount) { i ->
        val alpha = (data[i * channels].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        (alpha shl 24) or 0x00FFFFFF
    },
)

@Suppress("unused")
private val unusedTint = UiColor.White

/** Big enough to tell chalk from spatter at arm's length, small enough to fit six in a row. */
private const val SWATCH = 96
private const val SWATCH_SIZE = 64
private const val SWATCH_REPEATS = 2
