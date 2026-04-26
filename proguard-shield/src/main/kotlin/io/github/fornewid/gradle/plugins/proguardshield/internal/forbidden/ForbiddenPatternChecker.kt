package io.github.fornewid.gradle.plugins.proguardshield.internal.forbidden

import java.util.regex.PatternSyntaxException

/**
 * Scans normalized rule units against a set of regex patterns supplied by
 * the project author. The match runs against each unit's **directive head**
 * — i.e. the directive name plus any arguments before an opening `{`.
 * Lines that follow a `{` (block body such as `<init>();`) are excluded
 * so a body line cannot produce a spurious hit, and continuation lines
 * of multi-line directives like `-keepattributes A,\nB,\nC` are joined
 * back into the head so a pattern like `Signature` actually catches the
 * `Signature` token. When a head matches, the violation report carries
 * the entire unit verbatim so the user can see the rule in context.
 *
 * The check is intentionally policy-free: the plugin ships no default
 * patterns. Each project declares its own list via
 * `proguardShield.configuration("...").forbiddenPatterns`.
 *
 * Patterns are Kotlin regexes, matched with `containsMatchIn`. To anchor
 * explicitly, prefix `^` and / or suffix `$`. Blank patterns are silently
 * dropped (a stray empty string would otherwise match every rule and
 * mass-fail the build). Patterns that fail to compile cause an
 * [IllegalArgumentException] naming the offending pattern, which the
 * caller is expected to wrap into a Gradle-friendly error.
 */
internal object ForbiddenPatternChecker {

    fun check(
        units: List<List<String>>,
        patterns: List<String>,
    ): List<Violation> {
        val compiled = patterns
            .filter { it.isNotBlank() }
            .map { pattern ->
                try {
                    pattern to Regex(pattern)
                } catch (e: PatternSyntaxException) {
                    throw IllegalArgumentException(
                        "ProGuard Shield: invalid forbiddenPatterns regex `$pattern`: ${e.message}",
                        e,
                    )
                }
            }
        if (compiled.isEmpty() || units.isEmpty()) return emptyList()

        val violations = mutableListOf<Violation>()
        for ((source, regex) in compiled) {
            val matches = units.filter { regex.containsMatchIn(directiveHead(it)) }
            if (matches.isNotEmpty()) {
                violations += Violation(
                    pattern = source,
                    matchedRules = matches.map { it.joinToString("\n") },
                )
            }
        }
        return violations
    }

    /**
     * Joins the unit lines into a single haystack covering the directive
     * itself and any continuation lines, but stopping at the opening `{` of
     * a `-keep ... { ... }` block so block bodies don't leak into matching.
     * If no `{` is present (single-line directives, multi-line
     * `-keepattributes` etc.) the entire unit is searched.
     */
    private fun directiveHead(unit: List<String>): String {
        val joined = unit.joinToString(" ")
        return joined.substringBefore('{').trim()
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
