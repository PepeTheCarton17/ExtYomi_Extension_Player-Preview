package eu.kanade.tachiyomi.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.reader.loader.NovelSourceCompat
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.source.manga.service.MangaSourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * One of the three independent source blocks of the new Home. Enabled sources are tried in this
 * order; the player/migration priority is deliberately not read or written here.
 */
class HomeFeedSourcePriorityScreen(private val kind: String) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val uiPreferences = remember { Injekt.get<UiPreferences>() }
        val animeManager = remember { Injekt.get<AnimeSourceManager>() }
        val mangaManager = remember { Injekt.get<MangaSourceManager>() }

        val entries = remember(kind) {
            if (kind == StandaloneFeedScreenModel.KIND_ANIME) {
                animeManager.getCatalogueSources().map { it.id to it.name }
            } else {
                mangaManager.getCatalogueSources()
                    .filter {
                        NovelSourceCompat.isNovelSource(it) ==
                            (kind == StandaloneFeedScreenModel.KIND_NOVEL)
                    }
                    .map { it.id to it.name }
            }
        }
        val savedOrder = remember(kind) {
            uiPreferences.homeFeedSourceOrder(kind).get()
                .split(',')
                .mapNotNull { it.trim().toLongOrNull() }
        }
        val local = remember(entries, savedOrder) {
            entries.sortedBy { (id, _) ->
                val index = savedOrder.indexOf(id)
                if (index < 0) savedOrder.size + entries.indexOfFirst { it.first == id } else index
            }.toMutableStateList()
        }
        var disabled by remember(kind) {
            mutableStateOf(uiPreferences.homeFeedDisabledSources(kind).get())
        }

        fun save() {
            uiPreferences.homeFeedSourceOrder(kind).set(local.joinToString(",") { it.first.toString() })
            uiPreferences.homeFeedDisabledSources(kind).set(disabled)
        }

        val listState = rememberLazyListState()
        var dirty by remember { mutableStateOf(false) }
        val reorderState = rememberReorderableLazyListState(
            listState,
            PaddingValues(MaterialTheme.padding.medium),
        ) { from, to ->
            local.add(to.index, local.removeAt(from.index))
            dirty = true
        }

        LaunchedEffect(reorderState.isAnyItemDragging) {
            if (!reorderState.isAnyItemDragging && dirty) {
                save()
                dirty = false
            }
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = when (kind) {
                        StandaloneFeedScreenModel.KIND_ANIME -> stringResource(MR.strings.home_feed_sources_anime)
                        StandaloneFeedScreenModel.KIND_MANGA -> stringResource(MR.strings.home_feed_sources_manga)
                        else -> stringResource(MR.strings.home_feed_sources_novel)
                    },
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize(),
            ) {
                Text(
                    text = stringResource(MR.strings.home_feed_source_priority_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = MaterialTheme.padding.medium,
                        vertical = MaterialTheme.padding.small,
                    ),
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(MaterialTheme.padding.medium),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(
                        MaterialTheme.padding.small,
                    ),
                ) {
                    items(items = local, key = { "home-source-${it.first}" }) { entry ->
                        val enabled = entry.first.toString() !in disabled
                        ReorderableItem(reorderState, key = "home-source-${entry.first}") {
                            ElevatedCard(modifier = Modifier.animateItem()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = MaterialTheme.padding.small)
                                        .padding(
                                            start = MaterialTheme.padding.small,
                                            end = MaterialTheme.padding.medium,
                                        ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.DragHandle,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .padding(MaterialTheme.padding.medium)
                                            .draggableHandle(),
                                    )
                                    Text(
                                        text = entry.second,
                                        modifier = Modifier
                                            .weight(1f)
                                            .alpha(if (enabled) 1f else 0.4f),
                                    )
                                    Checkbox(
                                        checked = enabled,
                                        onCheckedChange = { checked ->
                                            disabled = if (checked) {
                                                disabled - entry.first.toString()
                                            } else {
                                                disabled + entry.first.toString()
                                            }
                                            save()
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
