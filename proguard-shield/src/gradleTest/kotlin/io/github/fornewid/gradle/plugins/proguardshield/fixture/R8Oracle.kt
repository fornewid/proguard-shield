package io.github.fornewid.gradle.plugins.proguardshield.fixture

import com.google.common.truth.Truth.assertWithMessage
import io.github.fornewid.gradle.plugins.proguardshield.internal.optimization.OptimizationBlockingRuleMatcher
import io.github.fornewid.gradle.plugins.proguardshield.internal.rules.RuleNormalizer
import java.io.File

/**
 * Checks the optimization list against what R8 itself reads: with `-printconfiguration`, R8 prints every rule it
 * reads under the file it came from (`<unknown>` for rules AGP passes as strings). Leaving out the sources the
 * optimization mode does not read (AAPT2 rules, rules generated while compiling, the project's other modules),
 * every blocking rule R8 reads must be listed, and every listed rule must be one R8 reads.
 */
internal object R8Oracle {

    private val SECTION = Regex("^# The proguard configuration file for the following section is (.+)$")
    private val COMPILED = listOf("/aapt_proguard_file/", "/generated_proguard_file/")

    fun assertOptimizationListMatchesR8(project: AndroidProject, listPath: String) {
        project.dir.resolve("app/proguard-rules.pro").appendText("\n-printconfiguration r8-config.txt\n")
        Builder.build(project, ":app:minifyReleaseWithR8", ":app:proguardShieldOptimizationBaseline")

        val read = inScopeSections(project).flatMap { RuleNormalizer.normalizeUnits(it) }
        val listed = RuleNormalizer.normalizeUnits(project.readOptimizationRules(listPath).orEmpty())
        assertWithMessage("listed rules that R8 does not read").that(read).containsAtLeastElementsIn(listed)
        assertWithMessage("blocking rules R8 reads that are not listed")
            .that(listed)
            .containsAtLeastElementsIn(read.filter(OptimizationBlockingRuleMatcher::matches))
    }

    /** The text of each section R8 printed, except those from sources the optimization mode does not read. */
    private fun inScopeSections(project: AndroidProject): List<String> {
        val sections = mutableListOf<Pair<String, StringBuilder>>()
        project.dir.resolve("app/r8-config.txt").readLines().forEach { line ->
            val source = SECTION.find(line)?.groupValues?.get(1)
            if (source != null) sections += source to StringBuilder() else sections.lastOrNull()?.second?.appendLine(line)
        }
        val root = project.dir.canonicalFile
        val app = root.resolve("app")
        return sections
            .filter { (source, _) ->
                val file = File(source)
                val otherModule = file.isAbsolute && file.canonicalFile.let { it.startsWith(root) && !it.startsWith(app) }
                COMPILED.none { it in file.invariantSeparatorsPath } && !otherModule
            }
            .map { it.second.toString() }
    }
}
