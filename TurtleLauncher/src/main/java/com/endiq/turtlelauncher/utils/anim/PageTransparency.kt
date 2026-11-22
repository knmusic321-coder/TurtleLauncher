package com.endiq.turtlelauncher.utils.anim

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView

/**
 * Applies the "page opacity" setting to the launcher's surfaces instead of the whole
 * screen.
 *
 * The old implementation called `View.setAlpha()` on the fragment container, which faded
 * every label, slider and switch along with the background. Drawable alpha paints only
 * the part of a view that the drawable covers, so cards and panels become translucent
 * while the text, sliders and toggles drawn on top of them stay fully opaque.
 */
object PageTransparency {
    /** [percent] is the stored page-opacity value: 100 = fully opaque, 0 = invisible. */
    @JvmStatic
    fun apply(root: View?, percent: Int) {
        if (root == null) return
        val alpha = percent.coerceIn(0, 100) * 255 / 100
        applyTo(root, alpha)
    }

    private fun applyTo(view: View, alpha: Int) {
        if (isSurface(view)) {
            // mutate() keeps one card's alpha from leaking into another view that shares
            // the same drawable constant state (all cards use the same background drawable).
            view.background?.mutate()?.alpha = alpha
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) applyTo(view.getChildAt(index), alpha)
        }
    }

    /**
     * Text, icons and interactive controls are deliberately left alone: the setting is
     * meant to let the wallpaper through, not to make the interface harder to read or hit.
     */
    private fun isSurface(view: View): Boolean = when (view) {
        is TextView, is ImageView, is SeekBar, is CompoundButton,
        is Button, is EditText, is ProgressBar -> false
        else -> view.background != null
    }
}
