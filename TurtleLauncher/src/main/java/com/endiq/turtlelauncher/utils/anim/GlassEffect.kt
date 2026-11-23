package com.endiq.turtlelauncher.utils.anim

import android.os.Build
import android.view.View
import com.endiq.turtlelauncher.setting.AllSettings

/**
 * Frosted glass for the launcher's backdrop.
 *
 * The backdrop the user actually sees is the looping background video (the still wallpaper
 * is only a poster underneath it). Blurring those layers is exactly what users did not want:
 * a blurred clip looks like an out-of-focus screen, so the picture is left sharp and only a
 * faint sheen is laid on top of it. The "glass" look comes from the translucent card and
 * panel surfaces themselves, not from touching the wallpaper.
 *
 * The switch in Settings -> Launcher still applies immediately: turning it off removes the
 * sheen entirely.
 */
object GlassEffect {
    /**
     * @param background the wallpaper ImageView (kept sharp).
     * @param video the background video view (kept sharp).
     * @param veil the faint sheen drawn above both, below the launcher screens.
     */
    @JvmStatic
    fun apply(background: View?, video: View?, veil: View?) {
        // Clear any RenderEffect a previous version may have set on these layers.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(background, video).forEach { view ->
                if (view != null) runCatching { view.setRenderEffect(null) }
            }
        }

        veil?.visibility = if (AllSettings.glassEffect.getValue()) View.VISIBLE else View.GONE
    }
}
