package eu.kanade.tachiyomi.util.standalone.sync

import kotlinx.serialization.Serializable

/**
 * Maps `kind:sourceId:url` to the one standalone instance that owns that entry. This is the
 * isolation barrier between two standalones generated for the same extension: an entry already
 * claimed by one instance can never be pulled or overwritten by another one.
 */
@Serializable
data class SyncOwnerIndex(val owners: Map<String, String> = emptyMap()) {
    companion object {
        fun key(kind: String, sourceId: Long, url: String): String = "$kind:$sourceId:$url"
    }
}

data class SyncMergeResult(
    val payload: StandaloneSyncPayload,
    val owners: Map<String, String>,
)

/**
 * Pure merge used by both the host provider and the standalone client.
 *
 * Conflict rule: per entry LWW by [SyncEntry.updatedAt]; a tombstone (an entry whose
 * [SyncEntry.favorite] is false and whose categories are empty) carries no library membership.
 * Categories are derived from surviving entries, so a category disappears as soon as its last
 * in-scope entry is removed.
 */
object StandaloneSyncMerge {

    fun merge(
        current: StandaloneSyncPayload?,
        incoming: StandaloneSyncPayload,
        ownerIndex: SyncOwnerIndex,
    ): SyncMergeResult {
        require(incoming.schema == StandaloneSyncPayload.SCHEMA) { "Unsupported sync schema" }
        val owners = ownerIndex.owners.toMutableMap()
        val merged = linkedMapOf<String, SyncEntry>()

        current?.entries?.forEach { entry ->
            val key = SyncOwnerIndex.key(incoming.kind, incoming.sourceId, entry.url)
            if (owners[key] == incoming.instanceId || !owners.containsKey(key)) {
                merged[entry.url] = entry
                owners.putIfAbsent(key, current.instanceId)
            }
        }

        incoming.entries.forEach { entry ->
            val key = SyncOwnerIndex.key(incoming.kind, incoming.sourceId, entry.url)
            val owner = owners[key]
            if (owner != null && owner != incoming.instanceId) return@forEach
            owners[key] = incoming.instanceId
            val existing = merged[entry.url]
            val winner = if (existing == null || entry.updatedAt >= existing.updatedAt) {
                entry
            } else {
                existing
            }
            val other = if (winner === entry) existing else entry
            merged[entry.url] = winner.withMissingChapterMetadataFrom(other)
        }

        val payload = incoming.copy(
            generatedAt = System.currentTimeMillis(),
            revision = maxOf(current?.revision ?: 0L, incoming.revision) + 1L,
            entries = merged.values
                .asSequence()
                .filter { it.url.isNotBlank() }
                .sortedBy { it.url }
                .toList(),
        )
        return SyncMergeResult(payload, owners.toMap())
    }
}
