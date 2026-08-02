package ir.pixellab.app

import android.content.Context
import java.io.File

/**
 * Where the user's own files live, and what the app does with each kind.
 *
 * Everything here is optional. The app works with none of it — that is the whole design — and each
 * folder switches on a capability when something is put in it. The alternative, bundling assets in
 * the APK, was ruled out twice over: the segmentation model alone is bigger than the entire rest of
 * the application, and a LUT pack or a template set is a matter of taste that belongs to the user
 * rather than to the binary.
 *
 * The location is `Android/data/ir.pixellab.app/files/`, which matters for one reason: it is visible
 * from any file manager and needs **no permission at all**. A folder of Persian fonts or a model
 * file can be copied in over USB or from a download, and the app picks it up on the next scan
 * without ever asking for storage access — which is the permission users are rightest to refuse.
 */
enum class AssetKind(
    val directory: String,
    /** What the user sees in settings. */
    val label: String,
    /** Extensions this folder is scanned for, lower case and without the dot. */
    val extensions: Set<String>,
    val purpose: String,
) {
    FONTS(
        directory = "fonts",
        label = "فونت",
        extensions = setOf("ttf", "otf", "ttc", "woff", "woff2"),
        purpose = "قلم‌های فارسی و لاتین شما — گروه‌بندی و جست‌وجو خودکار است",
    ),

    /**
     * The segmentation network.
     *
     * Nothing breaks without it: `core:ai` falls back to the classical cut-out, which is what the
     * app uses today. With a model present the *subject decision* improves; the edge is refined by
     * the same code either way, which is the part that decides whether hair looks like hair.
     */
    MODELS(
        directory = "models",
        label = "مدل هوش مصنوعی",
        extensions = setOf("tflite", "lite", "onnx"),
        purpose = "جداسازی سوژه (BiRefNet) و مش چهره — بدون این هم کار می‌کند، با این دقیق‌تر",
    ),

    LUTS(
        directory = "luts",
        label = "جدول رنگ",
        extensions = setOf("cube", "png", "3dl"),
        purpose = "پریست‌های رنگ سینمایی — هر فایلی که برای Premiere یا DaVinci خریده‌اید",
    ),

    /**
     * Brush tips as images.
     *
     * The generated tips cover round, square, chalk, spatter and bristle. What a scanned tip adds is
     * a *real* texture — an actual dry brush on paper — which cannot be produced procedurally in a
     * way anyone would mistake for the original.
     */
    BRUSHES(
        directory = "brushes",
        label = "نوک قلم‌مو",
        extensions = setOf("png", "abr"),
        purpose = "نوک‌های بافت‌دار — خاکستری خوانده می‌شود و رنگش نادیده گرفته می‌شود",
    ),

    /**
     * Environment maps for the 3D text.
     *
     * The computed studio is what makes chrome read as chrome today. A real captured environment
     * adds what an analytic one cannot: recognisable *things* in the reflection, which is most of
     * what separates a rendered letter from a photographed one.
     */
    ENVIRONMENTS(
        directory = "hdr",
        label = "نقشهٔ محیطی",
        extensions = setOf("hdr", "exr", "jpg", "png"),
        purpose = "بازتاب متن سه‌بعدی — محیط محاسباتی جایش را می‌گیرد ولی این واقعی‌تر است",
    ),

    TEMPLATES(
        directory = "templates",
        label = "قالب",
        extensions = setOf("pxl", "psd"),
        purpose = "طرح‌های آماده — هر PSD یا پروژه‌ای که به‌عنوان نقطهٔ شروع می‌خواهید",
    ),

    PATTERNS(
        directory = "patterns",
        label = "الگو",
        extensions = setOf("png", "jpg", "webp"),
        purpose = "الگوهای بافت — الگوهای ساخته‌شده هست، این‌ها اضافه می‌شوند",
    ),

    PROFILES(
        directory = "icc",
        label = "پروفایل رنگ",
        extensions = setOf("icc", "icm"),
        purpose = "پروفایل ICC برای CMYK و پیش‌نمایش چاپ",
    ),
    ;

    /**
     * The folder, created if it is not there.
     *
     * Created eagerly and on every launch, so a user opening the file manager sees the folders
     * waiting rather than having to know the names and make them. An empty folder with the right
     * name is documentation that cannot get out of date.
     */
    fun directoryIn(context: Context): File? =
        context.getExternalFilesDir(null)?.let { File(it, directory).apply { mkdirs() } }
}

/** What was found, per kind. */
data class AssetInventory(val kind: AssetKind, val path: String, val files: List<File>) {
    val count: Int get() = files.size
    val isEmpty: Boolean get() = files.isEmpty()

    /** Total size, for the one folder where it matters — a model is bigger than the whole app. */
    val bytes: Long get() = files.sumOf { it.length() }
}

object AssetLibrary {

    /**
     * Makes every folder and reports what is in them.
     *
     * Called at launch and again whenever settings is opened, because the whole point is that a user
     * copies a file in from *outside* the app — so the app has no event to react to and has to look.
     */
    fun scan(context: Context): List<AssetInventory> = AssetKind.entries.map { kind ->
        val directory = kind.directoryIn(context)
        val files = directory
            ?.walkTopDown()
            // A few levels, not unbounded: a user drops in a folder as it was downloaded, nested a
            // couple deep. Following it forever would mean a stray symlink walks the whole card.
            ?.maxDepth(MAX_DEPTH)
            ?.filter { it.isFile && it.extension.lowercase() in kind.extensions }
            ?.sortedBy { it.name }
            ?.toList()
            .orEmpty()
        AssetInventory(kind, directory?.absolutePath ?: "—", files)
    }

    /**
     * The path to show the user, shortened to the part they have to navigate.
     *
     * The full path begins `/storage/emulated/0/`, which is the same on every device and means
     * nothing to anyone. What a person needs is the part that appears in their file manager.
     */
    fun readablePath(absolute: String): String {
        val marker = "/Android/data/"
        val at = absolute.indexOf(marker)
        return if (at >= 0) "Android/data/" + absolute.substring(at + marker.length) else absolute
    }

    private const val MAX_DEPTH = 4
}
