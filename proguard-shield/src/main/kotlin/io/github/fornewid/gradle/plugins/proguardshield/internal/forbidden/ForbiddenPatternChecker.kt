package io.github.fornewid.gradle.plugins.proguardshield.internal.forbidden

/**
 * Scans normalized rule lines (one entry per rule unit, header line first)
 * against a set of regex patterns supplied by the project author.
 *
 * The check is intentionally policy-free: the plugin ships no default
 * patterns. Each project declares its own list via
 * `proguardShield.configuration("...").forbiddenPatterns`.
 *
 * Patterns are Kotlin regexes, matched with `containsMatchIn` so an entry
 * like `"-keep\\s+class\\s+\\*\\*"` matches any line containing that
 * sub-pattern. To anchor explicitly, prefix `^` and / or suffix `$`.
 */
internal object ForbiddenPatternChecker {

    fun check(
        rules: List<String>,
        patterns: List<String>,
    ): List<Violation> {
        if (patterns.isEmpty() || rules.isEmpty()) return emptyList()

        val compiled = patterns.map { it to Regex(it) }
        val violations = mutableListOf<Violation>()
        for ((source, regex) in compiled) {
            val matches = rules.filter { regex.containsMatchIn(it) }
            if (matches.isNotEmpty()) {
                violations += Violation(pattern = source, matchedRules = matches)
            }
        }
        return violations
    }

    fun renderFailureMessage(
        projectPath: String,
        configurationName: String,
        violations: List<Violation>,
    ): String = buildString {
        appendLine("ProGuard Shield: forbidden rule patterns detected in $projectPath ($configurationName).")
        appendLine()
        for (violation in violations) {
            appendLine("Pattern: ${violation.pattern}")
            for (rule in violation.matchedRules) {
                appendLine("  Matched: $rule")
            }
            appendLine()
        }
        append(
            "Configure proguardShield.configuration(\"$configurationName\").forbiddenPatterns to adjust the policy.",
        )
    }

    internal data class Violation(
        val pattern: String,
        val matchedRules: List<String>,
    )
}
