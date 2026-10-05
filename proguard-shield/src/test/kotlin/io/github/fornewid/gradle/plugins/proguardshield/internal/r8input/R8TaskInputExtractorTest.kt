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

    /** Shape of AGP 8.x `ProguardConfigurableTask` (no keep-rule source sets). */
    open inner class Agp8ShapedTask {
        fun getConfigurationFiles(): FileCollection = project.files("app.pro")
        fun getGeneratedProguardFile(): FileCollection = project.files("generated_proguard.txt")
    }

    /** Shape of AGP 9.3+ `ProguardConfigurableTask`. */
    inner class Agp93ShapedTask : Agp8ShapedTask() {
        fun getKeepRulesFiles(): FileCollection = project.files("src/main/keepRules/app.keep")
        fun getAaptProguardFiles(): FileCollection = project.files("aapt_rules.txt")
        fun getFeatureProguardFiles(): FileCollection = project.files("feature/feature-rules.pro")
    }

    inner class MissingRequiredGetterTask {
        fun getConfigurationFiles(): FileCollection = project.files("app.pro")
    }

    /** Shape of AGP's R8 task, which passes some rules to R8 as strings. */
    inner class InlineRulesTask {
        fun getProguardConfigurations(): List<String> = listOf("-keep class org.jacoco.** {*;}")
    }

    @Test
    fun `AGP 8 shaped task reads the required getters only`() {
        val files = R8TaskInputExtractor.ruleFiles(Agp8ShapedTask::class.java, Agp8ShapedTask())

        assertThat(files.relativePaths()).containsExactly("app.pro", "generated_proguard.txt")
    }

    @Test
    fun `AGP 9_3 shaped task also reads keep-rule source sets`() {
        val files = R8TaskInputExtractor.ruleFiles(Agp93ShapedTask::class.java, Agp93ShapedTask())

        assertThat(files.relativePaths())
            .containsExactly(
                "app.pro",
                "generated_proguard.txt",
                "src/main/keepRules/app.keep",
                "aapt_rules.txt",
                "feature/feature-rules.pro",
            )
    }

    @Test
    fun `missing required getter still fails`() {
        val e = assertThrows<GradleException> {
            R8TaskInputExtractor.ruleFiles(MissingRequiredGetterTask::class.java, MissingRequiredGetterTask())
        }

        assertThat(e).hasMessageThat().contains("getGeneratedProguardFile")
    }

    @Test
    fun `reads the rules AGP passes to R8 as strings`() {
        assertThat(R8TaskInputExtractor.inlineRules(InlineRulesTask())).containsExactly("-keep class org.jacoco.** {*;}")
    }

    @Test
    fun `missing inline rules getter fails`() {
        val e = assertThrows<GradleException> { R8TaskInputExtractor.inlineRules(MissingRequiredGetterTask()) }

        assertThat(e).hasMessageThat().contains("getProguardConfigurations")
    }

    private fun FileCollection.relativePaths(): List<String> =
        files.map { it.relativeTo(project.projectDir).invariantSeparatorsPath }
}
