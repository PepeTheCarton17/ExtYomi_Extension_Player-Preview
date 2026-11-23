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
                mutableState.update { current -> newState.copy(status = current.status) }
            }
        }
    }

    fun setKind(kind: String) {
        mutableState.update { current ->
            if (current.kind == kind) {
                current
            } else {
                buildState(kind).copy(status = current.status)
            }
        }
    }

    /** Recomputes installed versions; called after an installer returns or the app resumes. */
    fun refresh() {
        mutableState.update { current -> buildState(current.kind).copy(status = current.status) }
    }

    private fun buildState(kind: String): State {
        val hostVersionCode = runCatching {
            PackageInfoCompat.getLongVersionCode(
                context.packageManager.getPackageInfo(context.packageName, 0),
            )
        }.getOrDefault(BuildConfig.VERSION_CODE.toLong())

        // Sources, not extensions, decide the grouping. This reuses exactly the same enabled-source
        // and pin settings as the Sources tabs, so "Pinned" and language headers behave identically.
        val animeExtensionBySource = animeExtensions.installed
            .flatMap { extension -> extension.sources.map { it.id to extension } }
            .toMap()
        val mangaExtensionBySource = mangaExtensions.installed
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

        val installedApps = registry.map { app ->
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
        screenModelScope.launchIO {
            mutableState.update { it.copy(status = Status.Generating(item.name)) }
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
                    upsert(app)
                    mutableState.update { it.copy(status = Status.Generated(app)) }
                },
                onFailure = {
                    mutableState.update { it.copy(status = Status.Error) }
                },
            )
        }
    }

    /** Regenerates the APK with the current generator version, then prompts installation again. */
    fun update(context: Context, app: GeneratedStandaloneApp) {
        generate(
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

    private fun upsert(app: GeneratedStandaloneApp) {
        val updated = registry.filterNot { it.packageName == app.packageName } + app
        registry = updated
        uiPreferences.standaloneApps().set(updated)
        mutableState.update { it.copy(installedApps = it.installedApps) }
    }

    /**
     * Copies the SAF document to our own cache and returns an installer intent. The caller must
     * launch it with `StartActivityForResult` and then call [onInstallFinished].
     */
    fun prepareInstall(context: Context, app: GeneratedStandaloneApp): Intent? {
        mutableState.update { it.copy(status = Status.Installing(app)) }
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
            mutableState.update { it.copy(status = Status.Error) }
            null
        }
    }

    fun onInstallFinished(app: GeneratedStandaloneApp) {
        val refreshed = buildState(mutableState.value.kind)
        val installed = refreshed.installedApps.firstOrNull { it.app.packageName == app.packageName }
        mutableState.update { current ->
            refreshed.copy(
                status = if (installed?.installed == true) {
                    Status.Installed(app)
                } else {
                    current.status
                },
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
        val status: Status? = null,
    )

    sealed interface Status {
        data class Generating(val extensionName: String) : Status
        data class Generated(val app: GeneratedStandaloneApp) : Status
        data class Installing(val app: GeneratedStandaloneApp) : Status
        data class Installed(val app: GeneratedStandaloneApp) : Status
        data object Error : Status
    }

    companion object {
        private const val APK_MIME = "application/vnd.android.package-archive"
    }
}

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
