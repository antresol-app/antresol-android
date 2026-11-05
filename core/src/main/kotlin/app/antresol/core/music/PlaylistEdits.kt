package app.antresol.core.music

import app.antresol.core.model.PlaylistPatch

/**
 * Builds `PATCH /api/music/playlists/{id}` bodies for the edits the apps offer, and applies the same
 * edit to the list shown, so a screen can update at once and roll back if the server refuses.
 */
object PlaylistEdits {
    /** Renames; a blank name is rejected by the server (`400 Enter a playlist name`), so it is trimmed here. */
    fun rename(name: String): PlaylistPatch = PlaylistPatch(name = name.trim())

    /** Appends [trackIds] in order (an album, or one song). */
    fun append(trackIds: List<Long>): PlaylistPatch = PlaylistPatch(append = trackIds)

    /** Removes the entry at [position]. */
    fun remove(position: Int): PlaylistPatch = PlaylistPatch(remove = listOf(position))

    /**
     * Moves the entry at [from] to [to] in a playlist of [size] entries: `order[i]` is the old
     * position placed at new position `i`.
     */
    fun move(size: Int, from: Int, to: Int): PlaylistPatch = PlaylistPatch(order = moveOrder(size, from, to))

    fun moveOrder(size: Int, from: Int, to: Int): List<Int> {
        require(from in 0 until size && to in 0 until size) { "Positions out of range" }
        return (0 until size).toMutableList().apply { add(to, removeAt(from)) }
    }

    /** [items] after [moveOrder] (what the server will hold after the move). */
    fun <T> moved(items: List<T>, from: Int, to: Int): List<T> = moveOrder(items.size, from, to).map { items[it] }

    /** [items] without the entry at [position]. */
    fun <T> removed(items: List<T>, position: Int): List<T> = items.filterIndexed { index, _ -> index != position }
}
