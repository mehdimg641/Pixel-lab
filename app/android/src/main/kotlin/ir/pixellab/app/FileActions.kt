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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import ir.pixellab.core.codec.Codecs
import ir.pixellab.core.model.with
import ir.pixellab.core.codec.Format
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
): FileOutcome {
    val surface = handle.surface ?: return FileOutcome.Refused("بوم هنوز آماده نیست")
    val result = suspendCoroutine { continuation ->
        surface.export(document, format, scale, availableBytes(context)) { continuation.resume(it) }
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
 * The export dialog.
 *
 * Offers exactly the formats this build can write on this device, taken from the registry rather
 * than from a fixed list. A menu that offers HEIF on a phone that cannot encode it is worse than
 * one that offers less.
 */
@Composable
fun ExportDialog(onDismiss: () -> Unit, onExport: (Format, Float) -> Unit) {
    val formats = remember {
        // Ordered by what a cover design is actually exported as, not alphabetically.
        val preferred = listOf(Format.PNG, Format.JPEG, Format.WEBP, Format.TIFF, Format.BMP, Format.TGA, Format.ICO)
        preferred.filter { it in Codecs.writable } + (Codecs.writable - preferred.toSet())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف", color = Ink.TextMuted) } },
        containerColor = Ink.ChromeRaised,
        title = { Text("خروجی گرفتن", color = Ink.Text) },
        text = {
            Column {
                for (format in formats) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(
                            format.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Ink.Text,
                            modifier = Modifier.padding(bottom = 2.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            // Multipliers rather than pixel sizes: the canvas can be any size, and
                            // "×2" is the thing the user is actually deciding.
                            for (scale in SCALES) {
                                ScaleChip(scale) { onExport(format, scale) }
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun ScaleChip(scale: Float, onClick: () -> Unit) {
    Text(
        if (scale == 1f) "×۱" else if (scale == 2f) "×۲" else "×۴",
        style = MaterialTheme.typography.labelLarge,
        color = Ink.Accent,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Ink.Chrome)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

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
