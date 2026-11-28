package com.endiq.turtlelauncher.feature.ai

import android.content.Context
import com.endiq.turtlelauncher.feature.log.Logging
import com.endiq.turtlelauncher.renderer.Renderers
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.setting.unit.BooleanSettingUnit
import com.endiq.turtlelauncher.setting.unit.IntSettingUnit
import net.endiq.launcher.Tools
import java.util.Locale

/**
 * One change the assistant wants to make, together with the sentence that describes it.
 *
 * The action is a closure, and it is never run by the assistant: [apply] is called by the chat
 * screen *after* the user pressed Apply on the confirmation list. That is the whole safety
 * model - the model can propose anything it can name, but a setting only moves because a human
 * tapped "Áp dụng".
 */
class TurtleAiAction(
    /** Short imperative label, e.g. "Đổi renderer sang Holy GL4ES". */
    val title: String,
    /** Before → after, so the confirmation list is checkable at a glance. */
    val detail: String,
    private val change: () -> String
) {
    /** Runs the change once and reports what happened. Never throws. */
    fun apply(): String = runCatching { change() }
        .onFailure { e -> Logging.e("TurtleAiAction", "Action failed: $title", e) }
        .getOrElse { e -> "✗ $title - " + (e.message ?: e.javaClass.simpleName) }
}

/**
 * The assistant's hands: a catalogue of launcher changes it is allowed to *propose*, plus the
 * two parsers that turn a request into those proposals.
 *
 * Two entry points, one catalogue:
 *  - [plan] reads the message itself (Vietnamese and English) and proposes the change the user
 *    asked for - "tắt vsync", "đổi sang Holy GL4ES", "đặt 2GB RAM", "thêm -XX:+UseSerialGC",
 *    "tìm mod sodium". Deterministic, offline, no key needed.
 *  - [splitModelActions] reads the `[[action]]` lines the cloud model may append to an answer,
 *    so an open-ended request ("make the game smoother on my phone") can end in concrete
 *    changes too - still validated against the same catalogue, so a hallucinated setting id is
 *    dropped instead of written.
 *
 * Nothing here saves a setting on its own; see [TurtleAiAction.apply].
 */
object TurtleAiActions {

    private const val TAG = "TurtleAiActions"

    /** Marker the model uses; the chat strips these lines out of the visible answer. */
    const val MODEL_MARKER = "[[action]]"

    /** A reply that consists of proposals: the text to show plus the changes to confirm. */
    data class Plan(val text: String, val actions: List<TurtleAiAction> = emptyList())

    /** A boolean setting the assistant may offer to switch. [keywords] are matched lowercased. */
    private class Toggle(
        val id: String,
        val label: String,
        val keywords: List<String>,
        val unit: BooleanSettingUnit
    )

    /**
     * The whitelist. Every entry is a real `BooleanSettingUnit` in [AllSettings] with a label a
     * person would recognise; a setting that is not here cannot be changed from chat, by the
     * model or by a typed request.
     */
    private val TOGGLES: List<Toggle> = listOf(
        Toggle("force_vsync", "VSync", listOf("vsync"), AllSettings.forceVsync),
        Toggle(
            "sustained_performance", "Sustained performance",
            listOf("sustained performance", "hiệu năng bền", "sustained"), AllSettings.sustainedPerformance
        ),
        Toggle(
            "shader_cache", "Shader cache",
            listOf("shader cache", "bộ nhớ đệm shader", "cache shader"), AllSettings.rendererShaderCacheEnabled
        ),
        Toggle(
            "renderer_debug_log", "Renderer debug log",
            listOf("renderer debug", "debug log", "log renderer", "nhật ký renderer"), AllSettings.rendererDebugLogging
        ),
        Toggle(
            "adaptive_motion", "Adaptive motion",
            listOf("adaptive motion", "hoạt ảnh thích ứng", "adaptive", "giảm hoạt ảnh"), AllSettings.adaptiveMotion
        ),
        Toggle(
            "ui_thread_priority", "UI thread priority",
            listOf("ui thread", "thread priority", "ưu tiên luồng giao diện", "ưu tiên luồng"), AllSettings.uiThreadPriority
        ),
        Toggle(
            "auto_optimizer", "Auto settings optimizer",
            listOf("auto optimizer", "tối ưu tự động", "auto settings", "tự tối ưu"), AllSettings.autoSettingsOptimizer
        ),
        Toggle(
            "low_latency", "Low-latency front buffer",
            listOf("low latency", "độ trễ thấp", "low_latency"), AllSettings.lowLatencyFrontBuffer
        ),
        Toggle(
            "alternate_surface", "Alternate surface",
            listOf("alternate surface", "surface thay thế"), AllSettings.alternateSurface
        ),
        Toggle(
            "ignore_notch", "Ignore notch",
            listOf("notch", "tai thỏ"), AllSettings.ignoreNotch
        ),
        Toggle(
            "discord_rpc", "Discord Rich Presence",
            listOf("discord", "rich presence"), AllSettings.discordRpcPresenceEnabled
        ),
        Toggle("music", "Launcher music", listOf("music", "nhạc", "nhạc nền"), AllSettings.musicEnabled),
        Toggle(
            "virtual_mouse", "Virtual mouse",
            listOf("virtual mouse", "chuột ảo"), AllSettings.virtualMouseStart
        ),
        Toggle("gyro", "Gyroscope aiming", listOf("gyro", "con quay", "cảm biến xoay"), AllSettings.enableGyro),
        Toggle("emotes", "Emotes", listOf("emote", "biểu cảm"), AllSettings.emotesEnabled),
        Toggle(
            "web_search", "AI web search",
            listOf("web search", "tìm kiếm web", "search internet", "tìm trên mạng"), AllSettings.aiWebSearchEnabled
        ),
        Toggle(
            "ai_brain", "AI brain (Gemini)",
            listOf("ai brain", "bộ não ai", "gemini brain", "trợ lý ai"), AllSettings.aiAssistantCloudBrain
        ),
        Toggle(
            "chat_history", "Assistant history",
            listOf("chat history", "lịch sử chat", "lịch sử trò chuyện"), AllSettings.aiAssistantHistoryEnabled
        ),
        Toggle(
            "auto_cleanup", "Automatic cleanup",
            listOf("auto cleanup", "tự dọn dẹp", "dọn dẹp tự động"), AllSettings.autoCleanupEnabled
        )
    )

    /** Integer settings the assistant may set, by the id the model is told to use. */
    private val INT_SETTINGS: Map<String, IntSettingUnit> = mapOf(
        "resolutionRatio" to AllSettings.resolutionRatio,
        "buttonScale" to AllSettings.buttonScale,
        "mouseScale" to AllSettings.mouseScale
    )

    /** Ids offered to the model, so its prompt and this catalogue cannot drift apart. */
    @JvmStatic
    fun toggleIds(): List<String> = TOGGLES.map { it.id }

    @JvmStatic
    fun intSettingIds(): List<String> = INT_SETTINGS.keys.toList()

    // ── Typed requests ──────────────────────────────────────────────────────────────

    private val ON_WORDS = listOf("bật", "mở", "kích hoạt", "enable", "turn on", " on", "bật lên")
    private val OFF_WORDS = listOf("tắt", "vô hiệu", "disable", "turn off", " off", "tắt đi")
    private val SET_WORDS = listOf(
        "đặt", "chỉnh", "đổi thành", "thành", "lên", "set ", "change to", "make it", "allocate"
    )

    /** Phrasing that asks for an explanation (the topic engine's job), not an action. */
    private val QUESTION_WORDS = listOf(
        "làm sao", "làm thế nào", "như thế nào", "thế nào", "là gì", "vì sao", "tại sao",
        "how to", "how do i", "how can i", "what is", "what's", "why ", "should i"
    )

    /** Words that turn a sentence about a mod into a request to find or install one. */
    private val MOD_INTENTS = listOf(
        "tìm mod", "tìm và tải mod", "tìm giúp mod", "tải mod", "cài mod", "cài đặt mod",
        "tìm modpack", "find a mod", "find mod", "search mod", "search for a mod",
        "download a mod", "download mod", "install a mod", "install mod", "get me a mod"
    )

    /**
     * The change this message asks for, or null when it is a normal question.
     *
     * Null is the common case: a question about VSync ("VSync là gì?") must not move a setting,
     * which is why every branch below requires an intent word as well as its subject.
     */
    @JvmStatic
    fun plan(context: Context, raw: String, english: Boolean): Plan? {
        val text = " " + raw.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ") + " "
        // A question gets an explanation, not a dialog: "làm sao bật..." is asking how the
        // feature works, and answering it with a confirmation list would be the wrong reply.
        if (QUESTION_WORDS.any { text.contains(it) }) return null
        return modPlan(text, english)
            ?: rendererPlan(context, text, english)
            ?: togglePlan(text, english)
            ?: javaArgsPlan(raw, text, english)
            ?: ramPlan(context, text, english)
            ?: intPlan(text, english)
    }

    /** "tìm mod sodium" / "cài mod lithium" -> search Modrinth, offer the top results. */
    private fun modPlan(text: String, english: Boolean): Plan? {
        val intent = MOD_INTENTS.firstOrNull { text.contains(it) } ?: return null
        val query = text.substringAfter(intent).trim()
            .replace(Regex("\\b(hộ|giúp|cho tôi|cho mình|mình|với|nhé|đi|the|a|an|for|me|please)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (query.isEmpty()) {
            return Plan(
                if (english) "Which mod? For example: \"find a mod for better FPS\"."
                else "Bạn muốn tìm mod nào? Ví dụ: \"tìm mod giúp tăng FPS\"."
            )
        }
        val hits = TurtleAiMods.search(query, 3)
        if (hits.isEmpty()) {
            return Plan(
                if (english) "I couldn't reach Modrinth, or nothing matched \"$query\"."
                else "Không kết nối được Modrinth, hoặc không có kết quả cho \"$query\"."
            )
        }
        val lines = hits.joinToString("\n") { hit ->
            "• ${hit.title} (${hit.slug}) - ${TurtleAiMods.formatDownloads(hit.downloads)}" +
                if (hit.description.isBlank()) "" else "\n  ${hit.description}"
        }
        val actions = hits.map { hit ->
            TurtleAiAction(
                title = if (english) "Install ${hit.title}" else "Cài ${hit.title}",
                detail = if (english)
                    "${hit.slug} → mods folder of the selected version"
                else
                    "${hit.slug} → thư mục mods của phiên bản đang chọn"
            ) { TurtleAiMods.install(hit.slug) }
        }
        val head = if (english) "Found on Modrinth for \"$query\":"
        else "Tìm trên Modrinth cho \"$query\":"
        return Plan(head + "\n" + lines + "\n\n" + planText(actions, english), actions)
    }

    /** "đổi renderer sang Holy GL4ES" -> switch, provided that renderer runs on this device. */
    private fun rendererPlan(context: Context, text: String, english: Boolean): Plan? {
        val aboutRenderer = text.contains("renderer") || text.contains("kết xuất") ||
            text.contains(" render ")
        if (!aboutRenderer) return null
        val compatible = runCatching {
            Renderers.init(false)
            Renderers.getCompatibleRenderers(context).second
        }.getOrNull().orEmpty()
        if (compatible.isEmpty()) return null

        fun squash(value: String) = value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
        val flat = squash(text)
        val match = compatible.firstOrNull { renderer ->
            val name = squash(runCatching { renderer.getRendererName() }.getOrDefault(""))
            name.isNotEmpty() && flat.contains(name)
        } ?: compatible.firstOrNull { renderer ->
            val id = squash(runCatching { renderer.getRendererId() }.getOrDefault(""))
            id.isNotEmpty() && flat.contains(id)
        } ?: return null

        val currentId = runCatching { AllSettings.renderer.getValue() }.getOrDefault("")
        val targetId = match.getUniqueIdentifier()
        val name = runCatching { match.getRendererName() }.getOrDefault(targetId)
        if (currentId == targetId) {
            return Plan(
                if (english) "You're already on $name."
                else "Bạn đang dùng $name rồi."
            )
        }
        val currentName = compatible.firstOrNull { it.getUniqueIdentifier() == currentId }
            ?.let { runCatching { it.getRendererName() }.getOrNull() }
            ?: (if (english) "the saved choice" else "lựa chọn đã lưu")
        val action = TurtleAiAction(
            title = if (english) "Switch renderer to $name" else "Đổi renderer sang $name",
            detail = "$currentName → $name"
        ) {
            AllSettings.renderer.put(targetId).save()
            "✓ " + (if (english) "Renderer is now $name" else "Renderer giờ là $name")
        }
        return Plan(planText(listOf(action), english), listOf(action))
    }

    /** "tắt vsync" / "bật chế độ hiệu năng bền" -> flip one whitelisted boolean. */
    private fun togglePlan(text: String, english: Boolean): Plan? {
        val toggle = TOGGLES.firstOrNull { entry -> entry.keywords.any { text.contains(it) } } ?: return null
        val off = OFF_WORDS.any { text.contains(it) }
        val on = ON_WORDS.any { text.contains(it) }
        if (!off && !on) return null
        val value = !off
        val current = runCatching { toggle.unit.getValue() }.getOrDefault(!value)
        if (current == value) {
            return Plan(
                if (english) "${toggle.label} is already ${onOff(value, english)}."
                else "${toggle.label} đang ${onOff(value, english)} rồi."
            )
        }
        val action = TurtleAiAction(
            title = (if (english) (if (value) "Turn on " else "Turn off ") else (if (value) "Bật " else "Tắt ")) +
                toggle.label,
            detail = toggle.label + ": " + onOff(current, english) + " → " + onOff(value, english)
        ) {
            toggle.unit.put(value).save()
            "✓ " + toggle.label + " → " + onOff(value, english)
        }
        return Plan(planText(listOf(action), english), listOf(action))
    }

    /** "thêm -XX:+UseSerialGC" -> append the flags to the launcher's JVM arguments. */
    private fun javaArgsPlan(raw: String, text: String, english: Boolean): Plan? {
        val aboutJvm = text.contains("jvm") || text.contains("java args") ||
            text.contains("tham số java") || text.contains("java flag") || text.contains("-xx")
        if (!aboutJvm) return null
        val flags = JVM_FLAG.findAll(raw).map { it.value }.filter { it.length >= 4 }.toList()
        if (flags.isEmpty()) return null
        val current = runCatching { AllSettings.javaArgs.getValue() }.getOrDefault("")
        val missing = flags.filter { !current.contains(it) }
        if (missing.isEmpty()) {
            return Plan(
                if (english) "Those JVM flags are already in your arguments."
                else "Các cờ JVM đó đã có trong tham số của bạn rồi."
            )
        }
        val joined = missing.joinToString(" ")
        val action = TurtleAiAction(
            title = if (english) "Add JVM flags" else "Thêm cờ JVM",
            detail = joined
        ) {
            AllSettings.javaArgs.put((current + " " + joined).trim()).save()
            "✓ " + (if (english) "JVM arguments now include " else "Tham số JVM đã có ") + joined
        }
        return Plan(planText(listOf(action), english), listOf(action))
    }

    /** "đặt 2GB RAM" -> set the heap size, clamped to what this device can actually give. */
    private fun ramPlan(context: Context, text: String, english: Boolean): Plan? {
        val aboutRam = text.contains("ram") || text.contains("bộ nhớ") || text.contains("memory")
        if (!aboutRam || SET_WORDS.none { text.contains(it) }) return null
        val match = AMOUNT.find(text) ?: return null
        val amount = match.groupValues[1].toIntOrNull() ?: return null
        val mb = if (match.groupValues[2].startsWith("g")) amount * 1024 else amount
        if (mb < 256) return null

        val total = runCatching { Tools.getTotalDeviceMemory(context) }.getOrDefault(0)
        val ceiling = if (total > 2048) total - 1024 else 4096
        val target = (((mb + 128) / 256) * 256).coerceIn(512, ceiling.coerceAtLeast(512))

        val unit = AllSettings.ramAllocation.value
        val current = runCatching { unit.getValue() }.getOrDefault(target)
        if (current == target) {
            return Plan(
                if (english) "RAM is already set to ${target}MB."
                else "RAM đang được đặt ${target}MB rồi."
            )
        }
        val action = TurtleAiAction(
            title = if (english) "Set RAM to ${target}MB" else "Đặt RAM thành ${target}MB",
            detail = "${current}MB → ${target}MB"
        ) {
            unit.put(target).save()
            "✓ " + (if (english) "RAM is now ${target}MB" else "RAM giờ là ${target}MB")
        }
        return Plan(planText(listOf(action), english), listOf(action))
    }

    /** "đặt độ phân giải 75%" and the other integer settings in [INT_SETTINGS]. */
    private fun intPlan(text: String, english: Boolean): Plan? {
        if (SET_WORDS.none { text.contains(it) }) return null
        val percent = PERCENT.find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (percent != null && (text.contains("độ phân giải") || text.contains("resolution"))) {
            val target = percent.coerceIn(30, 100)
            val unit = INT_SETTINGS["resolutionRatio"] ?: return null
            val current = runCatching { unit.getValue() }.getOrDefault(target)
            if (current == target) {
                return Plan(if (english) "Resolution scale is already $target%." else "Độ phân giải đang là $target% rồi.")
            }
            val action = TurtleAiAction(
                title = if (english) "Set resolution scale to $target%" else "Đặt độ phân giải $target%",
                detail = "$current% → $target%"
            ) {
                unit.put(target).save()
                "✓ " + (if (english) "Resolution scale is now $target%" else "Độ phân giải giờ là $target%")
            }
            return Plan(planText(listOf(action), english), listOf(action))
        }
        return null
    }

    // ── Model-driven actions ────────────────────────────────────────────────────────

    /**
     * Pulls `[[action]]` lines out of a model answer: the visible text comes back cleaned, and
     * the lines become proposals through the same catalogue. An id that isn't in the catalogue
     * is dropped (and logged) rather than silently ignored by a setting write.
     */
    @JvmStatic
    fun splitModelActions(context: Context, text: String, english: Boolean): Pair<String, List<TurtleAiAction>> {
        if (!text.contains(MODEL_MARKER)) return text to emptyList()
        val kept = StringBuilder()
        val lines = mutableListOf<String>()
        text.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith(MODEL_MARKER)) {
                lines += trimmed.removePrefix(MODEL_MARKER).trim()
            } else {
                kept.append(line).append('\n')
            }
        }
        val actions = fromModelLines(context, lines, english)
        return kept.toString().trim() to actions
    }

    /** Builds proposals from the model's compact action lines. Unknown ids are dropped. */
    @JvmStatic
    fun fromModelLines(context: Context, lines: List<String>, english: Boolean): List<TurtleAiAction> {
        val actions = mutableListOf<TurtleAiAction>()
        lines.forEach { line ->
            runCatching { actionFromLine(context, line, english) }
                .onFailure { e -> Logging.w(TAG, "Dropped unreadable action line: $line", e) }
                .getOrNull()
                ?.let { actions += it }
        }
        return actions
    }

    private fun actionFromLine(context: Context, line: String, english: Boolean): TurtleAiAction? {
        val parts = line.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.size < 2) return null
        return when (parts[0].lowercase(Locale.ROOT)) {
            "toggle" -> {
                val toggle = TOGGLES.firstOrNull { it.id == parts[1].lowercase(Locale.ROOT) } ?: return null
                val value = parts.getOrNull(2)?.let { !it.equals("off", true) && !it.equals("false", true) }
                    ?: return null
                val current = runCatching { toggle.unit.getValue() }.getOrDefault(!value)
                TurtleAiAction(
                    title = (if (value) "Bật " else "Tắt ") + toggle.label,
                    detail = toggle.label + ": " + onOff(current, english) + " → " + onOff(value, english)
                ) {
                    toggle.unit.put(value).save()
                    "✓ " + toggle.label + " → " + onOff(value, english)
                }
            }
            "renderer" -> {
                val wanted = parts[1].lowercase(Locale.ROOT)
                val compatible = runCatching {
                    Renderers.init(false)
                    Renderers.getCompatibleRenderers(context).second
                }.getOrNull().orEmpty()
                fun squash(value: String) = value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
                val match = compatible.firstOrNull { renderer ->
                    squash(runCatching { renderer.getRendererId() }.getOrDefault("")) == squash(wanted) ||
                        squash(runCatching { renderer.getRendererName() }.getOrDefault("")) == squash(wanted)
                } ?: return null
                val id = match.getUniqueIdentifier()
                val name = runCatching { match.getRendererName() }.getOrDefault(id)
                TurtleAiAction(
                    title = "Đổi renderer sang $name",
                    detail = name
                ) {
                    AllSettings.renderer.put(id).save()
                    "✓ Renderer → $name"
                }
            }
            "java_args" -> {
                val flags = parts.drop(2).joinToString(" ").trim()
                if (flags.isBlank()) return null
                val current = runCatching { AllSettings.javaArgs.getValue() }.getOrDefault("")
                TurtleAiAction(
                    title = if (english) "Add JVM flags" else "Thêm cờ JVM",
                    detail = flags
                ) {
                    AllSettings.javaArgs.put((current + " " + flags).trim()).save()
                    "✓ JVM: $flags"
                }
            }
            "setting" -> {
                if (parts.size < 3) return null
                val id = parts[1]
                val value = parts[2].toIntOrNull() ?: return null
                if (id == "ramAllocation") {
                    val unit = AllSettings.ramAllocation.value
                    val current = runCatching { unit.getValue() }.getOrDefault(value)
                    TurtleAiAction("Đặt RAM thành ${value}MB", "${current}MB → ${value}MB") {
                        unit.put(value).save()
                        "✓ RAM → ${value}MB"
                    }
                } else {
                    val unit = INT_SETTINGS[id] ?: return null
                    val current = runCatching { unit.getValue() }.getOrDefault(value)
                    TurtleAiAction("Đặt $id = $value", "$current → $value") {
                        unit.put(value).save()
                        "✓ $id → $value"
                    }
                }
            }
            "mod" -> {
                // "mod install sodium" - the verb is optional, the slug is not.
                val slug = parts.last().lowercase(Locale.ROOT)
                if (slug == "install" || slug == "add") return null
                TurtleAiAction(
                    title = if (english) "Install $slug" else "Cài $slug",
                    detail = "$slug → mods"
                ) { TurtleAiMods.install(slug) }
            }
            else -> null
        }
    }

    // ── Text helpers ────────────────────────────────────────────────────────────────

    /** The bullet list shown above the confirmation note, in the reply itself. */
    @JvmStatic
    fun planText(actions: List<TurtleAiAction>, english: Boolean): String {
        val bullets = actions.joinToString("\n") { "• ${it.title}\n  ${it.detail}" }
        return if (bullets.isBlank()) confirmNote(english) else bullets + "\n\n" + confirmNote(english)
    }

    @JvmStatic
    fun confirmNote(english: Boolean): String = if (english)
        "Nothing has been changed yet. Tap Apply to make these changes, or Cancel to keep the " +
            "launcher as it is."
    else
        "Chưa có gì bị thay đổi. Bấm Áp dụng để mình thực hiện, hoặc Huỷ để giữ nguyên launcher."

    private fun onOff(value: Boolean, english: Boolean): String = when {
        value && english -> "on"
        value -> "bật"
        english -> "off"
        else -> "tắt"
    }

    /** `-XX:+UseSerialGC`, `-Xmx2G`, `-Dfoo=bar` - JVM-shaped tokens, nothing else. */
    private val JVM_FLAG = Regex("-{1,2}(?:XX:|X|D|verbose)[A-Za-z0-9:+=\\-_.]*")

    /** "2048MB", "2 GB", "4g". */
    private val AMOUNT = Regex("(\\d{1,5})\\s*(mb|mib|m|gb|gib|g)(?![a-z])")

    /** "75%". */
    private val PERCENT = Regex("(\\d{2,3})\\s*%")
}
