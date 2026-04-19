package io.github.fornewid.gradle.plugins.proguardshield

import com.google.common.truth.Truth.assertThat
import io.github.fornewid.gradle.plugins.proguardshield.fixture.AndroidProject
import io.github.fornewid.gradle.plugins.proguardshield.fixture.Builder.build
import io.github.fornewid.gradle.plugins.proguardshield.fixture.Builder.buildAndFail
import org.junit.jupiter.api.Test

internal class ProGuardShieldPluginTest {

    companion object {
        private const val ACCURATE_BASELINE = "proguardShield/releaseRules.txt"
        private const val FAST_BASELINE = "proguardShield/releaseFastRules.txt"
    }

    @Test
    fun `accurate baseline task writes the accurate baseline only`() {
        AndroidProject().use { project ->
            val result = build(project, ":app:proguardShieldBaselineRelease")

            assertThat(result.output).contains("ProGuard Shield baseline created")
            assertThat(project.baselineFileExists(ACCURATE_BASELINE)).isTrue()
            assertThat(project.baselineFileExists(FAST_BASELINE)).isFalse()

            val baseline = project.readBaselineFile(ACCURATE_BASELINE)!!
            assertThat(baseline).contains("-keepattributes")
        }
    }

    @Test
    fun `fast baseline task writes the fast baseline only`() {
        AndroidProject().use { project ->
            val result = build(project, ":app:proguardShieldFastBaselineRelease")

            assertThat(result.output).contains("ProGuard Shield baseline created")
            assertThat(project.baselineFileExists(FAST_BASELINE)).isTrue()
            assertThat(project.baselineFileExists(ACCURATE_BASELINE)).isFalse()
        }
    }

    @Test
    fun `combined baseline aggregate writes both baseline files`() {
        AndroidProject().use { project ->
            build(project, ":app:proguardShieldBaseline")

            assertThat(project.baselineFileExists(ACCURATE_BASELINE)).isTrue()
            assertThat(project.baselineFileExists(FAST_BASELINE)).isTrue()
        }
    }

    @Test
    fun `accurate and fast baselines are bit-identical`() {
        AndroidProject().use { project ->
            build(project, ":app:proguardShieldBaseline")

            val accurate = project.readBaselineFile(ACCURATE_BASELINE)!!
            val fast = project.readBaselineFile(FAST_BASELINE)!!
            assertThat(fast).isEqualTo(accurate)
        }
    }

    @Test
    fun `fast baseline task does not run the R8 task`() {
        AndroidProject().use { project ->
            val result = build(project, ":app:proguardShieldFastBaselineRelease")
            assertThat(result.output).doesNotContain("minifyReleaseWithR8")
        }
    }

    @Test
    fun `accurate baseline task runs the R8 task`() {
        AndroidProject().use { project ->
            val result = build(project, ":app:proguardShieldBaselineRelease")
            assertThat(result.output).contains("minifyReleaseWithR8")
        }
    }

    @Test
    fun `combined guard aggregate runs both per-variant guard tasks`() {
        AndroidProject().use { project ->
            build(project, ":app:proguardShieldBaseline")

            val result = build(project, ":app:proguardShield")
            assertThat(result.output).contains("proguardShieldRelease")
            assertThat(result.output).contains("proguardShieldFastRelease")
        }
    }

    @Test
    fun `guard passes when rules have not changed`() {
        AndroidProject().use { project ->
            build(project, ":app:proguardShieldBaseline")

            val result = build(project, ":app:proguardShield")
            assertThat(result.output).doesNotContain("rules changed")
        }
    }

    @Test
    fun `accurate guard fails when a new rule is added`() {
        AndroidProject().use { project ->
            build(project, ":app:proguardShieldBaseline")

            project.updateProguardRules(
                AndroidProject.DEFAULT_PROGUARD_RULES + "\n-keep class com.example.Added { *; }",
            )

            val result = buildAndFail(project, ":app:proguardShieldRelease")
            assertThat(result.output).contains("rules changed")
            assertThat(result.output).contains("-keep class com.example.Added")
        }
    }

    @Test
    fun `fast guard fails when a new rule is added`() {
        AndroidProject().use { project ->
            build(project, ":app:proguardShieldBaseline")

            project.updateProguardRules(
                AndroidProject.DEFAULT_PROGUARD_RULES + "\n-keep class com.example.Added { *; }",
            )

            val result = buildAndFail(project, ":app:proguardShieldFastRelease")
            assertThat(result.output).contains("rules changed")
            assertThat(result.output).contains("-keep class com.example.Added")
        }
    }

    @Test
    fun `guard fails when an existing rule is removed`() {
        AndroidProject().use { project ->
            build(project, ":app:proguardShieldBaseline")

            project.updateProguardRules("# all rules removed")

            val result = buildAndFail(project, ":app:proguardShieldRelease")
            assertThat(result.output).contains("rules changed")
            assertThat(result.output).contains("-keepattributes")
        }
    }

    @Test
    fun `rebaseline overwrites the stored baselines`() {
        AndroidProject().use { project ->
            build(project, ":app:proguardShieldBaseline")
            val before = project.readBaselineFile(ACCURATE_BASELINE)!!

            project.updateProguardRules(
                AndroidProject.DEFAULT_PROGUARD_RULES + "\n-keep class com.example.Rebaseline { *; }",
            )
            build(project, ":app:proguardShieldBaseline")
            val after = project.readBaselineFile(ACCURATE_BASELINE)!!

            assertThat(after).isNotEqualTo(before)
            assertThat(after).contains("-keep class com.example.Rebaseline")

            // Fast baseline tracks the same change.
            assertThat(project.readBaselineFile(FAST_BASELINE)).isEqualTo(after)
        }
    }

    @Test
    fun `unknown configuration name fails with diagnostic listing real variants`() {
        AndroidProject(
            pluginConfig = """
                proguardShield {
                    configuration("nonexistent")
                }
            """.trimIndent(),
        ).use { project ->
            val result = buildAndFail(project, ":app:proguardShield")
            assertThat(result.output).contains("could not resolve configuration")
            assertThat(result.output).contains("nonexistent")
            assertThat(result.output).contains("release")
        }
    }

    @Test
    fun `variant without minify enabled fails with helpful error`() {
        AndroidProject(
            minifyEnabled = false,
            pluginConfig = """
                proguardShield {
                    configuration("release")
                }
            """.trimIndent(),
        ).use { project ->
            val result = buildAndFail(project, ":app:proguardShield")
            assertThat(result.output).contains("does not have minification enabled")
            assertThat(result.output).contains("isMinifyEnabled")
        }
    }
}
