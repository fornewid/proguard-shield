package io.github.fornewid.gradle.plugins.proguardshield

import org.gradle.api.Plugin
import org.gradle.api.Project
import java.util.Properties

/**
 * A plugin for detecting unintentional changes to Android's merged ProGuard/R8 rules
 * and forbidden rule patterns.
 */
public class ProGuardShieldPlugin : Plugin<Project> {

    internal companion object {
        internal val VERSION: String by lazy {
            ProGuardShieldPlugin::class.java
                .getResourceAsStream("/proguard-shield.properties")
                ?.let { Properties().apply { load(it) }.getProperty("version") }
                ?: "dev"
        }
    }

    override fun apply(target: Project) {
        // Intentionally empty for the bootstrap PR.
        // Plugin functionality will be added in subsequent PRs.
    }
}
