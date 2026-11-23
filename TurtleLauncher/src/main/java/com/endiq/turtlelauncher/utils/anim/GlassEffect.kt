package com.endiq.turtlelauncher.utils.anim

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.widget.ImageView
import com.endiq.turtlelauncher.setting.AllSettings

/**
 * Frosted glass for the launcher's backdrop.
 *
 * Two layers make the effect:
 *
 *  * the still wallpaper is blurred with a [RenderEffect] (Android 12+), so the cards and
 *    panels above it look like they sit on frosted glass instead of a sharp photo;
 *  * a faint white veil is laid over the wallpaper/video for the highlights real glass
 *    picks up.
 *
 * Everything here is guarded by the `glassEffect` setting, so the switch in
 * Settings -> Launcher turns it on and off immediately. On Android 11 and older the blur
 * is unavailable, but the veil still applies - that is the closest approximation the
 * platform offers without rendering the backdrop to a bitmap every frame.
 */
object GlassEffect {
    private const val BLUR_RADIUS = 26f

    /** @param background the wallpaper ImageView, @param veil the overlay above the video. */
    @JvmStatic
    fun apply(background: ImageView?, veil: View?) {
        val enabled = AllSettings.glassEffect.getValue()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val effect = if (enabled) {
                RenderEffect.createBlurEffect(BLUR_RADIUS, BLUR_RADIUS, Shader.TileMode.CLAMP)
            } else {
                null
            }
            background?.setRenderEffect(effect)
        }

        veil?.visibility = if (enabled) View.VISIBLE else View.GONE
    }
}
