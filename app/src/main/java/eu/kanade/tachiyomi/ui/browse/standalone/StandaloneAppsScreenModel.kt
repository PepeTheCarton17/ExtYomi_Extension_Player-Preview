package eu.kanade.tachiyomi.ui.browse.standalone

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.core.content.pm.PackageInfoCompat
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.hippo.unifile.UniFile
import eu.kanade.domain.extension.anime.interactor.GetAnimeExtensionsByType
import eu.kanade.domain.extension.anime.model.AnimeExtensions
import eu.kanade.domain.extension.manga.interactor.GetMangaExtensionsByType
import eu.kanade.domain.extension.manga.model.MangaExtensions
import eu.kanade.domain.source.anime.interactor.GetEnabledAnimeSources
import eu.kanade.domain.source.manga.interactor.GetEnabledMangaSources
import eu.kanade.domain.ui.GeneratedStandaloneApp
import eu.kanade.domain.ui.StandaloneScope
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.ui.reader.loader.NovelSourceCompat
import eu.kanade.tachiyomi.util.standalone.StandaloneAppGenerator
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.LAST_USED_KEY
import eu.kanade.tachiyomi.util.system.LOCAL_SOURCE_KEY
import eu.kanade.tachiyomi.util.system.PINNED_KEY
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.source.anime.model.AnimeSource
import tachiyomi.source.local.entries.anime.LocalAnimeSource
import tachiyomi.source.local.entries.manga.LocalMangaSource
import tachiyomi.source.local.entries.novel.LocalNovelSource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.TreeMap
import tachiyomi.domain.source.anime.model.Pin as AnimePin
import tachiyomi.domain.source.manga.model.Pin as MangaPin
import tachiyomi.domain.source.manga.model.Source as MangaSource

class StandaloneAppsScreenModel(
    private val context: Context,
    private val getAnimeExtensions: GetAnimeExtensionsByType = Injekt.get(),
    private val getMangaExtensions: GetMangaExtensionsByType = Injekt.get(),
    private val getEnabledAnimeSources: GetEnabledAnimeSources = Injekt.get(),
    private val getEnabledMangaSources: GetEnabledMangaSources = Injekt.get(),
    private val uiPreferences: UiPreferences = Injekt.get(),
) : StateScreenModel<StandaloneAppsScreenModel.State>(State()) {

    private var animeExtensions: AnimeExtensions = AnimeExtensions(emptyList(), emptyList(), emptyList(), emptyList())
    private var mangaExtensions: MangaExtensions = MangaExtensions(emptyList(), emptyList(), emptyList(), emptyList())
    private var animeSources: List<AnimeSource> = emptyList()
    private var mangaSources: List<MangaSource> = emptyList()
    private var registry: List<GeneratedStandaloneApp> = emptyList()

    init {
        screenModelScope.launchIO {
            combine(
                getAnimeExtensions.subscribe(),
                getMangaExtensions.subscribe(),
                getEnabledAnimeSources.subscribe(),
                getEnabledMangaSources.subscribe(),
                uiPreferences.standaloneApps().changes(),
            ) { anime, manga, enabledAnime, enabledManga, apps ->
                animeExtensions = anime
                mangaExtensions = manga
                animeSources = enabledAnime
                mangaSources = enabledManga
                registry = apps
                buildState(mutableState.value.kind)
            }.collectLatest { newState ->
                mutableState.update { current ->
                    newState.copy(
                        phaseByPackage = current.phaseByPackage,
                        pendingInstall = current.pendingInstall,
                        isGeneratingAny = current.isGeneratingAny,
                        error = current.error,
                    )
                }
            }
        }
    }

    fun setKind(kind: String) {
        mutableState.update { current ->
            if (current.kind == kind) {
                current
            } else {
                buildState(kind).copyTransientStateFrom(current)
            }
        }
    }

    /** Recomputes installed versions; called after an installer returns or the app resumes. */
    fun refresh() {
        mutableState.update { current -> buildState(current.kind).copyTransientStateFrom(current) }
    }

    private fun State.copyTransientStateFrom(current: State) = copy(
        phaseByPackage = current.phaseByPackage,
        pendingInstall = current.pendingInstall,
        isGeneratingAny = current.isGeneratingAny,
        error = current.error,
    )

    private fun buildState(kind: String): State {
        val hostVersionCode = runCatching {
            PackageInfoCompat.getLongVersionCode(
                context.packageManager.getPackageInfo(context.packageName, 0),
            )
        }.getOrDefault(BuildConfig.VERSION_CODE.toLong())

        // Sources decide the grouping, but an extension with an update pending lives in `updates`,
        // not `installed`. Ignoring that bucket hides perfectly usable extensions from "Create app".
        val animeExtensionBySource = (animeExtensions.updates + animeExtensions.installed)
            .flatMap { extension -> extension.sources.map { it.id to extension } }
            .toMap()
        val mangaExtensionBySource = (mangaExtensions.updates + mangaExtensions.installed)
            .flatMap { extension -> extension.sources.map { it.id to extension } }
            .toMap()

        val candidates = buildList {
            animeSources.forEach { source ->
                val extension = animeExtensionBySource[source.id] ?: return@forEach
                add(
                    StandaloneExtensionItem(
                        name = extension.name,
                        sourceName = source.name,
                        sourceId = source.id,
                        kind = StandaloneScope.KIND_ANIME,
                        extensionPkg = extension.pkgName,
                        extensionVersionName = extension.versionName,
                        icon = extension.icon,
                        lang = source.lang.ifBlank { extension.lang },
                        isPinned = AnimePin.Actual in source.pin,
                        isUsedLast = source.isUsedLast,
                        isLocal = source.id == LocalAnimeSource.ID,
                    ),
                )
            }
            mangaSources.forEach { source ->
                val extension = mangaExtensionBySource[source.id] ?: return@forEach
                val isNovel = NovelSourceCompat.isNovelSource(source.id)
                add(
                    StandaloneExtensionItem(
                        name = extension.name,
                        sourceName = source.name,
                        sourceId = source.id,
                        kind = if (isNovel) {
                            StandaloneScope.KIND_NOVEL
                        } else {
                            StandaloneScope.KIND_MANGA
                        },
                        extensionPkg = extension.pkgName,
                        extensionVersionName = extension.versionName,
                        icon = extension.icon,
                        lang = source.lang.ifBlank { extension.lang },
                        isPinned = MangaPin.Actual in source.pin,
                        isUsedLast = source.isUsedLast,
                        isLocal = source.id == LocalMangaSource.ID || source.id == LocalNovelSource.ID,
                    ),
                )
            }
        }.distinctBy { Triple(it.kind, it.sourceId, it.extensionPkg) }

        val groups = TreeMap<String, MutableList<StandaloneExtensionItem>> { d1, d2 ->
            when {
                d1 == LOCAL_SOURCE_KEY && d2 != LOCAL_SOURCE_KEY -> -1
                d2 == LOCAL_SOURCE_KEY && d1 != LOCAL_SOURCE_KEY -> 1
                d1 == LAST_USED_KEY && d2 != LAST_USED_KEY -> -1
                d2 == LAST_USED_KEY && d1 != LAST_USED_KEY -> 1
                d1 == PINNED_KEY && d2 != PINNED_KEY -> -1
                d2 == PINNED_KEY && d1 != PINNED_KEY -> 1
                d1 == "" && d2 != "" -> 1
                d2 == "" && d1 != "" -> -1
                else -> d1.compareTo(d2)
            }
        }
        candidates
            .filter { it.kind == kind }
            .groupByTo(groups) {
                when {
                    it.isLocal -> LOCAL_SOURCE_KEY
                    it.isUsedLast -> LAST_USED_KEY
                    it.isPinned -> PINNED_KEY
                    else -> it.lang
                }
            }

        val items = groups.flatMap { (language, apps) ->
            buildList<StandaloneSourceUiModel> {
                add(StandaloneSourceUiModel.Header(language))
                apps.forEach { add(StandaloneSourceUiModel.Item(it)) }
            }
        }.toImmutableList()

        val installedApps = registry.filter { it.kind == kind }.map { app ->
            val info = runCatching {
                context.packageManager.getPackageInfo(app.packageName, 0)
            }.getOrNull()
            StandaloneInstallState(
                app = app,
                installed = info != null,
                installedVersionCode = info?.let { PackageInfoCompat.getLongVersionCode(it) } ?: -1L,
                installedVersionName = info?.versionName,
                hostVersionCode = hostVersionCode,
            )
        }

        return State(
            kind = kind,
            items = items,
            installedApps = installedApps,
            updateCount = installedApps.count { it.hasUpdate },
        )
    }

    fun generate(context: Context, item: StandaloneExtensionItem, outputDirUri: Uri) {
        val key = standaloneAppStateKey(item.kind, item.sourceId, item.extensionPkg)
        screenModelScope.launchIO {
            mutableState.update { current ->
                current.copy(
                    phaseByPackage = current.phaseByPackage + (key to AppPhase.Generating),
                    isGeneratingAny = true,
                    error = false,
                )
            }
            val result = runCatching {
                StandaloneAppGenerator.generate(context, item, outputDirUri)
            }
            result.fold(
                onSuccess = { generated ->
                    val app = GeneratedStandaloneApp(
                        packageName = generated.packageName,
                        label = item.name,
                        sourceId = item.sourceId,
                        kind = item.kind,
                        extensionPkg = item.extensionPkg,
                        extensionVersionName = item.extensionVersionName,
                        sourceName = item.sourceName,
                        outputUri = generated.outputDirUri,
                        apkUri = generated.apkUri,
                        fileName = generated.fileName,
                        generatedVersionCode = generated.generatedVersionCode,
                        generatedVersionName = generated.generatedVersionName,
                    )
                    // Rebuilding an app that is already installed must not offer "Instalar" in a
                    // second section: go straight to the installer and back to "Instaladas".
                    val alreadyInstalled = runCatching {
                        context.packageManager.getPackageInfo(generated.packageName, 0)
                    }.getOrNull() != null
                    upsert(app)
                    mutableState.update { current ->
                        current.copy(
                            phaseByPackage = current.phaseByPackage + (
                                key to if (alreadyInstalled) AppPhase.Updating else AppPhase.Generated(app)
                                ),
                            pendingInstall = if (alreadyInstalled) app else null,
                            isGeneratingAny = false,
                        )
                    }
                },
                onFailure = {
                    mutableState.update { current ->
                        current.copy(
                            phaseByPackage = current.phaseByPackage - key,
                            isGeneratingAny = false,
                            error = true,
                        )
                    }
                },
            )
        }
    }

    /**
     * Regenerates the APK with the current generator version and queues it for immediate install.
     * Used both by "Actualizar" and by "Reinstalar": neither leaves a pending "Instalar" step.
     */
    fun update(context: Context, app: GeneratedStandaloneApp) {
        val key = app.stateKey
        screenModelScope.launchIO {
            mutableState.update { current ->
                current.copy(
                    phaseByPackage = current.phaseByPackage + (key to AppPhase.Updating),
                    pendingInstall = null,
                    error = false,
                )
            }
            val result = runCatching {
                StandaloneAppGenerator.generate(
                    context = context,
                    item = StandaloneExtensionItem(
                        name = app.label,
                        sourceName = app.sourceName,
                        sourceId = app.sourceId,
                        kind = app.kind,
                        extensionPkg = app.extensionPkg,
                        extensionVersionName = app.extensionVersionName,
                        icon = null,
                        lang = "",
                    ),
                    outputDirUri = Uri.parse(app.outputUri),
                )
            }
            result.fold(
                onSuccess = { generated ->
                    val updatedApp = app.copy(
                        packageName = generated.packageName,
                        outputUri = generated.outputDirUri,
                        apkUri = generated.apkUri,
                        fileName = generated.fileName,
                        generatedVersionCode = generated.generatedVersionCode,
                        generatedVersionName = generated.generatedVersionName,
                    )
                    upsert(updatedApp, replacedPackage = app.packageName)
                    mutableState.update { current ->
                        current.copy(
                            phaseByPackage = current.phaseByPackage + (key to AppPhase.Updating),
                            pendingInstall = updatedApp,
                            error = false,
                        )
                    }
                },
                onFailure = {
                    mutableState.update { current ->
                        current.copy(
                            phaseByPackage = current.phaseByPackage - key,
                            pendingInstall = null,
                            error = true,
                        )
                    }
                },
            )
        }
    }

    /** Queues an already generated APK for installation. The UI observes [State.pendingInstall]. */
    fun requestInstall(app: GeneratedStandaloneApp) {
        mutableState.update { current -> current.copy(pendingInstall = app) }
    }

    /** Aborts a queued install, e.g. when the install-permission request is shown instead. */
    fun cancelPendingInstall() {
        mutableState.update { current ->
            current.copy(
                pendingInstall = null,
                phaseByPackage = current.phaseByPackage.filterValues { it !is AppPhase.Updating },
            )
        }
    }

    private fun upsert(app: GeneratedStandaloneApp, replacedPackage: String = app.packageName) {
        val updated = registry.filterNot {
            it.packageName == replacedPackage || it.packageName == app.packageName
        } + app
        registry = updated
        uiPreferences.standaloneApps().set(updated)
        val refreshed = buildState(mutableState.value.kind)
        mutableState.update { current -> refreshed.copyTransientStateFrom(current) }
    }

    /**
     * Copies the SAF document to our own cache and returns an installer intent. The caller must
     * launch it with `StartActivityForResult` and then call [onInstallFinished].
     */
    fun prepareInstall(context: Context, app: GeneratedStandaloneApp): Intent? {
        val key = app.stateKey
        mutableState.update { current ->
            current.copy(phaseByPackage = current.phaseByPackage + (key to AppPhase.Installing))
        }
        return runCatching {
            val document = UniFile.fromUri(context, Uri.parse(app.apkUri))
                ?: error("Cannot open the generated APK")
            val cacheDir = File(context.cacheDir, "standalone-install").apply { mkdirs() }
            val apk = File(cacheDir, app.fileName)
            document.openInputStream().use { input ->
                apk.outputStream().use { output -> input.copyTo(output) }
            }
            Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setDataAndType(apk.getUriCompat(context), APK_MIME)
                .putExtra(Intent.EXTRA_RETURN_RESULT, true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }.getOrElse {
            mutableState.update { current ->
                current.copy(
                    phaseByPackage = current.phaseByPackage - key,
                    error = true,
                )
            }
            null
        }
    }

    fun onInstallFinished(app: GeneratedStandaloneApp) {
        val refreshed = buildState(mutableState.value.kind)
        mutableState.update { current ->
            refreshed.copy(
                phaseByPackage = current.phaseByPackage - app.stateKey,
                pendingInstall = null,
                isGeneratingAny = false,
                error = current.error,
            )
        }
    }

    fun open(context: Context, app: GeneratedStandaloneApp) {
        runCatching {
            val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
                ?: error("The standalone app is not installed")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    @Immutable
    data class State(
        val kind: String = StandaloneScope.KIND_ANIME,
        val isLoading: Boolean = true,
        val items: ImmutableList<StandaloneSourceUiModel> = persistentListOf(),
        val installedApps: List<StandaloneInstallState> = emptyList(),
        val updateCount: Int = 0,
        /** Transient per-app phase; it only swaps the row's action, never its section. */
        val phaseByPackage: Map<String, AppPhase> = emptyMap(),
        /** APK waiting for the installer. Observed by the UI to launch the install prompt. */
        val pendingInstall: GeneratedStandaloneApp? = null,
        val isGeneratingAny: Boolean = false,
        val error: Boolean = false,
    )

    sealed interface AppPhase {
        data object Generating : AppPhase
        data class Generated(val app: GeneratedStandaloneApp) : AppPhase
        data object Updating : AppPhase
        data object Installing : AppPhase
    }

    companion object {
        private const val APK_MIME = "application/vnd.android.package-archive"
    }
}

internal fun standaloneAppStateKey(
    kind: String,
    sourceId: Long,
    extensionPkg: String,
): String = "$kind|$sourceId|$extensionPkg"

internal val GeneratedStandaloneApp.stateKey: String
    get() = standaloneAppStateKey(kind, sourceId, extensionPkg)

@Immutable
data class StandaloneInstallState(
    val app: GeneratedStandaloneApp,
    val installed: Boolean,
    val installedVersionCode: Long,
    val installedVersionName: String?,
    val hostVersionCode: Long,
) {
    val hasUpdate: Boolean
        get() = installed && installedVersionCode < maxOf(hostVersionCode, app.generatedVersionCode)
}

sealed interface StandaloneSourceUiModel {
    data class Header(val language: String) : StandaloneSourceUiModel
    data class Item(val app: StandaloneExtensionItem) : StandaloneSourceUiModel
}

@Immutable
data class StandaloneExtensionItem(
    val name: String,
    val sourceName: String,
    val sourceId: Long,
    val kind: String,
    val extensionPkg: String,
    val extensionVersionName: String,
    val icon: Drawable?,
    val lang: String = "",
    val isPinned: Boolean = false,
    val isUsedLast: Boolean = false,
    val isLocal: Boolean = false,
)
