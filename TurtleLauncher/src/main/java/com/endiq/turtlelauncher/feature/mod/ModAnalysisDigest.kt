package com.endiq.turtlelauncher.feature.mod

import com.endiq.turtlelauncher.feature.log.CrashExplainer

/**
 * The mod screen's crash analysis, reduced to the four answers a player actually needs:
 *
 *  - **Thiếu mod** - dependencies the log says are required but absent.
 *  - **Thêm mod**  - what to install to fix that.
 *  - **Lỗi mod**   - the jars the log blames / that keep appearing in the failure.
 *  - **Cách fix**  - the concrete steps, in order.
 *
 * Everything here is evidence from [ModCrashAnalyzer] and [CrashExplainer] (which already
 * writes in Vietnamese or English); nothing is inferred beyond what the log names.
 */
object ModAnalysisDigest {

    data class Digest(
        val missing: List<String> = emptyList(),
        val toAdd: List<String> = emptyList(),
        val broken: List<String> = emptyList(),
        val fixes: List<String> = emptyList()
    )

    /** Builds the digest from the already-parsed report/explanation; never re-reads the log. */
    @JvmStatic
    fun from(
        explanation: CrashExplainer.Explanation?,
        report: ModCrashAnalyzer.Report
    ): Digest {
        val missing = LinkedHashSet<String>()
        val broken = LinkedHashSet<String>()
        val fixes = LinkedHashSet<String>()

        explanation?.findings.orEmpty().forEach { finding ->
            when (finding.kind) {
                CrashExplainer.Kind.MISSING_MOD -> {
                    if (finding.subjects.isEmpty()) missing += finding.headline
                    else missing += finding.subjects
                }
                CrashExplainer.Kind.MOD_CONFLICT -> {
                    if (finding.subjects.isEmpty()) broken += finding.headline
                    else broken += finding.subjects
                }
                else -> Unit
            }
            fixes += finding.fixes
        }

        // Rule-engine fixes (renderer, Java version, corrupted files...) are still "cách fix".
        report.diagnoses.take(2).forEach { diagnosis -> fixes += diagnosis.fixSteps }

        // No named culprit? The jars the log actually mentions are the honest shortlist.
        if (broken.isEmpty()) {
            report.suspects.take(4).forEach { suspect -> broken += suspect.jar.name }
        }

        // "Thêm mod" is the actionable half of "thiếu mod": the same ids, worded as installs.
        val toAdd = missing.toList()

        return Digest(
            missing = missing.toList(),
            toAdd = toAdd,
            broken = broken.toList(),
            fixes = fixes.toList()
        )
    }
}
