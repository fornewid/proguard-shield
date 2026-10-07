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
     * Also write `<variant>OptimizationBlockingRules.tree.txt`, which groups the
     * optimization-blocking rules by the dependency or module that adds them.
     * Disabled by default.
     */
    @get:Input
    public var tree: Boolean = false

    /**
     * Packages, with their subpackages, whose external library rules are left out of the list, such as
     * an ad SDK keeping its own vendor's code: `listOf("com.applovin", "com.google.android.gms.ads")`.
     * A rule is left out only when every package it targets is excluded. Rules that block the whole app
     * and rules that reach the app's namespace are listed anyway. Empty by default.
     */
    @get:Input
    public var excludePackages: List<String> = emptyList()
}
