package eu.kanade.tachiyomi.ui.browse.standalone

import android.content.Context
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.extension.anime.interactor.GetAnimeExtensionsByType
import eu.kanade.domain.extension.manga.interactor.GetMangaExtensionsByType
import eu.kanade.domain.ui.StandaloneScope
import eu.kanade.tachiyomi.ui.reader.loader.NovelSourceCompat
import eu.kanade.tachiyomi.util.standalone.StandaloneAppGenerator
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.util.lang.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class StandaloneAppsScreenModel(
    private val getAnimeExtensions: GetAnimeExtensionsByType = Injekt.get(),
    private val getMangaExtensions: GetMangaExtensionsByType = Injekt.get(),
) : StateScreenModel<StandaloneAppsScreenModel.State>(State()) {

    init {
        screenModelScope.launchIO {
            combine(
                getAnimeExtensions.subscribe(),
                getMangaExtensions.subscribe(),
            ) { anime, manga ->
                buildList {
                    anime.installed.forEach { extension ->
                        extension.sources.forEach { source ->
                            add(
                                StandaloneExtensionItem(
                                    name = extension.name,
                                    sourceName = source.name,
                                    sourceId = source.id,
                                    kind = StandaloneScope.KIND_ANIME,
                                    extensionPkg = extension.pkgName,
                                    extensionVersionName = extension.versionName,
                                    extensionVersionCode = extension.versionCode,
                                    icon = extension.icon,
                                ),
                            )
                        }
                    }
                    manga.installed
                        .filterNot { NovelSourceCompat.isNovelExtensionPkg(it.pkgName) }
                        .forEach { extension ->
                            extension.sources.forEach { source ->
                                add(
                                    StandaloneExtensionItem(
                                        name = extension.name,
                                        sourceName = source.name,
                                        sourceId = source.id,
                                        kind = StandaloneScope.KIND_MANGA,
                                        extensionPkg = extension.pkgName,
                                        extensionVersionName = extension.versionName,
                                        extensionVersionCode = extension.versionCode,
                                        icon = extension.icon,
                                    ),
                                )
                            }
                        }
                }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            }.collectLatest { items ->
                mutableState.update { it.copy(isLoading = false, items = items) }
            }
        }
    }

    fun generate(context: Context, item: StandaloneExtensionItem, outputDirUri: Uri) {
        screenModelScope.launchIO {
            mutableState.update { it.copy(status = Status.Generating(item.name)) }
            val result = runCatching {
                StandaloneAppGenerator.generate(context, item, outputDirUri)
            }
            mutableState.update {
                it.copy(
                    status = result.fold(
                        onSuccess = { fileName -> Status.Success(fileName) },
                        onFailure = { Status.Error },
                    ),
                )
            }
        }
    }

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val items: List<StandaloneExtensionItem> = emptyList(),
        val status: Status? = null,
    )

    sealed interface Status {
        data class Generating(val extensionName: String) : Status
        data class Success(val fileName: String) : Status
        data object Error : Status
    }
}

@Immutable
data class StandaloneExtensionItem(
    val name: String,
    val sourceName: String,
    val sourceId: Long,
    val kind: String,
    val extensionPkg: String,
    val extensionVersionName: String,
    val extensionVersionCode: Long,
    val icon: Drawable?,
)
