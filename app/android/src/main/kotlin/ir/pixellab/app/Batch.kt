package ir.pixellab.app

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ir.pixellab.core.codec.Format
import ir.pixellab.core.editor.Look
import ir.pixellab.core.editor.withLook
import ir.pixellab.core.model.AssetId
import ir.pixellab.core.model.CanvasSpec
import ir.pixellab.core.model.Document
import ir.pixellab.core.model.DocumentId
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId
import ir.pixellab.engine.android.AssetSource
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Applying one saved grade to a folder of photographs.
 *
 * This is the other half of [Look] and the half that makes it worth having. A preset you apply by
 * hand to thirty pictures has saved you the sliders and left you the thirty openings, thirty
 * exports and thirty file names — which is most of the work. Every reference app that has presets
 * has this beside them.
 *
 * **One photograph is in memory at a time.** That is the whole design constraint and it is not
 * conservatism: thirty pictures from a modern phone camera is around 1.4 GB of decoded bitmap, which
 * is more than the heap an Android application is given, so the obvious implementation — decode
 * everything, render everything, write everything — dies partway through on exactly the batch size
 * where it would have been most useful.
 */
class BatchRun {

    /** What the sheet shows while it works. Null when nothing is running. */
    var progress: Progress? by mutableStateOf(null)
        private set

    data class Progress(val done: Int, val total: Int, val current: String)

    /**
     * What happened, per photograph.
     *
     * Failures are collected rather than thrown, because the alternative is a batch of thirty that
     * stops on the fourth and leaves the user to work out which twenty-six never ran. One unreadable
     * file is a fact about that file.
     */
    data class Outcome(val written: Int, val failed: List<String>) {
        val total: Int get() = written + failed.size
    }

    /**
     * Runs [look] over [uris] and writes each result to the gallery.
     *
     * @param assets what to restore the canvas to when the run ends. The surface is pointed at a
     *   one-image source for each photograph, and leaving it pointed there would blank every asset
     *   in the document the user was actually editing.
     */
    suspend fun run(
        context: Context,
        handle: CanvasHandle,
        look: Look,
        uris: List<Uri>,
        format: Format,
        assets: AssetSource,
    ): Outcome {
        val surface = handle.surface
        val failed = ArrayList<String>()
        var written = 0

        try {
            for ((index, uri) in uris.withIndex()) {
                // Cooperative: the user closing the sheet cancels the scope, and a batch that
                // ignored that would keep writing files after the panel it belongs to has gone.
                coroutineContext.ensureActive()

                val loaded = loadImage(context, uri).getOrNull()
                if (loaded == null) {
                    failed += uri.lastPathSegment ?: "?"
                    continue
                }
                val (image, name) = loaded
                progress = Progress(index, uris.size, name)

                // A source holding this one photograph, rather than putting it in the editor's own
                // store: a batch must not leave thirty pictures attached to the document the user
                // came back to, and this way nothing has to be cleaned up if the run is cancelled.
                val asset = AssetId(BATCH_ASSET)
                surface?.assets = AssetSource { id -> if (id.value == BATCH_ASSET) image else null }

                val document = Document(
                    id = DocumentId("batch-$index"),
                    // The photograph's own size. A batch that exported at some fixed canvas size
                    // would silently resample everything the user owns.
                    canvas = CanvasSpec(image.width, image.height),
                    name = Storage.sanitise(name.substringBeforeLast('.')) + BATCH_SUFFIX,
                    layers = listOf(Layer.Image(id = LayerId("photo"), asset = asset, name = name)),
                ).withLook(look)

                when (exportImage(context, handle, document, format)) {
                    is FileOutcome.Exported -> written++
                    else -> failed += name
                }
                progress = Progress(index + 1, uris.size, name)
            }
        } finally {
            // In `finally` because cancellation is the case that matters: a cancelled run that left
            // the canvas pointed at a batch source would blank every image in the open document.
            surface?.assets = assets
            progress = null
        }

        return Outcome(written, failed)
    }

    private companion object {
        const val BATCH_ASSET = "batch-photo"

        /** So a batch never overwrites the original in the gallery, whatever the user picked. */
        const val BATCH_SUFFIX = "-look"
    }
}
