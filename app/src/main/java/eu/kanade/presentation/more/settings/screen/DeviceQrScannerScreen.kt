package eu.kanade.presentation.more.settings.screen

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import cafe.adriel.voyager.core.screen.Screen
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** Minimal QR scanner used to join a session without typing the shared code. */
class DeviceQrScannerScreen(private val onResult: (String) -> Unit) : Screen {

    override val key: String = "device_qr_scanner"

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        var granted by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED,
            )
        }
        val launcher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted = it }

        if (!granted) {
            Column(Modifier.fillMaxSize().padding(ButtonPadding)) {
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                    Text(stringResource(MR.strings.device_sync_camera_permission))
                }
            }
            return
        }

        val previewView = remember { PreviewView(context) }
        val done = remember { mutableStateOf(false) }
        val analyzer = remember {
            ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
        }

        AndroidView({ previewView }, modifier = Modifier.fillMaxSize())

        LaunchedEffect(Unit) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    val provider = future.get()
                    val preview = Preview.Builder().build().also { cameraPreview ->
                        cameraPreview.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    analyzer.setAnalyzer(ContextCompat.getMainExecutor(context)) { image ->
                        val decoded = runCatching { decode(image) }.getOrNull()
                        image.close()
                        if (decoded != null && !done.value) {
                            done.value = true
                            onResult(decoded)
                        }
                    }
                    runCatching {
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analyzer,
                        )
                    }
                },
                ContextCompat.getMainExecutor(context),
            )
        }

        DisposableEffect(Unit) {
            onDispose { analyzer.clearAnalyzer() }
        }
    }

    private fun decode(image: ImageProxy): String? {
        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val buffer = plane.buffer
        val data = ByteArray(width * height)
        for (y in 0 until height) {
            buffer.position(y * rowStride)
            buffer.get(data, y * width, width)
        }
        val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return MultiFormatReader().decode(bitmap).text
    }

    private companion object {
        val ButtonPadding = 16.dp
    }
}
