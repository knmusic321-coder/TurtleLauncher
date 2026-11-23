package com.endiq.turtlelauncher.feature.benchmark

import com.endiq.turtlelauncher.feature.log.Logging
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Frame-pacing benchmark for the *Optimization* screen.
 *
 * Every toggle on that page (frame pacing, adaptive frame timing, frame skipping, low
 * latency, memory cleanup) is about how evenly frames are delivered, so the probe measures
 * exactly that: it runs a fixed amount of per-frame work on a background thread, waits out
 * the rest of a 60 Hz frame, and records how much each real frame interval deviates from
 * the average. A stable device lands under ~8% jitter; a loaded one drifts far above it.
 *
 * No GL context and no game process are involved, so this is safe to run at any time and
 * cannot disturb the renderer the user is evaluating.
 *
 * Call [run] from a background thread: it blocks for roughly one second.
 */
object OptimizationBenchmark {
    private const val TAG = "OptimizationBenchmark"

    private const val FRAME_TARGET_MS = 16.66
    private const val MEASURED_FRAMES = 45
    private const val WARMUP_FRAMES = 5
    private const val UNITS_PER_FRAME = 120_000

    enum class Tier { STABLE, MODERATE, UNSTABLE, UNAVAILABLE }

    data class Result(
        val averageFrameMs: Double,
        val worstFrameMs: Double,
        /** Standard deviation as a percentage of the average frame interval. */
        val jitterPercent: Double,
        val frames: Int,
        val failed: Boolean = false,
        val failureReason: String? = null
    ) {
        val tier: Tier
            get() = when {
                failed -> Tier.UNAVAILABLE
                jitterPercent < 8.0 -> Tier.STABLE
                jitterPercent < 20.0 -> Tier.MODERATE
                else -> Tier.UNSTABLE
            }

        /** 0-100, higher is steadier. Shown in the settings row. */
        val score: Int
            get() = if (failed) 0 else (100.0 - jitterPercent).toInt().coerceIn(0, 100)

        val averageFps: Int
            get() = if (averageFrameMs <= 0.0) 0 else (1000.0 / averageFrameMs).toInt()
    }

    private fun workload(units: Int): Int {
        var value = 1
        for (index in 0 until units) {
            value = value * 31 + index
            value = value xor (value ushr 7)
        }
        return value
    }

    private fun frame(): Long {
        val started = System.nanoTime()
        workload(UNITS_PER_FRAME)
        return System.nanoTime() - started
    }

    fun run(): Result {
        return try {
            repeat(WARMUP_FRAMES) {
                frame()
                Thread.sleep(FRAME_TARGET_MS.toLong())
            }

            val intervals = DoubleArray(MEASURED_FRAMES)
            var last = System.nanoTime()
            for (index in 0 until MEASURED_FRAMES) {
                val worked = frame()
                // Wait out the rest of the frame, the way a vsync-limited game does.
                val remainingMs = FRAME_TARGET_MS - worked / 1_000_000.0
                if (remainingMs > 0.5) Thread.sleep(remainingMs.toLong())
                val now = System.nanoTime()
                intervals[index] = (now - last) / 1_000_000.0
                last = now
            }

            val average = intervals.average()
            val worst = intervals.maxOrNull() ?: average
            val variance = intervals.sumOf { (it - average) * (it - average) } / intervals.size
            val jitter = sqrt(max(0.0, variance)) / max(0.001, average) * 100.0

            Logging.i(
                TAG,
                "Frame probe: avg " + average + " ms, worst " + worst + " ms, jitter " + jitter + "%"
            )
            Result(
                averageFrameMs = average,
                worstFrameMs = worst,
                jitterPercent = jitter,
                frames = MEASURED_FRAMES
            )
        } catch (e: Throwable) {
            Logging.w(TAG, "Optimization benchmark failed: ${e.message}")
            Result(
                averageFrameMs = 0.0,
                worstFrameMs = 0.0,
                jitterPercent = 0.0,
                frames = 0,
                failed = true,
                failureReason = e.message ?: e.javaClass.simpleName
            )
        }
    }
}
