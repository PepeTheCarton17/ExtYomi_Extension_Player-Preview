package eu.kanade.tachiyomi.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.domain.ui.StandaloneScope
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.ui.browse.anime.source.browse.BrowseAnimeSourceScreen
import eu.kanade.tachiyomi.ui.browse.anime.source.browse.SourceFilterAnimeDialog
import eu.kanade.tachiyomi.ui.browse.manga.source.browse.BrowseMangaSourceScreen
import eu.kanade.tachiyomi.ui.browse.manga.source.browse.SourceFilterMangaDialog
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.entries.manga.MangaScreen
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.source.manga.service.MangaSourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private enum class LocalHomeTab { ALL, POPULAR, TRENDING, FILTERS }

private data class LocalFeedSection(
    val title: String,
    val items: List<StandaloneFeedScreenModel.FeedItem>,
)

/**
 * Home of a generated standalone. Everything shown here comes from its single extension; the
 * public API only decides which real entries are suggested. Search and filters reuse the normal
 * single-source catalogue screen instead of the multi-extension Explore experience.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StandaloneLocalHomeFeedScreen(
    screenModel: StandaloneFeedScreenModel,
    modifier: Modifier = Modifier,
) {
    val state by screenModel.state.collectAsState()
    val navigator = LocalNavigator.currentOrThrow
    val isAnime = StandaloneScope.appliesTo(StandaloneScope.KIND_ANIME)
    var selectedTab by remember { mutableStateOf(LocalHomeTab.ALL) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var animeFilters by remember { mutableStateOf<AnimeFilterList?>(null) }
    var mangaFilters by remember { mutableStateOf<FilterList?>(null) }

    val tabs = buildList {
        add(LocalHomeTab.ALL)
        add(LocalHomeTab.POPULAR)
        if (state.hasTrending) add(LocalHomeTab.TRENDING)
        add(LocalHomeTab.FILTERS)
    }

    fun freshAnimeFilters(): AnimeFilterList {
        val source = Injekt.get<AnimeSourceManager>().get(StandaloneScope.sourceId) as? AnimeCatalogueSource
        return source?.let { runCatching { it.getFilterList() }.getOrNull() } ?: AnimeFilterList()
    }

    fun freshMangaFilters(): FilterList {
        val source = Injekt.get<MangaSourceManager>().get(StandaloneScope.sourceId) as? CatalogueSource
        return source?.let { runCatching { it.getFilterList() }.getOrNull() } ?: FilterList()
    }

    // Search still goes to the single-source catalogue. Filters stay here, in the Home.
    fun openSourceCatalogue() {
        val sourceId = StandaloneScope.sourceId
        if (isAnime) {
            navigator.push(BrowseAnimeSourceScreen(sourceId, null))
        } else {
            navigator.push(BrowseMangaSourceScreen(sourceId, null))
        }
    }

    fun openFilters() {
        if (isAnime) {
            animeFilters = freshAnimeFilters()
        } else {
            mangaFilters = freshMangaFilters()
        }
        showFilterSheet = true
    }

    fun onFeedItemClick(item: StandaloneFeedScreenModel.FeedItem) {
        val id = item.localId ?: item.id
        if (item.isAnime) {
            navigator.push(AnimeScreen(id, true))
        } else {
            navigator.push(MangaScreen(id, true))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(MR.strings.standalone_feed_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                actions = {
                    IconButton(onClick = { openSourceCatalogue() }) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = stringResource(MR.strings.action_search),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        PullRefresh(
            refreshing = state.isRefreshing,
            enabled = true,
            onRefresh = screenModel::refresh,
            indicatorPadding = padding,
        ) {
            if (state.isLoading) {
                LoadingScreen(
                    modifier = modifier
                        .fillMaxSize()
                        .padding(padding),
                )
                return@PullRefresh
            }

            val filteredEntries = state.filteredEntries

            // Todos shows every entry of the extension: categories, popular and trending are
            // interleaved as divided sections instead of the old anime/manga/movie chips.
            val sections = buildList<LocalFeedSection> {
                addAll(state.categorySections.map { LocalFeedSection(it.name, it.items) })
                add(LocalFeedSection(stringResource(MR.strings.standalone_feed_popular), state.popular))
                if (state.hasTrending) {
                    add(LocalFeedSection(stringResource(MR.strings.standalone_feed_trending), state.trending))
                }
                add(LocalFeedSection(stringResource(MR.strings.standalone_feed_all), state.allEntries))
            }

            val nothingToShow = when (selectedTab) {
                LocalHomeTab.ALL -> if (filteredEntries != null) {
                    filteredEntries.isEmpty()
                } else {
                    state.allEntries.isEmpty() &&
                        state.popular.isEmpty() &&
                        state.recommended.isEmpty() &&
                        state.continueList.isEmpty()
                }
                LocalHomeTab.POPULAR -> state.popular.isEmpty()
                LocalHomeTab.TRENDING -> state.trending.isEmpty()
                LocalHomeTab.FILTERS -> false
            }

            LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    PrimaryScrollableTabRow(
                        selectedTabIndex = tabs.indexOf(selectedTab).coerceAtLeast(0),
                        edgePadding = 0.dp,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        tabs.forEach { tab ->
                            Tab(
                                selected = tab == selectedTab,
                                onClick = {
                                    if (tab == LocalHomeTab.FILTERS) {
                                        // Open the filter sheet in this Home, without navigating.
                                        openFilters()
                                    } else {
                                        selectedTab = tab
                                    }
                                },
                                text = { Text(text = labelFor(tab)) },
                            )
                        }
                    }
                }

                when (selectedTab) {
                    LocalHomeTab.ALL -> {
                        if (filteredEntries != null) {
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = stringResource(MR.strings.action_filter),
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = screenModel::clearFilters) {
                                        Text(stringResource(MR.strings.action_filter_empty))
                                    }
                                }
                                LocalFeedRow(
                                    title = stringResource(MR.strings.action_filter),
                                    items = filteredEntries,
                                    onClick = ::onFeedItemClick,
                                )
                            }
                        } else {
                            if (state.showContinue && state.continueList.isNotEmpty()) {
                                item {
                                    LocalSectionTitle(stringResource(MR.strings.standalone_feed_continue))
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 16.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        items(items = state.continueList, key = { "continue-${it.id}" }) { entry ->
                                            ContinueCardLocal(entry) {
                                                if (entry.isAnime) {
                                                    navigator.push(AnimeScreen(entry.id, true))
                                                } else {
                                                    navigator.push(MangaScreen(entry.id, true))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (state.showRecommended && state.recommended.isNotEmpty()) {
                                item {
                                    LocalFeedRow(
                                        title = stringResource(MR.strings.standalone_feed_recommended),
                                        items = state.recommended,
                                        onClick = ::onFeedItemClick,
                                    )
                                }
                            }
                            sections.forEach { section ->
                                item(key = "local-section-${section.title}") {
                                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                                    LocalFeedRow(
                                        title = section.title,
                                        items = section.items,
                                        onClick = ::onFeedItemClick,
                                    )
                                }
                            }
                        }
                    }
                    LocalHomeTab.POPULAR -> {
                        item {
                            LocalFeedRow(
                                title = stringResource(MR.strings.standalone_feed_popular),
                                items = state.popular,
                                onClick = ::onFeedItemClick,
                            )
                        }
                    }
                    LocalHomeTab.TRENDING -> {
                        item {
                            LocalFeedRow(
                                title = stringResource(MR.strings.standalone_feed_trending),
                                items = state.trending,
                                onClick = ::onFeedItemClick,
                            )
                        }
                    }
                    LocalHomeTab.FILTERS -> Unit
                }

                if (nothingToShow) {
                    item {
                        Text(
                            text = stringResource(MR.strings.standalone_feed_empty),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }

    if (showFilterSheet) {
        if (isAnime) {
            animeFilters?.let { filters ->
                SourceFilterAnimeDialog(
                    onDismissRequest = { showFilterSheet = false },
                    filters = filters,
                    onReset = { animeFilters = freshAnimeFilters() },
                    onFilter = {
                        screenModel.applyAnimeFilters(filters)
                        selectedTab = LocalHomeTab.ALL
                        showFilterSheet = false
                    },
                    onUpdate = { animeFilters = it },
                )
            }
        } else {
            mangaFilters?.let { filters ->
                SourceFilterMangaDialog(
                    onDismissRequest = { showFilterSheet = false },
                    filters = filters,
                    onReset = { mangaFilters = freshMangaFilters() },
                    onFilter = {
                        screenModel.applyMangaFilters(filters)
                        selectedTab = LocalHomeTab.ALL
                        showFilterSheet = false
                    },
                    onUpdate = { mangaFilters = it },
                )
            }
        }
    }
}

@Composable
private fun labelFor(tab: LocalHomeTab): String = when (tab) {
    LocalHomeTab.ALL -> stringResource(MR.strings.standalone_feed_all)
    LocalHomeTab.POPULAR -> stringResource(MR.strings.standalone_feed_popular)
    LocalHomeTab.TRENDING -> stringResource(MR.strings.standalone_feed_trending)
    LocalHomeTab.FILTERS -> stringResource(MR.strings.action_filter)
}

@Composable
private fun LocalSectionTitle(title: String) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun LocalFeedRow(
    title: String,
    items: List<StandaloneFeedScreenModel.FeedItem>,
    onClick: (StandaloneFeedScreenModel.FeedItem) -> Unit,
) {
    val unique = remember(items) { items.distinctBy { it.localId ?: it.id } }
    if (unique.isEmpty()) return
    Column {
        LocalSectionTitle(title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(items = unique, key = { "entry-${it.localId ?: it.id}" }) { entry ->
                LocalFeedCard(entry) { onClick(entry) }
            }
        }
    }
}

@Composable
private fun LocalFeedCard(
    item: StandaloneFeedScreenModel.FeedItem,
    onClick: () -> Unit,
) {
    val clickable = Modifier
        .width(120.dp)
        .clip(RoundedCornerShape(8.dp))
    Column(modifier = clickable.clickable(onClick = onClick)) {
        AsyncImage(
            model = item.coverUrl,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ContinueCardLocal(
    item: StandaloneFeedScreenModel.ContinueItem,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .width(220.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = item.coverUrl,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.width(64.dp).height(90.dp),
        )
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(text = item.subtitle, style = MaterialTheme.typography.labelSmall)
        }
    }
}
