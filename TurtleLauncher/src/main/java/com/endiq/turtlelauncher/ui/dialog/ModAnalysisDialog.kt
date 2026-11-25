package com.endiq.turtlelauncher.ui.dialog

import android.content.Context
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.endiq.turtlelauncher.R
import com.endiq.turtlelauncher.databinding.DialogModAnalysisBinding
import com.endiq.turtlelauncher.feature.ai.TurtleAiGemini
import com.endiq.turtlelauncher.feature.log.CrashExplainer
import com.endiq.turtlelauncher.feature.mod.ModAnalysisDigest
import com.endiq.turtlelauncher.feature.mod.ModCrashAnalyzer
import com.endiq.turtlelauncher.setting.AllSettings
import com.endiq.turtlelauncher.task.Task
import com.endiq.turtlelauncher.task.TaskExecutors
import java.io.RandomAccessFile

/**
 * The mod screen's "Phân tích" result.
 *
 * It replaces the old free-text dialog with the four answers a player asks for - thiếu mod /
 * thêm mod / lỗi mod / cách fix - always in the launcher's language (Vietnamese on a Vietnamese
 * launcher), and it carries its own Gemini API-key field so AI analysis can be switched on right
 * where the log is, without hunting through Settings.
 *
 * The offline digest is always shown first; the AI pass is optional and only *rewrites/extends*
 * the same four sections, so a missing key or a failed request never hides the rule-engine answer.
 */
class ModAnalysisDialog(
    private val context: Context,
    private val report: ModCrashAnalyzer.Report,
    private val explanation: CrashExplainer.Explanation?,
    private val auto: Boolean
) {
    companion object {
        private const val MAX_LOG_CHARS = 6000

        private val AI_SYSTEM_PROMPT = """
            Bạn là trợ lý phân tích lỗi mod Minecraft trên Android của Turtle Launcher.
            Chỉ trả lời bằng tiếng Việt và CHỈ dùng đúng bốn mục sau, theo đúng thứ tự:

            THIẾU MOD:
            - (mod còn thiếu, hoặc "Không thiếu mod nào")

            THÊM MOD:
            - (mod cần thêm để chạy được, hoặc "Không cần thêm mod nào")

            LỖI MOD:
            - (mod gây lỗi / xung đột, hoặc "Chưa xác định được mod lỗi")

            CÁCH FIX:
            1. (bước làm cụ thể, ngắn gọn)

            Không thêm mở bài, kết luận, hay mục nào khác. Không bịa tên mod không có trong log.
            Nếu log không đủ dữ liệu, ghi rõ là chưa đủ dữ liệu trong mục tương ứng.
        """.trimIndent()
    }

    fun show() {
        if (!report.hasLog) {
            Toast.makeText(context, R.string.profile_mods_analyze_no_log, Toast.LENGTH_LONG).show()
            return
        }

        val binding = DialogModAnalysisBinding.inflate(LayoutInflater.from(context))
        val digest = ModAnalysisDigest.from(explanation, report)

        fun render(aiText: String?): String = buildString {
            appendLine(context.getString(R.string.mod_analysis_log_line, report.logFile?.name.orEmpty()))
            appendLine()
            section(context.getString(R.string.mod_analysis_missing_header), digest.missing, context.getString(R.string.mod_analysis_none))
            section(context.getString(R.string.mod_analysis_add_header), digest.toAdd, context.getString(R.string.mod_analysis_add_none))
            section(context.getString(R.string.mod_analysis_broken_header), digest.broken, context.getString(R.string.mod_analysis_unknown))
            appendLine(context.getString(R.string.mod_analysis_fix_header))
            if (digest.fixes.isEmpty()) {
                appendLine(context.getString(R.string.mod_analysis_fix_fallback))
            } else {
                digest.fixes.forEachIndexed { index, fix -> appendLine("${index + 1}. $fix") }
            }
            if (report.suspects.isNotEmpty()) {
                appendLine()
                appendLine(
                    context.getString(
                        R.string.mod_analysis_suspects,
                        report.suspects.joinToString(", ") { "${it.jar.name} (${it.hits})" }
                    )
                )
            }
            if (!aiText.isNullOrBlank()) {
                appendLine()
                appendLine(context.getString(R.string.mod_analysis_ai_section))
                appendLine(aiText.trim())
            }
            trimEnd().toString()
        }

        binding.analysisText.text = render(null)
        binding.apiKeyInput.setText(AllSettings.aiGeminiApiKey.getValue().orEmpty())

        val dialog = AlertDialog.Builder(context)
            .setTitle(
                if (auto) R.string.profile_mods_analyze_auto_title
                else R.string.mod_analysis_title
            )
            .setView(binding.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.generic_ok, null)
            .create()

        binding.aiAnalyzeButton.setOnClickListener {
            val key = binding.apiKeyInput.text.toString().trim()
            if (key.isEmpty() && TurtleAiGemini.apiKey().isEmpty()) {
                binding.aiStatusText.visibility = android.view.View.VISIBLE
                binding.aiStatusText.text = context.getString(R.string.mod_analysis_api_key_needed)
                return@setOnClickListener
            }
            if (key.isNotEmpty()) AllSettings.aiGeminiApiKey.put(key).save()

            val logTail = readLogTail()
            if (logTail.isBlank()) {
                binding.aiStatusText.visibility = android.view.View.VISIBLE
                binding.aiStatusText.text = context.getString(R.string.profile_mods_analyze_no_log)
                return@setOnClickListener
            }

            binding.aiAnalyzeButton.isEnabled = false
            binding.aiStatusText.visibility = android.view.View.VISIBLE
            binding.aiStatusText.text = context.getString(R.string.mod_analysis_ai_running)
            val offline = binding.analysisText.text.toString()
            val userText = buildString {
                appendLine("Nhật ký lỗi gần nhất (phần cuối):")
                appendLine(logTail)
                appendLine()
                appendLine("Kết quả phân tích offline của launcher:")
                appendLine(offline)
            }

            Task.runTask {
                TurtleAiGemini.complete(
                    systemPrompt = AI_SYSTEM_PROMPT,
                    userText = userText,
                    temperature = 0.1,
                    maxOutputTokens = 900
                )
            }.ended(TaskExecutors.getAndroidUI()) { answer ->
                binding.aiAnalyzeButton.isEnabled = true
                if (answer.isNullOrBlank()) {
                    binding.aiStatusText.text = context.getString(
                        R.string.mod_analysis_ai_failed,
                        TurtleAiGemini.failureReason().orEmpty()
                    )
                } else {
                    binding.aiStatusText.text = context.getString(R.string.mod_analysis_ai_done)
                    binding.analysisText.text = render(answer)
                }
            }.onThrowable { error ->
                binding.aiAnalyzeButton.isEnabled = true
                binding.aiStatusText.text = context.getString(
                    R.string.mod_analysis_ai_failed,
                    error.message.orEmpty()
                )
            }.execute()
        }

        dialog.show()
    }

    private fun StringBuilder.section(header: String, values: List<String>, emptyText: String) {
        appendLine(header)
        if (values.isEmpty()) appendLine(emptyText) else values.forEach { appendLine("- $it") }
        appendLine()
    }

    /** Only the tail of the log matters, and a heavily modded session writes megabytes. */
    private fun readLogTail(): String {
        val file = report.logFile ?: return ""
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val start = (raf.length() - MAX_LOG_CHARS).coerceAtLeast(0L)
                raf.seek(start)
                val size = (raf.length() - start).toInt()
                if (size <= 0) return ""
                val buffer = ByteArray(size)
                raf.readFully(buffer)
                String(buffer, Charsets.UTF_8)
            }
        }.getOrDefault("")
    }
}
