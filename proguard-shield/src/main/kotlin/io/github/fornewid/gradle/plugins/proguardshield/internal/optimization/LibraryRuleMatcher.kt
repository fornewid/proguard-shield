package io.github.fornewid.gradle.plugins.proguardshield.internal.optimization

/**
 * Decides whether an external library's rule unit reaches code outside the library: a whole package or all
 * fields or methods of a class it does not ship, app classes through a type it does not own, an `-assume*`
 * rule on code it does not ship, or an option for the whole app. Not listed: rules on its own packages (or
 * another module of its Maven group), rules scoped by its own types or an annotation, rules that keep a
 * named class without members or list only some members (no `*`, `<fields>` or `<methods>`), and rules with
 * both `allowshrinking` and `allowobfuscation`.
 */
internal object LibraryRuleMatcher {

    private val DIRECTIVE_NAME = Regex("^-([A-Za-z]+)")
    private val WHITESPACE = Regex("\\s+")

    /** Directives that are not listed here: decided by [OptimizationBlockingRuleMatcher], or not app-wide. */
    private val UNLISTED_OPTIONS = setOf(
        "dontobfuscate", "dontshrink", "dontoptimize", "keepattributes",
        "dontwarn", "dontnote", "whyareyoukeeping", "checkdiscard", "identifiernamestring", "if",
    )

    /** Member specs that cover every field or method, once access modifiers, `static` and `final` are dropped. */
    private val ALL_MEMBERS = setOf("*", "<fields>", "<methods>", "*** *", "*** *(...)")
    private val IGNORED_MEMBER_MODIFIERS = setOf("public", "protected", "private", "static", "final")

    fun matches(unit: List<String>, label: String, packages: LibraryPackages): Boolean {
        val directive = DIRECTIVE_NAME.find(unit.first())?.groupValues?.get(1) ?: return false
        val spec = ClassSpecification.parse(unit) ?: return when (directive) {
            in ClassSpecification.KEEP_DIRECTIVES, in ClassSpecification.ASSUME_DIRECTIVES -> false
            "keeppackagenames" -> keepsOtherPackageNames(unit, label, packages)
            else -> directive !in UNLISTED_OPTIONS
        }
        if ("allowshrinking" in spec.modifiers && "allowobfuscation" in spec.modifiers) return false
        if (spec.annotation != null) return false
        val foreign = spec.names.filter { !it.hasBackReference && !packages.isOwn(label, it) }
        val inheritance = spec.inheritance?.takeUnless { it.hasBackReference }
        val foreignInheritance = inheritance != null && !packages.isOwn(label, inheritance)
        if (spec.directive in ClassSpecification.ASSUME_DIRECTIVES) return foreign.isNotEmpty() || foreignInheritance
        if (spec.inheritance != null) return foreignInheritance && foreign.isNotEmpty() && keepsBroadly(spec)
        return foreign.any { name ->
            when {
                name.isExact -> spec.members.isNotEmpty() && keepsBroadly(spec)
                name.isPackageWide && name.packageLiteral.isNotEmpty() -> keepsBroadly(spec)
                else -> false
            }
        }
    }

    /** Keeps the matched classes without listing members, or all fields or methods of them. */
    private fun keepsBroadly(spec: ClassSpecification): Boolean = when (spec.directive) {
        "keep", "keepnames" -> spec.members.isEmpty() || spec.members.any(::isAllMembers)
        "keepclassmembers", "keepclassmembernames" -> spec.members.any(::isAllMembers)
        else -> spec.members.all(::isAllMembers)
    }

    private fun isAllMembers(entry: String): Boolean =
        entry.split(WHITESPACE).filter { it !in IGNORED_MEMBER_MODIFIERS }.joinToString(" ") in ALL_MEMBERS

    /** `-keeppackagenames` filters name packages; one outside the library's own lists the rule. */
    private fun keepsOtherPackageNames(unit: List<String>, label: String, packages: LibraryPackages): Boolean {
        val filters = unit.joinToString(" ").removePrefix("-keeppackagenames").split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("!") }
        return filters.isEmpty() || filters.any { !packages.isOwn(label, ClassNamePattern("$it.*")) }
    }
}
