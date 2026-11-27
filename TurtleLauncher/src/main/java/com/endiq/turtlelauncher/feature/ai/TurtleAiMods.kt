package com.endiq.turtlelauncher.feature.ai

import com.endiq.turtlelauncher.feature.download.enums.ModLoader
import com.endiq.turtlelauncher.feature.download.utils.ModLoaderUtils
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.feature.mod.ModrinthDirectApi
import com.endiq.turtlelauncher.feature.version.VersionsManager
import com.google.gson.JsonObject
import java.io.File
import java.util.Locale

/**
 * "Find and install a mod for me", on top of the Modrinth API the download screens already use.
 *
 * Two operations and nothing else, because those are the two the chat needs:
 *  - [search] answers "which mods match this" with a handful of real projects (title, slug,
 *    download count) - never with invented names;
 *  - [install] writes the newest build that matches the *currently selected* version and mod
 *    loader into that version's `mods` folder, reusing [ModrinthDirectApi.downloadBestMatch]
 *    (the same call the recommended-content installer makes), so a mod installed from chat is
 *    installed exactly the way the download screen would install it.
 *
 * Both do network and disk work: call them off the main thread (the chat already answers on a
 * background executor).
 */
object TurtleAiMods {

    private const val TAG = "TurtleAiMods"

    /** One search result, flattened to what the chat shows. */
    data class Hit(
        val slug: String,
        val title: String,
        val description: String,
        val downloads: Long
    )

    /** Where a mod for the current instance goes, and what it has to match. */
    private data class Target(
        val versionName: String,
        val mcVersion: String,
        val loader: ModLoader?,
        val modsDir: File
    )

    /** Modrinth search, newest/most downloaded first (Modrinth's own default relevance order). */
    fun search(query: String, limit: Int = 3): List<Hit> {
        if (query.isBlank()) return emptyList()
        return runCatching {
            ModrinthDirectApi.searchProjects(query, limit).mapNotNull { obj ->
                val slug = obj.string("slug")
                val title = obj.string("title")
                if (slug.isBlank() || title.isBlank()) return@mapNotNull null
                Hit(
                    slug = slug,
                    title = title,
                    description = obj.string("description"),
                    downloads = runCatching { obj.get("downloads")?.asLong ?: 0L }.getOrDefault(0L)
                )
            }
        }.onFailure { e -> Logging.e(TAG, "Modrinth search failed for \"$query\"", e) }
            .getOrDefault(emptyList())
    }

    /**
     * Installs [slug] for the selected version. Returns a one-line, user-facing result - the
     * same string the chat prints, success or failure, so the caller never has to guess.
     */
    fun install(slug: String): String {
        val target = target() ?: return "✗ Không xác định được phiên bản game đang chọn."
        return runCatching {
            target.modsDir.mkdirs()
            val installed = ModrinthDirectApi.downloadBestMatch(
                slug, target.mcVersion, target.loader, target.modsDir
            )
            if (installed == null) {
                val loaderName = target.loader?.loaderName ?: "vanilla"
                "✗ Không có bản \"$slug\" nào hợp với ${target.mcVersion} / $loaderName."
            } else {
                "✓ $slug → ${target.versionName}/mods: $installed"
            }
        }.onFailure { e -> Logging.e(TAG, "Install of $slug failed", e) }
            .getOrElse { e -> "✗ Cài \"$slug\" thất bại: ${e.message ?: e.javaClass.simpleName}" }
    }

    /** The selected version, its Minecraft version, loader and `mods` folder. */
    private fun target(): Target? = runCatching {
        val version = VersionsManager.getCurrentVersion() ?: return@runCatching null
        val info = version.getVersionInfo() ?: return@runCatching null
        val mcVersion = info.minecraftVersion ?: return@runCatching null
        val loader = info.loaderInfo?.firstNotNullOfOrNull { ModLoaderUtils.getModLoader(it.name) }
        Target(
            versionName = version.getVersionName(),
            mcVersion = mcVersion,
            loader = loader,
            modsDir = File(version.getGameDir(), "mods")
        )
    }.getOrNull()

    /** "12.3M" instead of "12345678" in the chat, in the reader's own number format. */
    fun formatDownloads(downloads: Long): String = when {
        downloads >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM", downloads / 1_000_000.0)
        downloads >= 1_000 -> String.format(Locale.ROOT, "%.1fK", downloads / 1_000.0)
        else -> downloads.toString()
    }

    private fun JsonObject.string(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: ""
}
