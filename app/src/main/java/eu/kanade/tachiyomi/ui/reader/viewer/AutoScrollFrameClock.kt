package eu.kanade.tachiyomi.ui.reader.viewer

import android.view.Choreographer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Frame clock for reader auto-scroll. [Choreographer] delivers callbacks in step with the display's
 * VSync, so scrolling no longer drifts the way it did with `delay(16)`. The returned delta time also
 * keeps the configured dp/s constant on 60/90/120 Hz screens.
 *
 * Must be used from the main thread (both viewers run their scroll job on [kotlinx.coroutines.MainScope]).
 */
class AutoScrollFrameClock {

    private var previousFrameMs = 0L

    /** Resets the delta so a restarted scroll doesn't jump by the time it was stopped. */
    fun reset() {
        previousFrameMs = 0L
    }

    suspend fun awaitFrameDeltaMs(): Float = suspendCancellableCoroutine { continuation ->
        val choreographer = Choreographer.getInstance()
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val nowMs = frameTimeNanos / 1_000_000L
                val delta = if (previousFrameMs == 0L) {
                    DEFAULT_FRAME_MS
                } else {
                    // Clamp so returning from background doesn't fling the reader.
                    (nowMs - previousFrameMs).coerceIn(0L, MAX_FRAME_MS)
                }
                previousFrameMs = nowMs
                continuation.resume(delta.toFloat())
            }
        }
        continuation.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
        choreographer.postFrameCallback(callback)
    }
}

private const val DEFAULT_FRAME_MS = 16L
private const val MAX_FRAME_MS = 50L
