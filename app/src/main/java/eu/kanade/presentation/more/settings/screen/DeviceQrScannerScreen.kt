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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.Alignment
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

/** QR scanner with an explicit scan button; decoding also tolerates rotated frames. */
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
        val lastDecoded = remember { mutableStateOf<String?>(null) }
        val scanRequested = remember { mutableStateOf(false) }
        val analyzer = remember {
            ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
        }
        val mainExecutor = remember { ContextCompat.getMainExecutor(context) }

        LaunchedEffect(Unit) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    val provider = future.get()
                    val preview = Preview.Builder().build().also { cameraPreview ->
                        cameraPreview.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    analyzer.setAnalyzer(mainExecutor) { image ->
                        val decoded = if (lastDecoded.value != null && !scanRequested.value) {
                            lastDecoded.value
                        } else {
                            runCatching { decode(image) }.getOrNull()
                        }
                        image.close()
                        if (decoded != null) {
                            lastDecoded.value = decoded
                        } else {
                            lastDecoded.value = null
                            scanRequested.value = false
                        }
                        if (scanRequested.value && decoded != null && !done.value) {
                            done.value = true
                            scanRequested.value = false
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
                mainExecutor,
            )
        }

        DisposableEffect(Unit) {
            onDispose { analyzer.clearAnalyzer() }
        }

        Box(Modifier.fillMaxSize()) {
            AndroidView({ previewView }, modifier = Modifier.fillMaxSize())
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(ButtonPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(MR.strings.device_sync_scan_hint))
                Button(
                    onClick = { scanRequested.value = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(MR.strings.device_sync_scan))
                }
            }
        }
    }

    private fun decode(image: ImageProxy): String? {
        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width
        val height = image.height
        val data = ByteArray(width * height)
        for (y in 0 until height) {
            bufferPosition(plane.buffer, y * plane.rowStride)
            plane.buffer.get(data, y * width, width)
        }
        // ZXing is sensitive to rotation; try all four orientations of the Y plane.
        listOf(0, 90, 180, 270).forEach { degrees ->
            val (bytes, decodedWidth, decodedHeight) = rotate(data, width, height, degrees)
            val result = runCatching {
                MultiFormatReader().decode(
                    BinaryBitmap(
                        HybridBinarizer(
                            PlanarYUVLuminanceSource(
                                bytes,
                                decodedWidth,
                                decodedHeight,
                                0,
                                0,
                                decodedWidth,
                                decodedHeight,
                                false,
                            ),
                        ),
                    ),
                )
            }.getOrNull()
            if (result != null) return result.text
        }
        return null
    }

    private fun bufferPosition(buffer: java.nio.ByteBuffer, position: Int) {
        buffer.position(position)
    }

    private fun rotate(
        data: ByteArray,
        width: Int,
        height: Int,
        degrees: Int,
    ): Triple<ByteArray, Int, Int> = when (degrees) {
        90 -> {
            val out = ByteArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    out[x * height + (height - 1 - y)] = data[y * width + x]
                }
            }
            Triple(out, height, width)
        }
        270 -> {
            val out = ByteArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    out[(width - 1 - x) * height + y] = data[y * width + x]
                }
            }
            Triple(out, height, width)
        }
        180 -> Triple(data.reversedArray(), width, height)
        else -> Triple(data, width, height)
    }

    private companion object {
        val ButtonPadding = 16.dp
    }
}
