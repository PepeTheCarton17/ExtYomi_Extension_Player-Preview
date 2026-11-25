package eu.kanade.domain.ui

import eu.kanade.domain.ui.model.AppIcon
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.domain.ui.model.HomeTabsMode
import eu.kanade.domain.ui.model.NavBarLabelMode
import eu.kanade.domain.ui.model.NavTab
import eu.kanade.domain.ui.model.StandaloneHomeMode
import eu.kanade.domain.ui.model.StartScreen
import eu.kanade.domain.ui.model.TabletUiMode
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.tachiyomi.util.system.DeviceUtil
import eu.kanade.tachiyomi.util.system.isDynamicColorAvailable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class UiPreferences(
    private val preferenceStore: PreferenceStore,
) {

    fun themeMode() = preferenceStore.getEnum("pref_theme_mode_key", ThemeMode.SYSTEM)

    fun appTheme() = preferenceStore.getEnum(
        "pref_app_theme",
        if (DeviceUtil.isDynamicColorAvailable) {
            AppTheme.MONET
        } else {
            AppTheme.DEFAULT
        },
    )

    fun themeDarkAmoled() = preferenceStore.getBoolean("pref_theme_dark_amoled_key", false)

    fun relativeTime() = preferenceStore.getBoolean("relative_time_v2", true)

    fun dateFormat() = preferenceStore.getString("app_date_format", "")

    fun tabletUiMode() = preferenceStore.getEnum("tablet_ui_mode", TabletUiMode.AUTOMATIC)

    fun startScreen() = preferenceStore.getEnum("start_screen", StartScreen.ANIME)

    // Cards shown per horizontal row/section in the new Home (host and standalone).
    fun newHomeItemsPerSection() = preferenceStore.getInt("pref_new_home_items_per_section", 10)

    fun bottomNavTabs() = preferenceStore.getStringSet("bottom_nav_tabs", NavTab.DEFAULT)

    // Which Manga/Anime sub-tabs are shown (and the default) in Updates/History/Browse.
    fun homeTabsMode() = preferenceStore.getEnum("home_tabs_mode", HomeTabsMode.ANIME_FIRST)

    // Scope of a generated standalone app: the single extension it is built around.
    fun standaloneSourceId() = preferenceStore.getLong("standalone_source_id", -1L)

    fun standaloneKind() = preferenceStore.getString("standalone_kind", "")

    fun standaloneExtensionPkg() = preferenceStore.getString("standalone_extension_pkg", "")

    fun standaloneInstanceId() = preferenceStore.getString("standalone_instance_id", "")

    fun standaloneSyncAuthority() = preferenceStore.getString("standalone_sync_authority", "")

    fun standaloneDownloadsUri() = preferenceStore.getString("standalone_downloads_uri", "")

    // Two-way sync runs by itself; Home > Filtrar can switch it off in either app.
    fun standaloneAutoSync() = preferenceStore.getBoolean("standalone_auto_sync", true)

    // Which screen a generated standalone app opens on: its source feed or its library.
    fun standaloneHomeMode() = preferenceStore.getEnum("standalone_home_mode", StandaloneHomeMode.FEED)

    // SAF tree where generated standalone APKs are written.
    fun standaloneOutputUri() = preferenceStore.getString("standalone_output_uri", "")

    // Registry of generated APKs, so they can be installed/updated/opened from the same tab.
    fun standaloneApps() = preferenceStore.getObject(
        "standalone_apps",
        emptyList<GeneratedStandaloneApp>(),
        serializer = { json.encodeToString(generatedAppsSerializer, it) },
        deserializer = { json.decodeFromString(generatedAppsSerializer, it) },
    )

    private val generatedAppsSerializer = ListSerializer(GeneratedStandaloneApp.serializer())
    private val json = Json { ignoreUnknownKeys = true }

    // Order of the bottom nav tabs, as a comma-separated list of NavTab prefKeys. Empty = enum order.
    fun bottomNavOrder() = preferenceStore.getString("bottom_nav_order", "")

    fun bottomNavFloating() = preferenceStore.getBoolean("pref_bottom_nav_floating", true)

    // How the nav bar shows tab labels: hidden, beside the selected icon (pill), or below every icon.
    fun bottomNavLabelMode() = preferenceStore.getEnum("pref_bottom_nav_label_mode", NavBarLabelMode.HIDDEN)

    // Opacity of the floating nav bar, as a percentage (0 = fully translucent, 100 = solid).
    fun bottomNavFloatingAlpha() = preferenceStore.getInt("pref_bottom_nav_floating_alpha", 81)

    // Backdrop blur radius of the floating nav bar, in dp (0 = off / no blur).
    fun bottomNavFloatingBlur() = preferenceStore.getInt("pref_bottom_nav_floating_blur", 0)

    // Height of the floating nav bar, as a percentage of the default (100 = default).
    fun bottomNavFloatingHeight() = preferenceStore.getInt("pref_bottom_nav_floating_height", 100)

    // Size of the navigation bar icons, as a percentage (100 = default).
    fun bottomNavIconScale() = preferenceStore.getInt("pref_bottom_nav_icon_scale", 100)

    fun showDownloadSize() = preferenceStore.getBoolean("pref_show_download_size", false)

    fun appIcon() = preferenceStore.getEnum("app_icon", AppIcon.Default)

    // Blurred cover backdrop on the manga/anime entry screen (values as percentages / dp).
    fun entryBackdropOpacity() = preferenceStore.getInt("pref_entry_backdrop_opacity", 20)

    fun entryBackdropBlur() = preferenceStore.getInt("pref_entry_backdrop_blur", 4)

    fun entryBackdropDim() = preferenceStore.getInt("pref_entry_backdrop_dim", 0)

    companion object {
        fun dateFormat(format: String): DateTimeFormatter = when (format) {
            "" -> DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT)
            else -> DateTimeFormatter.ofPattern(format, Locale.getDefault())
        }
    }
}
