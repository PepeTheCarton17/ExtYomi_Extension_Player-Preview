package eu.kanade.tachiyomi.ui.home

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.entries.anime.model.toDomainAnime
import eu.kanade.domain.entries.manga.model.toDomainManga
import eu.kanade.domain.ui.StandaloneScope
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.data.home.HomeFeedDataSource
import eu.kanade.tachiyomi.data.home.HomeFeedMedia
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import logcat.LogPriority
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.entries.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.entries.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.history.anime.interactor.GetAnimeHistory
import tachiyomi.domain.history.manga.interactor.GetMangaHistory
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.source.manga.service.MangaSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Feed for the Animetail-style Home.
 *
 * In a generated standalone the cards are entries of the single bundled extension: the public
 * feed is only a source of suggestions, and a suggestion is shown only when its normalised title
 * matches an entry of the extension exactly. In the generator (no standalone scope) the classic
 * global feed is kept unchanged.
 */
class StandaloneFeedScreenModel : StateScreenModel<StandaloneFeedScreenModel.State>(State()) {

    private val dataSource = HomeFeedDataSource()
    private val uiPreferences: UiPreferences = Injekt.get()
    private val getAnimeHistory: GetAnimeHistory = Injekt.get()
    private val getMangaHistory: GetMangaHistory = Injekt.get()
    private val animeSourceManager: AnimeSourceManager = Injekt.get()
    private val mangaSourceManager: MangaSourceManager = Injekt.get()
    private val networkToLocalAnime: NetworkToLocalAnime = Injekt.get()
    private val networkToLocalManga: NetworkToLocalManga = Injekt.get()

    private val isStandalone = StandaloneScope.isActive

    // In the generator (no standalone config) the feed is global: show both anime and manga.
    private val isAnimeScope = !isStandalone || StandaloneScope.appliesTo(StandaloneScope.KIND_ANIME)
    private val isNovelScope = StandaloneScope.appliesTo(StandaloneScope.KIND_NOVEL)
    private val isMangaContent = !isStandalone ||
        StandaloneScope.appliesTo(StandaloneScope.KIND_MANGA) ||
        isNovelScope
    private val sourceId = StandaloneScope.sourceId

    // Query and filters selected in the standalone Home. A search can auto-select a source filter
    // by name, so remember which one was automatic in order to clear it on the next query.
    private var currentAnimeFilters: AnimeFilterList? = null
    private var currentMangaFilters: FilterList? = null
    private var autoSelectedAnimeCategory: String? = null
    private var autoSelectedMangaCategory: String? = null
    private var pendingSearchQuery = ""
    private var currentTrendingName: String? = null

    /** Current Home filters, exposed so the in-place catalogue grid shows the same listing. */
    val activeAnimeFilters: AnimeFilterList? get() = currentAnimeFilters
    val activeMangaFilters: FilterList? get() = currentMangaFilters
    val activeSearchQuery: String get() = pendingSearchQuery
    val activeTrendingName: String? get() = currentTrendingName

    init {
        mutableState.update {
            it.copy(
                itemsPerSection = uiPreferences.newHomeItemsPerSection().get().coerceIn(
                    MIN_ITEMS_PER_SECTION,
                    MAX_ITEMS_PER_SECTION,
                ),
            )
        }
        refresh()
    }

    fun refresh() {
        screenModelScope.launchIO {
            mutableState.update { it.copy(isRefreshing = true, filteredEntries = null) }

            val continueList = loadContinue()

            if (isStandalone) {
                refreshFromSource(continueList)
                return@launchIO
            }

            val trendingAnime = if (isAnimeScope) safeFeed(true, "TRENDING_DESC") else emptyList()
            val trendingManga = if (isMangaContent) safeFeed(false, "TRENDING_DESC") else emptyList()
            val trending = (trendingAnime + trendingManga).map(::toGlobalFeedItem)

            // Use the first genre of the top trending title to give recommendations a personal touch.
            val genre = trending.firstOrNull()?.genres?.firstOrNull()
            val recommendedAnime = if (isAnimeScope) safeFeed(true, "SCORE_DESC", genre) else emptyList()
            val recommendedManga = if (isMangaContent) safeFeed(false, "SCORE_DESC", genre) else emptyList()
            val recommended = (recommendedAnime + recommendedManga).map(::toGlobalFeedItem)

            val popularAnime = if (isAnimeScope) safeFeed(true, "POPULARITY_DESC") else emptyList()
            val popularManga = if (isMangaContent) safeFeed(false, "POPULARITY_DESC") else emptyList()
            val popular = (popularAnime + popularManga).map(::toGlobalFeedItem)
            // "Recent" uses the same latest listing as the normal catalogue, not history.
            val recent = loadGlobalRecent()

            mutableState.update {
                it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    trending = trending,
                    recommended = recommended,
                    popular = popular,
                    recent = recent,
                    continueList = continueList,
                )
            }
        }
    }

    /** Applies the Home's filter sheet directly to the bundled extension and shows only its hits. */
    fun applyAnimeFilters(filters: AnimeFilterList) {
        currentAnimeFilters = filters
        autoSelectedAnimeCategory = null
        screenModelScope.launchIO {
            mutableState.update { it.copy(isRefreshing = true, filteredEntries = emptyList()) }
            val (items, hasNext) = searchAnimePage(filters, pendingSearchQuery)
            mutableState.update {
                it.copy(
                    isRefreshing = false,
                    filteredEntries = items,
                    sectionPages = it.sectionPages + (SECTION_FILTERED to FeedPagination(1, hasNext)),
                )
            }
        }
    }

    fun applyMangaFilters(filters: FilterList) {
        currentMangaFilters = filters
        autoSelectedMangaCategory = null
        screenModelScope.launchIO {
            mutableState.update { it.copy(isRefreshing = true, filteredEntries = emptyList()) }
            val (items, hasNext) = searchMangaPage(filters, pendingSearchQuery)
            mutableState.update {
                it.copy(
                    isRefreshing = false,
                    filteredEntries = items,
                    sectionPages = it.sectionPages + (SECTION_FILTERED to FeedPagination(1, hasNext)),
                )
            }
        }
    }

    /**
     * Searches inside the standalone Home instead of navigating to another catalogue. When the
     * query names one of the source's filters (a genre, for example), that filter is selected and
     * the search uses it, mirroring the catalogue's genre search; otherwise the text is passed to
     * the source together with the filters already chosen in the Home's filter sheet.
     */
    fun search(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            clearFilters()
            return
        }
        pendingSearchQuery = trimmed
        if (StandaloneScope.appliesTo(StandaloneScope.KIND_ANIME)) {
            searchAnime(trimmed)
        } else {
            searchManga(trimmed)
        }
    }

    private fun searchAnime(query: String) {
        val source = animeSourceManager.get(sourceId) as? AnimeCatalogueSource ?: return
        val filters = currentAnimeFilters ?: source.safeFilterList()
        currentAnimeFilters = filters
        autoSelectedAnimeCategory?.let { clearAnimeCategory(filters, it) }
        autoSelectedAnimeCategory = null
        val matched = applyAnimeCategory(filters, query)
        if (matched) autoSelectedAnimeCategory = query
        // The catalogue does the same for a genre: it searches with the filter, not as plain text.
        val effectiveQuery = if (matched) "" else query
        pendingSearchQuery = effectiveQuery
        screenModelScope.launchIO {
            mutableState.update {
                it.copy(
                    isRefreshing = true,
                    filteredEntries = emptyList(),
                    searchQuery = query,
                    activeFilterName = if (matched) query else null,
                    sectionPages = it.sectionPages + (SECTION_FILTERED to FeedPagination()),
                )
            }
            val (items, hasNext) = searchAnimePage(filters, effectiveQuery)
            mutableState.update {
                it.copy(
                    isRefreshing = false,
                    filteredEntries = items,
                    sectionPages = it.sectionPages + (SECTION_FILTERED to FeedPagination(1, hasNext)),
                )
            }
        }
    }

    private fun searchManga(query: String) {
        val source = mangaSourceManager.get(sourceId) as? CatalogueSource ?: return
        val filters = currentMangaFilters ?: source.safeFilterList()
        currentMangaFilters = filters
        autoSelectedMangaCategory?.let { clearMangaCategory(filters, it) }
        autoSelectedMangaCategory = null
        val matched = applyMangaCategory(filters, query)
        if (matched) autoSelectedMangaCategory = query
        val effectiveQuery = if (matched) "" else query
        pendingSearchQuery = effectiveQuery
        screenModelScope.launchIO {
            mutableState.update {
                it.copy(
                    isRefreshing = true,
                    filteredEntries = emptyList(),
                    searchQuery = query,
                    activeFilterName = if (matched) query else null,
                    sectionPages = it.sectionPages + (SECTION_FILTERED to FeedPagination()),
                )
            }
            val (items, hasNext) = searchMangaPage(filters, effectiveQuery)
            mutableState.update {
                it.copy(
                    isRefreshing = false,
                    filteredEntries = items,
                    sectionPages = it.sectionPages + (SECTION_FILTERED to FeedPagination(1, hasNext)),
                )
            }
        }
    }

    fun clearFilters() {
        currentAnimeFilters = null
        currentMangaFilters = null
        autoSelectedAnimeCategory = null
        autoSelectedMangaCategory = null
        pendingSearchQuery = ""
        mutableState.update { state ->
            state.copy(
                filteredEntries = null,
                searchQuery = "",
                activeFilterName = null,
                sectionPages = state.sectionPages - SECTION_FILTERED,
            )
        }
    }

    fun toggleFeatured() = mutableState.update { it.copy(showFeatured = !it.showFeatured) }

    fun toggleContinue() = mutableState.update { it.copy(showContinue = !it.showContinue) }

    fun toggleRecommended() = mutableState.update { it.copy(showRecommended = !it.showRecommended) }

    fun togglePopular() = mutableState.update { it.copy(showPopular = !it.showPopular) }

    fun toggleAutoScrollHero() = mutableState.update { it.copy(autoScrollHero = !it.autoScrollHero) }

    fun toggleOnlyAvailable() = mutableState.update { it.copy(onlyAvailable = !it.onlyAvailable) }

    fun setItemsPerSection(count: Int) {
        val coerced = count.coerceIn(MIN_ITEMS_PER_SECTION, MAX_ITEMS_PER_SECTION)
        uiPreferences.newHomeItemsPerSection().set(coerced)
        mutableState.update { it.copy(itemsPerSection = coerced) }
    }

    private suspend fun safeFeed(
        anime: Boolean,
        sort: String,
        genre: String? = null,
    ): List<HomeFeedMedia> {
        return runCatching { dataSource.getMedia(anime, sort, genre) }.getOrDefault(emptyList())
    }

    private fun toGlobalFeedItem(media: HomeFeedMedia) = FeedItem(
        id = media.id,
        title = media.title,
        coverUrl = media.coverUrl,
        isAnime = media.isAnime,
        rating = media.rating,
        synopsis = media.synopsis,
        format = media.format,
        genres = media.genres,
    )

    /**
     * Host: "Recent" uses each enabled extension's latest listing, the same source as the normal
     * catalogue's Latest page. History and library updates are deliberately not used here.
     */
    private suspend fun loadGlobalRecent(): List<FeedItem> = withIOContext {
        val animeSources = if (isAnimeScope) animeSourceManager.getCatalogueSources() else emptyList()
        val mangaSources = if (isMangaContent) mangaSourceManager.getCatalogueSources() else emptyList()
        coroutineScope {
            val animeRecent = async {
                animeSources.take(MAX_RECENT_SOURCES).mapNotNull { source ->
                    val page = runCatching {
                        withTimeout(SEARCH_TIMEOUT) { source.getLatestUpdates(1) }
                    }.getOrNull() ?: return@mapNotNull null
                    page.animes.take(RECENT_PER_SOURCE).map { anime ->
                        FeedItem(
                            id = source.id * 31L + anime.url.hashCode().toLong(),
                            title = anime.title,
                            coverUrl = anime.thumbnail_url,
                            isAnime = true,
                            rating = "",
                            synopsis = anime.description.orEmpty(),
                            format = "",
                            genres = anime.getGenres().orEmpty(),
                            available = true,
                        )
                    }
                }.flatten()
            }
            val mangaRecent = async {
                mangaSources.take(MAX_RECENT_SOURCES).mapNotNull { source ->
                    val page = runCatching {
                        withTimeout(SEARCH_TIMEOUT) { source.getLatestUpdates(1) }
                    }.getOrNull() ?: return@mapNotNull null
                    page.mangas.take(RECENT_PER_SOURCE).map { manga ->
                        FeedItem(
                            id = source.id * 31L + manga.url.hashCode().toLong(),
                            title = manga.title,
                            coverUrl = manga.thumbnail_url,
                            isAnime = false,
                            rating = "",
                            synopsis = manga.description.orEmpty(),
                            format = "",
                            genres = manga.getGenres().orEmpty(),
                            available = true,
                        )
                    }
                }.flatten()
            }
            (animeRecent.await() + mangaRecent.await())
                .distinctBy { normalized(it.title) to it.isAnime }
        }
    }

    private suspend fun loadContinue(): List<ContinueItem> = withIOContext {
        buildList {
            if (isAnimeScope) {
                getAnimeHistory.subscribe("")
                    .first()
                    .filter { !isStandalone || it.coverData.sourceId == sourceId }
                    .take(8)
                    .forEach { history ->
                        add(
                            ContinueItem(
                                id = history.animeId,
                                episodeId = history.episodeId,
                                chapterId = null,
                                title = history.title,
                                subtitle = "Ep. ${history.episodeNumber}",
                                coverUrl = history.coverData.url,
                                isAnime = true,
                                isNovel = false,
                            ),
                        )
                    }
            }
            if (isMangaContent) {
                getMangaHistory.subscribe("")
                    .first()
                    .filter {
                        !isStandalone || (it.coverData.sourceId == sourceId && it.isNovel == isNovelScope)
                    }
                    .take(8)
                    .forEach { history ->
                        add(
                            ContinueItem(
                                id = history.mangaId,
                                episodeId = null,
                                chapterId = history.chapterId,
                                title = history.title,
                                subtitle = "Cap. ${history.chapterNumber}",
                                coverUrl = history.coverData.url,
                                isAnime = false,
                                isNovel = history.isNovel,
                            ),
                        )
                    }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Standalone: the catalogue of the bundled extension is the source of truth.
    // ---------------------------------------------------------------------------

    private suspend fun refreshFromSource(continueList: List<ContinueItem>) {
        val animeScope = StandaloneScope.appliesTo(StandaloneScope.KIND_ANIME)

        // "Todo" and "Populares" start from the catalogue's popular page and paginate from there.
        val popularResult = if (animeScope) popularAnimePage(1) else popularMangaPage(1)
        val allEntries = popularResult.first

        val (trendingName, allCategoryNames) = if (animeScope) {
            val source = animeSourceManager.get(sourceId) as? AnimeCatalogueSource
            val trendingName = source?.let(::animeTrendingFilterName)
            trendingName to source?.let(::animeCategoryNames).orEmpty()
        } else {
            val source = mangaSourceManager.get(sourceId) as? CatalogueSource
            val trendingName = source?.let(::mangaTrendingFilterName)
            trendingName to source?.let(::mangaCategoryNames).orEmpty()
        }
        currentTrendingName = trendingName
        // Trending and recents are their own sections, not duplicated as genre categories.
        val categoryNames = allCategoryNames.filterNot { name ->
            name.equals(trendingName, ignoreCase = true) || isRecentName(name)
        }

        suspend fun fetchFilter(name: String): Pair<List<FeedItem>, Boolean> =
            if (animeScope) searchAnimeWith(name) else searchMangaWith(name)

        val trendingResult = trendingName?.let { fetchFilter(it) } ?: (emptyList<FeedItem>() to false)
        // "Recientes" uses the same latest listing as the normal catalogue.
        val recentResult = if (animeScope) latestAnimePage(1) else latestMangaPage(1)
        val categories = buildList {
            categoryNames.forEach { name ->
                val result = fetchFilter(name)
                if (result.first.isNotEmpty()) add(FeedCategory(name, result.first, result.second))
            }
        }

        // External APIs only suggest titles. A suggestion becomes a card only after its title was
        // found, with an exact normalised match, in the bundled extension. Only ask for titles of
        // the kind this standalone actually bundles (novels use the manga source).
        suspend fun suggestions(sort: String): List<HomeFeedMedia> {
            val animeHits = if (animeScope) safeFeed(true, sort) else emptyList()
            val mangaHits = if (animeScope) emptyList() else safeFeed(false, sort)
            return animeHits + mangaHits
        }
        val matchedTrending = matchSuggestions(suggestions("TRENDING_DESC"))
        val matchedRecommended = matchSuggestions(suggestions("SCORE_DESC"))
        val matchedPopular = matchSuggestions(suggestions("POPULARITY_DESC"))

        val finalTrending = if (trendingResult.first.isNotEmpty()) trendingResult.first else matchedTrending
        val sectionPages = buildMap {
            if (trendingResult.first.isNotEmpty()) {
                put(SECTION_TRENDING, FeedPagination(1, trendingResult.second))
            }
            if (recentResult.first.isNotEmpty()) {
                put(SECTION_RECENT, FeedPagination(1, recentResult.second))
            }
            if (popularResult.first.isNotEmpty()) {
                put(SECTION_POPULAR, FeedPagination(1, popularResult.second))
            }
            put(SECTION_ALL, FeedPagination(1, popularResult.second))
            categories.forEach { put(categoryKey(it.name), FeedPagination(1, it.hasNextPage)) }
        }
        mutableState.update {
            it.copy(
                isLoading = false,
                isRefreshing = false,
                allEntries = allEntries,
                popular = popularResult.first.ifEmpty {
                    matchedPopular.ifEmpty { allEntries.take(POPULAR_FALLBACK_COUNT) }
                },
                recommended = matchedRecommended,
                trending = finalTrending,
                recent = recentResult.first,
                // Trending is a real section only when the extension exposes a trending filter.
                hasTrending = trendingName != null && finalTrending.isNotEmpty(),
                categorySections = categories,
                sectionPages = sectionPages,
                continueList = continueList,
            )
        }
    }

    /** Latest updates: the same listing the normal catalogue shows as "Recientes". */
    private suspend fun latestAnimePage(page: Int): Pair<List<FeedItem>, Boolean> = withIOContext {
        val source = animeSourceManager.get(sourceId) as? AnimeCatalogueSource
            ?: return@withIOContext emptyList<FeedItem>() to false
        runCatching {
            withTimeout(PAGE_TIMEOUT) { source.getLatestUpdates(page) }
        }.getOrNull()?.let { result ->
            result.animes.mapNotNull { anime ->
                runCatching {
                    val local = networkToLocalAnime.await(anime.toDomainAnime(sourceId))
                    anime.toSourceFeedItem(local.id)
                }.getOrNull()
            } to result.hasNextPage
        } ?: (emptyList<FeedItem>() to false)
    }

    private suspend fun latestMangaPage(page: Int): Pair<List<FeedItem>, Boolean> = withIOContext {
        val source = mangaSourceManager.get(sourceId) as? CatalogueSource
            ?: return@withIOContext emptyList<FeedItem>() to false
        runCatching {
            withTimeout(PAGE_TIMEOUT) { source.getLatestUpdates(page) }
        }.getOrNull()?.let { result ->
            result.mangas.mapNotNull { manga ->
                runCatching {
                    val local = networkToLocalManga.await(manga.toDomainManga(sourceId))
                    manga.toSourceFeedItem(local.id)
                }.getOrNull()
            } to result.hasNextPage
        } ?: (emptyList<FeedItem>() to false)
    }

    /** Popular listing: the same listing the normal catalogue shows as "Populares". */
    private suspend fun popularAnimePage(page: Int): Pair<List<FeedItem>, Boolean> = withIOContext {
        val source = animeSourceManager.get(sourceId) as? AnimeCatalogueSource
            ?: return@withIOContext emptyList<FeedItem>() to false
        runCatching {
            withTimeout(PAGE_TIMEOUT) { source.getPopularAnime(page) }
        }.getOrNull()?.let { result ->
            result.animes.mapNotNull { anime ->
                runCatching {
                    val local = networkToLocalAnime.await(anime.toDomainAnime(sourceId))
                    anime.toSourceFeedItem(local.id)
                }.getOrNull()
            } to result.hasNextPage
        } ?: (emptyList<FeedItem>() to false)
    }

    private suspend fun popularMangaPage(page: Int): Pair<List<FeedItem>, Boolean> = withIOContext {
        val source = mangaSourceManager.get(sourceId) as? CatalogueSource
            ?: return@withIOContext emptyList<FeedItem>() to false
        runCatching {
            withTimeout(PAGE_TIMEOUT) { source.getPopularManga(page) }
        }.getOrNull()?.let { result ->
            result.mangas.mapNotNull { manga ->
                runCatching {
                    val local = networkToLocalManga.await(manga.toDomainManga(sourceId))
                    manga.toSourceFeedItem(local.id)
                }.getOrNull()
            } to result.hasNextPage
        } ?: (emptyList<FeedItem>() to false)
    }

    private suspend fun searchAnimeWith(
        name: String,
        page: Int = 1,
    ): Pair<List<FeedItem>, Boolean> = withIOContext {
        val source = animeSourceManager.get(sourceId) as? AnimeCatalogueSource
            ?: return@withIOContext emptyList<FeedItem>() to false
        runCatching {
            withTimeout(PAGE_TIMEOUT) {
                val filters = source.safeFilterList()
                if (!applyAnimeNamedFilter(filters, name)) return@withTimeout emptyList<FeedItem>() to false
                val result = source.getSearchAnime(page, "", filters)
                result.animes.mapNotNull { anime ->
                    runCatching {
                        val local = networkToLocalAnime.await(anime.toDomainAnime(sourceId))
                        anime.toSourceFeedItem(local.id)
                    }.getOrNull()
                } to result.hasNextPage
            }
        }.getOrDefault(emptyList<FeedItem>() to false)
    }

    private suspend fun searchMangaWith(
        name: String,
        page: Int = 1,
    ): Pair<List<FeedItem>, Boolean> = withIOContext {
        val source = mangaSourceManager.get(sourceId) as? CatalogueSource
            ?: return@withIOContext emptyList<FeedItem>() to false
        runCatching {
            withTimeout(PAGE_TIMEOUT) {
                val filters = source.safeFilterList()
                if (!applyMangaNamedFilter(filters, name)) return@withTimeout emptyList<FeedItem>() to false
                val result = source.getSearchManga(page, "", filters)
                result.mangas.mapNotNull { manga ->
                    runCatching {
                        val local = networkToLocalManga.await(manga.toDomainManga(sourceId))
                        manga.toSourceFeedItem(local.id)
                    }.getOrNull()
                } to result.hasNextPage
            }
        }.getOrDefault(emptyList<FeedItem>() to false)
    }

    private suspend fun searchAnimePage(
        filters: AnimeFilterList,
        query: String,
        page: Int = 1,
    ): Pair<List<FeedItem>, Boolean> = withIOContext {
        val source = animeSourceManager.get(sourceId) as? AnimeCatalogueSource
            ?: return@withIOContext emptyList<FeedItem>() to false
        runCatching {
            withTimeout(PAGE_TIMEOUT) {
                val result = source.getSearchAnime(page, query, filters)
                result.animes.mapNotNull { anime ->
                    runCatching {
                        val local = networkToLocalAnime.await(anime.toDomainAnime(sourceId))
                        anime.toSourceFeedItem(local.id)
                    }.getOrNull()
                } to result.hasNextPage
            }
        }.getOrDefault(emptyList<FeedItem>() to false)
    }

    private suspend fun searchMangaPage(
        filters: FilterList,
        query: String,
        page: Int = 1,
    ): Pair<List<FeedItem>, Boolean> = withIOContext {
        val source = mangaSourceManager.get(sourceId) as? CatalogueSource
            ?: return@withIOContext emptyList<FeedItem>() to false
        runCatching {
            withTimeout(PAGE_TIMEOUT) {
                val result = source.getSearchManga(page, query, filters)
                result.mangas.mapNotNull { manga ->
                    runCatching {
                        val local = networkToLocalManga.await(manga.toDomainManga(sourceId))
                        manga.toSourceFeedItem(local.id)
                    }.getOrNull()
                } to result.hasNextPage
            }
        }.getOrDefault(emptyList<FeedItem>() to false)
    }

    /** Fetches the next page of a Home section without repeating entries already shown. */
    fun loadMoreSection(key: String) {
        screenModelScope.launchIO {
            val pagination = mutableState.value.sectionPages[key] ?: FeedPagination()
            if (pagination.isLoadingMore || !pagination.hasNextPage) return@launchIO
            mutableState.update { state ->
                state.copy(
                    sectionPages = state.sectionPages + (key to pagination.copy(isLoadingMore = true)),
                )
            }
            val result = when {
                key == SECTION_FILTERED -> if (isAnimeScope) {
                    searchAnimePage(currentAnimeFilters ?: AnimeFilterList(), pendingSearchQuery, pagination.page + 1)
                } else {
                    searchMangaPage(currentMangaFilters ?: FilterList(), pendingSearchQuery, pagination.page + 1)
                }
                key.startsWith(CATEGORY_PREFIX) -> {
                    val name = key.removePrefix(CATEGORY_PREFIX)
                    if (isAnimeScope) {
                        searchAnimeWith(name, pagination.page + 1)
                    } else {
                        searchMangaWith(
                            name,
                            pagination.page + 1,
                        )
                    }
                }
                key == SECTION_TRENDING -> {
                    val name = currentTrendingName
                    if (name == null) {
                        emptyList<FeedItem>() to false
                    } else if (isAnimeScope) {
                        searchAnimeWith(name, pagination.page + 1)
                    } else {
                        searchMangaWith(name, pagination.page + 1)
                    }
                }
                key == SECTION_RECENT -> if (isAnimeScope) {
                    latestAnimePage(pagination.page + 1)
                } else {
                    latestMangaPage(pagination.page + 1)
                }
                key == SECTION_POPULAR -> if (isAnimeScope) {
                    popularAnimePage(pagination.page + 1)
                } else {
                    popularMangaPage(pagination.page + 1)
                }
                key == SECTION_ALL -> if (isAnimeScope) {
                    popularAnimePage(pagination.page + 1)
                } else {
                    popularMangaPage(pagination.page + 1)
                }
                else -> emptyList<FeedItem>() to false
            }
            val (items, hasNext) = result
            mutableState.update { state ->
                val updated = state.copy(
                    sectionPages = state.sectionPages + (
                        key to FeedPagination(pagination.page + 1, hasNext, false)
                        ),
                )
                when {
                    key == SECTION_FILTERED -> updated.copy(
                        filteredEntries = appendUnique(state.filteredEntries.orEmpty(), items),
                    )
                    key == SECTION_TRENDING -> updated.copy(
                        trending = appendUnique(state.trending, items),
                    )
                    key == SECTION_RECENT -> updated.copy(
                        recent = appendUnique(state.recent, items),
                    )
                    key.startsWith(CATEGORY_PREFIX) -> {
                        val name = key.removePrefix(CATEGORY_PREFIX)
                        updated.copy(
                            categorySections = state.categorySections.map { category ->
                                if (category.name == name) {
                                    category.copy(items = appendUnique(category.items, items))
                                } else {
                                    category
                                }
                            },
                        )
                    }
                    key == SECTION_POPULAR -> updated.copy(
                        popular = appendUnique(state.popular, items),
                    )
                    key == SECTION_ALL -> updated.copy(
                        allEntries = appendUnique(state.allEntries, items),
                    )
                    else -> updated
                }
            }
        }
    }

    private fun appendUnique(
        existing: List<FeedItem>,
        newItems: List<FeedItem>,
    ): List<FeedItem> = (existing + newItems).distinctBy { it.isAnime to (it.localId ?: it.id) }

    /**
     * Converts AniList suggestions into cards backed by real extension entries. Nothing is shown
     * when the title does not match exactly after normalisation (case and signs ignored).
     */
    private suspend fun matchSuggestions(media: List<HomeFeedMedia>): List<FeedItem> {
        val unique = media.distinctBy { normalized(it.title) to it.isAnime }
        if (unique.isEmpty()) return emptyList()
        val semaphore = Semaphore(4)
        return coroutineScope {
            unique.map { suggestion ->
                async {
                    semaphore.withPermit {
                        runCatching {
                            withTimeout(SEARCH_TIMEOUT) { findSourceEntry(suggestion) }
                        }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun findSourceEntry(media: HomeFeedMedia): FeedItem? = withIOContext {
        if (media.isAnime) {
            val source = animeSourceManager.get(sourceId) as? AnimeCatalogueSource
                ?: return@withIOContext null
            val page = source.getSearchAnime(1, media.title, source.safeFilterList())
            val match = page.animes.firstOrNull { normalized(it.title) == normalized(media.title) }
                ?: return@withIOContext null
            val local = networkToLocalAnime.await(match.toDomainAnime(sourceId))
            FeedItem(
                id = local.id,
                title = match.title,
                coverUrl = match.thumbnail_url,
                isAnime = true,
                rating = media.rating,
                synopsis = media.synopsis,
                format = media.format,
                genres = media.genres,
                available = true,
                localId = local.id,
            )
        } else {
            val source = mangaSourceManager.get(sourceId) as? CatalogueSource
                ?: return@withIOContext null
            val page = source.getSearchManga(1, media.title, source.safeFilterList())
            val match = page.mangas.firstOrNull { normalized(it.title) == normalized(media.title) }
                ?: return@withIOContext null
            val local = networkToLocalManga.await(match.toDomainManga(sourceId))
            FeedItem(
                id = local.id,
                title = match.title,
                coverUrl = match.thumbnail_url,
                isAnime = false,
                rating = media.rating,
                synopsis = media.synopsis,
                format = media.format,
                genres = media.genres,
                available = true,
                localId = local.id,
            )
        }
    }

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val isRefreshing: Boolean = false,
        val trending: List<FeedItem> = emptyList(),
        val recommended: List<FeedItem> = emptyList(),
        val popular: List<FeedItem> = emptyList(),
        /** Entries from the latest listing of the bundled extension (or each host extension). */
        val recent: List<FeedItem> = emptyList(),
        /** All entries exposed by the bundled extension. */
        val allEntries: List<FeedItem> = emptyList(),
        /** Non-null once the Home's search or filter sheet has been applied; null means no filter. */
        val filteredEntries: List<FeedItem>? = null,
        /** Text currently searched in the standalone Home (for the active-result chip). */
        val searchQuery: String = "",
        /** Source filter auto-selected from the search text, when there was a name match. */
        val activeFilterName: String? = null,
        val categorySections: List<FeedCategory> = emptyList(),
        /** Page/loading state of each load-more section, keyed by section id. */
        val sectionPages: Map<String, FeedPagination> = emptyMap(),
        val hasTrending: Boolean = false,
        val continueList: List<ContinueItem> = emptyList(),
        val showFeatured: Boolean = true,
        val showContinue: Boolean = true,
        val showRecommended: Boolean = true,
        val showPopular: Boolean = true,
        val autoScrollHero: Boolean = true,
        val onlyAvailable: Boolean = true,
        val itemsPerSection: Int = 10,
    )

    @Immutable
    data class FeedCategory(
        val name: String,
        val items: List<FeedItem>,
        val hasNextPage: Boolean = false,
    )

    @Immutable
    data class FeedPagination(
        val page: Int = 1,
        val hasNextPage: Boolean = false,
        val isLoadingMore: Boolean = false,
    )

    @Immutable
    data class FeedItem(
        val id: Long,
        val title: String,
        val coverUrl: String?,
        val isAnime: Boolean,
        val rating: String,
        val synopsis: String,
        val format: String,
        val genres: List<String>,
        /** null while the bundled source is still being queried (global feed only). */
        val available: Boolean? = null,
        /** Local database id of the real extension entry; used to open its detail screen. */
        val localId: Long? = null,
    )

    @Immutable
    data class ContinueItem(
        val id: Long,
        val episodeId: Long?,
        val chapterId: Long?,
        val title: String,
        val subtitle: String,
        val coverUrl: String?,
        val isAnime: Boolean,
        val isNovel: Boolean,
    )

    companion object {
        const val SECTION_ALL = "all"
        const val SECTION_TRENDING = "trending"
        const val SECTION_RECENT = "recent"
        const val SECTION_POPULAR = "popular"
        const val SECTION_FILTERED = "filtered"
        const val CATEGORY_PREFIX = "category:"

        fun categoryKey(name: String) = CATEGORY_PREFIX + name

        const val PAGE_TIMEOUT = 15_000L
        const val SEARCH_TIMEOUT = 8_000L
        const val POPULAR_FALLBACK_COUNT = 20
        const val MIN_ITEMS_PER_SECTION = 3
        const val MAX_ITEMS_PER_SECTION = 20
        const val MAX_RECENT_SOURCES = 3
        const val RECENT_PER_SOURCE = 8
    }
}

private fun SAnime.toSourceFeedItem(localId: Long) = StandaloneFeedScreenModel.FeedItem(
    id = localId,
    title = title,
    coverUrl = thumbnail_url,
    isAnime = true,
    rating = "",
    synopsis = description.orEmpty(),
    format = "",
    genres = getGenres().orEmpty(),
    available = true,
    localId = localId,
)

private fun SManga.toSourceFeedItem(localId: Long) = StandaloneFeedScreenModel.FeedItem(
    id = localId,
    title = title,
    coverUrl = thumbnail_url,
    isAnime = false,
    rating = "",
    synopsis = description.orEmpty(),
    format = "",
    genres = getGenres().orEmpty(),
    available = true,
    localId = localId,
)

private fun normalized(title: String): String =
    title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

private fun isTrendingName(name: String): Boolean =
    name.contains("trend", ignoreCase = true) || name.contains("tendenc", ignoreCase = true)

/** Matches the "recent"/"latest"/"new" filters extensions expose for freshly added titles. */
private fun isRecentName(name: String): Boolean = listOf(
    "recent",
    "recient",
    "latest",
    "ultim",
    "últim",
    "nuev",
    "new",
    "estren",
).any { name.contains(it, ignoreCase = true) }

private fun AnimeCatalogueSource.safeFilterList(): AnimeFilterList = try {
    getFilterList()
} catch (e: Throwable) {
    logcat(LogPriority.ERROR, e) { "Failed to build filters for source $name ($id)" }
    AnimeFilterList()
}

private fun CatalogueSource.safeFilterList(): FilterList = try {
    getFilterList()
} catch (e: Throwable) {
    logcat(LogPriority.ERROR, e) { "Failed to build filters for source $name ($id)" }
    FilterList()
}

private fun animeTrendingFilterName(source: AnimeCatalogueSource): String? =
    source.safeFilterList().firstOrNull { filter ->
        isTrendingName(filter.name) && (filter is AnimeFilter.Sort || filter is AnimeFilter.Select<*>)
    }?.name

private fun mangaTrendingFilterName(source: CatalogueSource): String? =
    source.safeFilterList().firstOrNull { filter ->
        isTrendingName(filter.name) && (filter is Filter.Sort || filter is Filter.Select<*>)
    }?.name

private fun applyAnimeNamedFilter(filters: AnimeFilterList, name: String): Boolean =
    applyAnimeCategory(filters, name) || applyAnimeTrending(filters, name)

private fun applyMangaNamedFilter(filters: FilterList, name: String): Boolean =
    applyMangaCategory(filters, name) || applyMangaTrending(filters, name)

private fun animeCategoryNames(source: AnimeCatalogueSource): List<String> = buildList {
    source.safeFilterList().forEach { filter ->
        when (filter) {
            is AnimeFilter.Group<*> -> filter.state.forEach { child ->
                if (child is AnimeFilter<*>) {
                    val name = child.name
                    if (name.isNotBlank() &&
                        child !is AnimeFilter.Header &&
                        child !is AnimeFilter.Separator &&
                        !isTrendingName(name) &&
                        !isRecentName(name)
                    ) {
                        add(name)
                    }
                }
            }
            is AnimeFilter.TriState, is AnimeFilter.CheckBox -> {
                if (filter.name.isNotBlank() &&
                    !isTrendingName(filter.name) &&
                    !isRecentName(filter.name)
                ) {
                    add(filter.name)
                }
            }
            else -> Unit
        }
    }
}.distinct().take(MAX_CATEGORY_FILTERS)

private fun mangaCategoryNames(source: CatalogueSource): List<String> = buildList {
    source.safeFilterList().forEach { filter ->
        when (filter) {
            is Filter.Group<*> -> filter.state.forEach { child ->
                if (child is Filter<*>) {
                    val name = child.name
                    if (name.isNotBlank() &&
                        child !is Filter.Header &&
                        child !is Filter.Separator &&
                        !isTrendingName(name) &&
                        !isRecentName(name)
                    ) {
                        add(name)
                    }
                }
            }
            is Filter.TriState, is Filter.CheckBox -> {
                if (filter.name.isNotBlank() &&
                    !isTrendingName(filter.name) &&
                    !isRecentName(filter.name)
                ) {
                    add(filter.name)
                }
            }
            else -> Unit
        }
    }
}.distinct().take(MAX_CATEGORY_FILTERS)

private fun applyAnimeCategory(filters: AnimeFilterList, name: String): Boolean {
    filters.forEach { filter ->
        when (filter) {
            is AnimeFilter.Group<*> -> filter.state.forEach { child ->
                if (child is AnimeFilter<*> && child.name.equals(name, ignoreCase = true)) {
                    when (child) {
                        is AnimeFilter.TriState -> child.state = AnimeFilter.TriState.STATE_INCLUDE
                        is AnimeFilter.CheckBox -> child.state = true
                        else -> Unit
                    }
                    return true
                }
            }
            is AnimeFilter.TriState -> if (filter.name.equals(name, ignoreCase = true)) {
                filter.state = AnimeFilter.TriState.STATE_INCLUDE
                return true
            }
            is AnimeFilter.CheckBox -> if (filter.name.equals(name, ignoreCase = true)) {
                filter.state = true
                return true
            }
            else -> Unit
        }
    }
    return false
}

private fun applyMangaCategory(filters: FilterList, name: String): Boolean {
    filters.forEach { filter ->
        when (filter) {
            is Filter.Group<*> -> filter.state.forEach { child ->
                if (child is Filter<*> && child.name.equals(name, ignoreCase = true)) {
                    when (child) {
                        is Filter.TriState -> child.state = Filter.TriState.STATE_INCLUDE
                        is Filter.CheckBox -> child.state = true
                        else -> Unit
                    }
                    return true
                }
            }
            is Filter.TriState -> if (filter.name.equals(name, ignoreCase = true)) {
                filter.state = Filter.TriState.STATE_INCLUDE
                return true
            }
            is Filter.CheckBox -> if (filter.name.equals(name, ignoreCase = true)) {
                filter.state = true
                return true
            }
            else -> Unit
        }
    }
    return false
}

/** Undoes the filter automatically selected for a previous search query. */
private fun clearAnimeCategory(filters: AnimeFilterList, name: String) {
    filters.forEach { filter ->
        when (filter) {
            is AnimeFilter.Group<*> -> filter.state.forEach { child ->
                if (child is AnimeFilter<*> && child.name.equals(name, ignoreCase = true)) {
                    when (child) {
                        is AnimeFilter.TriState -> child.state = AnimeFilter.TriState.STATE_IGNORE
                        is AnimeFilter.CheckBox -> child.state = false
                        else -> Unit
                    }
                }
            }
            is AnimeFilter.TriState -> if (filter.name.equals(name, ignoreCase = true)) {
                filter.state = AnimeFilter.TriState.STATE_IGNORE
            }
            is AnimeFilter.CheckBox -> if (filter.name.equals(name, ignoreCase = true)) {
                filter.state = false
            }
            else -> Unit
        }
    }
}

/** Undoes the filter automatically selected for a previous search query. */
private fun clearMangaCategory(filters: FilterList, name: String) {
    filters.forEach { filter ->
        when (filter) {
            is Filter.Group<*> -> filter.state.forEach { child ->
                if (child is Filter<*> && child.name.equals(name, ignoreCase = true)) {
                    when (child) {
                        is Filter.TriState -> child.state = Filter.TriState.STATE_IGNORE
                        is Filter.CheckBox -> child.state = false
                        else -> Unit
                    }
                }
            }
            is Filter.TriState -> if (filter.name.equals(name, ignoreCase = true)) {
                filter.state = Filter.TriState.STATE_IGNORE
            }
            is Filter.CheckBox -> if (filter.name.equals(name, ignoreCase = true)) {
                filter.state = false
            }
            else -> Unit
        }
    }
}

private fun applyAnimeTrending(filters: AnimeFilterList, name: String): Boolean {
    val filter = filters.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return false
    return when (filter) {
        is AnimeFilter.Sort -> {
            val index = filter.values.indexOfFirst {
                isTrendingName(it) || isRecentName(it) || it.equals(name, ignoreCase = true)
            }
            filter.state = AnimeFilter.Sort.Selection(
                index = if (index >= 0) index else 0,
                ascending = false,
            )
            true
        }
        is AnimeFilter.Select<*> -> {
            val index = filter.values.indexOfFirst {
                val value = it.toString()
                isTrendingName(value) || isRecentName(value) || value.equals(name, ignoreCase = true)
            }
            filter.state = if (index >= 0) index else 0
            true
        }
        else -> false
    }
}

private fun applyMangaTrending(filters: FilterList, name: String): Boolean {
    val filter = filters.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return false
    return when (filter) {
        is Filter.Sort -> {
            val index = filter.values.indexOfFirst {
                isTrendingName(it) || isRecentName(it) || it.equals(name, ignoreCase = true)
            }
            filter.state = Filter.Sort.Selection(
                index = if (index >= 0) index else 0,
                ascending = false,
            )
            true
        }
        is Filter.Select<*> -> {
            val index = filter.values.indexOfFirst {
                val value = it.toString()
                isTrendingName(value) || isRecentName(value) || value.equals(name, ignoreCase = true)
            }
            filter.state = if (index >= 0) index else 0
            true
        }
        else -> false
    }
}

private const val MAX_CATEGORY_FILTERS = 6
