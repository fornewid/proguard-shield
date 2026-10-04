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
     * Regex patterns that must not appear anywhere in the merged R8 rule
     * input. Empty by default — the plugin enforces no policy unless the
     * project author declares one.
     *
     * Each entry is a Kotlin [Regex] pattern matched with `containsMatchIn`
     * against each normalized rule unit (the header line of a `-keep ... { ... }`
     * block, or a single-line directive like `-dontobfuscate`). Use `^` / `$`
     * to anchor explicitly. Examples:
     *
     * ```
     * forbiddenPatterns = listOf(
     *     "-keep\\s+class\\s+\\*\\*",
     *     "-dontobfuscate",
     *     "-dontshrink",
     * )
     * ```
     *
     * Both the full (`proguardShieldFull`) and fullFast (`proguardShieldFullFast`)
     * modes run the same check on the same normalized inputs, so the parity
     * invariant is preserved. The optimization mode does not use these patterns.
     */
    @get:Input
    public var forbiddenPatterns: List<String> = emptyList()

    /**
     * Keep a baseline of the full merged rule set by reading R8's inputs
     * without running R8 (`<variant>FullFastRules.txt`). Runs as part of
     * `check` when enabled. Relies on AGP internal API. Disabled by default.
     */
    @get:Input
    public var fullFast: Boolean = false

    /**
     * Keep a baseline of the full merged rule set exactly as R8 prints it, by
     * running R8 (`<variant>FullRules.txt`). Uses only public AGP API and is
     * the reference the other modes are checked against. Not part of `check`;
     * run `proguardShieldFull` explicitly. Disabled by default.
     */
    @get:Input
    public var full: Boolean = false
}
