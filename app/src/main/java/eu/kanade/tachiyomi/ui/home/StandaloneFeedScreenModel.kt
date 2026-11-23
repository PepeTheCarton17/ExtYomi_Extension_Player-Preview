package eu.kanade.tachiyomi.ui.home

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.ui.StandaloneScope
import eu.kanade.tachiyomi.animesource.AnimeCatalogueSource
import eu.kanade.tachiyomi.data.home.HomeFeedDataSource
import eu.kanade.tachiyomi.data.home.HomeFeedMedia
import eu.kanade.tachiyomi.source.CatalogueSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.history.anime.interactor.GetAnimeHistory
import tachiyomi.domain.history.manga.interactor.GetMangaHistory
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.source.manga.service.MangaSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Home feed for a generated standalone app. Titles come from the public AniList feed; the local
 * catalogue is only queried to check whether a title exists in the single bundled source. Nothing
 * is inserted into the local library and the cover URLs from the API are never replaced.
 */
class StandaloneFeedScreenModel : StateScreenModel<StandaloneFeedScreenModel.State>(State()) {

    private val dataSource = HomeFeedDataSource()
    private val getAnimeHistory: GetAnimeHistory = Injekt.get()
    private val getMangaHistory: GetMangaHistory = Injekt.get()
    private val animeSourceManager: AnimeSourceManager = Injekt.get()
    private val mangaSourceManager: MangaSourceManager = Injekt.get()

    private val isStandalone = StandaloneScope.isActive
    // In the generator (no standalone config) the feed is global: show both anime and manga.
    private val isAnimeScope = !isStandalone || StandaloneScope.appliesTo(StandaloneScope.KIND_ANIME)
    private val isNovelScope = StandaloneScope.appliesTo(StandaloneScope.KIND_NOVEL)
    private val isMangaContent = !isStandalone ||
        StandaloneScope.appliesTo(StandaloneScope.KIND_MANGA) || isNovelScope
    private val sourceId = StandaloneScope.sourceId

    init {
        refresh()
    }

    fun refresh() {
        screenModelScope.launchIO {
            mutableState.update { it.copy(isRefreshing = true) }

            val continueList = loadContinue()

            val trendingAnime = if (isAnimeScope) safeFeed(true, "TRENDING_DESC") else emptyList()
            val trendingManga = if (isMangaContent) safeFeed(false, "TRENDING_DESC") else emptyList()
            val trending = (trendingAnime + trendingManga).map(::toFeedItem)

            // Use the first genre of the top trending title to give recommendations a personal touch.
            val genre = trending.firstOrNull()?.genres?.firstOrNull()
            val recommendedAnime = if (isAnimeScope) safeFeed(true, "SCORE_DESC", genre) else emptyList()
            val recommendedManga = if (isMangaContent) safeFeed(false, "SCORE_DESC", genre) else emptyList()
            val recommended = (recommendedAnime + recommendedManga).map(::toFeedItem)

            val popularAnime = if (isAnimeScope) safeFeed(true, "POPULARITY_DESC") else emptyList()
            val popularManga = if (isMangaContent) safeFeed(false, "POPULARITY_DESC") else emptyList()
            val popular = (popularAnime + popularManga).map(::toFeedItem)

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

            // Only the titles that can actually be shown need a catalogue lookup.
            verifyAvailability(
                listOf(trending, recommended, popular)
                    .flatMap { it.take(10) }
                    .distinctBy { normalized(it.title) to it.isAnime },
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
        mutableState.update { it.copy(itemsPerSection = count.coerceIn(3, 20)) }
    }

    private suspend fun safeFeed(
        anime: Boolean,
        sort: String,
        genre: String? = null,
    ): List<HomeFeedMedia> {
        return runCatching { dataSource.getMedia(anime, sort, genre) }.getOrDefault(emptyList())
    }

    private fun toFeedItem(media: HomeFeedMedia) = FeedItem(
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

    /**
     * Queries the bundled source for every title. Uses a small concurrency limit so weak devices
     * are not hit by dozens of parallel catalogue requests, and never writes to the database.
     */
    private suspend fun verifyAvailability(items: List<FeedItem>) {
        // A global feed has no single bundled source to check against.
        if (items.isEmpty() || !isStandalone) return
        val unique = items.distinctBy { normalized(it.title) to it.isAnime }
        val results = HashMap<Pair<String, Boolean>, Boolean>()
        val semaphore = Semaphore(4)

        coroutineScope {
            unique.map { item ->
                async {
                    semaphore.withPermit {
                        val key = normalized(item.title) to item.isAnime
                        val available = runCatching {
                            withTimeout(8_000) { isAvailable(item) }
                        }.getOrDefault(false)
                        synchronized(results) { results[key] = available }
                    }
                }
            }.awaitAll()
        }

        mutableState.update { state ->
            fun List<FeedItem>.mark(): List<FeedItem> = map { item ->
                val available = results[normalized(item.title) to item.isAnime]
                    ?: return@map item
                item.copy(available = available)
            }
            state.copy(
                trending = state.trending.mark(),
                recommended = state.recommended.mark(),
                popular = state.popular.mark(),
            )
        }
    }

    private suspend fun isAvailable(item: FeedItem): Boolean = withIOContext {
        if (item.isAnime) {
            val source = animeSourceManager.get(sourceId) as? AnimeCatalogueSource
                ?: return@withIOContext false
            val page = source.getSearchAnime(1, item.title, source.getFilterList())
            page.animes.any { titlesMatch(it.title, item.title) }
        } else {
            val source = mangaSourceManager.get(sourceId) as? CatalogueSource
                ?: return@withIOContext false
            val page = source.getSearchManga(1, item.title, source.getFilterList())
            page.mangas.any { titlesMatch(it.title, item.title) }
        }
    }

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val isRefreshing: Boolean = false,
        val trending: List<FeedItem> = emptyList(),
        val recommended: List<FeedItem> = emptyList(),
        val popular: List<FeedItem> = emptyList(),
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
    data class FeedItem(
        val id: Long,
        val title: String,
        val coverUrl: String?,
        val isAnime: Boolean,
        val rating: String,
        val synopsis: String,
        val format: String,
        val genres: List<String>,
        /** null while the bundled source is still being queried. */
        val available: Boolean? = null,
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
}

private fun normalized(title: String): String =
    title.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

private fun titlesMatch(a: String, b: String): Boolean {
    val first = normalized(a)
    val second = normalized(b)
    if (first.isBlank() || second.isBlank()) return false
    if (first == second || first.contains(second) || second.contains(first)) return true
    val firstWords = first.split(' ').filter { it.length > 2 }.toSet()
    val secondWords = second.split(' ').filter { it.length > 2 }.toSet()
    return firstWords.isNotEmpty() &&
        secondWords.isNotEmpty() &&
        firstWords.intersect(secondWords).size >= 2
}
