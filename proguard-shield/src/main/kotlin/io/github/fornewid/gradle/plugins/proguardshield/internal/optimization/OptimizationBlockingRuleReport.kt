package io.github.fornewid.gradle.plugins.proguardshield.internal.optimization

import io.github.fornewid.gradle.plugins.proguardshield.internal.rules.RuleNormalizer

/** One optimization-blocking rule (its lines joined with `\n`) found in a rule file. */
internal data class BlockingRule(val unit: String, val origin: RuleOrigin)

/** Entries only in the current rules ([added]) or only in the baseline ([removed]). */
internal data class RuleChanges(val added: List<String>, val removed: List<String>) {
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty()

    companion object {
        val NONE: RuleChanges = RuleChanges(emptyList(), emptyList())
    }
}

/**
 * Renders and compares the optimization baselines, dependency-guard style:
 * a list (`<variant>OptimizationBlockingRules.txt`, each rule once) and an
 * optional tree (`.tree.txt`, rules grouped by version-less origin).
 */
internal object OptimizationBlockingRuleReport {

    fun renderList(rules: List<BlockingRule>): String {
        val units = rules.map { it.unit }.distinct().sorted()
        return if (units.isEmpty()) "" else units.joinToString("\n", postfix = "\n")
    }

    fun renderTree(rules: List<BlockingRule>): String = buildString {
        val byOrigin = rules.groupBy { it.origin.label }
        byOrigin.keys.sortedWith(compareBy({ groupRank(it) }, { it })).forEachIndexed { index, label ->
            if (index > 0) appendLine()
            appendLine("[$label]")
            byOrigin.getValue(label).map { it.unit }.distinct().sorted().forEach { appendLine(it) }
        }
    }

    fun diffList(baseline: String, rules: List<BlockingRule>): RuleChanges {
        val expected = RuleNormalizer.normalizeUnits(baseline).map { it.joinToString("\n") }.toSet()
        return changes(expected, rules.map { it.unit }.toSet())
    }

    fun diffTree(baseline: String, rules: List<BlockingRule>): RuleChanges =
        changes(parseTree(baseline), rules.map { treeEntry(it.origin.label, it.unit) }.toSet())

    fun failureMessage(
        projectPath: String,
        configurationName: String,
        list: RuleChanges,
        tree: RuleChanges,
        rules: List<BlockingRule>,
        rebaselineMessage: String,
    ): String = buildString {
        appendLine("ProGuard Shield: optimization-blocking rules changed in $projectPath ($configurationName).")
        for (unit in list.added) {
            unit.lines().forEach { appendLine("+ $it") }
            val origins = rules.filter { it.unit == unit }.map { it.origin.detail }.distinct().sorted()
            appendLine("    from ${origins.joinToString(", ")}")
        }
        for (unit in list.removed) {
            unit.lines().forEach { appendLine("- $it") }
        }
        if (!tree.isEmpty) {
            appendLine()
            appendLine("Origins changed:")
            tree.added.forEach { appendLine("+ $it") }
            tree.removed.forEach { appendLine("- $it") }
        }
        appendLine()
        append(rebaselineMessage)
    }

    private fun changes(expected: Set<String>, actual: Set<String>) =
        RuleChanges(added = (actual - expected).sorted(), removed = (expected - actual).sorted())

    /** Local modules first, then libraries, then the AGP default file, then unresolved files. */
    private fun groupRank(label: String): Int = when {
        label.startsWith(":") -> 0
        label == RuleOrigins.AGP -> 2
        label == RuleOrigins.UNRESOLVED -> 3
        else -> 1
    }

    private fun treeEntry(label: String, unit: String) = "[$label] " + unit.replace('\n', ' ')

    private fun parseTree(text: String): Set<String> {
        val entries = mutableSetOf<String>()
        var label: String? = null
        val section = StringBuilder()
        fun flush() {
            val current = label
            val content = section.toString()
            section.setLength(0)
            if (current == null) return
            RuleNormalizer.normalizeUnits(content).forEach { entries += treeEntry(current, it.joinToString("\n")) }
        }
        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                flush()
                label = trimmed.substring(1, trimmed.length - 1)
            } else {
                section.appendLine(line)
            }
        }
        flush()
        return entries
    }
}
