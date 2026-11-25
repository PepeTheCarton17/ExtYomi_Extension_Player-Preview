package eu.kanade.tachiyomi.util.standalone.sync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** App-private, atomically written snapshots owned by ExtYomi. */
class StandaloneSyncStore(context: Context) {

    private val root = File(context.applicationContext.filesDir, "standalone-sync")
    private val mutex = Mutex()

    init {
        root.mkdirs()
    }

    suspend fun loadOwners(): SyncOwnerIndex = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { File(root, "owners.json").readText().toSyncOwnerIndex() }
                .getOrDefault(SyncOwnerIndex())
        }
    }

    suspend fun saveOwners(index: SyncOwnerIndex) = withContext(Dispatchers.IO) {
        mutex.withLock {
            writeAtomically(File(root, "owners.json"), standaloneSyncJson.encodeToString(index))
        }
    }

    suspend fun load(instanceId: String): StandaloneSyncPayload? = withContext(Dispatchers.IO) {
        mutex.withLock {
            runCatching { File(root, "$instanceId.json").readText().toStandaloneSyncPayload() }
                .getOrNull()
        }
    }

    suspend fun save(payload: StandaloneSyncPayload) = withContext(Dispatchers.IO) {
        mutex.withLock {
            writeAtomically(
                File(root, "${payload.instanceId}.json"),
                payload.toJson(),
            )
        }
    }

    suspend fun delete(instanceId: String) = withContext(Dispatchers.IO) {
        mutex.withLock { File(root, "$instanceId.json").delete() }
    }

    private fun writeAtomically(target: File, content: String) {
        val temp = File.createTempFile("standalone-sync-", ".tmp", root)
        try {
            temp.writeText(content)
            target.delete()
            check(temp.renameTo(target)) { "Could not write sync snapshot" }
        } finally {
            temp.delete()
        }
    }
}

private fun String.toSyncOwnerIndex(): SyncOwnerIndex = standaloneSyncJson.decodeFromString(this)
