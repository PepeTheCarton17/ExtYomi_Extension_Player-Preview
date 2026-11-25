package eu.kanade.tachiyomi.util.standalone.sync

import eu.kanade.domain.ui.StandaloneScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StandaloneSyncMergeTest {

    private fun payload(
        instanceId: String,
        revision: Long = 0L,
        entries: List<SyncEntry> = emptyList(),
    ) = StandaloneSyncPayload(
        instanceId = instanceId,
        sourceId = SOURCE_ID,
        kind = StandaloneScope.KIND_ANIME,
        revision = revision,
        entries = entries,
    )

    @Test
    fun `newer entry wins and revision advances`() {
        val older = SyncEntry(
            url = "/a",
            title = "Old title",
            favorite = true,
            updatedAt = 100L,
            categories = listOf("Old category"),
        )
        val newer = older.copy(
            title = "New title",
            updatedAt = 200L,
            categories = listOf("New category"),
        )

        val result = StandaloneSyncMerge.merge(
            current = payload("host", revision = 4L, entries = listOf(older)),
            incoming = payload("host", revision = 7L, entries = listOf(newer)),
            ownerIndex = SyncOwnerIndex(),
        )

        assertEquals(listOf(newer), result.payload.entries)
        assertEquals(8L, result.payload.revision)
        assertEquals(
            "host",
            result.owners[SyncOwnerIndex.key(StandaloneScope.KIND_ANIME, SOURCE_ID, "/a")],
        )
    }

    @Test
    fun `an instance cannot overwrite entry owned by another standalone`() {
        val ownedKey = SyncOwnerIndex.key(StandaloneScope.KIND_ANIME, SOURCE_ID, "/a")
        val stolen = SyncEntry(
            url = "/a",
            title = "Stolen",
            favorite = true,
            updatedAt = 999L,
        )
        val own = SyncEntry(
            url = "/b",
            title = "Own",
            favorite = true,
            updatedAt = 100L,
        )

        val result = StandaloneSyncMerge.merge(
            current = null,
            incoming = payload("app-b", entries = listOf(stolen, own)),
            ownerIndex = SyncOwnerIndex(mapOf(ownedKey to "app-a")),
        )

        assertEquals(listOf(own), result.payload.entries)
        assertEquals("app-a", result.owners[ownedKey])
        assertEquals(
            "app-b",
            result.owners[SyncOwnerIndex.key(StandaloneScope.KIND_ANIME, SOURCE_ID, "/b")],
        )
    }

    @Test
    fun `a category disappears with its last entry`() {
        val categorized = SyncEntry(
            url = "/a",
            title = "A",
            favorite = true,
            updatedAt = 100L,
            categories = listOf("Shonen"),
        )
        val removed = categorized.copy(
            favorite = false,
            categories = emptyList(),
            updatedAt = 200L,
        )

        val result = StandaloneSyncMerge.merge(
            current = payload("host", entries = listOf(categorized)),
            incoming = payload("host", entries = listOf(removed)),
            ownerIndex = SyncOwnerIndex(),
        )

        val entry = result.payload.entries.single()
        assertFalse(entry.favorite)
        assertTrue(entry.categories.isEmpty())
        assertTrue(result.payload.entries.flatMap { it.categories }.isEmpty())
    }

    private companion object {
        const val SOURCE_ID = 123L
    }
}
