package io.github.fornewid.gradle.plugins.proguardshield.internal.printconfig

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Writes a generated `.pro` file containing a single `-printconfiguration <path>`
 * directive. The file is added to `variant.proguardFiles`, so when R8 runs it
 * emits the fully-merged rule set to [mergedRulesPath] for the shield task to diff.
 */
internal abstract class GenerateInjectedRulesTask : DefaultTask() {

    @get:Input
    abstract val mergedRulesPath: Property<String>

    @get:OutputFile
    abstract val outputProFile: RegularFileProperty

    @TaskAction
    fun generate() {
        outputProFile.get().asFile.writeText(
            "-printconfiguration ${mergedRulesPath.get()}\n",
        )
    }
}
