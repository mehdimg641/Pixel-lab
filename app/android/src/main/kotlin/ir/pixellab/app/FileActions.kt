package ir.pixellab.app

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import ir.pixellab.core.codec.Codecs
import ir.pixellab.core.codec.PdfWriter
import ir.pixellab.core.model.with
import ir.pixellab.core.codec.Format
import ir.pixellab.core.codec.ImageEncoder
import ir.pixellab.core.codec.Project
import ir.pixellab.core.model.Document
import ir.pixellab.engine.android.CanvasSurface
import ir.pixellab.engine.android.ExportResult
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A live reference to the canvas view.
 *
 * The export runs on the GL thread, which only the view owns, so something has to bridge from a
 * button press to that thread. Held by the screen rather than by the view model on purpose: a view
 * model that keeps a `View` keeps the whole activity alive with it.
 */
class CanvasHandle {
    @Volatile
    var surface: CanvasSurface? = null
}

/** What an export or a save produced, in a form the interface can put in front of the user. */
sealed interface FileOutcome {
    data class Saved(val where: String) : FileOutcome
    data class Exported(val where: String, val width: Int, val height: Int) : FileOutcome
    data class Refused(val message: String, val advice: List<String> = emptyList()) : FileOutcome
}

/**
 * Writes the project to private storage.
 *
 * Assets travel with it — a mask is a reference, and a project that lost its masks would open with
 * every layer unmasked and no way to tell. Fonts do not: the document references them by identity,
 * and one opened on a device without a face falls back with a warning rather than failing.
 * Bundling fonts belongs with the sharing flow, not with an ordinary save, which has to stay fast
 * enough that the user does it without thinking.
 */
suspend fun saveProject(context: Context, project: Project): FileOutcome = withContext(Dispatchers.IO) {
    runCatching {
        val file = Storage.saveProject(context, project, project.document.name)
        FileOutcome.Saved(file.name)
    }.getOrElse { FileOutcome.Refused(it.message ?: "ذخیره نشد") }
}

/**
 * Renders the document and publishes it to the gallery.
 *
 * The budget passed to the renderer is the *remaining* heap rather than the maximum: a 4K export of
 * a heavy style is hundreds of megabytes of texture, and finding that out halfway through means the
 * user waited and then lost the render. Refusing up front with a suggestion costs them a second.
 */
suspend fun exportImage(
    context: Context,
    handle: CanvasHandle,
    document: Document,
    format: Format,
    scale: Float = 1f,
    quality: Int = ImageEncoder.DEFAULT_QUALITY,
): FileOutcome {
    val surface = handle.surface ?: return FileOutcome.Refused("بوم هنوز آماده نیست")
    val result = suspendCoroutine { continuation ->
        surface.export(document, format, scale, availableBytes(context), quality) { continuation.resume(it) }
    }
    return when (result) {
        is ExportResult.Success -> withContext(Dispatchers.IO) {
            runCatching {
                val name = Storage.sanitise(document.name) + "." + (format.extensions.firstOrNull() ?: "png")
                Storage.publishExport(context, name, result.bytes, format.mime)
                FileOutcome.Exported(name, result.width, result.height)
            }.getOrElse { FileOutcome.Refused(it.message ?: "خروجی نوشته نشد") }
        }
        is ExportResult.TooLarge -> FileOutcome.Refused(
            "این خروجی به ${result.requiredBytes / MEGABYTE} مگابایت حافظه نیاز دارد و " +
                "${result.availableBytes / MEGABYTE} مگابایت آزاد است",
            result.advice,
        )
        is ExportResult.Failed -> FileOutcome.Refused(result.reason)
    }
}

/**
 * Renders the document and writes it as a single-page PDF.
 *
 * A separate path from [exportImage] because PDF is not a raster format the encoder registry can
 * take: the page has a *physical* size, taken from the canvas's DPI, and that is the entire reason
 * to use it over a PNG. A print shop receiving a PDF is told how big the design is; one receiving a
 * PNG has to be told separately and often is not.
 */
suspend fun exportPdf(
    context: Context,
    handle: CanvasHandle,
    document: Document,
    scale: Float = 1f,
): FileOutcome {
    val surface = handle.surface ?: return FileOutcome.Refused("بوم هنوز آماده نیست")
    val result = suspendCoroutine<ExportResult> { continuation ->
        // Rendered through the ordinary PNG path first: the pixels are the same either way, and a
        // second render path for one format is a second place for the composite to disagree.
        surface.export(document, Format.PNG, scale, availableBytes(context)) { continuation.resume(it) }
    }
    return when (result) {
        is ExportResult.Success -> withContext(Dispatchers.IO) {
            runCatching {
                val decoded = Codecs.decode(result.bytes)
                // Scaled exports carry a proportionally higher resolution, so the page keeps its
                // real size rather than growing — which is what the user means by "export at ×2".
                val dpi = (document.canvas.dpi * scale).toInt().coerceAtLeast(1)
                val bytes = PdfWriter.write(decoded, dpi, document.name)
                val name = Storage.sanitise(document.name) + ".pdf"
                Storage.publishExport(context, name, bytes, Format.PDF.mime)
                FileOutcome.Exported(name, decoded.width, decoded.height)
            }.getOrElse { FileOutcome.Refused(it.message ?: "PDF نوشته نشد") }
        }
        is ExportResult.TooLarge -> FileOutcome.Refused(
            "این خروجی به ${result.requiredBytes / MEGABYTE} مگابایت حافظه نیاز دارد و " +
                "${result.availableBytes / MEGABYTE} مگابایت آزاد است",
            result.advice,
        )
        is ExportResult.Failed -> FileOutcome.Refused(result.reason)
    }
}

/**
 * What is actually free, not what the heap could grow to.
 *
 * `maxMemory` is the ceiling and the app is already using part of it; budgeting against the ceiling
 * is how an export gets accepted and then dies partway through.
 */
private fun availableBytes(context: Context): Long {
    val runtime = Runtime.getRuntime()
    val heapFree = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
    // Textures are native allocations rather than heap ones, so the system's own view of free
    // memory is the binding constraint on a large export; the smaller of the two is the honest one.
    val system = ActivityManager.MemoryInfo().also { info ->
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(info)
    }.availMem
    return minOf(heapFree, system).coerceAtLeast(0L)
}

private const val MEGABYTE = 1024L * 1024L

/**
 * Reads an image the user picked, as pixels and a name.
 *
 * Through the project's own decoders rather than through `BitmapFactory`, so a file that this app
 * can open in its file list is a file it can also place as a layer — the two would otherwise
 * disagree, and the disagreement would show up as "this app supports TGA but will not let me use
 * one".
 *
 * Downsampled if it is enormous. A 48-megapixel photograph placed at full resolution is nearly two
 * hundred megabytes of pixels before anything is drawn, and the user asked for a layer, not for the
 * app to be killed.
 */
suspend fun loadImage(
    context: Context,
    uri: android.net.Uri,
): Result<Pair<ir.pixellab.core.codec.RasterImage, String>> = withContext(Dispatchers.IO) {
    runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw java.io.IOException("فایل باز نشد")
        val decoded = Codecs.decode(bytes)
        val name = displayName(context, uri) ?: "تصویر"
        downsampled(decoded) to name
    }
}

/**
 * Reads a Photoshop `.acv` curve preset the user picked.
 *
 * Worth supporting out of all proportion to the format's size: every colour-grading pack sold or
 * given away for the last twenty years ships as a folder of these, so reading them means a user's
 * existing looks work here on the first day instead of being redrawn by hand.
 */
suspend fun loadCurvePreset(
    context: Context,
    uri: android.net.Uri,
): Result<Pair<ir.pixellab.core.model.Adjustment.Curves, String>> = withContext(Dispatchers.IO) {
    runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw java.io.IOException("فایل باز نشد")
        val curves = ir.pixellab.core.codec.CurvePreset.read(bytes)
            ?: throw java.io.IOException("این فایل یک پریست منحنی معتبر نیست")
        // The file's own name, because a grading pack's meaning is entirely in its file names and a
        // layer called "Curves" tells the user nothing about which of the forty they just applied.
        curves to (displayName(context, uri)?.substringBeforeLast('.') ?: "منحنی")
    }
}

/**
 * Reads a `.cube` colour lookup table and hands back the strip plus the file's own name.
 *
 * The name matters as much as the pixels here, for the same reason it does for a curve preset: a
 * grading pack's meaning is entirely in its file names, and a layer called "Color Lookup" tells the
 * user nothing about which of the forty they just applied.
 */
suspend fun loadColorLookup(
    context: Context,
    uri: android.net.Uri,
): Result<Pair<ir.pixellab.core.codec.RasterImage, String>> = withContext(Dispatchers.IO) {
    runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw java.io.IOException("فایل باز نشد")
        val strip = ir.pixellab.core.codec.CubeLut.read(bytes)
            ?: throw java.io.IOException("این فایل یک جدول رنگ معتبر (cube.) نیست")
        strip to (displayName(context, uri)?.substringBeforeLast('.') ?: "جدول رنگ")
    }
}

/**
 * Halves the image until it fits, rather than resampling to an exact size.
 *
 * Whole-pixel steps average a fixed block, which is both fast and free of the ringing a general
 * resampler leaves on a photograph. The layer's own scale then does the rest, on the GPU, at
 * whatever zoom the user is actually looking at.
 */
private fun downsampled(image: ir.pixellab.core.codec.RasterImage): ir.pixellab.core.codec.RasterImage {
    var step = 1
    while ((image.width / step).toLong() * (image.height / step) > MAX_PLACED_PIXELS) step *= 2
    if (step == 1) return image

    val width = (image.width / step).coerceAtLeast(1)
    val height = (image.height / step).coerceAtLeast(1)
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            var count = 0
            for (dy in 0 until step) {
                val sy = y * step + dy
                if (sy >= image.height) break
                for (dx in 0 until step) {
                    val sx = x * step + dx
                    if (sx >= image.width) break
                    val p = image[sx, sy]
                    // Weighted by alpha, so averaging a cut-out's edge does not drag transparent
                    // black into the colours that survive.
                    val pa = (p ushr 24) and 0xFF
                    a += pa
                    r += ((p shr 16) and 0xFF) * pa
                    g += ((p shr 8) and 0xFF) * pa
                    b += (p and 0xFF) * pa
                    count++
                }
            }
            pixels[y * width + x] = if (count == 0 || a == 0) {
                0
            } else {
                ((a / count) shl 24) or ((r / a) shl 16) or ((g / a) shl 8) or (b / a)
            }
        }
    }
    return ir.pixellab.core.codec.RasterImage(width, height, pixels)
}

private fun displayName(context: Context, uri: android.net.Uri): String? =
    context.contentResolver
        .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        ?.substringBeforeLast('.')

/** About twelve megapixels: more than any phone screen resolves, and a quarter of a gigabyte less. */
private const val MAX_PLACED_PIXELS = 12L * 1024 * 1024

/** Reads a saved project back. */
suspend fun openProject(file: java.io.File): Result<Project> = withContext(Dispatchers.IO) {
    runCatching { Storage.loadProject(file) }
}

/**
 * The open dialog.
 *
 * Plain and short on purpose. This is the list of the user's own work, so it is ordered by when
 * they last touched a file rather than by name — the one they want is almost always the last one
 * they had open.
 */
@Composable
fun OpenDialog(projects: List<java.io.File>, onDismiss: () -> Unit, onOpen: (java.io.File) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف", color = Ink.TextMuted) } },
        containerColor = Ink.ChromeRaised,
        title = { Text("باز کردن پروژه", color = Ink.Text) },
        text = {
            if (projects.isEmpty()) {
                Text("هنوز پروژه‌ای ذخیره نشده", color = Ink.TextMuted)
            } else {
                Column {
                    for (file in projects) {
                        Text(
                            file.nameWithoutExtension,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Ink.Text,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onOpen(file) }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        },
    )
}

/**
 * Imports a PSD.
 *
 * The reader recovers the file's structure and the importer decides what it means here; both report
 * what they could not carry rather than dropping it silently. An import that quietly loses a drop
 * shadow is one the user only discovers after exporting.
 */
suspend fun importPsd(bytes: ByteArray, name: String): Result<ir.pixellab.core.codec.ImportedPsd> =
    withContext(Dispatchers.Default) {
        runCatching { ir.pixellab.core.codec.PsdImport.convert(ir.pixellab.core.codec.PsdReader.read(bytes), name) }
    }

/**
 * Writes the document as a layered PSD.
 *
 * Each layer is rendered on its own and written with its own bounds, blend mode and opacity, so the
 * file reopens in Photoshop as layers rather than as a flattened picture. That reversibility is the
 * whole reason to write PSD at all — a flattened export is a one-way door.
 */
suspend fun exportPsd(
    context: Context,
    handle: CanvasHandle,
    document: ir.pixellab.core.model.Document,
): FileOutcome {
    val surface = handle.surface ?: return FileOutcome.Refused("بوم هنوز آماده نیست")

    // Every layer rendered alone, by hiding the rest. Rendering the whole document once and slicing
    // it would give each layer whatever was beneath it baked in.
    val sources = ArrayList<ir.pixellab.core.codec.PsdLayerSource>()
    for (layer in document.layers) {
        if (!layer.visible) continue
        val alone = document.copy(layers = document.layers.map { it.with(visible = it.id == layer.id) })
        val rendered = suspendCoroutine { continuation ->
            surface.export(alone, Format.PNG, 1f) { continuation.resume(it) }
        }
        val success = rendered as? ExportResult.Success ?: continue
        val decoded = runCatching { Codecs.decode(success.bytes) }.getOrNull() ?: continue
        sources += ir.pixellab.core.codec.PsdLayerSource(
            name = layer.name,
            left = 0,
            top = 0,
            image = decoded,
            opacity = layer.opacity,
            blendMode = layer.blendMode,
            visible = layer.visible,
            clipped = layer.clipped,
        )
    }

    val composite = suspendCoroutine { continuation ->
        surface.export(document, Format.PNG, 1f) { continuation.resume(it) }
    }
    val flat = (composite as? ExportResult.Success)?.let { runCatching { Codecs.decode(it.bytes) }.getOrNull() }
        ?: return FileOutcome.Refused("خروجی تخت ساخته نشد")

    return withContext(Dispatchers.IO) {
        runCatching {
            val bytes = ir.pixellab.core.codec.PsdWriter.write(document, sources, flat)
            val file = java.io.File(
                Storage.projectsDirectory(context),
                Storage.sanitise(document.name) + ".psd",
            )
            file.writeBytes(bytes)
            FileOutcome.Exported(file.name, flat.width, flat.height)
        }.getOrElse { FileOutcome.Refused(it.message ?: "PSD نوشته نشد") }
    }
}

/**
 * The export sheet.
 *
 * ### What it replaces
 *
 * A dialog listing every writable format with three scale chips under each — twenty-one buttons,
 * each of which exported immediately on the first tap. There was no way to see what you were about
 * to get, no way to change your mind, and **no quality control at all**: JPEG was written at a
 * constant, so the only way to make a smaller file was to make a smaller picture. Photoshop has had
 * that slider since 1990 and the reason is not subtle — a cover for print and the same cover for a
 * chat app are the same pixels and very different files.
 *
 * Now it is one decision at a time — format, then size, then quality — with the two numbers that
 * actually decide it shown before anything is written: the pixel dimensions the export will have,
 * and roughly what it will weigh.
 *
 * @param onExport format, scale multiplier, and JPEG/WebP quality. Quality is passed for every
 *   format; the lossless encoders ignore it, which is cheaper than making the caller know which.
 */
@Composable
fun ExportSheet(
    canvasWidth: Int,
    canvasHeight: Int,
    onDismiss: () -> Unit,
    onExport: (Format, Float, Int) -> Unit,
) {
    val formats = remember {
        // Ordered by what a cover design is actually exported as, not alphabetically.
        val preferred = listOf(Format.PNG, Format.JPEG, Format.WEBP, Format.TIFF, Format.BMP, Format.TGA, Format.ICO)
        // PDF is appended rather than filtered through the registry: it is not a raster encoder and
        // never will be, because its page carries a physical size that a pixel buffer cannot.
        preferred.filter { it in Codecs.writable } + (Codecs.writable - preferred.toSet()) + Format.PDF
    }
    var format by remember { mutableStateOf(formats.firstOrNull() ?: Format.PNG) }
    var scale by remember { mutableFloatStateOf(1f) }
    var quality by remember { mutableIntStateOf(ImageEncoder.DEFAULT_QUALITY) }

    val width = kotlin.math.ceil(canvasWidth * scale).toInt().coerceAtLeast(1)
    val height = kotlin.math.ceil(canvasHeight * scale).toInt().coerceAtLeast(1)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف", color = Ink.TextMuted) } },
        containerColor = Ink.Chrome,
        title = { Text("خروجی گرفتن", color = Ink.Text) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                SheetSection("قالب")
                SheetChips {
                    for (option in formats) {
                        SheetChip(option.label, chosen = option == format) { format = option }
                    }
                }

                SheetSection("اندازه")
                SheetChips {
                    for (option in SCALES) {
                        ScaleChip(option, chosen = option == scale) { scale = option }
                    }
                }

                // Only where it does something. A quality slider above a PNG is a control that
                // moves and changes nothing, which teaches the user that the controls are decorative.
                if (format.isLossy) {
                    SheetSection("کیفیت")
                    SheetSlider(
                        "کیفیت",
                        quality.toFloat(),
                        ImageEncoder.MIN_QUALITY.toFloat()..ImageEncoder.MAX_QUALITY.toFloat(),
                        onChange = { value, _ -> quality = value.toInt() },
                    )
                    SheetHint("زیر ۸۰ دورِ حروف موج می‌افتد — طرح گرافیکی لبهٔ تیز دارد و JPEG آن را دوست ندارد")
                }

                SheetSection("نتیجه")
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = Space.gutter),
                    horizontalArrangement = Arrangement.spacedBy(Space.small),
                ) {
                    Readout("ابعاد", "${Digits.technical(width)} × ${Digits.technical(height)}", Modifier.weight(1f))
                    Readout("حجم تقریبی", approximateSize(width, height, format, quality), Modifier.weight(1f))
                }

                SheetAction("خروجی گرفتن") { onExport(format, scale, quality) }
            }
        },
    )
}

/** One labelled number, as a plate. The pair of them is what makes the sheet worth opening. */
@Composable
private fun Readout(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(Corners.card)
            .background(Ink.ChromeRaised)
            .padding(horizontal = Space.medium, vertical = Space.small),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Ink.TextMuted)
        Text(value, style = NumericStyle, color = Ink.Text, modifier = Modifier.padding(top = 2.dp))
    }
}

/**
 * Roughly what the file will weigh.
 *
 * Estimated rather than measured, and the sheet says «تقریبی» for that reason: measuring means
 * rendering and encoding the whole export, which is the several-second job the user is deciding
 * whether to start. The point is not to be exact — it is to make the difference between ×۱ and ×۴,
 * or between quality 60 and 95, visible *before* it costs a minute and a full storage warning.
 *
 * The lossy figure comes from bits-per-pixel at quality, which is the standard rule of thumb for
 * JPEG on photographic content and errs high on flat colour — an over-estimate is the safe
 * direction when the failure it guards against is running out of space.
 */
private fun approximateSize(width: Int, height: Int, format: Format, quality: Int): String {
    val pixels = width.toLong() * height.toLong()
    val bytes = when {
        format.isLossy -> {
            // ~0.15 bpp at quality 20 rising to ~2.4 bpp at 100, which tracks measured JPEG well
            // enough for a chip that says "about".
            val bitsPerPixel = 0.1 + (quality / 100.0).let { it * it * 2.6 }
            (pixels * bitsPerPixel / 8).toLong()
        }
        // PNG on flat design artwork compresses hard; on a photograph it barely does. Halfway.
        format == Format.PNG -> (pixels * 1.8).toLong()
        else -> pixels * 4
    }
    return when {
        bytes >= MEGABYTE -> "${Digits.technical((bytes / MEGABYTE).toInt())}٫" +
            "${Digits.technical(((bytes % MEGABYTE) * 10 / MEGABYTE).toInt())} مگابایت"
        else -> "${Digits.technical((bytes / 1024).toInt().coerceAtLeast(1))} کیلوبایت"
    }
}

/** Whether a quality setting means anything for this format. */
private val Format.isLossy: Boolean
    get() = this == Format.JPEG || this == Format.WEBP || this == Format.HEIF

/**
 * Delegates to [SheetChip].
 *
 * It used to be a `Text` with a click and eight points of padding — 36.5dp tall, under the touch
 * minimum, and one of four private chips across the sheets that had each drifted into the same
 * defect independently. `SheetChip`'s own documentation warned about exactly this: "two sheets with
 * their own private chip drift apart within a week". They did.
 */
@Composable
private fun ScaleChip(scale: Float, chosen: Boolean, onClick: () -> Unit) =
    SheetChip(
        when (scale) {
            1f -> "×۱"
            2f -> "×۲"
            else -> "×۴"
        },
        chosen = chosen,
        onClick = onClick,
    )

private val SCALES = listOf(1f, 2f, 4f)

/** A transient message strip. Anchored below the top bar, out of the thumb's working area. */
@Composable
fun OutcomeBanner(outcome: FileOutcome, modifier: Modifier = Modifier, onDismiss: () -> Unit) {
    val (message, detail) = when (outcome) {
        is FileOutcome.Saved -> "ذخیره شد: ${outcome.where}" to emptyList()
        is FileOutcome.Exported ->
            "خروجی گرفته شد: ${outcome.where}" to listOf("${outcome.width}×${outcome.height} در گالری")
        is FileOutcome.Refused -> outcome.message to outcome.advice
    }
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (outcome is FileOutcome.Refused) Ink.Danger.copy(alpha = 0.18f) else Ink.ChromeRaised)
            .clickable(onClick = onDismiss)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Ink.Text)
        for (line in detail) {
            Text(
                "• $line",
                style = MaterialTheme.typography.labelSmall,
                color = Ink.TextMuted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                "باشه",
                style = MaterialTheme.typography.labelSmall,
                color = Ink.Accent,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
