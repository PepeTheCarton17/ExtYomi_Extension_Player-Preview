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
    fun `updates is forced on even when an older build disabled it`() {
        val enabled = NavTab.enabledInStandalone(emptySet(), "manga")

        assertEquals(
            setOf(NavTab.Home.prefKey, NavTab.Updates.prefKey),
            enabled,
        )
    }
}
