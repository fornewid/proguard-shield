package io.github.fornewid.gradle.plugins.proguardshield.internal.r8input

import org.gradle.api.Task
import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.file.FileCollection
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Provider

/**
 * Reproduces AGP's `optimization.keepRules` ignore filter for the optimization
 * mode. AGP drops the ignored libraries' consumer rules only when R8 runs (8.x
 * in `R8Task`, 9.x in `ProguardConfigurableTask.obtainKeepRules`, via
 * `getFilteredConfigurationFiles` on 8.0 and `getFilteredFiles` later). The
 * matching logic is identical from 8.0 through 9.4 and is mirrored by
 * [isIgnored]; the matched artifacts' files are removed by file equality, as
 * AGP does.
 */
internal object IgnoredLibraryKeepRules {

    /** (ignore list, ignore all external) getter pairs: AGP 8.8+ (incl. 9.x), then 8.0. */
    private val IGNORE_GETTERS = listOf(
        "getIgnoreFromInKeepRules" to "getIgnoreFromAllExternalDependenciesInKeepRules",
        "getIgnoredLibraryKeepRules" to "getIgnoreAllLibraryKeepRules",
    )

    /**
     * Returns [libraryRules]' files minus the ones R8 will ignore. Unchanged
     * (no artifact resolution) when the DSL is unused or not present in this
     * AGP version.
     */
    fun exclude(task: Task, libraryRules: ArtifactCollection, objects: ObjectFactory): FileCollection {
        val files = libraryRules.artifactFiles
        val (ignoreFrom, ignoreAll) = readIgnoreConfig(task) ?: return files
        if (ignoreFrom.isEmpty() && !ignoreAll) return files

        val ignoredFiles = libraryRules.resolvedArtifacts.map { artifacts ->
            artifacts
                .filter { isIgnored(it.id.componentIdentifier, ignoreFrom, ignoreAll) }
                .map { it.file }
        }
        return files.minus(objects.fileCollection().from(ignoredFiles))
    }

    /**
     * AGP's match: only external modules are eligible; `ignoreAll` takes all
     * of them, otherwise an entry must equal `group:module:version` (the
     * identifier's `toString()`) or `group:module`.
     */
    fun isIgnored(id: ComponentIdentifier, ignoreFrom: Set<String>, ignoreAll: Boolean): Boolean {
        if (id !is ModuleComponentIdentifier) return false
        if (ignoreAll) return true
        return id.toString() in ignoreFrom || "${id.group}:${id.module}" in ignoreFrom
    }

    private fun readIgnoreConfig(task: Task): Pair<Set<String>, Boolean>? {
        for ((listGetter, allGetter) in IGNORE_GETTERS) {
            val list = providerValue(task, listGetter) ?: continue
            val all = providerValue(task, allGetter) as? Boolean ?: false
            @Suppress("UNCHECKED_CAST")
            return (list as Set<String>) to all
        }
        return null
    }

    private fun providerValue(task: Task, getter: String): Any? {
        val method = runCatching { task.javaClass.getMethod(getter) }.getOrNull() ?: return null
        return (method.invoke(task) as? Provider<*>)?.orNull
    }
}
