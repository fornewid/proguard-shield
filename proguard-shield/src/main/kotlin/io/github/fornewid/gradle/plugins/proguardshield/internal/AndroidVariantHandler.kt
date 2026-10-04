package io.github.fornewid.gradle.plugins.proguardshield.internal

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import io.github.fornewid.gradle.plugins.proguardshield.ProGuardShieldConfiguration
import io.github.fornewid.gradle.plugins.proguardshield.ProGuardShieldPlugin
import io.github.fornewid.gradle.plugins.proguardshield.ProGuardShieldPluginExtension
import io.github.fornewid.gradle.plugins.proguardshield.internal.optimization.LibraryKeepRuleOrigins
import io.github.fornewid.gradle.plugins.proguardshield.internal.optimization.ProGuardShieldOptimizationTask
import io.github.fornewid.gradle.plugins.proguardshield.internal.optimization.RuleOrigins
import io.github.fornewid.gradle.plugins.proguardshield.internal.printconfig.GenerateInjectedRulesTask
import io.github.fornewid.gradle.plugins.proguardshield.internal.printconfig.ProGuardShieldListTask
import io.github.fornewid.gradle.plugins.proguardshield.internal.r8input.IgnoredLibraryKeepRules
import io.github.fornewid.gradle.plugins.proguardshield.internal.r8input.ProGuardShieldFastListTask
import io.github.fornewid.gradle.plugins.proguardshield.internal.r8input.R8TaskInputExtractor
import io.github.fornewid.gradle.plugins.proguardshield.internal.utils.OutputFileUtils
import io.github.fornewid.gradle.plugins.proguardshield.internal.verify.ProGuardShieldVerifyParityTask
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
        optimizationGuardTask: TaskProvider<*>,
        optimizationBaselineTask: TaskProvider<*>,
        fullGuardTask: TaskProvider<*>,
        fullBaselineTask: TaskProvider<*>,
        fullFastGuardTask: TaskProvider<*>,
        fullFastBaselineTask: TaskProvider<*>,
        verifyParityTask: TaskProvider<*>,
    ) {
        val androidComponents = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)

        val allVariantNames = mutableSetOf<String>()
        val declaredConfigNames = mutableSetOf<String>()
        val matchedConfigs = mutableSetOf<String>()

        androidComponents.onVariants { variant ->
            // Only minify-enabled variants are valid; suggesting others leads to the next error.
            if (variant.isMinifyEnabled) allVariantNames.add(variant.name)
            extension.configurations.configureEach {
                declaredConfigNames.add(configurationName)
                if (configurationName == variant.name) {
                    matchedConfigs.add(configurationName)
                    registerTasks(
                        project = project,
                        baselineDir = extension.baselineDir.get(),
                        config = this,
                        variant = variant,
                        optimizationGuardTask = optimizationGuardTask,
                        optimizationBaselineTask = optimizationBaselineTask,
                        fullGuardTask = fullGuardTask,
                        fullBaselineTask = fullBaselineTask,
                        fullFastGuardTask = fullFastGuardTask,
                        fullFastBaselineTask = fullFastBaselineTask,
                        verifyParityTask = verifyParityTask,
                    )
                }
            }
        }

        // Validate at task configuration time (not doFirst) — CC-safe.
        // `configure {}` runs at configuration time and captures only plain
        // String sets; the lambda itself is not serialized into the CC state.
        // Every aggregate validates, so whichever mode the user runs reports it.
        listOf(
            optimizationGuardTask, optimizationBaselineTask, fullGuardTask, fullBaselineTask,
            fullFastGuardTask, fullFastBaselineTask, verifyParityTask,
        ).forEach { aggregate ->
            aggregate.configure { validateConfigurations(declaredConfigNames, matchedConfigs, allVariantNames) }
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
        optimizationGuardTask: TaskProvider<*>,
        optimizationBaselineTask: TaskProvider<*>,
        fullGuardTask: TaskProvider<*>,
        fullBaselineTask: TaskProvider<*>,
        fullFastGuardTask: TaskProvider<*>,
        fullFastBaselineTask: TaskProvider<*>,
        verifyParityTask: TaskProvider<*>,
    ) {
        if (!variant.isMinifyEnabled) {
            throw GradleException(
                "ProGuard Shield: variant \"${variant.name}\" does not have minification enabled. " +
                    "Either enable it via android.buildTypes.${variant.buildType}.isMinifyEnabled = true " +
                    "(Groovy: minifyEnabled true), " +
                    "or remove configuration(\"${variant.name}\") from the proguardShield DSL.",
            )
        }

        val capitalizedName = config.configurationName.capitalize()
        val baselineDirectory = OutputFileUtils.proguardShieldDir(project, baselineDir)
        val fullFilePrefix = "${config.configurationName}FullRules"
        val fullFastFilePrefix = "${config.configurationName}FullFastRules"
        val minifyTaskName = "minify${capitalizedName}WithR8"
        val injectTaskName = "generateProguardShieldInject$capitalizedName"

        // ---- Full: runs R8 with -printconfiguration (public AGP API only) ----
        if (config.full) {
            val variantOutputDir = project.layout.buildDirectory.dir("proguardShield/${variant.name}")
            val mergedRulesFile = variantOutputDir.map { it.file("merged-rules.txt") }
            val injectProFile = variantOutputDir.map { it.file("inject.pro") }

            val injectTask = project.tasks.register(injectTaskName, GenerateInjectedRulesTask::class.java) {
                mergedRulesPath.set(mergedRulesFile.map { it.asFile.absolutePath })
                outputProFile.set(injectProFile)
            }

            // Include the generated `.pro` in R8's input list. Only variants that
            // enable full get it, so the other modes leave R8's inputs (and its
            // build cache key) untouched.
            variant.proguardFiles.add(injectTask.flatMap { it.outputProFile })

            val fullConfigGuardTask = project.tasks.register(
                "proguardShieldFull$capitalizedName",
                ProGuardShieldListTask::class.java,
            ) {
                dependsOn(minifyTaskName)
                this.mergedRulesFile.set(mergedRulesFile)
                configurationName.set(config.configurationName)
                projectPath.set(project.path)
                shouldBaseline.set(false)
                pluginVersion.set(ProGuardShieldPlugin.VERSION)
                this.baselineDir.set(baselineDirectory)
                this.filePrefix.set(fullFilePrefix)
                forbiddenPatterns.set(config.forbiddenPatterns)
            }
            fullGuardTask.configure { dependsOn(fullConfigGuardTask) }

            val fullConfigBaselineTask = project.tasks.register(
                "proguardShieldFullBaseline$capitalizedName",
                ProGuardShieldListTask::class.java,
            ) {
                dependsOn(minifyTaskName)
                this.mergedRulesFile.set(mergedRulesFile)
                configurationName.set(config.configurationName)
                projectPath.set(project.path)
                shouldBaseline.set(true)
                pluginVersion.set(ProGuardShieldPlugin.VERSION)
                this.baselineDir.set(baselineDirectory)
                this.filePrefix.set(fullFilePrefix)
                forbiddenPatterns.set(config.forbiddenPatterns)
            }
            fullBaselineTask.configure { dependsOn(fullConfigBaselineTask) }

            // Guard and baseline tasks share `baselineDir` as an output. When both
            // run in one build, the guard must compare against the committed
            // baseline before it is regenerated.
            fullConfigBaselineTask.configure { mustRunAfter(fullConfigGuardTask) }
        }

        // ---- R8's rule inputs, read without running R8 (fullFast, optimization) ----
        val ruleInputs = project.provider {
            val minifyTask = project.tasks.named(minifyTaskName).get()
            IgnoredLibraryKeepRules.exclude(
                minifyTask,
                R8TaskInputExtractor.allRuleFiles(minifyTask),
                project.objects,
            )
        }

        // Each library rule file mapped to the dependency that ships it (optimization), lazy like `ruleInputs`.
        val libraryOrigins = project.provider { project.tasks.named(minifyTaskName).get() }
            .flatMap { LibraryKeepRuleOrigins.of(it) }

        // configurationFiles references the user-selected default file under
        // build/intermediates/default_proguard_files/, which only exists after
        // extractProguardFiles runs. AAPT2-generated rules similarly require
        // their merge task, and with full enabled the injected `.pro` is one of
        // R8's inputs too. These task names are AGP-internal — if they ever
        // change, Gradle surfaces a "Task not found" error at execution time
        // and users can fall back to the full mode.
        val fastExtraDepNames = listOfNotNull(
            "extractProguardFiles",
            "merge${capitalizedName}GeneratedProguardFiles",
            injectTaskName.takeIf { config.full },
        )

        val rootDir = project.rootDir.absolutePath

        // ---- Optimization (default): optimization-blocking rules and their origins ----
        if (config.optimization) {
            val listFile = baselineDirectory.file("${config.configurationName}OptimizationBlockingRules.txt")
            val treeFile = baselineDirectory.file("${config.configurationName}OptimizationBlockingRules.tree.txt")
            val projectDirPath = project.projectDir.absolutePath

            val libraryArtifacts = variant.runtimeConfiguration.incoming
                .artifactView { componentFilter { RuleOrigins.of(it).isLibrary } }
                .artifacts
            val appNamespace = variant.namespace

            fun ProGuardShieldOptimizationTask.configureOptimization(baseline: Boolean) {
                this.ruleInputs.from(ruleInputs)
                fastExtraDepNames.forEach { dependsOn(it) }
                this.libraryOrigins.set(libraryOrigins)
                this.libraryArtifacts.from(libraryArtifacts.artifactFiles)
                this.libraryArtifactOrigins.set(RuleOrigins.byPath(libraryArtifacts))
                this.appNamespace.set(appNamespace)
                configurationName.set(config.configurationName)
                projectPath.set(project.path)
                this.projectDirPath.set(projectDirPath)
                shouldBaseline.set(baseline)
                pluginVersion.set(ProGuardShieldPlugin.VERSION)
                this.listFile.set(listFile)
                if (config.tree) this.treeFile.set(treeFile)
            }

            val optimizationConfigGuardTask = project.tasks.register(
                "proguardShieldOptimization$capitalizedName",
                ProGuardShieldOptimizationTask::class.java,
            ) { configureOptimization(baseline = false) }
            optimizationGuardTask.configure { dependsOn(optimizationConfigGuardTask) }

            val optimizationConfigBaselineTask = project.tasks.register(
                "proguardShieldOptimizationBaseline$capitalizedName",
                ProGuardShieldOptimizationTask::class.java,
            ) { configureOptimization(baseline = true) }
            optimizationBaselineTask.configure { dependsOn(optimizationConfigBaselineTask) }
            optimizationConfigBaselineTask.configure { mustRunAfter(optimizationConfigGuardTask) }
        }

        // ---- FullFast: the full rule set from R8's inputs, without running R8 ----
        if (config.fullFast) {
            val fullFastConfigGuardTask = project.tasks.register(
                "proguardShieldFullFast$capitalizedName",
                ProGuardShieldFastListTask::class.java,
            ) {
                this.ruleInputs.from(ruleInputs)
                fastExtraDepNames.forEach { dependsOn(it) }
                configurationName.set(config.configurationName)
                projectPath.set(project.path)
                shouldBaseline.set(false)
                pluginVersion.set(ProGuardShieldPlugin.VERSION)
                this.baselineDir.set(baselineDirectory)
                this.filePrefix.set(fullFastFilePrefix)
                this.rootDirPath.set(rootDir)
                forbiddenPatterns.set(config.forbiddenPatterns)
            }
            fullFastGuardTask.configure { dependsOn(fullFastConfigGuardTask) }

            val fullFastConfigBaselineTask = project.tasks.register(
                "proguardShieldFullFastBaseline$capitalizedName",
                ProGuardShieldFastListTask::class.java,
            ) {
                this.ruleInputs.from(ruleInputs)
                fastExtraDepNames.forEach { dependsOn(it) }
                configurationName.set(config.configurationName)
                projectPath.set(project.path)
                shouldBaseline.set(true)
                pluginVersion.set(ProGuardShieldPlugin.VERSION)
                this.baselineDir.set(baselineDirectory)
                this.filePrefix.set(fullFastFilePrefix)
                this.rootDirPath.set(rootDir)
                forbiddenPatterns.set(config.forbiddenPatterns)
            }
            fullFastBaselineTask.configure { dependsOn(fullFastConfigBaselineTask) }
            fullFastConfigBaselineTask.configure { mustRunAfter(fullFastConfigGuardTask) }
        }

        // ---- Parity: verification aid that fullFast matches full ----
        if (config.full && config.fullFast) {
            val perConfigVerifyParityTask = project.tasks.register(
                "proguardShieldVerifyParity$capitalizedName",
                ProGuardShieldVerifyParityTask::class.java,
            ) {
                // Force a fresh capture of both baselines first so the comparison
                // reflects the current build, not whatever was committed earlier.
                dependsOn("proguardShieldFullBaseline$capitalizedName")
                dependsOn("proguardShieldFullFastBaseline$capitalizedName")
                fullBaseline.set(baselineDirectory.file("$fullFilePrefix.txt"))
                fullFastBaseline.set(baselineDirectory.file("$fullFastFilePrefix.txt"))
                configurationName.set(config.configurationName)
                projectPath.set(project.path)
            }
            verifyParityTask.configure { dependsOn(perConfigVerifyParityTask) }
        }
    }
}
