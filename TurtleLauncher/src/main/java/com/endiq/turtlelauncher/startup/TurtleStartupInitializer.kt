package com.endiq.turtlelauncher.startup

import android.content.Context
import androidx.startup.Initializer
import com.endiq.turtlelauncher.feature.log.NativeCrashCapture
import com.endiq.turtlelauncher.feature.theme.ThemeManager
import com.endiq.turtlelauncher.feature.turtle.AnrWatchdog
import com.endiq.turtlelauncher.launch.LaunchPrewarm

class TurtleStartupInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        // Re-assert the user's theme mode (TurtleApplication does this first thing in
        // onCreate; this initializer still runs before the first Activity).
        ThemeManager.applyNightMode()

        // Material You dynamic color used to be applied here. It repaints Material widgets from
        // the wallpaper, which now fights the theme studio's packs and custom accent, so the
        // launcher keeps its own palette instead.
        AnrWatchdog.start()

        // Preload/warmup: Java runtime libraries for the last played version and the launcher's
        // own launch code paths, on a worker thread so app start never waits for it.
        LaunchPrewarm.prewarmAsync(context)

        NativeCrashCapture.checkAndReport(context)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
