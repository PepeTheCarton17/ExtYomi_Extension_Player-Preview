package eu.kanade.domain.ui.model

/**
 * Optional content-kind metadata used by the new Home to build format rows.
 *
 * Extensions do not have to implement it: [HomeFeedFormatMetadata] falls back to genre/filter names
 * and to AniList's own format. It exists so extension authors can opt in with an accurate value
 * instead of relying on title heuristics.
 */
enum class ContentFormat {
    ANIME,
    SERIES,
    MOVIE,
    MANGA,
    MANHWA,
    WEBTOON,
    COMIC,
    LIGHT_NOVEL,
    WEBNOVEL,
}
