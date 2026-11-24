package com.endiq.turtlelauncher.utils.anim

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import java.lang.ref.WeakReference

/**
 * Applies the "page opacity" setting to the launcher's surfaces instead of the whole
 * screen.
 *
 * The old implementation called `View.setAlpha()` on the fragment container, which faded
 * every label, slider and switch along with the background. Drawable alpha paints only
 * the part of a view that the drawable covers, so cards and panels become translucent
 * while the text, sliders and toggles drawn on top of them stay fully opaque.
 *
 * The value is also remembered here, not just re-read from settings: a screen that is built
 * later (the home screen coming back from settings, a page whose rows bind after the fragment
 * resumed) asks for [refresh] and gets the same translucency instead of snapping back to fully
 * opaque. While a page is translucent its view tree is watched, so content added by a later
 * layout pass is faded too - that late content is what used to leave the page opaque again.
 */
object PageTransparency {
    /** 0..255. 255 means fully opaque, and nothing needs to be watched. */
    @Volatile
    private var currentAlpha: Int = 255

    private var watchedRoot: WeakReference<View>? = null

    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
        watchedRoot?.get()?.let { root -> applyTo(root, currentAlpha) }
    }

    /** [percent] is the stored page-opacity value: 100 = fully opaque, 0 = invisible. */
    @JvmStatic
    fun apply(root: View?, percent: Int) {
        val alpha = percent.coerceIn(0, 100) * 255 / 100
        currentAlpha = alpha
        if (root == null) return
        applyTo(root, alpha)
        if (alpha >= 255) unwatch() else watch(root)
    }

    /**
     * Re-applies the remembered value to a view tree that was built after the slider moved.
     * Safe to call on every screen: it never changes the stored setting.
     */
    @JvmStatic
    fun refresh(root: View?) {
        root ?: return
        applyTo(root, currentAlpha)
        if (currentAlpha in 1..254) watch(root) else unwatch()
    }

    private fun watch(root: View) {
        val previous = watchedRoot?.get()
        if (previous != null && previous !== root) unwatch()
        watchedRoot = WeakReference(root)
        val observer = root.viewTreeObserver
        if (observer.isAlive) {
            observer.removeOnGlobalLayoutListener(layoutListener)
            observer.addOnGlobalLayoutListener(layoutListener)
        }
        // Rows and cards added later in the same frame (adapters, async content) are picked up
        // by the layout listener above; this pass covers content already inflated.
        root.post { applyTo(root, currentAlpha) }
    }

    private fun unwatch() {
        watchedRoot?.get()?.viewTreeObserver?.let { observer ->
            if (observer.isAlive) observer.removeOnGlobalLayoutListener(layoutListener)
        }
        watchedRoot = null
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
