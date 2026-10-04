package io.github.fornewid.gradle.plugins.proguardshield.internal.optimization

import io.github.fornewid.gradle.plugins.proguardshield.internal.r8input.IgnoredLibraryKeepRules
import org.gradle.api.Task
import org.gradle.api.provider.Provider

/**
 * Maps each library keep-rule file R8 reads (by absolute path) to the
 * dependency that ships it. Lazy, so the artifacts resolve at execution time
 * and stay configuration-cache compatible.
 */
internal object LibraryKeepRuleOrigins {

    fun of(task: Task): Provider<Map<String, RuleOrigin>> = RuleOrigins.byPath(
        IgnoredLibraryKeepRules.libraryKeepRules(task, "so the origins of optimization-blocking rules cannot be resolved"),
    )
}
