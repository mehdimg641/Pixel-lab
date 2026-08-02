package ir.pixellab.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import ir.pixellab.core.codec.Project
import ir.pixellab.core.codec.ProjectFile
import java.io.File

/**
 * Where projects and exports live on the device.
 *
 * Two destinations, because they are two different things. A project is the app's own working file
 * and belongs in private storage, where nothing else can half-delete it. An export is the *result*,
 * and it has to appear in the gallery — an export the user has to go looking for in a file manager
 * is an export they will assume failed.
 */
object Storage {

    private const val PROJECTS = "projects"

    /** Album name in the gallery. Exports from one app scattered across DCIM is the alternative. */
    private const val ALBUM = "PixelLab"

    fun projectsDirectory(context: Context): File =
        File(context.filesDir, PROJECTS).apply { mkdirs() }

    /** Newest first, which is the order a "recent projects" list is actually read in. */
    fun listProjects(context: Context): List<File> =
        projectsDirectory(context)
            .listFiles { file -> file.isFile && file.extension == ProjectFile.EXTENSION }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    /**
     * Writes a project, atomically.
     *
     * Through a temporary file and a rename: a save interrupted by the system killing the app would
     * otherwise leave a truncated zip where the user's work used to be, and the truncated file is
     * the one they would open next.
     */
    fun saveProject(context: Context, project: Project, fileName: String): File {
        val target = File(projectsDirectory(context), sanitise(fileName) + "." + ProjectFile.EXTENSION)
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.outputStream().use { ProjectFile.write(project, it) }
        if (target.exists()) target.delete()
        check(temp.renameTo(target)) { "could not replace ${target.name}" }
        return target
    }

    fun loadProject(file: File): Project = file.inputStream().use(ProjectFile::read)

    /**
     * Publishes an exported image where the user will find it.
     *
     * On Android 10 and later this is the gallery through MediaStore, with no permission at all.
     * Below that, writing to a public directory needs `WRITE_EXTERNAL_STORAGE`, and asking for
     * storage permission to save a picture the app just made is a prompt worth avoiding — those
     * devices get the app's own external folder, which every file manager can still reach.
     */
    fun publishExport(context: Context, fileName: String, bytes: ByteArray, mimeType: String): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + ALBUM)
                // Hidden from the gallery until the bytes are all there, so a half-written export
                // never shows up as a corrupt thumbnail.
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("gallery refused the export")
            resolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("could not write the export")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            return uri
        }

        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), ALBUM)
            .apply { mkdirs() }
        val file = File(directory, fileName)
        file.writeBytes(bytes)
        return Uri.fromFile(file)
    }

    /** Strips what no file system accepts, so a Persian document name is kept and a slash is not. */
    fun sanitise(name: String): String =
        name.ifBlank { "untitled" }.replace(Regex("[/\\\\:*?\"<>|\\u0000-\\u001F]"), "_").trim().take(120)
}
