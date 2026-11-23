package tachiyomi.domain.release.interactor

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ReleaseService
import java.time.Instant
import java.time.temporal.ChronoUnit

class GetApplicationRelease(
    private val service: ReleaseService,
    private val preferenceStore: PreferenceStore,
) {

    private val lastChecked: Preference<Long> by lazy {
        preferenceStore.getLong(Preference.appStateKey("last_app_check"), 0)
    }

    suspend fun await(arguments: Arguments): Result {
        val now = Instant.now()

        // Limit checks to once every 3 days at most
        if (!arguments.forceCheck &&
            now.isBefore(
                Instant.ofEpochMilli(lastChecked.get()).plus(3, ChronoUnit.DAYS),
            )
        ) {
            return Result.NoNewUpdate
        }

        val release = service.latest(arguments) ?: return Result.NoNewUpdate

        lastChecked.set(now.toEpochMilli())

        // Check if latest version is different from current version
        val isNewVersion = isNewVersion(
            arguments.isPreview,
            arguments.commitCount,
            arguments.versionName,
            release.version,
        )
        return when {
            isNewVersion -> Result.NewUpdate(release)
            else -> Result.NoNewUpdate
        }
    }

    private fun isNewVersion(
        isPreview: Boolean,
        commitCount: Int,
        versionName: String,
        versionTag: String,
    ): Boolean {
        // Legacy commit-count tags (r1234) are still understood for older previews.
        commitTagRegex.matchEntire(versionTag.trim())?.let { match ->
            val newCount = match.groupValues[1].toIntOrNull() ?: return false
            return isPreview && newCount > commitCount
        }

        // ExtYomi publishes semver tags: preview-v0.1.9 and v0.1.9. Compare numerically so
        // "0.1.10" is newer than "0.1.9" (a plain String comparison would get that wrong).
        val newVersion = parseVersion(versionTag) ?: return false
        val currentVersion = parseVersion(versionName) ?: return false
        return compareVersion(newVersion, currentVersion) > 0
    }

    private fun parseVersion(value: String): List<Int>? {
        val core = value.trim()
            .removePrefix("preview-v")
            .removePrefix("v")
            .substringBefore("-")
        if (core.isEmpty()) return null
        val parts = core.split(".").map { it.toIntOrNull() ?: return null }
        return parts
    }

    private fun compareVersion(a: List<Int>, b: List<Int>): Int {
        val size = maxOf(a.size, b.size)
        for (index in 0 until size) {
            val left = a.getOrElse(index) { 0 }
            val right = b.getOrElse(index) { 0 }
            if (left != right) return left.compareTo(right)
        }
        return 0
    }

    companion object {
        private val commitTagRegex = Regex("""r(\d+)""")
    }

    data class Arguments(
        val isPreview: Boolean,
        val commitCount: Int,
        val versionName: String,
        val repository: String,
        val forceCheck: Boolean = false,
    )

    sealed interface Result {
        data class NewUpdate(val release: Release) : Result
        data object NoNewUpdate : Result
        data object OsTooOld : Result
    }
}
