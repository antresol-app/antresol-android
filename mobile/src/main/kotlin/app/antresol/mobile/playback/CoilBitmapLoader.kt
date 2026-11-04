package app.antresol.mobile.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import coil.imageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Loads notification and lock-screen artwork through the app's Coil loader, which adds the
 * Bearer header that server images require (Media3's default loader sends no headers).
 */
@UnstableApi
class CoilBitmapLoader(private val context: Context, private val scope: CoroutineScope) : BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
            ?: return Futures.immediateFailedFuture(IOException("Undecodable artwork"))
        return Futures.immediateFuture(bitmap)
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val future = SettableFuture.create<Bitmap>()
        scope.launch {
            try {
                val request = ImageRequest.Builder(context)
                    .data(uri.toString())
                    .size(ARTWORK_PX)
                    .allowHardware(false)
                    .build()
                when (val result = context.imageLoader.execute(request)) {
                    is SuccessResult -> {
                        val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
                        if (bitmap != null) future.set(bitmap) else future.setException(IOException("Artwork is not a bitmap"))
                    }
                    is ErrorResult -> future.setException(result.throwable)
                }
            } catch (e: CancellationException) {
                future.cancel(false)
                throw e
            }
        }
        return future
    }

    private companion object {
        const val ARTWORK_PX = 512
    }
}
