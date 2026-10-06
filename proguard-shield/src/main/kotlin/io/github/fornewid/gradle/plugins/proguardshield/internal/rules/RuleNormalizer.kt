package io.github.fornewid.gradle.plugins.proguardshield.internal.rules

/**
 * Normalizes ProGuard/R8 rule text so cosmetic changes (comments, blank lines,
 * spacing, line breaks) don't cause spurious baseline diffs.
 *
 * Output is sorted by **rule unit**. A unit is a single `-...` directive plus
 * any continuation lines or `{ ... }` block body that belongs to it, written
 * on one line with whitespace normalized: `-keep class A { *; }`. The same
 * rule written with other spacing is the same unit, and sorting makes the
 * result independent of the order the rule files are read in.
 */
internal object RuleNormalizer {

    fun normalize(raw: String): String = normalizeLines(raw).joinToString("\n")

    fun normalizeLines(raw: String): List<String> = parseUnits(raw).map(::oneLine).sorted()

    /** [normalizeLines] with each line in its own list, the shape the optimization matchers take. */
    fun normalizeUnits(raw: String): List<List<String>> = normalizeLines(raw).map { listOf(it) }

    /** [lines] on one line: one space between tokens and around `{` and `}`, none before `;` or around `,`. */
    private fun oneLine(lines: List<String>): String = lines.joinToString(" ")
        .replace(BRACE_OR_SEMICOLON) { " ${it.value} " }
        .replace(WHITESPACE, " ")
        .trim()
        .replace(" ;", ";")
        .replace(COMMA, ",")

    /**
     * Splits [raw] into rule units. A unit starts at a `-...` directive when
     * the brace depth is 0, and absorbs every following non-empty line until
     * the next directive starts at depth 0. Inline `#` comments are stripped,
     * and blank lines are dropped.
     *
     * Assumptions about the input (true for R8's `-printconfiguration` output
     * and for the concatenation of `.pro` files that feed it):
     * - Continuation lines (e.g. the body of `-keepattributes A,\nB,\nC`) do
     *   not start with `-`. Only directive headers do.
     * - Brace counting is purely textual. R8 never emits `{` or `}` inside a
     *   string or character literal in its rule output, so no quote-aware
     *   parser is needed.
     */
    private fun parseUnits(raw: String): List<List<String>> {
        val units = mutableListOf<MutableList<String>>()
        var current: MutableList<String>? = null
        var depth = 0
        for (rawLine in raw.lineSequence()) {
            val code = stripInlineComment(rawLine).trim()
            if (code.isEmpty()) continue

            val isDirectiveStart = depth == 0 && code.startsWith("-")
            if (isDirectiveStart) {
                current = mutableListOf<String>().also { units += it }
            }

            current?.add(code)
            depth += code.count { it == '{' } - code.count { it == '}' }
            // R8's output is well-formed, but guard against a stray `}` so a
            // single malformed input can't poison the rest of the parse.
            if (depth < 0) depth = 0
        }
        return units
    }

    private val BRACE_OR_SEMICOLON = Regex("[{};]")
    private val WHITESPACE = Regex("\\s+")
    private val COMMA = Regex(" ?, ?")

    private fun stripInlineComment(line: String): String {
        val hash = line.indexOf('#')
        return if (hash >= 0) line.substring(0, hash) else line
    }
}
