package eu.kanade.tachiyomi.util.standalone.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.content.FileProvider
import eu.kanade.domain.ui.GeneratedStandaloneApp
import eu.kanade.domain.ui.UiPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.storage.service.StoragePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Host side of the two-way sync. Previously this only rebuilt ExtYomi's own database and applied
 * it back to itself, so nothing from the standalone ever arrived. It now pulls the standalone's
 * snapshot, merges it into ExtYomi, and pushes the result back.
 */
object HostStandaloneSyncManager {

    suspend fun syncAll(context: Context): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val apps = Injekt.get<UiPreferences>().standaloneApps().get()
                .filter { it.instanceId.isNotBlank() }
            check(apps.isNotEmpty()) {
                "No hay standalones registrados con ID de sincronización; vuelve a generar la app"
            }
            val errors = mutableListOf<String>()
            var synced = 0
            apps.forEach { app ->
                runCatching { syncOne(context, app) }
                    .onSuccess { synced++ }
                    .onFailure { error ->
                        errors += "${app.packageName}: ${error.message ?: error::class.simpleName}"
                    }
            }
            check(synced > 0) { errors.distinct().joinToString(" · ") }
            buildString {
                append("$synced/${apps.size}")
                if (errors.isNotEmpty()) append(" (${errors.size} con errores)")
            }
        }
    }

    private suspend fun syncOne(context: Context, app: GeneratedStandaloneApp) {
        val resolver = context.contentResolver
        val uri = Uri.parse("content://${StandaloneDataProvider.authority(app.packageName)}")
        val store = StandaloneSyncStore(context)

        val pulled = resolver.call(uri, StandaloneDataProvider.METHOD_PULL_LOCAL, null, Bundle())
            ?: error("La standalone no respondió al leer sus datos")
        val pullError = pulled.getString(StandaloneDataProvider.ERROR)
        check(pullError.isNullOrBlank()) { pullError ?: "No se pudieron leer los datos de la standalone" }
        val pulledUri = Uri.parse(
            pulled.getString(StandaloneDataProvider.KEY_URI)
                ?: error("La standalone no devolvió una snapshot"),
        )
        val remote = resolver.openInputStream(pulledUri)?.use {
            it.readBytes().toString(Charsets.UTF_8).toStandaloneSyncPayload()
        } ?: error("No se pudo leer la snapshot de la standalone")
        check(remote.instanceId == app.instanceId) { "Standalone returned another instance" }
        check(remote.sourceId == app.sourceId && remote.kind == app.kind) {
            "Standalone returned another scope"
        }

        val stored = store.load(app.instanceId)
        val owners = store.loadOwners().droppingStaleOwners(registeredInstanceIds(app)).owners
        val fresh = StandaloneSnapshotBuilder(
            sourceId = app.sourceId,
            kind = app.kind,
            instanceId = app.instanceId,
            extensionPkg = app.extensionPkg,
            previous = stored,
        ).build(hostDownloadsRootUri())
        val mergedResult = StandaloneSyncMerge.merge(remote, fresh, SyncOwnerIndex(owners))
        val merged = mergedResult.payload.copy(
            entries = mergedResult.payload.entries.filter { entry ->
                val key = SyncOwnerIndex.key(app.kind, app.sourceId, entry.url)
                mergedResult.owners[key] == null || mergedResult.owners[key] == app.instanceId
            },
        )
        StandaloneSnapshotApplier(
            sourceId = app.sourceId,
            kind = app.kind,
            previous = stored,
        ).apply(merged)
        store.save(merged)
        store.saveOwners(SyncOwnerIndex(mergedResult.owners))

        val file = File(context.cacheDir, "standalone-sync-host-out.json").apply {
            writeText(merged.toJson())
        }
        val outgoing = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file,
        )
        context.grantUriPermission(
            app.packageName,
            outgoing,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        val pushed = resolver.call(
            uri,
            StandaloneDataProvider.METHOD_PUSH_LOCAL,
            null,
            Bundle().apply {
                putString(StandaloneDataProvider.KEY_INSTANCE_ID, app.instanceId)
                putString(StandaloneDataProvider.KEY_URI, outgoing.toString())
            },
        ) ?: error("La standalone no respondió al aplicar la snapshot")
        val pushError = pushed.getString(StandaloneDataProvider.ERROR)
        check(pushError.isNullOrBlank()) { pushError ?: "No se pudo aplicar la snapshot" }
        check(pushed.getBoolean(StandaloneDataProvider.KEY_ACCEPTED, false)) {
            "La standalone no aceptó la snapshot"
        }
    }

    /** An owner from a reinstalled standalone is stale: its package and instanceId changed. */
    private fun registeredInstanceIds(app: GeneratedStandaloneApp): Set<String> =
        runCatching { Injekt.get<UiPreferences>().standaloneApps().get() }
            .getOrDefault(emptyList())
            .mapNotNull { it.instanceId.takeIf(String::isNotBlank) }
            .toSet() + app.instanceId

    private fun hostDownloadsRootUri(): String? = runCatching {
        Injekt.get<StoragePreferences>().baseStorageDirectory().get()
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
