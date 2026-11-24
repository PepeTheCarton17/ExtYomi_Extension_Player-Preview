package eu.kanade.tachiyomi.ui.browse.standalone

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.hippo.unifile.UniFile
import eu.kanade.domain.ui.GeneratedStandaloneApp
import eu.kanade.domain.ui.StandaloneScope
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.TabContent
import eu.kanade.presentation.components.WarningBanner
import eu.kanade.presentation.more.settings.widget.BasePreferenceWidget
import eu.kanade.presentation.util.rememberRequestPackageInstallsPermissionState
import eu.kanade.tachiyomi.ui.browse.anime.source.AnimeSourcesFilterScreen
import eu.kanade.tachiyomi.ui.browse.anime.source.globalsearch.GlobalAnimeSearchScreen
import eu.kanade.tachiyomi.ui.browse.manga.source.MangaSourcesFilterScreen
import eu.kanade.tachiyomi.ui.browse.manga.source.globalsearch.GlobalMangaSearchScreen
import eu.kanade.tachiyomi.util.system.LocaleHelper
import eu.kanade.tachiyomi.util.system.launchRequestPackageInstallsPermission
import kotlinx.collections.immutable.persistentListOf
import tachiyomi.core.common.storage.displayablePath
import tachiyomi.domain.storage.service.StorageManager
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.theme.header
import tachiyomi.presentation.core.util.secondaryItemAlpha
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.presentation.core.util.collectAsState as collectPreferenceAsState

@Composable
fun Screen.standaloneAppsTab(): TabContent {
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    val screenModel = rememberScreenModel { StandaloneAppsScreenModel(context.applicationContext) }
    val state by screenModel.state.collectAsState()
    val uiPreferences = remember { Injekt.get<UiPreferences>() }
    val configuredOutputUri by uiPreferences.standaloneOutputUri().collectPreferenceAsState()
    val installGranted = rememberRequestPackageInstallsPermissionState(initialValue = true)

    val installLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        screenModel.state.value.pendingInstall?.let { app ->
            screenModel.onInstallFinished(app)
        }
    }

    // "Instalar", "Actualizar" and "Reinstalar" all end here: one prompt, then back to the list.
    val pendingInstall = state.pendingInstall
    LaunchedEffect(pendingInstall, installGranted) {
        val app = pendingInstall ?: return@LaunchedEffect
        if (!installGranted) {
            context.launchRequestPackageInstallsPermission()
            screenModel.cancelPendingInstall()
            return@LaunchedEffect
        }
        val intent = screenModel.prepareInstall(context, app)
        if (intent != null) {
            installLauncher.launch(intent)
        } else {
            screenModel.onInstallFinished(app)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) screenModel.refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
    val selectedKindIndex = kindLabels.indexOfFirst { it.first == state.kind }.coerceAtLeast(0)
    val selectedKind = kindLabels[selectedKindIndex].first
    // These labels are needed inside the non-composable LazyListScope builder below.
    val updatesHeader = stringResource(MR.strings.ext_updates_pending)
    val installedHeader = stringResource(MR.strings.ext_installed)
    val generatedHeader = stringResource(MR.strings.standalone_generated_apps)

    // One row per app, in exactly one section. Being installed always wins over a transient phase,
    // so an installed app can never leak into the "Instalar" section below.
    val rows = remember(state.installedApps, state.phaseByPackage) {
        state.installedApps.map { installed ->
            val section = when {
                installed.hasUpdate -> AppSection.UPDATES
                installed.installed -> AppSection.INSTALLED
                else -> AppSection.GENERATED
            }
            val phase = state.phaseByPackage[installed.app.stateKey]
            val progress = when (phase) {
                StandaloneAppsScreenModel.AppPhase.Generating -> RowProgress.GENERATING
                StandaloneAppsScreenModel.AppPhase.Updating -> RowProgress.UPDATING
                StandaloneAppsScreenModel.AppPhase.Installing -> RowProgress.INSTALLING
                is StandaloneAppsScreenModel.AppPhase.Generated,
                null,
                -> null
            }
            val generatedApp = (phase as? StandaloneAppsScreenModel.AppPhase.Generated)?.app
            AppRow(
                installed = installed,
                section = section,
                progress = progress,
                actions = when {
                    progress != null -> emptyList()
                    installed.hasUpdate -> listOf(RowAction.UPDATE)
                    installed.installed -> listOf(RowAction.OPEN, RowAction.REINSTALL)
                    else -> listOf(RowAction.INSTALL)
                },
                app = generatedApp ?: installed.app,
            )
        }
    }

    fun handleRowAction(row: AppRow, action: RowAction) {
        when (action) {
            RowAction.OPEN -> screenModel.open(context, row.app)
            RowAction.INSTALL -> {
                if (installGranted) {
                    screenModel.requestInstall(row.app)
                } else {
                    context.launchRequestPackageInstallsPermission()
                }
            }
            RowAction.UPDATE, RowAction.REINSTALL -> {
                if (installGranted) {
                    screenModel.update(context, row.app)
                } else {
                    context.launchRequestPackageInstallsPermission()
                }
            }
        }
    }

    return TabContent(
        titleRes = MR.strings.label_standalone_apps,
        badgeNumber = state.updateCount.takeIf { it > 0 },
        // Same actions as the Sources tabs: global search and the Sources filter screen.
        actions = persistentListOf(
            AppBar.Action(
                title = stringResource(MR.strings.action_global_search),
                icon = Icons.Outlined.TravelExplore,
                onClick = {
                    when (selectedKind) {
                        StandaloneScope.KIND_ANIME -> navigator.push(GlobalAnimeSearchScreen())
                        else -> navigator.push(
                            GlobalMangaSearchScreen(novelOnly = selectedKind == StandaloneScope.KIND_NOVEL),
                        )
                    }
                },
            ),
            AppBar.Action(
                title = stringResource(MR.strings.action_filter),
                icon = Icons.Outlined.FilterList,
                onClick = {
                    when (selectedKind) {
                        StandaloneScope.KIND_ANIME -> navigator.push(AnimeSourcesFilterScreen())
                        else -> navigator.push(
                            MangaSourcesFilterScreen(novelOnly = selectedKind == StandaloneScope.KIND_NOVEL),
                        )
                    }
                },
            ),
        ),
        content = { contentPadding, _ ->
            ScrollbarLazyColumn(contentPadding = contentPadding) {
                item {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        // The media type is only a subcategory of this section, not a set of
                        // custom filter chips. Novel apps stay visible but cannot be generated.
                        PrimaryScrollableTabRow(
                            selectedTabIndex = selectedKindIndex,
                            edgePadding = 0.dp,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            kindLabels.forEachIndexed { index, (kind, label) ->
                                Tab(
                                    selected = index == selectedKindIndex,
                                    onClick = { screenModel.setKind(kind) },
                                    text = { Text(label) },
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))
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

                        if (!installGranted) {
                            Spacer(Modifier.height(12.dp))
                            WarningBanner(
                                textRes = MR.strings.ext_permission_install_apps_warning,
                                modifier = Modifier.clickable {
                                    context.launchRequestPackageInstallsPermission()
                                },
                            )
                        }

                        if (state.error) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                stringResource(MR.strings.standalone_error),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }

                appRowsSection(
                    sectionKey = "updates",
                    header = updatesHeader,
                    rows = rows.filter { it.section == AppSection.UPDATES },
                    onAction = { row, action -> handleRowAction(row, action) },
                )
                appRowsSection(
                    sectionKey = "installed",
                    header = installedHeader,
                    rows = rows.filter { it.section == AppSection.INSTALLED },
                    onAction = { row, action -> handleRowAction(row, action) },
                )
                appRowsSection(
                    sectionKey = "generated",
                    header = generatedHeader,
                    rows = rows.filter { it.section == AppSection.GENERATED },
                    onAction = { row, action -> handleRowAction(row, action) },
                )

                itemsIndexed(
                    items = state.items,
                    contentType = { _, model ->
                        if (model is StandaloneSourceUiModel.Header) "header" else "item"
                    },
                    key = { index, model ->
                        when (model) {
                            is StandaloneSourceUiModel.Header -> "source-header-${model.language}"
                            is StandaloneSourceUiModel.Item ->
                                "source-${model.app.kind}-${model.app.sourceId}-${model.app.extensionPkg}-$index"
                        }
                    },
                ) { _, model ->
                    when (model) {
                        is StandaloneSourceUiModel.Header -> {
                            SectionHeader(
                                text = LocaleHelper.getSourceDisplayName(model.language, context),
                            )
                        }
                        is StandaloneSourceUiModel.Item -> {
                            val item = model.app
                            val extensionIcon = item.icon
                            val isNovel = item.kind == StandaloneScope.KIND_NOVEL
                            BasePreferenceWidget(
                                modifier = Modifier.fillMaxWidth(),
                                title = item.name,
                                subcomponent = {
                                    Text(
                                        text = buildString {
                                            append(item.sourceName)
                                            append(" • ")
                                            append(item.extensionVersionName)
                                            append(" • ")
                                            append(LocaleHelper.getSourceDisplayName(item.lang, context))
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
                                    IconButton(
                                        enabled = outputUri.isNotBlank() &&
                                            !isNovel &&
                                            !state.isGeneratingAny,
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
                                },
                            )
                        }
                    }
                }
            }
        },
    )
}

private enum class AppSection { UPDATES, INSTALLED, GENERATED }

private enum class RowAction { UPDATE, OPEN, REINSTALL, INSTALL }

private enum class RowProgress { GENERATING, UPDATING, INSTALLING }

private data class AppRow(
    val installed: StandaloneInstallState,
    val section: AppSection,
    val actions: List<RowAction>,
    val progress: RowProgress? = null,
    val app: GeneratedStandaloneApp = installed.app,
)

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        style = MaterialTheme.typography.header,
    )
}

private fun LazyListScope.appRowsSection(
    sectionKey: String,
    header: String,
    rows: List<AppRow>,
    onAction: (AppRow, RowAction) -> Unit,
) {
    if (rows.isEmpty()) return
    item(key = "standalone-$sectionKey-header") {
        SectionHeader(text = header)
    }
    items(items = rows, key = { "standalone-$sectionKey-${it.installed.app.packageName}" }) { row ->
        val progressLabel = when (row.progress) {
            RowProgress.GENERATING -> stringResource(MR.strings.standalone_generating, row.app.label)
            RowProgress.UPDATING -> stringResource(MR.strings.standalone_updating, row.app.label)
            RowProgress.INSTALLING -> stringResource(MR.strings.ext_installing)
            null -> null
        }
        val actions = row.actions.map { action ->
            val label = when (action) {
                RowAction.UPDATE -> stringResource(MR.strings.ext_update)
                RowAction.OPEN -> stringResource(MR.strings.standalone_open)
                RowAction.REINSTALL -> stringResource(MR.strings.standalone_reinstall)
                RowAction.INSTALL -> stringResource(MR.strings.action_install)
            }
            action to label
        }
        GeneratedAppRow(
            installed = row.installed,
            label = row.app.label,
            progressLabel = progressLabel,
            actions = actions,
            onAction = { action -> onAction(row, action) },
        )
    }
}

@Composable
private fun GeneratedAppRow(
    installed: StandaloneInstallState,
    label: String,
    progressLabel: String?,
    actions: List<Pair<RowAction, String>>,
    onAction: (RowAction) -> Unit,
) {
    val context = LocalContext.current
    BasePreferenceWidget(
        modifier = Modifier.fillMaxWidth(),
        title = label,
        subcomponent = {
            Text(
                text = buildString {
                    append(installed.app.sourceName)
                    if (installed.installedVersionName != null) {
                        append(" • ")
                        append(installed.installedVersionName)
                    }
                    if (installed.hasUpdate) {
                        append(" • ")
                        append(stringResource(MR.strings.ext_update))
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp).secondaryItemAlpha(),
                style = MaterialTheme.typography.bodySmall,
            )
        },
        icon = {
            // Installed standalone apps have the extension's icon baked in; ones still waiting to
            // be installed fall back to the icon of the extension package they were built from.
            val iconPackage = if (installed.installed) {
                installed.app.packageName
            } else {
                installed.app.extensionPkg
            }
            val bitmap = remember(iconPackage, installed.app.extensionPkg) {
                val drawable = runCatching { context.packageManager.getApplicationIcon(iconPackage) }.getOrNull()
                    ?: runCatching {
                        context.packageManager.getApplicationIcon(installed.app.extensionPkg)
                    }.getOrNull()
                drawable?.toBitmap()?.asImageBitmap()
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
            if (progressLabel != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = progressLabel,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                Row {
                    actions.forEachIndexed { index, (action, label) ->
                        if (index > 0) {
                            Spacer(Modifier.width(4.dp))
                        }
                        TextButton(onClick = { onAction(action) }) { Text(label) }
                    }
                }
            }
        },
    )
}
