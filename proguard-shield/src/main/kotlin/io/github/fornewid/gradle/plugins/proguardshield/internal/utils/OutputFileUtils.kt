package io.github.fornewid.gradle.plugins.proguardshield.internal.utils

import java.io.File
import org.gradle.api.Project
import org.gradle.api.file.Directory

internal object OutputFileUtils {

    fun proguardShieldDir(
        project: Project,
        baselineDir: String,
    ): Directory {
        val dir = project.layout.projectDirectory.dir(baselineDir)
        dir.asFile.apply {
            if (!exists()) mkdirs()
        }
        return dir
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
