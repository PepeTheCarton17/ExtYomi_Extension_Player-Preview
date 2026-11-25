package eu.kanade.domain.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import eu.kanade.domain.extension.anime.interactor.TrustAnimeExtension
import eu.kanade.domain.extension.manga.interactor.TrustMangaExtension
import org.json.JSONObject
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException

/**
 * Scope baked into a generated standalone app.
 *
 * A standalone app is the ExtYomi fork repackaged for one extension. The generator writes a
 * small JSON asset into the new APK; on first launch the app reads it and locks its navigation
 * and library to that single source. The generator app itself has no such asset and keeps its
 * normal multi-extension behaviour.
 */
object StandaloneScope {

    const val KIND_ANIME = "anime"
    const val KIND_MANGA = "manga"

    /** Shown as a separate category in the generator; standalone generation is not supported yet. */
    const val KIND_NOVEL = "novel"
    const val CONFIG_ASSET = "standalone_config.json"

    private val preferences: UiPreferences
        get() = Injekt.get()

    val sourceId: Long
        get() = preferences.standaloneSourceId().get()

    val kind: String
        get() = preferences.standaloneKind().get()

    /** Package of the extension this standalone app was built for. */
    val extensionPkg: String
        get() = preferences.standaloneExtensionPkg().get()

    /** Stable identity of this generated app, independent of sourceId and language. */
    val instanceId: String
        get() = preferences.standaloneInstanceId().get()

    /** Authority of the ExtYomi data provider this app syncs with. */
    val syncAuthority: String
        get() = preferences.standaloneSyncAuthority().get()

    /** SAF tree shared with ExtYomi for downloads. */
    val downloadsRootUri: String
        get() = preferences.standaloneDownloadsUri().get()

    val isActive: Boolean
        get() = sourceId >= 0 && kind.isNotEmpty()

    /** Reads the generated app's config. Safe to call in the generator, where the asset is absent. */
    fun initializeFromAssets(context: Context) {
        val config = try {
            context.assets.open(CONFIG_ASSET).bufferedReader().use { it.readText() }
                .let(::JSONObject)
        } catch (_: IOException) {
            return
        } catch (_: Exception) {
            logcat { "Ignoring invalid standalone app config" }
            return
        }

        val sourceId = config.optLong("sourceId", -1L)
        val kind = config.optString("kind", "")
        if (sourceId < 0 || kind.isEmpty()) return

        preferences.standaloneSourceId().set(sourceId)
        preferences.standaloneKind().set(kind)
        preferences.standaloneInstanceId().set(config.optString("instanceId", ""))
        preferences.standaloneSyncAuthority().set(config.optString("syncAuthority", ""))
        val downloadsRootUri = config.optString("downloadsRootUri", "")
        preferences.standaloneDownloadsUri().set(downloadsRootUri)
        if (downloadsRootUri.isNotEmpty()) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    Uri.parse(downloadsRootUri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            runCatching {
                Injekt.get<tachiyomi.domain.storage.service.StoragePreferences>()
                    .baseStorageDirectory()
                    .set(downloadsRootUri)
            }
        }

        // The standalone app has its own preferences, so pre-trust the extension it was built for.
        val extensionPkg = config.optString("extensionPkg", "")
        preferences.standaloneExtensionPkg().set(extensionPkg)
        val versionCode = config.optLong("extensionVersionCode", -1L)
        val signatureHash = config.optString("extensionSignatureHash", "")
        if (extensionPkg.isEmpty() || versionCode < 0 || signatureHash.isEmpty()) return

        when (kind) {
            KIND_ANIME -> Injekt.get<TrustAnimeExtension>()
                .trust(extensionPkg, versionCode, signatureHash)
            KIND_MANGA, KIND_NOVEL -> Injekt.get<TrustMangaExtension>()
                .trust(extensionPkg, versionCode, signatureHash)
        }
    }

    fun appliesTo(kind: String): Boolean = isActive && this.kind == kind

    /** Source id to filter by for the given content kind, or -1 when unscoped. */
    fun sourceIdFor(kind: String): Long = if (appliesTo(kind)) sourceId else -1L
}
