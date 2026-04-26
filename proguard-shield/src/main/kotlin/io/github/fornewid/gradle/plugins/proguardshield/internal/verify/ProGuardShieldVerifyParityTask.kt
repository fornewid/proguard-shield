package io.github.fornewid.gradle.plugins.proguardshield.internal.verify

import io.github.fornewid.gradle.plugins.proguardshield.ProGuardShieldPlugin
import io.github.fornewid.gradle.plugins.proguardshield.internal.utils.ColorTerminal
import io.github.fornewid.gradle.plugins.proguardshield.internal.utils.Tasks.declareCompatibilities
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Reads the accurate and fast baseline files for a variant after both have
 * been (re)generated, and fails the build if their contents disagree.
 *
 * The intent is a deliberate parity check at known moments — first install,
 * AGP upgrade — where the developer wants a positive signal that the fast
 * path's reflection-based extraction still produces the same baseline R8
 * itself would. Daily / CI builds run only the fast path; this task is the
 * explicit gate that says "fast is trustworthy on this setup".
 */
internal abstract class ProGuardShieldVerifyParityTask : DefaultTask() {

    init {
        group = ProGuardShieldPlugin.PROGUARD_SHIELD_TASK_GROUP
    }

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val accurateBaseline: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val fastBaseline: RegularFileProperty

    @get:Input
    abstract val configurationName: Property<String>

    @get:Input
    abstract val projectPath: Property<String>

    init {
        declareCompatibilities()
    }

    @TaskAction
    fun execute() {
        val accurate = accurateBaseline.get().asFile
        val fast = fastBaseline.get().asFile
        val accurateText = accurate.readText()
        val fastText = fast.readText()

        if (accurateText == fastText) {
            logger.lifecycle(
                "ProGuard Shield parity holds for ${projectPath.get()} (${configurationName.get()}): " +
                    "${accurate.name} and ${fast.name} are byte-identical.",
            )
            return
        }

        val accurateLines = accurateText.lines()
        val fastLines = fastText.lines()
        val onlyAccurate = accurateLines.filterNot { it in fastLines }
        val onlyFast = fastLines.filterNot { it in accurateLines }

        val message = buildString {
            appendLine(
                "ProGuard Shield parity FAILED for ${projectPath.get()} (${configurationName.get()}). " +
                    "The accurate baseline (${accurate.name}) and the fast baseline (${fast.name}) " +
                    "diverge — the fast path cannot be trusted on this setup until the cause is investigated.",
            )
            appendLine()
            appendLine("Lines only in ${accurate.name}:")
            if (onlyAccurate.isEmpty()) appendLine("  (none)") else onlyAccurate.forEach { appendLine("  - $it") }
            appendLine()
            appendLine("Lines only in ${fast.name}:")
            if (onlyFast.isEmpty()) appendLine("  (none)") else onlyFast.forEach { appendLine("  + $it") }
            appendLine()
            appendLine(
                "Run ./gradlew ${projectPath.get()}:proguardShield to use the accurate path until parity is restored, " +
                    "and please file an issue: https://github.com/fornewid/proguard-shield/issues",
            )
        }

        logger.error(ColorTerminal.colorify(ColorTerminal.ANSI_RED, message))
        throw GradleException(message)
    }
}
