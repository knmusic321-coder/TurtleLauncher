package com.endiq.turtlelauncher.feature.benchmark

import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.launch.AutoSettingsOptimizer
import com.endiq.turtlelauncher.launch.GraphicsTuning
import com.endiq.turtlelauncher.renderer.CurrentRenderer
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.task.Task
import com.endiq.turtlelauncher.task.TaskExecutors
import com.endiq.turtlelauncher.ui.dialog.TipDialog

/**
 * The graphics-benchmark settings row, reused by every screen that should offer it
 * (Graphics & renderer, Phone settings, Optimization).
 *
 * The score is stored once in [AllSettings.lastGraphicsBenchmark] and shared by
 * [AutoSettingsOptimizer], so running it from any of those screens improves the same
 * recommendation everywhere.
 */
object BenchmarkRow {
    @JvmStatic
    fun bind(fragment: Fragment, row: View, value: TextView) {
        refresh(fragment, value)
        row.setOnClickListener { run(fragment, row, value) }
    }

    /** Shows the stored score (or "not run yet") without touching the GPU. */
    @JvmStatic
    fun refresh(fragment: Fragment, value: TextView) {
        if (!fragment.isAdded) return
        val context = fragment.context ?: return
        val stored = AllSettings.lastGraphicsBenchmark.getValue().substringBefore('|').toIntOrNull()
        value.text = if (stored == null || stored <= 0) {
            context.getString(R.string.setting_graphics_benchmark_not_run)
        } else {
            context.getString(
                R.string.setting_graphics_benchmark_current,
                AutoSettingsOptimizer.detectGpu(),
                CurrentRenderer.name(context) + " - " +
                    GraphicsTuning.benchTier(stored).name.lowercase()
            )
        }
    }

    private fun run(fragment: Fragment, row: View, value: TextView) {
        if (!canShowUi(fragment)) return
        value.setText(R.string.setting_graphics_benchmark_running)
        row.isEnabled = false

        Task.runTask { GraphicsBenchmark.run() }
            .onThrowable(TaskExecutors.getAndroidUI()) { error ->
                Logging.w("BenchmarkRow", "Benchmark failed: ${error.message}")
                TaskExecutors.runInUIThread {
                    if (!canShowUi(fragment)) return@runInUIThread
                    row.isEnabled = true
                    refresh(fragment, value)
                    TipDialog.Builder(fragment.requireContext())
                        .setTitle(R.string.setting_graphics_benchmark_title)
                        .setMessage(
                            fragment.getString(
                                R.string.setting_graphics_benchmark_failed,
                                error.message ?: error.javaClass.simpleName
                            )
                        )
                        .setShowCancel(false)
                        .showDialog()
                }
            }
            .finallyTask(TaskExecutors.getAndroidUI()) {
                if (canShowUi(fragment)) row.isEnabled = true
            }
            .ended(TaskExecutors.getAndroidUI()) { result ->
                if (result == null) return@ended
                if (!result.failed) {
                    AllSettings.lastGraphicsBenchmark
                        .put("${result.score}|${System.currentTimeMillis()}")
                        .save()
                }
                if (!canShowUi(fragment)) return@ended
                refresh(fragment, value)
                if (result.failed) {
                    TipDialog.Builder(fragment.requireContext())
                        .setTitle(R.string.setting_graphics_benchmark_title)
                        .setMessage(
                            fragment.getString(
                                R.string.setting_graphics_benchmark_failed,
                                result.failureReason ?: fragment.getString(R.string.generic_unknown)
                            )
                        )
                        .setShowCancel(false)
                        .showDialog()
                    return@ended
                }
                showResult(fragment, result)
            }
            .execute()
    }

    private fun showResult(fragment: Fragment, result: GraphicsBenchmark.Result) {
        if (!canShowUi(fragment)) return
        val context = fragment.requireContext()
        val profile = AutoSettingsOptimizer.buildProfile(context, "")
        val resolution = GraphicsTuning.resolutionPercent(profile)
        val message = fragment.getString(
            R.string.setting_graphics_benchmark_result,
            result.score,
            result.tier,
            result.megapixelsPerSecond,
            result.durationMs.toInt(),
            result.glRenderer ?: ""
        ) +
            "\n" + fragment.getString(
                R.string.setting_graphics_benchmark_current,
                profile.gpuName,
                CurrentRenderer.name(context)
            ) +
            "\n" + fragment.getString(R.string.setting_graphics_benchmark_resolution, resolution)

        TipDialog.Builder(context)
            .setTitle(R.string.setting_graphics_benchmark_title)
            .setMessage(message)
            .setConfirm(R.string.setting_graphics_benchmark_apply)
            .setCancel(R.string.generic_close)
            .setConfirmClickListener {
                if (!canShowUi(fragment)) return@setConfirmClickListener
                AutoSettingsOptimizer.apply(fragment.requireContext())
                android.widget.Toast.makeText(
                    fragment.requireContext(),
                    R.string.setting_graphics_benchmark_applied,
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
            .showDialog()
    }

    private fun canShowUi(fragment: Fragment): Boolean {
        val activity = fragment.activity ?: return false
        return fragment.isAdded && fragment.view != null && !activity.isFinishing && !activity.isDestroyed
    }
}
