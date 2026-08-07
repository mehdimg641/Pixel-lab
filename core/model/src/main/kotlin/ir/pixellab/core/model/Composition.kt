package ir.pixellab.core.model

import kotlinx.serialization.Serializable

/**
 * Layers that move together.
 *
 * Deliberately *not* a group. A group is a place in the tree — it clips, it takes effects, it
 * composites its children as one — while a link is only an agreement about movement. The two get
 * conflated constantly and the difference matters in practice: a caption pinned to a photograph
 * has to move with it, and grouping them would make the photograph's drop shadow fall on the
 * caption. Photoshop keeps them separate for the same reason.
 *
 * Stored as a set of sets. A layer belongs to at most one link, so joining a linked layer to
 * another link merges the two rather than leaving a layer in both — which would make "move
 * everything linked to this" ambiguous.
 */
@Serializable
data class LinkGroups(val groups: List<Set<LayerId>> = emptyList()) {

    /** Everything linked to [id], including [id] itself. Just [id] when it is not linked. */
    fun partners(id: LayerId): Set<LayerId> =
        groups.firstOrNull { id in it } ?: setOf(id)

    fun isLinked(id: LayerId): Boolean = groups.any { id in it }

    /**
     * Links every id together, absorbing any link they already belonged to.
     *
     * Absorbing rather than nesting: two overlapping links would make "what moves with this?"
     * depend on which layer was dragged, and a user cannot see that distinction on screen.
     */
    fun link(ids: Collection<LayerId>): LinkGroups {
        val wanted = ids.toSet()
        if (wanted.size < 2) return this
        val touched = groups.filter { it.any(wanted::contains) }
        val merged = touched.flatten().toSet() + wanted
        return LinkGroups(groups.filterNot { it in touched } + listOf(merged))
    }

    /** Removes [ids] from whatever they were linked to, dissolving any link left with one member. */
    fun unlink(ids: Collection<LayerId>): LinkGroups {
        val wanted = ids.toSet()
        val next = groups.mapNotNull { group ->
            val remaining = group - wanted
            // A link of one is not a link, and leaving it would make an unlinked layer report as
            // linked in the panel.
            if (remaining.size >= 2) remaining else null
        }
        return LinkGroups(next)
    }

    /** Drops a deleted layer from every link. */
    fun forget(id: LayerId): LinkGroups = unlink(listOf(id))
}

/**
 * A named arrangement of which layers are visible and where they sit.
 *
 * Photoshop's Layer Comps, and on a cover this is what makes it possible to hold two versions of a
 * design in one file — the Persian title and the Latin one, the light background and the dark —
 * without duplicating the whole document or losing one when the other is chosen.
 *
 * Records only what it captures. A comp that stored everything would silently undo edits made after
 * it was saved: restore it and every colour, effect and word goes back too, which is a version
 * control system rather than the layout switch this is meant to be.
 */
@Serializable
data class LayerComp(
    val name: String,
    /** Which layers were visible. Absent means the comp does not capture visibility. */
    val visibility: Map<String, Boolean> = emptyMap(),
    /** Where they sat, keyed the same way. Absent means position is not captured. */
    val positions: Map<String, Vec2> = emptyMap(),
) {
    val capturesVisibility: Boolean get() = visibility.isNotEmpty()
    val capturesPositions: Boolean get() = positions.isNotEmpty()

    companion object {

        /**
         * Captures the current state of [layers].
         *
         * Keyed by the layer's own id as a string, because a comp survives in the file across
         * sessions and an index into a list does not survive a reorder — restoring by index after
         * one layer moved would apply every setting to the wrong layer.
         */
        fun capture(
            name: String,
            layers: Sequence<Layer>,
            visibility: Boolean = true,
            positions: Boolean = true,
        ): LayerComp {
            val seen = layers.toList()
            return LayerComp(
                name = name,
                visibility = if (visibility) seen.associate { it.id.value to it.visible } else emptyMap(),
                positions = if (positions) {
                    seen.associate { it.id.value to it.transform.translation }
                } else {
                    emptyMap()
                },
            )
        }
    }
}
