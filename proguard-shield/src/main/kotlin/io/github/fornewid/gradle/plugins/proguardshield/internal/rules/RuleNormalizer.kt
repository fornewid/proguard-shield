package io.github.fornewid.gradle.plugins.proguardshield.internal.rules

/**
 * Normalizes R8 `-printconfiguration` output so cosmetic changes (comments,
 * blank lines, trailing whitespace) don't cause spurious baseline diffs.
 *
 * Lines are sorted alphabetically. R8 does not guarantee a stable output
 * order across versions, and the fast task concatenates files in whatever
 * order Gradle yields them — sorting up front makes the baseline
 * version-stable and lets the accurate and fast tasks produce
 * bit-identical baseline files.
 */
internal object RuleNormalizer {

    fun normalize(raw: String): String = normalizeLines(raw).joinToString("\n")

    fun normalizeLines(raw: String): List<String> {
        return raw.lineSequence()
            .map { stripInlineComment(it).trim() }
            .filter { it.isNotEmpty() }
            .filter { !it.startsWith("-printconfiguration") }
            .toList()
            .sorted()
    }

    private fun stripInlineComment(line: String): String {
        val hash = line.indexOf('#')
        return if (hash >= 0) line.substring(0, hash) else line
    }
}
