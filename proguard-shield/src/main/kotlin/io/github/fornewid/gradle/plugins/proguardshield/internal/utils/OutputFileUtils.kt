package io.github.fornewid.gradle.plugins.proguardshield.internal.utils

import org.gradle.api.Project
import org.gradle.api.file.Directory

internal object OutputFileUtils {

    fun proguardShieldDir(
        project: Project,
        baselineDir: String,
    ): Directory {
        // The optimization task's @OutputFile declarations make Gradle create
        // the directory at execution time; no mkdirs() needed here.
        return project.layout.projectDirectory.dir(baselineDir)
    }
}
