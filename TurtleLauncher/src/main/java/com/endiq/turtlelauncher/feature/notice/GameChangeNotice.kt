package com.endiq.turtlelauncher.feature.notice

import android.app.Activity
import com.endiq.turtlelauncher.BuildConfig
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.ui.dialog.GameChangeNoticeDialog

/**
 * Shows the change notice when the game opens, once per launcher version.
 *
 * The notice belongs to the game screen on purpose: it summarizes what was fixed in this
 * build and where the new features are, at the moment the user actually starts playing.
 */
object GameChangeNotice {
    private const val TAG = "GameChangeNotice"

    @JvmStatic
    fun showIfNeeded(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        val version = BuildConfig.VERSION_NAME.takeIf { it.isNotBlank() } ?: return
        if (AllSettings.inGameNoticeShownVersion.getValue() == version) return
        runCatching {
            AllSettings.inGameNoticeShownVersion.put(version).save()
            GameChangeNoticeDialog(activity, version).show()
        }.onFailure {
            Logging.w(TAG, "Could not show the in-game notice: ${it.message}")
        }
    }
}
