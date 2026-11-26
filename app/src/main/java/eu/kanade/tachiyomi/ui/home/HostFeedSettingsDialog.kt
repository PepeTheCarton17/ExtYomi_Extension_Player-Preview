package eu.kanade.tachiyomi.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.domain.ui.model.HomeFeedSort
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * The new Home's settings: the three tabs used by the anime filter (Filtrar / Ordenar / Apariencia)
 * adapted to Ajustes / Filtrar / Ordenar. The old "Only titles in this source" switch is replaced by
 * three independent source-priority screens opened from the Ajustes tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HostFeedSettingsDialog(
    state: StandaloneFeedScreenModel.State,
    onDismiss: () -> Unit,
    onToggleFeatured: () -> Unit,
    onToggleContinue: () -> Unit,
    onToggleRecommended: () -> Unit,
    onTogglePopular: () -> Unit,
    onToggleAutoScrollHero: () -> Unit,
    onSetItemsPerSection: (Int) -> Unit,
    onBlockedCategoriesChanged: (Set<String>) -> Unit,
    onCategoryOrderChanged: (List<String>) -> Unit,
    onSortChanged: (HomeFeedSort) -> Unit,
    onSourcesChanged: () -> Unit,
) {
    val navigator = LocalNavigator.currentOrThrow
    val uiPreferences = remember { Injekt.get<UiPreferences>() }
    var selectedTab by remember { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.standalone_feed_settings)) },
        text = {
            Column {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text(stringResource(MR.strings.home_feed_tab_adjustments)) },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text(stringResource(MR.strings.home_feed_tab_filter)) },
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text(stringResource(MR.strings.home_feed_tab_order)) },
                    )
                }
                when (selectedTab) {
                    0 -> Column(modifier = Modifier.padding(top = 8.dp)) {
                        HostSwitchRow(
                            label = stringResource(MR.strings.standalone_feed_show_featured),
                            checked = state.showFeatured,
                            onCheckedChange = onToggleFeatured,
                        )
                        HostSwitchRow(
                            label = stringResource(MR.strings.standalone_feed_show_continue),
                            checked = state.showContinue,
                            onCheckedChange = onToggleContinue,
                        )
                        HostSwitchRow(
                            label = stringResource(MR.strings.standalone_feed_show_recommended),
                            checked = state.showRecommended,
                            onCheckedChange = onToggleRecommended,
                        )
                        HostSwitchRow(
                            label = stringResource(MR.strings.standalone_feed_show_popular),
                            checked = state.showPopular,
                            onCheckedChange = onTogglePopular,
                        )
                        HostSwitchRow(
                            label = stringResource(MR.strings.standalone_feed_auto_scroll),
                            checked = state.autoScrollHero,
                            onCheckedChange = onToggleAutoScrollHero,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(
                                    MR.strings.standalone_feed_items_per_section,
                                    state.itemsPerSection,
                                ),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = { onSetItemsPerSection(state.itemsPerSection - 1) }) {
                                Text("−")
                            }
                            TextButton(onClick = { onSetItemsPerSection(state.itemsPerSection + 1) }) {
                                Text("+")
                            }
                        }
                        TextButton(
                            onClick = {
                                onDismiss()
                                navigator.push(
                                    HomeFeedSourcePriorityScreen(StandaloneFeedScreenModel.KIND_ANIME),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(MR.strings.home_feed_sources_anime))
                        }
                        TextButton(
                            onClick = {
                                onDismiss()
                                navigator.push(
                                    HomeFeedSourcePriorityScreen(StandaloneFeedScreenModel.KIND_MANGA),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(MR.strings.home_feed_sources_manga))
                        }
                        TextButton(
                            onClick = {
                                onDismiss()
                                navigator.push(
                                    HomeFeedSourcePriorityScreen(StandaloneFeedScreenModel.KIND_NOVEL),
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(MR.strings.home_feed_sources_novel))
                        }
                        TextButton(onClick = onSourcesChanged) {
                            Text(stringResource(MR.strings.action_webview_refresh))
                        }
                    }
                    1 -> FeedFilterTab(
                        state = state,
                        uiPreferences = uiPreferences,
                        onBlockedCategoriesChanged = onBlockedCategoriesChanged,
                        onCategoryOrderChanged = onCategoryOrderChanged,
                    )
                    2 -> Column(modifier = Modifier.padding(top = 8.dp)) {
                        HomeFeedSort.entries.forEach { mode ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = state.sortMode == mode,
                                    onClick = { onSortChanged(mode) },
                                )
                                Text(
                                    text = when (mode) {
                                        HomeFeedSort.NONE -> stringResource(MR.strings.home_feed_sort_none)
                                        HomeFeedSort.ALPHABETICAL -> stringResource(
                                            MR.strings.home_feed_sort_alphabetical,
                                        )
                                        HomeFeedSort.EPISODE_COUNT -> stringResource(MR.strings.home_feed_sort_episodes)
                                        HomeFeedSort.AIRING_DATE -> stringResource(MR.strings.home_feed_sort_airing)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
    )
}

@Composable
private fun HostSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Switch(checked = checked, onCheckedChange = { onCheckedChange() })
    }
}

@Composable
private fun FeedFilterTab(
    state: StandaloneFeedScreenModel.State,
    uiPreferences: UiPreferences,
    onBlockedCategoriesChanged: (Set<String>) -> Unit,
    onCategoryOrderChanged: (List<String>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val blocked = remember { uiPreferences.homeFeedBlockedCategories().get().toMutableSet() }
    val savedOrder = remember {
        uiPreferences.homeFeedCategoryOrder().get()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }
    val allCategories = remember(state.availableCategories, savedOrder, blocked) {
        (
            state.availableCategories + savedOrder + blocked +
                StandaloneFeedScreenModel.DEFAULT_HOST_CATEGORIES
            ).distinctBy { it.lowercase() }
    }
    val local = remember(allCategories) { allCategories.toMutableStateList() }
    val listState = rememberLazyListState()
    var dirty by remember { mutableStateOf(false) }
    val reorderState = rememberReorderableLazyListState(
        listState,
        PaddingValues(8.dp),
    ) { from, to ->
        local.add(to.index, local.removeAt(from.index))
        dirty = true
    }

    LaunchedEffect(reorderState.isAnyItemDragging) {
        if (!reorderState.isAnyItemDragging && dirty) {
            onCategoryOrderChanged(local.toList())
            dirty = false
        }
    }

    Column {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(MR.strings.home_feed_category_search)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(top = 8.dp),
            contentPadding = PaddingValues(8.dp),
        ) {
            items(items = local, key = { it }) { category ->
                if (!category.contains(query, ignoreCase = true)) return@items
                val isBlocked = category in blocked
                ReorderableItem(reorderState, key = category) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DragHandle,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .draggableHandle(),
                        )
                        Text(
                            text = category,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (isBlocked) {
                            Text(
                                text = stringResource(MR.strings.home_feed_category_blocked),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Checkbox(
                            checked = !isBlocked,
                            onCheckedChange = { enabled ->
                                if (enabled) blocked.remove(category) else blocked.add(category)
                                onBlockedCategoriesChanged(blocked.toSet())
                            },
                        )
                    }
                }
            }
        }
    }
}
