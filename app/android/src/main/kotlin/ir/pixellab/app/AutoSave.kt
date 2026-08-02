package ir.pixellab.app

import android.content.Context
import ir.pixellab.core.codec.ProjectFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Keeps a recovery copy of the work in progress.
 *
 * Separate from the user's own saves and deliberately so. A save is a decision — it names the file
 * and it is the version the user means to keep. An auto-save is insurance against the process being
 * killed, which on Android happens without warning whenever the system wants the memory. Writing
 * over the user's file on a timer would mean an accidental edit becomes permanent while they are
 * looking away, which is worse than the crash it was protecting them from.
 *
 * Written through a temporary file and then renamed. The failure this prevents is the one that
 * matters here: if the process dies *during* the write, a direct write leaves a truncated file
 * where the recovery copy should be — so the crash destroys the very thing meant to survive it.
 */
class AutoSave(
    private val context: Context,
    private val scope: CoroutineScope,
    private val snapshot: () -> ir.pixellab.core.codec.Project,
) {
    private var job: Job? = null

    /** Set from the editor whenever the document changes, so an idle session writes nothing. */
    @Volatile
    var dirty: Boolean = false

    /** @param minutes zero turns it off, which is a real choice and not a broken value. */
    fun start(minutes: Int) {
        stop()
        if (minutes <= 0) return
        job = scope.launch {
            while (isActive) {
                delay(minutes * MILLIS_PER_MINUTE)
                if (dirty) writeNow()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Writes the recovery copy now.
     *
     * Called on the timer and again when the editor is backgrounded — that second call is the one
     * that catches most real losses, because a process is usually killed while it is not in front
     * of the user rather than while it is.
     */
    suspend fun writeNow(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val project = snapshot()
            val target = recoveryFile(context)
            val temporary = File(target.parentFile, "${target.name}.part")
            temporary.outputStream().use { ProjectFile.write(project, it) }
            // Rename over the old copy. On the same filesystem this is atomic, so a reader either
            // sees the whole previous copy or the whole new one, and never half of either.
            if (target.exists()) target.delete()
            val moved = temporary.renameTo(target)
            if (!moved) temporary.delete()
            dirty = false
            moved
        }.getOrElse { false }
    }

    companion object {
        private const val MILLIS_PER_MINUTE = 60_000L
        private const val NAME = "recovery.pxl"

        fun recoveryFile(context: Context): File = File(Storage.projectsDirectory(context), NAME)

        /**
         * Whether there is work to offer back.
         *
         * Offered rather than restored silently. A user who deliberately started something new and
         * found last week's document loaded over it would have no way to understand what happened,
         * and the recovery would read as the app losing their work rather than saving it.
         */
        fun hasRecovery(context: Context): Boolean {
            val file = recoveryFile(context)
            return file.exists() && file.length() > 0
        }

        fun clearRecovery(context: Context) {
            recoveryFile(context).delete()
        }
    }
}
