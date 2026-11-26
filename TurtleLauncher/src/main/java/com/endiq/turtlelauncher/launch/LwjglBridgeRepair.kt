package com.endiq.turtlelauncher.launch

import android.content.Context
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.utils.path.PathManager
import net.endiq.launcher.Tools
import java.io.File

/**
 * Makes sure the Android-patched LWJGL jars are really on the game classpath.
 *
 * Everything about LWJGL on this launcher runs through `lwjgl3/lwjgl-glfw-classes.jar`: it holds
 * the patched `org.lwjgl.system.Library` that knows Android's native loader, plus the
 * `META-INF/linux/arm64/...` markers for the app's own ABI-built `liblwjgl.so`. Without it the
 * game falls back to the stock Mojang LWJGL jar, which looks for natives Mojang never publishes
 * for linux/arm64 - the launch dies with:
 *
 * ```
 * java.lang.UnsatisfiedLinkError: Failed to locate library: liblwjgl.so
 * ```
 *
 * `lwjgl-sdl-3.4.1.jar` is restored for the same reason: 26.3's `org.lwjgl.sdl.*` has to be the
 * build this bridge was compiled against, otherwise `SDL_LogOutputFunction` links against a
 * `org.lwjgl.system.CallbackDescriptor` constructor that the loaded core jar does not have and the
 * game dies in `RenderSystem.initBackendSystem` with
 * `NoSuchMethodError: CallbackDescriptor.<init>(Class, Class, Method)`. See
 * `Tools.getLwjglAbiOverrideClasspath` and `tests/check_lwjgl_java_pair.py`.
 *
 * [com.endiq.turtlelauncher.feature.unpack.UnpackComponentsTask] only copies the component when
 * its version stamp changes, so a jar that was deleted or half-written (interrupted first run,
 * storage cleanup, restored backup) stayed missing on every later launch. Re-copying it here is
 * one `stat` when the file is intact, and self-heals the launch when it is not.
 */
object LwjglBridgeRepair {
    private const val TAG = "LwjglBridgeRepair"
    private const val ASSET_DIR = "components/lwjgl3"
    private const val VERSION_FILE = "version"

    /** Bridge first: its `org.lwjgl.system.*` must win over anything else on the classpath. */
    private val PACKAGED_JARS = arrayOf(Tools.BRIDGE_JAR, Tools.SDL_JAR)

    /**
     * True when at least one jar had to be restored. Safe to call on the UI thread before a
     * launch: it does no work at all when the installed jars match the packaged ones.
     */
    @JvmStatic
    fun ensureInstalled(context: Context): Boolean {
        val dir = File(PathManager.DIR_GAME_HOME, "lwjgl3")
        val missing = PACKAGED_JARS.filter { name -> !isIntact(context, dir, name) }
        if (missing.isEmpty()) return false

        dir.mkdirs()
        var restored = false
        for (name in missing) {
            val ok = runCatching {
                Tools.copyAssetFile(context, "$ASSET_DIR/$name", dir.absolutePath, true)
                Logging.i(TAG, "Restored $name (${File(dir, name).length()} bytes) into ${dir.absolutePath}")
                true
            }.onFailure { error ->
                Logging.e(TAG, "Could not restore $name - LWJGL native or SDL loading may fail", error)
            }.getOrDefault(false)
            restored = restored || ok
        }
        if (restored) {
            // Keep the unpack stamp in sync, otherwise the next component check wipes the folder.
            runCatching {
                Tools.copyAssetFile(context, "$ASSET_DIR/$VERSION_FILE", dir.absolutePath, true)
            }
        }
        return restored
    }

    private fun isIntact(context: Context, dir: File, name: String): Boolean {
        val jar = File(dir, name)
        // openFd only works for an uncompressed asset; when it is unavailable we cannot compare
        // sizes and accept any non-empty jar instead of re-copying megabytes on every launch.
        val expectedSize = runCatching {
            context.assets.openFd("$ASSET_DIR/$name").use { it.length }
        }.getOrNull()
        return jar.isFile && jar.length() > 0L &&
            (expectedSize == null || expectedSize == jar.length())
    }
}
