package app.antresol.core.model

import kotlinx.serialization.Serializable

// «Семья»: where the other profiles of the household are in a title, as `GET /api/items/{id}/family` lists them
// (most recent first; nobody for a kid profile, nobody who hides their progress, and an empty list when nobody has
// started the title). The shapes are the server's `FamilyMember` and the web's `api/types.ts`.

/**
 * One other profile's place in an item. [itemId] is where the position is: the movie, book or audiobook itself, the
 * episode of a series, the volume of a comic. For a series or comic, [done] and [total] count its finished episodes or
 * volumes, [next] says [itemId] is the one after the last finished (not started: [positionMs] 0), and [finished] that
 * everything is done.
 */
@Serializable
data class FamilyMember(
    val userId: Long,
    val name: String = "",
    /** A device's managed account names its person («Living Room · Alex»); empty for a person's own profile. */
    val ownerName: String = "",
    val avatar: String = "",
    /** A server-relative photo path; empty without one. */
    val photo: String = "",
    val isKid: Boolean = false,
    val itemId: Long = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** 0..1 of [itemId]. */
    val fraction: Double = 0.0,
    val finished: Boolean = false,
    /** A reader's place (`page:N`, an EPUB's CFI). */
    val locator: String = "",
    /** RFC 3339. */
    val updatedAt: String = "",
    val season: Int = 0,
    val episode: Int = 0,
    val volume: Double = 0.0,
    /** The episode's or volume's title. */
    val title: String = "",
    /** A `page:N` position: N, and the page count when the server knows it. */
    val page: Int = 0,
    val pages: Int = 0,
    val next: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
) {
    /** What the name line says: «Living Room · Alex» for a device's account, else the profile's name. */
    val displayName: String get() = if (ownerName.isBlank()) name else "$name · $ownerName"
}

@Serializable
internal data class FamilyResponse(val members: List<FamilyMember> = emptyList())

/** Body of `POST /api/progress/{itemId}/adopt`: whose position becomes this profile's own. */
@Serializable
internal data class AdoptBody(val userId: Long)
