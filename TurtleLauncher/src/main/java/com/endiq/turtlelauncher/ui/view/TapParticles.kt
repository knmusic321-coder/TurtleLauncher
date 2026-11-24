package com.endiq.turtlelauncher.ui.view

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.ui.miuix.MiuixTheme
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/**
 * A short burst of coloured grains at the point a finger touched.
 *
 * Sits above the launcher content as a full-screen view that is not clickable, so every tap,
 * scroll and drag still reaches the widget underneath. Particles are plain circles moved with a
 * simple gravity step and drawn by one [Paint]; the view stops asking for frames as soon as the
 * last grain dies, and it does nothing at all when Settings -> Launcher turns animations off.
 */
class TapParticleView(context: Context) : View(context) {

    private class Grain(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        var life: Float,
        var maxLife: Float,
        var radius: Float,
        var color: Int
    )

    private val grains = ArrayList<Grain>(16)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val random = Random()

    private var lastFrame = 0L

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }

    /** [x] and [y] are in this view's coordinates. */
    fun burst(x: Float, y: Float) {
        if (!isAttachedToWindow || width == 0) return

        val density = resources.displayMetrics.density
        val colors = MiuixTheme.colors(context)
        val palette = intArrayOf(colors.primary, colors.onSurface, colors.onPrimary)

        val count = 12
        for (index in 0 until count) {
            val angle = Math.PI * 2.0 * index / count + (random.nextDouble() - 0.5) * 0.6
            val speed = (95 + random.nextInt(140)) * density
            grains += Grain(
                x = x,
                y = y,
                vx = (cos(angle) * speed).toFloat(),
                vy = (sin(angle) * speed - 70f * density).toFloat(),
                life = 0f,
                maxLife = 0.36f + random.nextFloat() * 0.22f,
                radius = (1.6f + random.nextFloat() * 2.2f) * density,
                color = palette[index % palette.size]
            )
        }
        // A frantic tapper must not grow the list without bound.
        if (grains.size > 96) grains.subList(0, grains.size - 96).clear()

        lastFrame = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        if (grains.isEmpty()) return

        val now = SystemClock.uptimeMillis()
        val step = ((now - lastFrame).coerceIn(0L, 40L)) / 1000f
        lastFrame = now
        val gravity = 900f * resources.displayMetrics.density

        val iterator = grains.iterator()
        while (iterator.hasNext()) {
            val grain = iterator.next()
            grain.life += step
            if (grain.life >= grain.maxLife) {
                iterator.remove()
                continue
            }
            grain.vy += gravity * step
            grain.x += grain.vx * step
            grain.y += grain.vy * step

            val fade = 1f - grain.life / grain.maxLife
            paint.color = grain.color
            paint.alpha = (fade * 230f).toInt().coerceIn(0, 255)
            canvas.drawCircle(grain.x, grain.y, grain.radius * (0.45f + 0.55f * fade), paint)
        }

        if (grains.isNotEmpty()) postInvalidateOnAnimation() else paint.alpha = 255
    }
}

/** Installs and triggers [TapParticleView] for a launcher Activity. */
object TapParticles {
    /** Tag used to find an overlay that is already installed. */
    const val TAG_VIEW: String = "turtle_tap_particles"

    @JvmStatic
    fun install(activity: Activity?): TapParticleView? {
        activity ?: return null
        return runCatching {
            val content = activity.findViewById(android.R.id.content) as? ViewGroup ?: return null
            (content.findViewWithTag<View>(TAG_VIEW) as? TapParticleView)?.let { return it }
            val view = TapParticleView(activity)
            view.tag = TAG_VIEW
            content.addView(
                view,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            view
        }.getOrNull()
    }

    /**
     * Bursts at a screen-wide touch point (raw coordinates), so the caller does not have to
     * translate between windows.
     */
    @JvmStatic
    fun burst(activity: Activity?, rawX: Float, rawY: Float) {
        activity ?: return
        if (!animationsEnabled()) return
        val overlay = install(activity) ?: return
        val location = IntArray(2)
        overlay.getLocationInWindow(location)
        overlay.burst(rawX - location[0], rawY - location[1])
    }

    private fun animationsEnabled(): Boolean =
        runCatching { AllSettings.animation.getValue() }.getOrDefault(true)
}
