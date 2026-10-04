package io.github.fornewid.gradle.plugins.proguardshield.internal.optimization

/** A class name in a rule's class specification, such as `com.foo.**`, `com.foo.Bar` or `**.R$*`. */
internal class ClassNamePattern(val text: String) {

    /** [text] up to its first wildcard (`*`, `?`, `<n>`). */
    val literal: String = text.substring(0, text.indexOfFirst { it in WILDCARDS }.takeIf { it >= 0 } ?: text.length)

    /** No wildcard: a single named class. */
    val isExact: Boolean = literal == text

    /** Refers back to a class matched by an `-if` condition (`<1>`). */
    val hasBackReference: Boolean = '<' in text

    /** The package part of [literal]: `com.foo` for `com.foo.**` and `com.foo.Bar`, empty for `*` or `**.R$*`. */
    val packageLiteral: String = literal.substringBeforeLast('.', missingDelimiterValue = "")

    /**
     * The wildcard covers whole packages (`com.foo.**`, `com.foo.*`, `com.applovin.sdk**`, `**`) rather
     * than class names (`**Parcelizer`, `DeviceInfo**`, `**.R$*`). Package names start lower case.
     */
    val isPackageWide: Boolean = !isExact && run {
        val tail = text.substring(if (packageLiteral.isEmpty()) 0 else packageLiteral.length + 1)
        val head = tail.substring(0, tail.indexOfFirst { it in WILDCARDS })
        val rest = tail.substring(head.length)
        rest.none { it.isLetterOrDigit() || it == '_' } && (head.isEmpty() || head.first().isLowerCase())
    }

    /** Whether this pattern can match a class in [pkg]. */
    fun reaches(pkg: String): Boolean = when {
        isExact || "**" !in text.substring(literal.length) -> pkg == packageLiteral
        else -> pkg == literal.removeSuffix(".") || pkg.startsWith(literal)
    }

    private companion object {
        const val WILDCARDS = "*?<"
    }
}

/**
 * The parts of a keep or assume rule that decide what it reaches: `-<directive>[,<modifiers>] [@annotation]
 * [modifiers] class|interface|enum|@interface <names> [extends|implements <type>] [{ <members> }]`.
 */
internal data class ClassSpecification(
    val directive: String,
    val modifiers: Set<String>,
    /** The class annotation type, without `@`. */
    val annotation: String?,
    /** The class names, without the `!`-negated ones. */
    val names: List<ClassNamePattern>,
    /** The `extends` or `implements` type. */
    val inheritance: ClassNamePattern?,
    /** Member entries, or null without a `{ }` block. */
    val members: List<String>?,
) {
    companion object {
        val KEEP_DIRECTIVES = setOf(
            "keep", "keepnames", "keepclassmembers", "keepclassmembernames",
            "keepclasseswithmembers", "keepclasseswithmembernames",
        )
        val ASSUME_DIRECTIVES = setOf(
            "assumenosideeffects", "assumevalues", "assumenoexternalsideeffects",
            "assumenoescapingarguments", "assumenoexternalreturnvalues",
        )
        private val DIRECTIVE = Regex("^-([A-Za-z]+)((?:\\s*,\\s*[A-Za-z]+)*)\\s+(.+)$")
        private val WHITESPACE = Regex("\\s+")
        private val CLASS_KEYWORDS = setOf("class", "interface", "enum", "@interface")

        /** Parses a keep or assume rule unit; null for any other rule or one it cannot read. */
        fun parse(unit: List<String>): ClassSpecification? {
            val text = unit.joinToString(" ").trim()
            val match = DIRECTIVE.find(text.substringBefore('{').trim()) ?: return null
            val directive = match.groupValues[1]
            if (directive !in KEEP_DIRECTIVES && directive !in ASSUME_DIRECTIVES) return null
            val tokens = match.groupValues[3].split(WHITESPACE)
            val keyword = tokens.indexOfFirst { it.removePrefix("!") in CLASS_KEYWORDS }
            if (keyword < 0) return null
            val afterKeyword = tokens.drop(keyword + 1)
            val relation = afterKeyword.indexOfFirst { it == "extends" || it == "implements" }
            val nameTokens = if (relation < 0) afterKeyword else afterKeyword.take(relation)
            return ClassSpecification(
                directive = directive,
                modifiers = match.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                annotation = tokens.take(keyword).firstOrNull { it.startsWith("@") }?.removePrefix("@"),
                names = nameTokens.joinToString("").split(',')
                    .filter { it.isNotEmpty() && !it.startsWith("!") }
                    .map(::ClassNamePattern),
                inheritance = if (relation < 0) {
                    null
                } else {
                    afterKeyword.drop(relation + 1).firstOrNull { !it.startsWith("@") }?.let(::ClassNamePattern)
                },
                members = if ('{' in text) {
                    text.substringAfter('{').substringBeforeLast('}').split(';').map { it.trim() }.filter { it.isNotEmpty() }
                } else {
                    null
                },
            )
        }
    }
}
