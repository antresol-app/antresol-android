package app.antresol.tv.homechannels

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import app.antresol.tv.MainActivity
import app.antresol.tv.R

/** What the tile is when launched: a plain `VIEW` of the deep link with the install's [nonce], addressed to this app only. */
internal fun HomeLink.intent(context: Context, nonce: String): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(HomeLinks.uri(this, nonce))).setPackage(context.packageName)

/** [ProgramSpec] → the TV provider's program objects. Kept apart from the store so they can be checked without a provider. */
object ProgramBuilders {
    /** Between neighbours in Watch Next: the row is ordered by the time of the last engagement, newest first. */
    const val ENGAGEMENT_STEP_MS = 60_000L

    fun watchNext(context: Context, spec: ProgramSpec, nowMs: Long): WatchNextProgram {
        val builder = WatchNextProgram.Builder()
            .setType(type(spec.kind))
            .setWatchNextType(
                when (spec.watchNext) {
                    WatchNextKind.Continue -> TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
                    else -> TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_NEXT
                },
            )
            .setLastEngagementTimeUtcMillis(nowMs - spec.rank * ENGAGEMENT_STEP_MS)
        if (spec.watchNext == WatchNextKind.Continue) {
            builder.setDurationMillis(spec.durationMs.toIntClamped()).setLastPlaybackPositionMillis(spec.positionMs.toIntClamped())
        }
        return builder.common(context, spec).build()
    }

    fun preview(context: Context, spec: ProgramSpec, channelId: Long): PreviewProgram =
        PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(type(spec.kind))
            // Higher weights come first in the channel.
            .setWeight(HomeMapper.MAX_CHANNEL - spec.rank)
            .common(context, spec)
            .build()

    private fun <T : androidx.tvprovider.media.tv.BasePreviewProgram.Builder<T>> T.common(context: Context, spec: ProgramSpec): T {
        setTitle(spec.title)
        setDescription(spec.description)
        spec.posterUri?.let { setPosterArtUri(Uri.parse(it)) }
        setPosterArtAspectRatio(
            if (spec.aspect == Aspect.Wide) {
                TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_16_9
            } else {
                TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_2_3
            },
        )
        setIntent(spec.link.intent(context, spec.linkNonce))
        setInternalProviderId(spec.key)
        setInternalProviderFlag1(spec.fingerprint())
        spec.episodeTitle?.let { setEpisodeTitle(it) }
        spec.season?.let { setSeasonNumber(it) }
        spec.episode?.let { setEpisodeNumber(it) }
        spec.year?.let { setReleaseDate(it.toString()) }
        return this
    }

    private fun type(kind: ProgramKind): Int = when (kind) {
        ProgramKind.Movie -> TvContractCompat.PreviewProgramColumns.TYPE_MOVIE
        ProgramKind.Episode -> TvContractCompat.PreviewProgramColumns.TYPE_TV_EPISODE
        ProgramKind.Series -> TvContractCompat.PreviewProgramColumns.TYPE_TV_SERIES
    }

    private fun Long.toIntClamped(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}

/** What the app remembers about its channel between runs. */
class HomeChannelPrefs(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The «Antresol» channel's id in the TV provider. */
    var channelId: Long?
        get() = prefs.getLong(KEY_CHANNEL, -1L).takeIf { it >= 0 }
        set(value) = prefs.edit().apply { if (value == null) remove(KEY_CHANNEL) else putLong(KEY_CHANNEL, value) }.apply()

    /** The user was asked once to let the channel show on the home screen (whatever the answer was). */
    var browsableRequested: Boolean
        get() = prefs.getBoolean(KEY_BROWSABLE_REQUESTED, false)
        set(value) = prefs.edit().putBoolean(KEY_BROWSABLE_REQUESTED, value).apply()

    /** A random string of this install: part of every cached picture's name ([HomeArtworkFiles]). */
    val artworkSecret: String
        @Synchronized get() = prefs.getString(KEY_SECRET, null) ?: java.util.UUID.randomUUID().toString().replace("-", "")
            .also { prefs.edit().putString(KEY_SECRET, it).apply() }

    /**
     * A random string of this install that every tile's address carries ([HomeLinks]): the activity that opens the
     * tiles is exported, and only the TV provider has seen this string. Not in backups (none are taken).
     */
    val linkNonce: String
        @Synchronized get() = prefs.getString(KEY_NONCE, null)?.takeIf { it.isNotEmpty() } ?: newNonce()
            .also { prefs.edit().putString(KEY_NONCE, it).apply() }

    private fun newNonce(): String {
        val bytes = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val KEY_NONCE = "link_nonce"
        const val FILE = "tv_home_channels"
        const val KEY_CHANNEL = "channel_id"
        const val KEY_BROWSABLE_REQUESTED = "browsable_requested"
        const val KEY_SECRET = "artwork_secret"
    }
}

/**
 * [HomeStore] on the system's TV provider, through androidx.tvprovider ([PreviewChannelHelper]). Writes need the
 * provider's `WRITE_EPG_DATA` permission, which the library declares in its own manifest. A row the user dismissed
 * on the launcher stays in the provider (hidden) and is only ever updated, so it does not come back.
 */
class TvProviderStore(
    private val context: Context,
    private val prefs: HomeChannelPrefs,
    private val channelName: () -> String,
    private val channelDescription: () -> String,
) : HomeStore {
    private val resolver get() = context.contentResolver
    private val helper = PreviewChannelHelper(context)

    // Whether the device has a TV provider at all, asked once (some TV boxes, e.g. certain Fire OS builds, have none).
    private val providerThere: Boolean by lazy {
        runCatching { resolver.acquireContentProviderClient(TvContractCompat.AUTHORITY)?.use { true } ?: false }.getOrDefault(false)
    }

    override fun available(): Boolean = providerThere

    override fun storedWatchNext(): List<StoredProgram> =
        rows(TvContractCompat.WatchNextPrograms.CONTENT_URI, WatchNextProgram.PROJECTION) { cursor ->
            WatchNextProgram.fromCursor(cursor).let { StoredProgram(it.id, it.internalProviderId, it.internalProviderFlag1) }
        }

    override fun storedChannel(channelId: Long): List<StoredProgram> =
        rows(TvContractCompat.buildPreviewProgramsUriForChannel(channelId), PreviewProgram.PROJECTION) { cursor ->
            PreviewProgram.fromCursor(cursor).let { StoredProgram(it.id, it.internalProviderId, it.internalProviderFlag1) }
        }

    private fun <T> rows(uri: Uri, projection: Array<String>, read: (android.database.Cursor) -> T): List<T> =
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(read(cursor)) }
        }.orEmpty()

    override fun applyWatchNext(plan: Plan, nowMs: Long) {
        plan.remove.forEach { resolver.delete(TvContractCompat.buildWatchNextProgramUri(it), null, null) }
        plan.update.forEach { (id, spec) -> helper.updateWatchNextProgram(ProgramBuilders.watchNext(context, spec, nowMs), id) }
        plan.add.forEach { spec ->
            if (helper.publishWatchNextProgram(ProgramBuilders.watchNext(context, spec, nowMs)) < 0) Log.w(TAG, "Watch Next refused ${spec.key}")
        }
    }

    override fun applyChannel(channelId: Long, plan: Plan) {
        plan.remove.forEach { helper.deletePreviewProgram(it) }
        plan.update.forEach { (id, spec) -> helper.updatePreviewProgram(id, ProgramBuilders.preview(context, spec, channelId)) }
        plan.add.forEach { spec ->
            if (helper.publishPreviewProgram(ProgramBuilders.preview(context, spec, channelId)) < 0) Log.w(TAG, "The channel refused ${spec.key}")
        }
    }

    private fun ownChannels(): List<PreviewChannel> = helper.getAllChannels().filter { it.internalProviderId == CHANNEL_KEY }

    override fun ensureChannel(): ChannelState? {
        val name = channelName()
        val description = channelDescription()
        val channels = ownChannels()
        val current = channels.firstOrNull()
        if (current != null) {
            // Two channels can only come from two runs racing: keep the first.
            channels.drop(1).forEach { helper.deletePreviewChannel(it.id) }
            if (current.displayName?.toString() != name || current.description?.toString() != description) {
                helper.updatePreviewChannel(current.id, PreviewChannel.Builder().setDisplayName(name).setDescription(description).build())
            }
            prefs.channelId = current.id
            return ChannelState(current.id, current.isBrowsable)
        }
        val builder = PreviewChannel.Builder()
            .setDisplayName(name)
            .setDescription(description)
            .setAppLinkIntent(Intent(context, MainActivity::class.java))
            .setInternalProviderId(CHANNEL_KEY)
        logo()?.let { builder.setLogo(it) }
        val id = helper.publishChannel(builder.build())
        if (id < 0) return null
        prefs.channelId = id
        return ChannelState(id, browsable = false)
    }

    override fun clearPrograms(): Int {
        var removed = 0
        storedWatchNext().forEach {
            resolver.delete(TvContractCompat.buildWatchNextProgramUri(it.id), null, null)
            removed++
        }
        ownChannels().forEach { channel ->
            storedChannel(channel.id).forEach {
                helper.deletePreviewProgram(it.id)
                removed++
            }
        }
        return removed
    }

    /** The app's icon on an opaque square (the launcher shows a channel logo under a circular mask and wants it opaque). */
    private fun logo(): Bitmap? = runCatching {
        val icon = ContextCompat.getDrawable(context, R.mipmap.ic_launcher) ?: return null
        Bitmap.createBitmap(LOGO_PX, LOGO_PX, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).apply {
                drawColor(LOGO_BACKGROUND)
                icon.setBounds(0, 0, LOGO_PX, LOGO_PX)
                icon.draw(this)
            }
        }
    }.getOrNull()

    private companion object {
        const val TAG = "HomeChannels"
        const val CHANNEL_KEY = "antresol-new-in-library"
        const val LOGO_PX = 256
        const val LOGO_BACKGROUND = 0xFF111114.toInt()
    }
}
