package eu.kanade.domain.ui

import android.content.Context
import org.json.JSONObject
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import eu.kanade.domain.extension.anime.interactor.TrustAnimeExtension
import eu.kanade.domain.extension.manga.interactor.TrustMangaExtension

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
    const val CONFIG_ASSET = "standalone_config.json"

    private val preferences: UiPreferences
        get() = Injekt.get()

    val sourceId: Long
        get() = preferences.standaloneSourceId().get()

    val kind: String
        get() = preferences.standaloneKind().get()

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

        // The standalone app has its own preferences, so pre-trust the extension it was built for.
        val extensionPkg = config.optString("extensionPkg", "")
        val versionCode = config.optLong("extensionVersionCode", -1L)
        val signatureHash = config.optString("extensionSignatureHash", "")
        if (extensionPkg.isEmpty() || versionCode < 0 || signatureHash.isEmpty()) return

        when (kind) {
            KIND_ANIME -> Injekt.get<TrustAnimeExtension>()
                .trust(extensionPkg, versionCode, signatureHash)
            KIND_MANGA -> Injekt.get<TrustMangaExtension>()
                .trust(extensionPkg, versionCode, signatureHash)
        }
    }

    fun appliesTo(kind: String): Boolean = isActive && this.kind == kind

    /** Source id to filter by for the given content kind, or -1 when unscoped. */
    fun sourceIdFor(kind: String): Long = if (appliesTo(kind)) sourceId else -1L
}
