package eu.kanade.tachiyomi.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.domain.ui.StandaloneScope
import eu.kanade.presentation.browse.anime.BrowseAnimeSourceContent
import eu.kanade.presentation.browse.manga.BrowseSourceContent
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.SearchToolbar
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.ui.browse.anime.extension.details.AnimeSourcePreferencesScreen
import eu.kanade.tachiyomi.ui.browse.anime.source.browse.BrowseAnimeSourceScreenModel
import eu.kanade.tachiyomi.ui.browse.anime.source.browse.SourceFilterAnimeDialog
import eu.kanade.tachiyomi.ui.browse.manga.extension.details.MangaSourcePreferencesScreen
import eu.kanade.tachiyomi.ui.browse.manga.source.browse.BrowseMangaSourceScreenModel
import eu.kanade.tachiyomi.ui.browse.manga.source.browse.SourceFilterMangaDialog
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.entries.manga.MangaScreen
import kotlinx.collections.immutable.persistentListOf
import mihon.presentation.core.util.collectAsLazyPagingItems
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.source.anime.interactor.GetRemoteAnime
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.source.manga.interactor.GetRemoteManga
import tachiyomi.domain.source.manga.service.MangaSourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

private enum class LocalHomeTab { ALL, POPULAR, RECENT, TRENDING, FILTERS }

private data class LocalFeedSection(
    val key: String,
    val title: String,
    val items: List<StandaloneFeedScreenModel.FeedItem>,
    val hasNextPage: Boolean = false,
    val isLoadingMore: Boolean = false,
)

/**
 * Home of a generated standalone. Everything shown here comes from its single extension; the
 * public API only decides which real entries are suggested. Search and filters run here against
 * the bundled source instead of redirecting to the multi-extension Explore experience.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StandaloneLocalHomeFeedScreen(
    screen: Screen,
    screenModel: StandaloneFeedScreenModel,
    modifier: Modifier = Modifier,
    requestSettings: Boolean = false,
    onSettingsHandled: () -> Unit = {},
) {
    val state by screenModel.state.collectAsState()
    val navigator = LocalNavigator.currentOrThrow
    val isAnime = StandaloneScope.appliesTo(StandaloneScope.KIND_ANIME)
    var selectedTab by remember { mutableStateOf(LocalHomeTab.ALL) }
    // Sections toggled to the vertical catalogue grid, kept in place in this same Home.
    var gridSections by remember { mutableStateOf(emptySet<String>()) }
    var showFilterSheet by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf<String?>(null) }
    var animeFilters by remember { mutableStateOf<AnimeFilterList?>(null) }
    var mangaFilters by remember { mutableStateOf<FilterList?>(null) }

    // Reselecting the Home tab opens the feed settings, as in the generator app.
    LaunchedEffect(requestSettings) {
        if (requestSettings) {
            showSettings = true
            onSettingsHandled()
        }
    }

    val tabs = buildList {
        add(LocalHomeTab.ALL)
        add(LocalHomeTab.POPULAR)
        add(LocalHomeTab.RECENT)
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

    // Same destination as the catalogue's settings action: the bundled extension's preferences.
    fun openSourceSettings() {
        val sourceId = StandaloneScope.sourceId
        if (isAnime) {
            navigator.push(AnimeSourcePreferencesScreen(sourceId))
        } else {
            navigator.push(MangaSourcePreferencesScreen(sourceId))
        }
    }

    // The toggle only changes how this section is shown: the vertical view is the real catalogue
    // grid, with the same listing/filters as the horizontal rows, embedded in this same Home.
    fun toggleSectionView(sectionKey: String) {
        gridSections = if (sectionKey in gridSections) {
            gridSections - sectionKey
        } else {
            gridSections + sectionKey
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
            // Search happens here: the text is sent to the bundled source (auto-selecting a
            // matching filter) instead of redirecting to the other catalogue.
            SearchToolbar(
                searchQuery = searchQuery,
                onChangeSearchQuery = { searchQuery = it },
                titleContent = {
                    Text(
                        text = stringResource(MR.strings.standalone_feed_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                onSearch = { query ->
                    screenModel.search(query)
                    selectedTab = LocalHomeTab.FILTERS
                },
                onClickCloseSearch = {
                    searchQuery = null
                    screenModel.clearFilters()
                    selectedTab = LocalHomeTab.ALL
                },
                actions = {
                    AppBarActions(
                        actions = persistentListOf(
                            AppBar.Action(
                                title = stringResource(MR.strings.standalone_feed_settings),
                                icon = Icons.Outlined.Tune,
                                onClick = { showSettings = true },
                            ),
                            AppBar.Action(
                                title = stringResource(MR.strings.action_settings),
                                icon = Icons.Outlined.Settings,
                                onClick = { openSourceSettings() },
                            ),
                        ),
                    )
                },
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

            fun section(
                key: String,
                title: String,
                items: List<StandaloneFeedScreenModel.FeedItem>,
            ): LocalFeedSection {
                val pagination = state.sectionPages[key]
                    ?: StandaloneFeedScreenModel.FeedPagination()
                return LocalFeedSection(
                    key = key,
                    title = title,
                    items = items,
                    hasNextPage = pagination.hasNextPage,
                    isLoadingMore = pagination.isLoadingMore,
                )
            }

            @Composable
            fun FeedSection(feedSection: LocalFeedSection) {
                LocalFeedSectionContent(
                    screen = screen,
                    section = feedSection,
                    itemsPerSection = state.itemsPerSection,
                    gridMode = feedSection.key in gridSections,
                    onToggleView = { toggleSectionView(feedSection.key) },
                    feedModel = screenModel,
                    onClick = ::onFeedItemClick,
                    onLoadMore = { screenModel.loadMoreSection(feedSection.key) },
                )
            }

            val filterLabel = buildString {
                append(stringResource(MR.strings.action_filter))
                state.activeFilterName?.let { append(" · ").append(it) }
            }
            val recentSection = section(
                StandaloneFeedScreenModel.SECTION_RECENT,
                stringResource(MR.strings.standalone_feed_recent),
                state.recent,
            )
            val popularSection = section(
                StandaloneFeedScreenModel.SECTION_POPULAR,
                stringResource(MR.strings.standalone_feed_popular),
                state.popular,
            )
            val trendingSection = section(
                StandaloneFeedScreenModel.SECTION_TRENDING,
                stringResource(MR.strings.standalone_feed_trending),
                state.trending,
            )
            val filteredSection = section(
                StandaloneFeedScreenModel.SECTION_FILTERED,
                filterLabel,
                filteredEntries.orEmpty(),
            )
            val recommendedSection = section(
                "recommended",
                stringResource(MR.strings.standalone_feed_recommended),
                state.recommended,
            )
            val allSection = section(
                StandaloneFeedScreenModel.SECTION_ALL,
                stringResource(MR.strings.standalone_feed_all),
                state.allEntries,
            )

            val nothingToShow = when (selectedTab) {
                LocalHomeTab.ALL -> if (filteredEntries != null) {
                    filteredEntries.isEmpty()
                } else {
                    state.allEntries.isEmpty() &&
                        state.popular.isEmpty() &&
                        state.recent.isEmpty() &&
                        state.recommended.isEmpty() &&
                        state.continueList.isEmpty()
                }
                LocalHomeTab.POPULAR -> state.popular.isEmpty()
                LocalHomeTab.RECENT -> state.recent.isEmpty()
                LocalHomeTab.TRENDING -> state.trending.isEmpty()
                LocalHomeTab.FILTERS -> filteredEntries?.isEmpty() == true
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
                                        selectedTab = tab
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
                                FilterHeader(filterLabel) {
                                    screenModel.clearFilters()
                                    searchQuery = null
                                    selectedTab = LocalHomeTab.ALL
                                }
                            }
                            item {
                                FeedSection(filteredSection)
                            }
                        } else {
                            if (state.showContinue && state.continueList.isNotEmpty()) {
                                item {
                                    LocalSectionTitle(
                                        title = stringResource(
                                            if (isAnime) {
                                                MR.strings.standalone_feed_continue_watching
                                            } else {
                                                MR.strings.standalone_feed_continue_reading
                                            },
                                        ),
                                        gridMode = false,
                                        onToggle = {},
                                        showToggle = false,
                                    )
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
                            item {
                                FeedSection(recommendedSection)
                            }
                            val sections = state.categorySections.map {
                                section(
                                    StandaloneFeedScreenModel.categoryKey(it.name),
                                    it.name,
                                    it.items,
                                )
                            } + recentSection + popularSection + trendingSection + allSection
                            sections.forEach { feedSection ->
                                item(key = "local-section-${feedSection.key}") {
                                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                                    FeedSection(feedSection)
                                }
                            }
                        }
                    }
                    LocalHomeTab.POPULAR -> item {
                        FeedSection(popularSection)
                    }
                    LocalHomeTab.RECENT -> item {
                        FeedSection(recentSection)
                    }
                    LocalHomeTab.TRENDING -> item {
                        FeedSection(trendingSection)
                    }
                    LocalHomeTab.FILTERS -> {
                        if (filteredEntries != null) {
                            item {
                                FilterHeader(filterLabel) {
                                    screenModel.clearFilters()
                                    searchQuery = null
                                    selectedTab = LocalHomeTab.FILTERS
                                }
                            }
                            item {
                                FeedSection(filteredSection)
                            }
                        }
                    }
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
                    onDismissRequest = {
                        showFilterSheet = false
                        if (state.filteredEntries == null) selectedTab = LocalHomeTab.ALL
                    },
                    filters = filters,
                    onReset = { animeFilters = freshAnimeFilters() },
                    onFilter = {
                        screenModel.applyAnimeFilters(filters)
                        selectedTab = LocalHomeTab.FILTERS
                        showFilterSheet = false
                    },
                    onUpdate = { animeFilters = it },
                )
            }
        } else {
            mangaFilters?.let { filters ->
                SourceFilterMangaDialog(
                    onDismissRequest = {
                        showFilterSheet = false
                        if (state.filteredEntries == null) selectedTab = LocalHomeTab.ALL
                    },
                    filters = filters,
                    onReset = { mangaFilters = freshMangaFilters() },
                    onFilter = {
                        screenModel.applyMangaFilters(filters)
                        selectedTab = LocalHomeTab.FILTERS
                        showFilterSheet = false
                    },
                    onUpdate = { mangaFilters = it },
                )
            }
        }
    }

    if (showSettings) {
        FeedSettingsDialog(
            state = state,
            onDismiss = { showSettings = false },
            onToggleFeatured = screenModel::toggleFeatured,
            onToggleContinue = screenModel::toggleContinue,
            onToggleRecommended = screenModel::toggleRecommended,
            onTogglePopular = screenModel::togglePopular,
            onToggleAutoScrollHero = screenModel::toggleAutoScrollHero,
            onToggleOnlyAvailable = screenModel::toggleOnlyAvailable,
            onSetItemsPerSection = screenModel::setItemsPerSection,
            showOnlyAvailable = false,
        )
    }
}

@Composable
private fun labelFor(tab: LocalHomeTab): String = when (tab) {
    LocalHomeTab.ALL -> stringResource(MR.strings.standalone_feed_all)
    LocalHomeTab.POPULAR -> stringResource(MR.strings.standalone_feed_popular)
    LocalHomeTab.RECENT -> stringResource(MR.strings.standalone_feed_recent)
    LocalHomeTab.TRENDING -> stringResource(MR.strings.standalone_feed_trending)
    LocalHomeTab.FILTERS -> stringResource(MR.strings.action_filter)
}

@Composable
private fun FilterHeader(label: String, onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onClear) {
            Text(stringResource(MR.strings.action_filter_empty))
        }
    }
}

@Composable
private fun LocalSectionTitle(
    title: String,
    gridMode: Boolean,
    onToggle: () -> Unit,
    showToggle: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (showToggle) {
            IconButton(onClick = onToggle) {
                Icon(
                    imageVector = if (gridMode) {
                        Icons.AutoMirrored.Filled.ViewList
                    } else {
                        Icons.Filled.ViewModule
                    },
                    contentDescription = stringResource(MR.strings.standalone_feed_toggle_view),
                )
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

/**
 * The vertical view of a Home section. It is the real catalogue grid and screen model, embedded
 * here with the listing/filters of the section that was being shown horizontally.
 */
@Composable
private fun LocalFeedCatalogueGrid(
    screen: Screen,
    sectionKey: String,
    feedModel: StandaloneFeedScreenModel,
) {
    val navigator = LocalNavigator.currentOrThrow
    val isAnime = StandaloneScope.appliesTo(StandaloneScope.KIND_ANIME)
    val listingQuery = when (sectionKey) {
        StandaloneFeedScreenModel.SECTION_POPULAR -> if (isAnime) {
            GetRemoteAnime.QUERY_POPULAR
        } else {
            GetRemoteManga.QUERY_POPULAR
        }
        StandaloneFeedScreenModel.SECTION_RECENT -> if (isAnime) {
            GetRemoteAnime.QUERY_LATEST
        } else {
            GetRemoteManga.QUERY_LATEST
        }
        else -> null
    }

    if (isAnime) {
        val model = screen.rememberScreenModel("standalone-home-grid-$sectionKey") {
            BrowseAnimeSourceScreenModel(StandaloneScope.sourceId, listingQuery)
        }
        LaunchedEffect(sectionKey) {
            when {
                sectionKey.startsWith(StandaloneFeedScreenModel.CATEGORY_PREFIX) ->
                    model.searchGenre(
                        sectionKey.removePrefix(StandaloneFeedScreenModel.CATEGORY_PREFIX),
                    )
                sectionKey == StandaloneFeedScreenModel.SECTION_FILTERED -> model.search(
                    feedModel.activeSearchQuery.takeIf { it.isNotBlank() },
                    feedModel.activeAnimeFilters,
                )
                sectionKey == StandaloneFeedScreenModel.SECTION_TRENDING ->
                    feedModel.activeTrendingName?.let(model::searchGenre)
            }
        }
        val snackbar = remember { SnackbarHostState() }
        Box(Modifier.fillMaxWidth().height(420.dp)) {
            BrowseAnimeSourceContent(
                source = model.source,
                animeList = model.animePagerFlowFlow.collectAsLazyPagingItems(),
                columns = model.getColumnsPreference(LocalConfiguration.current.orientation),
                displayMode = LibraryDisplayMode.ComfortableGrid,
                snackbarHostState = snackbar,
                contentPadding = PaddingValues(horizontal = 8.dp),
                onWebViewClick = {},
                onHelpClick = {},
                onLocalAnimeSourceHelpClick = {},
                onAnimeClick = { navigator.push(AnimeScreen(it.id, true)) },
                onAnimeLongClick = {},
            )
        }
    } else {
        val model = screen.rememberScreenModel("standalone-home-grid-$sectionKey") {
            BrowseMangaSourceScreenModel(StandaloneScope.sourceId, listingQuery)
        }
        LaunchedEffect(sectionKey) {
            when {
                sectionKey.startsWith(StandaloneFeedScreenModel.CATEGORY_PREFIX) ->
                    model.searchGenre(
                        sectionKey.removePrefix(StandaloneFeedScreenModel.CATEGORY_PREFIX),
                    )
                sectionKey == StandaloneFeedScreenModel.SECTION_FILTERED -> model.search(
                    feedModel.activeSearchQuery.takeIf { it.isNotBlank() },
                    feedModel.activeMangaFilters,
                )
                sectionKey == StandaloneFeedScreenModel.SECTION_TRENDING ->
                    feedModel.activeTrendingName?.let(model::searchGenre)
            }
        }
        val snackbar = remember { SnackbarHostState() }
        Box(Modifier.fillMaxWidth().height(420.dp)) {
            BrowseSourceContent(
                source = model.source,
                mangaList = model.mangaPagerFlowFlow.collectAsLazyPagingItems(),
                columns = model.getColumnsPreference(LocalConfiguration.current.orientation),
                displayMode = LibraryDisplayMode.ComfortableGrid,
                snackbarHostState = snackbar,
                contentPadding = PaddingValues(horizontal = 8.dp),
                onWebViewClick = {},
                onHelpClick = {},
                onLocalSourceHelpClick = {},
                onMangaClick = { navigator.push(MangaScreen(it.id, true)) },
                onMangaLongClick = {},
            )
        }
    }
}

/** One Home section: a horizontal row, or the real catalogue grid when toggled in place. */
@Composable
private fun LocalFeedSectionContent(
    screen: Screen,
    section: LocalFeedSection,
    itemsPerSection: Int,
    gridMode: Boolean,
    onToggleView: () -> Unit,
    feedModel: StandaloneFeedScreenModel,
    onClick: (StandaloneFeedScreenModel.FeedItem) -> Unit,
    onLoadMore: () -> Unit,
) {
    LocalSectionTitle(
        title = section.title,
        gridMode = gridMode,
        onToggle = onToggleView,
    )
    if (gridMode) {
        LocalFeedCatalogueGrid(screen, section.key, feedModel)
        return
    }

    val unique = remember(section.items) {
        section.items.distinctBy { it.isAnime to (it.localId ?: it.id) }
    }
    val pageSize = itemsPerSection.coerceAtLeast(1)
    var visibleCount by remember(section.key) { mutableStateOf(pageSize) }
    LaunchedEffect(pageSize) {
        if (visibleCount < pageSize) visibleCount = pageSize
    }

    if (unique.isNotEmpty()) {
        val visible = unique.take(visibleCount)
        val hasCachedMore = visibleCount < unique.size
        val canLoadMore = visible.isNotEmpty() && (hasCachedMore || section.hasNextPage)
        LocalFeedRow(
            items = visible,
            onClick = onClick,
            showLoadMore = canLoadMore,
            isLoadingMore = section.isLoadingMore && !hasCachedMore,
            loadMoreKey = "${section.key}-$visibleCount-${unique.size}-${section.hasNextPage}",
            onLoadMore = {
                if (hasCachedMore) {
                    visibleCount = (visibleCount + pageSize).coerceAtMost(unique.size)
                } else {
                    onLoadMore()
                }
            },
        )
    }
}

@Composable
private fun LocalFeedRow(
    items: List<StandaloneFeedScreenModel.FeedItem>,
    onClick: (StandaloneFeedScreenModel.FeedItem) -> Unit,
    showLoadMore: Boolean = false,
    isLoadingMore: Boolean = false,
    loadMoreKey: String = "load-more",
    onLoadMore: () -> Unit = {},
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items = items, key = { "${it.isAnime}-${it.localId ?: it.id}" }) { entry ->
            LocalFeedCard(entry, Modifier.width(120.dp)) { onClick(entry) }
        }
        if (showLoadMore) {
            item(key = loadMoreKey) {
                // Reaching the end of this row loads more into this same horizontal line.
                LaunchedEffect(Unit) { onLoadMore() }
                TextButton(onClick = onLoadMore, enabled = !isLoadingMore) {
                    Text(
                        stringResource(
                            if (isLoadingMore) {
                                MR.strings.standalone_feed_loading_more
                            } else {
                                MR.strings.standalone_feed_load_more
                            },
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun LocalFeedCard(
    item: StandaloneFeedScreenModel.FeedItem,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
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
