package eu.kanade.tachiyomi.util.devicesync

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

/** Applies one merged entry. Progress is monotonic; membership/categories use the LWW timestamp. */
class DeviceSyncSnapshotApplier(
    private val store: DeviceSyncStore,
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

    suspend fun apply(entry: DeviceSyncEntry) {
        val applied = store.loadApplied().toMutableMap()
        val newer = entry.updatedAt > (applied[entry.key] ?: 0L)
        when (entry.kind) {
            "anime" -> applyAnime(entry, newer)
            "manga" -> applyManga(entry, newer, novel = false)
            "novel" -> applyManga(entry, newer, novel = true)
            else -> return
        }
        if (newer) {
            applied[entry.key] = entry.updatedAt
            store.saveApplied(applied)
        }
    }

    private suspend fun applyAnime(entry: DeviceSyncEntry, newer: Boolean) {
        var anime = animeRepository.getAnimeByUrlAndSourceId(entry.url, entry.sourceId)
        if (anime == null) {
            if (entry.deleted) return
            val id = animeRepository.insertAnime(
                Anime.create().copy(
                    source = entry.sourceId,
                    url = entry.url,
                    title = entry.title,
                    thumbnailUrl = entry.thumbnailUrl,
                    favorite = entry.favorite,
                    dateAdded = entry.dateAdded.takeIf { it > 0L } ?: System.currentTimeMillis(),
                ),
            ) ?: return
            anime = animeRepository.getAnimeById(id)
        }
        if (newer) {
            animeRepository.updateAnime(
                AnimeUpdate(
                    id = anime.id,
                    favorite = !entry.deleted && entry.favorite,
                    title = entry.title.ifBlank { null },
                    lastUpdate = entry.lastUpdate.takeIf { it > 0L },
                    status = entry.status.takeIf { it >= 0L },
                ),
            )
            val categoryIds = if (entry.deleted) {
                emptyList()
            } else {
                entry.categories.map { name ->
                    resolveCategory(
                        name,
                        animeCategoryRepository.getAllAnimeCategories(),
                        { animeCategoryRepository.insertAnimeCategory(it) },
                        { animeCategoryRepository.getAllAnimeCategories() },
                    ).id
                }
            }
            animeRepository.setAnimeCategories(anime.id, categoryIds)
        }
        if (entry.deleted) return

        val episodes = episodeRepository.getEpisodeByAnimeId(anime.id)
        val byKey = episodes.associateBy { it.url.ifBlank { it.name } }
        val missing = entry.chapters.filter { it.itemKey !in byKey }
        if (missing.isNotEmpty()) {
            episodeRepository.addAllEpisodes(
                missing.mapIndexed { index, chapter ->
                    Episode.create().copy(
                        animeId = anime.id,
                        url = chapter.url,
                        name = chapter.name.ifBlank { chapter.url },
                        sourceOrder = index.toLong(),
                        seen = chapter.seen,
                        bookmark = chapter.bookmark,
                        fillermark = chapter.fillermark,
                        scanlator = chapter.scanlator.ifBlank { null },
                        lastSecondSeen = chapter.position,
                        dateFetch = chapter.dateFetch,
                        dateUpload = chapter.dateUpload,
                    )
                },
            )
        }
        val history = animeHistoryRepository.getHistoryByAnimeId(anime.id).associateBy { it.episodeId }
        val current = episodeRepository.getEpisodeByAnimeId(anime.id)
        episodeRepository.updateAllEpisodes(
            current.mapNotNull { episode ->
                val incoming = entry.chapters.firstOrNull { it.itemKey == episode.url.ifBlank { episode.name } }
                    ?: return@mapNotNull null
                EpisodeUpdate(
                    id = episode.id,
                    seen = episode.seen || incoming.seen,
                    bookmark = episode.bookmark || incoming.bookmark,
                    fillermark = episode.fillermark || incoming.fillermark,
                    lastSecondSeen = maxOf(episode.lastSecondSeen, incoming.position),
                    dateFetch = incoming.dateFetch.takeIf { it > episode.dateFetch },
                    scanlator = incoming.scanlator.ifBlank { null },
                )
            },
        )
        current.forEach { episode ->
            val incoming = entry.chapters.firstOrNull { it.itemKey == episode.url.ifBlank { episode.name } }
            val missingCount = (incoming?.count ?: 0L) - episode.watchCount
            if (missingCount > 0L) {
                repeat(missingCount.coerceAtMost(1000L).toInt()) {
                    episodeRepository.incrementWatchCount(episode.id)
                }
            }
            val readAt = incoming?.readAt ?: 0L
            if (readAt > (history[episode.id]?.seenAt?.time ?: 0L)) {
                animeHistoryRepository.upsertAnimeHistory(
                    AnimeHistoryUpdate(episode.id, Date(readAt)),
                )
            }
        }
    }

    private suspend fun applyManga(entry: DeviceSyncEntry, newer: Boolean, novel: Boolean) {
        var manga = mangaRepository.getMangaByUrlAndSourceId(entry.url, entry.sourceId)
        if (manga == null) {
            if (entry.deleted) return
            val id = mangaRepository.insertManga(
                Manga.create().copy(
                    source = entry.sourceId,
                    url = entry.url,
                    title = entry.title,
                    thumbnailUrl = entry.thumbnailUrl,
                    favorite = entry.favorite,
                    isNovel = novel,
                    dateAdded = entry.dateAdded.takeIf { it > 0L } ?: System.currentTimeMillis(),
                ),
            ) ?: return
            manga = mangaRepository.getMangaById(id)
        }
        if (newer) {
            mangaRepository.updateManga(
                MangaUpdate(
                    id = manga.id,
                    favorite = !entry.deleted && entry.favorite,
                    title = entry.title.ifBlank { null },
                    lastUpdate = entry.lastUpdate.takeIf { it > 0L },
                    status = entry.status.takeIf { it >= 0L },
                ),
            )
            val ids = if (entry.deleted) {
                emptyList()
            } else {
                val all = if (novel) {
                    novelCategoryRepository.getAllNovelCategories()
                } else {
                    mangaCategoryRepository.getAllMangaCategories()
                }
                entry.categories.map { name ->
                    resolveCategory(
                        name,
                        all,
                        { category ->
                            if (novel) {
                                novelCategoryRepository.insertNovelCategory(category)
                            } else {
                                mangaCategoryRepository.insertMangaCategory(category)
                            }
                        },
                        {
                            if (novel) {
                                novelCategoryRepository.getAllNovelCategories()
                            } else {
                                mangaCategoryRepository.getAllMangaCategories()
                            }
                        },
                    ).id
                }
            }
            if (novel) {
                novelCategoryRepository.setNovelCategories(manga.id, ids)
            } else {
                mangaRepository.setMangaCategories(manga.id, ids)
            }
        }
        if (entry.deleted) return

        val chapters = chapterRepository.getChapterByMangaId(manga.id)
        val byKey = chapters.associateBy { it.url.ifBlank { it.name } }
        val missing = entry.chapters.filter { it.itemKey !in byKey }
        if (missing.isNotEmpty()) {
            chapterRepository.addAllChapters(
                missing.mapIndexed { index, chapter ->
                    Chapter.create().copy(
                        mangaId = manga.id,
                        url = chapter.url,
                        name = chapter.name.ifBlank { chapter.url },
                        sourceOrder = index.toLong(),
                        read = chapter.seen,
                        bookmark = chapter.bookmark,
                        lastPageRead = chapter.position,
                        dateFetch = chapter.dateFetch,
                        dateUpload = chapter.dateUpload,
                        scanlator = chapter.scanlator.ifBlank { null },
                    )
                },
            )
        }
        val history = mangaHistoryRepository.getHistoryByMangaId(manga.id).associateBy { it.chapterId }
        val current = chapterRepository.getChapterByMangaId(manga.id)
        chapterRepository.updateAllChapters(
            current.mapNotNull { chapter ->
                val incoming = entry.chapters.firstOrNull { it.itemKey == chapter.url.ifBlank { chapter.name } }
                    ?: return@mapNotNull null
                ChapterUpdate(
                    id = chapter.id,
                    read = chapter.read || incoming.seen,
                    bookmark = chapter.bookmark || incoming.bookmark,
                    lastPageRead = maxOf(chapter.lastPageRead, incoming.position),
                    dateFetch = incoming.dateFetch.takeIf { it > chapter.dateFetch },
                    scanlator = incoming.scanlator.ifBlank { null },
                )
            },
        )
        current.forEach { chapter ->
            val incoming = entry.chapters.firstOrNull { it.itemKey == chapter.url.ifBlank { chapter.name } }
            val missingCount = (incoming?.count ?: 0L) - chapter.readCount
            if (missingCount > 0L) {
                repeat(missingCount.coerceAtMost(1000L).toInt()) {
                    chapterRepository.incrementReadCount(chapter.id)
                }
            }
            val readAt = incoming?.readAt ?: 0L
            if (readAt > (history[chapter.id]?.readAt?.time ?: 0L)) {
                mangaHistoryRepository.upsertMangaHistory(
                    MangaHistoryUpdate(chapter.id, Date(readAt), 0L),
                )
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
