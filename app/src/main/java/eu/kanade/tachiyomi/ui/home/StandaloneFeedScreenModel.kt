package eu.kanade.tachiyomi.ui.home

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.entries.anime.model.toDomainAnime
import eu.kanade.domain.entries.manga.model.toDomainManga
import eu.kanade.domain.ui.StandaloneScope
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

    init {
        refresh()
    }

    fun refresh() {
        screenModelScope.launchIO {
            mutableState.update { it.copy(isRefreshing = true) }

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

            mutableState.update {
                it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    trending = trending,
                    recommended = recommended,
                    popular = popular,
                    continueList = continueList,
                )
            }
        }
    }

    fun toggleFeatured() = mutableState.update { it.copy(showFeatured = !it.showFeatured) }

    fun toggleContinue() = mutableState.update { it.copy(showContinue = !it.showContinue) }

    fun toggleRecommended() = mutableState.update { it.copy(showRecommended = !it.showRecommended) }

    fun togglePopular() = mutableState.update { it.copy(showPopular = !it.showPopular) }

    fun toggleAutoScrollHero() = mutableState.update { it.copy(autoScrollHero = !it.autoScrollHero) }

    fun toggleOnlyAvailable() = mutableState.update { it.copy(onlyAvailable = !it.onlyAvailable) }

    fun setItemsPerSection(count: Int) {
        mutableState.update { it.copy(itemsPerSection = count.coerceIn(3, 20)) }
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

        // Every entry the source exposes through its popular listing, across all pages.
        val allEntries = if (animeScope) loadAllAnimeEntries() else loadAllMangaEntries()

        val (trendingName, categoryNames) = if (animeScope) {
            val source = animeSourceManager.get(sourceId) as? AnimeCatalogueSource
            Pair(source?.let(::animeTrendingFilterName), source?.let(::animeCategoryNames).orEmpty())
        } else {
            val source = mangaSourceManager.get(sourceId) as? CatalogueSource
            Pair(source?.let(::mangaTrendingFilterName), source?.let(::mangaCategoryNames).orEmpty())
        }

        val sourceTrending = when {
            trendingName == null -> emptyList()
            animeScope -> searchAnimeWith(trendingName, isCategory = false)
            else -> searchMangaWith(trendingName, isCategory = false)
        }
        val categories = categoryNames.map { name ->
            val items = if (animeScope) {
                searchAnimeWith(name, isCategory = true)
            } else {
                searchMangaWith(name, isCategory = true)
            }
            FeedCategory(name, items)
        }.filter { it.items.isNotEmpty() }

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

        val finalTrending = if (sourceTrending.isNotEmpty()) sourceTrending else matchedTrending
        mutableState.update {
            it.copy(
                isLoading = false,
                isRefreshing = false,
                allEntries = allEntries,
                popular = matchedPopular.ifEmpty { allEntries.take(POPULAR_FALLBACK_COUNT) },
                recommended = matchedRecommended,
                trending = finalTrending,
                // Trending is a real section only when the extension exposes a trending filter.
                hasTrending = trendingName != null && finalTrending.isNotEmpty(),
                categorySections = categories,
                continueList = continueList,
            )
        }
    }

    private suspend fun loadAllAnimeEntries(): List<FeedItem> = withIOContext {
        val source = animeSourceManager.get(sourceId) as? AnimeCatalogueSource ?: return@withIOContext emptyList()
        buildList {
            var page = 1
            while (page <= MAX_CATALOGUE_PAGES) {
                val result = runCatching {
                    withTimeout(PAGE_TIMEOUT) { source.getPopularAnime(page) }
                }.getOrNull() ?: break
                result.animes.forEach { anime ->
                    runCatching {
                        val local = networkToLocalAnime.await(anime.toDomainAnime(sourceId))
                        add(anime.toSourceFeedItem(local.id))
                    }
                }
                if (!result.hasNextPage || result.animes.isEmpty()) break
                page++
            }
        }
    }

    private suspend fun loadAllMangaEntries(): List<FeedItem> = withIOContext {
        val source = mangaSourceManager.get(sourceId) as? CatalogueSource ?: return@withIOContext emptyList()
        buildList {
            var page = 1
            while (page <= MAX_CATALOGUE_PAGES) {
                val result = runCatching {
                    withTimeout(PAGE_TIMEOUT) { source.getPopularManga(page) }
                }.getOrNull() ?: break
                result.mangas.forEach { manga ->
                    runCatching {
                        val local = networkToLocalManga.await(manga.toDomainManga(sourceId))
                        add(manga.toSourceFeedItem(local.id))
                    }
                }
                if (!result.hasNextPage || result.mangas.isEmpty()) break
                page++
            }
        }
    }

    private suspend fun searchAnimeWith(name: String, isCategory: Boolean): List<FeedItem> = withIOContext {
        val source = animeSourceManager.get(sourceId) as? AnimeCatalogueSource ?: return@withIOContext emptyList()
        runCatching {
            withTimeout(PAGE_TIMEOUT) {
                val filters = source.safeFilterList()
                val applied = if (isCategory) {
                    applyAnimeCategory(filters, name)
                } else {
                    applyAnimeTrending(filters, name)
                }
                if (!applied) return@withTimeout emptyList()
                source.getSearchAnime(1, "", filters).animes.mapNotNull { anime ->
                    runCatching {
                        val local = networkToLocalAnime.await(anime.toDomainAnime(sourceId))
                        anime.toSourceFeedItem(local.id)
                    }.getOrNull()
                }
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun searchMangaWith(name: String, isCategory: Boolean): List<FeedItem> = withIOContext {
        val source = mangaSourceManager.get(sourceId) as? CatalogueSource ?: return@withIOContext emptyList()
        runCatching {
            withTimeout(PAGE_TIMEOUT) {
                val filters = source.safeFilterList()
                val applied = if (isCategory) {
                    applyMangaCategory(filters, name)
                } else {
                    applyMangaTrending(filters, name)
                }
                if (!applied) return@withTimeout emptyList()
                source.getSearchManga(1, "", filters).mangas.mapNotNull { manga ->
                    runCatching {
                        val local = networkToLocalManga.await(manga.toDomainManga(sourceId))
                        manga.toSourceFeedItem(local.id)
                    }.getOrNull()
                }
            }
        }.getOrDefault(emptyList())
    }

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
        /** All entries exposed by the bundled extension. */
        val allEntries: List<FeedItem> = emptyList(),
        val categorySections: List<FeedCategory> = emptyList(),
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

    private companion object {
        const val MAX_CATALOGUE_PAGES = 15
        const val PAGE_TIMEOUT = 15_000L
        const val SEARCH_TIMEOUT = 8_000L
        const val POPULAR_FALLBACK_COUNT = 20
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

private fun animeCategoryNames(source: AnimeCatalogueSource): List<String> = buildList {
    source.safeFilterList().forEach { filter ->
        when (filter) {
            is AnimeFilter.Group<*> -> filter.state.forEach { child ->
                if (child is AnimeFilter<*>) {
                    val name = child.name
                    if (name.isNotBlank() &&
                        child !is AnimeFilter.Header &&
                        child !is AnimeFilter.Separator &&
                        !isTrendingName(name)
                    ) {
                        add(name)
                    }
                }
            }
            is AnimeFilter.TriState, is AnimeFilter.CheckBox -> {
                if (filter.name.isNotBlank() && !isTrendingName(filter.name)) add(filter.name)
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
                        !isTrendingName(name)
                    ) {
                        add(name)
                    }
                }
            }
            is Filter.TriState, is Filter.CheckBox -> {
                if (filter.name.isNotBlank() && !isTrendingName(filter.name)) add(filter.name)
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

private fun applyAnimeTrending(filters: AnimeFilterList, name: String): Boolean {
    val filter = filters.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return false
    return when (filter) {
        is AnimeFilter.Sort -> {
            val index = filter.values.indexOfFirst { isTrendingName(it) }
            filter.state = AnimeFilter.Sort.Selection(
                index = if (index >= 0) index else 0,
                ascending = false,
            )
            true
        }
        is AnimeFilter.Select<*> -> {
            val index = filter.values.indexOfFirst { isTrendingName(it.toString()) }
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
            val index = filter.values.indexOfFirst { isTrendingName(it) }
            filter.state = Filter.Sort.Selection(
                index = if (index >= 0) index else 0,
                ascending = false,
            )
            true
        }
        is Filter.Select<*> -> {
            val index = filter.values.indexOfFirst { isTrendingName(it.toString()) }
            filter.state = if (index >= 0) index else 0
            true
        }
        else -> false
    }
}

private const val MAX_CATEGORY_FILTERS = 6
