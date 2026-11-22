package eu.kanade.tachiyomi.util.standalone

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import com.android.apksig.ApkSigner
import com.hippo.unifile.UniFile
import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.archive.InputSource
import com.reandroid.arsc.value.ValueType
import eu.kanade.domain.ui.StandaloneScope
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.browse.standalone.StandaloneExtensionItem
import eu.kanade.tachiyomi.util.lang.Hash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

/**
 * Builds a standalone app from the installed ExtYomi APK.
 *
 * The output is the same fork with its own package, label, icon and a config asset pointing at
 * one installed extension. It is signed with a device-local key and written only to the SAF
 * folder chosen by the user.
 */
object StandaloneAppGenerator {

    suspend fun generate(
        context: Context,
        item: StandaloneExtensionItem,
        outputDirUri: Uri,
    ): String = withContext(Dispatchers.IO) {
        val workDir = File(context.cacheDir, "standalone-build").apply { mkdirs() }
        val unsignedApk = File(workDir, "unsigned.apk")
        val signedApk = File(workDir, "signed.apk")
        val hostApk = File(context.applicationInfo.sourceDir)
        val newPackage = standalonePackageName(context, item)
        val oldPackage = context.packageName

        val packageInfo = context.packageManager.getPackageInfo(
            item.extensionPkg,
            PackageManager.GET_SIGNING_CERTIFICATES,
        )
        val extensionVersionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
        val signatureHash = extensionSignatureHash(packageInfo)

        val config = JSONObject()
            .put("sourceId", item.sourceId)
            .put("kind", item.kind)
            .put("extensionPkg", item.extensionPkg)
            .put("extensionVersionCode", extensionVersionCode)
            .put("extensionSignatureHash", signatureHash)
            .put("generatedBy", BuildConfig.APPLICATION_ID)

        val minSdkVersion: Int
        val module = ApkModule.loadApkFile(hostApk)
        try {
            val manifest = module.androidManifest
            minSdkVersion = manifest.minSdkVersion ?: 26

            // New identity. setPackageName updates both the manifest and the resource table.
            module.setPackageName(newPackage)
            manifest.setApplicationLabel(item.name)

            // Provider authorities are literal application ids in the merged manifest; they must
            // be unique on-device or the standalone app cannot be installed next to the generator.
            val attributes = manifest.manifestElement.recursiveAttributes()
            while (attributes.hasNext()) {
                val attribute = attributes.next()
                if (!attribute.name.toString().contains("authorities")) continue
                if (attribute.valueType != ValueType.STRING) continue
                val value = attribute.valueAsString ?: continue
                if (value.contains(oldPackage)) {
                    attribute.setValueAsString(value.replace(oldPackage, newPackage))
                }
            }

            val launcherIcon = item.icon
                ?: context.getDrawable(R.mipmap.ic_launcher)
                ?: error("No launcher icon available for the standalone app")
            replaceLauncherIcons(module, launcherIcon)

            // Remove the host signature; a re-signed APK must not keep stale META-INF entries.
            module.inputSources.toList().forEach { source ->
                val alias = source.alias
                if (
                    alias.startsWith("META-INF/", ignoreCase = true) &&
                    (
                        alias.endsWith(".SF", ignoreCase = true) ||
                            alias.endsWith(".RSA", ignoreCase = true) ||
                            alias.endsWith(".DSA", ignoreCase = true) ||
                            alias.endsWith("MANIFEST.MF", ignoreCase = true)
                        )
                ) {
                    module.removeInputSource(alias)
                }
            }
            module.setApkSignatureBlock(null)

            val configPath = "assets/${StandaloneScope.CONFIG_ASSET}"
            module.removeInputSource(configPath)
            module.add(ByteInputSource(config.toString().toByteArray(Charsets.UTF_8), configPath))

            module.writeApk(unsignedApk)
        } finally {
            module.close()
        }

        val signer = StandaloneSigningKey.getOrCreate(context)
        val signerConfig = ApkSigner.SignerConfig.Builder(
            "extyomi",
            signer.privateKey,
            listOf(signer.certificate),
        ).build()
        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(unsignedApk)
            .setOutputApk(signedApk)
            .setMinSdkVersion(minSdkVersion)
            .build()
            .sign()

        val outputDir = UniFile.fromUri(context, outputDirUri)
            ?: error("Could not open the selected output folder")
        val fileName = "${safeFileName(item.name)}-${item.kind}.apk"
        val outputFile = outputDir.createFile(fileName)
            ?: error("Could not create the APK in the selected folder")
        outputFile.openOutputStream().use { output ->
            signedApk.inputStream().use { input -> input.copyTo(output) }
        }

        unsignedApk.delete()
        signedApk.delete()
        fileName
    }

    private fun standalonePackageName(context: Context, item: StandaloneExtensionItem): String {
        val seed = safeFileName(item.extensionPkg).lowercase(Locale.ROOT).take(32).trim('_')
        val prefix = if (seed.firstOrNull()?.isLetter() == true) seed else "ext_$seed"
        val source = "s" + java.lang.Long.toUnsignedString(item.sourceId, 16)
        return "${BuildConfig.APPLICATION_ID}.standalone.$prefix.$source"
    }

    private fun safeFileName(value: String): String {
        return value.trim()
            .replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .trim('_')
            .take(60)
            .ifEmpty { "standalone" }
    }

    @Suppress("DEPRECATION")
    private fun extensionSignatureHash(packageInfo: android.content.pm.PackageInfo): String {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = packageInfo.signingInfo
            if (info?.hasMultipleSigners() == true) {
                info.apkContentsSigners
            } else {
                info?.signingCertificateHistory
            }
        } else {
            packageInfo.signatures
        }
        val certificate = signatures?.lastOrNull()
            ?: error("The extension has no signing certificate")
        return Hash.sha256(certificate.toByteArray())
    }

    private fun replaceLauncherIcons(module: ApkModule, drawable: Drawable) {
        val iconBytes = drawable.toPngBytes()
        module.inputSources.toList().forEach { source: InputSource ->
            val alias = source.alias
            val fileName = alias.substringAfterLast('/')
            if (
                alias.startsWith("res/mipmap-") &&
                fileName.startsWith("ic_launcher") &&
                fileName.endsWith(".png")
            ) {
                val replacement = ByteInputSource(iconBytes, alias)
                replacement.copyAttributes(source)
                module.removeInputSource(alias)
                module.add(replacement)
            }
        }
    }

    private fun Drawable.toPngBytes(size: Int = 192): ByteArray {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, size, size)
        draw(canvas)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        bitmap.recycle()
        return stream.toByteArray()
    }
}
