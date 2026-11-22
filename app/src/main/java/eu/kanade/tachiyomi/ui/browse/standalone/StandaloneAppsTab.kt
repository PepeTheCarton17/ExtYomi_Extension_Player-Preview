package eu.kanade.tachiyomi.ui.browse.standalone

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GetApp
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.components.TabContent
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.presentation.core.util.collectAsState as collectPreferenceAsState

@Composable
fun standaloneAppsTab(): TabContent {
    val context = LocalContext.current
    val screenModel = rememberScreenModel { StandaloneAppsScreenModel() }
    val state by screenModel.state.collectAsState()
    val uiPreferences = remember { Injekt.get<UiPreferences>() }
    val outputUri by uiPreferences.standaloneOutputUri().collectPreferenceAsState()

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
                                Text(stringResource(MR.strings.standalone_choose_folder))
                            }
                            if (outputUri.isNotBlank()) {
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = stringResource(MR.strings.standalone_output_folder),
                                    style = MaterialTheme.typography.bodySmall,
                                )
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

                if (!state.isLoading && state.items.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(MR.strings.standalone_no_extensions),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                items(
                    count = state.items.size,
                    key = { index -> state.items[index].sourceId },
                ) { index ->
                    val item = state.items[index]
                    TextPreferenceWidget(
                        modifier = Modifier.fillMaxWidth(),
                        title = item.name,
                        subtitle = "${item.sourceName} • ${item.kind} • ${item.extensionVersionName}",
                        icon = Icons.Outlined.GetApp,
                        widget = {
                            IconButton(
                                enabled = outputUri.isNotBlank() &&
                                    state.status !is StandaloneAppsScreenModel.Status.Generating,
                                onClick = {
                                    screenModel.generate(context, item, Uri.parse(outputUri))
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.GetApp,
                                    contentDescription = stringResource(MR.strings.standalone_create_app),
                                )
                            }
                        },
                    )
                }
            }
        },
    )
}
