package io.github.fornewid.gradle.plugins.proguardshield

import org.gradle.api.Plugin
import org.gradle.api.Project

public class ProGuardShieldPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        // Intentionally empty for the bootstrap PR.
        // Plugin functionality will be added in subsequent PRs.
    }

    public companion object {
        public const val VERSION: String = "0.1.0-SNAPSHOT"
    }
}
