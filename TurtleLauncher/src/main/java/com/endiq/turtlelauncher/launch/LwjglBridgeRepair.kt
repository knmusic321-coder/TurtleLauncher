package com.endiq.turtlelauncher.launch

import android.content.Context
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.utils.path.PathManager
import net.endiq.launcher.Tools
import java.io.File

/**
 * Makes sure the Android-patched LWJGL bridge jar is really on the game classpath.
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
 * [com.endiq.turtlelauncher.feature.unpack.UnpackComponentsTask] only copies the component when
 * its version stamp changes, so a jar that was deleted or half-written (interrupted first run,
 * storage cleanup, restored backup) stayed missing on every later launch. Re-copying it here is
 * one `stat` when the file is intact, and self-heals the launch when it is not.
 */
object LwjglBridgeRepair {
    private const val TAG = "LwjglBridgeRepair"
    private const val ASSET_DIR = "components/lwjgl3"
    private const val BRIDGE_JAR = "lwjgl-glfw-classes.jar"
    private const val VERSION_FILE = "version"

    /**
     * True when the bridge jar had to be restored. Safe to call on the UI thread before a launch:
     * it does no work at all when the installed jar matches the packaged one.
     */
    @JvmStatic
    fun ensureInstalled(context: Context): Boolean {
        val dir = File(PathManager.DIR_GAME_HOME, "lwjgl3")
        val jar = File(dir, BRIDGE_JAR)
        // openFd only works for an uncompressed asset; when it is unavailable we cannot compare
        // sizes and accept any non-empty jar instead of re-copying 12 MB on every launch.
        val expectedSize = runCatching {
            context.assets.openFd("$ASSET_DIR/$BRIDGE_JAR").use { it.length }
        }.getOrNull()
        val intact = jar.isFile && jar.length() > 0L &&
            (expectedSize == null || expectedSize == jar.length())
        if (intact) return false

        dir.mkdirs()
        return runCatching {
            Tools.copyAssetFile(context, "$ASSET_DIR/$BRIDGE_JAR", dir.absolutePath, true)
            // Keep the unpack stamp in sync, otherwise the next component check wipes the folder.
            runCatching {
                Tools.copyAssetFile(context, "$ASSET_DIR/$VERSION_FILE", dir.absolutePath, true)
            }
            Logging.i(TAG, "Restored $BRIDGE_JAR (${jar.length()} bytes) into ${dir.absolutePath}")
            true
        }.onFailure { error ->
            Logging.e(TAG, "Could not restore $BRIDGE_JAR - LWJGL native loading may fail", error)
        }.getOrDefault(false)
    }
}
