package eu.kanade.domain.ui.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.ui.StandaloneScope
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.browse.BrowseTab
import eu.kanade.tachiyomi.ui.history.HistoriesTab
import eu.kanade.tachiyomi.ui.home.HomeFeedTab
import eu.kanade.tachiyomi.ui.library.anime.AnimeLibraryTab
import eu.kanade.tachiyomi.ui.library.manga.MangaLibraryTab
import eu.kanade.tachiyomi.ui.library.manga.NovelLibraryTab
import eu.kanade.tachiyomi.ui.more.MoreTab
import eu.kanade.tachiyomi.ui.updates.UpdatesTab
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR

/**
 * The tabs the user can add to or remove from the bottom navigation bar. Only the More tab is always
 * shown; every unchosen [NavTab] is moved into the More tab instead.
 */
enum class NavTab(
    val tab: Tab,
    val prefKey: String,
    val titleRes: StringResource,
) {
    Home(HomeFeedTab, "home", MR.strings.label_home),
    Anime(AnimeLibraryTab, "anime", AYMR.strings.label_anime_library),
    Manga(MangaLibraryTab, "manga", AYMR.strings.label_manga_library),
    Updates(UpdatesTab, "updates", MR.strings.label_recent_updates),
    History(HistoriesTab, "history", MR.strings.history),
    Browse(BrowseTab, "browse", MR.strings.browse),
    Novel(NovelLibraryTab, "novel", AYMR.strings.label_novel),
    ;

    val icon: ImageVector
        @Composable
        get() = when (this) {
            Home -> Icons.Outlined.Home
            Anime -> Icons.Outlined.VideoLibrary
            Manga -> Icons.Outlined.CollectionsBookmark
            Updates -> ImageVector.vectorResource(id = R.drawable.ic_updates_outline_24dp)
            History -> Icons.Outlined.History
            Browse -> Icons.Outlined.Explore
            Novel -> Icons.Outlined.AutoStories
        }

    companion object {
        // Novels sit beside Anime and Manga in the bottom bar instead of being buried in More.
        val DEFAULT: Set<String> = setOf(
            Home.prefKey,
            Anime.prefKey,
            Manga.prefKey,
            Novel.prefKey,
            Updates.prefKey,
            Browse.prefKey,
        )

        /**
         * All tabs sorted by the user's saved [order] (a comma-separated list of prefKeys). Tabs
         * missing from [order] keep their enum order at the end, so new tabs appear predictably.
         */
        fun ordered(order: String): List<NavTab> {
            val keys = order.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            // Home is always the first tab, like Animetail's house icon.
            return listOf(Home) + entries.filter { it != Home }
                .sortedBy { keys.indexOf(it.prefKey).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        }

        /** Tabs shown in the bottom bar, in the user's [order], for the [enabled] set (More is always last). */
        fun shownTabs(enabled: Set<String>, order: String): List<Tab> = buildList {
            ordered(order).forEach { if (it.prefKey in enabled) add(it.tab) }
            add(MoreTab)
        }

        /** Optional tabs not in the bottom bar; reached from the More tab. */
        fun hidden(enabled: Set<String>): List<NavTab> = entries.filter { it.prefKey !in enabled }

        /**
         * Tabs that make sense in a standalone app of [kind]. The other content kinds and the
         * multi-source Explore tab must never be offered there, but the user can still choose
         * whether History sits in the bottom bar or in More.
         */
        fun allowedInStandalone(kind: String): List<NavTab> = when (kind) {
            StandaloneScope.KIND_ANIME -> listOf(Home, Anime, Updates, History)
            StandaloneScope.KIND_MANGA -> listOf(Home, Manga, Updates, History)
            StandaloneScope.KIND_NOVEL -> listOf(Home, Novel, Updates, History)
            else -> ordered("")
        }

        /**
         * Effective bottom-bar tabs for a standalone app. Home and Updates are mandatory; History
         * keeps the saved choice (off by default). Foreign tabs are dropped even if an older build
         * saved them in [enabled].
         */
        fun enabledInStandalone(enabled: Set<String>, kind: String): Set<String> {
            val allowed = allowedInStandalone(kind).map { it.prefKey }.toSet()
            val library = when (kind) {
                StandaloneScope.KIND_ANIME -> Anime.prefKey
                StandaloneScope.KIND_MANGA -> Manga.prefKey
                StandaloneScope.KIND_NOVEL -> Novel.prefKey
                else -> null
            }
            return (enabled intersect allowed) + listOfNotNull(Home.prefKey, library, Updates.prefKey)
        }

        /** Home, the app's own library and Updates cannot be removed from a standalone bar. */
        fun isFixedInStandalone(navTab: NavTab, kind: String): Boolean = when (kind) {
            StandaloneScope.KIND_ANIME -> navTab == Home || navTab == Anime || navTab == Updates
            StandaloneScope.KIND_MANGA -> navTab == Home || navTab == Manga || navTab == Updates
            StandaloneScope.KIND_NOVEL -> navTab == Home || navTab == Novel || navTab == Updates
            else -> navTab == Home || navTab == Updates
        }
    }
}
