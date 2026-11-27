package eu.kanade.tachiyomi.util.devicesync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * App-private transport files. A delta event is deleted only when every paired device has sent an
 * ACK for it; until then the outbox keeps the payload and a small per-peer ACK cursor.
 */
class DeviceSyncStore(context: Context) {

    private val root = File(context.applicationContext.filesDir, "device-sync")
    private val outbox = File(root, "outbox")
    private val configFile = File(root, "config.json")
    private val hashesFile = File(root, "hashes.json")
    private val acksFile = File(root, "acks.json")
    private val categoriesFile = File(root, "categories.json")
    private val settingHashesFile = File(root, "setting-hashes.json")
    private val appliedFile = File(root, "applied.json")
    private val mutex = Mutex()

    init {
        outbox.mkdirs()
    }

    suspend fun loadConfig(): DeviceSyncConfig = withLock {
        read(configFile)?.toDeviceSyncConfig() ?: DeviceSyncConfig()
    }

    suspend fun saveConfig(config: DeviceSyncConfig) = withLock { write(configFile, config.toJson()) }

    suspend fun deviceId(): String = withLock {
        val current = read(configFile)?.toDeviceSyncConfig() ?: DeviceSyncConfig()
        val id = current.deviceId.ifBlank { UUID.randomUUID().toString() }
        if (id != current.deviceId) {
            write(configFile, current.copy(deviceId = id).toJson())
        }
        id
    }

    suspend fun loadHashes(): Map<String, String> = withLock {
        runCatching {
            deviceSyncJson.decodeFromString<Map<String, String>>(read(hashesFile).orEmpty())
        }.getOrDefault(emptyMap())
    }

    suspend fun saveHashes(hashes: Map<String, String>) = withLock {
        write(hashesFile, deviceSyncJson.encodeToString(hashes))
    }

    /** Per-entry category versions: positive = added, negative = removed. */
    suspend fun loadCategoryVersions(): Map<String, Map<String, Long>> = withLock {
        runCatching {
            deviceSyncJson.decodeFromString<Map<String, Map<String, Long>>>(read(categoriesFile).orEmpty())
        }.getOrDefault(emptyMap())
    }

    suspend fun saveCategoryVersions(versions: Map<String, Map<String, Long>>) = withLock {
        write(categoriesFile, deviceSyncJson.encodeToString(versions))
    }

    suspend fun loadSettingHashes(): Map<String, String> = withLock {
        runCatching {
            deviceSyncJson.decodeFromString<Map<String, String>>(read(settingHashesFile).orEmpty())
        }.getOrDefault(emptyMap())
    }

    suspend fun saveSettingHashes(hashes: Map<String, String>) = withLock {
        write(settingHashesFile, deviceSyncJson.encodeToString(hashes))
    }

    /** Newest remote timestamp applied to each entry, so old events cannot overwrite new data. */
    suspend fun loadApplied(): Map<String, Long> = withLock {
        runCatching {
            deviceSyncJson.decodeFromString<Map<String, Long>>(read(appliedFile).orEmpty())
        }.getOrDefault(emptyMap())
    }

    suspend fun saveApplied(applied: Map<String, Long>) = withLock {
        write(appliedFile, deviceSyncJson.encodeToString(applied))
    }

    suspend fun outboxEvents(): List<DeviceSyncEvent> = withLock {
        outbox.listFiles { file -> file.extension == "json" }
            .orEmpty()
            .mapNotNull { runCatching { read(it)?.toDeviceSyncEvent() }.getOrNull() }
            .sortedBy { it.seq }
    }

    suspend fun enqueue(event: DeviceSyncEvent) = withLock {
        write(File(outbox, "${event.opId}.json"), event.toJson())
    }

    suspend fun acknowledge(peerId: String, opIds: List<String>) = withLock {
        if (opIds.isEmpty()) return@withLock
        val config = read(configFile)?.toDeviceSyncConfig() ?: DeviceSyncConfig()
        val peers = config.peers.map { it.deviceId }.toSet()
        val acks = runCatching {
            deviceSyncJson.decodeFromString<MutableMap<String, MutableMap<String, Long>>>(
                read(acksFile).orEmpty(),
            )
        }.getOrDefault(mutableMapOf())

        val now = System.currentTimeMillis()
        val peerAcks = acks.getOrPut(peerId) { mutableMapOf() }
        opIds.forEach { peerAcks[it] = now }

        val liveOps = outbox.listFiles { file -> file.extension == "json" }
            .orEmpty()
            .map { it.nameWithoutExtension }
            .toSet()
        // Forget peers that were unpaired and ACKs for payloads that no longer exist.
        acks.keys.retainAll { it in peers || it == peerId }
        acks.values.forEach { it.keys.retainAll { op -> op in liveOps } }

        val ackedByAll = liveOps.filter { op -> peers.isNotEmpty() && peers.all { acks[it]?.containsKey(op) == true } }
        ackedByAll.forEach { File(outbox, "$it.json").delete() }
        acks.values.forEach { it.keys.removeAll(ackedByAll.toSet()) }
        write(acksFile, deviceSyncJson.encodeToString(acks))
    }

    suspend fun pendingCount(): Int = withLock {
        outbox.listFiles { file -> file.extension == "json" }.orEmpty().size
    }

    suspend fun forgetPeer(peerId: String) = withLock {
        val config = read(configFile)?.toDeviceSyncConfig() ?: DeviceSyncConfig()
        write(
            configFile,
            config.copy(peers = config.peers.filterNot { it.deviceId == peerId }).toJson(),
        )
        val acks = runCatching {
            deviceSyncJson.decodeFromString<MutableMap<String, MutableMap<String, Long>>>(
                read(acksFile).orEmpty(),
            )
        }.getOrDefault(mutableMapOf())
        acks.remove(peerId)
        write(acksFile, deviceSyncJson.encodeToString(acks))
    }

    suspend fun fingerprint(entry: DeviceSyncEntry): String = withContext(Dispatchers.Default) {
        val normalized = entry.copy(
            updatedAt = 0L,
            chapters = entry.chapters.map { it.copy(updatedAt = 0L) },
        )
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(deviceSyncJson.encodeToString(normalized).toByteArray())
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private suspend fun <T> withLock(block: () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block() }
    }

    private fun read(file: File): String? = if (file.exists()) file.readText() else null

    private fun write(file: File, text: String) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.writeText(text)
            temp.delete()
        }
    }
}
