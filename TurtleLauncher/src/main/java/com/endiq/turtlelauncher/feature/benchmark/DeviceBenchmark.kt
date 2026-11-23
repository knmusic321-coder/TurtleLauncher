package com.endiq.turtlelauncher.feature.benchmark

import com.endiq.turtlelauncher.feature.log.Logging
import kotlin.math.max

/**
 * CPU/memory benchmark for the *Phone settings* screen.
 *
 * The phone section owns cores, RAM and memory tuning, so its benchmark measures exactly
 * those: a fixed integer workload timed on one thread and on every available core, plus the
 * device's total/free memory. Nothing here touches the GPU or the renderer, and the result
 * is stored separately from [GraphicsBenchmark]'s score so the two screens can never
 * overwrite each other's recommendation.
 *
 * Broken into a pure work loop ([workload]) and a timing wrapper so the numbers are
 * reproducible and the class stays unit-testable on a plain JVM.
 *
 * Call [run] from a background thread: it blocks for roughly a second.
 */
object DeviceBenchmark {
    private const val TAG = "DeviceBenchmark"

    /** Enough work per thread to time reliably without a noticeable pause. */
    private const val UNITS_PER_THREAD = 600_000
    private const val WARMUP_UNITS = 60_000

    data class Result(
        /** Millions of work units per second on one core. */
        val singleThreadScore: Int,
        /** Millions of work units per second across all cores. */
        val multiThreadScore: Int,
        val cores: Int,
        val totalRamBytes: Long,
        val freeRamBytes: Long,
        val failed: Boolean = false,
        val failureReason: String? = null
    ) {
        val tier: String
            get() = when {
                failed -> "unavailable"
                multiThreadScore >= 1200 -> "high"
                multiThreadScore >= 400 -> "mid"
                else -> "low"
            }

        /**
         * Cores the launcher may hand to the game. One is left for the system and the
         * launcher's own UI thread, and the value never goes past eight because beyond
         * that Minecraft's own worker pools gain nothing and the shared clusters overheat.
         */
        val suggestedCores: Int
            get() = if (cores <= 3) cores else (cores - 1).coerceAtMost(8)
    }

    /** Mixed integer/average math; `checksum` keeps ART from eliminating the loop. */
    private fun workload(units: Int): Int {
        var value = 1
        for (index in 0 until units) {
            value = value * 31 + index
            value = value xor (value ushr 7)
        }
        return value
    }

    private fun timedUnits(units: Int): Pair<Int, Long> {
        val started = System.nanoTime()
        val checksum = workload(units)
        return checksum to max(1L, System.nanoTime() - started)
    }

    /** @param totalRamBytes / @param freeRamBytes from [android.app.ActivityManager.MemoryInfo]. */
    fun run(totalRamBytes: Long, freeRamBytes: Long): Result {
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return try {
            workload(WARMUP_UNITS)
            val (_, singleNs) = timedUnits(UNITS_PER_THREAD)
            val single = (UNITS_PER_THREAD.toLong() * 1000L / singleNs / 1000L).toInt()

            val threads = (0 until cores).map {
                Thread { timedUnits(UNITS_PER_THREAD) }
            }
            val started = System.nanoTime()
            threads.forEach { it.start() }
            threads.forEach { it.join() }
            val multiNs = max(1L, System.nanoTime() - started)
            val totalUnits = UNITS_PER_THREAD.toLong() * cores
            val multi = (totalUnits * 1000L / multiNs / 1000L).toInt()

            Logging.i(
                TAG,
                "CPU probe: ${cores} cores | single $single / multi $multi M units/s | " +
                    "RAM ${freeRamBytes / (1024 * 1024)}/${totalRamBytes / (1024 * 1024)} MB"
            )
            Result(
                singleThreadScore = single,
                multiThreadScore = multi,
                cores = cores,
                totalRamBytes = totalRamBytes,
                freeRamBytes = freeRamBytes
            )
        } catch (e: Throwable) {
            Logging.w(TAG, "Device benchmark failed: ${e.message}")
            Result(
                singleThreadScore = 0,
                multiThreadScore = 0,
                cores = cores,
                totalRamBytes = totalRamBytes,
                freeRamBytes = freeRamBytes,
                failed = true,
                failureReason = e.message ?: e.javaClass.simpleName
            )
        }
    }
}
