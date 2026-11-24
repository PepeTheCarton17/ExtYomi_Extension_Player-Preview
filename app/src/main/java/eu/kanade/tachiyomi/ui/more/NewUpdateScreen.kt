package eu.kanade.tachiyomi.ui.more

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.work.WorkInfo
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.more.AppUpdateDownloadState
import eu.kanade.presentation.more.NewUpdateScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import eu.kanade.tachiyomi.data.updater.AppUpdateDownloadJob
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.openInBrowser

class NewUpdateScreen(
    private val versionName: String,
    private val changelogInfo: String,
    private val releaseLink: String,
    private val downloadLink: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val changelogInfoNoChecksum = remember {
            changelogInfo.replace("""---(\R|.)*Checksums(\R|.)*""".toRegex(), "")
        }

        // Start once when the prompt opens. Observing the same WorkManager job below makes the
        // progress visible in the app even if notifications are disabled or missed by the user.
        LaunchedEffect(Unit) {
            AppUpdateDownloadJob.start(context, downloadLink, versionName)
        }

        val workInfos by remember {
            AppUpdateDownloadJob.workInfos(context)
        }.collectAsState(initial = emptyList())
        // Ignore a finished job left over from a previous version.
        val workInfo = workInfos.firstOrNull {
            it.inputData.getString(AppUpdateDownloadJob.EXTRA_DOWNLOAD_TITLE) == versionName
        }
        val downloadState = when (workInfo?.state) {
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.BLOCKED,
            -> AppUpdateDownloadState.Queued
            WorkInfo.State.RUNNING -> AppUpdateDownloadState.Downloading(
                workInfo?.progress?.getInt(AppUpdateDownloadJob.EXTRA_PROGRESS, 0) ?: 0,
            )
            WorkInfo.State.SUCCEEDED -> AppUpdateDownloadState.Done
            WorkInfo.State.FAILED,
            WorkInfo.State.CANCELLED,
            -> AppUpdateDownloadState.Error
            else -> AppUpdateDownloadState.Idle
        }

        NewUpdateScreen(
            versionName = versionName,
            changelogInfo = changelogInfoNoChecksum,
            downloadState = downloadState,
            onOpenInBrowser = { context.openInBrowser(releaseLink) },
            onRejectUpdate = {
                AppUpdateDownloadJob.stop(context)
                navigator.pop()
            },
            onAcceptUpdate = {
                when (downloadState) {
                    AppUpdateDownloadState.Done -> {
                        NotificationHandler.installApkPendingActivity(
                            context,
                            AppUpdateDownloadJob.downloadedApk(context).getUriCompat(context),
                        ).send()
                    }
                    AppUpdateDownloadState.Idle,
                    AppUpdateDownloadState.Error,
                    -> AppUpdateDownloadJob.start(
                        context = context,
                        url = downloadLink,
                        title = versionName,
                    )
                    else -> Unit
                }
            },
        )
    }
}
