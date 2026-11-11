package app.antresol.core.api

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds `Authorization: Bearer`, `Accept-Language`, `X-Vault-Caps` and strips `Origin` on requests to the
 * Antresol server only, so the same OkHttpClient can serve Coil and ExoPlayer safely.
 *
 * The providers are read on every request, so a sign-in or language change applies at once.
 */
class AntresolHeadersInterceptor(
    private val baseUrl: () -> String?,
    private val token: () -> String?,
    private val language: () -> String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val server = baseUrl()?.toHttpUrlOrNull()
        if (server == null || !request.url.isSameOrigin(server)) return chain.proceed(request)

        val builder = request.newBuilder().removeHeader("Origin")
        if (request.header("Accept-Language") == null) builder.header("Accept-Language", language())
        // This client lists external voice-over files and cleaned tracks (index 2000+, `src`, «Очищено»).
        if (request.header(CAPS_HEADER) == null) builder.header(CAPS_HEADER, CAPS)
        val bearer = token()
        if (bearer != null && request.header("Authorization") == null && request.url.queryParameter("mk") == null) {
            builder.header("Authorization", "Bearer $bearer")
        }
        return chain.proceed(builder.build())
    }
}

/** What the app understands beyond the first builds; without it the server hides external audio. */
const val CAPS_HEADER = "X-Vault-Caps"
const val CAPS = "external-audio"

/** Same scheme, host and port: the only URLs that may receive the device token. */
internal fun HttpUrl.isSameOrigin(other: HttpUrl): Boolean =
    scheme == other.scheme && host == other.host && port == other.port

/** Maps a locale language to what the server understands. The server defaults to Russian. */
object ServerLanguage {
    const val EN = "en"
    const val RU = "ru"

    fun from(languageTag: String): String = if (languageTag.lowercase().startsWith("ru")) RU else EN
}
