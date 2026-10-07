package io.github.fornewid.gradle.plugins.proguardshield.internal.optimization

/**
 * Decides whether an external library's rule unit reaches code outside the library: a whole package or all
 * fields or methods of a class it does not ship, app classes through a type it does not own, or an app-wide
 * option that reduces what R8 does. Not listed: rules on its own packages (or another module of its Maven
 * group, or a package the configuration excludes), rules scoped by its own types or an annotation, rules that keep a named class without members or
 * list only some members (no `*`, `<fields>` or `<methods>`), rules with both `allowshrinking` and
 * `allowobfuscation`, rules whose targets match no class R8 processes ([LibraryPackages.reachesProgram]),
 * and `-assume*` rules, which let R8 do more rather than less.
 */
internal object LibraryRuleMatcher {

    private val DIRECTIVE_NAME = Regex("^-([A-Za-z]+)")
    private val WHITESPACE = Regex("\\s+")

    /**
     * App-wide options that reduce what R8 does. [OptimizationBlockingRuleMatcher] lists `-dontobfuscate`,
     * `-dontshrink`, `-dontoptimize` and an unfiltered `-keepattributes` for every origin, and
     * `-keeppackagenames` is listed when it reaches other packages. Other options write output, rename
     * resources, let R8 do more, or are ignored by R8. Checked against the options R8 9.4.24 reads.
     */
    private val LISTED_OPTIONS = setOf("keepparameternames", "keepkotlinmetadata", "dontrepackage")

    /** Member specs that cover every field or method, once access modifiers, `static` and `final` are dropped. */
    private val ALL_MEMBERS = setOf("*", "<fields>", "<methods>", "*** *", "*** *(...)")
    private val IGNORED_MEMBER_MODIFIERS = setOf("public", "protected", "private", "static", "final")

    fun matches(unit: List<String>, label: String, packages: LibraryPackages): Boolean {
        val directive = DIRECTIVE_NAME.find(unit.first())?.groupValues?.get(1) ?: return false
        val spec = ClassSpecification.parse(unit) ?: return when (directive) {
            "keeppackagenames" -> keepsOtherPackageNames(unit, label, packages)
            else -> directive in LISTED_OPTIONS
        }
        if ("allowshrinking" in spec.modifiers && "allowobfuscation" in spec.modifiers) return false
        if (spec.annotation != null) return false
        val foreign = spec.names.filter { !it.hasBackReference && reachesOutside(it, label, packages) }
        val inheritance = spec.inheritance?.takeUnless { it.hasBackReference }
        val foreignInheritance = inheritance != null && !packages.isOwn(label, inheritance)
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

    /**
     * Listed with no filter or only `!` filters (both keep package names outside the library), or with a
     * filter outside the library's own packages.
     */
    private fun keepsOtherPackageNames(unit: List<String>, label: String, packages: LibraryPackages): Boolean {
        val filters = unit.joinToString(" ").removePrefix("-keeppackagenames").split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("!") }
        return filters.isEmpty() || filters.any { reachesOutside(ClassNamePattern("$it.*"), label, packages) }
    }

    /** Whether [pattern] reaches outside the library [label] and can match a class R8 processes. */
    private fun reachesOutside(pattern: ClassNamePattern, label: String, packages: LibraryPackages): Boolean =
        !packages.isOwn(label, pattern) && packages.reachesProgram(pattern)
}
