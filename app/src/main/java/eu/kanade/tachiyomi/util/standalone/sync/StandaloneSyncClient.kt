package eu.kanade.tachiyomi.util.standalone.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.FileProvider
import eu.kanade.domain.ui.StandaloneScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.storage.service.StoragePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/** Standalone side of the sync: handshake → push local changes → pull the merged snapshot. */
object StandaloneSyncClient {

    suspend fun syncNow(context: Context): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(StandaloneScope.isActive) { "This is not a standalone app" }
            val authority = StandaloneScope.syncAuthority
            require(authority.isNotBlank()) { "The host sync service is not configured" }
            val uri = Uri.parse("content://$authority")
            val resolver = context.contentResolver
            val instanceId = StandaloneScope.instanceId
            require(instanceId.isNotBlank()) { "This standalone has no sync id" }

            val handshake = resolver.call(
                uri,
                StandaloneDataProvider.METHOD_HANDSHAKE,
                null,
                Bundle().apply { putString(StandaloneDataProvider.KEY_INSTANCE_ID, instanceId) },
            ) ?: error("No response from ExtYomi")
            checkError(handshake)
            val hostPackage = handshake.getString(StandaloneDataProvider.KEY_HOST_PACKAGE).orEmpty()
            check(hostPackage.isNotBlank()) { "ExtYomi did not identify itself" }

            handshake.getString(StandaloneDataProvider.KEY_DOWNLOADS_ROOT_URI)
                ?.takeIf { it.isNotBlank() }
                ?.let { root ->
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            Uri.parse(root),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                        )
                    }
                    Injekt.get<StoragePreferences>().baseStorageDirectory().set(root)
                }

            val snapshotFile = File(context.cacheDir, "standalone-sync-in.json")
            val snapshotUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                snapshotFile.apply {
                    writeText(
                        StandaloneSnapshotBuilder().build().toJson(),
                    )
                },
            )
            grant(context, hostPackage, snapshotUri)

            val pushed = resolver.call(
                uri,
                StandaloneDataProvider.METHOD_PUSH,
                null,
                Bundle().apply {
                    putString(StandaloneDataProvider.KEY_INSTANCE_ID, instanceId)
                    putString(StandaloneDataProvider.KEY_URI, snapshotUri.toString())
                },
            ) ?: error("No response while pushing")
            checkError(pushed)

            val pulled = resolver.call(
                uri,
                StandaloneDataProvider.METHOD_PULL,
                null,
                Bundle().apply { putString(StandaloneDataProvider.KEY_INSTANCE_ID, instanceId) },
            ) ?: error("No response while pulling")
            checkError(pulled)
            val pulledUri = Uri.parse(
                pulled.getString(StandaloneDataProvider.KEY_URI)
                    ?: error("ExtYomi returned no snapshot"),
            )
            val merged = context.contentResolver.openInputStream(pulledUri)?.use {
                it.readBytes().toString(Charsets.UTF_8).toStandaloneSyncPayload()
            } ?: error("Could not read the merged snapshot")
            check(merged.instanceId == instanceId) { "Wrong sync instance" }
            check(merged.sourceId == StandaloneScope.sourceId) { "Wrong source" }
            check(merged.kind == StandaloneScope.kind) { "Wrong content kind" }

            StandaloneSnapshotApplier().apply(merged)
            snapshotFile.delete()
            "OK"
        }
    }

    private fun grant(context: Context, hostPackage: String, uri: Uri) {
        runCatching {
            context.grantUriPermission(hostPackage, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun checkError(bundle: Bundle) {
        val error = bundle.getString(StandaloneDataProvider.ERROR)
        check(error.isNullOrBlank()) { error ?: "Sync failed" }
    }
}
