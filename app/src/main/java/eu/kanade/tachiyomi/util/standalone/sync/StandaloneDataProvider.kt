package eu.kanade.tachiyomi.util.standalone.sync

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import androidx.core.content.FileProvider
import eu.kanade.domain.ui.GeneratedStandaloneApp
import eu.kanade.domain.ui.UiPreferences
import kotlinx.coroutines.runBlocking
import tachiyomi.domain.storage.service.StoragePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File

/**
 * Exported only to standalones registered in ExtYomi. Because generated apps use a device-local
 * signing key, identity is checked against the generated-app registry instead of a signature.
 * Payloads travel as `content://` URIs so they never hit the Binder transaction limit.
 */
class StandaloneDataProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = context ?: return bundleOf(ERROR to "NO_CONTEXT")
        val caller = callingPackage ?: return bundleOf(ERROR to "NO_CALLER")
        val instanceId = extras?.getString(KEY_INSTANCE_ID).orEmpty()
        if (caller == context.packageName) return bundleOf(ERROR to "SELF_CALL")

        val app = findApp(context, caller, instanceId)
            ?: return bundleOf(ERROR to "UNKNOWN_CALLER")
        val store = StandaloneSyncStore(context)

        return try {
            runBlocking {
                when (method) {
                    METHOD_HANDSHAKE -> {
                        val root = hostDownloadsRootUri(context)
                        grantDownloadsRoot(context, caller, root)
                        bundleOf(
                            KEY_HOST_PACKAGE to context.packageName,
                            KEY_DOWNLOADS_ROOT_URI to root.orEmpty(),
                            KEY_REVISION to (store.load(app.instanceId)?.revision ?: 0L),
                            KEY_SCHEMA to StandaloneSyncPayload.SCHEMA,
                        )
                    }
                    METHOD_PUSH -> {
                        val uri = extras?.getString(KEY_URI)?.let(Uri::parse)
                            ?: return@runBlocking bundleOf(ERROR to "NO_URI")
                        val incoming = context.contentResolver.openInputStream(uri)?.use {
                            it.readBytes().toString(Charsets.UTF_8).toStandaloneSyncPayload()
                        } ?: return@runBlocking bundleOf(ERROR to "UNREADABLE_URI")
                        require(incoming.instanceId == app.instanceId) { "Instance mismatch" }
                        require(incoming.sourceId == app.sourceId && incoming.kind == app.kind) {
                            "Scope mismatch"
                        }
                        val owners = store.loadOwners()
                        val merged = StandaloneSyncMerge.merge(store.load(app.instanceId), incoming, owners)
                        val filtered = merged.payload.filterFor(app, merged.owners)
                        StandaloneSnapshotApplier(
                            sourceId = app.sourceId,
                            kind = app.kind,
                        ).apply(filtered)
                        store.save(filtered)
                        store.saveOwners(SyncOwnerIndex(merged.owners))
                        bundleOf(KEY_ACCEPTED to true, KEY_REVISION to filtered.revision)
                    }
                    METHOD_PULL -> {
                        val snapshot = store.load(app.instanceId)
                            ?: StandaloneSyncPayload(
                                instanceId = app.instanceId,
                                sourceId = app.sourceId,
                                kind = app.kind,
                                extensionPkg = app.extensionPkg,
                                downloadsRootUri = hostDownloadsRootUri(context),
                            )
                        val file = File(context.cacheDir, "standalone-sync-out.json").apply {
                            writeText(snapshot.toJson())
                        }
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.provider",
                            file,
                        )
                        grant(context, caller, uri)
                        bundleOf(
                            KEY_URI to uri.toString(),
                            KEY_REVISION to snapshot.revision,
                            KEY_SCHEMA to StandaloneSyncPayload.SCHEMA,
                        )
                    }
                    else -> bundleOf(ERROR to "UNKNOWN_METHOD")
                }
            }
        } catch (e: Exception) {
            bundleOf(ERROR to (e.message ?: "SYNC_FAILED"))
        }
    }

    private fun findApp(context: Context, caller: String, instanceId: String): GeneratedStandaloneApp? {
        val apps = runCatching { Injekt.get<UiPreferences>().standaloneApps().get() }
            .getOrDefault(emptyList())
        val app = apps.firstOrNull {
            it.packageName == caller && instanceId.isNotBlank() && it.instanceId == instanceId
        } ?: return null
        val uid = runCatching { context.packageManager.getPackageUid(caller, 0) }.getOrNull()
            ?: return null
        val uids = context.packageManager.getPackagesForUid(uid)
        return app.takeIf { uids?.size == 1 && uids.first() == caller }
    }

    private fun hostDownloadsRootUri(context: Context): String? = runCatching {
        Injekt.get<StoragePreferences>().baseStorageDirectory().get()
    }.getOrNull()?.takeIf { it.isNotBlank() }

    private fun grantDownloadsRoot(context: Context, caller: String, uri: String?) {
        val parsed = uri?.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return
        runCatching {
            context.grantUriPermission(
                caller,
                parsed,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }
    }

    private fun grant(context: Context, caller: String, uri: Uri) {
        runCatching {
            context.grantUriPermission(caller, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun StandaloneSyncPayload.filterFor(
        app: GeneratedStandaloneApp,
        owners: Map<String, String>,
    ): StandaloneSyncPayload = copy(
        entries = entries.filter {
            owners[SyncOwnerIndex.key(kind, sourceId, it.url)] == app.instanceId
        },
    )

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = 0

    companion object {
        const val AUTHORITY_SUFFIX = ".standalonesync"
        const val METHOD_HANDSHAKE = "handshake"
        const val METHOD_PUSH = "push"
        const val METHOD_PULL = "pull"

        const val KEY_INSTANCE_ID = "instanceId"
        const val KEY_URI = "uri"
        const val KEY_HOST_PACKAGE = "hostPackage"
        const val KEY_DOWNLOADS_ROOT_URI = "downloadsRootUri"
        const val KEY_REVISION = "revision"
        const val KEY_SCHEMA = "schema"
        const val KEY_ACCEPTED = "accepted"
        const val KEY_ERROR = "error"
        const val ERROR = "error"

        fun authority(packageName: String): String = "$packageName$AUTHORITY_SUFFIX"
    }
}

private fun bundleOf(vararg pairs: Pair<String, Any?>): Bundle = Bundle().apply {
    pairs.forEach { (key, value) ->
        when (value) {
            null -> Unit
            is String -> putString(key, value)
            is Boolean -> putBoolean(key, value)
            is Long -> putLong(key, value)
            is Int -> putInt(key, value)
        }
    }
}
