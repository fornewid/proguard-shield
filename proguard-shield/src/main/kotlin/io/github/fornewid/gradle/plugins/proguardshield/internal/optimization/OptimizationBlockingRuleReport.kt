package io.github.fornewid.gradle.plugins.proguardshield.internal.optimization

import io.github.fornewid.gradle.plugins.proguardshield.internal.rules.RuleNormalizer

/** One optimization-blocking rule (its lines joined with `\n`) and the origin of its rule file. */
internal data class BlockingRule(val unit: String, val origin: RuleOrigin) : Comparable<BlockingRule> {
    override fun compareTo(other: BlockingRule): Int = compareValuesBy(this, other, { it.origin.label }, { it.unit })
}

/** Entries only in the current rules ([added]) or only in the baseline ([removed]). */
internal data class RuleChanges<T>(val added: List<T>, val removed: List<T>) {
    val isEmpty: Boolean get() = added.isEmpty() && removed.isEmpty()

    companion object {
        fun <T> none(): RuleChanges<T> = RuleChanges(emptyList(), emptyList())
    }
}

/**
 * Renders and compares the optimization baselines, dependency-guard style:
 * a list (`<variant>OptimizationBlockingRules.txt`, each rule once) and an
 * optional tree (`.tree.txt`, rules grouped by version-less origin).
 */
internal object OptimizationBlockingRuleReport {

    /** Local modules first, then libraries, then the AGP default file, then unresolved files. */
    private val ORIGIN_ORDER: Comparator<String> = compareBy({ groupRank(it) }, { it })

    fun renderList(rules: List<BlockingRule>): String {
        val units = rules.map { it.unit }.distinct().sorted()
        return if (units.isEmpty()) "" else units.joinToString("\n", postfix = "\n")
    }

    fun renderTree(rules: List<BlockingRule>): String = buildString {
        val byOrigin = rules.groupBy { it.origin.label }
        byOrigin.keys.sortedWith(ORIGIN_ORDER).forEachIndexed { index, label ->
            if (index > 0) appendLine()
            appendLine("[$label]")
            byOrigin.getValue(label).map { it.unit }.distinct().sorted().forEach { appendLine(it) }
        }
    }

    fun diffList(baseline: String, rules: List<BlockingRule>): RuleChanges<String> {
        val expected = RuleNormalizer.normalizeUnits(baseline).map { it.joinToString("\n") }.toSet()
        return changes(expected, rules.map { it.unit }.toSet())
    }

    fun diffTree(baseline: String, rules: List<BlockingRule>): RuleChanges<BlockingRule> =
        changes(parseTree(baseline), rules.toSet())

    /**
     * The changes as a diff: the rules, or — when the tree changed — the rules under each origin label,
     * like manifest-shield's sources diff.
     */
    fun failureMessage(
        projectPath: String,
        configurationName: String,
        list: RuleChanges<String>,
        tree: RuleChanges<BlockingRule>,
        rebaselineMessage: String,
    ): String = buildString {
        appendLine("ProGuard Shield: optimization-blocking rules changed in $projectPath ($configurationName).")
        if (tree.isEmpty) {
            list.added.forEach { appendUnit("+ ", it) }
            list.removed.forEach { appendUnit("- ", it) }
        } else {
            (tree.removed.map { "- " to it } + tree.added.map { "+ " to it })
                .groupBy { it.second.origin.label }
                .toSortedMap(ORIGIN_ORDER)
                .forEach { (label, changes) ->
                    appendLine("  [$label]")
                    changes.sortedWith(compareBy({ it.second.unit }, { it.first == "+ " }))
                        .forEach { (prefix, rule) -> appendUnit(prefix, rule.unit) }
                }
        }
        appendLine()
        append(rebaselineMessage)
    }

    private fun StringBuilder.appendUnit(prefix: String, unit: String) {
        unit.lines().forEach { appendLine(prefix + it) }
    }

    private fun <T : Comparable<T>> changes(expected: Set<T>, actual: Set<T>) =
        RuleChanges(added = (actual - expected).sorted(), removed = (expected - actual).sorted())

    private fun groupRank(label: String): Int = when {
        label.startsWith(":") -> 0
        label == RuleOrigins.AGP -> 2
        label == RuleOrigins.UNRESOLVED -> 3
        else -> 1
    }

    private fun parseTree(text: String): Set<BlockingRule> {
        val entries = mutableSetOf<BlockingRule>()
        var label: String? = null
        val section = StringBuilder()
        fun flush() {
            val current = label
            val content = section.toString()
            section.setLength(0)
            if (current == null) return
            RuleNormalizer.normalizeUnits(content).forEach { entries += BlockingRule(it.joinToString("\n"), RuleOrigin(current)) }
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
