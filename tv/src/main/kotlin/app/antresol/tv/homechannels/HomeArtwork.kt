package app.antresol.tv.homechannels

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Where the pictures of the home screen are kept, and how they are named.
 *
 * The launcher draws a program's picture by itself, with no login, and the server's images need the Bearer header
 * (`?mk=` does not work for them). So each picture is fetched by the app, shrunk, and put in a file that
 * [HomeArtworkProvider] hands to the launcher as `content://{authority}/{name}`. [name] is a SHA-256 of a secret that
 * belongs to this install plus the server path, so the address of a picture is known only to those who read it from
 * the TV provider; the provider serves nothing but these files.
 */
object HomeArtworkFiles {
    /** What the provider serves: 64 lowercase hex digits. */
    private val NAME = Regex("[0-9a-f]{64}")

    fun authority(context: Context): String = "${context.packageName}.homeart"

    fun dir(context: Context): File = File(context.cacheDir, "tv-home")

    fun isName(name: String?): Boolean = name != null && NAME.matches(name)

    fun name(secret: String, path: String): String =
        MessageDigest.getInstance("SHA-256").digest("$secret\u0000$path".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun uri(authority: String, name: String): String = "content://$authority/$name"

    /** The file behind [name], or null when the name is not one of ours or the file is not there. */
    fun fileFor(dir: File, name: String?): File? =
        if (isName(name)) File(dir, name!!).takeIf { it.isFile } else null
}

/** Shrinks a picture to what a TV card shows and re-encodes it, so the launcher does not decode the server's originals. */
object ArtworkEncoder {
    /** A 16:9 tile is at most 960 px wide on a 4K TV; a poster is shorter than that. */
    const val MAX_SIDE = 960
    private const val QUALITY = 85
    private const val BACKGROUND = 0xFF111114.toInt()

    /** The largest power of two to divide both sides by while the longer side stays at least [maxSide]. */
    fun sampleSize(width: Int, height: Int, maxSide: Int = MAX_SIDE): Int {
        var sample = 1
        val longer = maxOf(width, height)
        while (longer / (sample * 2) >= maxSide) sample *= 2
        return sample
    }

    /** A JPEG of [bytes] no larger than [maxSide] on its longer side; null when it is not a picture Android can decode (an SVG). */
    fun shrink(bytes: ByteArray, maxSide: Int = MAX_SIDE): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSide) }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val longer = maxOf(decoded.width, decoded.height)
        val scaled = if (longer > maxSide) {
            val factor = maxSide.toFloat() / longer
            Bitmap.createScaledBitmap(decoded, (decoded.width * factor).toInt().coerceAtLeast(1), (decoded.height * factor).toInt().coerceAtLeast(1), true)
        } else {
            decoded
        }
        // JPEG has no transparency: a logo-like picture goes on the app's dark background rather than black.
        val flat = if (scaled.hasAlpha()) {
            Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888).also {
                Canvas(it).apply {
                    drawColor(BACKGROUND)
                    drawBitmap(scaled, 0f, 0f, null)
                }
            }
        } else {
            scaled
        }
        return ByteArrayOutputStream().use { out ->
            if (flat.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) out.toByteArray() else null
        }
    }
}

/**
 * The cache of the pictures ([HomeArtworkFiles] says why it exists). A picture is fetched once and kept for [TTL_MS]
 * (the server's own cache lifetime), then fetched again; when that fails the old file keeps serving. What is not
 * behind a program any more is deleted by [retain].
 */
class HomeArtworkStore(
    private val dir: File,
    private val secret: String,
    private val authority: String,
    override val placeholderUri: String,
    /** The bytes of a server-relative path, or null when it could not be had. */
    private val fetch: suspend (path: String) -> ByteArray?,
    private val encode: (ByteArray) -> ByteArray? = { ArtworkEncoder.shrink(it) },
    private val clock: () -> Long = System::currentTimeMillis,
) : HomeArtwork {
    override suspend fun uriFor(ref: ArtworkRef): String? {
        for (path in listOf(ref.path, ref.fallbackPath)) {
            if (path.isEmpty()) continue
            val name = HomeArtworkFiles.name(secret, path)
            val file = File(dir, name)
            val fresh = file.isFile && clock() - file.lastModified() < TTL_MS
            if (fresh || download(path, file) || file.isFile) return HomeArtworkFiles.uri(authority, name)
        }
        return null
    }

    private suspend fun download(path: String, target: File): Boolean {
        val bytes = fetch(path) ?: return false
        val encoded = encode(bytes) ?: return false
        dir.mkdirs()
        // Written beside the target and renamed over it: the launcher never reads half a picture.
        val temp = File(dir, "${target.name}.tmp")
        temp.writeBytes(encoded)
        // Its age is measured by the store's own clock.
        temp.setLastModified(clock())
        return temp.renameTo(target).also { if (!it) temp.delete() }
    }

    override fun retain(uris: Set<String>) {
        val keep = uris.mapNotNullTo(HashSet()) { uri -> uri.takeIf { it.startsWith("content://$authority/") }?.substringAfterLast('/') }
        dir.listFiles()?.forEach { file -> if (file.name !in keep) file.delete() }
    }

    companion object {
        const val TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
