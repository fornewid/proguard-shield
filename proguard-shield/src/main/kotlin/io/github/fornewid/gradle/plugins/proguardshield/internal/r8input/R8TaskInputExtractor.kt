package io.github.fornewid.gradle.plugins.proguardshield.internal.r8input

import org.gradle.api.GradleException
import org.gradle.api.file.FileCollection

/**
 * Reflective reads of AGP's R8 task, for the rule inputs the optimization mode
 * cannot get from public API: keep-rule source sets and the rules AGP passes to
 * R8 as strings. Neither needs compiling the variant.
 */
internal object R8TaskInputExtractor {

    private const val INLINE_RULES_METHOD_NAME = "getProguardConfigurations"

    /** Keep-rule source sets (`src/<variant>/keepRules/`), which exist without compiling; null before AGP 9.1. */
    fun keepRulesFiles(task: Any): FileCollection? =
        runCatching { task.javaClass.getMethod("getKeepRulesFiles") }.getOrNull()?.invoke(task) as? FileCollection

    /**
     * Rules AGP passes to R8 as strings rather than files, such as JaCoCo's keeps when the variant's build type
     * is the `testBuildType` with `enableAndroidTestCoverage`. Declared on AGP's R8 task (`R8Task` in 8.x,
     * `BaseR8Task` in 9.x).
     */
    fun inlineRules(task: Any): List<String> {
        val value = runCatching { task.javaClass.getMethod(INLINE_RULES_METHOD_NAME) }.getOrNull()?.invoke(task)
        return (value as? Iterable<*>)?.map { it.toString() }
            ?: throw GradleException(
                "ProGuard Shield: $INLINE_RULES_METHOD_NAME could not be read on ${task.javaClass.name} in this AGP " +
                    "version (got ${value?.let { it::class.qualifiedName }}). Set optimization = false for this " +
                    "configuration and report the AGP version at https://github.com/fornewid/proguard-shield/issues.",
            )
    }
}
