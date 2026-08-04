package ir.pixellab.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ir.pixellab.core.editor.Look
import ir.pixellab.core.model.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The user's own saved Looks.
 *
 * One file each rather than one file for all of them, for the reason every store in this app is
 * written that way: a single index is a single thing to corrupt, and a Look that fails to parse
 * should cost that Look rather than the whole shelf. It also makes a Look something the user can
 * copy off the device and send to somebody, which a row in a shared index is not.
 *
 * Written atomically through a temporary file, like a project: a save interrupted by the system
 * killing the app would otherwise leave a truncated file where a working preset used to be, and the
 * truncated one is what loads next.
 */
class LookStore {

    var looks: List<Look> by mutableStateOf(emptyList())
        private set

    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        val found = directory(context)
            .listFiles { file -> file.isFile && file.extension == EXTENSION }
            ?.sortedBy { it.name }
            // One unreadable preset must not cost the rest — the same rule the asset store follows,
            // and the reason a Look is a file of its own.
            ?.mapNotNull { file -> runCatching { read(file) }.getOrNull() }
            ?: emptyList()
        withContext(Dispatchers.Main) { looks = found }
    }

    /**
     * Saves a Look under a handle that is free.
     *
     * The handle rather than the name is what has to be unique, because it is what the file is
     * called and what an applied layer is namespaced by. Persian names slug to the same string —
     * `Look.slug` says so plainly rather than guessing a transliteration — so uniqueness is settled
     * here, by counting, where the existing handles are actually known.
     */
    suspend fun save(context: Context, document: Document, name: String): Look {
        val taken = looks.mapTo(HashSet()) { it.id }
        val base = Look.slug(name)
        var handle = base
        var n = 2
        while (handle in taken) handle = "$base-${n++}"

        val look = Look.from(document, name, handle)
        withContext(Dispatchers.IO) {
            val target = File(directory(context), "$handle.$EXTENSION")
            val temp = File(target.parentFile, target.name + ".tmp")
            temp.writeText(Document.json.encodeToString(Look.serializer(), look))
            if (target.exists()) target.delete()
            check(temp.renameTo(target)) { "could not replace ${target.name}" }
        }
        withContext(Dispatchers.Main) { looks = looks + look }
        return look
    }

    suspend fun delete(context: Context, look: Look) {
        withContext(Dispatchers.IO) { File(directory(context), "${look.id}.$EXTENSION").delete() }
        withContext(Dispatchers.Main) { looks = looks.filterNot { it.id == look.id } }
    }

    private fun read(file: File): Look =
        Document.json.decodeFromString(Look.serializer(), file.readText())

    private fun directory(context: Context) = File(context.filesDir, LOOKS).apply { mkdirs() }

    private companion object {
        const val LOOKS = "looks"
        const val EXTENSION = "look"
    }
}
