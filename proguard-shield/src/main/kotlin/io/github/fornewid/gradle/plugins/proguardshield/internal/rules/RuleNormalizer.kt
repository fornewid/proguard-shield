package io.github.fornewid.gradle.plugins.proguardshield.internal.rules

/**
 * Normalizes R8 `-printconfiguration` output so cosmetic changes (comments,
 * blank lines, trailing whitespace) don't cause spurious baseline diffs.
 *
 * Order of the remaining lines is preserved — R8's output is deterministic
 * for a fixed input, and humans read baselines top-down by source.
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
