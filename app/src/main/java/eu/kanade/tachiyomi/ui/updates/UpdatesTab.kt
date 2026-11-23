package eu.kanade.tachiyomi.ui.updates

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.domain.ui.StandaloneScope
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.HomeTabsMode
import eu.kanade.domain.ui.model.NavTab
import eu.kanade.presentation.components.TabbedScreen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.download.DownloadsTab
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.updates.anime.animeUpdatesTab
import eu.kanade.tachiyomi.ui.updates.manga.mangaUpdatesTab
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data object UpdatesTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_updates_enter)
            return TabOptions(
                index = 2u,
                title = stringResource(MR.strings.label_recent_updates),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }
    override suspend fun onReselect(navigator: Navigator) {
        navigator.push(DownloadsTab)
    }

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val enabledTabs = currentBottomNavTabs()
        val fromMore = NavTab.Updates.prefKey !in enabledTabs
        val animeScope = StandaloneScope.appliesTo(StandaloneScope.KIND_ANIME)
        val novelScope = StandaloneScope.appliesTo(StandaloneScope.KIND_NOVEL)
        // Show a dedicated "Novels" updates feed (novels are excluded from the manga one) when the
        // user actually uses novels, i.e. has the Novel tab enabled. A standalone app is scoped to
        // one kind, so never offer the other kinds there.
        val showNovel = !StandaloneScope.isActive && NavTab.Novel.prefKey in enabledTabs
        val savedMode = remember { Injekt.get<UiPreferences>() }.homeTabsMode().get()
        val mode = when {
            animeScope -> HomeTabsMode.ANIME_ONLY
            StandaloneScope.appliesTo(StandaloneScope.KIND_MANGA) || novelScope ->
                HomeTabsMode.MANGA_ONLY
            else -> savedMode
        }

        val tabs = when {
            novelScope -> persistentListOf(mangaUpdatesTab(context, fromMore, novelOnly = true))
            else -> when (mode) {
                HomeTabsMode.ANIME_ONLY -> persistentListOf(animeUpdatesTab(context, fromMore))
                HomeTabsMode.MANGA_ONLY -> if (showNovel) {
                    persistentListOf(
                        mangaUpdatesTab(context, fromMore),
                        mangaUpdatesTab(context, fromMore, novelOnly = true),
                    )
                } else {
                    persistentListOf(mangaUpdatesTab(context, fromMore))
                }
                else -> if (showNovel) {
                    persistentListOf(
                        animeUpdatesTab(context, fromMore),
                        mangaUpdatesTab(context, fromMore),
                        mangaUpdatesTab(context, fromMore, novelOnly = true),
                    )
                } else {
                    persistentListOf(
                        animeUpdatesTab(context, fromMore),
                        mangaUpdatesTab(context, fromMore),
                    )
                }
            }
        }

        TabbedScreen(
            titleRes = MR.strings.label_recent_updates,
            tabs = tabs,
            state = rememberPagerState(mode.defaultIndex.coerceAtMost(tabs.lastIndex)) { tabs.size },
        )

        LaunchedEffect(Unit) {
            (context as? MainActivity)?.ready = true
        }
    }
}

private const val TAB_ANIME = 0
private const val TAB_MANGA = 1
