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
 *   and only exists after `extractProguardFiles` runs, so the fast task must
 *   `dependsOn(extractProguardFiles)` explicitly.
 * - `generatedProguardFile` (reflection): AAPT2-generated rules.
 * - `keepRulesFiles` (reflection, optional): keep-rule source sets
 *   (`.keep` files under `src/<variant>/keepRules/`). Only exists on AGP 9.3+.
 */
internal object R8TaskInputExtractor {

    private const val BASE_CLASS = "com.android.build.gradle.internal.tasks.ProguardConfigurableTask"

    private val REQUIRED_METHOD_NAMES = listOf(
        "getConfigurationFiles",
        "getGeneratedProguardFile",
    )

    /** Getters that only exist on some AGP versions; skipped when absent. */
    private val OPTIONAL_METHOD_NAMES = listOf(
        "getKeepRulesFiles", // AGP 9.3+
    )

    fun allRuleFiles(task: Task): FileCollection {
        // Load from the task's own classloader: with includeBuild or plugin
        // isolation AGP can live in a different loader than this plugin.
        val baseClass = runCatching { task.javaClass.classLoader.loadClass(BASE_CLASS) }
            .getOrElse {
                throw GradleException(
                    "ProGuard Shield fast mode: AGP internal class '$BASE_CLASS' not found. " +
                        "This AGP version is unsupported; " +
                        "use the standard 'proguardShield' task instead of 'proguardShieldFast'.",
                )
            }

        if (!baseClass.isInstance(task)) {
            throw GradleException(
                "ProGuard Shield fast mode: ${task.path} is not a ProguardConfigurableTask " +
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
                        "ProGuard Shield fast mode: method '$methodName' not found on " +
                            "$BASE_CLASS in this AGP version. Switch to the standard " +
                            "'proguardShield' task instead of 'proguardShieldFast'.",
                    )
                }
            val value = method.invoke(target)
            value as? FileCollection
                ?: throw GradleException(
                    "ProGuard Shield fast mode: $methodName returned an unsupported type " +
                        "(${value?.let { it::class.qualifiedName }}) in this AGP version. " +
                        "Switch to the standard 'proguardShield' task instead of 'proguardShieldFast'.",
                )
        }

        return collections.reduce { acc, next -> acc.plus(next) }
    }
}
