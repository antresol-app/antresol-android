package app.antresol.core.api

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A fully read HTTP response. */
internal class RawResponse(val status: Int, val headers: Headers, val body: String)

/**
 * Runs the call on OkHttp's dispatcher. A 2xx body is handed to [onSuccess] there (streamed, e.g.
 * into a file) and null is returned; any other answer is read fully and returned for error handling.
 */
internal suspend fun Call.awaitStreamed(onSuccess: (Response) -> Unit): RawResponse? = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val result = runCatching {
                response.use {
                    if (it.code in 200..299) {
                        onSuccess(it)
                        null
                    } else {
                        RawResponse(it.code, it.headers, it.body?.string().orEmpty())
                    }
                }
            }
            result.fold(cont::resume) { if (!cont.isCancelled) cont.resumeWithException(it) }
        }
    })
}

/** Runs the call on OkHttp's dispatcher, reads the body there, and cancels the call with the coroutine. */
internal suspend fun Call.awaitRaw(): RawResponse = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val result = runCatching {
                response.use { RawResponse(it.code, it.headers, it.body?.string().orEmpty()) }
            }
            result.fold(cont::resume) { if (!cont.isCancelled) cont.resumeWithException(it) }
        }
    })
}
