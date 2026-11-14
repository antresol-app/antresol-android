package app.antresol.mobile.ui.pairing

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.antresol.core.pairing.QrCodes
import java.util.concurrent.Executors

/**
 * The back camera's preview with a QR reader: CameraX for the frames, ZXing core (on the device,
 * no Play services or ML Kit) for the decoding. [onText] gets every decoded text on the main
 * thread (the same code again while it stays in view). The camera stops with the composable or
 * when the lifecycle stops. Requires the CAMERA permission.
 */
@Composable
fun QrScanner(onText: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(onText)
    val preview = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    AndroidView(factory = { preview }, modifier = modifier)
    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false
        future.addListener({
            if (disposed) return@addListener
            val cameras = runCatching { future.get() }.getOrNull() ?: return@addListener
            provider = cameras
            val previewUseCase = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                        .build(),
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { image ->
                val text = image.use(::decode)
                if (text != null) main.execute { if (!disposed) latest(text) }
            }
            runCatching {
                cameras.unbindAll()
                cameras.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase, analysis)
            }
        }, main)
        onDispose {
            disposed = true
            runCatching { provider?.unbindAll() }
            executor.shutdown()
        }
    }
}

/** The luminance (Y) plane of a YUV_420_888 frame through ZXing. */
private fun decode(image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
    if (plane.pixelStride != 1) return null
    // The last row may be shorter than rowStride: pad so ZXing's offsets stay inside the array.
    val needed = plane.rowStride * image.height
    val data = if (bytes.size >= needed) bytes else bytes.copyOf(needed)
    return QrCodes.decode(data, plane.rowStride, image.width, image.height)
}
