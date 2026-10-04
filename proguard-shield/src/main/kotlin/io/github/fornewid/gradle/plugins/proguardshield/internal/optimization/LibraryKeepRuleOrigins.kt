package io.github.fornewid.gradle.plugins.proguardshield.internal.optimization

import org.gradle.api.GradleException
import org.gradle.api.Task
import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.provider.Provider

/**
 * Maps each library keep-rule file R8 reads to the dependency that ships it,
 * through AGP's internal `ProguardConfigurableTask.getLibraryKeepRules()`
 * (present from AGP 8.0 through 9.4). The maps are lazy providers, so the
 * artifacts resolve at execution time and stay configuration-cache compatible.
 */
internal object LibraryKeepRuleOrigins {

    /** Rule file absolute path → version-less origin, e.g. `com.example:sdk`. */
    fun labels(task: Task): Provider<Map<String, String>> = originsBy(task) { it.label }

    /** Rule file absolute path → origin with version, e.g. `com.example:sdk:1.2.3`. */
    fun details(task: Task): Provider<Map<String, String>> = originsBy(task) { it.detail }

    private fun originsBy(task: Task, pick: (RuleOrigin) -> String): Provider<Map<String, String>> {
        val libraryKeepRules = runCatching {
            task.javaClass.getMethod("getLibraryKeepRules").invoke(task) as ArtifactCollection
        }.getOrElse {
            throw GradleException(
                "ProGuard Shield: 'getLibraryKeepRules' could not be read on ${task.path} in this AGP version, " +
                    "so the origins of optimization-blocking rules cannot be resolved. " +
                    "Set optimization = false and use full = true instead.",
                it,
            )
        }
        return libraryKeepRules.resolvedArtifacts.map { artifacts ->
            artifacts.associate { it.file.absolutePath to pick(RuleOrigins.of(it.id.componentIdentifier)) }
        }
    }
}
