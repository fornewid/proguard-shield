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
    fun `module origin drops the version from the label`() {
        assertThat(RuleOrigins.of(module("com.example", "sdk", "1.2.3")))
            .isEqualTo(RuleOrigin("com.example:sdk"))
    }

    @Test
    fun `project origin uses the project path`() {
        assertThat(RuleOrigins.of(project(":lib"))).isEqualTo(RuleOrigin(":lib"))
    }

    @Test
    fun `file dependencies are libraries labeled by file name`() {
        val id = object : ComponentIdentifier {
            override fun getDisplayName() = "local.jar"
        }
        assertThat(RuleOrigins.of(id)).isEqualTo(RuleOrigin("local.jar"))
        assertThat(RuleOrigins.of(id).isLibrary).isTrue()
    }

    @Test
    fun `other components are unresolved`() {
        val id = object : ComponentIdentifier {
            override fun getDisplayName() = "unknown component"
        }
        assertThat(RuleOrigins.of(id)).isEqualTo(RuleOrigin("<unresolved>"))
    }

    @Test
    fun `library files are resolved from the library map first`() {
        val file = File("/gradle/transforms/abc/proguard.txt")
        val sdk = RuleOrigin("com.example:sdk")
        val origin = RuleOrigins.resolve(file, mapOf(file.absolutePath to sdk), projectDir, ":app")
        assertThat(origin).isEqualTo(sdk)
    }

    @Test
    fun `AGP default file wins over the module directory it lives in`() {
        val file = File("/work/app/build/intermediates/default_proguard_files/global/proguard-android-optimize.txt-8.8.0")
        assertThat(RuleOrigins.resolve(file, emptyMap(), projectDir, ":app"))
            .isEqualTo(RuleOrigin("<agp>"))
    }

    @Test
    fun `files inside the module are attributed to the module`() {
        val file = File("/work/app/proguard-rules.pro")
        assertThat(RuleOrigins.resolve(file, emptyMap(), projectDir, ":app"))
            .isEqualTo(RuleOrigin(":app"))
    }

    @Test
    fun `anything else is unresolved`() {
        val file = File("/elsewhere/rules.pro")
        assertThat(RuleOrigins.resolve(file, emptyMap(), projectDir, ":app"))
            .isEqualTo(RuleOrigin("<unresolved>"))
    }

    @Test
    fun `only external modules are libraries`() {
        assertThat(RuleOrigin("com.example:sdk").isLibrary).isTrue()
        assertThat(RuleOrigin(":lib").isLibrary).isFalse()
        assertThat(RuleOrigin(":").isLibrary).isFalse()
        assertThat(RuleOrigin("<agp>").isLibrary).isFalse()
        assertThat(RuleOrigin("<unresolved>").isLibrary).isFalse()
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
