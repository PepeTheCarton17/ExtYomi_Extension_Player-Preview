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

    @Test
    fun `a newer tombstone removes the entry and its categories`() {
        val existing = SyncEntry(
            url = "/a",
            title = "A",
            favorite = true,
            updatedAt = 100L,
            categories = listOf("Shonen"),
        )
        val tombstone = SyncEntry(
            url = "/a",
            title = "A",
            favorite = false,
            categories = emptyList(),
            updatedAt = 300L,
        )

        val result = StandaloneSyncMerge.merge(
            current = payload("host", entries = listOf(existing)),
            incoming = payload("host", entries = listOf(tombstone)),
            ownerIndex = SyncOwnerIndex(),
        )

        val entry = result.payload.entries.single()
        assertTrue(entry.isTombstone)
        assertFalse(entry.favorite)
        assertTrue(entry.categories.isEmpty())
    }

    @Test
    fun `bookkeeping timestamps do not count as a real edit`() {
        val original = SyncEntry(
            url = "/a",
            title = "A",
            favorite = true,
            updatedAt = 100L,
            categories = listOf("Shonen"),
        )

        assertTrue(original.sameContentAs(original.copy(updatedAt = 999L)))
        assertFalse(original.sameContentAs(original.copy(title = "Renamed", updatedAt = 200L)))
        assertFalse(original.sameContentAs(original.copy(favorite = false, updatedAt = 200L)))
    }

    @Test
    fun `a chapter title change counts as a real edit`() {
        val chapter = SyncChapter(
            url = "/media/serie/24",
            name = "Episodio 24",
            readAt = 1000L,
            dateUpload = 1000L,
        )
        val original = SyncEntry(
            url = "/a",
            title = "A",
            favorite = true,
            chapters = listOf(chapter),
        )

        assertTrue(original.sameContentAs(original.copy(updatedAt = 999L)))
        assertFalse(
            original.sameContentAs(
                original.copy(chapters = listOf(chapter.copy(name = "/media/serie/24"))),
            ),
        )
        assertFalse(
            original.sameContentAs(
                original.copy(chapters = listOf(chapter.copy(dateUpload = 2000L))),
            ),
        )
    }

    @Test
    fun `chapter identity falls back to name when the url is blank`() {
        assertEquals("Episodio 24", SyncChapter(url = "", name = "Episodio 24").itemKey)
        assertEquals("/media/serie/24", SyncChapter(url = "/media/serie/24").itemKey)
    }

    @Test
    fun `only path titles left by an old sync are repaired`() {
        val incoming = SyncChapter(url = "/media/serie/24", name = "Episodio 24")

        assertEquals("Episodio 24", incoming.repairedName("/media/serie/24", "/media/serie/24"))
        assertEquals("Episodio 24", incoming.repairedName("", "/media/serie/24"))
        assertEquals(null, incoming.repairedName("Mi capítulo", "/media/serie/24"))
        assertEquals(
            null,
            SyncChapter(url = "/media/serie/24")
                .repairedName("/media/serie/24", "/media/serie/24"),
        )
    }

    @Test
    fun `chapter metadata survives serialization and old snapshots still decode`() {
        val payload = payload(
            "host",
            entries = listOf(
                SyncEntry(
                    url = "/a",
                    title = "A",
                    favorite = true,
                    chapters = listOf(
                        SyncChapter(
                            url = "/a/1",
                            name = "Episodio 1",
                            readAt = 123L,
                            dateUpload = 1234L,
                        ),
                    ),
                ),
            ),
        )

        val roundTrip = payload.toJson().toStandaloneSyncPayload()
        assertEquals(
            "Episodio 1",
            roundTrip.entries.single().chapters.single().name,
        )
        assertEquals(123L, roundTrip.entries.single().chapters.single().readAt)
        assertEquals(1234L, roundTrip.entries.single().chapters.single().dateUpload)

        val oldSnapshot = """
            {"schema":1,"instanceId":"host","sourceId":123,"kind":"anime","entries":[
            {"url":"/a","title":"A","favorite":true,"chapters":[{"url":"/a/1","readAt":123}]}]}
        """.trimIndent().toStandaloneSyncPayload()
        assertEquals("", oldSnapshot.entries.single().chapters.single().name)
        assertEquals(0L, oldSnapshot.entries.single().chapters.single().dateUpload)
    }

    @Test
    fun `merge keeps chapter metadata from an older snapshot without it`() {
        val key = SyncOwnerIndex.key(StandaloneScope.KIND_ANIME, SOURCE_ID, "/a")
        val named = SyncEntry(
            url = "/a",
            title = "A",
            favorite = true,
            updatedAt = 100L,
            chapters = listOf(
                SyncChapter(url = "/a/1", name = "Episodio 1", dateUpload = 1234L),
            ),
        )
        val old = SyncEntry(
            url = "/a",
            title = "A",
            favorite = true,
            updatedAt = 300L,
            chapters = listOf(SyncChapter(url = "/a/1")),
        )

        val result = StandaloneSyncMerge.merge(
            current = payload("app-b", revision = 4L, entries = listOf(named)),
            incoming = payload("app-b", revision = 5L, entries = listOf(old)),
            ownerIndex = SyncOwnerIndex(mapOf(key to "app-b")),
        )

        val chapter = result.payload.entries.single().chapters.single()
        assertEquals("Episodio 1", chapter.name)
        assertEquals(1234L, chapter.dateUpload)
    }

    private companion object {
        const val SOURCE_ID = 123L
    }
}
