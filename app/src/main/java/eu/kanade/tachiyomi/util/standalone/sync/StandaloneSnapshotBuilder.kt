package eu.kanade.tachiyomi.util.standalone.sync

import eu.kanade.domain.ui.StandaloneScope
import tachiyomi.domain.category.anime.repository.AnimeCategoryRepository
import tachiyomi.domain.category.manga.repository.MangaCategoryRepository
import tachiyomi.domain.category.novel.repository.NovelCategoryRepository
import tachiyomi.domain.history.anime.repository.AnimeHistoryRepository
import tachiyomi.domain.history.manga.repository.MangaHistoryRepository
import tachiyomi.domain.items.chapter.repository.ChapterRepository
import tachiyomi.domain.items.episode.repository.EpisodeRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/** Builds a snapshot from this app's database, restricted to one extension scope. */
class StandaloneSnapshotBuilder(
    private val sourceId: Long = StandaloneScope.sourceId,
    private val kind: String = StandaloneScope.kind,
    private val instanceId: String = StandaloneScope.instanceId,
    private val extensionPkg: String = StandaloneScope.extensionPkg,
    private val animeRepository: tachiyomi.domain.entries.anime.repository.AnimeRepository = Injekt.get(),
    private val mangaRepository: tachiyomi.domain.entries.manga.repository.MangaRepository = Injekt.get(),
    private val episodeRepository: EpisodeRepository = Injekt.get(),
    private val chapterRepository: ChapterRepository = Injekt.get(),
    private val animeHistoryRepository: AnimeHistoryRepository = Injekt.get(),
    private val mangaHistoryRepository: MangaHistoryRepository = Injekt.get(),
    private val animeCategoryRepository: AnimeCategoryRepository = Injekt.get(),
    private val mangaCategoryRepository: MangaCategoryRepository = Injekt.get(),
    private val novelCategoryRepository: NovelCategoryRepository = Injekt.get(),
    /** Last snapshot this app produced, used to keep timestamps stable for unchanged entries. */
    private val previous: StandaloneSyncPayload? = null,
) {

    suspend fun build(downloadsRootUri: String? = null): StandaloneSyncPayload {
        val now = System.currentTimeMillis()
        val current = if (kind == StandaloneScope.KIND_ANIME) {
            buildAnimeEntries(now)
        } else {
            buildMangaEntries(now)
        }
        val previousByUrl = previous?.entries?.associateBy { it.url }.orEmpty()
        val stable = current.map { entry ->
            val old = previousByUrl[entry.url]
            if (old != null && entry.sameContentAs(old)) {
                entry.copy(
                    updatedAt = old.updatedAt,
                    chapters = entry.chapters.map { chapter ->
                        val oldChapter = old.chapters.firstOrNull { it.url == chapter.url }
                        if (
                            oldChapter != null &&
                            chapter.name == oldChapter.name &&
                            chapter.seen == oldChapter.seen &&
                            chapter.bookmark == oldChapter.bookmark &&
                            chapter.position == oldChapter.position &&
                            chapter.readAt == oldChapter.readAt
                        ) {
                            chapter.copy(updatedAt = oldChapter.updatedAt)
                        } else {
                            chapter.copy(updatedAt = now)
                        }
                    },
                )
            } else {
                entry.copy(
                    updatedAt = now,
                    chapters = entry.chapters.map { it.copy(updatedAt = now) },
                )
            }
        }
        // Removals have to travel too: without a tombstone the other app keeps the entry forever.
        val currentUrls = stable.map { it.url }.toSet()
        val tombstones = previousByUrl.keys
            .minus(currentUrls)
            .mapNotNull { url ->
                val old = previousByUrl[url] ?: return@mapNotNull null
                // Keep an existing tombstone's timestamp: re-sending it with `now` on every sync
                // would keep bumping revisions and could beat a later re-add on the other app.
                if (old.isTombstone) {
                    old
                } else {
                    old.copy(
                        favorite = false,
                        categories = emptyList(),
                        chapters = emptyList(),
                        updatedAt = now,
                    )
                }
            }
        return StandaloneSyncPayload(
            instanceId = instanceId,
            sourceId = sourceId,
            kind = kind,
            extensionPkg = extensionPkg,
            generatedAt = now,
            revision = previous?.revision ?: 0L,
            entries = stable + tombstones,
            downloadsRootUri = downloadsRootUri,
        )
    }

    private suspend fun buildAnimeEntries(now: Long): List<SyncEntry> {
        val anime = animeRepository.getLibraryAnime()
            .asSequence()
            .filter { it.anime.source == sourceId }
            .map { it.anime }
            .plus(
                animeRepository.getWatchedAnimeNotInLibrary().filter { it.source == sourceId },
            )
            .distinctBy { it.url }

        val result = mutableListOf<SyncEntry>()
        for (item in anime) {
            val episodes = episodeRepository.getEpisodeByAnimeId(item.id)
            val history = animeHistoryRepository.getHistoryByAnimeId(item.id)
                .associateBy { it.episodeId }
            val historyByUrl = episodes.associate {
                it.url.ifBlank { it.name } to history[it.id]?.seenAt?.time
            }
            val categories = animeCategoryRepository.getCategoriesByAnimeId(item.id)
                .filter { !it.isSystemCategory }
                .map { it.name }
            result += SyncEntry(
                url = item.url,
                title = item.title,
                thumbnailUrl = item.thumbnailUrl,
                favorite = item.favorite,
                dateAdded = item.dateAdded,
                updatedAt = now,
                categories = if (item.favorite) categories else emptyList(),
                chapters = episodes.map { episode ->
                    val key = episode.url.ifBlank { episode.name }
                    SyncChapter(
                        url = key,
                        name = episode.name,
                        seen = episode.seen,
                        bookmark = episode.bookmark,
                        position = episode.lastSecondSeen,
                        readAt = historyByUrl[key] ?: 0L,
                        updatedAt = now,
                    )
                },
            )
        }
        return result
    }

    private suspend fun buildMangaEntries(now: Long): List<SyncEntry> {
        val isNovel = kind == StandaloneScope.KIND_NOVEL
        val manga = (
            if (isNovel) mangaRepository.getNovelLibraryManga() else mangaRepository.getLibraryManga()
            )
            .asSequence()
            .filter { it.manga.source == sourceId }
            .map { it.manga }
            .plus(
                mangaRepository.getReadMangaNotInLibrary()
                    .filter { it.source == sourceId && it.isNovel == isNovel },
            )
            .distinctBy { it.url }

        val result = mutableListOf<SyncEntry>()
        for (item in manga) {
            val chapters = chapterRepository.getChapterByMangaId(item.id)
            val history = mangaHistoryRepository.getHistoryByMangaId(item.id)
                .associateBy { it.chapterId }
            val historyByUrl = chapters.associate {
                it.url.ifBlank { it.name } to history[it.id]?.readAt?.time
            }
            val categories = (
                if (isNovel) {
                    novelCategoryRepository.getCategoriesByNovelId(item.id)
                } else {
                    mangaCategoryRepository.getCategoriesByMangaId(item.id)
                }
                )
                .filter { !it.isSystemCategory }
                .map { it.name }
            result += SyncEntry(
                url = item.url,
                title = item.title,
                thumbnailUrl = item.thumbnailUrl,
                favorite = item.favorite,
                isNovel = isNovel,
                dateAdded = item.dateAdded,
                updatedAt = now,
                categories = if (item.favorite) categories else emptyList(),
                chapters = chapters.map { chapter ->
                    val key = chapter.url.ifBlank { chapter.name }
                    SyncChapter(
                        url = key,
                        name = chapter.name,
                        seen = chapter.read,
                        bookmark = chapter.bookmark,
                        position = chapter.lastPageRead,
                        readAt = historyByUrl[key] ?: 0L,
                        updatedAt = now,
                    )
                },
            )
        }
        return result
    }
}
