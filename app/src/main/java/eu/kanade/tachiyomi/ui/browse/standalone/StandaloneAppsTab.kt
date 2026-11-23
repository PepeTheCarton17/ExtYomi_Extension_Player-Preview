package eu.kanade.tachiyomi.ui.browse.standalone

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import com.hippo.unifile.UniFile
import eu.kanade.domain.ui.StandaloneScope
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.components.TabContent
import eu.kanade.presentation.more.settings.widget.BasePreferenceWidget
import eu.kanade.tachiyomi.util.system.LocaleHelper
import tachiyomi.core.common.storage.displayablePath
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.secondaryItemAlpha
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.presentation.core.util.collectAsState as collectPreferenceAsState

private sealed interface AppsRow {
    val key: String

    data class Header(override val key: String, val title: String) : AppsRow

    data class App(val item: StandaloneExtensionItem) : AppsRow {
        override val key: String get() = "app-${item.kind}-${item.sourceId}"
    }
}

@Composable
fun Screen.standaloneAppsTab(): TabContent {
    val context = LocalContext.current
    val screenModel = rememberScreenModel { StandaloneAppsScreenModel() }
    val state by screenModel.state.collectAsState()
    val uiPreferences = remember { Injekt.get<UiPreferences>() }
    val configuredOutputUri by uiPreferences.standaloneOutputUri().collectPreferenceAsState()

    var selectedKind by remember { mutableStateOf<String?>(null) }
    var pinnedOnly by remember { mutableStateOf(false) }

    // ExtYomi already has a storage root configured at install time, so just use its
    // "standalone" subdirectory. The picker is only for changing that destination.
    val defaultOutputUri = remember {
        Injekt.get<StorageManager>()
            .getStandaloneAppsDirectory()
            ?.uri
            ?.toString()
            .orEmpty()
    }
    val outputUri = configuredOutputUri.ifBlank { defaultOutputUri }
    val outputPath = remember(outputUri) {
        UniFile.fromUri(context, outputUri.toUri())?.displayablePath.orEmpty()
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: SecurityException) {
                // Some OEMs do not implement persistable URI grants; keep the raw URI anyway.
            }
            uiPreferences.standaloneOutputUri().set(uri.toString())
        }
    }

    val kindLabels = listOf(
        StandaloneScope.KIND_ANIME to stringResource(AYMR.strings.label_anime),
        StandaloneScope.KIND_MANGA to stringResource(AYMR.strings.label_manga),
        StandaloneScope.KIND_NOVEL to stringResource(AYMR.strings.label_novel),
    )
    val pinnedLabel = stringResource(MR.strings.pinned_sources)
    val sourcesLabel = stringResource(MR.strings.label_sources)

    // Languages come from the unfiltered list: turning a chip off must not remove the chip itself.
    val languages = remember(state.items) {
        state.items
            .map { it.lang }
            .filter { it.isNotBlank() && it != "all" }
            .distinct()
            .sortedWith(LocaleHelper.comparator)
    }

    val visibleItems = remember(state.items, state.enabledLanguages, selectedKind, pinnedOnly) {
        state.items.filter { item ->
            (selectedKind == null || item.kind == selectedKind) &&
                (!pinnedOnly || item.isPinned) &&
                (item.lang.isBlank() || item.lang == "all" || item.lang in state.enabledLanguages)
        }
    }

    val rows = remember(visibleItems, kindLabels, pinnedLabel, context) {
        buildList<AppsRow> {
            kindLabels.forEach { (kind, label) ->
                val forKind = visibleItems.filter { it.kind == kind }
                if (forKind.isEmpty()) return@forEach
                add(AppsRow.Header("kind-$kind", label))

                val pinned = forKind.filter { it.isPinned }
                if (pinned.isNotEmpty()) {
                    add(AppsRow.Header("pinned-$kind", pinnedLabel))
                    pinned.forEach { add(AppsRow.App(it)) }
                }

                forKind.filterNot { it.isPinned }
                    .groupBy { it.lang.ifBlank { "other" } }
                    .toSortedMap { a, b ->
                        when {
                            a == "other" && b != "other" -> 1
                            b == "other" && a != "other" -> -1
                            else -> LocaleHelper.comparator(a, b)
                        }
                    }
                    .forEach { (lang, apps) ->
                        add(
                            AppsRow.Header(
                                "lang-$kind-$lang",
                                LocaleHelper.getSourceDisplayName(lang, context),
                            ),
                        )
                        apps.forEach { add(AppsRow.App(it)) }
                    }
            }
        }
    }

    return TabContent(
        titleRes = MR.strings.label_standalone_apps,
        content = { contentPadding, _ ->
            ScrollbarLazyColumn(contentPadding = contentPadding) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        Text(
                            text = stringResource(MR.strings.standalone_apps_summary),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { folderPicker.launch(null) }) {
                                Text(stringResource(MR.strings.standalone_change_folder))
                            }
                            if (outputPath.isNotBlank()) {
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = stringResource(
                                        MR.strings.standalone_output_folder_value,
                                        outputPath,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = selectedKind == null,
                                onClick = { selectedKind = null },
                                label = { Text(stringResource(MR.strings.all)) },
                            )
                            kindLabels.forEach { (kind, label) ->
                                FilterChip(
                                    selected = selectedKind == kind,
                                    onClick = { selectedKind = kind },
                                    label = { Text(label) },
                                )
                            }
                            FilterChip(
                                selected = pinnedOnly,
                                onClick = { pinnedOnly = !pinnedOnly },
                                label = { Text(pinnedLabel) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.PushPin,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                            )
                        }

                        if (languages.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                languages.forEach { lang ->
                                    FilterChip(
                                        selected = lang in state.enabledLanguages,
                                        onClick = { screenModel.toggleLanguage(lang) },
                                        label = {
                                            Text(LocaleHelper.getSourceDisplayName(lang, context))
                                        },
                                    )
                                }
                            }
                        }

                        val status = state.status
                        when (status) {
                            is StandaloneAppsScreenModel.Status.Generating -> {
                                Spacer(Modifier.height(12.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.height(18.dp).width(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        stringResource(
                                            MR.strings.standalone_generating,
                                            status.extensionName,
                                        ),
                                    )
                                }
                            }
                            is StandaloneAppsScreenModel.Status.Success -> {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    stringResource(MR.strings.standalone_generated, status.fileName),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            StandaloneAppsScreenModel.Status.Error -> {
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    stringResource(MR.strings.standalone_error),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            null -> Unit
                        }
                    }
                }

                if (!state.isLoading && rows.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(MR.strings.standalone_no_extensions),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                items(items = rows, key = { it.key }) { row ->
                    when (row) {
                        is AppsRow.Header -> Text(
                            text = row.title,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        is AppsRow.App -> {
                            val item = row.item
                            val extensionIcon = item.icon
                            val isNovel = item.kind == StandaloneScope.KIND_NOVEL
                            BasePreferenceWidget(
                                modifier = Modifier.fillMaxWidth(),
                                title = item.name,
                                subcomponent = {
                                    Text(
                                        text = buildString {
                                            append(item.extensionVersionName)
                                            append(" • ")
                                            append(LocaleHelper.getSourceDisplayName(item.lang, context))
                                            append(" • ")
                                            append(item.sourceCount)
                                            append(' ')
                                            append(sourcesLabel)
                                        },
                                        modifier = Modifier
                                            .padding(horizontal = 16.dp)
                                            .secondaryItemAlpha(),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                    )
                                },
                                icon = {
                                    val bitmap = remember(extensionIcon) {
                                        extensionIcon?.toBitmap()?.asImageBitmap()
                                    }
                                    if (bitmap != null) {
                                        Image(
                                            bitmap = bitmap,
                                            contentDescription = null,
                                            modifier = Modifier.size(40.dp),
                                        )
                                    } else {
                                        Icon(
                                            imageVector = Icons.Outlined.GetApp,
                                            contentDescription = null,
                                        )
                                    }
                                },
                                widget = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(onClick = { screenModel.togglePin(item) }) {
                                            Icon(
                                                imageVector = if (item.isPinned) {
                                                    Icons.Filled.PushPin
                                                } else {
                                                    Icons.Outlined.PushPin
                                                },
                                                contentDescription = stringResource(
                                                    if (item.isPinned) {
                                                        MR.strings.action_unpin
                                                    } else {
                                                        MR.strings.action_pin
                                                    },
                                                ),
                                            )
                                        }
                                        IconButton(
                                            enabled = outputUri.isNotBlank() &&
                                                !isNovel &&
                                                state.status !is StandaloneAppsScreenModel.Status.Generating,
                                            onClick = {
                                                screenModel.generate(context, item, Uri.parse(outputUri))
                                            },
                                        ) {
                                            Icon(
                                                imageVector = Icons.Outlined.GetApp,
                                                contentDescription = stringResource(
                                                    if (isNovel) {
                                                        MR.strings.standalone_novel_not_supported
                                                    } else {
                                                        MR.strings.standalone_create_app
                                                    },
                                                ),
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        },
    )
}
