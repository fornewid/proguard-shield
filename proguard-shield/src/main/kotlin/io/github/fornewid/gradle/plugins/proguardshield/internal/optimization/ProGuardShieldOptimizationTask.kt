package io.github.fornewid.gradle.plugins.proguardshield.internal.optimization

import io.github.fornewid.gradle.plugins.proguardshield.ProGuardShieldPlugin
import io.github.fornewid.gradle.plugins.proguardshield.internal.rules.RuleDiffResult
import io.github.fornewid.gradle.plugins.proguardshield.internal.rules.RuleNormalizer
import io.github.fornewid.gradle.plugins.proguardshield.internal.utils.Messaging
import io.github.fornewid.gradle.plugins.proguardshield.internal.utils.Tasks.declareCompatibilities
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Optimization mode: finds the rules that block R8's shrinking, obfuscation or
 * optimization in the rule files R8 reads, and keeps them in a baseline with
 * their origin (`<variant>OptimizationBlockingRules.txt`, optionally `.tree.txt`).
 * External libraries' rules that reach code outside the library are listed
 * too (LibraryRuleMatcher).
 *
 * Reads R8's inputs through the same AGP-internal accessors as the fullFast
 * mode, without running R8 and without adding anything to R8's inputs.
 */
internal abstract class ProGuardShieldOptimizationTask : DefaultTask() {

    init {
        group = ProGuardShieldPlugin.PROGUARD_SHIELD_TASK_GROUP
    }

    /** Files that R8 would consume — wired from AGP's `ProguardConfigurableTask`. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val ruleInputs: ConfigurableFileCollection

    /** Library rule file absolute path → the dependency that ships it. */
    @get:Input
    abstract val libraryOrigins: MapProperty<String, RuleOrigin>

    /** External library artifacts (AAR/JAR) on the variant's runtime classpath; their classes set each library's own packages. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val libraryArtifacts: ConfigurableFileCollection

    /** Library artifact absolute path → `group:module`. */
    @get:Input
    abstract val libraryArtifactLabels: MapProperty<String, String>

    @get:Input
    abstract val configurationName: Property<String>

    @get:Input
    abstract val projectPath: Property<String>

    /** The module directory, to attribute its own rule files to [projectPath]. */
    @get:Input
    abstract val projectDirPath: Property<String>

    @get:Input
    abstract val shouldBaseline: Property<Boolean>

    @get:Input
    abstract val pluginVersion: Property<String>

    @get:OutputFile
    abstract val listFile: RegularFileProperty

    /** Set only when the configuration enables `tree`. */
    @get:Optional
    @get:OutputFile
    abstract val treeFile: RegularFileProperty

    init {
        declareCompatibilities()
    }

    @TaskAction
    fun execute() {
        val path = projectPath.get()
        val configName = configurationName.get()
        val projectDir = File(projectDirPath.get())
        val origins = libraryOrigins.get()
        val packages by lazy { LibraryPackages.read(libraryArtifactLabels.get().mapKeys { File(it.key) }) }

        // Every output below is sorted or compared as a set, so file order does not matter.
        val rules = ruleInputs.files
            .filter { it.isFile }
            .flatMap { file ->
                val origin = RuleOrigins.resolve(file, origins, projectDir, path)
                RuleNormalizer.normalizeUnits(file.readText())
                    .filter { unit ->
                        OptimizationBlockingRuleMatcher.matches(unit) ||
                            (origin.isLibrary && LibraryRuleMatcher.matches(unit, origin.label, packages))
                    }
                    .map { BlockingRule(it.joinToString("\n"), origin) }
            }

        val listChanges = writeOrCompare(listFile.get().asFile, OptimizationBlockingRuleReport.renderList(rules)) {
            OptimizationBlockingRuleReport.diffList(it, rules)
        }
        val treeChanges = if (treeFile.isPresent) {
            writeOrCompare(treeFile.get().asFile, OptimizationBlockingRuleReport.renderTree(rules)) {
                OptimizationBlockingRuleReport.diffTree(it, rules)
            }
        } else {
            RuleChanges.none()
        }
        if (listChanges.isEmpty && treeChanges.isEmpty) return

        throw GradleException(
            OptimizationBlockingRuleReport.failureMessage(
                projectPath = path,
                configurationName = configName,
                list = listChanges,
                tree = treeChanges,
                rebaselineMessage = Messaging.rebaselineMessage(
                    projectPath = path,
                    configurationName = configName,
                    baselineTaskPrefix = "proguardShieldOptimization",
                    aggregateBaselineTask = ProGuardShieldPlugin.PROGUARD_SHIELD_OPTIMIZATION_BASELINE_TASK_NAME,
                ),
            ),
        )
    }

    /** Writes [content] when re-baselining or when [file] is missing; otherwise compares against it. */
    private fun <T> writeOrCompare(file: File, content: String, compare: (String) -> RuleChanges<T>): RuleChanges<T> {
        if (shouldBaseline.get() || !file.exists()) {
            file.writeText(content)
            logger.lifecycle(
                RuleDiffResult.BaselineCreated(projectPath.get(), configurationName.get(), file).format(withColor = true),
            )
            return RuleChanges.none()
        }
        return compare(file.readText())
    }
}
