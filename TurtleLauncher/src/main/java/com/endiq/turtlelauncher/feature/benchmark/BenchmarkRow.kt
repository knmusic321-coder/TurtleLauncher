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
import com.endiq.turtlelauncher.utils.file.FileTools
import com.endiq.turtlelauncher.utils.platform.MemoryUtils

/**
 * One benchmark row, three independent scopes.
 *
 * Each settings screen measures and tunes *its own* section only:
 *
 *  * [Scope.GRAPHICS]     - GPU fill rate, then renderer/driver + render resolution.
 *  * [Scope.DEVICE]       - CPU throughput and memory, then cores + RAM allocation.
 *  * [Scope.OPTIMIZATION] - frame-pacing jitter, then the toggles on that page.
 *
 * The score of each scope lives in its own setting, so running the phone benchmark can no
 * longer change what the graphics screen recommends (or the other way round), and pressing
 * "apply" touches only the settings that belong to the screen the user is on.
 */
object BenchmarkRow {
    enum class Scope { GRAPHICS, DEVICE, OPTIMIZATION }

    @JvmStatic
    fun bind(fragment: Fragment, scope: Scope, row: View, value: TextView) {
        refresh(fragment, scope, value)
        row.setOnClickListener { run(fragment, scope, row, value) }
    }

    /** Shows the stored score (or "not run yet") without touching the CPU or GPU. */
    @JvmStatic
    fun refresh(fragment: Fragment, scope: Scope, value: TextView) {
        if (!fragment.isAdded) return
        val context = fragment.context ?: return
        val stored = storedScore(scope)
        val score = stored ?: 0
        value.text = when {
            stored == null || stored <= 0 -> context.getString(R.string.setting_graphics_benchmark_not_run)
            scope == Scope.GRAPHICS -> context.getString(
                R.string.setting_graphics_benchmark_current,
                AutoSettingsOptimizer.detectGpu(),
                CurrentRenderer.name(context) + " - " +
                    GraphicsTuning.benchTier(score).name.lowercase()
            )
            scope == Scope.DEVICE -> context.getString(
                R.string.setting_device_benchmark_current,
                Runtime.getRuntime().availableProcessors(),
                score,
                FileTools.formatFileSize(MemoryUtils.getTotalDeviceMemory(context))
            )
            else -> context.getString(
                R.string.setting_optimization_benchmark_current,
                score,
                optimizationTierLabel(context, score)
            )
        }
    }

    private fun storedScore(scope: Scope): Int? = when (scope) {
        Scope.GRAPHICS -> AllSettings.lastGraphicsBenchmark
        Scope.DEVICE -> AllSettings.lastDeviceBenchmark
        Scope.OPTIMIZATION -> AllSettings.lastOptimizationBenchmark
    }.getValue().substringBefore('|').toIntOrNull()

    private fun storeScore(scope: Scope, score: Int) {
        val value = "$score|${System.currentTimeMillis()}"
        when (scope) {
            Scope.GRAPHICS -> AllSettings.lastGraphicsBenchmark.put(value).save()
            Scope.DEVICE -> AllSettings.lastDeviceBenchmark.put(value).save()
            Scope.OPTIMIZATION -> AllSettings.lastOptimizationBenchmark.put(value).save()
        }
    }

    private fun optimizationTierLabel(context: android.content.Context, score: Int): String = context.getString(
        when {
            score >= 92 -> R.string.setting_benchmark_tier_stable
            score >= 80 -> R.string.setting_benchmark_tier_moderate
            else -> R.string.setting_benchmark_tier_unstable
        }
    )

    private fun run(fragment: Fragment, scope: Scope, row: View, value: TextView) {
        if (!canShowUi(fragment)) return
        value.setText(R.string.setting_graphics_benchmark_running)
        row.isEnabled = false
        when (scope) {
            Scope.GRAPHICS -> runGraphics(fragment, row, value)
            Scope.DEVICE -> runDevice(fragment, row, value)
            Scope.OPTIMIZATION -> runOptimization(fragment, row, value)
        }
    }

    // ── Graphics & renderer ──────────────────────────────────────────────────

    private fun runGraphics(fragment: Fragment, row: View, value: TextView) {
        val appContext = fragment.requireContext().applicationContext
        Task.runTask { GraphicsBenchmark.run() }
            .onThrowable(TaskExecutors.getAndroidUI()) { error ->
                fail(fragment, row, value, Scope.GRAPHICS, error.message ?: error.javaClass.simpleName)
            }
            .finallyTask(TaskExecutors.getAndroidUI()) {
                if (canShowUi(fragment)) row.isEnabled = true
            }
            .ended(TaskExecutors.getAndroidUI()) { result ->
                if (result == null) return@ended
                if (result.failed) {
                    fail(fragment, row, value, Scope.GRAPHICS, result.failureReason
                        ?: fragment.getString(R.string.generic_unknown))
                    return@ended
                }
                storeScore(Scope.GRAPHICS, result.score)
                if (!canShowUi(fragment)) return@ended
                refresh(fragment, Scope.GRAPHICS, value)
                val context = fragment.requireContext()
                val profile = AutoSettingsOptimizer.buildProfile(context, "")
                val resolution = GraphicsTuning.resolutionPercent(profile)
                val detail = fragment.getString(
                    R.string.setting_graphics_benchmark_result,
                    result.score,
                    result.tier,
                    result.megapixelsPerSecond,
                    result.durationMs.toInt(),
                    result.glRenderer ?: ""
                ) + "\n" + fragment.getString(
                    R.string.setting_graphics_benchmark_current,
                    profile.gpuName,
                    CurrentRenderer.name(context)
                ) + "\n" + fragment.getString(
                    R.string.setting_graphics_benchmark_resolution,
                    resolution
                )
                showResult(
                    fragment, row, value, Scope.GRAPHICS, detail,
                    R.string.setting_graphics_benchmark_apply,
                    R.string.setting_graphics_benchmark_applied
                ) {
                    AutoSettingsOptimizer.applyGraphicsRecommendation(appContext)
                }
            }
            .execute()
    }

    // ── Phone settings ───────────────────────────────────────────────────────

    private fun runDevice(fragment: Fragment, row: View, value: TextView) {
        val context = fragment.requireContext()
        val appContext = context.applicationContext
        val total = MemoryUtils.getTotalDeviceMemory(context)
        val free = MemoryUtils.getFreeDeviceMemory(context)
        Task.runTask { DeviceBenchmark.run(total, free) }
            .onThrowable(TaskExecutors.getAndroidUI()) { error ->
                fail(fragment, row, value, Scope.DEVICE, error.message ?: error.javaClass.simpleName)
            }
            .finallyTask(TaskExecutors.getAndroidUI()) {
                if (canShowUi(fragment)) row.isEnabled = true
            }
            .ended(TaskExecutors.getAndroidUI()) { result ->
                if (result == null) return@ended
                if (result.failed) {
                    fail(fragment, row, value, Scope.DEVICE, result.failureReason
                        ?: fragment.getString(R.string.generic_unknown))
                    return@ended
                }
                storeScore(Scope.DEVICE, result.multiThreadScore)
                if (!canShowUi(fragment)) return@ended
                refresh(fragment, Scope.DEVICE, value)
                val detail = fragment.getString(
                    R.string.setting_device_benchmark_result,
                    result.singleThreadScore,
                    result.multiThreadScore,
                    result.tier,
                    result.cores,
                    FileTools.formatFileSize(result.totalRamBytes),
                    FileTools.formatFileSize(result.freeRamBytes)
                ) + "\n" + fragment.getString(
                    R.string.setting_device_benchmark_recommendation,
                    result.suggestedCores
                )
                showResult(
                    fragment, row, value, Scope.DEVICE, detail,
                    R.string.setting_device_benchmark_apply,
                    R.string.setting_device_benchmark_applied
                ) {
                    AutoSettingsOptimizer.applyPhoneRecommendation(appContext, result)
                }
            }
            .execute()
    }

    // ── Optimization ─────────────────────────────────────────────────────────

    private fun runOptimization(fragment: Fragment, row: View, value: TextView) {
        val appContext = fragment.requireContext().applicationContext
        Task.runTask { OptimizationBenchmark.run() }
            .onThrowable(TaskExecutors.getAndroidUI()) { error ->
                fail(fragment, row, value, Scope.OPTIMIZATION, error.message ?: error.javaClass.simpleName)
            }
            .finallyTask(TaskExecutors.getAndroidUI()) {
                if (canShowUi(fragment)) row.isEnabled = true
            }
            .ended(TaskExecutors.getAndroidUI()) { result ->
                if (result == null) return@ended
                if (result.failed) {
                    fail(fragment, row, value, Scope.OPTIMIZATION, result.failureReason
                        ?: fragment.getString(R.string.generic_unknown))
                    return@ended
                }
                storeScore(Scope.OPTIMIZATION, result.score)
                if (!canShowUi(fragment)) return@ended
                refresh(fragment, Scope.OPTIMIZATION, value)
                val tier = when (result.tier) {
                    OptimizationBenchmark.Tier.STABLE -> R.string.setting_benchmark_tier_stable
                    OptimizationBenchmark.Tier.MODERATE -> R.string.setting_benchmark_tier_moderate
                    OptimizationBenchmark.Tier.UNSTABLE -> R.string.setting_benchmark_tier_unstable
                    OptimizationBenchmark.Tier.UNAVAILABLE -> R.string.setting_benchmark_tier_unavailable
                }
                val detail = fragment.getString(
                    R.string.setting_optimization_benchmark_result,
                    result.score,
                    fragment.getString(tier),
                    result.averageFrameMs,
                    result.worstFrameMs,
                    result.jitterPercent,
                    result.averageFps
                )
                showResult(
                    fragment, row, value, Scope.OPTIMIZATION, detail,
                    R.string.setting_optimization_benchmark_apply,
                    R.string.setting_optimization_benchmark_applied
                ) {
                    AutoSettingsOptimizer.applyOptimizationRecommendation(appContext, result)
                }
            }
            .execute()
    }

    // ── Shared UI ────────────────────────────────────────────────────────────

    private fun showResult(
        fragment: Fragment,
        row: View,
        value: TextView,
        scope: Scope,
        message: String,
        applyLabel: Int,
        appliedLabel: Int,
        apply: () -> Unit
    ) {
        if (!canShowUi(fragment)) return
        val context = fragment.requireContext()
        TipDialog.Builder(context)
            .setTitle(titleFor(scope))
            .setMessage(message)
            .setConfirm(applyLabel)
            .setCancel(R.string.generic_close)
            .setConfirmClickListener {
                if (!canShowUi(fragment)) return@setConfirmClickListener
                apply()
                android.widget.Toast.makeText(
                    fragment.requireContext(), appliedLabel, android.widget.Toast.LENGTH_SHORT
                ).show()
                refresh(fragment, scope, value)
            }
            .showDialog()
    }

    private fun fail(
        fragment: Fragment,
        row: View,
        value: TextView,
        scope: Scope,
        reason: String
    ) {
        Logging.w("BenchmarkRow", "Benchmark failed: $reason")
        TaskExecutors.runInUIThread {
            if (!canShowUi(fragment)) return@runInUIThread
            row.isEnabled = true
            refresh(fragment, scope, value)
            TipDialog.Builder(fragment.requireContext())
                .setTitle(titleFor(scope))
                .setMessage(fragment.getString(R.string.setting_graphics_benchmark_failed, reason))
                .setShowCancel(false)
                .showDialog()
        }
    }

    private fun titleFor(scope: Scope): Int = when (scope) {
        Scope.GRAPHICS -> R.string.setting_graphics_benchmark_title
        Scope.DEVICE -> R.string.setting_device_benchmark_title
        Scope.OPTIMIZATION -> R.string.setting_optimization_benchmark_title
    }

    private fun canShowUi(fragment: Fragment): Boolean {
        val activity = fragment.activity ?: return false
        return fragment.isAdded && fragment.view != null && !activity.isFinishing && !activity.isDestroyed
    }
}
