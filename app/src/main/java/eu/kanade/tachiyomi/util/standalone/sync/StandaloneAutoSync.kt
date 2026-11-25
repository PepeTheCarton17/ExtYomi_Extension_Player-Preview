package eu.kanade.tachiyomi.util.standalone.sync

import android.content.Context
import eu.kanade.domain.ui.StandaloneScope
import eu.kanade.domain.ui.UiPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.concurrent.atomic.AtomicLong

/**
 * Debounced entry point used when the apps come to the foreground and while they stay open.
 * The switch in Home > Filtrar controls both directions from either app.
 */
object StandaloneAutoSync {

    private const val MIN_INTERVAL_MS = 20_000L
    private val lastAttempt = AtomicLong(0L)

    suspend fun runIfEnabled(context: Context): Result<String>? {
        val appContext = context.applicationContext
        if (!Injekt.get<UiPreferences>().standaloneAutoSync().get()) return null
        val now = System.currentTimeMillis()
        if (now - lastAttempt.getAndSet(now) < MIN_INTERVAL_MS) return null
        return if (StandaloneScope.isActive) {
            StandaloneSyncClient.syncNow(appContext)
        } else {
            HostStandaloneSyncManager.syncAll(appContext)
        }
    }
}
