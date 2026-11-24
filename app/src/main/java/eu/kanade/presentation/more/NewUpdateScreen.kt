package eu.kanade.presentation.more

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.halilibo.richtext.markdown.Markdown
import com.halilibo.richtext.ui.RichTextStyle
import com.halilibo.richtext.ui.material3.RichText
import com.halilibo.richtext.ui.string.RichTextStringStyle
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.InfoScreen

/** Download state mirrored from WorkManager so the screen can show real progress. */
sealed interface AppUpdateDownloadState {
    data object Idle : AppUpdateDownloadState
    data object Queued : AppUpdateDownloadState
    data class Downloading(val progress: Int) : AppUpdateDownloadState
    data object Done : AppUpdateDownloadState
    data object Error : AppUpdateDownloadState
}

@Composable
fun NewUpdateScreen(
    versionName: String,
    changelogInfo: String,
    onOpenInBrowser: () -> Unit,
    onRejectUpdate: () -> Unit,
    onAcceptUpdate: () -> Unit,
    downloadState: AppUpdateDownloadState = AppUpdateDownloadState.Idle,
) {
    val acceptTextRes = when (downloadState) {
        AppUpdateDownloadState.Idle -> MR.strings.update_check_confirm
        AppUpdateDownloadState.Queued,
        is AppUpdateDownloadState.Downloading,
        -> MR.strings.update_check_notification_download_in_progress
        AppUpdateDownloadState.Done -> MR.strings.action_install
        AppUpdateDownloadState.Error -> MR.strings.action_retry
    }
    val canAccept = downloadState !is AppUpdateDownloadState.Queued &&
        downloadState !is AppUpdateDownloadState.Downloading

    InfoScreen(
        icon = Icons.Outlined.NewReleases,
        headingText = stringResource(MR.strings.update_check_notification_update_available),
        subtitleText = versionName,
        acceptText = stringResource(acceptTextRes),
        canAccept = canAccept,
        onAcceptClick = onAcceptUpdate,
        rejectText = stringResource(MR.strings.action_not_now),
        onRejectClick = onRejectUpdate,
    ) {
        when (downloadState) {
            is AppUpdateDownloadState.Downloading -> {
                LinearProgressIndicator(
                    progress = { (downloadState.progress.coerceIn(0, 100) / 100f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "${downloadState.progress.coerceIn(0, 100)}%",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            AppUpdateDownloadState.Queued -> {
                Text(
                    text = stringResource(MR.strings.update_check_notification_download_in_progress),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            AppUpdateDownloadState.Done -> {
                Text(
                    text = stringResource(MR.strings.update_check_notification_download_complete),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            AppUpdateDownloadState.Error -> {
                Text(
                    text = stringResource(MR.strings.update_check_notification_download_error),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            AppUpdateDownloadState.Idle -> Unit
        }

        RichText(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = MaterialTheme.padding.large),
            style = RichTextStyle(
                stringStyle = RichTextStringStyle(
                    linkStyle = SpanStyle(color = MaterialTheme.colorScheme.primary),
                ),
            ),
        ) {
            Markdown(content = changelogInfo)

            TextButton(
                onClick = onOpenInBrowser,
                modifier = Modifier.padding(top = MaterialTheme.padding.small),
            ) {
                Text(text = stringResource(MR.strings.update_check_open))
                Spacer(modifier = Modifier.width(MaterialTheme.padding.extraSmall))
                Icon(imageVector = Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun NewUpdateScreenPreview() {
    TachiyomiPreviewTheme {
        NewUpdateScreen(
            versionName = "v0.99.9",
            changelogInfo = """
                ## Yay
                Foobar

                ### More info
                - Hello
                - World
            """.trimIndent(),
            onOpenInBrowser = {},
            onRejectUpdate = {},
            onAcceptUpdate = {},
        )
    }
}
