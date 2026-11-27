package eu.kanade.tachiyomi.util.devicesync

import tachiyomi.domain.category.anime.repository.AnimeCategoryRepository
import tachiyomi.domain.category.manga.repository.MangaCategoryRepository
import tachiyomi.domain.category.novel.repository.NovelCategoryRepository
import tachiyomi.domain.entries.anime.repository.AnimeRepository
import tachiyomi.domain.entries.manga.repository.MangaRepository
import tachiyomi.domain.history.anime.repository.AnimeHistoryRepository
import tachiyomi.domain.history.manga.repository.MangaHistoryRepository
import tachiyomi.domain.items.chapter.repository.ChapterRepository
import tachiyomi.domain.items.episode.repository.EpisodeRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Builds a full cross-device snapshot as the baseline and the source of small delta events. */
class DeviceSyncSnapshotBuilder(
    private val store: DeviceSyncStore,
    private val deviceId: String,
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

    suspend fun build(): DeviceSyncSnapshot {
        val now = System.currentTimeMillis()
        val entries = buildAnime(now) + buildManga(now, novel = false) + buildManga(now, novel = true)
        return DeviceSyncSnapshot(deviceId = deviceId, generatedAt = now, entries = entries)
    }

    private suspend fun buildAnime(now: Long): List<DeviceSyncEntry> {
        val items = (animeRepository.getLibraryAnime().map { it.anime } + animeRepository.getWatchedAnimeNotInLibrary())
            .distinctBy { it.url }
        val previous = store.loadCategoryVersions()
        val updated = previous.toMutableMap()

        val result = items.map { item ->
            val episodes = episodeRepository.getEpisodeByAnimeId(item.id)
            val history = animeHistoryRepository.getHistoryByAnimeId(item.id).associateBy { it.episodeId }
            val categories = animeCategoryRepository.getCategoriesByAnimeId(item.id)
                .filter { !it.isSystemCategory }
                .map { it.name }
            val key = deviceEntryKey("anime", item.source, item.url)
            val versions = updatedVersions(updated[key].orEmpty(), categories, now)
            updated[key] = versions
            DeviceSyncEntry(
                key = key,
                kind = "anime",
                sourceId = item.source,
                url = item.url,
                title = item.title,
                thumbnailUrl = item.thumbnailUrl,
                favorite = item.favorite,
                dateAdded = item.dateAdded,
                updatedAt = now,
                categories = versions.filterValues { it > 0L }.keys.toList().sorted(),
                categoryVersions = versions,
                chapters = episodes.map { episode ->
                    val chapterKey = episode.url.ifBlank { episode.name }
                    DeviceSyncChapter(
                        url = chapterKey,
                        name = episode.name,
                        seen = episode.seen,
                        bookmark = episode.bookmark,
                        position = episode.lastSecondSeen,
                        readAt = history[episode.id]?.seenAt?.time ?: 0L,
                        dateUpload = episode.dateUpload,
                        updatedAt = now,
                    )
                },
            )
        }
        store.saveCategoryVersions(updated)
        return result
    }

    private suspend fun buildManga(now: Long, novel: Boolean): List<DeviceSyncEntry> {
        val kind = if (novel) "novel" else "manga"
        val items = (
            if (novel) mangaRepository.getNovelLibraryManga() else mangaRepository.getLibraryManga()
            )
            .map { it.manga }
            .plus(
                mangaRepository.getReadMangaNotInLibrary()
                    .filter { it.isNovel == novel },
            )
            .distinctBy { it.url }
        val previous = store.loadCategoryVersions()
        val updated = previous.toMutableMap()

        val result = items.map { item ->
            val chapters = chapterRepository.getChapterByMangaId(item.id)
            val history = mangaHistoryRepository.getHistoryByMangaId(item.id).associateBy { it.chapterId }
            val categories = (
                if (novel) {
                    novelCategoryRepository.getCategoriesByNovelId(item.id)
                } else {
                    mangaCategoryRepository.getCategoriesByMangaId(item.id)
                }
                )
                .filter { !it.isSystemCategory }
                .map { it.name }
            val key = deviceEntryKey(kind, item.source, item.url)
            val versions = updatedVersions(updated[key].orEmpty(), categories, now)
            updated[key] = versions
            DeviceSyncEntry(
                key = key,
                kind = kind,
                sourceId = item.source,
                url = item.url,
                title = item.title,
                thumbnailUrl = item.thumbnailUrl,
                favorite = item.favorite,
                dateAdded = item.dateAdded,
                updatedAt = now,
                categories = versions.filterValues { it > 0L }.keys.toList().sorted(),
                categoryVersions = versions,
                chapters = chapters.map { chapter ->
                    val chapterKey = chapter.url.ifBlank { chapter.name }
                    DeviceSyncChapter(
                        url = chapterKey,
                        name = chapter.name,
                        seen = chapter.read,
                        bookmark = chapter.bookmark,
                        position = chapter.lastPageRead,
                        readAt = history[chapter.id]?.readAt?.time ?: 0L,
                        dateUpload = chapter.dateUpload,
                        updatedAt = now,
                    )
                },
            )
        }
        store.saveCategoryVersions(updated)
        return result
    }

    private fun updatedVersions(
        previous: Map<String, Long>,
        current: List<String>,
        now: Long,
    ): Map<String, Long> {
        val result = previous.toMutableMap()
        current.forEach { name ->
            if ((result[name] ?: 0L) <= 0L) result[name] = now
        }
        previous.filterValues { it > 0L }.keys.minus(current.toSet()).forEach { name ->
            result[name] = -now
        }
        return result
    }
}
