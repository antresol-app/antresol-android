package app.antresol.core.family

import app.antresol.core.api.AntresolClient
import app.antresol.core.model.FamilyMember
import app.antresol.core.model.Kind
import app.antresol.core.model.Progress
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** The server calls of «Семья»; [AntresolClient.familyApi] is the real one. */
interface FamilyApi {
    /** The other profiles' places in item [itemId]; empty for a kid and when nobody has started it. */
    suspend fun members(itemId: Long): List<FamilyMember>

    /**
     * [userId]'s unfinished position in [itemId] becomes this profile's own (theirs is never written). 404 when they
     * are not in this profile's family view or have no unfinished position there.
     */
    suspend fun adopt(itemId: Long, userId: Long): Progress
}

fun AntresolClient.familyApi(): FamilyApi = object : FamilyApi {
    private val client = this@familyApi

    override suspend fun members(itemId: Long) = client.family(itemId)
    override suspend fun adopt(itemId: Long, userId: Long) = client.adoptProgress(itemId, userId)
}

/** What a page is, as far as «Семья» goes: the words of a place and the way to continue from it depend on it. */
enum class FamilyKind {
    Movie,
    Episode,
    Series,
    Book,
    Audiobook,
    Comic,
    ;

    companion object {
        /** The family kind of a page of item kind [kind]; null for kinds without progress to share (music, playlists). */
        fun of(kind: String): FamilyKind? = when (kind) {
            Kind.MOVIE -> Movie
            Kind.EPISODE -> Episode
            Kind.SERIES, Kind.SEASON -> Series
            Kind.BOOK -> Book
            Kind.AUDIOBOOK -> Audiobook
            Kind.COMIC, Kind.COMIC_VOLUME -> Comic
            else -> null
        }
    }
}

/** The rules of «Семья» that are not words: the ring, whether continuing copies a position, and whether there is one. */
object Family {
    /** The ring around a face: a series' or comic's share done, else the item's own. */
    fun ring(m: FamilyMember): Float = when {
        m.finished -> 1f
        m.total > 0 -> (m.done.toFloat() / m.total).coerceIn(0f, 1f)
        else -> m.fraction.toFloat().coerceIn(0f, 1f)
    }

    /**
     * Whether continuing from [m] first takes [m]'s position as this profile's own: a next episode or volume just
     * starts, and a place that is not a position (nothing played, nothing read) has nothing to take.
     */
    fun adopts(m: FamilyMember): Boolean = !m.finished && !m.next && (m.positionMs > 0 || m.locator.isNotEmpty())

    /** Whether there is somewhere to continue from [m] on a page of [kind] (the web's `jumpLabel() !== ''`). */
    fun canContinue(kind: FamilyKind, m: FamilyMember): Boolean {
        if (m.finished) return false
        return when (kind) {
            FamilyKind.Series, FamilyKind.Comic -> true
            FamilyKind.Movie, FamilyKind.Episode, FamilyKind.Audiobook -> m.positionMs > 0
            FamilyKind.Book -> m.page > 0 || m.locator.isNotEmpty()
        }
    }

    /** The first of [members] a page of [kind] can continue from, else null. */
    fun firstToContinue(kind: FamilyKind, members: List<FamilyMember>): FamilyMember? = members.firstOrNull { canContinue(kind, it) }

    /** How far apart two places in the same item must be for the family's to be offered at the start: over two minutes. */
    const val RESUME_GAP_MS = 120_000L

    /** How many of the family the start prompt lists. */
    const val RESUME_ROWS = 3

    /**
     * The family members the resume prompt offers when item [itemId] starts and this profile's own place in it is
     * [ownPositionMs] (0 for a first time): the ones with a place to take (not finished, not a next episode, a position
     * above zero) that is not about the same as this profile's: another episode always counts, the same item only when the
     * positions are over [RESUME_GAP_MS] apart. Most recent first, at most [RESUME_ROWS]. [members] may be the family of the
     * item itself or of its series (an episode's place is then wherever each of them is in the series).
     */
    fun resumeCandidates(members: List<FamilyMember>, itemId: Long, ownPositionMs: Long, limit: Int = RESUME_ROWS): List<FamilyMember> =
        members
            .filter { !it.finished && !it.next && it.positionMs > 0 }
            .filter { it.itemId != itemId || kotlin.math.abs(it.positionMs - ownPositionMs) > RESUME_GAP_MS }
            .sortedByDescending { updatedAtMillis(it) }
            .take(limit)

    /** [FamilyMember.updatedAt] as epoch milliseconds; a time that does not parse is the oldest. */
    private fun updatedAtMillis(m: FamilyMember): Long = try {
        OffsetDateTime.parse(m.updatedAt).toInstant().toEpochMilli()
    } catch (e: DateTimeParseException) {
        Long.MIN_VALUE
    }
}
