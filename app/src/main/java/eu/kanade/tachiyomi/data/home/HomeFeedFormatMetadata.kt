package eu.kanade.tachiyomi.data.home

import eu.kanade.domain.ui.model.ContentFormat
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Optional content-format hook for the new Home.
 *
 * Extension authors can opt in by exposing either
 * `fun getContentFormats(): Collection<String>` or a public `contentFormats` field on the source
 * (strings such as "MOVIE", "MANHWA", "WEBTOON", "LIGHT_NOVEL"). Nothing is required: when the hook
 * is absent we infer the format from AniList's value and from genre/filter names. See
 * `docs/HOME_FEED_FORMATS.md`.
 */
object HomeFeedFormatMetadata {

    fun detect(
        source: Any?,
        genres: List<String>,
        apiFormat: String,
    ): Set<ContentFormat> = buildSet {
        declaredFormats(source).mapNotNull(::parse).forEach(::add)
        parse(apiFormat)?.let(::add)
        genres.mapNotNull(::parse).forEach(::add)
    }

    fun isMovie(formats: Set<ContentFormat>) = ContentFormat.MOVIE in formats

    fun isSeries(formats: Set<ContentFormat>) = ContentFormat.SERIES in formats

    /** Reads the opt-in hook without making the host depend on any new extension interface. */
    private fun declaredFormats(source: Any?): Set<String> {
        if (source == null) return emptySet()
        val values = runCatching {
            source.javaClass.methods.firstOrNull {
                it.name == "getContentFormats" && it.parameterCount == 0
            }?.invoke(source)
        }.onFailure { error ->
            logcat(LogPriority.WARN, error) { "Home format hook failed on ${source.javaClass.simpleName}" }
        }.getOrNull() ?: runCatching {
            source.javaClass.getField("contentFormats").get(source)
        }.getOrNull() ?: return emptySet()

        return when (values) {
            is Collection<*> -> values.mapNotNull { it?.toString() }.toSet()
            is Array<*> -> values.mapNotNull { it?.toString() }.toSet()
            is String -> values.split(',', ';').map { it.trim() }.filter { it.isNotBlank() }.toSet()
            else -> emptySet()
        }
    }

    private fun parse(raw: String): ContentFormat? = when {
        raw.equals("MOVIE", ignoreCase = true) ||
            raw.contains("pelicula", ignoreCase = true) ||
            raw.contains("movie", ignoreCase = true) -> ContentFormat.MOVIE

        raw.equals("TV", ignoreCase = true) ||
            raw.equals("TV_SHORT", ignoreCase = true) ||
            raw.equals("ONA", ignoreCase = true) ||
            raw.contains("serie", ignoreCase = true) -> ContentFormat.SERIES

        raw.contains("webtoon", ignoreCase = true) -> ContentFormat.WEBTOON
        raw.contains("webnovel", ignoreCase = true) -> ContentFormat.WEBNOVEL
        raw.contains("light novel", ignoreCase = true) ||
            raw.equals("LIGHT_NOVEL", ignoreCase = true) -> ContentFormat.LIGHT_NOVEL

        raw.equals("MANHWA", ignoreCase = true) -> ContentFormat.MANHWA
        raw.equals("COMIC", ignoreCase = true) -> ContentFormat.COMIC
        raw.equals("MANGA", ignoreCase = true) -> ContentFormat.MANGA
        raw.equals("ANIME", ignoreCase = true) -> ContentFormat.ANIME
        else -> null
    }
}
