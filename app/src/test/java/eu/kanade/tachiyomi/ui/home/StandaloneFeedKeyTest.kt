package eu.kanade.tachiyomi.ui.home

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class StandaloneFeedKeyTest {

    private fun item(
        id: Long,
        isAnime: Boolean,
        isNovel: Boolean = false,
        episodeId: Long? = null,
        chapterId: Long? = null,
    ) = StandaloneFeedScreenModel.ContinueItem(
        id = id,
        episodeId = episodeId,
        chapterId = chapterId,
        title = "title",
        subtitle = "subtitle",
        coverUrl = null,
        isAnime = isAnime,
        isNovel = isNovel,
    )

    @Test
    fun `anime and manga entries with the same database id get different keys`() {
        assertNotEquals(item(id = 1L, isAnime = true).feedKey(), item(id = 1L, isAnime = false).feedKey())
    }

    @Test
    fun `different history entries of the same anime get different keys`() {
        assertNotEquals(
            item(id = 1L, isAnime = true, episodeId = 10L).feedKey(),
            item(id = 1L, isAnime = true, episodeId = 11L).feedKey(),
        )
    }

    @Test
    fun `novel and manga entries with the same database id get different keys`() {
        assertNotEquals(
            item(id = 1L, isAnime = false, isNovel = true, chapterId = 5L).feedKey(),
            item(id = 1L, isAnime = false, isNovel = false, chapterId = 5L).feedKey(),
        )
    }

    @Test
    fun `equal entries keep a stable key`() {
        assertEquals(
            item(id = 7L, isAnime = true, episodeId = 3L).feedKey(),
            item(id = 7L, isAnime = true, episodeId = 3L).feedKey(),
        )
    }
}
