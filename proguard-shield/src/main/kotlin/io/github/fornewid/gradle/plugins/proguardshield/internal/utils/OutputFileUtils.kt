package io.github.fornewid.gradle.plugins.proguardshield.internal.utils

import java.io.File
import org.gradle.api.Project
import org.gradle.api.file.Directory

internal object OutputFileUtils {

    fun proguardShieldDir(
        project: Project,
        baselineDir: String,
    ): Directory {
        // The @OutputDirectory declaration on ProGuardShieldListTask ensures Gradle
        // materializes the directory at execution time; no mkdirs() needed here.
        return project.layout.projectDirectory.dir(baselineDir)
    }

    fun baselineFile(
        directory: Directory,
        fileName: String,
    ): File {
        return directory
            .file("$fileName.txt")
            .asFile
            .apply {
                parentFile.apply {
                    if (!exists()) mkdirs()
                }
            }
    }
}
