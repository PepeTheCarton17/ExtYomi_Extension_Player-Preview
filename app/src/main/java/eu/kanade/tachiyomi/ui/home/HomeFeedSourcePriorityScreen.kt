package eu.kanade.tachiyomi.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.source.interactor.ToggleLanguage
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.reader.loader.NovelSourceCompat
import eu.kanade.tachiyomi.util.system.LocaleHelper
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.source.manga.service.MangaSourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * One of the three independent source blocks of the new Home. Language variants of the same
 * extension are shown as one reorderable group; each variant keeps its own checkbox and source id.
 */
class HomeFeedSourcePriorityScreen(private val kind: String) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val uiPreferences = remember { Injekt.get<UiPreferences>() }
        val sourcePreferences = remember { Injekt.get<SourcePreferences>() }
        val toggleLanguage = remember { Injekt.get<ToggleLanguage>() }
        val animeManager = remember { Injekt.get<AnimeSourceManager>() }
        val mangaManager = remember { Injekt.get<MangaSourceManager>() }

        // The same preference used by Extensions. Changing it here adds/removes language variants.
        val enabledLanguages by remember {
            sourcePreferences.enabledLanguages().changes()
        }.collectAsState(initial = sourcePreferences.enabledLanguages().get())

        val allEntries = remember(kind) {
            val savedOrder = uiPreferences.homeFeedSourceOrder(kind).get()
                .split(',')
                .mapNotNull { it.trim().toLongOrNull() }
            val entries = if (kind == StandaloneFeedScreenModel.KIND_ANIME) {
                animeManager.getCatalogueSources().map { source ->
                    HomeSourceEntry(
                        id = source.id,
                        name = source.name,
                        lang = source.lang,
                        groupKey = homeFeedSourceGroupKey(
                            animeManager.getExtensionPackage(source.id),
                            source.name,
                        ),
                    )
                }
            } else {
                mangaManager.getCatalogueSources()
                    .filter {
                        NovelSourceCompat.isNovelSource(it) ==
                            (kind == StandaloneFeedScreenModel.KIND_NOVEL)
                    }
                    .map { source ->
                        HomeSourceEntry(
                            id = source.id,
                            name = source.name,
                            lang = source.lang,
                            groupKey = homeFeedSourceGroupKey(
                                mangaManager.getExtensionPackage(source.id),
                                source.name,
                            ),
                        )
                    }
            }
            entries.sortedBy { entry ->
                val index = savedOrder.indexOf(entry.id)
                if (index < 0) savedOrder.size + entries.indexOf(entry) else index
            }
        }

        val allGroups = remember(allEntries) {
            allEntries.groupBy { it.groupKey }
                .map { (key, entries) -> HomeSourceGroup(key, entries.first().name, entries) }
        }
        val local = remember(allGroups, enabledLanguages) {
            allGroups.mapNotNull { group ->
                val visible = group.entries.filter { it.lang in enabledLanguages }
                if (visible.isEmpty()) null else group.copy(entries = visible)
            }.toMutableStateList()
        }
        var disabled by remember(kind) {
            mutableStateOf(uiPreferences.homeFeedDisabledSources(kind).get())
        }
        var languageDialogKey by remember { mutableStateOf<String?>(null) }
        val dialogGroup = remember(languageDialogKey, allGroups) {
            languageDialogKey?.let { key -> allGroups.firstOrNull { it.key == key } }
        }

        fun save() {
            uiPreferences.homeFeedSourceOrder(kind).set(
                local.flatMap { it.entries }.joinToString(",") { it.id.toString() },
            )
            uiPreferences.homeFeedDisabledSources(kind).set(disabled)
        }

        val listState = rememberLazyListState()
        var dirty by remember { mutableStateOf(false) }
        val reorderState = rememberReorderableLazyListState(
            listState,
            PaddingValues(MaterialTheme.padding.medium),
        ) { from, to ->
            local.add(to.index, local.removeAt(from.index))
            dirty = true
        }

        LaunchedEffect(reorderState.isAnyItemDragging) {
            if (!reorderState.isAnyItemDragging && dirty) {
                save()
                dirty = false
            }
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = when (kind) {
                        StandaloneFeedScreenModel.KIND_ANIME -> stringResource(MR.strings.home_feed_sources_anime)
                        StandaloneFeedScreenModel.KIND_MANGA -> stringResource(MR.strings.home_feed_sources_manga)
                        else -> stringResource(MR.strings.home_feed_sources_novel)
                    },
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .padding(paddingValues)
                    .fillMaxSize(),
            ) {
                Text(
                    text = stringResource(MR.strings.home_feed_source_priority_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = MaterialTheme.padding.medium,
                        vertical = MaterialTheme.padding.small,
                    ),
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(MaterialTheme.padding.medium),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                ) {
                    items(items = local, key = { it.key }) { group ->
                        val groupEnabled = group.entries.any { it.id.toString() !in disabled }
                        ReorderableItem(reorderState, key = group.key) {
                            ElevatedCard(modifier = Modifier.animateItem()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            start = MaterialTheme.padding.small,
                                            end = MaterialTheme.padding.medium,
                                            top = MaterialTheme.padding.small,
                                            bottom = MaterialTheme.padding.small,
                                        ),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Outlined.DragHandle,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .padding(MaterialTheme.padding.small)
                                                .draggableHandle(),
                                        )
                                        Text(
                                            text = group.label,
                                            modifier = Modifier
                                                .weight(1f)
                                                .alpha(if (groupEnabled) 1f else 0.4f),
                                            style = MaterialTheme.typography.titleSmall,
                                        )
                                        TextButton(onClick = { languageDialogKey = group.key }) {
                                            Text(stringResource(MR.strings.home_feed_source_languages))
                                        }
                                    }
                                    group.entries.forEach { entry ->
                                        val enabled = entry.id.toString() !in disabled
                                        val languageLabel = LocaleHelper.getSourceDisplayName(
                                            entry.lang,
                                            context,
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = if (entry.name.equals(group.label, ignoreCase = true)) {
                                                    languageLabel
                                                } else {
                                                    "${entry.name} · $languageLabel"
                                                },
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .alpha(if (enabled) 1f else 0.4f),
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                            Checkbox(
                                                checked = enabled,
                                                onCheckedChange = { checked ->
                                                    disabled = if (checked) {
                                                        disabled - entry.id.toString()
                                                    } else {
                                                        disabled + entry.id.toString()
                                                    }
                                                    save()
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        dialogGroup?.let { group ->
            val languages = group.entries.map { it.lang }.distinct()
                .sortedWith(LocaleHelper.comparator)
            AlertDialog(
                onDismissRequest = { languageDialogKey = null },
                title = { Text(stringResource(MR.strings.home_feed_source_languages)) },
                text = {
                    LazyColumn {
                        items(items = languages, key = { it }) { lang ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = LocaleHelper.getSourceDisplayName(lang, context),
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Checkbox(
                                    checked = lang in enabledLanguages,
                                    onCheckedChange = { toggleLanguage.await(lang) },
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { languageDialogKey = null }) {
                        Text(stringResource(MR.strings.action_ok))
                    }
                },
            )
        }
    }
}

private data class HomeSourceEntry(
    val id: Long,
    val name: String,
    val lang: String,
    val groupKey: String,
)

private data class HomeSourceGroup(
    val key: String,
    val label: String,
    val entries: List<HomeSourceEntry>,
)

/** Groups packages such as "...extension.es.slug" and "...extension.en.slug" into one extension. */
private fun homeFeedSourceGroupKey(pkgName: String?, sourceName: String): String {
    if (pkgName.isNullOrBlank()) return "name:${sourceName.lowercase()}"
    val marker = "extension."
    val index = pkgName.lastIndexOf(marker)
    if (index < 0) return "pkg:${pkgName.lowercase()}"
    val prefix = pkgName.substring(0, index + marker.length)
    val slug = pkgName.substring(index + marker.length).substringAfter('.', "")
    return if (slug.isBlank()) {
        "pkg:${pkgName.lowercase()}"
    } else {
        "pkg:${(prefix + slug).lowercase()}"
    }
}
