package io.github.fornewid.gradle.plugins.proguardshield

import io.github.fornewid.gradle.plugins.proguardshield.internal.AndroidVariantHandler
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.language.base.plugins.LifecycleBasePlugin
import java.util.Properties

/**
 * A plugin for detecting unintentional changes to Android's merged ProGuard/R8 rules
 * and forbidden rule patterns.
 */
public class ProGuardShieldPlugin : Plugin<Project> {

    internal companion object {
        internal const val PROGUARD_SHIELD_TASK_GROUP = "ProGuard Shield"

        internal const val PROGUARD_SHIELD_EXTENSION_NAME = "proguardShield"

        internal const val PROGUARD_SHIELD_TASK_NAME = "proguardShield"

        internal const val PROGUARD_SHIELD_BASELINE_TASK_NAME = "proguardShieldBaseline"

        internal const val PROGUARD_SHIELD_FAST_TASK_NAME = "proguardShieldFast"

        internal const val PROGUARD_SHIELD_FAST_BASELINE_TASK_NAME = "proguardShieldFastBaseline"

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

        // Approach 1: accurate (R8 runs, -printconfiguration).
        val guardTask = target.tasks.register(PROGUARD_SHIELD_TASK_NAME) {
            group = PROGUARD_SHIELD_TASK_GROUP
            description = "Guard against unintentional ProGuard/R8 rule changes (accurate, runs R8)"
        }
        val baselineTask = target.tasks.register(PROGUARD_SHIELD_BASELINE_TASK_NAME) {
            group = PROGUARD_SHIELD_TASK_GROUP
            description = "Save current R8-merged ProGuard rules as baseline"
        }

        // Approach 2-B: fast (R8 does NOT run; reads rule inputs directly via AGP internal API).
        val fastGuardTask = target.tasks.register(PROGUARD_SHIELD_FAST_TASK_NAME) {
            group = PROGUARD_SHIELD_TASK_GROUP
            description = "Guard against unintentional ProGuard/R8 rule changes (fast, skips R8)"
        }
        val fastBaselineTask = target.tasks.register(PROGUARD_SHIELD_FAST_BASELINE_TASK_NAME) {
            group = PROGUARD_SHIELD_TASK_GROUP
            description = "Save current ProGuard rule inputs as baseline (skips R8)"
        }

        // Only application modules produce a fully merged ProGuard configuration that
        // includes AAR consumer rules, AAPT2-generated rules, and dynamic plugin rules.
        // Library modules do not go through R8, so there is nothing to shield.
        target.pluginManager.withPlugin("com.android.application") {
            AndroidVariantHandler.configureVariants(
                project = target,
                extension = extension,
                guardTask = guardTask,
                baselineTask = baselineTask,
                fastGuardTask = fastGuardTask,
                fastBaselineTask = fastBaselineTask,
            )
        }

        // Only the accurate task is attached to `check` — the fast task is opt-in
        // during the approach 1 vs approach 2-B evaluation phase. The eventual
        // release will keep only one path and attach it here.
        attachToCheckTask(target, guardTask)
    }

    private fun attachToCheckTask(target: Project, guardTask: TaskProvider<*>) {
        target.pluginManager.withPlugin("base") {
            target.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME).configure {
                this.dependsOn(guardTask)
            }
        }
    }
}
