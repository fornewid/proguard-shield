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

        val guardTask = target.tasks.register(PROGUARD_SHIELD_TASK_NAME) {
            group = PROGUARD_SHIELD_TASK_GROUP
            description = "Guard against unintentional ProGuard/R8 rule changes"
        }
        val baselineTask = target.tasks.register(PROGUARD_SHIELD_BASELINE_TASK_NAME) {
            group = PROGUARD_SHIELD_TASK_GROUP
            description = "Save current ProGuard/R8 rules as baseline"
        }

        // Only application modules produce a fully merged ProGuard configuration that
        // includes AAR consumer rules, AAPT2-generated rules, and dynamic plugin rules.
        // Library modules do not go through R8, so there is nothing to shield.
        target.pluginManager.withPlugin("com.android.application") {
            AndroidVariantHandler.configureVariants(target, extension, guardTask, baselineTask)
        }

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
