package io.github.fornewid.gradle.plugins.proguardshield.internal

import io.github.fornewid.gradle.plugins.proguardshield.internal.utils.Tasks.declareCompatibilities
import org.gradle.api.DefaultTask
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

/**
 * Placeholder per-variant task. Real extraction / diff logic lands in follow-up PRs
 * (approach 1: `-printconfiguration`, approach 2-B: R8 task input interception).
 */
internal abstract class ProGuardShieldTask : DefaultTask() {

    @get:Input
    abstract val configurationName: Property<String>

    @get:Input
    abstract val shouldBaseline: Property<Boolean>

    @get:Input
    abstract val pluginVersion: Property<String>

    init {
        declareCompatibilities()
    }

    @TaskAction
    fun execute() {
        val mode = if (shouldBaseline.get()) "baseline" else "guard"
        logger.lifecycle(
            "proguard-shield ${pluginVersion.get()}: ${configurationName.get()} ($mode) — " +
                "functionality under development. See https://github.com/fornewid/proguard-shield for progress.",
        )
    }
}
