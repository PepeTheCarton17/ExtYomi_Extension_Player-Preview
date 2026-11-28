package eu.kanade.tachiyomi.util.standalone.sync

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import eu.kanade.domain.ui.GeneratedStandaloneApp
import eu.kanade.domain.ui.StandaloneScope
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.util.standalone.StandaloneSigningKey
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import tachiyomi.domain.storage.service.StoragePreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

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

        return try {
            if (StandaloneScope.isActive) {
                callAsStandalone(context, caller, method, extras)
            } else {
                val app = findApp(context, caller, instanceId)
                    ?: return bundleOf(ERROR to unknownCallerMessage(context, caller, instanceId))
                callAsHost(context, caller, app, method, extras)
            }
        } catch (e: Exception) {
            bundleOf(ERROR to (e.message ?: "SYNC_FAILED"))
        }
    }

    /** The generated APK carries this same provider, so ExtYomi can sync in the opposite direction. */
    private fun callAsStandalone(
        context: Context,
        caller: String,
        method: String,
        extras: Bundle?,
    ): Bundle = runBlocking {
        val hostPackage = StandaloneScope.syncAuthority.removeSuffix(AUTHORITY_SUFFIX)
        check(caller == hostPackage) { "Caller $caller is not ExtYomi" }
        when (method) {
            METHOD_PULL_LOCAL -> {
                val snapshot = StandaloneSnapshotBuilder(
                    previous = StandaloneSyncStore(context).load(StandaloneScope.instanceId),
                ).build()
                val uri = writeSnapshot(context, caller, "standalone-sync-local.json", snapshot)
                bundleOf(
                    KEY_URI to uri.toString(),
                    KEY_REVISION to snapshot.revision,
                    KEY_SCHEMA to StandaloneSyncPayload.SCHEMA,
                )
            }
            METHOD_PUSH_LOCAL -> {
                val incoming = readSnapshot(context, extras) ?: return@runBlocking bundleOf(ERROR to "NO_URI")
                check(incoming.instanceId == StandaloneScope.instanceId) { "Instance mismatch" }
                check(
                    incoming.sourceId == StandaloneScope.sourceId &&
                        incoming.kind == StandaloneScope.kind,
                ) { "Scope mismatch" }
                val store = StandaloneSyncStore(context)
                StandaloneSnapshotApplier(previous = store.load(StandaloneScope.instanceId))
                    .apply(incoming)
                store.save(incoming)
                bundleOf(KEY_ACCEPTED to true, KEY_REVISION to incoming.revision)
            }
            else -> bundleOf(ERROR to "UNKNOWN_METHOD")
        }
    }

    private fun callAsHost(
        context: Context,
        caller: String,
        app: GeneratedStandaloneApp,
        method: String,
        extras: Bundle?,
    ): Bundle = runBlocking {
        val store = StandaloneSyncStore(context)
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
                val incoming = readSnapshot(context, extras) ?: return@runBlocking bundleOf(ERROR to "NO_URI")
                require(incoming.instanceId == app.instanceId) { "Instance mismatch" }
                require(incoming.sourceId == app.sourceId && incoming.kind == app.kind) {
                    "Scope mismatch"
                }
                val previous = store.load(app.instanceId)
                val owners = store.loadOwners().droppingStaleOwners(registeredInstanceIds(app))
                val merged = StandaloneSyncMerge.merge(previous, incoming, owners)
                val filtered = merged.payload.filterFor(app, merged.owners)
                StandaloneSnapshotApplier(
                    sourceId = app.sourceId,
                    kind = app.kind,
                    previous = previous,
                ).apply(filtered)
                store.save(filtered)
                store.saveOwners(SyncOwnerIndex(merged.owners))
                bundleOf(KEY_ACCEPTED to true, KEY_REVISION to filtered.revision)
            }
            METHOD_PULL -> {
                val snapshot = hostSnapshot(context, app, store)
                val uri = writeSnapshot(context, caller, "standalone-sync-out.json", snapshot)
                bundleOf(
                    KEY_URI to uri.toString(),
                    KEY_REVISION to snapshot.revision,
                    KEY_SCHEMA to StandaloneSyncPayload.SCHEMA,
                )
            }
            else -> bundleOf(ERROR to "UNKNOWN_METHOD")
        }
    }

    /** ExtYomi's view of one instance: its own DB changes merged with the last known snapshot. */
    internal suspend fun hostSnapshot(
        context: Context,
        app: GeneratedStandaloneApp,
        store: StandaloneSyncStore,
    ): StandaloneSyncPayload {
        val stored = store.load(app.instanceId)
        val owners = store.loadOwners().droppingStaleOwners(registeredInstanceIds(app)).owners
        val fresh = StandaloneSnapshotBuilder(
            sourceId = app.sourceId,
            kind = app.kind,
            instanceId = app.instanceId,
            extensionPkg = app.extensionPkg,
            previous = stored,
        ).build(hostDownloadsRootUri(context))
        val merged = StandaloneSyncMerge.merge(stored, fresh, SyncOwnerIndex(owners))
        val filtered = merged.payload.copy(
            entries = merged.payload.entries.filter { entry ->
                val key = SyncOwnerIndex.key(app.kind, app.sourceId, entry.url)
                merged.owners[key] == null || merged.owners[key] == app.instanceId
            },
        )
        store.save(filtered)
        store.saveOwners(SyncOwnerIndex(merged.owners))
        return filtered
    }

    private fun readSnapshot(context: Context, extras: Bundle?): StandaloneSyncPayload? {
        val uri = extras?.getString(KEY_URI)?.let(Uri::parse) ?: return null
        return context.contentResolver.openInputStream(uri)?.use {
            it.readBytes().toString(Charsets.UTF_8).toStandaloneSyncPayload()
        }
    }

    private fun writeSnapshot(
        context: Context,
        caller: String,
        fileName: String,
        snapshot: StandaloneSyncPayload,
    ): Uri {
        val file = File(context.cacheDir, fileName).apply { writeText(snapshot.toJson()) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        grant(context, caller, uri)
        return uri
    }

    /**
     * Generated apps use a device-local signing key. If the local registry was lost (reinstall,
     * preference reset) but the APK was really generated here, recover its scope from the config
     * embedded in the APK and re-register it. A foreign key is rejected with an actionable message.
     */
    private fun unknownCallerMessage(context: Context, caller: String, instanceId: String): String {
        val generatedPrefix = "${context.packageName}.standalone."
        return if (caller.startsWith(generatedPrefix)) {
            "Esta app independiente no coincide con el registro local (paquete o ID $instanceId). " +
                "Si la clave de firma de este dispositivo cambió al reinstalar ExtYomi, regenera " +
                "la app desde Explorar → Apps independientes e instala el APK resultante aquí."
        } else {
            "Llamada no autorizada al proveedor de sincronización: $caller"
        }
    }

    private fun findApp(context: Context, caller: String, instanceId: String): GeneratedStandaloneApp? {
        val registry = Injekt.get<UiPreferences>().standaloneApps()
        val apps = runCatching { registry.get() }.getOrDefault(emptyList())
        val app = apps.firstOrNull {
            it.packageName == caller && instanceId.isNotBlank() && it.instanceId == instanceId
        }?.takeIf { hasSingleUid(context, caller) }
        if (app != null) return app
        if (!caller.startsWith("${context.packageName}.standalone.")) return null

        val recovered = recoverLocallyGeneratedApp(context, caller, instanceId) ?: return null
        runCatching {
            registry.set(apps.filterNot { it.packageName == caller } + recovered)
        }
        return recovered
    }

    /** Only the apps registered right now may own entries; reinstalled apps get a fresh ID. */
    private fun registeredInstanceIds(app: GeneratedStandaloneApp): Set<String> =
        runCatching { Injekt.get<UiPreferences>().standaloneApps().get() }
            .getOrDefault(emptyList())
            .mapNotNull { it.instanceId.takeIf(String::isNotBlank) }
            .toSet() + app.instanceId

    private fun hasSingleUid(context: Context, caller: String): Boolean {
        val uid = runCatching { context.packageManager.getPackageUid(caller, 0) }.getOrNull()
            ?: return false
        val uids = context.packageManager.getPackagesForUid(uid)
        return uids?.size == 1 && uids.first() == caller
    }

    /** Rebuilds the registry entry from the APK itself, but only when signed with our local key. */
    @Suppress("DEPRECATION")
    private fun recoverLocallyGeneratedApp(
        context: Context,
        caller: String,
        instanceId: String,
    ): GeneratedStandaloneApp? {
        if (instanceId.isBlank() || !hasSingleUid(context, caller)) return null
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info = runCatching { context.packageManager.getPackageInfo(caller, flags) }.getOrNull()
            ?: return null
        val signer = runCatching {
            val expected = StandaloneSigningKey.getOrCreate(context).certificate.encoded
            val actual = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val signingInfo = info.signingInfo
                val certs = if (signingInfo?.hasMultipleSigners() == true) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo?.signingCertificateHistory
                }
                certs?.lastOrNull()?.toByteArray()
            } else {
                info.signatures?.lastOrNull()?.toByteArray()
            }
            actual != null &&
                sha256(expected).contentEquals(sha256(actual))
        }.getOrDefault(false)
        if (!signer) return null

        val sourceDir = runCatching { context.packageManager.getApplicationInfo(caller, 0).sourceDir }
            .getOrNull() ?: return null
        val config = runCatching {
            ZipFile(sourceDir).use { zip ->
                val entry = zip.getEntry("assets/${StandaloneScope.CONFIG_ASSET}") ?: return@use null
                zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
            }
        }.getOrNull() ?: return null
        val json = runCatching { JSONObject(config) }.getOrNull() ?: return null
        if (json.optString("generatedBy") != context.packageName ||
            json.optString("syncAuthority") != authority(context.packageName) ||
            json.optString("instanceId") != instanceId
        ) {
            return null
        }
        val kind = json.optString("kind")
        val sourceId = json.optLong("sourceId", -1L)
        val extensionPkg = json.optString("extensionPkg")
        if (kind.isBlank() || sourceId < 0L || extensionPkg.isBlank()) return null

        val label = runCatching {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(caller, 0),
            ).toString()
        }.getOrDefault(caller)
        return GeneratedStandaloneApp(
            packageName = caller,
            instanceId = instanceId,
            label = label,
            sourceId = sourceId,
            kind = kind,
            extensionPkg = extensionPkg,
            extensionVersionName = info.versionName.orEmpty(),
            sourceName = label,
            outputUri = "",
            apkUri = "",
            fileName = sourceDir.substringAfterLast('/'),
            generatedVersionCode = PackageInfoCompat.getLongVersionCode(info),
            generatedVersionName = info.versionName.orEmpty(),
        )
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

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
        const val METHOD_PULL_LOCAL = "pullLocal"
        const val METHOD_PUSH_LOCAL = "pushLocal"

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
