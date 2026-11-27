package eu.kanade.tachiyomi.util.devicesync

import kotlinx.serialization.encodeToString
import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.MessageDigest

/**
 * Optional sync for a deliberately small whitelist of library preferences. Everything else stays
 * local to each device. The toggle itself is never synced.
 */
object DeviceSyncSettings {

    private const val PREFIX = "setting:"

    /** Display/filter preferences that are useful to keep identical across devices. */
    private val KEYS = setOf(
        "pref_display_mode_library",
        "library_sorting_mode",
        "animelib_sorting_mode",
        "display_category_tabs",
        "display_category_tabs_novel",
        "display_number_of_items",
        "display_unread_badge",
        "display_download_badge",
        "display_language_badge",
        "library_show_updates_count",
        "pref_library_columns_portrait_key",
        "pref_library_columns_landscape_key",
        "pref_animelib_columns_portrait_key",
        "pref_animelib_columns_landscape_key",
        "pref_filter_library_unread_v2",
        "pref_filter_animelib_unread_v2",
        "pref_filter_library_bookmarked_v2",
        "pref_filter_animelib_bookmarked_v2",
    )

    private val preferenceStore: PreferenceStore get() = Injekt.get()

    /** Returns only the changed settings and the new hash map. */
    fun collect(
        previous: Map<String, String>,
        now: Long,
    ): Pair<List<DeviceSyncFilter>, Map<String, String>> {
        val all = preferenceStore.getAll()
        val hashes = previous.toMutableMap()
        val filters = mutableListOf<DeviceSyncFilter>()

        KEYS.forEach { key ->
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

    fun apply(filter: DeviceSyncFilter) {
        val key = filter.key.removePrefix(PREFIX)
        if (filter.deleted || key !in KEYS) return
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
