package eu.kanade.tachiyomi.ui.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
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

/** Simple feed for a generated standalone app: the catalogue of its single source. */
data object StandaloneFeedTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            return TabOptions(
                index = 0u,
                title = stringResource(MR.strings.label_standalone_feed),
                icon = rememberVectorPainter(Icons.Outlined.Home),
            )
        }

    @Composable
    override fun Content() {
        val sourceId = StandaloneScope.sourceId
        if (StandaloneScope.appliesTo(StandaloneScope.KIND_MANGA)) {
            BrowseMangaSourceScreen(sourceId, null).Content()
        } else {
            BrowseAnimeSourceScreen(sourceId, null).Content()
        }
    }
}
