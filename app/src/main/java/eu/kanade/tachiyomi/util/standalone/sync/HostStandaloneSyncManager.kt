package eu.kanade.tachiyomi.util.standalone.sync

import android.content.Context
import eu.kanade.domain.ui.GeneratedStandaloneApp
import eu.kanade.domain.ui.UiPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tachiyomi.domain.storage.service.StoragePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Host side: syncs ExtYomi's own changes into each registered standalone's snapshot. */
object HostStandaloneSyncManager {

    suspend fun syncAll(context: Context): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val apps = Injekt.get<UiPreferences>().standaloneApps().get()
            val store = StandaloneSyncStore(context)
            val root = runCatching {
                Injekt.get<StoragePreferences>().baseStorageDirectory().get()
            }.getOrNull()
            var synced = 0
            apps.forEach { app ->
                if (syncOne(app, store, root)) synced++
            }
            "$synced/${apps.size}"
        }
    }

    private suspend fun syncOne(
        app: GeneratedStandaloneApp,
        store: StandaloneSyncStore,
        downloadsRootUri: String?,
    ): Boolean {
        val instanceId = app.instanceId.ifBlank { return false }
        val owners = store.loadOwners()
        val host = StandaloneSnapshotBuilder(
            sourceId = app.sourceId,
            kind = app.kind,
            instanceId = instanceId,
            extensionPkg = app.extensionPkg,
        ).build(downloadsRootUri)
        val ownedByHost = host.copy(
            entries = host.entries.filter { entry ->
                val key = SyncOwnerIndex.key(app.kind, app.sourceId, entry.url)
                owners.owners[key] == null || owners.owners[key] == instanceId
            },
        )
        val merged = StandaloneSyncMerge.merge(store.load(instanceId), ownedByHost, owners)
        val scoped = merged.payload.copy(
            entries = merged.payload.entries.filter {
                merged.owners[SyncOwnerIndex.key(app.kind, app.sourceId, it.url)] == instanceId
            },
        )
        StandaloneSnapshotApplier(sourceId = app.sourceId, kind = app.kind).apply(scoped)
        store.save(scoped)
        store.saveOwners(SyncOwnerIndex(merged.owners))
        return true
    }
}
