package io.github.fornewid.gradle.plugins.proguardshield.internal.r8input

import com.google.common.truth.Truth.assertThat
import org.gradle.api.GradleException
import org.gradle.api.file.FileCollection
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class R8TaskInputExtractorTest {

    @TempDir
    lateinit var tempDir: File

    private val project by lazy { ProjectBuilder.builder().withProjectDir(tempDir).build() }

    /** Shape of AGP 9.1+'s R8 task, which also has keep-rule source sets. */
    inner class KeepRulesTask {
        fun getKeepRulesFiles(): FileCollection = project.files("src/main/keepRules/app.keep")
    }

    /** Shape of AGP's R8 task, which passes some rules to R8 as strings. */
    inner class InlineRulesTask {
        fun getProguardConfigurations(): List<String> = listOf("-keep class org.jacoco.** {*;}")
    }

    @Test
    fun `reads keep-rule source sets`() {
        assertThat(R8TaskInputExtractor.keepRulesFiles(KeepRulesTask())?.relativePaths())
            .containsExactly("src/main/keepRules/app.keep")
    }

    @Test
    fun `no keep-rule source sets before AGP 9_1`() {
        assertThat(R8TaskInputExtractor.keepRulesFiles(Any())).isNull()
    }

    @Test
    fun `reads the rules AGP passes to R8 as strings`() {
        assertThat(R8TaskInputExtractor.inlineRules(InlineRulesTask())).containsExactly("-keep class org.jacoco.** {*;}")
    }

    @Test
    fun `missing inline rules getter fails`() {
        val e = assertThrows<GradleException> { R8TaskInputExtractor.inlineRules(Any()) }

        assertThat(e).hasMessageThat().contains("getProguardConfigurations")
    }

    private fun FileCollection.relativePaths(): List<String> =
        files.map { it.relativeTo(project.projectDir).invariantSeparatorsPath }
}
