package app.antresol.tv.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import coil.imageLoader
import coil.request.ImageRequest
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.io.IOException

/** Loads now-playing artwork through the app's Coil loader, which adds the server's Bearer header. */
@UnstableApi
class CoilBitmapLoader(private val context: Context) : BitmapLoader {
    override fun supportsMimeType(mimeType: String): Boolean = mimeType.startsWith("image/")

    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> {
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
            ?: return Futures.immediateFailedFuture(IOException("Unreadable artwork"))
        return Futures.immediateFuture(bitmap)
    }

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        val future = SettableFuture.create<Bitmap>()
        val request = ImageRequest.Builder(context)
            .data(uri.toString())
            .size(ARTWORK_SIZE_PX)
            .allowHardware(false)
            .target(
                onSuccess = { drawable -> future.set(drawable.toBitmap()) },
                onError = { future.setException(IOException("Artwork failed to load: $uri")) },
            )
            .build()
        context.imageLoader.enqueue(request)
        return future
    }

    private companion object {
        const val ARTWORK_SIZE_PX = 512
    }
}
