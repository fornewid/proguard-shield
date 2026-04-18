package io.github.fornewid.gradle.plugins.proguardshield.internal.utils

import io.github.fornewid.gradle.plugins.proguardshield.ProGuardShieldPlugin

internal object Messaging {

    fun rebaselineMessage(
        projectPath: String,
        configurationName: String,
        baselineTaskPrefix: String = "proguardShield",
        aggregateBaselineTask: String = ProGuardShieldPlugin.PROGUARD_SHIELD_BASELINE_TASK_NAME,
    ): String {
        val separator = if (projectPath == ":") "" else ":"
        return """
            If this is intentional, re-baseline using ./gradlew $projectPath$separator${baselineTaskPrefix}Baseline${configurationName.capitalize()}
            Or use ./gradlew $aggregateBaselineTask to re-baseline in entire project.
        """.trimIndent()
    }

    @Suppress("DEPRECATION")
    private fun String.capitalize(): String {
        return if (isEmpty()) "" else get(0).toUpperCase() + substring(1)
    }
}
