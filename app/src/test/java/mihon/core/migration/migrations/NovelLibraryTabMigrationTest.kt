package mihon.core.migration.migrations

import eu.kanade.domain.ui.model.NavTab
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NovelLibraryTabMigrationTest {

    @Test
    fun `novel library is enabled with a blank order`() {
        val (enabled, order) = enableNovelTab(
            enabled = setOf(NavTab.Home.prefKey, NavTab.Anime.prefKey, NavTab.Manga.prefKey),
            order = "",
        )

        assertTrue(NavTab.Novel.prefKey in enabled)
        assertEquals("", order)
    }

    @Test
    fun `novel library is inserted after manga in a custom order`() {
        val (_, order) = enableNovelTab(
            enabled = setOf(NavTab.Anime.prefKey, NavTab.Manga.prefKey),
            order = "home,anime,manga,updates,browse",
        )

        assertEquals("home,anime,manga,novel,updates,browse", order)
    }

    @Test
    fun `existing novel preference is left untouched`() {
        val (enabled, order) = enableNovelTab(
            enabled = setOf(NavTab.Manga.prefKey, NavTab.Novel.prefKey),
            order = "home,manga,novel",
        )

        assertEquals(setOf(NavTab.Manga.prefKey, NavTab.Novel.prefKey), enabled)
        assertEquals("home,manga,novel", order)
    }
}
