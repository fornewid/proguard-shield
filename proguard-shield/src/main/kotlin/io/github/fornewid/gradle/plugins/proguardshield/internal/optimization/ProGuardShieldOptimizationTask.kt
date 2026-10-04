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

    /** Library rule file absolute path → version-less origin, e.g. `com.example:sdk`. */
    @get:Input
    abstract val libraryLabels: MapProperty<String, String>

    /** Library rule file absolute path → origin with version, e.g. `com.example:sdk:1.2.3`. */
    @get:Input
    abstract val libraryDetails: MapProperty<String, String>

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

    @get:Input
    abstract val tree: Property<Boolean>

    @get:OutputFile
    abstract val listFile: RegularFileProperty

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
        val labels = libraryLabels.get()
        val details = libraryDetails.get()

        val rules = ruleInputs.files
            .filter { it.isFile }
            .sortedBy { it.absoluteFile.invariantSeparatorsPath }
            .flatMap { file ->
                val origin = RuleOrigins.resolve(file, labels, details, projectDir, path)
                RuleNormalizer.normalizeUnits(file.readText())
                    .filter { OptimizationBlockingRuleMatcher.matches(it) }
                    .map { BlockingRule(it.joinToString("\n"), origin) }
            }

        val listChanges = writeOrCompare(listFile.get().asFile, OptimizationBlockingRuleReport.renderList(rules)) {
            OptimizationBlockingRuleReport.diffList(it, rules)
        }
        val treeChanges = if (tree.get()) {
            writeOrCompare(treeFile.get().asFile, OptimizationBlockingRuleReport.renderTree(rules)) {
                OptimizationBlockingRuleReport.diffTree(it, rules)
            }
        } else {
            RuleChanges.NONE
        }
        if (listChanges.isEmpty && treeChanges.isEmpty) return

        throw GradleException(
            OptimizationBlockingRuleReport.failureMessage(
                projectPath = path,
                configurationName = configName,
                list = listChanges,
                tree = treeChanges,
                rules = rules,
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
    private fun writeOrCompare(file: File, content: String, compare: (String) -> RuleChanges): RuleChanges {
        if (shouldBaseline.get() || !file.exists()) {
            file.writeText(content)
            logger.lifecycle(
                RuleDiffResult.BaselineCreated(projectPath.get(), configurationName.get(), file).format(withColor = true),
            )
            return RuleChanges.NONE
        }
        return compare(file.readText())
    }
}
