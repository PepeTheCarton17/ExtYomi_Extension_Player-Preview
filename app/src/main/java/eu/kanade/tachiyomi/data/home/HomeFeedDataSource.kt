package eu.kanade.tachiyomi.data.home

import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.jsonMime
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.injectLazy

/**
 * Read-only AniList GraphQL feed. No OAuth token is used: this is the public endpoint and the
 * tracking login is deliberately left alone. Cover URLs returned by the API are kept verbatim.
 */
class HomeFeedDataSource(
    private val client: OkHttpClient = OkHttpClient(),
) {

    private val json: Json by injectLazy()

    suspend fun getMedia(
        anime: Boolean,
        sort: String = "TRENDING_DESC",
        genre: String? = null,
        page: Int = 1,
    ): List<HomeFeedMedia> = withIOContext {
        val type = if (anime) "ANIME" else "MANGA"
        val genreFilter = if (genre.isNullOrBlank()) {
            ""
        } else {
            ", genre_in: [\"" + genre.replace("\"", "\\\"") + "\"]"
        }
        val query = """
            query Feed(${"$"}type: MediaType!, ${"$"}page: Int!) {
                Page(page: ${"$"}page, perPage: 20) {
                    media(type: ${"$"}type, sort: $sort, isAdult: false$genreFilter) {
                        id
                        title { userPreferred romaji english }
                        coverImage { large extraLarge }
                        averageScore
                        description(asHtml: false)
                        format
                        genres
                        episodes
                        nextAiringEpisode { airingAt }
                    }
                }
            }
        """.trimIndent()
        val payload = buildJsonObject {
            put("query", query)
            put(
                "variables",
                buildJsonObject {
                    put("type", type)
                    put("page", page)
                },
            )
        }

        with(json) {
            client.newCall(
                POST(
                    API_URL,
                    body = payload.toString().toRequestBody(jsonMime),
                ),
            )
                .awaitSuccess()
                .parseAs<AniListFeedResponse>()
                .data
                .page
                .media
                .mapNotNull { media ->
                    val title = listOfNotNull(
                        media.title.userPreferred,
                        media.title.romaji,
                        media.title.english,
                    ).firstOrNull { it.isNotBlank() } ?: return@mapNotNull null
                    HomeFeedMedia(
                        id = media.id,
                        title = title,
                        coverUrl = listOfNotNull(media.coverImage.extraLarge, media.coverImage.large)
                            .firstOrNull { it.isNotBlank() },
                        isAnime = anime,
                        rating = media.averageScore
                            ?.takeIf { it > 0 }
                            ?.let { String.format("%.1f", it / 10.0) }
                            .orEmpty(),
                        synopsis = media.description.orEmpty(),
                        format = media.format.orEmpty(),
                        genres = media.genres.mapNotNull { it },
                        episodeCount = media.episodes,
                        airingAt = media.nextAiringEpisode?.airingAt,
                    )
                }
        }
    }

    companion object {
        private const val API_URL = "https://graphql.anilist.co/"
    }
}

data class HomeFeedMedia(
    val id: Long,
    val title: String,
    val coverUrl: String?,
    val isAnime: Boolean,
    val rating: String,
    val synopsis: String,
    val format: String,
    val genres: List<String>,
    val episodeCount: Int? = null,
    val airingAt: Long? = null,
)

@Serializable
private data class AniListFeedResponse(val data: AniListFeedData)

@Serializable
private data class AniListFeedData(@SerialName("Page") val page: AniListFeedPage)

@Serializable
private data class AniListFeedPage(val media: List<AniListFeedMedia>)

@Serializable
private data class AniListFeedMedia(
    val id: Long,
    val title: AniListFeedTitle,
    val coverImage: AniListFeedCover,
    val averageScore: Int? = null,
    val description: String? = null,
    val format: String? = null,
    // AniList occasionally includes null entries; don't let one break the whole feed.
    val genres: List<String?> = emptyList(),
    val episodes: Int? = null,
    val nextAiringEpisode: AniListAiring? = null,
)

@Serializable
private data class AniListAiring(val airingAt: Long? = null)

@Serializable
private data class AniListFeedTitle(
    val userPreferred: String? = null,
    val romaji: String? = null,
    val english: String? = null,
)

@Serializable
private data class AniListFeedCover(
    val large: String? = null,
    val extraLarge: String? = null,
)
