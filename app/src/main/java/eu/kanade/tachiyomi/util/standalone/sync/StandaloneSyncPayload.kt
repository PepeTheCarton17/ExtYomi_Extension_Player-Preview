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
    /** Display title. Without it a synced episode is shown as its raw `/media/...` path. */
    val name: String = "",
    val seen: Boolean = false,
    val bookmark: Boolean = false,
    val position: Long = 0L,
    /** Epoch millis of the last read/watch, 0 when there is no history row. */
    val readAt: Long = 0L,
    /** Epoch millis of the release date; -1 when the source does not provide one. */
    val dateUpload: Long = 0L,
    val updatedAt: Long = 0L,
)

/** A tombstone means "this entry was removed from the other app's library/categories". */
internal val SyncEntry.isTombstone: Boolean
    get() = !favorite && categories.isEmpty() && chapters.isEmpty()

/**
 * Episodes and chapters from local sources can have an empty URL; in that case their name is
 * the stable identity. History must use the same key as the payload itself.
 */
internal val SyncChapter.itemKey: String
    get() = url.ifBlank { name }

/**
 * Repairs a title that an older sync stored as the raw path/URL. Returns null when the current
 * title looks genuine, so user-edited names are never overwritten.
 */
internal fun SyncChapter.repairedName(currentName: String, currentUrl: String): String? {
    val incomingName = name.takeIf { it.isNotBlank() } ?: return null
    return incomingName.takeIf {
        currentName.isBlank() || currentName == currentUrl || currentName == url
    }
}

/**
 * Older snapshots have neither chapter titles nor release dates. Keep the metadata we already
 * know instead of replacing it with an empty value when that side wins the LWW merge.
 */
internal fun SyncEntry.withMissingChapterMetadataFrom(other: SyncEntry?): SyncEntry {
    if (other == null) return this
    val knownByKey = other.chapters.associateBy { it.itemKey }
    val needsRepair = chapters.any { chapter ->
        val known = knownByKey[chapter.itemKey]
        (chapter.name.isBlank() && !known?.name.isNullOrBlank()) ||
            (chapter.dateUpload <= 0L && (known?.dateUpload ?: 0L) > 0L)
    }
    if (!needsRepair) return this
    return copy(
        chapters = chapters.map { chapter ->
            val known = knownByKey[chapter.itemKey] ?: return@map chapter
            val name = if (chapter.name.isBlank()) known.name else chapter.name
            val dateUpload = if (chapter.dateUpload <= 0L && known.dateUpload > 0L) {
                known.dateUpload
            } else {
                chapter.dateUpload
            }
            chapter.copy(name = name, dateUpload = dateUpload)
        },
    )
}

/** Ignores the bookkeeping timestamps so a snapshot can tell real edits from no-op syncs. */
internal fun SyncEntry.sameContentAs(other: SyncEntry): Boolean =
    title == other.title &&
        thumbnailUrl == other.thumbnailUrl &&
        favorite == other.favorite &&
        isNovel == other.isNovel &&
        dateAdded == other.dateAdded &&
        categories == other.categories &&
        chapters.size == other.chapters.size &&
        chapters.zip(other.chapters).all { (left, right) ->
            left.url == right.url &&
                left.name == right.name &&
                left.seen == right.seen &&
                left.bookmark == right.bookmark &&
                left.position == right.position &&
                left.readAt == right.readAt &&
                left.dateUpload == right.dateUpload
        }

fun StandaloneSyncPayload.toJson(): String = standaloneSyncJson.encodeToString(this)

fun String.toStandaloneSyncPayload(): StandaloneSyncPayload =
    standaloneSyncJson.decodeFromString(this)
