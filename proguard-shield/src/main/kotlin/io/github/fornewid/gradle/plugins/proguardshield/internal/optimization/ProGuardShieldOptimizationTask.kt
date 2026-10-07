package io.github.fornewid.gradle.plugins.proguardshield.internal.optimization

import io.github.fornewid.gradle.plugins.proguardshield.ProGuardShieldPlugin
import io.github.fornewid.gradle.plugins.proguardshield.internal.rules.RuleNormalizer
import io.github.fornewid.gradle.plugins.proguardshield.internal.utils.ColorTerminal
import io.github.fornewid.gradle.plugins.proguardshield.internal.utils.Messaging
import io.github.fornewid.gradle.plugins.proguardshield.internal.utils.Tasks.declareCompatibilities
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
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
 * optimization in the rule files R8 reads, and keeps them in a baseline
 * (`<variant>OptimizationBlockingRules.txt`, with their origins in `.tree.txt`
 * when enabled). External libraries' rules that reach code outside the library
 * are listed too (LibraryRuleMatcher).
 *
 * Reads the rules R8 reads that exist without compiling the variant: the app's
 * rule files and AGP's default file, keep-rule source sets, external libraries'
 * keep rules, and the rules AGP passes to R8 as strings. Doesn't run R8 or add
 * anything to its inputs.
 */
internal abstract class ProGuardShieldOptimizationTask : DefaultTask() {

    init {
        group = ProGuardShieldPlugin.PROGUARD_SHIELD_TASK_GROUP
    }

    /** Rule files R8 reads that exist without compiling. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val ruleInputs: ConfigurableFileCollection

    /** Rules AGP passes to R8 as strings rather than files; their origin is `<agp>`. */
    @get:Input
    abstract val inlineRules: ListProperty<String>

    /** Library rule file absolute path → the dependency that ships it. */
    @get:Input
    abstract val libraryOrigins: MapProperty<String, RuleOrigin>

    /** Package lists ([LibraryPackagesTransform]) of the external library artifacts on the variant's runtime classpath. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val libraryArtifacts: ConfigurableFileCollection

    /** Package list absolute path → the dependency it belongs to. */
    @get:Input
    abstract val libraryArtifactOrigins: MapProperty<String, RuleOrigin>

    /** Packages whose library rules are left out: the configuration's `excludePackages`. */
    @get:Input
    abstract val excludePackages: ListProperty<String>

    /** The app's namespace: a library rule that reaches it reaches the app's code. */
    @get:Input
    abstract val appNamespace: Property<String>

    /** How R8 reads the rules ([R8Context]), written as the list's header. */
    @get:Input
    abstract val r8Context: ListProperty<String>

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
        // Lazy: unused when AGP's optimization.keepRules.ignoreFrom drops every external library's rules.
        val packages by lazy {
            LibraryPackages.read(
                libraryArtifactOrigins.get().entries.associate { File(it.key) to it.value.label },
                appNamespace.get(),
                excludePackages.get(),
            )
        }

        // Every output below is sorted or compared as a set, so file order does not matter.
        val (listed, unlisted) = ruleInputs.files
            .filter { it.isFile }
            .map { file -> file.readText() to RuleOrigins.resolve(file, origins, projectDir, path) }
            .plus(inlineRules.get().joinToString("\n") to RuleOrigin(RuleOrigins.AGP))
            .flatMap { (text, origin) -> RuleNormalizer.normalizeUnits(text).map { it to origin } }
            .partition { (unit, origin) ->
                OptimizationBlockingRuleMatcher.matches(unit) ||
                    (origin.isLibrary && LibraryRuleMatcher.matches(unit, origin.label, packages))
            }
        // A library's rule that a source also declares without it being listed (the app's or AGP's rules, or
        // the library that owns its target) has no effect of its own.
        val declaredElsewhere = unlisted.mapTo(HashSet()) { (unit, _) -> unit }
        val rules = listed
            .filter { (unit, _) -> unit !in declaredElsewhere }
            .map { (unit, origin) -> BlockingRule(unit.single(), origin) }

        val context = r8Context.get()
        val listChanges = writeOrCompare(listFile.get().asFile, OptimizationBlockingRuleReport.renderList(rules, context)) {
            OptimizationBlockingRuleReport.diffList(it, rules, context)
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
                rebaselineMessage = Messaging.rebaselineMessage(path, configName),
            ),
        )
    }

    /** Writes [content] when re-baselining or when [file] is missing; otherwise compares against it. */
    private fun <T> writeOrCompare(file: File, content: String, compare: (String) -> RuleChanges<T>): RuleChanges<T> {
        if (shouldBaseline.get() || !file.exists()) {
            file.writeText(content)
            val message = "ProGuard Shield baseline created for ${projectPath.get()} (${configurationName.get()}).\n" +
                "File: file://${file.canonicalPath}"
            logger.lifecycle(ColorTerminal.colorify(ColorTerminal.ANSI_YELLOW, message))
            return RuleChanges.none()
        }
        return compare(file.readText())
    }
}
