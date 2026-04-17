package io.github.fornewid.gradle.plugins.proguardshield.internal

import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.Variant
import io.github.fornewid.gradle.plugins.proguardshield.ProGuardShieldConfiguration
import io.github.fornewid.gradle.plugins.proguardshield.ProGuardShieldPlugin
import io.github.fornewid.gradle.plugins.proguardshield.ProGuardShieldPluginExtension
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

/**
 * Isolated handler for AGP-specific configuration.
 * Separated from [ProGuardShieldPlugin][io.github.fornewid.gradle.plugins.proguardshield.ProGuardShieldPlugin]
 * to avoid classloader issues with GradleRunner TestKit.
 */
internal object AndroidVariantHandler {

    fun configureVariants(
        project: Project,
        extension: ProGuardShieldPluginExtension,
        guardTask: TaskProvider<*>,
        baselineTask: TaskProvider<*>,
    ) {
        val androidComponents = project.extensions.getByType(AndroidComponentsExtension::class.java)

        // Track all variant names, declared config names, and which matched.
        val allVariantNames = mutableSetOf<String>()
        val declaredConfigNames = mutableSetOf<String>()
        val matchedConfigs = mutableSetOf<String>()

        androidComponents.onVariants { variant ->
            allVariantNames.add(variant.name)
            extension.configurations.configureEach {
                declaredConfigNames.add(configurationName)
                if (configurationName == variant.name) {
                    matchedConfigs.add(configurationName)
                    registerTasks(project, this, variant, guardTask, baselineTask)
                }
            }
        }

        // Validate at task configuration time (not doFirst) — CC-safe.
        // Only plain String sets are referenced — no extension or project objects.
        guardTask.configure {
            validateConfigurations(declaredConfigNames, matchedConfigs, allVariantNames)
        }
        baselineTask.configure {
            validateConfigurations(declaredConfigNames, matchedConfigs, allVariantNames)
        }
    }

    private fun validateConfigurations(
        declaredConfigNames: Set<String>,
        matchedConfigs: Set<String>,
        allVariantNames: Set<String>,
    ) {
        for (name in declaredConfigNames) {
            if (name !in matchedConfigs) {
                throw GradleException(
                    buildString {
                        appendLine("ProGuard Shield could not resolve configuration \"$name\".")
                        if (allVariantNames.isNotEmpty()) {
                            appendLine("Here are some valid configurations you could use.")
                            appendLine()
                            appendLine("proguardShield {")
                            allVariantNames.forEach { appendLine("    configuration(\"$it\")") }
                            appendLine("}")
                        }
                    },
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun String.capitalize(): String {
        return if (isEmpty()) "" else get(0).toUpperCase() + substring(1)
    }

    private fun registerTasks(
        project: Project,
        config: ProGuardShieldConfiguration,
        @Suppress("UNUSED_PARAMETER") variant: Variant,
        guardTask: TaskProvider<*>,
        baselineTask: TaskProvider<*>,
    ) {
        val capitalizedName = config.configurationName.capitalize()

        val perConfigGuardTask = project.tasks.register(
            "proguardShield$capitalizedName",
            ProGuardShieldTask::class.java,
        ) {
            configurationName.set(config.configurationName)
            shouldBaseline.set(false)
            pluginVersion.set(ProGuardShieldPlugin.VERSION)
        }
        guardTask.configure { dependsOn(perConfigGuardTask) }

        val perConfigBaselineTask = project.tasks.register(
            "proguardShieldBaseline$capitalizedName",
            ProGuardShieldTask::class.java,
        ) {
            configurationName.set(config.configurationName)
            shouldBaseline.set(true)
            pluginVersion.set(ProGuardShieldPlugin.VERSION)
        }
        baselineTask.configure { dependsOn(perConfigBaselineTask) }
    }
}
