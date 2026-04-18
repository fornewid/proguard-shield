package io.github.fornewid.gradle.plugins.proguardshield.internal.rules

import io.github.fornewid.gradle.plugins.proguardshield.internal.utils.ColorTerminal
import java.io.File

internal sealed class RuleDiffResult {

    internal class BaselineCreated(
        private val projectPath: String,
        private val configurationName: String,
        private val baselineFile: File,
    ) : RuleDiffResult() {

        private val message = """
            ProGuard Shield baseline created for $projectPath ($configurationName).
            File: file://${baselineFile.canonicalPath}
        """.trimIndent()

        fun format(withColor: Boolean): String = if (withColor) {
            ColorTerminal.colorify(ColorTerminal.ANSI_YELLOW, message)
        } else {
            message
        }
    }

    internal class NoDiff(
        projectPath: String,
        configurationName: String,
    ) : RuleDiffResult() {
        val message: String =
            "No ProGuard/R8 rule changes found in $projectPath for $configurationName."
    }

    internal data class HasDiff(
        val projectPath: String,
        val configurationName: String,
        val removedLines: List<String>,
        val addedLines: List<String>,
    ) : RuleDiffResult() {

        private val header = "ProGuard/R8 rules changed in $projectPath for $configurationName."

        fun format(withColor: Boolean, rebaselineMessage: String): String = buildString {
            appendLine(if (withColor) ColorTerminal.colorify(ColorTerminal.ANSI_YELLOW, header) else header)
            appendLine(diffBody(withColor))
            appendLine(if (withColor) ColorTerminal.colorify(ColorTerminal.ANSI_RED, rebaselineMessage) else rebaselineMessage)
        }

        private fun diffBody(withColor: Boolean): String = buildString {
            val lines = buildList {
                removedLines.forEach { add(false to it) }
                addedLines.forEach { add(true to it) }
            }.sortedBy { it.second }

            for ((added, line) in lines) {
                val prefixed = if (added) "+ $line" else "- $line"
                appendLine(
                    if (withColor) {
                        ColorTerminal.colorify(
                            if (added) ColorTerminal.ANSI_GREEN else ColorTerminal.ANSI_RED,
                            prefixed,
                        )
                    } else {
                        prefixed
                    },
                )
            }
        }
    }
}

internal object RuleDiff {

    fun compare(
        projectPath: String,
        configurationName: String,
        expectedContent: String,
        actualContent: String,
    ): RuleDiffResult {
        val expected = RuleNormalizer.normalizeLines(expectedContent)
        val actual = RuleNormalizer.normalizeLines(actualContent)

        // Multiset diff: R8's merged output has many duplicate rules (e.g. the
        // same -dontwarn line contributed by several AARs), so a plain
        // List.contains check would treat "2 copies" and "1 copy" as identical.
        val expectedCounts = expected.groupingBy { it }.eachCount()
        val actualCounts = actual.groupingBy { it }.eachCount()

        val removed = mutableListOf<String>()
        val added = mutableListOf<String>()
        for (key in expectedCounts.keys + actualCounts.keys) {
            val e = expectedCounts[key] ?: 0
            val a = actualCounts[key] ?: 0
            when {
                a > e -> repeat(a - e) { added.add(key) }
                e > a -> repeat(e - a) { removed.add(key) }
            }
        }

        return if (removed.isEmpty() && added.isEmpty()) {
            RuleDiffResult.NoDiff(projectPath, configurationName)
        } else {
            RuleDiffResult.HasDiff(projectPath, configurationName, removed, added)
        }
    }
}
