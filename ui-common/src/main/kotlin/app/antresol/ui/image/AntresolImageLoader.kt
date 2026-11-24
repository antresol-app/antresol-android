package app.antresol.ui.image

import android.app.ActivityManager
import android.content.Context
import app.antresol.core.api.AntresolClient
import app.antresol.core.api.AntresolHeadersInterceptor
import app.antresol.core.api.MediaUrls
import coil.ImageLoader
import coil.disk.DiskCache
import coil.intercept.Interceptor
import coil.memory.MemoryCache
import coil.request.ImageResult
import coil.size.Dimension
import coil.size.Scale
import okhttp3.OkHttpClient

/**
 * Coil image loaders for Antresol artwork. Images require `Authorization: Bearer` (the media key
 * is not accepted), so the loader uses an OkHttpClient carrying [AntresolHeadersInterceptor].
 */
object AntresolImageLoader {
    /** A loader sharing [client]'s OkHttpClient, which already adds the Bearer header for its server. */
    fun create(context: Context, client: AntresolClient): ImageLoader = create(context, client.httpClient) { client.baseUrl }

    /** A loader whose headers follow the providers, for apps that swap [AntresolClient] instances. */
    fun create(
        context: Context,
        baseUrl: () -> String?,
        token: () -> String?,
        language: () -> String,
    ): ImageLoader = create(context, artworkHttpClient(baseUrl, token, language), baseUrl)

    /**
     * The API client's timeouts (a first `?w=` resize of a big picture on a busy server takes longer than
     * OkHttp's 10 s read default and would show as a failed image) and the headers of the providers.
     */
    internal fun artworkHttpClient(baseUrl: () -> String?, token: () -> String?, language: () -> String): OkHttpClient =
        AntresolClient.defaultHttpClient().newBuilder().addInterceptor(AntresolHeadersInterceptor(baseUrl, token, language)).build()

    /**
     * Request parameter of artwork drawn in a box of its own shape (a poster in a poster tile, a cover
     * in a square): the box's larger side is all the image needs, see [SizedImageInterceptor].
     */
    const val FITS_BOX = "antresol_fits_box"

    /**
     * Request parameter of a picture that may be transparent (a logo): it is asked for as it is, never
     * as the server's smaller copy (a JPEG, which has no transparency), see [SizedImageInterceptor].
     */
    const val TRANSPARENT = "antresol_transparent"

    private fun create(context: Context, okHttpClient: OkHttpClient, baseUrl: () -> String?): ImageLoader {
        val appContext = context.applicationContext
        val memory = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        // A low-RAM box has few, slow cores: two decodes at a time (Coil's default is four) leave some for the interface.
        val lowRam = memory.isLowRamDevice || memory.memoryClass <= LOW_RAM_MEMORY_CLASS_MB
        return ImageLoader.Builder(appContext)
            .apply { if (lowRam) bitmapFactoryMaxParallelism(2) }
            .okHttpClient(okHttpClient)
            .components { add(SizedImageInterceptor(baseUrl)) }
            .memoryCache { MemoryCache.Builder(appContext).maxSizePercent(0.20).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(appContext.cacheDir.resolve("artwork"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            // The server sends `Cache-Control: private, max-age=604800`; let Coil honour it.
            .respectCacheHeaders(true)
            .crossfade(true)
            .build()
    }
}

/** The app's heap limit (MB) at or under which a device counts as low on memory for decoding images. */
private const val LOW_RAM_MEMORY_CLASS_MB = 192

/**
 * Asks the server for an image no bigger than the view needs (`?w=`, [MediaUrls.sizedImage]) instead
 * of the original, which is slow to download and decode on a TV box. The width is part of the URL,
 * so of the memory and disk cache keys too: a small copy is never reused for a bigger view.
 */
private class SizedImageInterceptor(private val baseUrl: () -> String?) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val url = request.data as? String ?: return chain.proceed(request)
        val base = baseUrl() ?: return chain.proceed(request)
        val width = (chain.size.width as? Dimension.Pixels)?.px ?: return chain.proceed(request)
        val height = (chain.size.height as? Dimension.Pixels)?.px
        val fitsBox = request.parameters.value<Boolean>(AntresolImageLoader.FITS_BOX) == true
        val crop = request.scale == Scale.FILL
        val needed = when {
            height == null -> width
            fitsBox -> MediaUrls.neededTileWidth(width, height, crop)
            else -> MediaUrls.neededImageWidth(width, height, crop)
        }
        val transparent = request.parameters.value<Boolean>(AntresolImageLoader.TRANSPARENT) == true
        val sized = MediaUrls.sizedImage(base.trimEnd('/'), url, needed, transparent)
        return chain.proceed(if (sized == url) request else request.newBuilder().data(sized).build())
    }
}
