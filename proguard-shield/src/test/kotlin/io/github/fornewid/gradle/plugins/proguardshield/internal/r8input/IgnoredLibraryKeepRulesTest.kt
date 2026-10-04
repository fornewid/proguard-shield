package io.github.fornewid.gradle.plugins.proguardshield.internal.r8input

import com.google.common.truth.Truth.assertThat
import org.gradle.api.artifacts.ModuleIdentifier
import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.junit.jupiter.api.Test

class IgnoredLibraryKeepRulesTest {

    private val sdk = module("com.example", "sdk", "1.2.3")
    private val project = projectId(":lib")

    @Test
    fun `module matches by group and name`() {
        assertThat(IgnoredLibraryKeepRules.isIgnored(sdk, setOf("com.example:sdk"), false)).isTrue()
    }

    @Test
    fun `module matches by full coordinate including version`() {
        assertThat(IgnoredLibraryKeepRules.isIgnored(sdk, setOf("com.example:sdk:1.2.3"), false)).isTrue()
    }

    @Test
    fun `different version or module does not match`() {
        assertThat(IgnoredLibraryKeepRules.isIgnored(sdk, setOf("com.example:sdk:9.9.9"), false)).isFalse()
        assertThat(IgnoredLibraryKeepRules.isIgnored(sdk, setOf("com.example:sdk-core"), false)).isFalse()
        assertThat(IgnoredLibraryKeepRules.isIgnored(sdk, setOf("com.example"), false)).isFalse()
    }

    @Test
    fun `ignore all external drops every module but keeps project rules`() {
        assertThat(IgnoredLibraryKeepRules.isIgnored(sdk, emptySet(), true)).isTrue()
        assertThat(IgnoredLibraryKeepRules.isIgnored(project, emptySet(), true)).isFalse()
    }

    @Test
    fun `project dependency is never ignored even when named`() {
        assertThat(IgnoredLibraryKeepRules.isIgnored(project, setOf(":lib", project.displayName), false)).isFalse()
    }

    @Test
    fun `no ignore config keeps everything`() {
        assertThat(IgnoredLibraryKeepRules.isIgnored(sdk, emptySet(), false)).isFalse()
        assertThat(IgnoredLibraryKeepRules.isIgnored(project, emptySet(), false)).isFalse()
    }

    private fun module(group: String, name: String, version: String): ModuleComponentIdentifier =
        object : ModuleComponentIdentifier {
            override fun getGroup() = group
            override fun getModule() = name
            override fun getVersion() = version
            override fun getModuleIdentifier(): ModuleIdentifier = throw UnsupportedOperationException()
            override fun getDisplayName() = "$group:$name:$version"

            // Mirrors Gradle's DefaultModuleComponentIdentifier.toString().
            override fun toString() = displayName
        }

    // Any non-module identifier (project, included build, local file) behaves the same.
    private fun projectId(path: String): ComponentIdentifier =
        object : ComponentIdentifier {
            override fun getDisplayName() = "project $path"
            override fun toString() = displayName
        }
}
