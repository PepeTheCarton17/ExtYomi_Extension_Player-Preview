package eu.kanade.tachiyomi.util.devicesync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** An event received from a peer, kept until it has been applied and acknowledged. */
@Serializable
internal data class DeviceIncomingEvent(
    val from: String,
    val event: DeviceSyncEvent,
    val receivedAt: Long = 0L,
)

/**
 * App-private transport files. A delta event is deleted only when every paired device has sent an
 * ACK for it; until then the outbox keeps the payload and a small per-peer ACK cursor.
 */
class DeviceSyncStore(context: Context) {

    private val root = File(context.applicationContext.filesDir, "device-sync")
    private val outbox = File(root, "outbox")
    private val inbox = File(root, "inbox")
    private val configFile = File(root, "config.json")
    private val hashesFile = File(root, "hashes.json")
    private val acksFile = File(root, "acks.json")
    private val pendingAcksFile = File(root, "pending-acks.json")
    private val categoriesFile = File(root, "categories.json")
    private val settingHashesFile = File(root, "setting-hashes.json")
    private val appliedFile = File(root, "applied.json")
    private val cursorsFile = File(root, "outbox-cursors.json")
    private val mutex = Mutex()

    init {
        outbox.mkdirs()
        inbox.mkdirs()
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

    /**
     * Reads one bounded batch. The cursor is carried by the event itself, not by its file: ACKs
     * delete outbox files as soon as every peer confirms, which made a file-name cursor restart
     * from the beginning and never reach later events.
     */
    suspend fun outboxBatch(
        cursor: OutboxCursor?,
        maxEvents: Int,
        maxChars: Int,
    ): List<DeviceSyncEvent> = withLock {
        outbox.listFiles { file -> file.extension == "json" }
            .orEmpty()
            .mapNotNull { file -> runCatching { read(file)?.toDeviceSyncEvent() }.getOrNull() }
            .sortedWith(compareBy<DeviceSyncEvent> { it.createdAt }.thenBy { it.opId })
            .after(cursor)
            .nextBatch(maxEvents, maxChars)
    }

    suspend fun outboxCursor(peerId: String): OutboxCursor? = withLock { readCursors()[peerId] }

    suspend fun saveOutboxCursor(peerId: String, cursor: OutboxCursor) = withLock {
        val cursors = readCursors()
        cursors[peerId] = cursor
        write(cursorsFile, deviceSyncJson.encodeToString(cursors))
    }

    private fun readCursors(): MutableMap<String, OutboxCursor> = runCatching {
        deviceSyncJson.decodeFromString<Map<String, OutboxCursor>>(read(cursorsFile).orEmpty())
            .toMutableMap()
    }.getOrDefault(mutableMapOf())

    suspend fun enqueue(event: DeviceSyncEvent) = withLock {
        write(File(outbox, "${event.opId}.json"), event.toJson())
    }

    /** Remote events are persisted before they are applied, so a crash cannot lose changes. */
    suspend fun enqueueIncoming(from: String, events: List<DeviceSyncEvent>) = withLock {
        if (from.isBlank()) return@withLock
        events.forEach { event ->
            val wrapper = DeviceIncomingEvent(from, event, System.currentTimeMillis())
            write(File(inbox, "${event.opId}.json"), deviceSyncJson.encodeToString(wrapper))
        }
    }

    internal suspend fun inboxBatch(maxEvents: Int): List<DeviceIncomingEvent> = withLock {
        inbox.listFiles { file -> file.extension == "json" }
            .orEmpty()
            .sortedBy { it.lastModified() }
            .take(maxEvents)
            .mapNotNull {
                runCatching {
                    read(it)?.let { json -> deviceSyncJson.decodeFromString<DeviceIncomingEvent>(json) }
                }.getOrNull()
            }
    }

    suspend fun dropIncoming(opIds: List<String>) = withLock {
        opIds.forEach { File(inbox, "$it.json").delete() }
    }

    suspend fun inboxCount(): Int = withLock {
        inbox.listFiles { file -> file.extension == "json" }.orEmpty().size
    }

    /** ACKs that could not be delivered are retried on the next sync tick. */
    suspend fun addPendingAcks(peerId: String, opIds: List<String>) = withLock {
        if (peerId.isBlank() || opIds.isEmpty()) return@withLock
        val acks = readPendingAcks()
        val bucket = acks.getOrPut(peerId) { mutableListOf() }
        opIds.forEach { if (it !in bucket) bucket += it }
        writePendingAcks(acks)
    }

    suspend fun pendingAcks(peerId: String, limit: Int): List<String> = withLock {
        readPendingAcks()[peerId].orEmpty().take(limit)
    }

    suspend fun removePendingAcks(peerId: String, opIds: List<String>) = withLock {
        if (opIds.isEmpty()) return@withLock
        val acks = readPendingAcks()
        val bucket = acks[peerId] ?: return@withLock
        bucket.removeAll(opIds.toSet())
        if (bucket.isEmpty()) acks.remove(peerId) else acks[peerId] = bucket
        writePendingAcks(acks)
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

    /** Local changes that are still waiting for an ACK from every paired device. */
    suspend fun outgoingPending(): Int = withLock {
        outbox.listFiles { file -> file.extension == "json" }.orEmpty().size
    }

    private fun readPendingAcks(): MutableMap<String, MutableList<String>> {
        val decoded = runCatching {
            deviceSyncJson.decodeFromString<Map<String, List<String>>>(read(pendingAcksFile).orEmpty())
        }.getOrDefault(emptyMap())
        return decoded.mapValues { it.value.toMutableList() }.toMutableMap()
    }

    private fun writePendingAcks(acks: Map<String, List<String>>) =
        write(pendingAcksFile, deviceSyncJson.encodeToString(acks))

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
        val pending = readPendingAcks()
        pending.remove(peerId)
        writePendingAcks(pending)
        val cursors = readCursors()
        cursors.remove(peerId)
        write(cursorsFile, deviceSyncJson.encodeToString(cursors))
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
