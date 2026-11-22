package eu.kanade.tachiyomi.util.system

import android.os.Build

/**
 * Device quirks that change how the player has to be configured.
 *
 * Windows Subsystem for Android virtualizes MediaCodec and OpenGL: the H.264 decoder cannot hand
 * mpv a DynamicANWBuffer, while gpu-next renders correctly. Hardware decoding must stay off there;
 * the defaults are applied once so the user still sees them in player settings.
 */
object DeviceCapabilities {

    val isWindowsSubsystemForAndroid: Boolean
        get() = Build.BRAND.equals("Windows", ignoreCase = true) ||
            Build.MANUFACTURER.contains("Microsoft", ignoreCase = true) ||
            Build.MODEL.contains("Subsystem for Android", ignoreCase = true)
}
