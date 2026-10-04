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

        internal const val PROGUARD_SHIELD_FULL_TASK_NAME = "proguardShieldFull"

        internal const val PROGUARD_SHIELD_FULL_BASELINE_TASK_NAME = "proguardShieldFullBaseline"

        internal const val PROGUARD_SHIELD_FULL_FAST_TASK_NAME = "proguardShieldFullFast"

        internal const val PROGUARD_SHIELD_FULL_FAST_BASELINE_TASK_NAME = "proguardShieldFullFastBaseline"

        internal const val PROGUARD_SHIELD_VERIFY_PARITY_TASK_NAME = "proguardShieldVerifyParity"

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

        // Optimization (default): rules that block R8's optimization, with
        // their origins. Wired to the `check` lifecycle.
        val guardTask = target.tasks.register(PROGUARD_SHIELD_TASK_NAME) {
            group = PROGUARD_SHIELD_TASK_GROUP
            description = "Guard against new optimization-blocking ProGuard/R8 rules"
        }
        val baselineTask = target.tasks.register(PROGUARD_SHIELD_BASELINE_TASK_NAME) {
            group = PROGUARD_SHIELD_TASK_GROUP
            description = "Save the current optimization-blocking rules to the baseline file"
        }

        // Full: the full merged rule set as R8 prints it. Runs R8 with
        // -printconfiguration, public AGP API only. Reserved for explicit
        // invocation; not on the default `check` lifecycle.
        val fullGuardTask = target.tasks.register(PROGUARD_SHIELD_FULL_TASK_NAME) {
            group = PROGUARD_SHIELD_TASK_GROUP
            description = "Guard against unintentional ProGuard/R8 rule changes (full, runs R8)"
        }
        val fullBaselineTask = target.tasks.register(PROGUARD_SHIELD_FULL_BASELINE_TASK_NAME) {
            group = PROGUARD_SHIELD_TASK_GROUP
            description = "Save current ProGuard/R8 rules to the full baseline file (runs R8)"
        }

        // FullFast: the same full rule set read from R8's inputs without
        // running R8. Wired to the `check` lifecycle when enabled.
        val fullFastGuardTask = target.tasks.register(PROGUARD_SHIELD_FULL_FAST_TASK_NAME) {
            group = PROGUARD_SHIELD_TASK_GROUP
            description = "Guard against unintentional ProGuard/R8 rule changes (fullFast, skips R8)"
        }
        val fullFastBaselineTask = target.tasks.register(PROGUARD_SHIELD_FULL_FAST_BASELINE_TASK_NAME) {
            group = PROGUARD_SHIELD_TASK_GROUP
            description = "Save current ProGuard rule inputs to the fullFast baseline file (skips R8)"
        }

        // Parity verification: regenerates both baselines and byte-compares
        // them. A verification aid for maintainers and AI agents (registered
        // when both full and fullFast are enabled), not part of `check`.
        val verifyParityTask = target.tasks.register(PROGUARD_SHIELD_VERIFY_PARITY_TASK_NAME) {
            group = PROGUARD_SHIELD_TASK_GROUP
            description = "Verify that the full and fullFast baselines are byte-identical (verification aid)"
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
                fullGuardTask = fullGuardTask,
                fullBaselineTask = fullBaselineTask,
                fullFastGuardTask = fullFastGuardTask,
                fullFastBaselineTask = fullFastBaselineTask,
                verifyParityTask = verifyParityTask,
            )
        }

        // `check` runs the optimization and fullFast modes of the variants that
        // enable them. Full is reserved for explicit invocation (it runs R8).
        attachToCheckTask(target, guardTask, fullFastGuardTask)
    }

    private fun attachToCheckTask(target: Project, vararg guardTasks: TaskProvider<*>) {
        target.pluginManager.withPlugin("base") {
            target.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME).configure {
                this.dependsOn(*guardTasks)
            }
        }
    }
}
