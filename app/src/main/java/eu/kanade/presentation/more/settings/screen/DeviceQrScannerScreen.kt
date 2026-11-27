package eu.kanade.presentation.more.settings.screen

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Voyager saves screens in the activity Bundle, so the screen must stay a stateless singleton.
 * The decoded value travels through this flow instead of a non-serializable callback.
 */
object DeviceQrScannerResult {
    private val _pending = MutableStateFlow<String?>(null)
    val pending = _pending.asStateFlow()
    fun submit(text: String) {
        _pending.value = text
    }
    fun consume() {
        _pending.value = null
    }
}

/** QR scanner with a visible targeting frame, auto-detection and an explicit scan button. */
object DeviceQrScannerScreen : Screen {

    override val key: String = "device_qr_scanner"

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val navigator = LocalNavigator.currentOrThrow
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
            Column(
                Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .padding(16.dp),
            ) {
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) {
                    Text(stringResource(MR.strings.device_sync_camera_permission))
                }
            }
            return
        }

        val previewView = remember { PreviewView(context) }
        val done = remember { mutableStateOf(false) }
        val lastDecoded = remember { mutableStateOf<String?>(null) }
        var message by remember { mutableStateOf<String?>(null) }
        val analyzer = remember {
            ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
        }
        val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
        val noQr = stringResource(MR.strings.device_sync_no_qr)

        fun finish(text: String) {
            if (done.value) return
            done.value = true
            DeviceQrScannerResult.submit(text)
            if (navigator.canPop) navigator.pop()
        }

        LaunchedEffect(Unit) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    val provider = future.get()
                    val preview = Preview.Builder().build().also { cameraPreview ->
                        cameraPreview.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    analyzer.setAnalyzer(mainExecutor) { image ->
                        val decoded = runCatching { decode(image) }.getOrNull()
                        image.close()
                        if (decoded != null) {
                            lastDecoded.value = decoded
                            finish(decoded)
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
            Box(
                modifier = Modifier
                    .size(260.dp)
                    .align(Alignment.Center)
                    .border(BorderStroke(2.dp, Color.White), RoundedCornerShape(12.dp)),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(MR.strings.device_sync_scan_hint))
                message?.let { Text(it) }
                Button(
                    onClick = {
                        val decoded = lastDecoded.value
                        if (decoded != null && !done.value) {
                            finish(decoded)
                        } else {
                            message = noQr
                        }
                    },
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
            plane.buffer.position(y * plane.rowStride)
            plane.buffer.get(data, y * width, width)
        }
        val hints = mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        )
        // ZXing is sensitive to rotation; try all four orientations of the Y plane.
        listOf(0, 90, 180, 270).forEach { degrees ->
            val (bytes, decodedWidth, decodedHeight) = rotate(data, width, height, degrees)
            val result = runCatching {
                MultiFormatReader().apply { setHints(hints) }.decode(
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
}
