package io.github.fornewid.gradle.plugins.proguardshield.internal.rules

/**
 * Normalizes R8 `-printconfiguration` output so cosmetic changes (comments,
 * blank lines, trailing whitespace) don't cause spurious baseline diffs.
 *
 * Output is sorted by **rule unit**, not by individual line. A unit is a
 * single `-...` directive plus any continuation lines or `{ ... }` block
 * body that belongs to it. Sorting by header line keeps each block's body
 * anchored to its own header in the baseline file, so a `git diff` of the
 * committed baseline still shows which class's keep block actually changed.
 *
 * R8's own `-printconfiguration` line order is not stable across versions,
 * and the fast path concatenates input `.pro` files in arbitrary order —
 * sorting by header line absorbs both. Bodies stay in the order R8 wrote
 * them within a unit.
 */
internal object RuleNormalizer {

    fun normalize(raw: String): String = normalizeLines(raw).joinToString("\n")

    fun normalizeLines(raw: String): List<String> {
        return parseUnits(raw)
            // Sort by header first (the actionable identity of the rule),
            // then by full body content for a deterministic tie-break.
            // Without the tie-break, two units with identical headers
            // (e.g. several `-keepclasseswithmembers class * { ... }`
            // blocks differing only by their inner annotation) would
            // keep input order — which differs between the accurate and
            // fast paths and breaks bit-identical parity.
            .sortedWith(compareBy({ it.first() }, { it.joinToString("\n") }))
            .flatten()
    }

    /**
     * Splits [raw] into rule units. A unit starts at a `-...` directive when
     * the brace depth is 0, and absorbs every following non-empty line until
     * the next directive starts at depth 0. Inline `#` comments are stripped,
     * blank lines are dropped, and the plugin-injected `-printconfiguration`
     * line is filtered out (otherwise it would anchor a per-machine path
     * into the baseline).
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

            // R8 echoes our injected -printconfiguration directive back; the
            // path it carries is machine-specific so it must not enter the
            // baseline. Drop it unconditionally — even if a malformed input
            // somehow buried it inside an unbalanced block — to make sure no
            // per-machine path can leak. The line carries no braces, so this
            // does not disturb depth tracking.
            if (code.startsWith("-printconfiguration")) {
                if (depth == 0) current = null
                continue
            }

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

    private fun stripInlineComment(line: String): String {
        val hash = line.indexOf('#')
        return if (hash >= 0) line.substring(0, hash) else line
    }
}
