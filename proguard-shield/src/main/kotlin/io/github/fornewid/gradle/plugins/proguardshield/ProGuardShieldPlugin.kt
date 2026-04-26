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

        // Accurate (approach 1): runs R8 with -printconfiguration. Reserved
        // for explicit invocation; not on the default `check` lifecycle.
        val guardTask = target.tasks.register(PROGUARD_SHIELD_TASK_NAME) {
            group = PROGUARD_SHIELD_TASK_GROUP
            description = "Guard against unintentional ProGuard/R8 rule changes (accurate, runs R8)"
        }
        // The baseline aggregate writes both files in one shot — it is the
        // command users run on first install or after intentional rule
        // changes, and they need both committed for the fast path to verify
        // against the same source of truth.
        val baselineTask = target.tasks.register(PROGUARD_SHIELD_BASELINE_TASK_NAME) {
            group = PROGUARD_SHIELD_TASK_GROUP
            description = "Save current ProGuard/R8 rules to both baseline files (accurate + fast)"
        }

        // Fast (approach 2-B): reads R8 rule inputs directly. Wired to the
        // `check` lifecycle so every CI build catches drift cheaply.
        val fastGuardTask = target.tasks.register(PROGUARD_SHIELD_FAST_TASK_NAME) {
            group = PROGUARD_SHIELD_TASK_GROUP
            description = "Guard against unintentional ProGuard/R8 rule changes (fast, skips R8)"
        }
        val fastBaselineTask = target.tasks.register(PROGUARD_SHIELD_FAST_BASELINE_TASK_NAME) {
            group = PROGUARD_SHIELD_TASK_GROUP
            description = "Save current ProGuard rule inputs to the fast baseline file (skips R8)"
        }

        // Parity verification: regenerates both baselines and byte-compares
        // them. Run this on first install and after every AGP upgrade to
        // confirm that the fast path is trustworthy on the current setup.
        val verifyParityTask = target.tasks.register(PROGUARD_SHIELD_VERIFY_PARITY_TASK_NAME) {
            group = PROGUARD_SHIELD_TASK_GROUP
            description = "Verify that the accurate and fast baselines are byte-identical (run on first install / AGP upgrade)"
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
                verifyParityTask = verifyParityTask,
            )
        }

        // `check` runs only the fast path — accurate is reserved for
        // explicit invocation (`./gradlew :app:proguardShield`) and for
        // the parity-verification flow (`./gradlew :app:proguardShieldVerifyParity`).
        attachToCheckTask(target, fastGuardTask)
    }

    private fun attachToCheckTask(target: Project, guardTask: TaskProvider<*>) {
        target.pluginManager.withPlugin("base") {
            target.tasks.named(LifecycleBasePlugin.CHECK_TASK_NAME).configure {
                this.dependsOn(guardTask)
            }
        }
    }
}
