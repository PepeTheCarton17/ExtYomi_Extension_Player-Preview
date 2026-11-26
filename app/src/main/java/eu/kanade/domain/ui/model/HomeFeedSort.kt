package eu.kanade.domain.ui.model

/**
 * How the titles of the new Home are ordered inside each row.
 *
 * [NONE] keeps the order returned by the source/API and is the default, so behaviour is unchanged
 * until the user picks something else. Source-backed cards do not always expose episode counts or
 * airing dates; those entries are kept at the end when the selected order needs metadata they lack.
 */
enum class HomeFeedSort {
    NONE,
    ALPHABETICAL,
    EPISODE_COUNT,
    AIRING_DATE,
}
