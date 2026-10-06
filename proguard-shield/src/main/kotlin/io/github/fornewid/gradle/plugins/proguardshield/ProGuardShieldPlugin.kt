package io.github.fornewid.gradle.plugins.proguardshield

import io.github.fornewid.gradle.plugins.proguardshield.internal.AndroidVariantHandler
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.language.base.plugins.LifecycleBasePlugin
import java.util.Properties

/**
 * A plugin that detects ProGuard/R8 rules that block R8's optimization, and
 * library rules that reach beyond the library.
 */
public class ProGuardShieldPlugin : Plugin<Project> {

    internal companion object {
        internal const val PROGUARD_SHIELD_TASK_GROUP = "ProGuard Shield"

        internal const val PROGUARD_SHIELD_EXTENSION_NAME = "proguardShield"

        internal const val PROGUARD_SHIELD_OPTIMIZATION_TASK_NAME = "proguardShieldOptimization"

        internal const val PROGUARD_SHIELD_OPTIMIZATION_BASELINE_TASK_NAME = "proguardShieldOptimizationBaseline"

        internal val VERSION: String by lazy {
            ProGuardShieldPlugin::class.java
                .getResourceAsStream("/proguard-shield.properties")
                ?.use { Properties().apply { load(it) }.getProperty("version") }
                ?: "dev"
        }
    }

    override fun apply(target: Project) {
        val extension = target.extensions.create(
            PROGUARD_SHIELD_EXTENSION_NAME,
            ProGuardShieldPluginExtension::class.java,
            target.objects,
        )

        // Rules that block R8's optimization, with their origins. Wired to the `check` lifecycle.
        val optimizationGuardTask = target.tasks.register(PROGUARD_SHIELD_OPTIMIZATION_TASK_NAME) {
            group = PROGUARD_SHIELD_TASK_GROUP
            description = "Guard against new optimization-blocking ProGuard/R8 rules"
        }
        val optimizationBaselineTask = target.tasks.register(PROGUARD_SHIELD_OPTIMIZATION_BASELINE_TASK_NAME) {
            group = PROGUARD_SHIELD_TASK_GROUP
            description = "Save the current optimization-blocking rules to the baseline file"
        }

        // Only application modules run R8; library modules have nothing to guard.
        target.pluginManager.withPlugin("com.android.application") {
            AndroidVariantHandler.configureVariants(
                project = target,
                extension = extension,
                optimizationGuardTask = optimizationGuardTask,
                optimizationBaselineTask = optimizationBaselineTask,
            )
        }

        // `check` runs the optimization mode of the variants that enable it.
        attachToCheckTask(target, optimizationGuardTask)
    }

    private fun attachToCheckTask(target: Project, vararg guardTasks: TaskProvider<*>) {
        target.pluginManager.withPlugin("base") {
            target.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME).configure {
                this.dependsOn(*guardTasks)
            }
        }
    }
}
