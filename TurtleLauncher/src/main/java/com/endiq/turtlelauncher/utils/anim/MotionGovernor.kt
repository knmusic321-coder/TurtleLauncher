package com.endiq.turtlelauncher.utils.anim

import android.os.Process
import android.view.Choreographer
import com.endiq.turtlelauncher.setting.AllSettings

/**
 * Keeps the launcher's own frames cheap while it is the visible window.
 *
 * Two mechanisms, both measured rather than guessed:
 *
 *  * **Adaptive motion** - samples real frame intervals off the Choreographer. When most of
 *    the last second missed the frame budget, the launcher shortens its own animations
 *    ([factor]) instead of showing a stutter, and climbs back to full motion after a clean
 *    second. A transition that is being displayed at a lower frame rate is harder to read
 *    than the same transition played shorter, so on a loaded device less motion is the
 *    smoother result.
 *  * **UI thread priority** - runs the main thread at display priority while the launcher is
 *    on screen, so a background download, a mod-folder scan or file indexing cannot pull
 *    frames out from under a tap or a scroll.
 *
 * Neither runs when the launcher is not visible: [onResume] starts it, [onPause] stops it and
 * restores the thread priority it changed.
 */
object MotionGovernor {

    /** An interval above this counts as a dropped frame at 60Hz. */
    private const val DROPPED_FRAME_NS = 24_000_000L

    /** Frames one verdict is made from - about a second at 60Hz. */
    private const val SAMPLE_WINDOW = 60

    /** Below this many samples a single slow frame would decide the verdict on its own. */
    private const val MIN_SAMPLES = 20

    /** Share of dropped frames that counts as jank. */
    private const val JANK_FRACTION = 0.2f

    /** Motion budget while janky. Shorter, not gone: the interface still shows what moved. */
    private const val JANK_FACTOR = 0.5f

    private val intervals = LongArray(SAMPLE_WINDOW)
    private var cursor = 0
    private var samples = 0
    private var lastFrameNanos = 0L
    private var sampling = false
    private var threadPriorityRaised = false

    @Volatile
    private var janky = false

    /** Multiplier for every launcher-owned duration - 1 while frames are clean. */
    @JvmStatic
    fun factor(): Float = if (janky) JANK_FACTOR else 1f

    /** Activity resume path. Idempotent. */
    @JvmStatic
    fun onResume() {
        refresh()
        if (AllSettings.adaptiveMotion.getValue()) startSampling()
    }

    /** Activity pause path: stop sampling and hand the thread priority back. */
    @JvmStatic
    fun onPause() {
        stopSampling()
        restoreUiThreadPriority()
    }

    /**
     * Applies both switches as they are right now. Called on resume and again when either
     * switch is flipped, so a change takes effect without waiting for the next screen.
     */
    @JvmStatic
    fun refresh() {
        if (AllSettings.uiThreadPriority.getValue()) raiseUiThreadPriority() else restoreUiThreadPriority()
        if (AllSettings.adaptiveMotion.getValue()) startSampling() else stopSampling()
    }

    // ── Frame sampling ───────────────────────────────────────────────────────────

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!sampling) return
            if (lastFrameNanos != 0L) {
                intervals[cursor] = frameTimeNanos - lastFrameNanos
                cursor = (cursor + 1) % SAMPLE_WINDOW
                if (samples < SAMPLE_WINDOW) samples++
                if (samples >= MIN_SAMPLES) {
                    val share = droppedShare()
                    // Asymmetric thresholds: entering jank needs a fifth of the window,
                    // leaving it needs half of that. Otherwise it flaps on every hiccup.
                    janky = share > (if (janky) JANK_FRACTION * 0.5f else JANK_FRACTION)
                }
            }
            lastFrameNanos = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun droppedShare(): Float {
        var dropped = 0
        for (index in 0 until samples) {
            if (intervals[index] > DROPPED_FRAME_NS) dropped++
        }
        return dropped.toFloat() / samples
    }

    private fun startSampling() {
        if (sampling) return
        sampling = true
        samples = 0
        cursor = 0
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopSampling() {
        if (!sampling) return
        sampling = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        lastFrameNanos = 0L
        samples = 0
        cursor = 0
        janky = false
    }

    // ── Thread priority ──────────────────────────────────────────────────────────

    private fun raiseUiThreadPriority() {
        if (threadPriorityRaised) return
        threadPriorityRaised = true
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
    }

    private fun restoreUiThreadPriority() {
        if (!threadPriorityRaised) return
        threadPriorityRaised = false
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT) }
    }
}
