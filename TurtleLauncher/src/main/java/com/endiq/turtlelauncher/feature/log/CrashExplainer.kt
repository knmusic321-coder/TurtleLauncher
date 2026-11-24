package com.endiq.turtlelauncher.feature.log

import com.endiq.turtlelauncher.context.LocaleHelper
import com.endiq.turtlelauncher.feature.mod.ModCrashAnalyzer
import com.endiq.turtlelauncher.feature.mod.ModScanCache
import com.endiq.turtlelauncher.feature.version.Version
import com.endiq.turtlelauncher.setting.AllSettings
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale

/**
 * Turns a raw Minecraft log/crash report into something a player can act on, in Vietnamese or
 * English, instead of a stack trace.
 *
 * The rule engine in [CrashAnalyzer] answers *what matched*. This class answers the next two
 * questions - "which mod is missing?" and "which two mods are fighting?" - by reading the actual
 * mod-loader error lines, then renders the whole thing as a short headline, one cause paragraph
 * and a numbered list of fixes.
 *
 * Deliberately evidence-based: a mod is only named when the log names it, and every result is
 * presented as a candidate to test, never as a proven verdict.
 */
object CrashExplainer {
    private const val MAX_LOG_BYTES = 2_000_000L
    private const val MAX_CRASH_BYTES = 512_000L

    enum class Language(val code: String) {
        EN("en"), VI("vi");

        companion object {
            @JvmStatic
            fun fromCode(code: String?): Language =
                values().firstOrNull { it.code.equals(code, ignoreCase = true) } ?: EN
        }
    }

    enum class Kind { MISSING_MOD, MOD_CONFLICT, LOADER_MISMATCH, JAVA_VERSION, MEMORY, GPU, CORRUPT_FILES, UNKNOWN }

    /** One actionable finding. [detail] and [fixes] are already in the requested language. */
    data class Finding(
        val kind: Kind,
        /** Mod ids/names the finding is about (empty for non-mod findings). */
        val subjects: List<String> = emptyList(),
        val headline: String,
        val detail: String,
        val fixes: List<String>
    )

    data class Explanation(
        val language: Language,
        val headline: String,
        val findings: List<Finding>,
        /** Jars named in the log, most mentioned first (from [ModCrashAnalyzer]). */
        val suspects: List<String> = emptyList(),
        val hasLog: Boolean = true
    )

    /**
     * Explains the newest log (and newest crash report) for [version].
     *
     * @param modsFolder the instance's `mods` folder, used to tell a *missing* dependency from an
     *                   installed one and to name the jars a conflict is between
     */
    @JvmStatic
    @JvmOverloads
    fun explain(
        version: Version?,
        modsFolder: File?,
        language: Language = language()
    ): Explanation {
        val logFile = newestLog(version)
        val crash = version?.let { newestCrashReport(it) }
        val text = buildString {
            logFile?.let { append(readTail(it, MAX_LOG_BYTES)) }
            crash?.let {
                if (isNotEmpty()) append('\n')
                append(readTail(it, MAX_CRASH_BYTES))
            }
        }
        if (text.isBlank()) {
            return Explanation(
                language = language,
                headline = t(language, "No log to read yet", "Chưa có nhật ký để đọc"),
                findings = emptyList(),
                hasLog = false
            )
        }

        val installed = installedModTokens(modsFolder)
        val findings = mutableListOf<Finding>()
        findings += detectMissingMod(text, installed, language)
        findings += detectConflict(text, installed, language)
        findings += detectNonModCauses(text, version, language)

        // CrashAnalyzer's rules add the deeper launcher-specific knowledge (renderer, LWJGL,
        // corrupted files). Keep their detail but let this class own the language + fix list.
        val diagnoses = runCatching { CrashAnalyzer.analyze(text, null, version) }.getOrDefault(emptyList())
        diagnoses.take(2).forEach { diagnosis ->
            findings += Finding(
                kind = Kind.UNKNOWN,
                headline = localizeTitle(diagnosis.title, language),
                detail = localizeCause(diagnosis, language),
                fixes = localizeFixes(diagnosis, language)
            )
        }

        val suspects = modsFolder?.let { folder ->
            runCatching { ModCrashAnalyzer.analyze(version, folder).suspects.map { it.jar.name } }
                .getOrDefault(emptyList())
        }.orEmpty()

        val headline = findings.firstOrNull()?.headline ?: t(
            language,
            "The log does not name a specific cause.",
            "Nhật ký không chỉ ra nguyên nhân cụ thể."
        )
        return Explanation(
            language = language,
            headline = headline,
            findings = findings.distinctBy { it.headline },
            suspects = suspects,
            hasLog = true
        )
    }

    /** Which language to write in: the setting, or the launcher language when set to "auto". */
    @JvmStatic
    fun language(): Language {
        val explicit = runCatching { AllSettings.crashExplainerLanguage.getValue() }.getOrNull()
        if (!explicit.isNullOrBlank() && !explicit.equals("auto", ignoreCase = true)) {
            return Language.fromCode(explicit)
        }
        val launcherLanguage = runCatching { AllSettings.launcherLanguage.getValue() }.getOrNull()
            ?: Locale.getDefault().language
        return if (launcherLanguage.trim().lowercase(Locale.ROOT).startsWith("vi")) Language.VI else Language.EN
    }

    /** Renders an [Explanation] as plain text, ready for a dialog or a share sheet. */
    @JvmStatic
    fun render(explanation: Explanation): String {
        val language = explanation.language
        if (!explanation.hasLog) return explanation.headline

        val out = StringBuilder()
        out.append("✦ ").append(explanation.headline)
        explanation.findings.forEach { finding ->
            out.append("\n\n").append(finding.headline)
            if (finding.detail.isNotBlank()) {
                out.append("\n").append(t(language, "Cause: ", "Nguyên nhân: ")).append(finding.detail)
            }
            if (finding.fixes.isNotEmpty()) {
                out.append("\n").append(t(language, "How to fix:", "Cách khắc phục:"))
                finding.fixes.forEachIndexed { index, fix ->
                    out.append("\n  ").append(index + 1).append(". ").append(fix)
                }
            }
        }
        if (explanation.suspects.isNotEmpty()) {
            out.append("\n\n").append(
                t(language, "Jars named in this log: ", "Các file jar được nhắc trong log: ")
            ).append(explanation.suspects.joinToString(", "))
        }
        return out.toString()
    }

    // ── Detection ────────────────────────────────────────────────────────────

    /**
     * A required mod is absent. Covers both wordings the loaders actually print:
     *   Fabric/Quilt: "requires any version of mod 'create', which is missing!"
     *   Forge/NeoForge: "Missing or unsupported mandatory dependencies: ... Mod ID: 'x'"
     */
    private fun detectMissingMod(text: String, installed: List<String>, language: Language): List<Finding> {
        val requested = linkedSetOf<String>()

        Regex("requires (?:any version of |version [^ ]+ of )?mod '([^']+)'", RegexOption.IGNORE_CASE)
            .findAll(text)
            .forEach { requested += it.groupValues[1].trim() }

        Regex("Mod ID: '([^']+)', Requested by: '([^']+)'", RegexOption.IGNORE_CASE)
            .findAll(text)
            .forEach { requested += it.groupValues[1].trim() }

        Regex("Missing or unsupported mandatory dependencies?[^:]*:?", RegexOption.IGNORE_CASE)
            .findAll(text)
            .forEach { header ->
                // The ids usually follow the header on the next few lines.
                val window = text.substring(header.range.last, minOf(text.length, header.range.last + 600))
                Regex("'([a-z0-9_.\\-]{3,})'", RegexOption.IGNORE_CASE)
                    .findAll(window)
                    .forEach { requested += it.groupValues[1].trim() }
            }

        val missing = requested
            .filter { id -> id.length >= 3 }
            .filterNot { id -> installed.any { token -> id.contains(token) || token.contains(id) } }
            .filterNot { id -> id.contains("minecraft") || id.contains("java") }
            .distinct()
            .take(4)

        if (missing.isEmpty()) return emptyList()

        return listOf(
            Finding(
                kind = Kind.MISSING_MOD,
                subjects = missing,
                headline = t(
                    language,
                    "Missing mod(s): ${missing.joinToString(", ")}",
                    "Thiếu mod: ${missing.joinToString(", ")}"
                ),
                detail = t(
                    language,
                    "One of your mods declares ${missing.joinToString(", ")} as a required dependency, " +
                        "but no jar for it is installed.",
                    "Một mod bạn đã cài khai báo ${missing.joinToString(", ")} là phụ thuộc bắt buộc, " +
                        "nhưng chưa có file jar của mod đó."
                ),
                fixes = listOf(
                    t(
                        language,
                        "Install ${missing.joinToString(", ")} from Modrinth/CurseForge in the same Minecraft version and loader.",
                        "Cài ${missing.joinToString(", ")} từ Modrinth/CurseForge đúng phiên bản Minecraft và mod loader."
                    ),
                    t(
                        language,
                        "Make sure the dependency is not renamed to .jar.disabled.",
                        "Kiểm tra xem file phụ thuộc có bị đổi tên thành .jar.disabled không."
                    ),
                    t(
                        language,
                        "If you do not want the parent mod either, remove it instead of adding dependencies.",
                        "Nếu không cần mod gốc nữa, hãy xoá mod gốc thay vì cài thêm phụ thuộc."
                    )
                )
            )
        )
    }

    /** Two installed mods fight over the same classes/mixins, or ship the same mod id twice. */
    private fun detectConflict(text: String, installed: List<String>, language: Language): List<Finding> {
        val conflicting = linkedSetOf<String>()

        // Fabric's DuplicateModsException lists "modid" -> [file, file]
        Regex("DuplicateModsFoundException[\\s\\S]{0,800}", RegexOption.IGNORE_CASE)
            .find(text)?.value?.let { block ->
                Regex("\"([a-z0-9_.\\-]{3,})\"", RegexOption.IGNORE_CASE)
                    .findAll(block)
                    .forEach { conflicting += it.groupValues[1] }
            }

        Regex("(?:conflicting|duplicate) mods?[: ]+([^\\n]{3,120})", RegexOption.IGNORE_CASE)
            .findAll(text)
            .forEach { match ->
                match.groupValues[1]
                    .split(',', ' ', ';')
                    .map { it.trim().trim('\'', '"') }
                    .filter { it.length >= 3 && it.none { char -> char.isDigit() } }
                    .forEach { conflicting += it }
            }

        // "Mixin apply failed ... mixin from mod X" plus a second mod named in the same trace is a
        // strong hint, but only if both jars actually exist here.
        val mixinMods = Regex("mixin[s]? from mod ([a-z0-9_.\\-]{3,})", RegexOption.IGNORE_CASE)
            .findAll(text)
            .map { it.groupValues[1] }
            .filter { id -> installed.any { token -> id.contains(token) } }
            .distinct()
            .toList()
        if (mixinMods.size >= 2) conflicting += mixinMods

        val real = conflicting
            .filter { id -> installed.any { token -> id.contains(token) || token.contains(id) } }
            .distinct()
            .take(4)

        if (real.size < 2) return emptyList()

        return listOf(
            Finding(
                kind = Kind.MOD_CONFLICT,
                subjects = real,
                headline = t(
                    language,
                    "Conflict between ${real.joinToString(" and ")}",
                    "Xung đột giữa ${real.joinToString(" và ")}"
                ),
                detail = t(
                    language,
                    "These mods claim the same classes or the same mod id. Only one of them can load per launch.",
                    "Các mod này dùng chung class hoặc cùng mod id; mỗi lần chạy chỉ một trong số chúng tải được."
                ),
                fixes = listOf(
                    t(
                        language,
                        "Open Mod management and disable one of ${real.joinToString(", ")}.",
                        "Mở Quản lý mod và tắt một trong ${real.joinToString(", ")}."
                    ),
                    t(
                        language,
                        "If one is an old fork (e.g. a 1.x build next to a 2.x build), delete the old jar.",
                        "Nếu một cái là bản cũ (ví dụ bản 1.x cạnh bản 2.x), hãy xoá file cũ."
                    ),
                    t(
                        language,
                        "Check that every mod targets the same loader (Fabric vs Forge vs NeoForge).",
                        "Kiểm tra mọi mod cùng một mod loader (Fabric / Forge / NeoForge)."
                    )
                )
            )
        )
    }

    /** Java version, memory and GPU failures - the three non-mod causes that dominate crashes. */
    private fun detectNonModCauses(text: String, version: Version?, language: Language): List<Finding> {
        val findings = mutableListOf<Finding>()

        if (text.contains("UnsupportedClassVersionError") || text.contains("class file version")) {
            findings += Finding(
                kind = Kind.JAVA_VERSION,
                headline = t(language, "Wrong Java version", "Sai phiên bản Java"),
                detail = t(
                    language,
                    "A class was compiled for a newer Java than the selected runtime provides. " +
                        "Minecraft 1.17+ needs Java 17 or newer; 1.20.5+ needs Java 21.",
                    "Có class được biên dịch cho Java mới hơn runtime đang chọn. Minecraft 1.17+ cần Java 17 trở lên; 1.20.5+ cần Java 21."
                ),
                fixes = listOf(
                    t(language, "Settings → Java: pick a runtime that matches this version.", "Cài đặt → Java: chọn runtime phù hợp với phiên bản này."),
                    t(language, "Reinstall the runtime if it is marked as broken.", "Cài lại runtime nếu nó đang bị đánh dấu lỗi.")
                )
            )
        }

        if (text.contains("OutOfMemoryError") || text.contains("Java heap space")) {
            findings += Finding(
                kind = Kind.MEMORY,
                headline = t(language, "Ran out of memory", "Hết bộ nhớ"),
                detail = t(
                    language,
                    "The JVM heap was exhausted. Modpacks with many mods need more RAM, but too much also hurts on a phone.",
                    "Heap của JVM đã cạn. Modpack nhiều mod cần thêm RAM, nhưng cấp quá nhiều trên điện thoại cũng có hại."
                ),
                fixes = listOf(
                    t(language, "Settings → Phone: raise the RAM allocation by 512–1024 MB.", "Cài đặt → Điện thoại: tăng RAM thêm 512–1024 MB."),
                    t(language, "Close other apps, then launch again.", "Đóng các ứng dụng khác rồi chạy lại."),
                    t(language, "If it still fails, lower render distance and remove heavy mods.", "Nếu vẫn lỗi, giảm render distance và bỏ bớt mod nặng.")
                )
            )
        }

        if (text.contains("GL_INVALID_OPERATION") || text.contains("Failed to create GL context") ||
            text.contains("EGL_BAD") || text.contains("Couldn't create window")
        ) {
            findings += Finding(
                kind = Kind.GPU,
                headline = t(language, "Renderer failed to start", "Renderer không khởi động được"),
                detail = t(
                    language,
                    "The graphics driver rejected the OpenGL/Vulkan context the renderer asked for.",
                    "Driver đồ hoạ từ chối context OpenGL/Vulkan mà renderer yêu cầu."
                ),
                fixes = listOf(
                    t(language, "Settings → Video: switch renderer (OpenGL ES / Vulkan-Zink / MobileGlues).", "Cài đặt → Video: đổi renderer (OpenGL ES / Vulkan-Zink / MobileGlues)."),
                    t(language, "Turn off shader packs and any FBO/allocation tweaks for one launch.", "Tắt shader pack và các tuỳ chọn FBO/phân bổ cho một lần chạy."),
                    t(language, "Update the device's GPU driver if the system offers one.", "Cập nhật driver GPU nếu hệ thống có bản mới.")
                )
            )
        }

        if (text.contains("DuplicateModsFoundException") || text.contains("ModResolutionException")) {
            // Already handled by detectConflict when two real jars are involved.
            findings += Finding(
                kind = Kind.LOADER_MISMATCH,
                headline = t(language, "The mod loader refused the mod set", "Mod loader từ chối bộ mod"),
                detail = t(
                    language,
                    "The loader could not resolve a valid mod graph: a dependency is missing, duplicated or for another Minecraft version.",
                    "Loader không dựng được đồ thị mod hợp lệ: thiếu phụ thuộc, bị trùng, hoặc sai phiên bản Minecraft."
                ),
                fixes = listOf(
                    t(language, "Read the first 'requires' line in the log above - it names the problem mod.", "Đọc dòng 'requires' đầu tiên trong log - nó ghi tên mod gây lỗi."),
                    t(language, "Remove mods added since the last successful launch, then add them back one by one.", "Bỏ các mod mới thêm từ lần chạy thành công trước, rồi thêm lại từng cái.")
                )
            )
        }

        if (version != null) {
            val loader = runCatching { version.getVersionName() }.getOrNull()
            if (loader != null && text.contains("Mixin apply failed", ignoreCase = true) &&
                text.contains("is not compatible", ignoreCase = true)
            ) {
                findings += Finding(
                    kind = Kind.LOADER_MISMATCH,
                    headline = t(language, "A mod does not support this Minecraft version", "Có mod không hỗ trợ phiên bản Minecraft này"),
                    detail = t(
                        language,
                        "A mixin could not be applied because the game classes it targets changed in this version.",
                        "Mixin không áp dụng được vì class game mà nó nhắm tới đã thay đổi ở phiên bản này."
                    ),
                    fixes = listOf(
                        t(language, "Update that mod to a build made for $loader.", "Cập nhật mod đó lên bản dành cho $loader."),
                        t(language, "If there is no update, remove it.", "Nếu chưa có bản cập nhật, hãy xoá mod đó.")
                    )
                )
            }
        }

        return findings
    }

    // ── Localisation of CrashAnalyzer's own diagnoses ─────────────────────────

    private fun localizeTitle(title: String, language: Language): String =
        if (language == Language.EN) title else VI_TITLES[title] ?: title

    private fun localizeCause(diagnosis: CrashAnalyzer.Diagnosis, language: Language): String =
        if (language == Language.EN) diagnosis.cause else VI_CAUSES[diagnosis.title] ?: diagnosis.cause

    private fun localizeFixes(diagnosis: CrashAnalyzer.Diagnosis, language: Language): List<String> =
        if (language == Language.EN) diagnosis.fixSteps
        else VI_FIXES[diagnosis.title] ?: diagnosis.fixSteps

    /**
     * Vietnamese for the launcher's most common diagnoses. Anything not listed keeps the English
     * text rather than showing a machine-translated half-sentence.
     */
    private val VI_TITLES = mapOf(
        "Game appears to be frozen (no crash reported)" to "Game bị treo (không có log crash)",
        "Out of memory" to "Hết bộ nhớ",
        "The game ran out of memory" to "Game đã hết bộ nhớ",
        "Wrong Java version" to "Sai phiên bản Java",
        "Java version mismatch" to "Java không khớp phiên bản",
        "Renderer failed to start" to "Renderer không khởi động được",
        "Graphics driver rejected the renderer" to "Driver đồ hoạ từ chối renderer",
        "Missing mod dependency" to "Thiếu mod phụ thuộc",
        "Mod conflict" to "Xung đột mod",
        "The game crashed during startup" to "Game crash khi khởi động",
        "Corrupted game files" to "File game bị hỏng",
        "Corrupted library" to "Thư viện bị hỏng",
        "Native library not found" to "Không tìm thấy thư viện native"
    )

    private val VI_CAUSES = mapOf(
        "Game appears to be frozen (no crash reported)" to
            "Tiến trình Minecraft vẫn chạy nhưng log không có dòng mới. Thường do nạp shader/world/datafixer quá lâu trên GPU yếu, hoặc render thread chờ GPU mà không báo lỗi.",
        "Out of memory" to "JVM đã dùng hết heap được cấp.",
        "The game ran out of memory" to "JVM đã dùng hết heap được cấp.",
        "Wrong Java version" to "Class được biên dịch cho bản Java mới hơn runtime đang chọn.",
        "Java version mismatch" to "Class được biên dịch cho bản Java mới hơn runtime đang chọn.",
        "Renderer failed to start" to "Driver từ chối context OpenGL/Vulkan mà renderer yêu cầu.",
        "Graphics driver rejected the renderer" to "Driver từ chối context OpenGL/Vulkan mà renderer yêu cầu."
    )

    private val VI_FIXES = mapOf(
        "Game appears to be frozen (no crash reported)" to listOf(
            "Chờ thêm một chút - lần chạy đầu sau khi cài/cập nhật có thể mất rất lâu để biên dịch shader và datafixer.",
            "Nếu không hồi phục, buộc dừng rồi đổi renderer (OpenGL ES / Vulkan-Zink) trong Cài đặt → Video.",
            "Giảm RAM cấp cho game nếu máy đang thiếu bộ nhớ.",
            "Kiểm tra máy có đang bị quá nhiệt (thermal throttling) không."
        ),
        "Out of memory" to listOf(
            "Tăng RAM trong Cài đặt → Điện thoại (thêm 512-1024 MB).",
            "Đóng ứng dụng khác trước khi chạy game.",
            "Giảm render distance hoặc bỏ bớt mod nặng."
        ),
        "Wrong Java version" to listOf(
            "Cài đặt → Java: chọn runtime đúng với phiên bản Minecraft.",
            "Cài lại runtime nếu nó đang báo lỗi."
        )
    )

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun installedModTokens(modsFolder: File?): List<String> {
        modsFolder ?: return emptyList()
        if (!modsFolder.isDirectory) return emptyList()
        return runCatching {
            ModScanCache.scan(modsFolder).jars.values.flatMap { it.tokens }.distinct()
        }.getOrDefault(emptyList())
    }

    private fun newestLog(version: Version?): File? {
        val candidates = mutableListOf<File>()
        runCatching { LatestLogResolver.resolveLastGameLogFile() }.getOrNull()?.let { candidates += it }
        runCatching { LatestLogResolver.resolveLatestLogFile() }.getOrNull()?.let { candidates += it }
        version?.let { v ->
            runCatching { File(v.getGameDir(), "logs/latest.log") }.getOrNull()?.let { candidates += it }
        }
        return candidates.filter { it.isFile && it.length() > 0 }.maxByOrNull { it.lastModified() }
    }

    private fun newestCrashReport(version: Version): File? = runCatching {
        val dir = File(version.getGameDir(), "crash-reports")
        if (!dir.isDirectory) return null
        dir.listFiles { file -> file.isFile && file.extension == "txt" }
            ?.maxByOrNull { it.lastModified() }
    }.getOrNull()

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

    private fun t(language: Language, en: String, vi: String): String =
        if (language == Language.VI) vi else en

    /** Kept for callers that want the launcher's current locale code. */
    @JvmStatic
    fun currentLocaleCode(): String =
        runCatching { LocaleHelper.selectedLocale()?.language }.getOrNull()
            ?: Locale.getDefault().language
}
