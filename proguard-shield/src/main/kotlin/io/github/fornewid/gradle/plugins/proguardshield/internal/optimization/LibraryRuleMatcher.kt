package io.github.fornewid.gradle.plugins.proguardshield.internal.optimization

/**
 * Decides whether an external library's rule unit reaches code outside the library: a whole package or all
 * fields or methods of a class it does not ship, app classes through a type it does not own, an `-assume*`
 * rule on code it does not ship, or an option for the whole app. Rules on its own packages (or another
 * module of its Maven group), scoped by its own types or an annotation, keeping a named class without
 * members or only some members, or with both `allowshrinking` and `allowobfuscation` are not listed.
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
                name.isExact -> keepsAllMembers(spec)
                name.isPackageWide && name.packageLiteral.isNotEmpty() -> keepsBroadly(spec)
                else -> false
            }
        }
    }

    /** Keeps the matched classes themselves, or all fields or methods of them. */
    private fun keepsBroadly(spec: ClassSpecification): Boolean {
        val members = spec.members.orEmpty()
        return when (spec.directive) {
            "keep", "keepnames" -> true
            "keepclassmembers", "keepclassmembernames" -> members.any(::isAllMembers)
            else -> members.all(::isAllMembers)
        }
    }

    /** Keeps all fields or all methods of a single named class. */
    private fun keepsAllMembers(spec: ClassSpecification): Boolean {
        val members = spec.members.orEmpty()
        return when (spec.directive) {
            "keepclasseswithmembers", "keepclasseswithmembernames" -> members.isNotEmpty() && members.all(::isAllMembers)
            else -> members.any(::isAllMembers)
        }
    }

    private fun isAllMembers(entry: String): Boolean =
        entry.split(WHITESPACE).filter { it !in IGNORED_MEMBER_MODIFIERS }.joinToString(" ") in ALL_MEMBERS

    private fun keepsOtherPackageNames(unit: List<String>, label: String, packages: LibraryPackages): Boolean {
        val filters = unit.joinToString(" ").removePrefix("-keeppackagenames").split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("!") }
        if (filters.isEmpty()) return true
        val own = packages.ownPackages(label)
        return filters.any { filter ->
            val pkg = filter.takeWhile { it != '*' && it != '?' }.removeSuffix(".")
            own.none { pkg == it || pkg.startsWith("$it.") }
        }
    }
}
