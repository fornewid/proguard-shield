package io.github.fornewid.gradle.plugins.proguardshield.internal.optimization

import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * The packages each external library ships, read from its AAR or JAR, to tell a library's own code from
 * code it does not ship. The other modules of a library's Maven group count as its own.
 *
 * @param byLibrary `group:module`, or `x.aar` for a file dependency → packages of its classes
 * @param appNamespace the app's namespace; a pattern that reaches it reaches the app's code
 * @param excludePackages packages, with their subpackages, that count as every library's own
 */
internal class LibraryPackages(
    byLibrary: Map<String, Set<String>>,
    private val appNamespace: String? = null,
    excludePackages: Collection<String> = emptyList(),
) {

    private val byGroup: Map<String, Set<String>> = byLibrary.entries
        .groupBy({ it.key.substringBefore(':') }, { it.value })
        .mapValues { (_, packages) -> packages.flatten().toSet() }

    private val all: Set<String> = byLibrary.values.flatten().toSet()

    /** `com.applovin.` is written as `com.applovin`. */
    private val excluded: Set<String> = excludePackages.mapTo(HashSet()) { it.trimEnd('.') }

    /** Packages of the library [label] (`group:module`) and of the other modules in its group; a file (`x.aar`) has no group. */
    fun ownPackages(label: String): Set<String> = byGroup[label.substringBefore(':')].orEmpty()

    /**
     * Whether [pattern], in a rule of the library [label], stays inside the library: its package sits in one
     * of the library's packages, or every classpath package it can reach is the library's. A pattern
     * without a package (`*`, `**`) or one that reaches the app's code ([reachesApp]) is never the
     * library's own. Excluded packages count as every library's own.
     */
    fun isOwn(label: String, pattern: ClassNamePattern): Boolean {
        val pkg = pattern.packageLiteral
        if (pkg.isEmpty() || reachesApp(pattern)) return false
        val own = ownPackages(label)
        if (own.any { pkg == it || pkg.startsWith("$it.") } || isExcluded(pkg)) return true
        // A single-package pattern reaches only its own package, which the check above covers.
        if (!pattern.isRecursive) return false
        val reached = all.filter(pattern::reaches)
        return reached.isNotEmpty() && reached.all { it in own || isExcluded(it) }
    }

    private fun isExcluded(pkg: String): Boolean = excluded.any { pkg == it || pkg.startsWith("$it.") }

    /**
     * Whether [pattern] can match a class R8 processes: one an external library ships, or the app's
     * ([reachesApp]). A framework class (`android.jar`) is not one.
     */
    fun reachesProgram(pattern: ClassNamePattern): Boolean = all.any(pattern::reaches) || reachesApp(pattern)

    /** Whether [pattern] reaches [appNamespace] or its subpackages, where the app's classes are; they are not read. */
    private fun reachesApp(pattern: ClassNamePattern): Boolean =
        appNamespace?.let { pattern.reaches(it) || pattern.packageLiteral.startsWith("$it.") } == true

    companion object {
        private val VERSIONED = Regex("^META-INF/versions/\\d+/")

        /** Writes the packages of an AAR or JAR [artifact] to [list], one per line, for [read]. */
        fun writeList(artifact: File, list: File) = list.writeText(packagesOf(artifact).sorted().joinToString("\n"))

        /**
         * Reads the lists [writeList] wrote; [artifacts] maps each list to its library label. An AAR or JAR the
         * transform skipped (a variant with no single `artifactType`) is read directly.
         */
        fun read(
            artifacts: Map<File, String>,
            appNamespace: String,
            excludePackages: Collection<String> = emptyList(),
        ): LibraryPackages = LibraryPackages(
            artifacts.entries
                .groupBy({ it.value }) { (file, _) ->
                    if (file.extension == "aar" || file.extension == "jar") packagesOf(file) else file.readLines()
                }
                .mapValues { (_, packages) -> packages.flatten().toSet() },
            appNamespace,
            excludePackages,
        )

        /** Packages of the classes in a JAR, or in an AAR's `classes.jar` and `libs/` jars; empty for any other file. */
        fun packagesOf(file: File): Set<String> = when (file.extension) {
            "aar" -> ZipFile(file).use { aar ->
                aar.entries().toList()
                    .filter { it.name == "classes.jar" || (it.name.startsWith("libs/") && it.name.endsWith(".jar")) }
                    .flatMap { jar -> aar.getInputStream(jar).use { packagesOf(ZipInputStream(it)) } }
                    .toSet()
            }
            "jar" -> ZipFile(file).use { jar -> jar.entries().toList().mapNotNull { packageOf(it.name) }.toSet() }
            else -> emptySet()
        }

        private fun packagesOf(jar: ZipInputStream): Set<String> =
            generateSequence { jar.nextEntry }.mapNotNull { packageOf(it.name) }.toSet()

        /** `com/foo/Bar.class` → `com.foo`; null for other entries, `module-info` and the default package. */
        private fun packageOf(entry: String): String? {
            if (!entry.endsWith(".class")) return null
            val name = if (entry.startsWith("META-INF/")) entry.replace(VERSIONED, "") else entry
            if (name.endsWith("module-info.class") || '/' !in name) return null
            return name.substringBeforeLast('/').replace('/', '.')
        }
    }
}
