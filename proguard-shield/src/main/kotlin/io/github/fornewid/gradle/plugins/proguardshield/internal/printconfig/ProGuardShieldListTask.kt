package io.github.fornewid.gradle.plugins.proguardshield.internal.printconfig

import io.github.fornewid.gradle.plugins.proguardshield.ProGuardShieldPlugin
import io.github.fornewid.gradle.plugins.proguardshield.internal.forbidden.ForbiddenPatternChecker
import io.github.fornewid.gradle.plugins.proguardshield.internal.rules.RuleDiff
import io.github.fornewid.gradle.plugins.proguardshield.internal.rules.RuleDiffResult
import io.github.fornewid.gradle.plugins.proguardshield.internal.rules.RuleNormalizer
import io.github.fornewid.gradle.plugins.proguardshield.internal.utils.Messaging
import io.github.fornewid.gradle.plugins.proguardshield.internal.utils.OutputFileUtils
import io.github.fornewid.gradle.plugins.proguardshield.internal.utils.Tasks.declareCompatibilities
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

/**
 * Reads R8's `-printconfiguration` output and either writes it to a baseline file
 * ([shouldBaseline] = true / baseline missing) or diffs against an existing baseline.
 *
 * Approach 1 of the v0 prototype — accurate (100% of R8's merged rules) but slow
 * (requires running the full `minify{Variant}WithR8` task beforehand).
 */
internal abstract class ProGuardShieldListTask : DefaultTask() {

    init {
        group = ProGuardShieldPlugin.PROGUARD_SHIELD_TASK_GROUP
    }

    /** R8 `-printconfiguration` output, produced by `minify{Variant}WithR8`. */
    @get:InputFile
    abstract val mergedRulesFile: RegularFileProperty

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

    @get:Input
    abstract val forbiddenPatterns: ListProperty<String>

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

        val rawContent = mergedRulesFile.get().asFile.readText()
        val units = RuleNormalizer.normalizeUnits(rawContent)
        val normalized = units.flatten().joinToString("\n")

        // Forbidden-pattern check first — strongest signal, no rebaseline can
        // silence it. Both the accurate and fast tasks run the identical check
        // on the identical normalized inputs, preserving parity.
        val violations = try {
            ForbiddenPatternChecker.check(units, forbiddenPatterns.get())
        } catch (e: IllegalArgumentException) {
            throw GradleException(e.message ?: "Invalid forbiddenPatterns regex", e)
        }
        if (violations.isNotEmpty()) {
            val message = ForbiddenPatternChecker.renderFailureMessage(path, configName, violations)
            logger.error(message)
            throw GradleException(message)
        }

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
                val rebaseline = Messaging.rebaselineMessage(path, configName)
                logger.error(result.format(withColor = true, rebaselineMessage = rebaseline))
                throw GradleException(result.format(withColor = false, rebaselineMessage = rebaseline))
            }
            is RuleDiffResult.NoDiff -> logger.debug(result.message)
            is RuleDiffResult.BaselineCreated -> logger.lifecycle(result.format(withColor = true))
        }
    }
}
