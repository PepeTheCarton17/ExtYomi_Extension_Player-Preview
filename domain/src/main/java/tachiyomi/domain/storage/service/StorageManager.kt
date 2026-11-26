package tachiyomi.domain.storage.service

import android.content.Context
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn

class StorageManager(
    private val context: Context,
    storagePreferences: StoragePreferences,
) {

    private val scope = CoroutineScope(Dispatchers.IO)

    private var baseDir: UniFile? = getBaseDir(storagePreferences.baseStorageDirectory().get())

    private val _changes: Channel<Unit> = Channel(Channel.UNLIMITED)
    val changes = _changes.receiveAsFlow()
        .shareIn(scope, SharingStarted.Lazily, 1)

    init {
        // Create the suggested standalone folders on startup too: on a fresh install the storage
        // preference is already set, so the `changes()` flow below never fires.
        baseDir?.let(::initializeDirectories)
        storagePreferences.baseStorageDirectory().changes()
            .drop(1)
            .distinctUntilChanged()
            .onEach { uri ->
                baseDir = getBaseDir(uri)
                baseDir?.let(::initializeDirectories)
                _changes.send(Unit)
            }
            .launchIn(scope)
    }

    private fun getBaseDir(uri: String): UniFile? {
        return UniFile.fromUri(context, uri.toUri())
            .takeIf { it?.exists() == true }
    }

    /**
     * Creates every folder managed by the app and removes empty, numbered leftovers from older
     * runs. `UniFile.findFile()` is not reliable for every SAF provider: it builds a child URI from
     * `<parentId>/<name>`, which fails for providers with opaque document ids. When that happens,
     * `createDirectory()` asks the provider for "downloads" again and it answers "downloads (1)",
     * then "downloads (2)", and so on. Looking at the actual children avoids that.
     */
    private fun initializeDirectories(parent: UniFile) {
        parent.ensureDirectory(AUTOMATIC_BACKUPS_PATH)
        parent.ensureDirectory(LOCAL_SOURCE_PATH)
        parent.ensureDirectory(LOCAL_ANIMESOURCE_PATH)
        parent.ensureDirectory(LOCAL_NOVELSOURCE_PATH)
        parent.ensureDirectory(DOWNLOADS_PATH)?.let { DiskUtil.createNoMediaFile(it, context) }
        createStandaloneDirectories(parent)
        parent.ensureDirectory(MPV_CONFIG_PATH)?.let { mpvDir ->
            mpvDir.ensureDirectory(FONTS_PATH)
            mpvDir.ensureDirectory(SCRIPTS_PATH)
            mpvDir.ensureDirectory(SCRIPT_OPTS_PATH)
            mpvDir.ensureDirectory(SHADERS_PATH)
        }
        cleanupNumberedDuplicateDirectories(parent)
    }

    fun getAutomaticBackupsDirectory(): UniFile? {
        return baseDir?.ensureDirectory(AUTOMATIC_BACKUPS_PATH)
    }

    fun getDownloadsDirectory(): UniFile? {
        return baseDir?.ensureDirectory(DOWNLOADS_PATH)
    }

    fun getStandaloneAppsDirectory(): UniFile? {
        return baseDir?.let { root ->
            createStandaloneDirectories(root)
            root.ensureDirectory(STANDALONE_APPS_PATH)
        }
    }

    /** Suggested output folder for a standalone, by content kind. */
    fun getStandaloneAppsDirectory(kind: String): UniFile? {
        val child = when (kind) {
            "anime" -> STANDALONE_ANIME_PATH
            "manga" -> STANDALONE_MANGA_PATH
            "novel" -> STANDALONE_NOVEL_PATH
            else -> return getStandaloneAppsDirectory()
        }
        return getStandaloneAppsDirectory()?.ensureDirectory(child)
    }

    private fun createStandaloneDirectories(parent: UniFile) {
        val root = parent.ensureDirectory(STANDALONE_APPS_PATH) ?: return
        root.ensureDirectory(STANDALONE_ANIME_PATH)
        root.ensureDirectory(STANDALONE_MANGA_PATH)
        root.ensureDirectory(STANDALONE_NOVEL_PATH)
    }

    fun getLocalMangaSourceDirectory(): UniFile? {
        return baseDir?.ensureDirectory(LOCAL_SOURCE_PATH)
    }

    fun getLocalAnimeSourceDirectory(): UniFile? {
        return baseDir?.ensureDirectory(LOCAL_ANIMESOURCE_PATH)
    }

    fun getLocalNovelSourceDirectory(): UniFile? {
        return baseDir?.ensureDirectory(LOCAL_NOVELSOURCE_PATH)
    }

    fun getFontsDirectory(): UniFile? {
        return getMPVConfigDirectory()?.ensureDirectory(FONTS_PATH)
    }

    fun getScriptsDirectory(): UniFile? {
        return getMPVConfigDirectory()?.ensureDirectory(SCRIPTS_PATH)
    }

    fun getScriptOptsDirectory(): UniFile? {
        return getMPVConfigDirectory()?.ensureDirectory(SCRIPT_OPTS_PATH)
    }

    fun getShadersDirectory(): UniFile? {
        return getMPVConfigDirectory()?.ensureDirectory(SHADERS_PATH)
    }

    fun getMPVConfigDirectory(): UniFile? {
        return baseDir?.ensureDirectory(MPV_CONFIG_PATH)
    }

    private fun cleanupNumberedDuplicateDirectories(parent: UniFile) {
        cleanupNumberedDuplicates(parent, MANAGED_ROOT_DIRECTORIES)
        parent.findExistingDirectory(MPV_CONFIG_PATH)?.let {
            cleanupNumberedDuplicates(it, MPV_SUB_DIRECTORIES)
        }
        parent.findExistingDirectory(STANDALONE_APPS_PATH)?.let {
            cleanupNumberedDuplicates(it, STANDALONE_SUB_DIRECTORIES)
        }
    }

    /**
     * Deletes only directories named exactly like a managed folder plus a SAF numeric suffix
     * (`downloads (1)`, `mpv-config (2)`, ...) and only when we can prove they are empty. Anything
     * with content, or whose listing cannot be read, is left untouched.
     */
    private fun cleanupNumberedDuplicates(parent: UniFile, names: Collection<String>) {
        val children = parent.listFiles() ?: return
        for (child in children) {
            if (!child.isDirectory) continue
            val name = child.name ?: continue
            if (names.none { name.isNumberedDuplicateOf(it) }) continue
            if (child.listFiles()?.isEmpty() != true) continue
            child.delete()
        }
    }
}

internal fun String.isNumberedDuplicateOf(base: String): Boolean {
    if (!startsWith(base)) return false
    val suffix = substring(base.length)
    return suffix.matches(NUMBERED_SUFFIX_REGEX)
}

private fun UniFile.findExistingDirectory(name: String): UniFile? {
    return listFiles()?.firstOrNull { it.isDirectory && it.name == name }
}

private fun UniFile.ensureDirectory(name: String): UniFile? {
    return findExistingDirectory(name) ?: createDirectory(name)
}

private val NUMBERED_SUFFIX_REGEX = Regex("""\s*\(\d+\)""")

private val MANAGED_ROOT_DIRECTORIES = listOf(
    AUTOMATIC_BACKUPS_PATH,
    DOWNLOADS_PATH,
    STANDALONE_APPS_PATH,
    LOCAL_SOURCE_PATH,
    LOCAL_ANIMESOURCE_PATH,
    LOCAL_NOVELSOURCE_PATH,
    MPV_CONFIG_PATH,
)

private val MPV_SUB_DIRECTORIES = listOf(
    FONTS_PATH,
    SCRIPTS_PATH,
    SCRIPT_OPTS_PATH,
    SHADERS_PATH,
)

private val STANDALONE_SUB_DIRECTORIES = listOf(
    STANDALONE_ANIME_PATH,
    STANDALONE_MANGA_PATH,
    STANDALONE_NOVEL_PATH,
)

private const val AUTOMATIC_BACKUPS_PATH = "autobackup"
private const val DOWNLOADS_PATH = "downloads"
private const val STANDALONE_APPS_PATH = "standalone"
private const val STANDALONE_ANIME_PATH = "anime"
private const val STANDALONE_MANGA_PATH = "manga"
private const val STANDALONE_NOVEL_PATH = "novelas"
private const val LOCAL_SOURCE_PATH = "local"
private const val LOCAL_ANIMESOURCE_PATH = "localanime"
private const val LOCAL_NOVELSOURCE_PATH = "localnovel"
private const val MPV_CONFIG_PATH = "mpv-config"
private const val FONTS_PATH = "fonts"
const val SCRIPTS_PATH = "scripts"
const val SCRIPT_OPTS_PATH = "script-opts"
private const val SHADERS_PATH = "shaders"
