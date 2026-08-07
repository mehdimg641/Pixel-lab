package ir.pixellab.app

import android.content.Context
import ir.pixellab.core.canvas.SnapConfig

/**
 * Settings that outlive a document.
 *
 * Deliberately *not* in the editor state and never on the undo stack. A preference is a statement
 * about how the user likes to work, not about the artwork — undoing back past the moment they
 * turned snapping off and having it come back on would be indefensible.
 *
 * Written through `SharedPreferences` rather than DataStore: this is a dozen scalars read once at
 * launch, and a coroutine-flow API for that is machinery without a purpose. The one thing that
 * matters is that a write is committed before the process can be killed, which `apply` handles.
 */
data class Preferences(
    val snapEnabled: Boolean = true,
    val snapToCanvas: Boolean = true,
    val snapToLayers: Boolean = true,
    val snapToGuides: Boolean = true,
    val snapToSpacing: Boolean = true,
    /** Screen pixels. Eight is about a fingertip's worth of slop at a normal density. */
    val snapTolerance: Float = 8f,
    /** Instagram's square, which is what most covers here start from. */
    val defaultCanvasWidth: Int = 1080,
    val defaultCanvasHeight: Int = 1080,
    /** New text layers start with Persian digits, because the work and the interface both are. */
    val persianDigits: Boolean = true,
    val autoSaveMinutes: Int = 5,
    /**
     * Megapixels a placed image is downsampled to.
     *
     * A 48-megapixel photograph placed at full resolution is nearly two hundred megabytes before
     * anything is drawn. The user asked for a layer, not for the app to be killed.
     */
    val placedMegapixels: Int = 16,
    val hapticFeedback: Boolean = true,
    /**
     * Which palette to draw in.
     *
     * A setting rather than only the system's preference, because the two themes are not the same
     * tool. A light ground is the only one in which the *colour* of a photograph can be judged —
     * the eye adapts to the surround — so someone grading for print wants light at midnight, and
     * someone laying out a cover wants dark at noon. Following the system alone offers neither.
     */
    /**
     * Light by default.
     *
     * It followed the phone before, which sounds like the polite choice and is not one: this
     * application is for laying out covers, its user asked for a light interface twice, and a
     * phone left in dark mode silently overruled them both times. Following the system is still
     * offered — it is simply no longer assumed.
     */
    val theme: ThemeChoice = ThemeChoice.LIGHT,
    /**
     * Which of the four directions to paint in.
     *
     * Separate from [theme], and the separation is the point: *day or night* and *which design* are
     * two questions, and folding them into one list of six would mean a user who prefers Console
     * has to re-pick their direction every time they move between a dark room and a bright one.
     */
    val skin: ThemeSkin = ThemeSkin.EMBER,
) {
    val snap: SnapConfig
        get() = SnapConfig(
            toleranceScreen = snapTolerance,
            snapToCanvas = snapToCanvas,
            snapToLayers = snapToLayers,
            snapToSpacing = snapToSpacing,
            snapToGuides = snapToGuides,
        )

    companion object {
        private const val FILE = "pixellab-settings"

        fun load(context: Context): Preferences {
            val store = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val defaults = Preferences()
            // Every read carries its own default from the data class rather than a literal, so
            // adding a preference cannot leave a second copy of its default to drift out of step.
            return Preferences(
                snapEnabled = store.getBoolean("snapEnabled", defaults.snapEnabled),
                snapToCanvas = store.getBoolean("snapToCanvas", defaults.snapToCanvas),
                snapToLayers = store.getBoolean("snapToLayers", defaults.snapToLayers),
                snapToGuides = store.getBoolean("snapToGuides", defaults.snapToGuides),
                snapToSpacing = store.getBoolean("snapToSpacing", defaults.snapToSpacing),
                snapTolerance = store.getFloat("snapTolerance", defaults.snapTolerance),
                defaultCanvasWidth = store.getInt("canvasWidth", defaults.defaultCanvasWidth),
                defaultCanvasHeight = store.getInt("canvasHeight", defaults.defaultCanvasHeight),
                persianDigits = store.getBoolean("persianDigits", defaults.persianDigits),
                autoSaveMinutes = store.getInt("autoSaveMinutes", defaults.autoSaveMinutes),
                placedMegapixels = store.getInt("placedMegapixels", defaults.placedMegapixels),
                hapticFeedback = store.getBoolean("haptics", defaults.hapticFeedback),
                theme = runCatching {
                    ThemeChoice.valueOf(store.getString("theme", defaults.theme.name) ?: defaults.theme.name)
                }.getOrDefault(defaults.theme),
                // `runCatching` rather than a lookup, for the same reason as the line above: a
                // direction removed in a later version leaves its name behind in the file, and
                // `valueOf` on a name that no longer exists throws on the launch after an update.
                skin = runCatching {
                    ThemeSkin.valueOf(store.getString("skin", defaults.skin.name) ?: defaults.skin.name)
                }.getOrDefault(defaults.skin),
            ).sane()
        }

        fun save(context: Context, preferences: Preferences) {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().apply {
                putString("theme", preferences.theme.name)
                putString("skin", preferences.skin.name)
                putBoolean("snapEnabled", preferences.snapEnabled)
                putBoolean("snapToCanvas", preferences.snapToCanvas)
                putBoolean("snapToLayers", preferences.snapToLayers)
                putBoolean("snapToGuides", preferences.snapToGuides)
                putBoolean("snapToSpacing", preferences.snapToSpacing)
                putFloat("snapTolerance", preferences.snapTolerance)
                putInt("canvasWidth", preferences.defaultCanvasWidth)
                putInt("canvasHeight", preferences.defaultCanvasHeight)
                putBoolean("persianDigits", preferences.persianDigits)
                putInt("autoSaveMinutes", preferences.autoSaveMinutes)
                putInt("placedMegapixels", preferences.placedMegapixels)
                putBoolean("haptics", preferences.hapticFeedback)
                apply()
            }
        }

        const val MIN_CANVAS = 16
        const val MAX_CANVAS = 8192
        const val MIN_TOLERANCE = 0f
        const val MAX_TOLERANCE = 32f
        const val MAX_AUTO_SAVE = 60
        const val MIN_MEGAPIXELS = 1
        const val MAX_MEGAPIXELS = 64
    }
}

/**
 * Clamps a stored value back into range.
 *
 * Preferences are a file on disk that a previous version wrote and a user with a rooted phone can
 * edit. A canvas of zero pixels or a negative tolerance is a crash on the next launch that no
 * amount of reinstalling fixes, because the bad value is read back every time.
 */
fun Preferences.sane(): Preferences = copy(
    snapTolerance = snapTolerance.coerceIn(Preferences.MIN_TOLERANCE, Preferences.MAX_TOLERANCE),
    defaultCanvasWidth = defaultCanvasWidth.coerceIn(Preferences.MIN_CANVAS, Preferences.MAX_CANVAS),
    defaultCanvasHeight = defaultCanvasHeight.coerceIn(Preferences.MIN_CANVAS, Preferences.MAX_CANVAS),
    // Zero is a real setting here and means "never", so only the top is clamped.
    autoSaveMinutes = autoSaveMinutes.coerceIn(0, Preferences.MAX_AUTO_SAVE),
    placedMegapixels = placedMegapixels.coerceIn(Preferences.MIN_MEGAPIXELS, Preferences.MAX_MEGAPIXELS),
)

/** Light, dark, or whatever the phone is doing. */
enum class ThemeChoice(val persianLabel: String) {
    SYSTEM("مثل گوشی"),
    LIGHT("روشن"),
    DARK("تیره"),
}
