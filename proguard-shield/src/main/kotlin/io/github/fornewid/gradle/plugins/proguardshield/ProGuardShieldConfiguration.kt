package io.github.fornewid.gradle.plugins.proguardshield

import org.gradle.api.Named
import org.gradle.api.tasks.Input
import javax.inject.Inject

/**
 * Configuration for [ProGuardShieldPlugin] per build variant.
 */
public open class ProGuardShieldConfiguration @Inject constructor(
    /**
     * Name of the build variant (e.g., "release", "debug", "devRelease").
     */
    @get:Input
    public val configurationName: String,
) : Named {

    @Input
    public override fun getName(): String = configurationName

    /**
     * Track rules that block R8's shrinking, obfuscation or optimization
     * (`-dontobfuscate`, keeps of every class, `-keepattributes *`, …) together
     * with the dependency or module that adds them
     * (`<variant>OptimizationBlockingRules.txt`). Runs as part of `check`.
     * Enabled by default; `false` registers no tasks for this configuration.
     */
    @get:Input
    public var optimization: Boolean = true

    /**
     * Also write `<variant>OptimizationBlockingRules.tree.txt`, which groups the
     * optimization-blocking rules by the dependency or module that adds them.
     * Used only when [optimization] is enabled. Disabled by default.
     */
    @get:Input
    public var tree: Boolean = false
}
