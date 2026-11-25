package eu.kanade.domain.ui.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NavTabStandaloneTest {

    @Test
    fun `anime standalone only offers its own tabs`() {
        assertEquals(
            listOf(NavTab.Home, NavTab.Anime, NavTab.Updates, NavTab.History),
            NavTab.allowedInStandalone("anime"),
        )
    }

    @Test
    fun `foreign tabs are dropped and updates stays enabled`() {
        val enabled = NavTab.enabledInStandalone(
            NavTab.DEFAULT + NavTab.History.prefKey + NavTab.Browse.prefKey,
            "anime",
        )

        assertTrue(NavTab.Home.prefKey in enabled)
        assertTrue(NavTab.Anime.prefKey in enabled)
        assertTrue(NavTab.Updates.prefKey in enabled)
        assertTrue(NavTab.History.prefKey in enabled)
        assertFalse(NavTab.Manga.prefKey in enabled)
        assertFalse(NavTab.Novel.prefKey in enabled)
        assertFalse(NavTab.Browse.prefKey in enabled)
    }

    @Test
    fun `library and updates are forced on even when an older build disabled them`() {
        val enabled = NavTab.enabledInStandalone(emptySet(), "manga")

        assertEquals(
            setOf(
                NavTab.Home.prefKey,
                NavTab.Manga.prefKey,
                NavTab.Updates.prefKey,
            ),
            enabled,
        )
    }

    @Test
    fun `each standalone forces only its own library`() {
        val anime = NavTab.enabledInStandalone(emptySet(), "anime")
        val novel = NavTab.enabledInStandalone(emptySet(), "novel")

        assertTrue(NavTab.Anime.prefKey in anime)
        assertFalse(NavTab.Manga.prefKey in anime)
        assertTrue(NavTab.Novel.prefKey in novel)
        assertFalse(NavTab.Manga.prefKey in novel)
    }
}
