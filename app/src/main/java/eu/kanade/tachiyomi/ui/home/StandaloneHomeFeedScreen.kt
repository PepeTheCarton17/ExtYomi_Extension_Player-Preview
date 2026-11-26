package eu.kanade.tachiyomi.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.domain.ui.StandaloneScope
import eu.kanade.domain.ui.model.ContentFormat
import eu.kanade.domain.ui.model.HomeFeedSort
import eu.kanade.tachiyomi.ui.browse.anime.source.globalsearch.GlobalAnimeSearchScreen
import eu.kanade.tachiyomi.ui.browse.manga.source.globalsearch.GlobalMangaSearchScreen
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.material.PullRefresh
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

private enum class FeedFormat {
    ALL,
    ANIME,
    MANGA,
    MOVIE,
    SERIES,
    POPULAR,
    RECENT,
}

private fun StandaloneFeedScreenModel.FeedItem.matches(format: FeedFormat): Boolean = when (format) {
    FeedFormat.ALL -> true
    FeedFormat.ANIME -> isAnime
    FeedFormat.MANGA -> !isAnime
    FeedFormat.MOVIE ->
        ContentFormat.MOVIE in contentFormats ||
            this.format.equals("MOVIE", ignoreCase = true)
    FeedFormat.SERIES ->
        ContentFormat.SERIES in contentFormats ||
            this.format.equals("TV", ignoreCase = true) ||
            this.format.equals("TV_SHORT", ignoreCase = true) ||
            this.format.equals("ONA", ignoreCase = true)
    // Popular and Recent are complete sections of their own, not per-card format filters.
    FeedFormat.POPULAR, FeedFormat.RECENT -> true
}

private fun StandaloneFeedScreenModel.ContinueItem.matches(format: FeedFormat): Boolean = when (format) {
    FeedFormat.ALL, FeedFormat.ANIME, FeedFormat.MANGA ->
        format == FeedFormat.ALL || (format == FeedFormat.ANIME) == isAnime
    // A history entry has no published format/date, so it is hidden in those format chips.
    else -> false
}

private fun homeFeedComparator(sort: HomeFeedSort): Comparator<StandaloneFeedScreenModel.FeedItem> =
    when (sort) {
        HomeFeedSort.NONE -> Comparator { _, _ -> 0 }
        HomeFeedSort.ALPHABETICAL -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        HomeFeedSort.EPISODE_COUNT -> compareByDescending<StandaloneFeedScreenModel.FeedItem> {
            it.episodeCount ?: Int.MIN_VALUE
        }
        HomeFeedSort.AIRING_DATE -> compareByDescending<StandaloneFeedScreenModel.FeedItem> {
            it.airingAt ?: Long.MIN_VALUE
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StandaloneHomeFeedScreen(
    screen: Screen,
    screenModel: StandaloneFeedScreenModel,
    modifier: Modifier = Modifier,
    requestSettings: Boolean = false,
    onSettingsHandled: () -> Unit = {},
) {
    // A generated app has one bundled extension: its Home is catalogue-backed and never shows the
    // global, multi-source feed. It has its own filter icon and extension settings action.
    if (StandaloneScope.isActive) {
        StandaloneLocalHomeFeedScreen(
            screen = screen,
            screenModel = screenModel,
            modifier = modifier,
            requestSettings = requestSettings,
            onSettingsHandled = onSettingsHandled,
        )
        return
    }

    val state by screenModel.state.collectAsState()
    val navigator = LocalNavigator.currentOrThrow
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedFormat by remember { mutableStateOf(FeedFormat.ALL) }
    var showSettings by remember { mutableStateOf(false) }

    // Re-selecting the Home tab opens the feed settings, as in Animetail.
    LaunchedEffect(requestSettings) {
        if (requestSettings) {
            showSettings = true
            onSettingsHandled()
        }
    }

    val animeScope = StandaloneScope.appliesTo(StandaloneScope.KIND_ANIME)
    val mangaScope = StandaloneScope.appliesTo(StandaloneScope.KIND_MANGA) ||
        StandaloneScope.appliesTo(StandaloneScope.KIND_NOVEL)
    val extensionFilter = StandaloneScope.extensionPkg.ifBlank { null }

    val onFeedItemClick: (StandaloneFeedScreenModel.FeedItem) -> Unit = { item ->
        if (item.isAnime) {
            navigator.push(GlobalAnimeSearchScreen(item.title, extensionFilter))
        } else {
            navigator.push(
                GlobalMangaSearchScreen(
                    searchQuery = item.title,
                    extensionFilter = extensionFilter,
                    novelOnly = StandaloneScope.appliesTo(StandaloneScope.KIND_NOVEL),
                ),
            )
        }
    }

    val onContinueClick: (StandaloneFeedScreenModel.ContinueItem) -> Unit = { item ->
        val episodeId = item.episodeId
        val chapterId = item.chapterId
        when {
            item.isAnime && episodeId != null -> scope.launch {
                MainActivity.startPlayerActivity(context, item.id, episodeId, false)
            }
            !item.isAnime && chapterId != null -> {
                context.startActivity(ReaderActivity.newIntent(context, item.id, chapterId))
            }
            item.isAnime -> navigator.push(GlobalAnimeSearchScreen(item.title, extensionFilter))
            else -> navigator.push(
                GlobalMangaSearchScreen(
                    searchQuery = item.title,
                    extensionFilter = extensionFilter,
                    novelOnly = item.isNovel,
                ),
            )
        }
    }

    if (showSettings) {
        HostFeedSettingsDialog(
            state = state,
            onDismiss = { showSettings = false },
            onToggleFeatured = screenModel::toggleFeatured,
            onToggleContinue = screenModel::toggleContinue,
            onToggleRecommended = screenModel::toggleRecommended,
            onTogglePopular = screenModel::togglePopular,
            onToggleAutoScrollHero = screenModel::toggleAutoScrollHero,
            onSetItemsPerSection = screenModel::setItemsPerSection,
            onBlockedCategoriesChanged = screenModel::setBlockedCategories,
            onCategoryOrderChanged = screenModel::setCategoryOrder,
            onSortChanged = screenModel::setSortMode,
            onSourcesChanged = screenModel::onSourcePreferencesChanged,
        )
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
                    IconButton(
                        onClick = {
                            if (animeScope) {
                                navigator.push(GlobalAnimeSearchScreen("", extensionFilter))
                            } else {
                                navigator.push(
                                    GlobalMangaSearchScreen(
                                        searchQuery = "",
                                        extensionFilter = extensionFilter,
                                        novelOnly = StandaloneScope.appliesTo(StandaloneScope.KIND_NOVEL),
                                    ),
                                )
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = stringResource(MR.strings.action_search),
                        )
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(
                            imageVector = Icons.Filled.Tune,
                            contentDescription = stringResource(MR.strings.standalone_feed_settings),
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

            val visible = remember(
                state.trending,
                state.recommended,
                state.popular,
                state.recent,
                state.continueList,
                selectedFormat,
                state.sortMode,
                state.continueVisibleCount,
            ) {
                val comparator = homeFeedComparator(state.sortMode)

                // Availability is decided in the model against the enabled sources of the new
                // setting; the rows reveal itemsPerSection at a time and load further pages.
                fun List<StandaloneFeedScreenModel.FeedItem>.filtered() = filter {
                    it.matches(selectedFormat)
                }.sortedWith(comparator)

                FeedLists(
                    trending = state.trending.filtered(),
                    recommended = state.recommended.filtered(),
                    popular = state.popular.filtered(),
                    recent = state.recent.filtered(),
                    continueList = state.continueList
                        .filter { it.matches(selectedFormat) }
                        .sortedByDescending { it.seenAt }
                        .take(state.continueVisibleCount),
                )
            }

            LazyColumn(
                modifier = modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = selectedFormat == FeedFormat.ALL,
                            onClick = { selectedFormat = FeedFormat.ALL },
                            label = { Text(stringResource(MR.strings.standalone_feed_all)) },
                        )
                        // This composable is the host feed: anime, manga and novel all exist here.
                        FilterChip(
                            selected = selectedFormat == FeedFormat.ANIME,
                            onClick = { selectedFormat = FeedFormat.ANIME },
                            label = { Text(stringResource(AYMR.strings.label_anime)) },
                        )
                        FilterChip(
                            selected = selectedFormat == FeedFormat.MANGA,
                            onClick = { selectedFormat = FeedFormat.MANGA },
                            label = { Text(stringResource(AYMR.strings.label_manga)) },
                        )
                        FilterChip(
                            selected = selectedFormat == FeedFormat.MOVIE,
                            onClick = { selectedFormat = FeedFormat.MOVIE },
                            label = { Text(stringResource(MR.strings.standalone_feed_movies)) },
                        )
                        FilterChip(
                            selected = selectedFormat == FeedFormat.SERIES,
                            onClick = { selectedFormat = FeedFormat.SERIES },
                            label = { Text(stringResource(MR.strings.standalone_feed_series)) },
                        )
                        FilterChip(
                            selected = selectedFormat == FeedFormat.POPULAR,
                            onClick = { selectedFormat = FeedFormat.POPULAR },
                            label = { Text(stringResource(MR.strings.standalone_feed_popular)) },
                        )
                        FilterChip(
                            selected = selectedFormat == FeedFormat.RECENT,
                            onClick = { selectedFormat = FeedFormat.RECENT },
                            label = { Text(stringResource(MR.strings.standalone_feed_recent)) },
                        )
                    }
                }

                when (selectedFormat) {
                    FeedFormat.RECENT -> item {
                        FeedSection(
                            enabled = true,
                            title = stringResource(MR.strings.standalone_feed_recent),
                            feedItems = visible.recent,
                            onClick = onFeedItemClick,
                        )
                    }
                    FeedFormat.POPULAR -> item {
                        FeedSection(
                            enabled = true,
                            title = stringResource(MR.strings.standalone_feed_popular),
                            feedItems = visible.popular,
                            onClick = onFeedItemClick,
                        )
                    }
                    else -> {
                        if (state.showFeatured && visible.trending.isNotEmpty()) {
                            item {
                                HeroCarousel(
                                    items = visible.trending.take(6),
                                    autoScroll = state.autoScrollHero,
                                    onClick = onFeedItemClick,
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                )
                                if (state.sectionPages[StandaloneFeedScreenModel.SECTION_TRENDING]
                                        ?.hasNextPage == true
                                ) {
                                    TextButton(
                                        onClick = {
                                            screenModel.loadMoreSection(
                                                StandaloneFeedScreenModel.SECTION_TRENDING,
                                            )
                                        },
                                    ) {
                                        Text(stringResource(MR.strings.standalone_feed_load_more))
                                    }
                                }
                            }
                        }

                        if (state.showContinue && visible.continueList.isNotEmpty()) {
                            item {
                                SectionTitle(stringResource(MR.strings.standalone_feed_continue))
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    items(items = visible.continueList, key = { "continue-${it.id}" }) { item ->
                                        ContinueCard(item, onClick = { onContinueClick(item) })
                                    }
                                    if (state.continueList.count { it.matches(selectedFormat) } >
                                        visible.continueList.size
                                    ) {
                                        item(key = "continue-load-more") {
                                            TextButton(onClick = screenModel::loadMoreContinue) {
                                                Text(stringResource(MR.strings.standalone_feed_load_more))
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (state.showRecommended) {
                            item(key = "recommended") {
                                GlobalFeedRow(
                                    loadMoreKey = StandaloneFeedScreenModel.SECTION_RECOMMENDED,
                                    title = stringResource(MR.strings.standalone_feed_recommended),
                                    items = visible.recommended,
                                    itemsPerSection = state.itemsPerSection,
                                    hasNextPage = state.sectionPages[
                                        StandaloneFeedScreenModel.SECTION_RECOMMENDED,
                                    ]?.hasNextPage == true,
                                    isLoadingMore = state.sectionPages[
                                        StandaloneFeedScreenModel.SECTION_RECOMMENDED,
                                    ]?.isLoadingMore == true,
                                    onClick = onFeedItemClick,
                                    onLoadMore = {
                                        screenModel.loadMoreSection(
                                            StandaloneFeedScreenModel.SECTION_RECOMMENDED,
                                        )
                                    },
                                )
                            }
                        }
                        if (state.showPopular) {
                            item(key = "popular") {
                                GlobalFeedRow(
                                    loadMoreKey = StandaloneFeedScreenModel.SECTION_POPULAR,
                                    title = stringResource(MR.strings.standalone_feed_popular),
                                    items = visible.popular,
                                    itemsPerSection = state.itemsPerSection,
                                    hasNextPage = state.sectionPages[
                                        StandaloneFeedScreenModel.SECTION_POPULAR,
                                    ]?.hasNextPage == true,
                                    isLoadingMore = state.sectionPages[
                                        StandaloneFeedScreenModel.SECTION_POPULAR,
                                    ]?.isLoadingMore == true,
                                    onClick = onFeedItemClick,
                                    onLoadMore = {
                                        screenModel.loadMoreSection(
                                            StandaloneFeedScreenModel.SECTION_POPULAR,
                                        )
                                    },
                                )
                            }
                        }

                        // Generated rows: genres, then per-extension "de todo lo disponible",
                        // then películas/series. They respect the category filter and source priority.
                        state.feedRows.forEach { row ->
                            val rowItems = row.items.filter { it.matches(selectedFormat) }
                            if (rowItems.isEmpty()) return@forEach
                            item(key = row.key) {
                                GlobalFeedRow(
                                    loadMoreKey = row.key,
                                    title = feedRowTitle(row),
                                    subtitle = row.sourceName?.let {
                                        stringResource(MR.strings.home_feed_from_all)
                                    },
                                    items = rowItems,
                                    itemsPerSection = state.itemsPerSection,
                                    hasNextPage = state.sectionPages[row.key]?.hasNextPage == true,
                                    isLoadingMore = state.sectionPages[row.key]?.isLoadingMore == true,
                                    onClick = onFeedItemClick,
                                    onLoadMore = { screenModel.loadMoreSection(row.key) },
                                )
                            }
                        }
                    }
                }

                val nothingToShow = when (selectedFormat) {
                    FeedFormat.RECENT -> visible.recent.isEmpty()
                    FeedFormat.POPULAR -> visible.popular.isEmpty()
                    else -> visible.trending.isEmpty() &&
                        visible.recommended.isEmpty() &&
                        visible.popular.isEmpty() &&
                        visible.continueList.isEmpty()
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
}

private data class FeedLists(
    val trending: List<StandaloneFeedScreenModel.FeedItem>,
    val recommended: List<StandaloneFeedScreenModel.FeedItem>,
    val popular: List<StandaloneFeedScreenModel.FeedItem>,
    val recent: List<StandaloneFeedScreenModel.FeedItem>,
    val continueList: List<StandaloneFeedScreenModel.ContinueItem>,
)

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun FeedSection(
    enabled: Boolean,
    title: String,
    feedItems: List<StandaloneFeedScreenModel.FeedItem>,
    onClick: (StandaloneFeedScreenModel.FeedItem) -> Unit,
) {
    if (!enabled || feedItems.isEmpty()) return
    SectionTitle(title)
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items = feedItems, key = { "${it.isAnime}-${it.id}" }) { item ->
            FeedCard(item, onClick = { onClick(item) })
        }
    }
}

/** Horizontal row that reveals itemsPerSection cards at a time, then loads another page. */
@Composable
private fun GlobalFeedRow(
    loadMoreKey: String,
    title: String,
    items: List<StandaloneFeedScreenModel.FeedItem>,
    itemsPerSection: Int,
    hasNextPage: Boolean,
    isLoadingMore: Boolean,
    subtitle: String? = null,
    onClick: (StandaloneFeedScreenModel.FeedItem) -> Unit,
    onLoadMore: () -> Unit,
) {
    if (items.isEmpty()) return
    val pageSize = itemsPerSection.coerceAtLeast(1)
    var visibleCount by remember(loadMoreKey) { mutableStateOf(pageSize) }
    LaunchedEffect(pageSize) {
        if (visibleCount < pageSize) visibleCount = pageSize
    }
    val visible = items.take(visibleCount)

    SectionTitle(title)
    if (subtitle != null) {
        Text(
            text = subtitle,
            modifier = Modifier.padding(horizontal = 16.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(items = visible, key = { "${it.isAnime}-${it.localId ?: it.id}" }) { item ->
            FeedCard(item, onClick = { onClick(item) })
        }
        val hasCachedMore = visibleCount < items.size
        if (visible.isNotEmpty() && (hasCachedMore || hasNextPage)) {
            item(key = "$loadMoreKey-$visibleCount-${items.size}") {
                // Reaching the end of the row asks the model for the next page.
                LaunchedEffect(Unit) {
                    if (!hasCachedMore) onLoadMore()
                }
                TextButton(onClick = {
                    if (hasCachedMore) {
                        visibleCount = (visibleCount + pageSize).coerceAtMost(items.size)
                    } else {
                        onLoadMore()
                    }
                }, enabled = !isLoadingMore) {
                    Text(
                        stringResource(
                            if (isLoadingMore && !hasCachedMore) {
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
private fun feedRowTitle(row: StandaloneFeedScreenModel.FeedRow): String {
    val kind = when (row.kind) {
        StandaloneFeedScreenModel.KIND_ANIME -> stringResource(MR.strings.home_feed_kind_anime)
        StandaloneFeedScreenModel.KIND_MANGA -> stringResource(MR.strings.home_feed_kind_manga)
        else -> stringResource(MR.strings.home_feed_kind_novel)
    }
    return when {
        row.sourceName != null -> stringResource(MR.strings.home_feed_row_source, row.sourceName)
        row.format != null -> stringResource(
            MR.strings.home_feed_row_format,
            when (row.format) {
                ContentFormat.MOVIE -> stringResource(MR.strings.home_feed_movies)
                else -> stringResource(MR.strings.home_feed_series)
            },
            kind.lowercase(),
        )
        else -> stringResource(
            MR.strings.home_feed_row_category,
            kind,
            row.category?.lowercase().orEmpty(),
        )
    }
}

@Composable
private fun HeroCarousel(
    items: List<StandaloneFeedScreenModel.FeedItem>,
    autoScroll: Boolean,
    onClick: (StandaloneFeedScreenModel.FeedItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(pageCount = { items.size })
    if (autoScroll && items.size > 1) {
        LaunchedEffect(pagerState, items.size) {
            while (true) {
                delay(5000)
                pagerState.animateScrollToPage((pagerState.currentPage + 1) % items.size)
            }
        }
    }

    HorizontalPager(
        state = pagerState,
        modifier = modifier
            .fillMaxWidth()
            .height(210.dp)
            .clip(RoundedCornerShape(12.dp)),
    ) { page ->
        val item = items[page]
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable { onClick(item) },
        ) {
            AsyncImage(
                model = item.coverUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.65f))
                    .padding(12.dp),
            ) {
                Text(
                    text = item.title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.rating.isNotBlank()) {
                    Text(
                        text = "★ ${item.rating}",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Text(
                    text = item.genres.take(3).joinToString(" • "),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun FeedCard(
    item: StandaloneFeedScreenModel.FeedItem,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(120.dp)
            .clickable(onClick = onClick),
    ) {
        Box {
            AsyncImage(
                model = item.coverUrl,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            if (item.rating.isNotBlank()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(text = item.rating, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (item.available == false) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.9f),
                ) {
                    Text(
                        text = stringResource(MR.strings.standalone_feed_not_available),
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
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
private fun ContinueCard(
    item: StandaloneFeedScreenModel.ContinueItem,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .width(220.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = item.coverUrl,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(64.dp)
                .height(90.dp),
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

@Composable
internal fun FeedSettingsDialog(
    state: StandaloneFeedScreenModel.State,
    onDismiss: () -> Unit,
    onToggleFeatured: () -> Unit,
    onToggleContinue: () -> Unit,
    onToggleRecommended: () -> Unit,
    onTogglePopular: () -> Unit,
    onToggleAutoScrollHero: () -> Unit,
    onToggleOnlyAvailable: () -> Unit,
    onSetItemsPerSection: (Int) -> Unit,
    showOnlyAvailable: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.standalone_feed_settings)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StandaloneSyncToggle()
                SwitchRow(
                    label = stringResource(MR.strings.standalone_feed_show_featured),
                    checked = state.showFeatured,
                    onCheckedChange = onToggleFeatured,
                )
                SwitchRow(
                    label = stringResource(MR.strings.standalone_feed_show_continue),
                    checked = state.showContinue,
                    onCheckedChange = onToggleContinue,
                )
                SwitchRow(
                    label = stringResource(MR.strings.standalone_feed_show_recommended),
                    checked = state.showRecommended,
                    onCheckedChange = onToggleRecommended,
                )
                SwitchRow(
                    label = stringResource(MR.strings.standalone_feed_show_popular),
                    checked = state.showPopular,
                    onCheckedChange = onTogglePopular,
                )
                SwitchRow(
                    label = stringResource(MR.strings.standalone_feed_auto_scroll),
                    checked = state.autoScrollHero,
                    onCheckedChange = onToggleAutoScrollHero,
                )
                if (showOnlyAvailable) {
                    SwitchRow(
                        label = stringResource(MR.strings.standalone_feed_only_available),
                        checked = state.onlyAvailable,
                        onCheckedChange = onToggleOnlyAvailable,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(
                            MR.strings.standalone_feed_items_per_section,
                            state.itemsPerSection,
                        ),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { onSetItemsPerSection(state.itemsPerSection - 1) }) {
                        Text("−")
                    }
                    TextButton(onClick = { onSetItemsPerSection(state.itemsPerSection + 1) }) {
                        Text("+")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
    )
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Switch(checked = checked, onCheckedChange = { onCheckedChange() })
    }
}
