package com.endiq.turtlelauncher.utils.anim

import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.view.animation.TimeInterpolator
import com.endiq.turtlelauncher.feature.theme.Appearance

/**
 * Every launcher-owned motion value, in one place: the easing curves, the duration of each
 * kind of interaction, and the interpolators themselves - built once and shared, so a tap or
 * a list change allocates nothing.
 *
 * The curves are the strong variants rather than the platform's built-ins. `ease-in` on a UI
 * element delays the exact moment the user is watching, which reads as a slow interface even
 * at the same duration; anything arriving uses a hard-start, soft-landing curve instead.
 *
 * [ms] runs a base duration through [Appearance.duration], so the motion budget, reduced
 * motion and the adaptive downgrade in [MotionGovernor] all still apply. These constants are
 * bases, never the final number.
 */
object MotionSpec {

    /** Entering, leaving, press release: starts immediately, lands softly. */
    @JvmField
    val EASE_OUT: TimeInterpolator = PathInterpolator(0.23f, 1f, 0.32f, 1f)

    /** Movement between two positions already on screen (reorder, reflow). */
    @JvmField
    val EASE_IN_OUT: TimeInterpolator = PathInterpolator(0.77f, 0f, 0.175f, 1f)

    /** Press-down: the finger is still on the glass, so it has to be over before it reads. */
    @JvmField
    val PRESS_IN: TimeInterpolator = PathInterpolator(0.2f, 0f, 0f, 1f)

    @JvmField
    val LINEAR: TimeInterpolator = LinearInterpolator()

    /**
     * Durations. A tap is seen hundreds of times a day and a row change dozens, so both stay
     * far under the 300ms ceiling that separates "responsive" from "catching up".
     */
    const val PRESS_MS = 90L
    const val RELEASE_MS = 170L
    const val FADE_MS = 160L

    const val ITEM_ADD_MS = 190L
    const val ITEM_REMOVE_MS = 150L
    const val ITEM_MOVE_MS = 200L
    const val ITEM_CHANGE_MS = 160L

    /** Gap between two rows arriving. Short enough to read as one movement, not a queue. */
    const val LIST_STEP_MS = 35L

    /** Rows past this many do not wait any longer - the tail lands with the rest. */
    const val MAX_STAGGERED_ROWS = 6

    /** A base duration with the current motion budget applied. */
    @JvmStatic
    fun ms(baseMs: Long): Long = Appearance.duration(baseMs)
}
