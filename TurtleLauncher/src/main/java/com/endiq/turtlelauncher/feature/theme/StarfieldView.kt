package com.endiq.turtlelauncher.feature.theme

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.ui.miuix.MiuixColorScheme
import kotlin.math.sin

/**
 * The End Void theme's slow drifting stars.
 *
 * Deliberately cheap: ~48 dots drawn with one [Paint], positions derived from the index and a
 * single clock value (no per-star objects, no animator ticks waking the CPU through Choreographer
 * when the screen is not visible). Motion is time-based, so it stays smooth on a 60/90/120 Hz
 * panel and stops the moment the view is detached.
 *
 * Layouts never reference this view: [ThemeEffects] inserts it behind the content of a screen when
 * the active pack asks for a starfield, and takes it away again otherwise.
 */
class StarfieldView @JvmOverloads constructor(
    context: Context,
    private val scheme: MiuixColorScheme? = null,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Full loop of a star, in milliseconds: deliberately slow ("bay chậm"). */
    private val loopMillis = 26_000L

    private val starCount = 48
    private val seeds = FloatArray(starCount * 4) { index ->
        val star = index / 4
        when (index % 4) {
            0 -> fract(star * 0.6180339f)          // x
            1 -> fract(star * 0.4142135f + 0.17f)  // starting y
            2 -> 0.6f + fract(star * 0.7320508f) * 1.8f // radius in px at density 1
            else -> 0.25f + fract(star * 0.2718281f) * 0.75f // alpha
        }
    }

    private var startTime = 0L

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        paint.color = blendStar()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startTime = SystemClock.uptimeMillis()
        if (animationsEnabled) postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val elapsed = if (animationsEnabled) (now - startTime) else 0L
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return

        val accent = scheme?.primary ?: DEFAULT_STAR
        paint.color = withAlpha(accent, 0xC0)

        for (star in 0 until starCount) {
            val baseX = seeds[star * 4]
            val baseY = seeds[star * 4 + 1]
            val radius = seeds[star * 4 + 2] * density
            val alpha = seeds[star * 4 + 3]

            // Slow upward drift with a wrapped loop, plus a tiny horizontal sway.
            val travelled = ((elapsed % loopMillis) / loopMillis.toFloat() + baseY) % 1f
            val y = (1f - travelled) * height
            val sway = sin((elapsed / 7000f) + star) * 6f * density
            val x = baseX * width + sway

            paint.alpha = (alpha * 255).toInt().coerceIn(24, 255)
            canvas.drawCircle(x, y, radius.coerceAtLeast(0.8f), paint)

            // Every fourth star gets a faint halo so the field does not look like noise.
            if (star % 4 == 0) {
                paint.alpha = (alpha * 60).toInt().coerceIn(12, 255)
                canvas.drawCircle(x, y, radius * 3.5f, paint)
            }
        }

        if (animationsEnabled) postInvalidateOnAnimation()
    }

    private val animationsEnabled: Boolean
        get() = runCatching { AllSettings.animation.getValue() }.getOrDefault(true)

    private val density: Float get() = resources.displayMetrics.density

    private fun blendStar(): Int = withAlpha(scheme?.primary ?: DEFAULT_STAR, 0xB0)

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun fract(value: Float): Float = value - kotlin.math.floor(value)

    companion object {
        /** Fallback star colour for the case where the view is built without a scheme. */
        private val DEFAULT_STAR = 0xFFB9A8FF.toInt()

        /** Tag used by [ThemeEffects] to find an already-installed overlay. */
        const val TAG_VIEW: String = "turtle_theme_starfield"
    }
}
