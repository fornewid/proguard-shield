package io.github.fornewid.gradle.plugins.proguardshield.internal.optimization

import com.google.common.truth.Truth.assertThat
import org.gradle.api.artifacts.ModuleIdentifier
import org.gradle.api.artifacts.component.BuildIdentifier
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.junit.jupiter.api.Test
import java.io.File

class RuleOriginsTest {

    private val projectDir = File("/work/app")

    @Test
    fun `module origin drops the version from the label but keeps it in the detail`() {
        assertThat(RuleOrigins.of(module("com.example", "sdk", "1.2.3")))
            .isEqualTo(RuleOrigin("com.example:sdk", "com.example:sdk:1.2.3"))
    }

    @Test
    fun `project origin uses the project path`() {
        assertThat(RuleOrigins.of(project(":lib"))).isEqualTo(RuleOrigin(":lib", ":lib"))
    }

    @Test
    fun `other components are unresolved`() {
        val id = object : ComponentIdentifier {
            override fun getDisplayName() = "libs/local.jar"
        }
        assertThat(RuleOrigins.of(id)).isEqualTo(RuleOrigin("<unresolved>", "libs/local.jar"))
    }

    @Test
    fun `library files are resolved from the library maps first`() {
        val file = File("/gradle/transforms/abc/proguard.txt")
        val origin = RuleOrigins.resolve(
            file,
            mapOf(file.absolutePath to "com.example:sdk"),
            mapOf(file.absolutePath to "com.example:sdk:1.2.3"),
            projectDir,
            ":app",
        )
        assertThat(origin).isEqualTo(RuleOrigin("com.example:sdk", "com.example:sdk:1.2.3"))
    }

    @Test
    fun `AGP default file wins over the module directory it lives in`() {
        val file = File("/work/app/build/intermediates/default_proguard_files/global/proguard-android-optimize.txt-8.8.0")
        assertThat(RuleOrigins.resolve(file, emptyMap(), emptyMap(), projectDir, ":app"))
            .isEqualTo(RuleOrigin("<agp>", "<agp> (proguard-android-optimize.txt-8.8.0)"))
    }

    @Test
    fun `files inside the module are attributed to the module`() {
        val file = File("/work/app/proguard-rules.pro")
        assertThat(RuleOrigins.resolve(file, emptyMap(), emptyMap(), projectDir, ":app"))
            .isEqualTo(RuleOrigin(":app", ":app (proguard-rules.pro)"))
    }

    @Test
    fun `anything else is unresolved with its path`() {
        val file = File("/elsewhere/rules.pro")
        assertThat(RuleOrigins.resolve(file, emptyMap(), emptyMap(), projectDir, ":app"))
            .isEqualTo(RuleOrigin("<unresolved>", file.absolutePath))
    }

    private fun module(group: String, name: String, version: String): ModuleComponentIdentifier =
        object : ModuleComponentIdentifier {
            override fun getGroup() = group
            override fun getModule() = name
            override fun getVersion() = version
            override fun getModuleIdentifier(): ModuleIdentifier = throw UnsupportedOperationException()
            override fun getDisplayName() = "$group:$name:$version"
        }

    private fun project(path: String): ProjectComponentIdentifier =
        object : ProjectComponentIdentifier {
            override fun getBuild(): BuildIdentifier = throw UnsupportedOperationException()
            override fun getProjectPath() = path
            override fun getBuildTreePath() = path
            override fun getProjectName() = path.substringAfterLast(':')
            override fun getDisplayName() = "project $path"
        }
}
