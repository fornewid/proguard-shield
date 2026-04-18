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
}
