package io.github.fornewid.gradle.plugins.proguardshield.internal.r8input

import io.github.fornewid.gradle.plugins.proguardshield.ProGuardShieldPlugin
import io.github.fornewid.gradle.plugins.proguardshield.internal.rules.RuleDiff
import io.github.fornewid.gradle.plugins.proguardshield.internal.rules.RuleDiffResult
import io.github.fornewid.gradle.plugins.proguardshield.internal.rules.RuleNormalizer
import io.github.fornewid.gradle.plugins.proguardshield.internal.utils.Messaging
import io.github.fornewid.gradle.plugins.proguardshield.internal.utils.OutputFileUtils
import io.github.fornewid.gradle.plugins.proguardshield.internal.utils.Tasks.declareCompatibilities
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Approach 2-B: reads R8's ProGuard rule inputs directly, without invoking R8.
 *
 * Much faster than approach 1 ([ProGuardShieldListTask][io.github.fornewid.gradle.plugins.proguardshield.internal.printconfig.ProGuardShieldListTask])
 * because the shrinking/dexing step is skipped, but trades away R8's runtime rule
 * generation. The captured baseline is the *source* rules (app rules + AAR
 * consumer rules + AAPT2-generated + default R8 rules), as opposed to R8's
 * post-processed view. Relies on AGP internal API (see [R8TaskInputExtractor]).
 */
internal abstract class ProGuardShieldFastListTask : DefaultTask() {

    init {
        group = ProGuardShieldPlugin.PROGUARD_SHIELD_TASK_GROUP
    }

    /** Files that R8 would consume — wired from AGP's `ProguardConfigurableTask.configurationFiles`. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val ruleInputs: ConfigurableFileCollection

    @get:Input
    abstract val configurationName: Property<String>

    @get:Input
    abstract val projectPath: Property<String>

    @get:Input
    abstract val shouldBaseline: Property<Boolean>

    @get:Input
    abstract val pluginVersion: Property<String>

    @get:OutputDirectory
    abstract val baselineDir: DirectoryProperty

    @get:Input
    abstract val filePrefix: Property<String>

    init {
        declareCompatibilities()
    }

    @TaskAction
    fun execute() {
        val configName = configurationName.get()
        val path = projectPath.get()
        val baseline = shouldBaseline.get()
        val dir = baselineDir.get()
        val prefix = filePrefix.get()

        // Stable order across machines/runs: sort by path relative to project dir.
        val rootDir = project.rootDir
        val concatenated = ruleInputs.files
            .sortedBy { it.relativeTo(rootDir).path }
            .joinToString("\n") { file ->
                if (file.exists() && file.isFile) file.readText() else ""
            }
        val normalized = RuleNormalizer.normalize(concatenated)

        val file = OutputFileUtils.baselineFile(dir, prefix)

        val result: RuleDiffResult = if (baseline || !file.exists()) {
            file.writeText(normalized + "\n")
            RuleDiffResult.BaselineCreated(
                projectPath = path,
                configurationName = configName,
                baselineFile = file,
            )
        } else {
            RuleDiff.compare(
                projectPath = path,
                configurationName = configName,
                expectedContent = file.readText(),
                actualContent = normalized,
            )
        }

        when (result) {
            is RuleDiffResult.HasDiff -> {
                val rebaseline = Messaging.rebaselineMessage(
                    projectPath = path,
                    configurationName = configName,
                    baselineTaskPrefix = "proguardShieldFast",
                    aggregateBaselineTask = ProGuardShieldPlugin.PROGUARD_SHIELD_FAST_BASELINE_TASK_NAME,
                )
                logger.error(result.format(withColor = true, rebaselineMessage = rebaseline))
                throw GradleException(result.format(withColor = false, rebaselineMessage = rebaseline))
            }
            is RuleDiffResult.NoDiff -> logger.debug(result.message)
            is RuleDiffResult.BaselineCreated -> logger.lifecycle(result.format(withColor = true))
        }
    }
}
