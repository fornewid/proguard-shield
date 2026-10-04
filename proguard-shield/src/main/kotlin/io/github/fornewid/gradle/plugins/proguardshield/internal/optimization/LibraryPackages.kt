package io.github.fornewid.gradle.plugins.proguardshield.internal.optimization

import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * The packages each external library ships, read from its AAR or JAR, to tell a library's own code from
 * code it does not ship. The other modules of a library's Maven group count as its own.
 *
 * @param byLibrary `group:module` → packages of its classes
 */
internal class LibraryPackages(byLibrary: Map<String, Set<String>>) {

    private val byGroup: Map<String, Set<String>> = byLibrary.entries
        .groupBy({ it.key.substringBefore(':') }, { it.value })
        .mapValues { (_, packages) -> packages.flatten().toSet() }

    private val all: Set<String> = byLibrary.values.flatten().toSet()

    /** Packages of the library [label] (`group:module`) and of the other modules in its group. */
    fun ownPackages(label: String): Set<String> = byGroup[label.substringBefore(':')].orEmpty()

    /**
     * Whether [pattern], in a rule of the library [label], stays inside the library: its package sits in one
     * of the library's packages, or every classpath package it can reach is the library's. A pattern
     * without a package (`*`, `**`) also reaches the app, so it is never the library's own.
     */
    fun isOwn(label: String, pattern: ClassNamePattern): Boolean {
        val pkg = pattern.packageLiteral
        if (pkg.isEmpty()) return false
        val own = ownPackages(label)
        if (own.any { pkg == it || pkg.startsWith("$it.") }) return true
        // A single-package pattern reaches only its own package, which the check above covers.
        if (!pattern.isRecursive) return false
        val reached = all.filter(pattern::reaches)
        return reached.isNotEmpty() && reached.all { it in own }
    }

    companion object {
        private val VERSIONED = Regex("^META-INF/versions/\\d+/")

        /** Reads the packages of each artifact; [artifacts] maps an AAR or JAR to its `group:module`. */
        fun read(artifacts: Map<File, String>): LibraryPackages = LibraryPackages(
            artifacts.entries
                .groupBy({ it.value }, { packagesOf(it.key) })
                .mapValues { (_, packages) -> packages.flatten().toSet() },
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
