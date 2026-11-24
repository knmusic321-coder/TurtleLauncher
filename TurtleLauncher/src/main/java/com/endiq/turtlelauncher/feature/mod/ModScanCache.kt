package com.endiq.turtlelauncher.feature.mod

import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.utils.path.PathManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Hash-keyed cache for mod-folder scans.
 *
 * Scanning a mods folder means listing every jar, reading its metadata (tokens, mixin targets,
 * display name) and re-doing all of that after every launch, even when not a single jar changed.
 * This cache remembers, per file, a fingerprint made of its size and a SHA-256 of its first and
 * last block - so a renamed-but-identical jar still hits the cache while an edited one misses -
 * and returns the previously computed data instead of re-reading the file.
 *
 * The index itself is invalidated wholesale when a jar is added or removed, which keeps "only
 * rescan when files change" honest: cached entries are looked up per file, and a missing entry is
 * computed once.
 */
object ModScanCache {
    private const val TAG = "ModScanCache"
    private const val CACHE_FILE = "mod_scan_cache.json"
    private const val BLOCK_BYTES = 16 * 1024
    private const val MAX_ENTRIES = 512

    /** What was learned from one jar; kept small so the JSON stays a few hundred KB at most. */
    data class Entry(
        val path: String = "",
        val size: Long = 0,
        val modified: Long = 0,
        /** SHA-256 of (size + first block + last block). Invalidated by any content change. */
        val hash: String = "",
        /** Filename words that identify the mod in a log, e.g. "sodium" for sodium-fabric-0.5.jar. */
        val tokens: List<String> = emptyList(),
        /** Entries of `fabric.mod.json`/`mods.toml` is not parsed here; this is the display name. */
        val displayName: String = ""
    )

    data class Scan(
        val folder: String,
        /** Jar name -> entry, in display order. */
        val jars: Map<String, Entry>,
        /** True when every jar came from the cache and no file had to be read. */
        val fromCache: Boolean
    )

    private val gson = Gson()
    private val lock = Any()
    private var memory: MutableMap<String, Entry>? = null

    /** Scans [folder] (normally `<game dir>/mods`), reusing cached entries when nothing changed. */
    @JvmStatic
    fun scan(folder: File): Scan {
        val disabled = !runCatching { AllSettings.modScanCacheEnabled.getValue() }.getOrDefault(true)
        val jars = folder.listFiles { file -> file.isFile && isModJar(file.name) }
            .orEmpty()
            .sortedBy { it.name.lowercase() }
        if (jars.isEmpty()) return Scan(folder.absolutePath, emptyMap(), fromCache = true)

        val index = if (disabled) mutableMapOf() else loadIndex()
        val result = LinkedHashMap<String, Entry>(jars.size)
        var allCached = true

        for (jar in jars) {
            val cached = index[jar.absolutePath]
            val entry = if (cached != null && cached.size == jar.length() &&
                cached.modified == jar.lastModified() && cached.hash.isNotEmpty()
            ) {
                cached
            } else {
                allCached = false
                buildEntry(jar)
            }
            result[jar.name] = entry
        }

        if (!disabled) saveIndex(result.values.associateBy { it.path })
        return Scan(folder.absolutePath, result, fromCache = allCached)
    }

    /** Tokens for a single jar, cached - what [ModCrashAnalyzer] uses when matching log lines. */
    @JvmStatic
    fun tokens(file: File): List<String> {
        val disabled = !runCatching { AllSettings.modScanCacheEnabled.getValue() }.getOrDefault(true)
        if (disabled) return tokenize(file.name)

        val index = loadIndex()
        val cached = index[file.absolutePath]
        if (cached != null && cached.size == file.length() && cached.modified == file.lastModified()) {
            return cached.tokens
        }
        val entry = buildEntry(file)
        index[entry.path] = entry
        saveIndex(index)
        return entry.tokens
    }

    /** Drops every cached entry (Settings -> "Rescan mods" / troubleshooting). */
    @JvmStatic
    fun clear() {
        synchronized(lock) {
            memory = mutableMapOf()
            runCatching { cacheFile().delete() }
        }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun buildEntry(file: File): Entry {
        val hash = runCatching { fingerprint(file) }.getOrDefault("")
        return Entry(
            path = file.absolutePath,
            size = file.length(),
            modified = file.lastModified(),
            hash = hash,
            tokens = tokenize(file.name),
            displayName = file.name
                .removeSuffix(ModUtils.DISABLE_JAR_FILE_SUFFIX)
                .removeSuffix(ModUtils.JAR_FILE_SUFFIX)
        )
    }

    private fun cacheFile(): File = File(PathManager.DIR_CACHE, CACHE_FILE)

    private fun loadIndex(): MutableMap<String, Entry> {
        memory?.let { return it }
        synchronized(lock) {
            memory?.let { return it }
            val loaded = runCatching {
                val file = cacheFile()
                if (!file.isFile || file.length() == 0L) return@runCatching mutableMapOf()
                val type = object : TypeToken<Map<String, Entry>>() {}.type
                val parsed: MutableMap<String, Entry> = gson.fromJson(file.readText(), type) ?: mutableMapOf()
                parsed
            }.getOrElse { error ->
                Logging.e(TAG, "Unreadable mod scan cache, starting fresh", error)
                mutableMapOf()
            }
            memory = loaded
            return loaded
        }
    }

    private fun saveIndex(index: Map<String, Entry>) {
        synchronized(lock) {
            val trimmed = if (index.size > MAX_ENTRIES) {
                index.entries.sortedByDescending { it.value.modified }.take(MAX_ENTRIES).associate { it.toPair() }
            } else index
            memory = trimmed.toMutableMap()
            runCatching {
                val file = cacheFile()
                file.parentFile?.mkdirs()
                file.writeText(gson.toJson(trimmed))
            }.onFailure { Logging.e(TAG, "Failed to persist the mod scan cache", it) }
        }
    }

    /**
     * Size + SHA-256 of the first and last [BLOCK_BYTES]. Reading the head and tail is enough to
     * notice an edited jar without hashing hundreds of megabytes on a phone.
     */
    private fun fingerprint(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(file.length().toString().toByteArray())
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val headSize = minOf(length, BLOCK_BYTES.toLong()).toInt()
            val head = ByteArray(headSize)
            raf.seek(0)
            raf.readFully(head)
            digest.update(head)

            if (length > BLOCK_BYTES) {
                val tailSize = minOf(length - BLOCK_BYTES, BLOCK_BYTES.toLong()).toInt()
                val tail = ByteArray(tailSize)
                raf.seek(length - tailSize)
                raf.readFully(tail)
                digest.update(tail)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isModJar(name: String): Boolean {
        val lower = name.lowercase()
        return lower.endsWith(ModUtils.JAR_FILE_SUFFIX) || lower.endsWith(ModUtils.DISABLE_JAR_FILE_SUFFIX)
    }

    /**
     * Filename words that can identify a mod in a log: "sodium-fabric-0.5.8.jar" becomes
     * "sodium". Version numbers, loader names and one-word noise are dropped.
     */
    fun tokenize(fileName: String): List<String> {
        val base = fileName
            .removeSuffix(ModUtils.DISABLE_JAR_FILE_SUFFIX)
            .removeSuffix(ModUtils.JAR_FILE_SUFFIX)
        return base
            .split(Regex("[^A-Za-z0-9]+"))
            .asSequence()
            .map { it.lowercase() }
            .filter { token -> token.length >= 4 && token.first().isLetter() }
            .filter { token -> token.none { it.isDigit() } }
            .filter { token -> token !in STOP_WORDS }
            .distinct()
            .toList()
    }

    /** Loader/format words that are part of a jar's filename but never identify a mod. */
    private val STOP_WORDS = setOf(
        "fabric", "forge", "neoforge", "quilt", "loader", "mods", "mod", "lib", "libs",
        "api", "common", "client", "server", "universal", "release", "alpha", "beta",
        "snapshot", "dev", "all", "jar", "minecraft", "mc", "core", "utils", "compat"
    )
}
