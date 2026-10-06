package io.github.fornewid.gradle.plugins.proguardshield.internal.optimization

/**
 * Decides whether a rule unit (one `-...` directive with its `{ ... }` body, on
 * one line as produced by `RuleNormalizer.normalizeUnits`) blocks
 * R8's shrinking, obfuscation or optimization for the whole app:
 *
 * - `-dontobfuscate`, `-dontshrink`, `-dontoptimize`
 * - `-keepattributes` with no filter or a bare `*`
 * - a `-keep*` rule on every class (`*`, `**`, …) that is not scoped by an
 *   annotation, `extends` or `implements`, and whose member specs (for the
 *   member variants) are unrestricted
 *
 * The definition is fixed and narrow: annotation- or inheritance-scoped rules
 * never match here. An external library's rules that reach code outside the
 * library are matched by [LibraryRuleMatcher].
 */
internal object OptimizationBlockingRuleMatcher {

    private val DISABLED_STEP = Regex("^-(dontobfuscate|dontshrink|dontoptimize)(\\s|$)")
    private val KEEP_ATTRIBUTES = Regex("^-keepattributes(\\s+(.*))?$")
    private val KEEP_DIRECTIVE = Regex(
        "^-(keep|keepnames|keepclassmembers|keepclassmembernames|keepclasseswithmembers|keepclasseswithmembernames)" +
            "((?:\\s*,\\s*[A-Za-z]+)*)\\s+(.+)$",
    )
    private val WHITESPACE = Regex("\\s+")
    private val CLASS_KEYWORDS = setOf("class", "interface", "enum", "@interface")
    private val ACCESS_MODIFIERS = setOf("public", "protected", "private")
    private val UNRESTRICTED_MEMBERS = setOf("*", "<fields>", "<methods>", "<init>(...)", "*** *", "*** *(...)")

    fun matches(unit: List<String>): Boolean {
        val text = unit.joinToString(" ").trim()
        val head = text.substringBefore('{').trim()
        val body = if ('{' in text) text.substringAfter('{').substringBeforeLast('}') else null
        return DISABLED_STEP.containsMatchIn(head) || keepsAllAttributes(head) || keepsEveryClass(head, body)
    }

    private fun keepsAllAttributes(head: String): Boolean {
        val match = KEEP_ATTRIBUTES.find(head) ?: return false
        val filters = match.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return filters.isEmpty() || "*" in filters
    }

    private fun keepsEveryClass(head: String, body: String?): Boolean {
        val match = KEEP_DIRECTIVE.find(head) ?: return false
        val directive = match.groupValues[1]
        val modifiers = match.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if ("allowshrinking" in modifiers && "allowobfuscation" in modifiers) return false

        val tokens = match.groupValues[3].split(WHITESPACE)
        val keyword = tokens.indexOfFirst { it.removePrefix("!") in CLASS_KEYWORDS }
        if (keyword < 0 || keyword == tokens.lastIndex) return false
        if (tokens.take(keyword).any { it.startsWith("@") }) return false
        if (tokens[keyword + 1].any { it.isLetterOrDigit() || it == '_' }) return false
        if (tokens.drop(keyword + 2).any { it == "extends" || it == "implements" }) return false

        val members = body?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        return when (directive) {
            "keep", "keepnames" -> true
            "keepclassmembers", "keepclassmembernames" -> members.any(::isUnrestrictedMember)
            else -> members.all(::isUnrestrictedMember)
        }
    }

    private fun isUnrestrictedMember(spec: String): Boolean =
        spec.split(WHITESPACE).filter { it !in ACCESS_MODIFIERS }.joinToString(" ") in UNRESTRICTED_MEMBERS
}
