package com.endiq.turtlelauncher.feature.theme

import android.app.Activity
import android.view.ViewGroup
import com.endiq.turtlelauncher.feature.log.Logging

/**
 * Applies the parts of a theme pack that are not a colour: today that is End Void's starfield.
 *
 * Inserted behind the activity's content so it never intercepts touches and never covers a card;
 * the launcher's glass surfaces let it show through. Call it right after `setContentView` - the
 * content view exists from then on and the pack is already resolved from settings.
 */
object ThemeEffects {
    private const val TAG = "ThemeEffects"

    @JvmStatic
    fun apply(activity: Activity?) {
        activity ?: return
        runCatching {
            val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
            val existing = content.findViewWithTag<StarfieldView>(StarfieldView.TAG_VIEW)

            if (!ThemeManager.pack().starfield) {
                existing?.let { content.removeView(it) }
                return
            }
            if (existing != null) return

            val stars = StarfieldView(activity, ThemeManager.scheme(activity))
            stars.tag = StarfieldView.TAG_VIEW
            content.addView(
                stars,
                0,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }.onFailure { Logging.e(TAG, "Failed to apply theme effects", it) }
    }
}
