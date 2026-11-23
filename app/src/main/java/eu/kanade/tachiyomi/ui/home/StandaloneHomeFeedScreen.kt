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
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.domain.ui.StandaloneScope
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
}

private fun StandaloneFeedScreenModel.FeedItem.matches(format: FeedFormat): Boolean = when (format) {
    FeedFormat.ALL -> true
    FeedFormat.ANIME -> isAnime
    FeedFormat.MANGA -> !isAnime
    FeedFormat.MOVIE -> this.format.equals("MOVIE", ignoreCase = true)
    FeedFormat.SERIES -> this.format.equals("TV", ignoreCase = true) ||
        this.format.equals("TV_SHORT", ignoreCase = true) ||
        this.format.equals("ONA", ignoreCase = true)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StandaloneHomeFeedScreen(
    screenModel: StandaloneFeedScreenModel,
    modifier: Modifier = Modifier,
) {
    val state by screenModel.state.collectAsState()
    val navigator = LocalNavigator.currentOrThrow
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedFormat by remember { mutableStateOf(FeedFormat.ALL) }
    var showSettings by remember { mutableStateOf(false) }

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
                state.continueList,
                selectedFormat,
                state.onlyAvailable,
                state.itemsPerSection,
            ) {
                fun List<StandaloneFeedScreenModel.FeedItem>.filtered() = filter {
                    it.matches(selectedFormat) && (!state.onlyAvailable || it.available != false)
                }.take(state.itemsPerSection)

                FeedLists(
                    trending = state.trending.filtered(),
                    recommended = state.recommended.filtered(),
                    popular = state.popular.filtered(),
                    continueList = state.continueList.filter { if (animeScope) it.isAnime else !it.isAnime },
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
                        if (animeScope) {
                            FilterChip(
                                selected = selectedFormat == FeedFormat.ANIME,
                                onClick = { selectedFormat = FeedFormat.ANIME },
                                label = { Text(stringResource(AYMR.strings.label_anime)) },
                            )
                        }
                        if (mangaScope) {
                            FilterChip(
                                selected = selectedFormat == FeedFormat.MANGA,
                                onClick = { selectedFormat = FeedFormat.MANGA },
                                label = { Text(stringResource(AYMR.strings.label_manga)) },
                            )
                        }
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
                    }
                }

                if (state.showFeatured && visible.trending.isNotEmpty()) {
                    item {
                        HeroCarousel(
                            items = visible.trending.take(6),
                            autoScroll = state.autoScrollHero,
                            onClick = onFeedItemClick,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
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
                        }
                    }
                }

                feedSection(
                    enabled = state.showRecommended,
                    title = stringResource(MR.strings.standalone_feed_recommended),
                    feedItems = visible.recommended,
                    onClick = onFeedItemClick,
                )
                feedSection(
                    enabled = state.showPopular,
                    title = stringResource(MR.strings.standalone_feed_popular),
                    feedItems = visible.popular,
                    onClick = onFeedItemClick,
                )

                if (visible.trending.isEmpty() && visible.recommended.isEmpty() &&
                    visible.popular.isEmpty() && visible.continueList.isEmpty()
                ) {
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
private fun androidx.compose.foundation.lazy.LazyListScope.feedSection(
    enabled: Boolean,
    title: String,
    feedItems: List<StandaloneFeedScreenModel.FeedItem>,
    onClick: (StandaloneFeedScreenModel.FeedItem) -> Unit,
) {
    if (!enabled || feedItems.isEmpty()) return
    item {
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
private fun FeedSettingsDialog(
    state: StandaloneFeedScreenModel.State,
    onDismiss: () -> Unit,
    onToggleFeatured: () -> Unit,
    onToggleContinue: () -> Unit,
    onToggleRecommended: () -> Unit,
    onTogglePopular: () -> Unit,
    onToggleAutoScrollHero: () -> Unit,
    onToggleOnlyAvailable: () -> Unit,
    onSetItemsPerSection: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.standalone_feed_settings)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                SwitchRow(
                    label = stringResource(MR.strings.standalone_feed_only_available),
                    checked = state.onlyAvailable,
                    onCheckedChange = onToggleOnlyAvailable,
                )
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
