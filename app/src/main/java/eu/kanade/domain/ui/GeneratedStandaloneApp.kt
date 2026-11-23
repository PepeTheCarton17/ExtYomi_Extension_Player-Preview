package eu.kanade.domain.ui

import kotlinx.serialization.Serializable

/**
 * A standalone APK produced by the generator and kept so the same app can reinstall or update it
 * without asking the user to regenerate it from scratch.
 */
@Serializable
data class GeneratedStandaloneApp(
    val packageName: String,
    val label: String,
    val sourceId: Long,
    val kind: String,
    val extensionPkg: String,
    val extensionVersionName: String,
    val sourceName: String,
    /** SAF tree URI the APK was written to, reused when updating. */
    val outputUri: String,
    /** SAF document URI of the APK itself, used to install it. */
    val apkUri: String,
    val fileName: String,
    /** Host version the APK was built from, used to detect newer generator releases. */
    val generatedVersionCode: Long,
    val generatedVersionName: String,
)
