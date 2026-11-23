package eu.kanade.tachiyomi.ui.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.domain.ui.StandaloneScope
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.ui.browse.anime.source.browse.BrowseAnimeSourceScreen
import eu.kanade.tachiyomi.ui.browse.manga.source.browse.BrowseMangaSourceScreen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** The single source's catalogue, selected by "Feed (catálogo)" in the start-screen setting. */
data object StandaloneCatalogueTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            return TabOptions(
                index = 1u,
                title = stringResource(MR.strings.label_standalone_catalogue),
                icon = rememberVectorPainter(Icons.Outlined.Explore),
            )
        }

    @Composable
    override fun Content() {
        val sourceId = StandaloneScope.sourceId
        if (StandaloneScope.appliesTo(StandaloneScope.KIND_MANGA) ||
            StandaloneScope.appliesTo(StandaloneScope.KIND_NOVEL)
        ) {
            BrowseMangaSourceScreen(sourceId, null).Content()
        } else {
            BrowseAnimeSourceScreen(sourceId, null).Content()
        }
    }
}
