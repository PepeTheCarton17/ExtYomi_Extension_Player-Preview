package eu.kanade.presentation.util

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap

/**
 * Icon shown in place of the back arrow in a generated standalone app.
 *
 * The launcher icon of the generated APK is the icon of the extension it was built for, so that is
 * also the icon the source catalogue should display.
 */
@Composable
fun StandaloneSourceIcon(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val painter = remember(context.packageName) {
        runCatching { context.packageManager.getApplicationIcon(context.packageName) }
            .getOrNull()
            ?.let { BitmapPainter(it.toBitmap().asImageBitmap()) }
    }
    if (painter != null) {
        Image(painter = painter, contentDescription = null, modifier = modifier)
    }
}
