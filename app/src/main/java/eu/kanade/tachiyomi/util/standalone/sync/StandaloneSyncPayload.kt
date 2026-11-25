package eu.kanade.tachiyomi.util.standalone.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal val standaloneSyncJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Snapshot exchanged between ExtYomi and one generated standalone app. */
@Serializable
data class StandaloneSyncPayload(
    val schema: Int = SCHEMA,
    val instanceId: String,
    val sourceId: Long,
    val kind: String,
    val extensionPkg: String = "",
    val generatedAt: Long = 0L,
    val revision: Long = 0L,
    /** SAF tree shared by both apps so the same downloaded files are visible. */
    val downloadsRootUri: String? = null,
    val entries: List<SyncEntry> = emptyList(),
) {
    companion object {
        const val SCHEMA = 1
    }
}

/**
 * Entries are keyed by source URL, not by the local database id, because each APK has its own
 * private database. Category names are used instead of ids for the same reason.
 */
@Serializable
data class SyncEntry(
    val url: String,
    val title: String,
    val thumbnailUrl: String? = null,
    val favorite: Boolean,
    val isNovel: Boolean = false,
    val dateAdded: Long = 0L,
    val updatedAt: Long = 0L,
    val categories: List<String> = emptyList(),
    val chapters: List<SyncChapter> = emptyList(),
)

@Serializable
data class SyncChapter(
    val url: String,
    val seen: Boolean = false,
    val bookmark: Boolean = false,
    val position: Long = 0L,
    /** Epoch millis of the last read/watch, 0 when there is no history row. */
    val readAt: Long = 0L,
    val updatedAt: Long = 0L,
)

fun StandaloneSyncPayload.toJson(): String = standaloneSyncJson.encodeToString(this)

fun String.toStandaloneSyncPayload(): StandaloneSyncPayload =
    standaloneSyncJson.decodeFromString(this)
