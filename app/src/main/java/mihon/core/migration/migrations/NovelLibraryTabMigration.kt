package mihon.core.migration.migrations

import eu.kanade.domain.ui.model.NavTab
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import tachiyomi.core.common.preference.PreferenceStore

/**
 * Novels used to be enabled only from the More tab. Put the library beside Anime and Manga for
 * existing installs too, keeping any custom tab order intact (Novel goes right after Manga).
 */
class NovelLibraryTabMigration : Migration {
    override val version = 16f

    override suspend fun invoke(migrationContext: MigrationContext): Boolean {
        val preferenceStore = migrationContext.get<PreferenceStore>() ?: return false

        val tabsPreference = preferenceStore.getStringSet("bottom_nav_tabs", NavTab.DEFAULT)
        val orderPreference = preferenceStore.getString("bottom_nav_order", "")
        val (enabledTabs, tabOrder) = enableNovelTab(tabsPreference.get(), orderPreference.get())

        if (enabledTabs != tabsPreference.get()) {
            tabsPreference.set(enabledTabs)
        }
        if (tabOrder != orderPreference.get()) {
            orderPreference.set(tabOrder)
        }
        return true
    }
}

/** Pure helper so the migration can be unit tested without Android preferences. */
internal fun enableNovelTab(
    enabled: Set<String>,
    order: String,
): Pair<Set<String>, String> {
    val updatedTabs = if (NavTab.Novel.prefKey in enabled) {
        enabled
    } else {
        enabled + NavTab.Novel.prefKey
    }

    val keys = order.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
    if (keys.isNotEmpty() && NavTab.Novel.prefKey !in keys) {
        val mangaIndex = keys.indexOf(NavTab.Manga.prefKey)
        keys.add(if (mangaIndex >= 0) mangaIndex + 1 else keys.size, NavTab.Novel.prefKey)
    }
    return updatedTabs to keys.joinToString(",")
}
