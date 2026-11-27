package eu.kanade.tachiyomi.util.devicesync

import kotlin.math.abs

/**
 * Conflict rules shared by both devices:
 * - progress is monotonic (a chapter seen/bookmarked on either device stays seen/bookmarked),
 * - favorites and tombstones are last-write-wins by timestamp,
 * - categories are per-name last-write-wins so a removal on one device survives an older add,
 * - filters are last-write-wins by key.
 */
object DeviceSyncMerge {

    fun merge(current: DeviceSyncSnapshot?, incoming: DeviceSyncSnapshot): DeviceSyncSnapshot {
        val entries = LinkedHashMap<String, DeviceSyncEntry>()
        current?.entries?.forEach { entries[it.key] = it }
        incoming.entries.forEach { entry ->
            val old = entries[entry.key]
            entries[entry.key] = if (old == null) entry else mergeEntry(old, entry)
        }

        val filters = LinkedHashMap<String, DeviceSyncFilter>()
        current?.filters?.forEach { filters[it.key] = it }
        incoming.filters.forEach { filter ->
            val old = filters[filter.key]
            filters[filter.key] = if (old == null || filter.updatedAt >= old.updatedAt) filter else old
        }

        return DeviceSyncSnapshot(
            deviceId = current?.deviceId ?: incoming.deviceId,
            generatedAt = maxOf(current?.generatedAt ?: 0L, incoming.generatedAt),
            entries = entries.values.toList().sortedBy { it.key },
            filters = filters.values.toList().sortedBy { it.key },
        )
    }

    fun mergeEntry(current: DeviceSyncEntry, incoming: DeviceSyncEntry): DeviceSyncEntry {
        val winner = if (incoming.updatedAt >= current.updatedAt) incoming else current
        val chapters = LinkedHashMap<String, DeviceSyncChapter>()
        current.chapters.forEach { chapters[it.itemKey] = it }
        incoming.chapters.forEach { chapter ->
            val old = chapters[chapter.itemKey]
            chapters[chapter.itemKey] = if (old == null) chapter else mergeChapter(old, chapter)
        }

        val versions = LinkedHashMap<String, Long>()
        current.categoryVersions.forEach { (name, version) -> versions[name] = version }
        incoming.categoryVersions.forEach { (name, version) ->
            if (abs(version) > abs(versions[name] ?: 0L)) versions[name] = version
        }

        return winner.copy(
            dateAdded = listOf(current.dateAdded, incoming.dateAdded)
                .firstOrNull { it > 0L } ?: 0L,
            lastUpdate = maxOf(current.lastUpdate, incoming.lastUpdate),
            updatedAt = maxOf(current.updatedAt, incoming.updatedAt),
            // A category is present only when its newest version is an add.
            categories = versions.filterValues { it > 0L }.keys.toList().sorted(),
            categoryVersions = versions,
            chapters = chapters.values.toList().sortedBy { it.itemKey },
        )
    }

    private fun mergeChapter(
        current: DeviceSyncChapter,
        incoming: DeviceSyncChapter,
    ): DeviceSyncChapter = DeviceSyncChapter(
        url = current.url.ifBlank { incoming.url },
        name = listOf(current.name, incoming.name).firstOrNull { it.isNotBlank() }.orEmpty(),
        seen = current.seen || incoming.seen,
        bookmark = current.bookmark || incoming.bookmark,
        fillermark = current.fillermark || incoming.fillermark,
        count = maxOf(current.count, incoming.count),
        scanlator = current.scanlator.ifBlank { incoming.scanlator },
        position = maxOf(current.position, incoming.position),
        readAt = maxOf(current.readAt, incoming.readAt),
        dateUpload = maxOf(current.dateUpload, incoming.dateUpload),
        updatedAt = maxOf(current.updatedAt, incoming.updatedAt),
    )
}
