package eu.kanade.tachiyomi.util.devicesync

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal val deviceSyncJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Stable key for an entry across devices, independent of local database ids. */
fun deviceEntryKey(kind: String, sourceId: Long, url: String): String = "$kind:$sourceId:$url"

@Serializable
data class DeviceSyncChapter(
    val url: String = "",
    val name: String = "",
    val seen: Boolean = false,
    val bookmark: Boolean = false,
    val fillermark: Boolean = false,
    val count: Long = 0L,
    val scanlator: String = "",
    val position: Long = 0L,
    val readAt: Long = 0L,
    /** When the chapter was discovered locally; drives the "Actualizaciones" list on the peer. */
    val dateFetch: Long = 0L,
    val dateUpload: Long = 0L,
    val updatedAt: Long = 0L,
) {
    val itemKey: String get() = url.ifBlank { name }
}

/** One library entry. `deleted` is a tombstone that removes library membership on the peer. */
@Serializable
data class DeviceSyncEntry(
    val key: String,
    val kind: String,
    val sourceId: Long,
    val url: String,
    val title: String = "",
    val thumbnailUrl: String = "",
    val favorite: Boolean = false,
    val dateAdded: Long = 0L,
    val lastUpdate: Long = 0L,
    val status: Long = -1L,
    val updatedAt: Long = 0L,
    val categories: List<String> = emptyList(),
    /** Positive timestamp = category added, negative = removed. LWW per category name. */
    val categoryVersions: Map<String, Long> = emptyMap(),
    val deleted: Boolean = false,
    val chapters: List<DeviceSyncChapter> = emptyList(),
)

/** Last-write-wins per filter key; `deleted` is the tombstone. */
@Serializable
data class DeviceSyncFilter(
    val key: String,
    val value: String = "",
    val updatedAt: Long = 0L,
    val deleted: Boolean = false,
)

/** Typed value carried by an optional settings filter. */
@Serializable
data class DeviceSyncSettingValue(
    val type: String,
    val value: String,
)

@Serializable
data class DeviceSyncSnapshot(
    val deviceId: String,
    val generatedAt: Long = 0L,
    val entries: List<DeviceSyncEntry> = emptyList(),
    val filters: List<DeviceSyncFilter> = emptyList(),
)

/** Small delta record kept in the outbox until every known peer acknowledges it. */
@Serializable
data class DeviceSyncEvent(
    val opId: String,
    val author: String,
    val seq: Long = 0L,
    val createdAt: Long = 0L,
    val entry: DeviceSyncEntry? = null,
    val filter: DeviceSyncFilter? = null,
    val snapshot: DeviceSyncSnapshot? = null,
)

/**
 * Persistent send cursor. It uses the event itself instead of its outbox file name: ACKs delete
 * those files as soon as every peer confirms, so a file-based cursor restarted from zero and kept
 * resending the same batch forever.
 */
@Serializable
internal data class OutboxCursor(val createdAt: Long, val opId: String)

/** Events newer than the cursor, with a stable tie-breaker for events created in the same ms. */
internal fun List<DeviceSyncEvent>.after(cursor: OutboxCursor?): List<DeviceSyncEvent> =
    if (cursor == null) {
        this
    } else {
        filter {
            it.createdAt > cursor.createdAt ||
                (it.createdAt == cursor.createdAt && it.opId > cursor.opId)
        }
    }

/** First bounded slice of a list, used for both tests and the durable outbox. */
internal fun List<DeviceSyncEvent>.nextBatch(maxEvents: Int, maxChars: Int): List<DeviceSyncEvent> {
    val batch = mutableListOf<DeviceSyncEvent>()
    var chars = 0
    for (event in this) {
        val size = runCatching { event.toJson().length }.getOrDefault(0)
        if (batch.isNotEmpty() && (batch.size >= maxEvents || chars + size > maxChars)) break
        batch += event
        chars += size
    }
    return batch
}

/**
 * Splits events before serialization. A single envelope with thousands of entries made
 * `Json.encodeToString` try to allocate ~129 MB in one array and crashed the app.
 */
internal fun List<DeviceSyncEvent>.batched(maxEvents: Int, maxChars: Int): List<List<DeviceSyncEvent>> {
    val batches = mutableListOf<List<DeviceSyncEvent>>()
    val current = mutableListOf<DeviceSyncEvent>()
    var chars = 0
    forEach { event ->
        val size = runCatching { event.toJson().length }.getOrDefault(0)
        if (current.isNotEmpty() && (current.size >= maxEvents || chars + size > maxChars)) {
            batches += current.toList()
            current.clear()
            chars = 0
        }
        current += event
        chars += size
    }
    if (current.isNotEmpty()) batches += current.toList()
    return batches
}

/** Pairing metadata travels in the clear: it contains no secret and no library data. */
@Serializable
data class DevicePairingMessage(
    val type: String,
    val from: String,
    val deviceName: String = "",
    val deviceModel: String = "",
    val endpoint: String = "",
    val endpoints: List<String> = emptyList(),
    val code: String = "",
    val relayUrl: String = "",
    val to: String = "",
    val confirmed: Boolean = false,
    val sentAt: Long = 0L,
)

/** Every library payload is AES-GCM encrypted before it leaves the device. */
@Serializable
data class DeviceSyncEnvelope(
    val type: String,
    val from: String,
    val to: String = "",
    val code: String,
    val iv: String,
    val ciphertext: String,
    val sentAt: Long = 0L,
)

/** Decrypted content of [DeviceSyncEnvelope]. */
@Serializable
data class DeviceSyncMessage(
    val type: String,
    val from: String,
    val to: String = "",
    val deviceName: String = "",
    val deviceModel: String = "",
    val endpoint: String = "",
    /** All LAN addresses of the sender, so replies can skip an unreachable adapter. */
    val endpoints: List<String> = emptyList(),
    val events: List<DeviceSyncEvent> = emptyList(),
    val acks: List<String> = emptyList(),
    val snapshot: DeviceSyncSnapshot? = null,
    val sentAt: Long = 0L,
)

@Serializable
data class DeviceSyncPeer(
    val deviceId: String,
    val name: String,
    val model: String,
    val endpoint: String,
    /** Kept alongside [endpoint] so later syncs can try every known address. */
    val endpoints: List<String> = emptyList(),
    val code: String,
    val trustedAt: Long = 0L,
    val lastSeen: Long = 0L,
)

@Serializable
data class DeviceSyncConfig(
    val deviceId: String = "",
    val code: String = "",
    val password: String = "",
    val relayUrl: String = "",
    val syncDeviceSettings: Boolean = false,
    val peers: List<DeviceSyncPeer> = emptyList(),
    /**
     * True only after the user published this device. A leftover code/password must never turn a
     * joiner (or a device whose peers were forgotten) into a QR publisher on the next restart.
     */
    val hosting: Boolean = false,
    /**
     * An unfinished join survives a restart. The joiner must be able to get back to the password
     * screen instead of silently appearing paired while the host never verified the secret.
     */
    val joinTarget: DevicePairingMessage? = null,
)

fun DeviceSyncSnapshot.toJson(): String = deviceSyncJson.encodeToString(this)
fun String.toDeviceSyncSnapshot(): DeviceSyncSnapshot = deviceSyncJson.decodeFromString(this)

fun DeviceSyncEvent.toJson(): String = deviceSyncJson.encodeToString(this)
fun String.toDeviceSyncEvent(): DeviceSyncEvent = deviceSyncJson.decodeFromString(this)

fun DeviceSyncMessage.toJson(): String = deviceSyncJson.encodeToString(this)
fun String.toDeviceSyncMessage(): DeviceSyncMessage = deviceSyncJson.decodeFromString(this)

fun DeviceSyncEnvelope.toJson(): String = deviceSyncJson.encodeToString(this)
fun String.toDeviceSyncEnvelope(): DeviceSyncEnvelope = deviceSyncJson.decodeFromString(this)

fun DevicePairingMessage.toJson(): String = deviceSyncJson.encodeToString(this)
fun String.toDevicePairingMessage(): DevicePairingMessage = deviceSyncJson.decodeFromString(this)

fun DeviceSyncConfig.toJson(): String = deviceSyncJson.encodeToString(this)
fun String.toDeviceSyncConfig(): DeviceSyncConfig = deviceSyncJson.decodeFromString(this)
