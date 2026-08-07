package ir.pixellab.core.editor

import ir.pixellab.core.model.Color

/**
 * The two colours the whole toolbar reads from — Photoshop's foreground and background.
 *
 * Absent from this project until now, and its absence was quietly shaping the interface. Every sheet
 * that needed a colour carried its own: the brush had one, the fill sheet offered three fixed
 * swatches, the shape tool had another. So picking a colour in one place and reaching for it in
 * another was impossible, and *that* is why those sheets grew fixed swatches — not because fixed
 * swatches are good, but because there was nowhere to put a chosen colour.
 *
 * Half of Photoshop's tool behaviour assumes this pair exists:
 *
 * - The brush paints the foreground; the eraser reveals the background.
 * - A gradient's default is foreground → background.
 * - Quick Mask is *defined* by it: black subtracts from the selection, white adds.
 * - Delete fills with background; Alt-Delete fills with foreground.
 *
 * **Swap and reset are the whole ergonomics.** X and D in Photoshop, and they are pressed constantly
 * — masking is a continuous alternation between adding and removing, which with one colour means
 * opening a picker every few seconds. That is why they are methods here rather than something a call
 * site improvises.
 *
 * Immutable, like everything else in the model: a change produces a new pair, so a caller cannot
 * mutate the palette out from under a composition that is reading it.
 */
data class Palette(
    val foreground: Color = Color.BLACK,
    val background: Color = Color.WHITE,
) {
    /** Photoshop's X. */
    fun swapped() = Palette(background, foreground)

    /** Photoshop's D — back to black on white, which is what every mask starts from. */
    fun reset() = DEFAULT

    fun withForeground(color: Color) = copy(foreground = color)

    fun withBackground(color: Color) = copy(background = color)

    /**
     * Puts [color] into whichever slot is being edited.
     *
     * A single entry point so a picker does not have to know which slot it is attached to; the
     * caller sets [Slot] once and the picker stays generic.
     */
    fun with(slot: Slot, color: Color) = when (slot) {
        Slot.FOREGROUND -> withForeground(color)
        Slot.BACKGROUND -> withBackground(color)
    }

    operator fun get(slot: Slot) = when (slot) {
        Slot.FOREGROUND -> foreground
        Slot.BACKGROUND -> background
    }

    enum class Slot(val persianLabel: String) {
        FOREGROUND("پیش‌زمینه"),
        BACKGROUND("پس‌زمینه"),
    }

    companion object {
        /**
         * Black on white.
         *
         * Not an arbitrary default: a layer mask is black-and-white by definition, and so is Quick
         * Mask, so starting anywhere else means the first thing a user does with either is fix the
         * colour. Photoshop has shipped this default for thirty years for that reason.
         */
        val DEFAULT = Palette(Color.BLACK, Color.WHITE)
    }
}
