package app.antresol.ui.image

import android.content.Context
import app.antresol.core.api.AntresolClient
import app.antresol.core.api.AntresolHeadersInterceptor
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.OkHttpClient

/**
 * Coil image loaders for Antresol artwork. Images require `Authorization: Bearer` (the media key
 * is not accepted), so the loader uses an OkHttpClient carrying [AntresolHeadersInterceptor].
 */
object AntresolImageLoader {
    /** A loader sharing [client]'s OkHttpClient, which already adds the Bearer header for its server. */
    fun create(context: Context, client: AntresolClient): ImageLoader = create(context, client.httpClient)

    /** A loader whose headers follow the providers, for apps that swap [AntresolClient] instances. */
    fun create(
        context: Context,
        baseUrl: () -> String?,
        token: () -> String?,
        language: () -> String,
    ): ImageLoader = create(
        context,
        OkHttpClient.Builder().addInterceptor(AntresolHeadersInterceptor(baseUrl, token, language)).build(),
    )

    private fun create(context: Context, okHttpClient: OkHttpClient): ImageLoader {
        val appContext = context.applicationContext
        return ImageLoader.Builder(appContext)
            .okHttpClient(okHttpClient)
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
