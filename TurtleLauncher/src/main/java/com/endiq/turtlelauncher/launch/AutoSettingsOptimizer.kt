package com.endiq.turtlelauncher.launch

import android.content.Context
import android.opengl.EGL14
import android.os.Build
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.renderer.Renderers
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.utils.platform.BatterySaverManager
import com.endiq.turtlelauncher.utils.platform.ThermalManager
import net.endiq.launcher.Tools
import net.endiq.launcher.prefs.LauncherPreferences

object AutoSettingsOptimizer {
    private const val TAG = "AutoSettingsOptimizer"

    private const val LOW_TIER_RAM_MB = 3072
    private const val HIGH_TIER_RAM_MB = 6144

    /**
     * @param context       Activity context
     * @param mcVersionId   Minecraft version string, e.g. "26.1.2" (empty = legacy path)
     */
    fun apply(context: Context, mcVersionId: String = "") {
        // One profile for both halves, so the renderer choice and the resolution
        // level can never be based on two different readings of the device.
        val profile = buildProfile(context, mcVersionId)
        applyGraphics(context, profile)
        // Fast Boot must not cache a cold launch's thermal decision for all later sessions.
        applyPerformanceTier(context, profile)
        if (mcVersionId.isNotEmpty()) {
            AllSettings.lastOptimizedVersion.put(mcVersionId).save()
        }
    }

    /**
     * Everything the graphics policy is allowed to look at, gathered once.
     * Kept separate from the decisions (which live in [GraphicsTuning]) so the
     * decisions stay unit-testable without an Android device.
     */
    internal fun buildProfile(context: Context, mcVersionId: String): GraphicsTuning.DeviceProfile {
        @Suppress("DEPRECATION")
        val display = if (Build.VERSION.SDK_INT >= 30) context.display
            else (context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager)?.defaultDisplay
        val metrics = context.resources.displayMetrics
        val powerSaving = BatterySaverManager.isPowerSaveMode(context)
        return GraphicsTuning.DeviceProfile(
            gpuName = detectGpu(),
            totalRamMb = Tools.getTotalDeviceMemory(context).toLong(),
            screenPixels = metrics.widthPixels.toLong() * metrics.heightPixels.toLong(),
            refreshRateHz = display?.refreshRate?.toInt() ?: 60,
            hasVulkan = Tools.checkVulkanSupport(context.packageManager),
            minecraftVersion = mcVersionId,
            benchmarkScore = AllSettings.lastGraphicsBenchmark.getValue()
                .substringBefore('|').toIntOrNull()?.takeIf { it > 0 },
            severeThermal = ThermalManager.isSeverelyThrottled(context),
            powerSaving = powerSaving
        )
    }

    private fun applyGraphics(context: Context, profile: GraphicsTuning.DeviceProfile) {
        val isMC26Plus = LaunchArgs.isMinecraftVersionAtLeast(profile.minecraftVersion, 26, 1, 0)
        val cpuCores = Runtime.getRuntime().availableProcessors()
        // Queried via ActivityManager, not a live GL context (none exists yet at this point -
        // the game hasn't started) - the standard, correct way to get this ahead of time.
        val glEsVersionPacked = (context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)
            ?.deviceConfigurationInfo?.reqGlEsVersion ?: 0
        val glEsVersion = "${glEsVersionPacked shr 16}.${glEsVersionPacked and 0xffff}"
        Logging.i(TAG, "Detected GPU: ${profile.gpuName} | Manufacturer: ${Build.MANUFACTURER} | Android ${Build.VERSION.SDK_INT} | " +
                "MC26+: $isMC26Plus | Refresh rate: ${profile.refreshRateHz}Hz | Resolution: ${profile.screenPixels}px | " +
                "CPU cores: $cpuCores | OpenGL ES: $glEsVersion | benchmark: ${profile.benchmarkScore ?: "not run"}")

        val previous = AllSettings.lastAutoGraphics.getValue().split('|')
        val currentRenderer = AllSettings.renderer.getValue()
        val currentDriver = AllSettings.driver.getValue()
        // Renderer and driver are tracked separately: a user who pinned the driver can
        // still benefit from an automatic renderer choice, and vice versa. The old code
        // treated them as one switch and gave up on both.
        val mayChangeRenderer = PerformancePolicy.mayAutoTune(
            currentRenderer, previous.getOrNull(0)?.takeIf { it.isNotBlank() }, AllSettings.renderer.defaultValue
        )
        val mayChangeDriver = PerformancePolicy.mayAutoTune(
            currentDriver, previous.getOrNull(1)?.takeIf { it.isNotBlank() }, AllSettings.driver.defaultValue
        )

        val available = runCatching {
            Renderers.getCompatibleRenderers(context).second.map { it.getRendererId() }.toSet()
        }.getOrElse {
            Logging.w(TAG, "Could not enumerate compatible renderers: ${it.message}")
            null
        }

        val recommendation = GraphicsTuning.recommend(
            profile = profile,
            currentRenderer = currentRenderer,
            currentDriver = currentDriver,
            mayChangeRenderer = mayChangeRenderer,
            mayChangeDriver = mayChangeDriver,
            isAvailable = { id -> available?.contains(id) ?: false },
            inVersionRange = { id, version -> GraphicsTuning.withinCatalogRange(id, version) }
        )

        var appliedRenderer: String? = null
        var appliedDriver: String? = null
        recommendation.rendererUniqueId?.let { uuid ->
            if (currentRenderer != uuid) {
                AllSettings.renderer.put(uuid).save()
                appliedRenderer = uuid
            }
        }
        recommendation.driver?.let { driver ->
            if (currentDriver != driver) {
                AllSettings.driver.put(driver).save()
                appliedDriver = driver
            }
        }
        Logging.i(TAG, "Auto graphics: ${recommendation.reason}; " +
                "renderer=${appliedRenderer ?: "kept $currentRenderer"}, driver=${appliedDriver ?: "kept $currentDriver"}")

        // Store only the fields this run actually owns; a field left at its user-chosen
        // value stays blank in the snapshot so it is never treated as an automatic pick.
        val snapshot = "${appliedRenderer ?: previous.getOrNull(0).orEmpty()}|" +
            (appliedDriver ?: previous.getOrNull(1).orEmpty())
        if (snapshot != "|" && AllSettings.lastAutoGraphics.getValue() != snapshot) {
            AllSettings.lastAutoGraphics.put(snapshot).save()
        }
    }

    private fun applyPerformanceTier(context: Context, profile: GraphicsTuning.DeviceProfile) {
        val appliedRam: Int
        if (!AllSettings.autoRamCalculator.getValue()) {
            appliedRam = AllSettings.ramAllocation.value.getValue()
            Logging.i(TAG, "Auto RAM Calculator disabled in Phone Settings: leaving RAM at ${appliedRam}MB")
        } else {
            val bestRam = presetAdjustedRam(context, LauncherPreferences.findBestRAMAllocation(context))

            val currentRam = AllSettings.ramAllocation.value.getValue()
            val lastAutoRam = AllSettings.lastAutoRamAllocation.getValue()
            if (lastAutoRam == -1 || currentRam == lastAutoRam) {
                AllSettings.ramAllocation.value.put(bestRam).save()
                AllSettings.lastAutoRamAllocation.put(bestRam).save()
                appliedRam = bestRam
            } else {
                Logging.i(TAG, "Skipping RAM auto-tune: user manually set ${currentRam}MB (last auto pick was ${lastAutoRam}MB)")
                appliedRam = currentRam
            }
        }

        val powerSaving = profile.powerSaving
        val throttled = ThermalManager.isThrottled(context) || powerSaving
        val severelyThrottled = profile.severeThermal
        if (throttled) {
            Logging.i(TAG, "Capping FPS-boost profile regardless of RAM tier (thermal severe=$severelyThrottled, batterySaver=$powerSaving)")
        }

        val auto = AutoProfile()
        // Resolution now comes from the shared graphics policy: benchmark score when it
        // has been run, RAM tier otherwise, minus penalties for large/high-refresh
        // displays. Still clamped to the same 60..100% range as before.
        auto.tune(AllSettings.resolutionRatio, GraphicsTuning.resolutionPercent(profile))
        // Never equate a large RAM capacity with permission to uncap the GPU or pre-touch
        // a large heap. These features remain available as explicit user choices.
        auto.tune(AllSettings.unlimitedFps, false)
        auto.tune(AllSettings.lowLatencyRendering, false)
        auto.tune(AllSettings.framePacing, false)
        auto.tune(AllSettings.adaptiveFrameTiming, true)
        auto.save()
        Logging.i(TAG, "Sustainable auto profile: RAM=${appliedRam}MB, thermal capped=$throttled; manual overrides preserved")
    }

    // ── Per-section recommendations ──────────────────────────────────────────

    /**
     * Graphics & renderer benchmark result: picks the renderer/driver and the render
     * resolution only. Phone/RAM/optimization flags are deliberately left alone - those
     * screens own their own benchmark rows.
     */
    fun applyGraphicsRecommendation(context: Context, mcVersionId: String = "") {
        val profile = buildProfile(context, mcVersionId)
        applyGraphics(context, profile)
        val auto = AutoProfile(force = true)
        auto.tune(AllSettings.resolutionRatio, GraphicsTuning.resolutionPercent(profile))
        auto.save()
    }

    /**
     * Phone settings benchmark result: hands the measured core count and RAM figure to the
     * settings on that page. Renderer, resolution and frame-pacing toggles are untouched.
     */
    fun applyPhoneRecommendation(
        context: Context,
        result: com.endiq.turtlelauncher.feature.benchmark.DeviceBenchmark.Result
    ) {
        if (result.failed) return

        // The benchmark was run on purpose and the user pressed "apply", so this is the one
        // moment where the measured values are allowed to win over older manual choices: the
        // whole Phone settings page (cores, memory, JNI) follows the score it just measured.
        val high = result.multiThreadScore >= 1200
        val mid = result.multiThreadScore >= 400
        val lowMemory = result.totalRamBytes > 0 &&
            result.freeRamBytes.toDouble() / result.totalRamBytes.toDouble() < 0.25

        val auto = AutoProfile(force = true)
        // CPU
        auto.tune(AllSettings.autoDetectCores, false)
        auto.tune(AllSettings.manualCoreOverride, true)
        auto.tune(AllSettings.manualCoreCount, result.suggestedCores)
        auto.tune(AllSettings.perInstanceCpuOverride, false)
        auto.tune(AllSettings.advancedCpuTuning, true)
        auto.tune(AllSettings.bigCoreAffinity, result.suggestedCores < result.cores)
        auto.tune(AllSettings.schedulerTuning, high || mid)
        // Memory
        auto.tune(AllSettings.autoRamCalculator, true)
        auto.tune(AllSettings.equalHeapSizes, true)
        auto.tune(AllSettings.ramPreset, if (high) "high" else if (mid) "balanced" else "low")
        auto.tune(AllSettings.memoryPressureMonitor, lowMemory)
        auto.tune(AllSettings.gcStatistics, false)
        // JNI
        auto.tune(AllSettings.jniBatching, true)
        auto.tune(AllSettings.jniCachedReferences, true)
        auto.tune(AllSettings.nativeObjectPooling, high)
        auto.tune(AllSettings.reducedJniCalls, high)
        auto.save()

        // RAM itself is stored outside AutoProfile because its "manual override" marker lives
        // in lastAutoRamAllocation rather than in the tuning snapshot.
        val bestRam = presetAdjustedRam(context, LauncherPreferences.findBestRAMAllocation(context))
        AllSettings.ramAllocation.value.put(bestRam).save()
        AllSettings.lastAutoRamAllocation.put(bestRam).save()

        Logging.i(TAG, "Phone recommendation applied: ${result.suggestedCores} cores, ${bestRam}MB RAM, tier=${result.tier}")
    }

    /**
     * Optimization benchmark result: turns the toggles on the Optimization page according to
     * the measured frame-pacing jitter. Graphics, renderer and memory-allocation settings are
     * not part of this scope.
     */
    fun applyOptimizationRecommendation(
        context: Context,
        result: com.endiq.turtlelauncher.feature.benchmark.OptimizationBenchmark.Result
    ) {
        if (result.failed) return
        val stable = result.tier == com.endiq.turtlelauncher.feature.benchmark.OptimizationBenchmark.Tier.STABLE
        val unstable = result.tier == com.endiq.turtlelauncher.feature.benchmark.OptimizationBenchmark.Tier.UNSTABLE
        val unavailable = result.tier == com.endiq.turtlelauncher.feature.benchmark.OptimizationBenchmark.Tier.UNAVAILABLE

        // Explicit apply: every switch on the Optimization page is written, so the page and
        // the score can never disagree. Frame pacing is the only switch whose value is
        // derived purely from the jitter measurement; unlimited FPS stays off because
        // uncapping the GPU is never a safe automatic choice.
        val auto = AutoProfile(force = true)
        auto.tune(AllSettings.unlimitedFps, false)
        auto.tune(AllSettings.lowLatencyRendering, stable)
        auto.tune(AllSettings.framePacing, !stable)
        auto.tune(AllSettings.frameSkipping, unstable)
        auto.tune(AllSettings.adaptiveFrameTiming, !unstable)
        auto.tune(AllSettings.autoMemoryCleanup, unstable)
        auto.tune(AllSettings.rendererShaderCacheEnabled, !unavailable)
        auto.tune(AllSettings.rendererDebugLogging, false)
        auto.save()
        Logging.i(TAG, "Optimization recommendation applied for tier ${result.tier} (force)")
    }

    /**
     * Writes the values this launcher tuned automatically, and only those: a setting whose
     * current value differs from the last automatic pick stays untouched (manual choice).
     */
    private class AutoProfile(
        /**
         * True only for an explicit "apply" from a benchmark result dialog: that press is
         * the user asking for the measured values, so they win over older manual picks.
         */
        private val force: Boolean = false
    ) {
        private val entries: MutableMap<String, String> = AllSettings.lastAutoPerformanceProfile.getValue()
            .split(';')
            .mapNotNull { entry ->
                if ('=' !in entry) null else entry.substringBefore('=') to entry.substringAfter('=')
            }
            .toMap().toMutableMap()

        fun <T> tune(setting: com.endiq.turtlelauncher.setting.unit.AbstractSettingUnit<T>, value: T) {
            val current = setting.getValue().toString()
            val allowed = force || PerformancePolicy.mayAutoTune(
                current, entries[setting.key], setting.defaultValue.toString()
            )
            if (allowed) {
                if (current != value.toString()) setting.put(value).save()
                entries[setting.key] = value.toString()
            }
        }

        fun save() {
            val snapshot = entries.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }
            if (AllSettings.lastAutoPerformanceProfile.getValue() != snapshot) {
                AllSettings.lastAutoPerformanceProfile.put(snapshot).save()
            }
        }
    }

    private fun presetAdjustedRam(context: Context, baseline: Int): Int {
        val totalRamMb = Tools.getTotalDeviceMemory(context)
        return when (AllSettings.ramPreset.getValue()) {
            "low" -> (baseline * 0.7).toInt().coerceAtLeast(384)
            "high" -> (baseline * 1.4).toInt().coerceAtMost((totalRamMb * 0.75).toInt()).coerceAtLeast(baseline)
            else -> baseline // "balanced" or "custom"
        }
    }

    @Volatile
    private var cachedGlGpu: String? = null

    /**
     * Best-effort GPU name for the auto-picker: the live `GL_RENDERER`/`GL_VENDOR`
     * pair when an EGL probe succeeds, otherwise the weaker `ro.hardware.egl`
     * property. Internal so the settings screen can show the same value the picker
     * used, instead of running a second EGL probe of its own.
     */
    internal fun detectGpu(): String {
        cachedGlGpu?.let { return it }
        queryGlRenderer()?.let {
            cachedGlGpu = it
            return it
        }
        return try {
            // queryGlRenderer owns a balanced EGL probe. Do not initialize another
            // display here and leak its reference on the fallback path.
            val renderer = System.getProperty("ro.hardware.egl") ?: ""
            "$renderer ${Build.HARDWARE}"
        } catch (e: Exception) {
            Build.HARDWARE
        }
    }

    private fun queryGlRenderer(): String? {
        val previousDisplay = EGL14.eglGetCurrentDisplay()
        val previousContext = EGL14.eglGetCurrentContext()
        val previousRead = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        val previousDraw = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        var initialized = false
        var display = EGL14.EGL_NO_DISPLAY
        var context = EGL14.EGL_NO_CONTEXT
        var surface = EGL14.EGL_NO_SURFACE
        return try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return null
            val version = IntArray(2)
            if (display != previousDisplay) {
                if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null
                initialized = true
            }

            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) return null
            val config = configs[0] ?: return null

            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
            if (context == EGL14.EGL_NO_CONTEXT) return null

            val pbufferAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            surface = EGL14.eglCreatePbufferSurface(display, config, pbufferAttribs, 0)
            if (surface == EGL14.EGL_NO_SURFACE) return null

            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return null

            val renderer = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_RENDERER)
            val vendor = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_VENDOR)
            if (renderer.isNullOrBlank()) null else "$vendor $renderer"
        } catch (e: Exception) {
            Logging.w(TAG, "GL_RENDERER probe failed, falling back to weaker GPU signals: ${e.message}")
            null
        } finally {
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                if (previousDisplay != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(previousDisplay, previousDraw, previousRead, previousContext)
                }
                if (initialized) EGL14.eglTerminate(display)
            }
        }
    }
}
