package eu.kanade.tachiyomi.ui.home

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.entries.anime.model.toDomainAnime
import eu.kanade.domain.entries.manga.model.toDomainManga
import eu.kanade.domain.ui.StandaloneScope
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.ContentFormat
import eu.kanade.domain.ui.model.HomeFeedSort
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.data.home.HomeFeedDataSource
import eu.kanade.tachiyomi.data.home.HomeFeedFormatMetadata
import eu.kanade.tachiyomi.data.home.HomeFeedMedia
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.reader.loader.NovelSourceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
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

    /** Genre used by the host's "recommended" request; reused when that row loads more pages. */
    private var currentHostGenre: String? = null

    private var refreshJob: Job? = null
    private var refreshGeneration = 0

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
                sortMode = uiPreferences.homeFeedSort().get(),
                continueVisibleCount = uiPreferences.newHomeItemsPerSection().get().coerceIn(
                    MIN_ITEMS_PER_SECTION,
                    MAX_ITEMS_PER_SECTION,
                ),
            )
        }
        refresh()
    }

    fun refresh() {
        val generation = ++refreshGeneration
        refreshJob?.cancel()
        refreshJob = screenModelScope.launchIO {
            try {
                // Never gate the screen on network work: history is local and can be shown at once.
                mutableState.update {
                    it.copy(isRefreshing = true, isLoading = false, filteredEntries = null)
                }
                val continueList = loadContinue()
                mutableState.update { state ->
                    state.copy(
                        isLoading = false,
                        continueList = continueList.sortedByDescending { it.seenAt },
                        continueVisibleCount = state.itemsPerSection,
                    )
                }
                yield()

                if (isStandalone) {
                    refreshFromSource(continueList)
                } else {
                    refreshHost(continueList)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.ERROR, e) { "Failed to refresh Home" }
            } finally {
                if (generation == refreshGeneration) {
                    mutableState.update { it.copy(isRefreshing = false, isLoading = false) }
                }
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
        mutableState.update {
            it.copy(itemsPerSection = coerced, continueVisibleCount = coerced)
        }
    }

    private suspend fun safeFeed(
        anime: Boolean,
        sort: String,
        genre: String? = null,
        page: Int = 1,
    ): List<HomeFeedMedia> = withIOContext {
        runCatching {
            withTimeout(ANILIST_TIMEOUT) { dataSource.getMedia(anime, sort, genre, page) }
        }.onFailure { error ->
            logcat(LogPriority.WARN, error) { "AniList feed request failed ($anime, $sort)" }
        }.getOrDefault(emptyList())
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
        contentFormats = HomeFeedFormatMetadata.detect(null, media.genres, media.format),
        episodeCount = media.episodeCount,
        airingAt = media.airingAt,
    )

    /**
     * Host: "Recent" uses each enabled extension's latest listing, the same source as the normal
     * catalogue's Latest page. History and library updates are deliberately not used here.
     */
    private suspend fun loadGlobalRecent(sources: List<HostSource>): List<FeedItem> = withIOContext {
        coroutineScope {
            val animeRecent = async {
                sources.filter { it.kind == KIND_ANIME }.take(MAX_RECENT_SOURCES)
                    .map { ref ->
                        async {
                            val source = animeSourceFor(ref.id) ?: return@async emptyList()
                            val page = runCatching {
                                withTimeout(SEARCH_TIMEOUT) { source.getLatestUpdates(1) }
                            }.getOrNull() ?: return@async emptyList()
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
                                    contentFormats = HomeFeedFormatMetadata.detect(
                                        source,
                                        anime.getGenres().orEmpty(),
                                        "",
                                    ),
                                )
                            }
                        }
                    }.awaitAll().flatten()
            }
            val mangaRecent = async {
                sources.filter { it.kind != KIND_ANIME }.take(MAX_RECENT_SOURCES)
                    .map { ref ->
                        async {
                            val source = mangaSourceFor(ref.id) ?: return@async emptyList()
                            val page = runCatching {
                                withTimeout(SEARCH_TIMEOUT) { source.getLatestUpdates(1) }
                            }.getOrNull() ?: return@async emptyList()
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
                                    contentFormats = HomeFeedFormatMetadata.detect(
                                        source,
                                        manga.getGenres().orEmpty(),
                                        "",
                                    ),
                                )
                            }
                        }
                    }.awaitAll().flatten()
            }
            (animeRecent.await() + mangaRecent.await())
                .distinctBy { normalized(it.title) to it.isAnime }
        }
    }

    private suspend fun loadContinue(): List<ContinueItem> = withIOContext {
        // In the host the row is one mixed, recency-ordered timeline; keep enough entries there for
        // "load more". A standalone still shows only its single source and keeps the old small cap.
        val limit = if (isStandalone) 8 else MAX_HOST_CONTINUE_ENTRIES
        buildList {
            if (isAnimeScope) {
                getAnimeHistory.subscribe("")
                    .first()
                    .filter { !isStandalone || it.coverData.sourceId == sourceId }
                    .take(limit)
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
                                seenAt = history.seenAt?.time ?: 0L,
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
                    .take(limit)
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
                                seenAt = history.readAt?.time ?: 0L,
                            ),
                        )
                    }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Host (ExtYomi): AniList only suggests; the enabled extensions decide what exists.
    // ---------------------------------------------------------------------------

    private data class HostSource(val id: Long, val name: String, val kind: String)

    private suspend fun refreshHost(continueList: List<ContinueItem>) {
        val sources = hostSources()

        // Reading filters is cheap for healthy extensions but can hang forever on a broken one.
        // Each source gets its own short budget and a failure only removes its categories.
        val support = mutableMapOf<String, MutableList<HostSource>>()
        sources.forEach { ref ->
            val names = runCatching {
                withTimeout(FILTER_TIMEOUT) { withIOContext { categoryNamesFor(ref) } }
            }.onFailure { error ->
                logcat(LogPriority.WARN, error) { "Failed to read filters for source ${ref.name}" }
            }.getOrDefault(emptyList())
            names.forEach { name ->
                if (!isTrendingName(name) && !isRecentName(name)) {
                    support.getOrPut(name) { mutableListOf() }.add(ref)
                }
            }
        }

        val blocked = uiPreferences.homeFeedBlockedCategories().get()
        val savedOrder = uiPreferences.homeFeedCategoryOrder().get()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val available = (DEFAULT_HOST_CATEGORIES + support.keys)
            .distinctBy(::normalized)
            .filterNot { it in blocked }
        val orderedCategories = buildList {
            savedOrder.forEach { saved ->
                available.firstOrNull { normalized(it) == normalized(saved) }?.let(::add)
            }
            available.forEach { name ->
                if (none { normalized(it) == normalized(name) }) add(name)
            }
        }.distinctBy(::normalized)

        // First visible state: history is already in place, categories are known and no network
        // call has happened yet. The UI can compose while the rows are generated below.
        mutableState.update { state ->
            state.copy(
                availableCategories = orderedCategories,
                feedRows = emptyList(),
                sectionPages = mapOf(
                    SECTION_TRENDING to FeedPagination(1, false),
                    SECTION_RECOMMENDED to FeedPagination(1, false),
                    SECTION_POPULAR to FeedPagination(1, false),
                ),
            )
        }
        yield()

        val tasks = buildHostRowTasks(sources, support, orderedCategories)
        val firstRows = tasks.take(FIRST_HOST_ROWS).mapNotNull { task ->
            runCatching { task() }.onFailure { error ->
                logcat(LogPriority.WARN, error) { "Skipped a Home row after a source error" }
            }.getOrNull()
        }
        appendHostRows(firstRows)
        yield()

        // Suggestions and "recent" are independent of the generated rows: load them alongside the
        // remaining rows instead of making them wait for each other.
        coroutineScope {
            val suggestions = async { loadHostSuggestions(sources) }
            val recent = async {
                runCatching { loadGlobalRecent(sources) }
                    .getOrDefault(emptyList())
                    .also { items -> mutableState.update { it.copy(recent = items) } }
            }
            tasks.drop(FIRST_HOST_ROWS).forEach { task ->
                val row = runCatching { task() }.onFailure { error ->
                    logcat(LogPriority.WARN, error) { "Skipped a Home row after a source error" }
                }.getOrNull()
                if (row != null) appendHostRows(listOf(row))
                // Let Compose show each row before the next source is queried.
                yield()
            }
            suggestions.join()
            recent.join()
        }
    }

    private suspend fun loadHostSuggestions(sources: List<HostSource>) {
        val trendingMedia = safeFeed(true, "TRENDING_DESC") + safeFeed(false, "TRENDING_DESC")
        val genre = trendingMedia.firstOrNull()?.genres?.firstOrNull()
        currentHostGenre = genre
        val recommendedMedia = safeFeed(true, "SCORE_DESC", genre) + safeFeed(false, "SCORE_DESC", genre)
        val popularMedia = safeFeed(true, "POPULARITY_DESC") + safeFeed(false, "POPULARITY_DESC")

        val (trending, recommended, popular) = coroutineScope {
            val t = async { validateSuggestions(trendingMedia, sources) }
            val r = async { validateSuggestions(recommendedMedia, sources) }
            val p = async { validateSuggestions(popularMedia, sources) }
            Triple(t.await(), r.await(), p.await())
        }
        mutableState.update { state ->
            state.copy(
                trending = trending,
                recommended = recommended,
                popular = popular,
                sectionPages = state.sectionPages + mapOf(
                    SECTION_TRENDING to FeedPagination(1, trending.isNotEmpty()),
                    SECTION_RECOMMENDED to FeedPagination(1, recommended.isNotEmpty()),
                    SECTION_POPULAR to FeedPagination(1, popular.isNotEmpty()),
                ),
            )
        }
    }

    private fun appendHostRows(rows: List<FeedRow>) {
        if (rows.isEmpty()) return
        mutableState.update { state ->
            val fresh = rows.filterNot { row -> state.feedRows.any { it.key == row.key } }
            if (fresh.isEmpty()) {
                state
            } else {
                state.copy(
                    feedRows = state.feedRows + fresh,
                    sectionPages = state.sectionPages +
                        fresh.associate { it.key to FeedPagination(1, it.hasNextPage) },
                )
            }
        }
    }

    /**
     * Called by the vertical feed footer. It advances the first section that still has a page,
     * so the user only has to keep scrolling instead of pressing "load more" on each row.
     */
    fun loadNextPage() {
        if (!isStandalone) {
            val state = mutableState.value
            val continuePending = state.continueList.size > state.continueVisibleCount
            if (continuePending) {
                loadMoreContinue()
                return
            }
            val keys = listOf(
                SECTION_TRENDING,
                SECTION_RECOMMENDED,
                SECTION_POPULAR,
            ) + state.feedRows.map { it.key }
            val key = keys.firstOrNull { sectionKey ->
                val page = state.sectionPages[sectionKey] ?: return@firstOrNull false
                page.hasNextPage && !page.isLoadingMore
            } ?: return
            loadMoreGlobalSection(key)
            return
        }
        loadMoreContinue()
    }

    /** Installed, enabled sources in the user's own priority order, independent of migration/player. */
    private fun hostSources(): List<HostSource> {
        if (isStandalone) return emptyList()
        val anime = animeSourceManager.getCatalogueSources().map { HostSource(it.id, it.name, KIND_ANIME) }
        val mangaSources = mangaSourceManager.getCatalogueSources()
        val manga = mangaSources.filterNot { NovelSourceCompat.isNovelSource(it) }
            .map { HostSource(it.id, it.name, KIND_MANGA) }
        val novel = mangaSources.filter { NovelSourceCompat.isNovelSource(it) }
            .map { HostSource(it.id, it.name, KIND_NOVEL) }

        return HOST_KINDS.flatMap { kind ->
            val refs = when (kind) {
                KIND_ANIME -> anime
                KIND_MANGA -> manga
                else -> novel
            }
            val order = uiPreferences.homeFeedSourceOrder(kind).get()
                .split(',')
                .mapNotNull { it.trim().toLongOrNull() }
            val disabled = uiPreferences.homeFeedDisabledSources(kind).get()
            refs.filterNot { it.id.toString() in disabled }
                .sortedBy { ref ->
                    val index = order.indexOf(ref.id)
                    if (index < 0) order.size + refs.indexOf(ref) else index
                }
        }
    }

    private fun animeSourceFor(id: Long) = animeSourceManager.get(id) as? AnimeCatalogueSource

    private fun mangaSourceFor(id: Long) = mangaSourceManager.get(id) as? CatalogueSource

    private fun categoryNamesFor(ref: HostSource): List<String> = if (ref.kind == KIND_ANIME) {
        animeSourceFor(ref.id)?.let(::animeCategoryNames).orEmpty()
    } else {
        mangaSourceFor(ref.id)?.let(::mangaCategoryNames).orEmpty()
    }

    /**
     * Every AniList card is checked by title against the enabled sources of its own kind. An empty
     * source list means "no filter configured yet", so suggestions are shown as before instead of
     * leaving an empty Home.
     */
    private suspend fun validateSuggestions(
        media: List<HomeFeedMedia>,
        sources: List<HostSource>,
    ): List<FeedItem> {
        val unique = media.distinctBy { normalized(it.title) to it.isAnime }
            .take(MAX_SUGGESTIONS_TO_VALIDATE)
        if (unique.isEmpty()) return emptyList()
        if (sources.isEmpty()) return unique.map(::toGlobalFeedItem)

        val semaphore = Semaphore(4)
        return coroutineScope {
            unique.map { suggestion ->
                async {
                    semaphore.withPermit {
                        runCatching {
                            withTimeout(VALIDATION_TIMEOUT) {
                                var matches = false
                                for (ref in sources) {
                                    if ((ref.kind == KIND_ANIME) != suggestion.isAnime) continue
                                    // A broken source only removes itself from this check.
                                    if (matchesTitle(ref, suggestion.title, suggestion.isAnime)) {
                                        matches = true
                                        break
                                    }
                                }
                                toGlobalFeedItem(suggestion).copy(
                                    available = matches,
                                    contentFormats = HomeFeedFormatMetadata.detect(
                                        source = null,
                                        genres = suggestion.genres,
                                        apiFormat = suggestion.format,
                                    ),
                                    episodeCount = suggestion.episodeCount,
                                    airingAt = suggestion.airingAt,
                                )
                            }
                        }.onFailure { error ->
                            logcat(LogPriority.WARN, error) {
                                "Skipped suggestion ${suggestion.title} after a source error"
                            }
                        }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull().filter { it.available != false }
        }
    }

    private suspend fun matchesTitle(
        ref: HostSource,
        title: String,
        anime: Boolean,
    ): Boolean = withIOContext {
        val target = normalized(title)
        runCatching {
            withTimeout(SOURCE_SEARCH_TIMEOUT) {
                if (anime) {
                    val source = animeSourceFor(ref.id) ?: return@withTimeout false
                    source.getSearchAnime(1, title, source.safeFilterList()).animes
                        .any { normalized(it.title) == target }
                } else {
                    val source = mangaSourceFor(ref.id) ?: return@withTimeout false
                    source.getSearchManga(1, title, source.safeFilterList()).mangas
                        .any { normalized(it.title) == target }
                }
            }
        }.onFailure { error ->
            logcat(LogPriority.WARN, error) { "Source ${ref.name} failed while checking $title" }
        }.getOrDefault(false)
    }

    /**
     * Builds the rows after Recomendados/Populares: alternating anime, manga and novel categories,
     * each served by the first available source in its own priority order, then the per-source
     * "de todo lo disponible" rows, then movie/series rows (which have no "de todo" block).
     */
    /**
     * Builds the row plan without touching the network. Each task fetches exactly one row and is
     * executed later, so the first rows can be shown while the rest are still being generated.
     */
    private fun buildHostRowTasks(
        sources: List<HostSource>,
        support: Map<String, List<HostSource>>,
        orderedCategories: List<String>,
    ): List<suspend () -> FeedRow?> {
        if (sources.isEmpty()) return emptyList()

        val used = mutableSetOf<String>()
        val cursors = HOST_KINDS.associateWith { 0 }.toMutableMap()
        val tasks = mutableListOf<suspend () -> FeedRow?>()

        for (index in 0 until MAX_HOST_CATEGORY_ROWS.coerceAtMost(orderedCategories.size)) {
            val category = orderedCategories[index]
            val kind = HOST_KINDS[index % HOST_KINDS.size]
            val refs = sources.filter { it.kind == kind }
            if (refs.isEmpty()) continue
            val candidates = support[category]?.filter { it.kind == kind }.orEmpty().ifEmpty { refs }
            val startCursor = cursors[kind] ?: 0
            tasks += {
                var cursor = startCursor
                var chosen: Pair<HostSource, Pair<List<FeedItem>, Boolean>>? = null
                for (offset in candidates.indices) {
                    val ref = candidates[(cursor + offset) % candidates.size]
                    val page = fetchCategoryPage(ref, category, 1)
                    if (page.first.any { it.rowKey !in used }) {
                        chosen = ref to page
                        cursor = (candidates.indexOf(ref) + 1) % candidates.size
                        break
                    }
                }
                if (chosen == null) {
                    // No source served the category: fall back to the first one, as requested.
                    val ref = candidates[cursor % candidates.size]
                    chosen = ref to fetchCategoryPage(ref, category, 1)
                    cursor = (cursor + 1) % candidates.size
                }
                cursors[kind] = cursor
                chosen?.let { (picked, result) ->
                    val items = result.first.filterNot { it.rowKey in used }
                        .take(mutableState.value.itemsPerSection)
                    if (items.isEmpty()) {
                        null
                    } else {
                        items.forEach { used += it.rowKey }
                        FeedRow(
                            key = "genre:$kind:${normalized(category)}",
                            kind = kind,
                            category = category,
                            sourceId = picked.id,
                            items = items,
                            hasNextPage = result.second,
                        )
                    }
                }
            }
        }

        // "de todo lo disponible": one row per enabled extension, in priority order.
        sources.forEach { ref ->
            tasks += {
                val result = fetchPopularPage(ref, 1)
                val items = result.first.filterNot { it.rowKey in used }
                    .take(mutableState.value.itemsPerSection)
                if (items.isEmpty()) {
                    null
                } else {
                    items.forEach { used += it.rowKey }
                    FeedRow(
                        key = "source:${ref.kind}:${ref.id}",
                        kind = ref.kind,
                        sourceId = ref.id,
                        sourceName = ref.name,
                        items = items,
                        hasNextPage = result.second,
                    )
                }
            }
        }

        // Películas y series: format rows only, deliberately without a "de todo" block.
        for (kind in HOST_KINDS) {
            for (format in listOf(ContentFormat.MOVIE, ContentFormat.SERIES)) {
                val refs = sources.filter { it.kind == kind }
                if (refs.isEmpty()) continue
                val categoryName = support.keys.firstOrNull { isFormatName(it, format) }
                val candidates = if (categoryName == null) {
                    refs
                } else {
                    support[categoryName].orEmpty().filter { it.kind == kind }.ifEmpty { refs }
                }
                tasks += {
                    var picked: HostSource? = null
                    var found = emptyList<FeedItem>()
                    var hasNext = false
                    for (ref in candidates) {
                        val page = if (categoryName == null) {
                            fetchPopularPage(ref, 1)
                        } else {
                            fetchCategoryPage(ref, categoryName, 1)
                        }
                        val matching = page.first.filter { format in it.contentFormats }
                        if (matching.any { it.rowKey !in used }) {
                            picked = ref
                            found = matching
                            hasNext = page.second
                            break
                        }
                    }
                    picked?.let { source ->
                        val items = found.filterNot { it.rowKey in used }
                            .take(mutableState.value.itemsPerSection)
                        if (items.isEmpty()) {
                            null
                        } else {
                            items.forEach { used += it.rowKey }
                            FeedRow(
                                key = "format:$kind:${format.name}",
                                kind = kind,
                                format = format,
                                sourceId = source.id,
                                items = items,
                                hasNextPage = hasNext,
                            )
                        }
                    }
                }
            }
        }
        return tasks
    }

    private suspend fun fetchCategoryPage(
        ref: HostSource,
        category: String,
        page: Int,
    ): Pair<List<FeedItem>, Boolean> = withIOContext {
        runCatching {
            withTimeout(PAGE_TIMEOUT) {
                if (ref.kind == KIND_ANIME) {
                    val source = animeSourceFor(ref.id)
                        ?: return@withTimeout emptyList<FeedItem>() to false
                    val filters = source.safeFilterList()
                    if (!applyAnimeNamedFilter(filters, category)) {
                        return@withTimeout emptyList<FeedItem>() to false
                    }
                    val result = source.getSearchAnime(page, "", filters)
                    result.animes.mapNotNull { anime ->
                        runCatching {
                            val local = networkToLocalAnime.await(anime.toDomainAnime(ref.id))
                            anime.toSourceFeedItem(local.id).copy(
                                contentFormats = HomeFeedFormatMetadata.detect(
                                    source,
                                    anime.getGenres().orEmpty(),
                                    "",
                                ),
                            )
                        }.getOrNull()
                    } to result.hasNextPage
                } else {
                    val source = mangaSourceFor(ref.id)
                        ?: return@withTimeout emptyList<FeedItem>() to false
                    val filters = source.safeFilterList()
                    if (!applyMangaNamedFilter(filters, category)) {
                        return@withTimeout emptyList<FeedItem>() to false
                    }
                    val result = source.getSearchManga(page, "", filters)
                    result.mangas.mapNotNull { manga ->
                        runCatching {
                            val local = networkToLocalManga.await(manga.toDomainManga(ref.id))
                            manga.toSourceFeedItem(local.id).copy(
                                contentFormats = HomeFeedFormatMetadata.detect(
                                    source,
                                    manga.getGenres().orEmpty(),
                                    "",
                                ),
                            )
                        }.getOrNull()
                    } to result.hasNextPage
                }
            }
        }.getOrDefault(emptyList<FeedItem>() to false)
    }

    private suspend fun fetchPopularPage(
        ref: HostSource,
        page: Int,
    ): Pair<List<FeedItem>, Boolean> = withIOContext {
        runCatching {
            withTimeout(PAGE_TIMEOUT) {
                if (ref.kind == KIND_ANIME) {
                    val source = animeSourceFor(ref.id)
                        ?: return@withTimeout emptyList<FeedItem>() to false
                    val result = source.getPopularAnime(page)
                    result.animes.mapNotNull { anime ->
                        runCatching {
                            val local = networkToLocalAnime.await(anime.toDomainAnime(ref.id))
                            anime.toSourceFeedItem(local.id).copy(
                                contentFormats = HomeFeedFormatMetadata.detect(
                                    source,
                                    anime.getGenres().orEmpty(),
                                    "",
                                ),
                            )
                        }.getOrNull()
                    } to result.hasNextPage
                } else {
                    val source = mangaSourceFor(ref.id)
                        ?: return@withTimeout emptyList<FeedItem>() to false
                    val result = source.getPopularManga(page)
                    result.mangas.mapNotNull { manga ->
                        runCatching {
                            val local = networkToLocalManga.await(manga.toDomainManga(ref.id))
                            manga.toSourceFeedItem(local.id).copy(
                                contentFormats = HomeFeedFormatMetadata.detect(
                                    source,
                                    manga.getGenres().orEmpty(),
                                    "",
                                ),
                            )
                        }.getOrNull()
                    } to result.hasNextPage
                }
            }
        }.getOrDefault(emptyList<FeedItem>() to false)
    }

    /** Paginates the global rows without refetching the whole feed. */
    private fun loadMoreGlobalSection(key: String) {
        screenModelScope.launchIO {
            val pagination = mutableState.value.sectionPages[key] ?: FeedPagination(1, false)
            if (pagination.isLoadingMore || !pagination.hasNextPage) return@launchIO
            mutableState.update { state ->
                state.copy(
                    sectionPages = state.sectionPages + (key to pagination.copy(isLoadingMore = true)),
                )
            }
            try {
                val nextPage = pagination.page + 1
                when {
                    key == SECTION_TRENDING || key == SECTION_RECOMMENDED || key == SECTION_POPULAR -> {
                        val sort = when (key) {
                            SECTION_TRENDING -> "TRENDING_DESC"
                            SECTION_RECOMMENDED -> "SCORE_DESC"
                            else -> "POPULARITY_DESC"
                        }
                        val media = safeFeed(true, sort, currentHostGenre, nextPage) +
                            safeFeed(false, sort, currentHostGenre, nextPage)
                        val items = validateSuggestions(media, hostSources())
                        mutableState.update { state ->
                            val updated = state.copy(
                                sectionPages = state.sectionPages + (
                                    key to FeedPagination(nextPage, items.isNotEmpty(), false)
                                    ),
                            )
                            when (key) {
                                SECTION_TRENDING -> updated.copy(
                                    trending = appendUnique(updated.trending, items),
                                )
                                SECTION_RECOMMENDED -> updated.copy(
                                    recommended = appendUnique(updated.recommended, items),
                                )
                                else -> updated.copy(popular = appendUnique(updated.popular, items))
                            }
                        }
                    }
                    else -> {
                        val row = mutableState.value.feedRows.firstOrNull { it.key == key }
                            ?: return@launchIO
                        val ref = hostSources().firstOrNull {
                            it.id == row.sourceId && it.kind == row.kind
                        } ?: return@launchIO
                        val result = when {
                            key.startsWith("genre:") && row.category != null ->
                                fetchCategoryPage(ref, row.category, nextPage)
                            else -> fetchPopularPage(ref, nextPage)
                        }
                        mutableState.update { state ->
                            val fresh = result.first.filterNot { item ->
                                state.feedRows.any { other ->
                                    other.key != key &&
                                        other.items.any { it.rowKey == item.rowKey }
                                }
                            }
                            state.copy(
                                sectionPages = state.sectionPages + (
                                    key to FeedPagination(nextPage, result.second, false)
                                    ),
                                feedRows = state.feedRows.map { current ->
                                    if (current.key == key) {
                                        val items = appendUnique(current.items, fresh)
                                        current.copy(
                                            items = if (key.startsWith("format:")) {
                                                items.filter { row.format in it.contentFormats }
                                            } else {
                                                items
                                            },
                                            hasNextPage = result.second,
                                        )
                                    } else {
                                        current
                                    }
                                },
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logcat(LogPriority.WARN, e) { "Home pagination failed for $key" }
            } finally {
                mutableState.update { state ->
                    val current = state.sectionPages[key] ?: pagination
                    state.copy(sectionPages = state.sectionPages + (key to current.copy(isLoadingMore = false)))
                }
            }
        }
    }

    fun loadMoreContinue() = mutableState.update { state ->
        state.copy(continueVisibleCount = state.continueVisibleCount + state.itemsPerSection)
    }

    fun setSortMode(mode: HomeFeedSort) {
        uiPreferences.homeFeedSort().set(mode)
        mutableState.update { it.copy(sortMode = mode) }
    }

    fun setBlockedCategories(names: Set<String>) {
        uiPreferences.homeFeedBlockedCategories().set(names)
        refresh()
    }

    fun setCategoryOrder(names: List<String>) {
        uiPreferences.homeFeedCategoryOrder().set(names.joinToString(","))
        refresh()
    }

    fun onSourcePreferencesChanged() = refresh()

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
        if (!isStandalone) {
            loadMoreGlobalSection(key)
            return
        }
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
        /** Extra rows generated for the host from categories, sources and content formats. */
        val feedRows: List<FeedRow> = emptyList(),
        /** Categories offered by the enabled extensions, after order and block rules. */
        val availableCategories: List<String> = emptyList(),
        val continueVisibleCount: Int = 10,
        val sortMode: HomeFeedSort = HomeFeedSort.NONE,
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

    /** One generated horizontal row of the host feed. The UI builds its title from these fields. */
    @Immutable
    data class FeedRow(
        val key: String,
        val kind: String,
        val category: String? = null,
        val format: ContentFormat? = null,
        val sourceId: Long? = null,
        val sourceName: String? = null,
        val items: List<FeedItem> = emptyList(),
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
        /** Optional format metadata used by the Películas/Series rows and the Ordenar tab. */
        val contentFormats: Set<ContentFormat> = emptySet(),
        val episodeCount: Int? = null,
        val airingAt: Long? = null,
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
        /** Timestamp used to merge anime, manga and novel into one recency-ordered row. */
        val seenAt: Long = 0L,
    )

    companion object {
        const val SECTION_ALL = "all"
        const val SECTION_TRENDING = "trending"
        const val SECTION_RECENT = "recent"
        const val SECTION_POPULAR = "popular"
        const val SECTION_FILTERED = "filtered"
        const val SECTION_RECOMMENDED = "recommended"
        const val CATEGORY_PREFIX = "category:"

        const val KIND_ANIME = "anime"
        const val KIND_MANGA = "manga"
        const val KIND_NOVEL = "novel"
        val HOST_KINDS = listOf(KIND_ANIME, KIND_MANGA, KIND_NOVEL)

        /** Defaults from the filter dialog; extension categories are appended after them. */
        val DEFAULT_HOST_CATEGORIES = listOf(
            "Acción",
            "Aventura",
            "Comedia",
            "Terror",
            "Ciencia ficción",
            "Romance",
            "Drama",
            "Fantasía",
            "Suspenso",
        )

        // A bounded number of generated rows keeps a first feed refresh from hammering every source.
        const val MAX_HOST_CATEGORY_ROWS = 12
        const val MAX_HOST_CONTINUE_ENTRIES = 60

        fun categoryKey(name: String) = CATEGORY_PREFIX + name

        const val PAGE_TIMEOUT = 12_000L
        const val SEARCH_TIMEOUT = 8_000L
        const val ANILIST_TIMEOUT = 10_000L
        const val FILTER_TIMEOUT = 3_000L
        const val FIRST_HOST_ROWS = 3
        const val MAX_SUGGESTIONS_TO_VALIDATE = 12
        const val VALIDATION_TIMEOUT = 6_000L
        const val SOURCE_SEARCH_TIMEOUT = 5_000L
        const val POPULAR_FALLBACK_COUNT = 20
        const val MIN_ITEMS_PER_SECTION = 3
        const val MAX_ITEMS_PER_SECTION = 20
        const val MAX_RECENT_SOURCES = 3
        const val RECENT_PER_SOURCE = 8
    }
}

/**
 * Anime and manga/novel database ids come from separate tables and can collide (both can be 1).
 * History can also contain several episodes/chapters of the same entry, so the row key needs the
 * kind and the concrete episode/chapter as well as the entry id.
 */
internal fun StandaloneFeedScreenModel.ContinueItem.feedKey(): String {
    val kind = when {
        isAnime -> "anime"
        isNovel -> "novel"
        else -> "manga"
    }
    return "continue-$kind-$id-${episodeId ?: chapterId ?: 0L}"
}

private val StandaloneFeedScreenModel.FeedItem.rowKey: String
    get() = "$isAnime-${localId ?: id}"

private fun isFormatName(name: String, format: ContentFormat): Boolean = when (format) {
    ContentFormat.MOVIE -> name.contains("pelicula", ignoreCase = true) ||
        name.contains("movie", ignoreCase = true)
    ContentFormat.SERIES -> name.contains("serie", ignoreCase = true) ||
        name.equals("TV", ignoreCase = true)
    else -> false
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
