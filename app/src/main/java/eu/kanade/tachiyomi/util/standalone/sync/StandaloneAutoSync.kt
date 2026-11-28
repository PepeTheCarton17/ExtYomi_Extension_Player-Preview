package eu.kanade.tachiyomi.util.standalone.sync

import android.content.Context
import eu.kanade.domain.ui.StandaloneScope
import eu.kanade.domain.ui.UiPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Entry point for local sync.
 *
 * Changes in the library/categories call [request], which coalesces a short burst of writes into
 * one sync instead of dropping it for 20 seconds. Foreground, the periodic fallback and the manual
 * button call [runIfEnabled] directly. A mutex prevents two syncs from fighting over the same
 * ContentProvider files.
 */
object StandaloneAutoSync {

    private const val COALESCE_MS = 350L
    private const val MIN_RUN_GAP_MS = 250L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runMutex = Mutex()
    private val scheduled = AtomicBoolean(false)
    private val lastRun = AtomicLong(0L)

    /** Schedules a sync right after a local change, merging rapid successive writes. */
    suspend fun request(context: Context) {
        val app = context.applicationContext
        if (!isEnabled(app)) return
        if (!scheduled.compareAndSet(false, true)) return
        scope.launch {
            try {
                delay(COALESCE_MS)
                runSuspended(app)
            } finally {
                scheduled.set(false)
            }
        }
    }

    /** Runs a sync now when the Home switch is enabled. */
    suspend fun runIfEnabled(context: Context): Result<String>? {
        val app = context.applicationContext
        if (!isEnabled(app)) return null
        return runSuspended(app)
    }

    /**
     * Manual button: always runs, even when automatic sync is switched off, but shares the mutex
     * with [request] so a click cannot race an automatic sync over the same snapshot files.
     */
    suspend fun runNow(context: Context): Result<String> = withContext(Dispatchers.IO) {
        runMutex.withLock {
            perform(context.applicationContext)
        }
    }

    private suspend fun runSuspended(context: Context): Result<String>? = runMutex.withLock {
        val now = System.currentTimeMillis()
        // Only suppresses an immediate duplicate (foreground + observer + fallback), never a
        // real change: those arrive through [request] after the coalescing window.
        if (now - lastRun.get() < MIN_RUN_GAP_MS) return@withLock null
        lastRun.set(now)
        perform(context)
    }

    private suspend fun perform(context: Context): Result<String> =
        if (StandaloneScope.isActive) {
            StandaloneSyncClient.syncNow(context)
        } else {
            HostStandaloneSyncManager.syncAll(context)
        }

    private fun isEnabled(context: Context): Boolean =
        Injekt.get<UiPreferences>().standaloneAutoSync().get()
}
