package com.endiq.turtlelauncher.feature.mod

import com.endiq.turtlelauncher.feature.log.CrashAnalyzer
import com.endiq.turtlelauncher.feature.log.LatestLogResolver
import com.endiq.turtlelauncher.feature.version.Version
import java.io.File
import java.io.RandomAccessFile

/**
 * Reads the last game session's log (and its newest crash report) and points at the mod jars
 * that are actually named in it, so "Mod management" can answer "which of my mods broke the
 * launch?" instead of making the user read the whole log.
 *
 * This is evidence, not a verdict: a jar is listed only when its own name appears in the
 * failing log, and the result is always presented as something to test one jar at a time.
 */
object ModCrashAnalyzer {

    /** Only the tail of a log is needed; a heavily modded session writes far more than this. */
    private const val MAX_LOG_BYTES = 2_000_000L
    private const val MAX_CRASH_BYTES = 512_000L

    /** Loader/format words that are part of a jar's filename but never identify a mod. */
    private val STOP_WORDS = setOf(
        "fabric", "forge", "neoforge", "quilt", "loader", "mods", "mod", "lib", "libs",
        "api", "common", "client", "server", "universal", "release", "alpha", "beta",
        "snapshot", "dev", "all", "jar", "minecraft", "mc", "core", "utils", "compat"
    )

    data class Suspect(
        val jar: File,
        /** How many times this jar's name was mentioned in the log. */
        val hits: Int,
        /** The filename words that were found, for showing why it was picked. */
        val matchedTokens: List<String>,
        val disabled: Boolean
    )

    data class Report(
        val logFile: File?,
        /** Age of the analysed log in milliseconds; -1 when there is no log. */
        val logAgeMillis: Long,
        val diagnoses: List<CrashAnalyzer.Diagnosis>,
        val suspects: List<Suspect>,
        val totalJars: Int
    ) {
        val hasLog: Boolean get() = logFile != null
        val hasDiagnosis: Boolean get() = diagnoses.isNotEmpty()
    }

    @JvmStatic
    fun analyze(version: Version?, modsFolder: File): Report {
        val log = pickLog(version)
        val logText = log?.let { readTail(it, MAX_LOG_BYTES) }.orEmpty()
        val crashText = version?.let { readNewestCrashReport(it) }.orEmpty()

        val combined = buildString {
            append(logText)
            if (crashText.isNotBlank()) append("\n").append(crashText)
        }
        val diagnoses = if (combined.isBlank()) {
            emptyList()
        } else {
            runCatching { CrashAnalyzer.analyze(combined, null, version) }.getOrDefault(emptyList())
        }

        val jars = modsFolder.listFiles { file -> file.isFile && isModJar(file.name) }
            ?.filter { isModJar(it.name) }
            .orEmpty()
            .sortedBy { it.name.lowercase() }

        val haystack = combined.lowercase()
        val suspects = if (haystack.isEmpty()) {
            emptyList()
        } else {
            jars.mapNotNull { jar ->
                val tokens = tokensOf(jar.name)
                if (tokens.isEmpty()) return@mapNotNull null
                val matched = tokens.filter { haystack.contains(it) }
                if (matched.isEmpty()) return@mapNotNull null
                Suspect(
                    jar = jar,
                    hits = matched.sumOf { token -> countOccurrences(haystack, token) },
                    matchedTokens = matched,
                    disabled = jar.name.endsWith(ModUtils.DISABLE_JAR_FILE_SUFFIX)
                )
            }.sortedByDescending { it.hits }.take(6)
        }

        return Report(
            logFile = log,
            logAgeMillis = log?.let { System.currentTimeMillis() - it.lastModified() } ?: -1L,
            diagnoses = diagnoses,
            suspects = suspects,
            totalJars = jars.size
        )
    }

    /** The same log the home screen's "last game log" card shows, newest of the two sources. */
    private fun pickLog(version: Version?): File? {
        val candidates = mutableListOf<File>()
        runCatching { LatestLogResolver.resolveLastGameLogFile() }.getOrNull()?.let { candidates += it }
        runCatching { LatestLogResolver.resolveLatestLogFile() }.getOrNull()?.let { candidates += it }
        return candidates.filter { it.isFile && it.length() > 0 }.maxByOrNull { it.lastModified() }
    }

    private fun readNewestCrashReport(version: Version): String? = runCatching {
        val dir = File(version.getGameDir(), "crash-reports")
        if (!dir.isDirectory) return null
        val newest = dir.listFiles { file -> file.isFile && file.extension == "txt" }
            ?.maxByOrNull { it.lastModified() }
            ?: return null
        readTail(newest, MAX_CRASH_BYTES)
    }.getOrNull()

    /** Reads the last [maxBytes] of [file]; the top of a log is never where a crash is. */
    private fun readTail(file: File, maxBytes: Long): String = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val start = (length - maxBytes).coerceAtLeast(0L)
            raf.seek(start)
            val size = (length - start).toInt()
            if (size <= 0) return ""
            val buffer = ByteArray(size)
            raf.readFully(buffer)
            String(buffer, Charsets.UTF_8)
        }
    }.getOrDefault("")

    private fun isModJar(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(ModUtils.JAR_FILE_SUFFIX) || lower.endsWith(ModUtils.DISABLE_JAR_FILE_SUFFIX)
    }

    /**
     * Filename words that can identify a mod in a log: "sodium-fabric-0.5.8.jar" becomes
     * "sodium". Version numbers, loader names and one-word noise are dropped.
     */
    private fun tokensOf(fileName: String): List<String> {
        val base = fileName
            .removeSuffix(ModUtils.DISABLE_JAR_FILE_SUFFIX)
            .removeSuffix(ModUtils.JAR_FILE_SUFFIX)
        return base
            .split(Regex("[^A-Za-z0-9]+"))
            .asSequence()
            .map { it.lowercase() }
            .filter { token -> token.length >= 4 && token.first().isLetter() }
            .filter { token -> token.any { char -> char.isDigit() }.not() }
            .filter { token -> token !in STOP_WORDS }
            .distinct()
            .toList()
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0 && count < 100) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }
}
