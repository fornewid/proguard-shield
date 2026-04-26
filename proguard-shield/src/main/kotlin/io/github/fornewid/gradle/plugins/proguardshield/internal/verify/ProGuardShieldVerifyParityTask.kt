package io.github.fornewid.gradle.plugins.proguardshield.internal.verify

import io.github.fornewid.gradle.plugins.proguardshield.ProGuardShieldPlugin
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

        // Multiset diff so a duplicate-count change (e.g. baseline has 2,
        // actual has 1) is reported instead of being lost to set semantics.
        // Same shape as RuleDiff for consistency.
        val accurateCounts = accurateText.lines().groupingBy { it }.eachCount()
        val fastCounts = fastText.lines().groupingBy { it }.eachCount()
        val onlyAccurate = mutableListOf<String>()
        val onlyFast = mutableListOf<String>()
        for (key in accurateCounts.keys + fastCounts.keys) {
            val a = accurateCounts[key] ?: 0
            val f = fastCounts[key] ?: 0
            when {
                a > f -> repeat(a - f) { onlyAccurate.add(key) }
                f > a -> repeat(f - a) { onlyFast.add(key) }
            }
        }

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
                    "and please file an issue (please include the AGP version): " +
                    "https://github.com/fornewid/proguard-shield/issues",
            )
        }

        // Gradle prints the GradleException message itself, so emitting via
        // logger.error first would just duplicate the same lines on stdout.
        throw GradleException(message)
    }
}
