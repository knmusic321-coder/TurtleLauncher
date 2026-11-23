package com.endiq.turtlelauncher.utils.anim

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import com.endiq.turtlelauncher.setting.AllSettings

/**
 * Frosted glass for the launcher's backdrop.
 *
 * The backdrop the user actually sees is the looping background video (the still wallpaper
 * is only a poster underneath it), so blurring just the ImageView did nothing visible - the
 * opaque TextureView sat on top of it. Both layers are blurred here:
 *
 *  * the wallpaper and the video get a [RenderEffect] blur (Android 12+), which is what
 *    turns the picture behind the cards into frosted glass;
 *  * a faint white veil is laid over them for the highlights real glass picks up, and it
 *    also carries the effect on Android 11 and older where blur is not available.
 *
 * Everything is guarded by the `glassEffect` setting, so the switch in
 * Settings -> Launcher turns it on and off immediately.
 */
object GlassEffect {
    /**
     * Kept moderate on purpose: the blur runs over a full-screen moving video, so a huge
     * radius costs a lot of GPU for barely more frost.
     */
    private const val BLUR_RADIUS = 18f

    /**
     * @param background the wallpaper ImageView.
     * @param video the background video view (drawn above the wallpaper).
     * @param veil the frosted sheet drawn above both, below the launcher screens.
     */
    @JvmStatic
    fun apply(background: View?, video: View?, veil: View?) {
        val enabled = AllSettings.glassEffect.getValue()
        val supportsBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        if (supportsBlur) {
            val effect = if (enabled) {
                RenderEffect.createBlurEffect(BLUR_RADIUS, BLUR_RADIUS, Shader.TileMode.CLAMP)
            } else {
                null
            }
            // A device may refuse a RenderEffect on a given surface (an override of
            // setRenderEffect, a vendor TextureView): the veil below still gives the
            // frosted look rather than crashing the home screen.
            arrayOf(background, video).forEach { view ->
                if (view != null) runCatching { view.setRenderEffect(effect) }
            }
        }

        veil?.visibility = if (enabled) View.VISIBLE else View.GONE
    }
}
