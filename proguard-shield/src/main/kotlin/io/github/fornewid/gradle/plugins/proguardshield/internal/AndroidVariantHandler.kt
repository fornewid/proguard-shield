package io.github.fornewid.gradle.plugins.proguardshield.internal

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import io.github.fornewid.gradle.plugins.proguardshield.ProGuardShieldConfiguration
import io.github.fornewid.gradle.plugins.proguardshield.ProGuardShieldPlugin
import io.github.fornewid.gradle.plugins.proguardshield.ProGuardShieldPluginExtension
import io.github.fornewid.gradle.plugins.proguardshield.internal.printconfig.GenerateInjectedRulesTask
import io.github.fornewid.gradle.plugins.proguardshield.internal.printconfig.ProGuardShieldListTask
import io.github.fornewid.gradle.plugins.proguardshield.internal.utils.OutputFileUtils
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
        val androidComponents = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)

        val allVariantNames = mutableSetOf<String>()
        val declaredConfigNames = mutableSetOf<String>()
        val matchedConfigs = mutableSetOf<String>()

        androidComponents.onVariants { variant ->
            allVariantNames.add(variant.name)
            extension.configurations.configureEach {
                declaredConfigNames.add(configurationName)
                if (configurationName == variant.name) {
                    matchedConfigs.add(configurationName)
                    registerTasks(
                        project = project,
                        baselineDir = extension.baselineDir.get(),
                        config = this,
                        variant = variant,
                        guardTask = guardTask,
                        baselineTask = baselineTask,
                    )
                }
            }
        }

        // Validate at task configuration time (not doFirst) — CC-safe.
        // `guardTask.configure {}` runs at configuration time and captures only plain
        // String sets; the lambda itself is not serialized into the CC state.
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
        baselineDir: String,
        config: ProGuardShieldConfiguration,
        variant: ApplicationVariant,
        guardTask: TaskProvider<*>,
        baselineTask: TaskProvider<*>,
    ) {
        if (!variant.isMinifyEnabled) {
            throw GradleException(
                "ProGuard Shield: variant \"${variant.name}\" does not have minification enabled. " +
                    "Either enable it via android.buildTypes.${variant.name}.isMinifyEnabled = true, " +
                    "or remove configuration(\"${variant.name}\") from the proguardShield DSL.",
            )
        }

        val capitalizedName = config.configurationName.capitalize()
        val baselineDirectory = OutputFileUtils.proguardShieldDir(project, baselineDir)
        val filePrefix = "${config.configurationName}Rules"
        val variantOutputDir = project.layout.buildDirectory.dir("proguardShield/${variant.name}")
        val mergedRulesFile = variantOutputDir.map { it.file("merged-rules.txt") }
        val injectProFile = variantOutputDir.map { it.file("inject.pro") }

        val injectTask = project.tasks.register(
            "generateProguardShieldInject$capitalizedName",
            GenerateInjectedRulesTask::class.java,
        ) {
            mergedRulesPath.set(mergedRulesFile.map { it.asFile.absolutePath })
            outputProFile.set(injectProFile)
        }

        // Include the generated `.pro` in R8's input list.
        variant.proguardFiles.add(injectTask.flatMap { it.outputProFile })

        val minifyTaskName = "minify${capitalizedName}WithR8"

        val perConfigGuardTask = project.tasks.register(
            "proguardShield$capitalizedName",
            ProGuardShieldListTask::class.java,
        ) {
            dependsOn(minifyTaskName)
            this.mergedRulesFile.set(mergedRulesFile)
            configurationName.set(config.configurationName)
            projectPath.set(project.path)
            shouldBaseline.set(false)
            pluginVersion.set(ProGuardShieldPlugin.VERSION)
            this.baselineDir.set(baselineDirectory)
            this.filePrefix.set(filePrefix)
        }
        guardTask.configure { dependsOn(perConfigGuardTask) }

        val perConfigBaselineTask = project.tasks.register(
            "proguardShieldBaseline$capitalizedName",
            ProGuardShieldListTask::class.java,
        ) {
            dependsOn(minifyTaskName)
            this.mergedRulesFile.set(mergedRulesFile)
            configurationName.set(config.configurationName)
            projectPath.set(project.path)
            shouldBaseline.set(true)
            pluginVersion.set(ProGuardShieldPlugin.VERSION)
            this.baselineDir.set(baselineDirectory)
            this.filePrefix.set(filePrefix)
        }
        baselineTask.configure { dependsOn(perConfigBaselineTask) }
    }
}
