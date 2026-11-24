package com.endiq.turtlelauncher.feature.download

import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.utils.path.UrlManager
import net.endiq.launcher.Tools
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Parallel, resumable file download for mods and modpack assets.
 *
 * Two things every mobile launcher gets wrong and this fixes:
 *
 *  - **Losing the connection loses the file.** Progress is written to `<name>.part` and a
 *    `Range: bytes=<done>-` request picks the transfer up where it stopped, so a tunnel, a
 *    Wi-Fi/cellular handover or a killed app does not restart a 300 MB download from zero.
 *  - **One stream on a fast link.** When the server advertises `Accept-Ranges`, the transfer is
 *    split into [DEFAULT_LANES] ranged chunks downloading at the same time (the requested
 *    "4 luồng"). Servers without range support fall back to a single resumable stream.
 *
 * Completion is atomic: the `.part` file is renamed onto the destination only after every byte
 * arrived, so the caller's existing SHA-1 verification never sees a half-written jar.
 */
object ResumableDownload {
    private const val TAG = "ResumableDownload"

    /** The four lanes requested for mod downloads. */
    const val DEFAULT_LANES = 4

    /** Below this size a ranged split costs more (extra requests) than it saves. */
    private const val MIN_PARALLEL_BYTES = 4L * 1024 * 1024

    /** Chunks smaller than this are not worth a separate lane. */
    private const val MIN_CHUNK_BYTES = 1024L * 1024

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Downloads [url] to [destination], resuming an interrupted transfer when possible.
     *
     * @param lanes how many parallel ranged chunks to use for large files (1 disables splitting)
     * @param progress optional ticker; receives (downloaded, total) across all lanes
     */
    @JvmStatic
    @JvmOverloads
    @Throws(IOException::class)
    fun download(
        url: String,
        destination: File,
        lanes: Int = DEFAULT_LANES,
        progress: Tools.DownloaderFeedback? = null
    ) {
        destination.parentFile?.mkdirs()
        val partFile = File(destination.parentFile, destination.name + ".part")

        val total = contentLength(url)
        val already = if (partFile.isFile) partFile.length() else 0L

        if (total > 0 && already == total) {
            finish(partFile, destination)
            progress?.updateProgress(total, total)
            return
        }

        val supportsRanges = total > 0 && supportsRanges(url)
        val laneCount = when {
            !supportsRanges -> 1
            total < MIN_PARALLEL_BYTES -> 1
            else -> lanes.coerceIn(1, DEFAULT_LANES)
        }

        if (laneCount <= 1) {
            downloadSingleStream(url, partFile, total, progress)
        } else {
            downloadParallel(url, partFile, total, laneCount, progress)
        }

        val expected = if (total > 0) total else partFile.length()
        if (expected > 0 && partFile.length() != expected) {
            throw IOException("Incomplete download: ${partFile.length()} of $expected bytes")
        }
        finish(partFile, destination)
    }

    // ── Single stream (also the resume path) ─────────────────────────────────

    private fun downloadSingleStream(
        url: String,
        partFile: File,
        total: Long,
        progress: Tools.DownloaderFeedback?
    ) {
        var offset = if (partFile.isFile) partFile.length() else 0L
        if (total in 1..offset) {
            // The server's file shrank (or the part file is stale) - start over.
            partFile.delete()
            offset = 0
        }

        val builder = UrlManager.createRequestBuilder(url)
        if (offset > 0) builder.header("Range", "bytes=$offset-")
        val request: Request = builder.build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Server returned HTTP ${response.code}: ${response.message}")
            }
            // A server that ignores Range answers 200 with the whole body; append only on 206.
            val append = response.code == 206 && offset > 0
            if (!append) offset = 0
            val body = response.body ?: throw IOException("Empty response body for $url")
            val length = if (total > 0) total else body.contentLength() + offset

            body.byteStream().use { input ->
                RandomAccessFile(partFile, "rw").use { out ->
                    out.seek(offset)
                    val buffer = ByteArray(128 * 1024)
                    var done = offset
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        done += read
                        progress?.updateProgress(done, length)
                    }
                }
            }
        }
    }

    // ── Parallel ranged chunks ───────────────────────────────────────────────

    private fun downloadParallel(
        url: String,
        partFile: File,
        total: Long,
        lanes: Int,
        progress: Tools.DownloaderFeedback?
    ) {
        val chunk = (total / lanes).coerceAtLeast(MIN_CHUNK_BYTES)
        val ranges = buildList {
            var start = 0L
            while (start < total) {
                val end = minOf(start + chunk - 1, total - 1)
                add(start to end)
                start = end + 1
            }
        }

        // Pre-size the part file so every lane can seek to its own offset.
        RandomAccessFile(partFile, "rw").use { it.setLength(total) }

        val done = AtomicLong(0)
        val pool = Executors.newFixedThreadPool(ranges.size) { runnable ->
            Thread(runnable, "mod-download-lane").apply { isDaemon = true }
        }
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())

        try {
            ranges.forEach { (start, end) ->
                pool.execute {
                    runCatching { fetchRange(url, partFile, start, end, total, done, progress) }
                        .onFailure { error ->
                            Logging.e(TAG, "Lane $start-$end failed", error)
                            errors += error
                        }
                }
            }
        } finally {
            pool.shutdown()
            // Lanes are network-bound; waiting here is what makes the caller's next step safe.
            pool.awaitTermination(30, TimeUnit.MINUTES)
        }

        errors.firstOrNull()?.let { throw IOException("Parallel download failed: ${it.message}", it) }
    }

    private fun fetchRange(
        url: String,
        partFile: File,
        start: Long,
        end: Long,
        total: Long,
        done: AtomicLong,
        progress: Tools.DownloaderFeedback?
    ) {
        val request = UrlManager.createRequestBuilder(url)
            .header("Range", "bytes=$start-$end")
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code != 206) {
                throw IOException("Range not honoured (HTTP ${response.code}) for $url")
            }
            val body = response.body ?: throw IOException("Empty range body for $url")
            body.byteStream().use { input ->
                RandomAccessFile(partFile, "rw").use { out ->
                    out.seek(start)
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        out.write(buffer, 0, read)
                        progress?.updateProgress(done.addAndGet(read.toLong()), total)
                    }
                }
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun contentLength(url: String): Long = runCatching {
        client.newCall(UrlManager.createRequestBuilder(url).head().build()).execute().use { response ->
            if (!response.isSuccessful) 0L else response.header("Content-Length")?.toLongOrNull() ?: 0L
        }
    }.getOrDefault(0L)

    private fun supportsRanges(url: String): Boolean = runCatching {
        client.newCall(UrlManager.createRequestBuilder(url).head().build()).execute().use { response ->
            response.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
        }
    }.getOrDefault(false)

    private fun finish(partFile: File, destination: File) {
        if (destination.exists()) destination.delete()
        if (!partFile.renameTo(destination)) {
            // Cross-filesystem rename (rare on Android, but possible with adopted storage).
            partFile.copyTo(destination, overwrite = true)
            partFile.delete()
        }
    }
}
