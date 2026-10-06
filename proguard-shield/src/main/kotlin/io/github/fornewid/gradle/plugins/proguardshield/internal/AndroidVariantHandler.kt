package io.github.fornewid.gradle.plugins.proguardshield.internal

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.ApplicationVariant
import io.github.fornewid.gradle.plugins.proguardshield.ProGuardShieldConfiguration
import io.github.fornewid.gradle.plugins.proguardshield.ProGuardShieldPlugin
import io.github.fornewid.gradle.plugins.proguardshield.ProGuardShieldPluginExtension
import io.github.fornewid.gradle.plugins.proguardshield.internal.optimization.LibraryPackagesTransform
import io.github.fornewid.gradle.plugins.proguardshield.internal.optimization.ProGuardShieldOptimizationTask
import io.github.fornewid.gradle.plugins.proguardshield.internal.optimization.R8Context
import io.github.fornewid.gradle.plugins.proguardshield.internal.optimization.RuleOrigins
import io.github.fornewid.gradle.plugins.proguardshield.internal.r8input.IgnoredLibraryKeepRules
import io.github.fornewid.gradle.plugins.proguardshield.internal.r8input.R8TaskInputExtractor
import io.github.fornewid.gradle.plugins.proguardshield.internal.utils.OutputFileUtils
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.type.ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE
import org.gradle.api.tasks.TaskProvider

/**
 * Isolated handler for AGP-specific configuration.
 * Separated from [ProGuardShieldPlugin][io.github.fornewid.gradle.plugins.proguardshield.ProGuardShieldPlugin]
 * to avoid classloader issues with GradleRunner TestKit.
 */
internal object AndroidVariantHandler {

    /** AGP's (internal) artifact type of the library keep rules R8 receives. */
    private const val FILTERED_PROGUARD_RULES = "android-filtered-proguard-rules"

    fun configureVariants(
        project: Project,
        extension: ProGuardShieldPluginExtension,
        optimizationGuardTask: TaskProvider<*>,
        optimizationBaselineTask: TaskProvider<*>,
    ) {
        val androidComponents = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
        LibraryPackagesTransform.register(project.dependencies)

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
                    )
                }
            }
        }

        // Validate at task configuration time (not doFirst) — CC-safe.
        // `configure {}` runs at configuration time and captures only plain
        // String sets; the lambda itself is not serialized into the CC state.
        // Both aggregates validate, so whichever the user runs reports it.
        listOf(optimizationGuardTask, optimizationBaselineTask).forEach { aggregate ->
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
    ) {
        if (!variant.isMinifyEnabled) {
            throw GradleException(
                "ProGuard Shield: variant \"${variant.name}\" does not have minification enabled. " +
                    "Either enable it via android.buildTypes.${variant.buildType}.isMinifyEnabled = true " +
                    "(Groovy: minifyEnabled true), " +
                    "or remove configuration(\"${variant.name}\") from the proguardShield DSL.",
            )
        }

        if (!config.optimization) return

        val capitalizedName = config.configurationName.capitalize()
        val baselineDirectory = OutputFileUtils.proguardShieldDir(project, baselineDir)
        val minifyTaskName = "minify${capitalizedName}WithR8"
        val inlineRules = project.provider { R8TaskInputExtractor.inlineRules(project.tasks.named(minifyTaskName).get()) }

        // External libraries' artifacts of [artifactType]. The project's own modules are left out: their
        // keep rules only exist once they compile.
        fun libraryArtifacts(artifactType: String) = variant.runtimeConfiguration.incoming
            .artifactView {
                componentFilter { RuleOrigins.of(it).isLibrary }
                attributes { attribute(ARTIFACT_TYPE_ATTRIBUTE, artifactType) }
            }
            .artifacts

        // External libraries' keep rules as R8 receives them.
        val libraryKeepRules = libraryArtifacts(FILTERED_PROGUARD_RULES)
        // The rules R8 reads that exist without compiling the variant. Lazy: the R8 task doesn't exist yet in onVariants.
        val ruleInputs = project.provider {
            val minifyTask = project.tasks.named(minifyTaskName).get()
            project.files(
                variant.proguardFiles,
                listOfNotNull(R8TaskInputExtractor.keepRulesFiles(minifyTask)),
                IgnoredLibraryKeepRules.exclude(minifyTask, libraryKeepRules, project.objects),
            )
        }
        val listFile = baselineDirectory.file("${config.configurationName}OptimizationBlockingRules.txt")
        val treeFile = baselineDirectory.file("${config.configurationName}OptimizationBlockingRules.tree.txt")
        val projectDirPath = project.projectDir.absolutePath

        val libraryPackages = libraryArtifacts(LibraryPackagesTransform.ARTIFACT_TYPE)
        val appNamespace = variant.namespace
        val r8Context = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java).pluginVersion.run {
            R8Context.lines(R8Context.agpVersion(major, minor, micro, previewType, preview)) { project.providers.gradleProperty(it).orNull }
        }

        fun ProGuardShieldOptimizationTask.configureOptimization(baseline: Boolean) {
            this.ruleInputs.from(ruleInputs)
            this.inlineRules.set(inlineRules)
            // For the AGP default file under build/intermediates/default_proguard_files/.
            dependsOn("extractProguardFiles")
            this.libraryOrigins.set(RuleOrigins.byPath(libraryKeepRules))
            this.libraryArtifacts.from(libraryPackages.artifactFiles)
            this.libraryArtifactOrigins.set(RuleOrigins.byPath(libraryPackages))
            this.appNamespace.set(appNamespace)
            this.r8Context.set(r8Context)
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
}
