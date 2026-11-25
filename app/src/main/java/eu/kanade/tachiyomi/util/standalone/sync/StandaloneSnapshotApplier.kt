package eu.kanade.tachiyomi.util.standalone.sync

import eu.kanade.domain.ui.StandaloneScope
import tachiyomi.domain.category.anime.repository.AnimeCategoryRepository
import tachiyomi.domain.category.manga.repository.MangaCategoryRepository
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.novel.repository.NovelCategoryRepository
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.anime.model.AnimeUpdate
import tachiyomi.domain.entries.anime.repository.AnimeRepository
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.entries.manga.model.MangaUpdate
import tachiyomi.domain.entries.manga.repository.MangaRepository
import tachiyomi.domain.history.anime.model.AnimeHistoryUpdate
import tachiyomi.domain.history.anime.repository.AnimeHistoryRepository
import tachiyomi.domain.history.manga.model.MangaHistoryUpdate
import tachiyomi.domain.history.manga.repository.MangaHistoryRepository
import tachiyomi.domain.items.chapter.model.Chapter
import tachiyomi.domain.items.chapter.model.ChapterUpdate
import tachiyomi.domain.items.chapter.repository.ChapterRepository
import tachiyomi.domain.items.episode.model.Episode
import tachiyomi.domain.items.episode.model.EpisodeUpdate
import tachiyomi.domain.items.episode.repository.EpisodeRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date

/** Applies a synced snapshot to this app's database, never touching entries outside the scope. */
class StandaloneSnapshotApplier(
    private val sourceId: Long = StandaloneScope.sourceId,
    private val kind: String = StandaloneScope.kind,
    private val animeRepository: AnimeRepository = Injekt.get(),
    private val mangaRepository: MangaRepository = Injekt.get(),
    private val episodeRepository: EpisodeRepository = Injekt.get(),
    private val chapterRepository: ChapterRepository = Injekt.get(),
    private val animeHistoryRepository: AnimeHistoryRepository = Injekt.get(),
    private val mangaHistoryRepository: MangaHistoryRepository = Injekt.get(),
    private val animeCategoryRepository: AnimeCategoryRepository = Injekt.get(),
    private val mangaCategoryRepository: MangaCategoryRepository = Injekt.get(),
    private val novelCategoryRepository: NovelCategoryRepository = Injekt.get(),
) {

    suspend fun apply(payload: StandaloneSyncPayload) {
        require(payload.sourceId == sourceId && payload.kind == kind) {
            "Refusing a snapshot from another scope"
        }
        if (kind == StandaloneScope.KIND_ANIME) {
            applyAnime(payload)
        } else {
            applyManga(payload)
        }
    }

    private suspend fun applyAnime(payload: StandaloneSyncPayload) {
        payload.entries.forEach { entry ->
            var anime = animeRepository.getAnimeByUrlAndSourceId(entry.url, sourceId)
            if (anime == null) {
                val id = animeRepository.insertAnime(
                    Anime.create().copy(
                        source = sourceId,
                        url = entry.url,
                        title = entry.title,
                        thumbnailUrl = entry.thumbnailUrl,
                        favorite = entry.favorite,
                        dateAdded = entry.dateAdded,
                    ),
                ) ?: return@forEach
                anime = animeRepository.getAnimeById(id)
            }
            animeRepository.updateAnime(AnimeUpdate(id = anime.id, favorite = entry.favorite))

            val existing = episodeRepository.getEpisodeByAnimeId(anime.id)
            val byUrl = existing.associateBy { it.url.ifBlank { it.name } }
            val missing = entry.chapters.filter { it.url !in byUrl }
            if (missing.isNotEmpty()) {
                episodeRepository.addAllEpisodes(
                    missing.mapIndexed { index, chapter ->
                        Episode.create().copy(
                            animeId = anime.id,
                            url = chapter.url,
                            name = chapter.url,
                            sourceOrder = index.toLong(),
                            seen = chapter.seen,
                            bookmark = chapter.bookmark,
                            lastSecondSeen = chapter.position,
                        )
                    },
                )
            }
            val nowExisting = episodeRepository.getEpisodeByAnimeId(anime.id)
            episodeRepository.updateAllEpisodes(
                nowExisting.mapNotNull { episode ->
                    val incoming = entry.chapters.firstOrNull {
                        it.url == episode.url.ifBlank { episode.name }
                    } ?: return@mapNotNull null
                    EpisodeUpdate(
                        id = episode.id,
                        seen = incoming.seen,
                        bookmark = incoming.bookmark,
                        lastSecondSeen = incoming.position,
                    )
                },
            )

            animeHistoryRepository.resetHistoryByAnimeId(anime.id)
            nowExisting.forEach { episode ->
                val readAt = entry.chapters
                    .firstOrNull { it.url == episode.url.ifBlank { episode.name } }
                    ?.readAt
                    ?: 0L
                if (readAt > 0L) {
                    animeHistoryRepository.upsertAnimeHistory(
                        AnimeHistoryUpdate(episode.id, Date(readAt)),
                    )
                }
            }

            val categoryIds = entry.categories.map { name ->
                resolveCategory(
                    name,
                    animeCategoryRepository.getAllAnimeCategories(),
                    { animeCategoryRepository.insertAnimeCategory(it) },
                    { animeCategoryRepository.getAllAnimeCategories() },
                ).id
            }
            animeRepository.setAnimeCategories(anime.id, categoryIds)
        }
    }

    private suspend fun applyManga(payload: StandaloneSyncPayload) {
        val isNovel = kind == StandaloneScope.KIND_NOVEL
        payload.entries.forEach { entry ->
            var manga = mangaRepository.getMangaByUrlAndSourceId(entry.url, sourceId)
            if (manga == null) {
                val id = mangaRepository.insertManga(
                    Manga.create().copy(
                        source = sourceId,
                        url = entry.url,
                        title = entry.title,
                        thumbnailUrl = entry.thumbnailUrl,
                        favorite = entry.favorite,
                        isNovel = isNovel || entry.isNovel,
                        dateAdded = entry.dateAdded,
                    ),
                ) ?: return@forEach
                manga = mangaRepository.getMangaById(id)
            }
            mangaRepository.updateManga(MangaUpdate(id = manga.id, favorite = entry.favorite))

            val existing = chapterRepository.getChapterByMangaId(manga.id)
            val byUrl = existing.associateBy { it.url.ifBlank { it.name } }
            val missing = entry.chapters.filter { it.url !in byUrl }
            if (missing.isNotEmpty()) {
                chapterRepository.addAllChapters(
                    missing.mapIndexed { index, chapter ->
                        Chapter.create().copy(
                            mangaId = manga.id,
                            url = chapter.url,
                            name = chapter.url,
                            sourceOrder = index.toLong(),
                            read = chapter.seen,
                            bookmark = chapter.bookmark,
                            lastPageRead = chapter.position,
                        )
                    },
                )
            }
            val nowExisting = chapterRepository.getChapterByMangaId(manga.id)
            chapterRepository.updateAllChapters(
                nowExisting.mapNotNull { chapter ->
                    val incoming = entry.chapters.firstOrNull {
                        it.url == chapter.url.ifBlank { chapter.name }
                    } ?: return@mapNotNull null
                    ChapterUpdate(
                        id = chapter.id,
                        read = incoming.seen,
                        bookmark = incoming.bookmark,
                        lastPageRead = incoming.position,
                    )
                },
            )

            mangaHistoryRepository.resetHistoryByMangaId(manga.id)
            nowExisting.forEach { chapter ->
                val readAt = entry.chapters
                    .firstOrNull { it.url == chapter.url.ifBlank { chapter.name } }
                    ?.readAt
                    ?: 0L
                if (readAt > 0L) {
                    mangaHistoryRepository.upsertMangaHistory(
                        MangaHistoryUpdate(chapter.id, Date(readAt), 0L),
                    )
                }
            }

            val allCategories = if (isNovel) {
                novelCategoryRepository.getAllNovelCategories()
            } else {
                mangaCategoryRepository.getAllMangaCategories()
            }
            val categoryIds = entry.categories.map { name ->
                resolveCategory(
                    name,
                    allCategories,
                    { category ->
                        if (isNovel) {
                            novelCategoryRepository.insertNovelCategory(category)
                        } else {
                            mangaCategoryRepository.insertMangaCategory(category)
                        }
                    },
                    {
                        if (isNovel) {
                            novelCategoryRepository.getAllNovelCategories()
                        } else {
                            mangaCategoryRepository.getAllMangaCategories()
                        }
                    },
                ).id
            }
            if (isNovel) {
                novelCategoryRepository.setNovelCategories(manga.id, categoryIds)
            } else {
                mangaRepository.setMangaCategories(manga.id, categoryIds)
            }
        }
    }

    private suspend fun resolveCategory(
        name: String,
        existing: List<Category>,
        insert: suspend (Category) -> Unit,
        reload: suspend () -> List<Category>,
    ): Category {
        existing.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it }
        insert(
            Category(
                id = -1L,
                name = name,
                order = existing.size.toLong(),
                flags = 0L,
                hidden = false,
            ),
        )
        return reload().first { it.name.equals(name, ignoreCase = true) }
    }
}
