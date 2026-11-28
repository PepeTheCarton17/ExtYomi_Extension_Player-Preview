package eu.kanade.tachiyomi.util.devicesync

import kotlinx.serialization.encodeToString
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.MessageDigest

/**
 * Library view preferences are synced as part of normal sync. Device-specific preferences (decoder,
 * viewer, internal player and advanced settings) are opt-in per device and off by default.
 */
object DeviceSyncSettings {

    private const val PREFIX = "setting:"

    /**
     * Every library view/filter preference, always synced like the rest of the library. Keeping
     * this list in sync with `LibraryPreferences` matters: a missing key makes the other device
     * silently fall back to its own sort order, hidden categories or filter state.
     */
    private val KEYS = setOf(
        "pref_display_mode_library",
        "library_sorting_mode",
        "animelib_sorting_mode",
        "display_category_tabs",
        "display_category_tabs_novel",
        "display_number_of_items",
        "display_unread_badge",
        "display_local_badge",
        "display_download_badge",
        "display_language_badge",
        "display_continue_reading_button",
        "library_show_updates_count",
        "categorized_display",
        "hidden_categories",
        "auto_hide_anime_categories",
        "auto_hide_manga_categories",
        "pref_library_columns_portrait_key",
        "pref_library_columns_landscape_key",
        "pref_animelib_columns_portrait_key",
        "pref_animelib_columns_landscape_key",
        "pref_novellib_columns_portrait_key",
        "pref_novellib_columns_landscape_key",
        "library_random_anime_sort_seed",
        "library_random_manga_sort_seed",
        "library_group_mode_anime",
        "library_group_mode_manga",
        "library_group_mode_novel",
        "library_pinned_anime_ids",
        "library_pinned_manga_ids",
        "library_pinned_novel_ids",
        "library_series_anime",
        "library_series_manga",
        "library_series_novel",
        "library_series_covers_anime",
        "library_series_covers_manga",
        "library_series_covers_novel",
        "pref_filter_library_unread_v2",
        "pref_filter_animelib_unread_v2",
        "pref_filter_novellib_unread_v2",
        "pref_filter_library_bookmarked_v2",
        "pref_filter_animelib_bookmarked_v2",
        "pref_filter_novellib_bookmarked_v2",
        "pref_filter_library_downloaded_v2",
        "pref_filter_animelib_downloaded_v2",
        "pref_filter_novellib_downloaded_v2",
        "pref_filter_library_started_v2",
        "pref_filter_animelib_started_v2",
        "pref_filter_novellib_started_v2",
        "pref_filter_library_completed_v2",
        "pref_filter_animelib_completed_v2",
        "pref_filter_novellib_completed_v2",
        "pref_filter_library_genres_include",
        "pref_filter_library_genres_exclude",
        "pref_filter_animelib_genres_include",
        "pref_filter_animelib_genres_exclude",
        "pref_filter_novellib_genres_include",
        "pref_filter_novellib_genres_exclude",
        "pref_filter_tags_expanded",
        "pref_filter_library_interval_custom",
        "pref_filter_novellib_interval_custom",
    )

    /** True when the key is shared, requiring explicit consent for device-specific ones. */
    fun canSync(key: String, includeDeviceSettings: Boolean): Boolean =
        key in KEYS || (includeDeviceSettings && key in DeviceSyncDeviceSettings.KEYS)

    private val preferenceStore: PreferenceStore get() = Injekt.get()

    /** Returns only the changed settings and the new hash map. */
    fun collect(
        previous: Map<String, String>,
        now: Long,
        includeDeviceSettings: Boolean,
    ): Pair<List<DeviceSyncFilter>, Map<String, String>> {
        val all = preferenceStore.getAll()
        val hashes = previous.toMutableMap()
        val filters = mutableListOf<DeviceSyncFilter>()
        val keys = if (includeDeviceSettings) KEYS + DeviceSyncDeviceSettings.KEYS else KEYS

        keys.forEach { key ->
            val encoded = encode(all[key]) ?: return@forEach
            val fullKey = PREFIX + key
            val hash = fingerprint(encoded)
            if (hashes[fullKey] != hash) {
                filters += DeviceSyncFilter(
                    key = fullKey,
                    value = deviceSyncJson.encodeToString(encoded),
                    updatedAt = now,
                )
                hashes[fullKey] = hash
            }
        }
        return filters to hashes
    }

    fun apply(filter: DeviceSyncFilter, includeDeviceSettings: Boolean) {
        val key = filter.key.removePrefix(PREFIX)
        if (filter.deleted || !canSync(key, includeDeviceSettings)) return
        val value = runCatching {
            deviceSyncJson.decodeFromString<DeviceSyncSettingValue>(filter.value)
        }.getOrNull() ?: return

        when (value.type) {
            "b" -> preferenceStore.getBoolean(key).set(value.value.toBoolean())
            "i" -> preferenceStore.getInt(key).set(value.value.toIntOrNull() ?: return)
            "l" -> preferenceStore.getLong(key).set(value.value.toLongOrNull() ?: return)
            "f" -> preferenceStore.getFloat(key).set(value.value.toFloatOrNull() ?: return)
            "s" -> preferenceStore.getString(key).set(value.value)
            "set" -> {
                val items = runCatching {
                    deviceSyncJson.decodeFromString<List<String>>(value.value)
                }.getOrNull() ?: return
                preferenceStore.getStringSet(key).set(items.toSet())
            }
        }
    }

    private fun encode(value: Any?): DeviceSyncSettingValue? = when (value) {
        is Boolean -> DeviceSyncSettingValue("b", value.toString())
        is Int -> DeviceSyncSettingValue("i", value.toString())
        is Long -> DeviceSyncSettingValue("l", value.toString())
        is Float -> DeviceSyncSettingValue("f", value.toString())
        is String -> DeviceSyncSettingValue("s", value)
        is Set<*> -> DeviceSyncSettingValue(
            "set",
            deviceSyncJson.encodeToString(value.map { it.toString() }),
        )
        else -> null
    }

    private fun fingerprint(value: DeviceSyncSettingValue): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(deviceSyncJson.encodeToString(value).toByteArray())
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
