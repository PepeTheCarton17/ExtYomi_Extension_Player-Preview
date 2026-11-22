package eu.kanade.presentation.util

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import eu.kanade.domain.ui.StandaloneScope

/**
 * Icon shown in place of the back arrow in a generated standalone app.
 *
 * Prefer the icon of the extension the app was built for. Falling back to our own application icon
 * is not reliable: the launcher may have selected an activity alias with a different icon.
 */
@Composable
fun StandaloneSourceIcon(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val extensionPkg = StandaloneScope.extensionPkg
    val painter = remember(context.packageName, extensionPkg) {
        val drawable = runCatching { context.packageManager.getApplicationIcon(extensionPkg) }
            .getOrNull()
            ?: runCatching { context.packageManager.getApplicationIcon(context.packageName) }
                .getOrNull()
        drawable?.let { BitmapPainter(it.toBitmap().asImageBitmap()) }
    }
    if (painter != null) {
        Image(painter = painter, contentDescription = null, modifier = modifier)
    }
}
