package com.endiq.turtlelauncher.launch

import android.content.Context
import com.endiq.turtlelauncher.feature.log.LatestLogResolver
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.mod.ModScanCache
import com.endiq.turtlelauncher.feature.theme.ThemeManager
import com.endiq.turtlelauncher.feature.version.VersionsManager
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.utils.path.PathManager
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Warms everything the next launch will need while the launcher is still idle on the home
 * screen, so pressing "Play" does not pay for it.
 *
 * Three separate things happen here, all on one background thread and all best-effort:
 *
 *  1. **Java libraries (LWJGL + natives).** The runtime archives and native libraries of the
 *     last used version are streamed once into the OS page cache, so the game JVM opens them
 *     from RAM instead of slow flash. Nothing is loaded into the launcher's own process - no
 *     native library is `System.load`ed here, because loading game natives in the launcher
 *     process is exactly what breaks a launch.
 *
 *  2. **JIT warmup.** The launcher's own launch path (JVM argument merging/parsing, path
 *     resolution, log lookup, theme resolution) runs once with representative inputs so ART has
 *     compiled those methods before the user actually launches. The game JVM's own code sharing
 *     is `CdsArchiveManager`'s job (CDS archives); this class does not duplicate it.
 *
 *  3. **Mod scan cache.** The current instance's mods folder is fingerprinted, so opening Mod
 *     management (or the post-crash analysis) reads the cache instead of re-hashing every jar.
 *
 * Everything is guarded by [AllSettings.preloadJavaLibraries] / [AllSettings.jitWarmupEnabled]
 * and by a one-shot flag: the work runs at most once per process and never blocks startup.
 */
object LaunchPrewarm {
    private const val TAG = "LaunchPrewarm"

    /** Files are read in 256 KB blocks: enough to keep storage busy without a big allocation. */
    private const val READ_BUFFER = 256 * 1024

    /** Never warm more than this per process - a 2 GB library tree would flush the page cache. */
    private const val MAX_WARM_BYTES = 512L * 1024 * 1024

    private val started = AtomicBoolean(false)

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "launch-prewarm").apply { isDaemon = true }
    }

    @JvmStatic
    fun prewarmAsync(context: Context) {
        if (!started.compareAndSet(false, true)) return
        executor.execute {
            runCatching { prewarm(context.applicationContext) }
                .onFailure { Logging.e(TAG, "Prewarm failed (ignored)", it) }
        }
    }

    /** Synchronous body; also usable from a settings "Warm up now" action. */
    @JvmStatic
    fun prewarm(context: Context) {
        val preloadLibraries = runCatching {
            AllSettings.preloadJavaLibraries.getValue()
        }.getOrDefault(true)
        if (preloadLibraries) warmJavaLibraries()

        val jitWarmup = runCatching { AllSettings.jitWarmupEnabled.getValue() }.getOrDefault(true)
        if (jitWarmup) warmLaunchPath()

        runCatching { warmModScanCache() }
            .onFailure { Logging.e(TAG, "Mod scan cache warm-up failed (ignored)", it) }
    }

    /**
     * Streams the current runtime's jars/archives through the page cache. File contents are never
     * retained, so this cannot leak memory or storage.
     */
    private fun warmJavaLibraries() {
        val version = runCatching { VersionsManager.getCurrentVersion() }.getOrNull()
        val roots = buildList {
            runCatching {
                val runtimeDir = File(PathManager.DIR_MULTIRT_HOME)
                if (runtimeDir.isDirectory) addAll(runtimeDir.listFiles().orEmpty().filter { it.isDirectory })
            }
            runCatching {
                val nativesDir = File(PathManager.DIR_CACHE, "natives")
                if (nativesDir.isDirectory) add(nativesDir)
            }
        }
        if (roots.isEmpty()) {
            Logging.i(TAG, "No runtime directories to preload yet")
            return
        }

        var warmed = 0L
        for (root in roots) {
            warmed += warmDirectory(root, MAX_WARM_BYTES - warmed)
            if (warmed >= MAX_WARM_BYTES) break
        }
        Logging.i(
            TAG,
            "Preloaded ${warmed / (1024 * 1024)} MB of Java/native libraries for " +
                (version?.getVersionName() ?: "the last version")
        )
    }

    private fun warmDirectory(root: File, budget: Long): Long {
        if (budget <= 0) return 0
        var used = 0L
        val buffer = ByteArray(READ_BUFFER)
        root.walkTopDown()
            .filter { file -> file.isFile && file.length() > 0 && isWarmCandidate(file) }
            .forEach { file ->
                if (used >= budget) return@forEach
                runCatching {
                    file.inputStream().use { input ->
                        while (used < budget) {
                            if (input.read(buffer) <= 0) break
                            used += buffer.size
                        }
                    }
                }
            }
        return used
    }

    /** Only what a launch actually reads: jars, native libraries and runtime archives. */
    private fun isWarmCandidate(file: File): Boolean {
        val name = file.name.lowercase()
        return name.endsWith(".jar") || name.endsWith(".so") || name.endsWith(".zip") ||
            name.endsWith(".tar.xz") || name.endsWith(".xz")
    }

    /**
     * Runs the launch argument/path code once so ART has compiled it. Inputs are representative
     * defaults; nothing is written and failures are ignored.
     */
    private fun warmLaunchPath() {
        runCatching {
            JvmArguments.merge("-Xmx2G -XX:+UseSerialGC", "-Xms512M -XX:+UseG1GC -Djava.library.path=.")
            JvmArguments.parse("-Xmx2G -XX:+UseG1GC -Dfoo=\"bar baz\"")
            File(PathManager.DIR_DATA, "versions").listFiles()
            File(PathManager.DIR_CACHE, "assets").listFiles()
            LatestLogResolver.resolveLatestLogFile()
            ThemeManager.scheme(null)
        }.onFailure { Logging.e(TAG, "JIT warmup of the launch path failed (ignored)", it) }
    }

    private fun warmModScanCache() {
        val enabled = runCatching { AllSettings.modScanCacheEnabled.getValue() }.getOrDefault(true)
        if (!enabled) return
        val version = runCatching { VersionsManager.getCurrentVersion() }.getOrNull() ?: return
        val mods = File(version.getGameDir(), "mods")
        if (!mods.isDirectory) return
        ModScanCache.scan(mods)
        Logging.i(TAG, "Mod scan cache warmed for ${version.getVersionName()}")
    }
}
