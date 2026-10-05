package io.github.fornewid.gradle.plugins.proguardshield.internal.r8input

import org.gradle.api.GradleException
import org.gradle.api.Task
import org.gradle.api.file.FileCollection

/**
 * Reflective bridge to AGP's internal `ProguardConfigurableTask` so we can
 * snapshot the full ProGuard rule inputs *without* running R8 itself.
 *
 * These sources are combined to mirror what R8 reads:
 * - `configurationFiles` (reflection): references most rule files R8
 *   uses — app `.pro`, AAR consumer rules, plugin-injected, and the single
 *   default file the user chose via `getDefaultProguardFile(...)`. Note the
 *   default file path lives under `build/intermediates/.../default_proguard_files/`
 *   and only exists after `extractProguardFiles` runs, so the fullFast task must
 *   `dependsOn(extractProguardFiles)` explicitly.
 * - `generatedProguardFile` (reflection): rules generated while compiling
 *   (e.g. by annotation processors).
 * - `keepRulesFiles` (reflection, optional): keep-rule source sets
 *   (`.keep` files under `src/<variant>/keepRules/`). Only exists on AGP 9.1+.
 * - `aaptProguardFiles` (reflection, optional): AAPT2-generated keep rules
 *   (manifest components, layouts). Only exists on AGP 9.3+; earlier versions
 *   pass them through `configurationFiles`.
 * - `featureProguardFiles` (reflection, optional): rules of dynamic feature
 *   modules. Only exists on AGP 9.3+; earlier versions pass them through
 *   `configurationFiles`.
 */
internal object R8TaskInputExtractor {

    private const val BASE_CLASS = "com.android.build.gradle.internal.tasks.ProguardConfigurableTask"

    /** Shared by every mode that reads R8's inputs through AGP internals (optimization, fullFast). */
    const val FALLBACK_HINT: String =
        "Use the full mode instead (full = true, with optimization = false and fullFast = false), " +
            "which runs R8 and uses only public AGP API."

    private val REQUIRED_METHOD_NAMES = listOf(
        "getConfigurationFiles",
        "getGeneratedProguardFile",
    )

    /** Getters that only exist on some AGP versions; skipped when absent. */
    private val OPTIONAL_METHOD_NAMES = listOf(
        "getKeepRulesFiles", // AGP 9.1+
        "getAaptProguardFiles", // AGP 9.3+
        "getFeatureProguardFiles", // AGP 9.3+
    )

    private const val INLINE_RULES_METHOD_NAME = "getProguardConfigurations"

    fun allRuleFiles(task: Task): FileCollection {
        // Load from the task's own classloader: with includeBuild or plugin
        // isolation AGP can live in a different loader than this plugin.
        val baseClass = runCatching { task.javaClass.classLoader.loadClass(BASE_CLASS) }
            .getOrElse {
                throw GradleException(
                    "ProGuard Shield: AGP internal class '$BASE_CLASS' not found, so R8's rule inputs " +
                        "cannot be read in this AGP version. $FALLBACK_HINT",
                )
            }

        if (!baseClass.isInstance(task)) {
            throw GradleException(
                "ProGuard Shield: ${task.path} is not a ProguardConfigurableTask " +
                    "(got ${task::class.qualifiedName}). Expected AGP's R8 task.",
            )
        }

        return ruleFiles(baseClass, task)
    }

    /** Reads the rule-file getters declared on [baseClass] from [target]. */
    internal fun ruleFiles(baseClass: Class<*>, target: Any): FileCollection {
        val collections = (REQUIRED_METHOD_NAMES + OPTIONAL_METHOD_NAMES).mapNotNull { methodName ->
            val method = runCatching { baseClass.getMethod(methodName) }
                .getOrElse {
                    if (methodName in OPTIONAL_METHOD_NAMES) return@mapNotNull null
                    throw GradleException(
                        "ProGuard Shield: method '$methodName' not found on " +
                            "$BASE_CLASS in this AGP version. $FALLBACK_HINT",
                    )
                }
            val value = method.invoke(target)
            value as? FileCollection
                ?: throw GradleException(
                    "ProGuard Shield: $methodName returned an unsupported type " +
                        "(${value?.let { it::class.qualifiedName }}) in this AGP version. $FALLBACK_HINT",
                )
        }

        return collections.reduce { acc, next -> acc.plus(next) }
    }

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
                    "version (got ${value?.let { it::class.qualifiedName }}). $FALLBACK_HINT",
            )
    }
}
