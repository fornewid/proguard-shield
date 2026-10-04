package io.github.fornewid.gradle.plugins.proguardshield

import com.google.common.truth.Truth.assertThat
import io.github.fornewid.gradle.plugins.proguardshield.fixture.AndroidProject
import io.github.fornewid.gradle.plugins.proguardshield.fixture.Builder.build
import io.github.fornewid.gradle.plugins.proguardshield.fixture.Builder.buildAndFail
import org.gradle.api.JavaVersion
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Cross-version smoke tests against AGP 9.x to guard against regressions
 * introduced by AGP API or R8 configuration changes. The companion test class
 * [ProGuardShieldPluginTest] still exercises full coverage on the AGP version
 * selected by `-PagpVersion` (8.x matrix in CI).
 */
internal class ProGuardShieldPluginAgp9Test {

    @BeforeEach
    fun requireJava17() {
        // AGP 9.x and Gradle 9.x require JDK 17+; skip on older JVMs to avoid
        // a confusing TestKit failure when run locally on JDK 11.
        assumeTrue(JavaVersion.current().isCompatibleWith(JavaVersion.VERSION_17))
    }

    private fun newProject(
        proguardRules: String = AndroidProject.DEFAULT_PROGUARD_RULES,
    ) = AndroidProject(
        proguardRules = proguardRules,
        // Resource shrinking on: AGP 9 then emits no AAPT2 keep rules, so the
        // known fast-path gap (#30) does not mask other AGP 9 regressions.
        shrinkResources = true,
        agpVersion = AGP_VERSION,
        gradleVersion = GRADLE_VERSION,
    )

    @Test
    fun `accurate baseline task writes the accurate baseline on AGP 9`() {
        newProject().use { project ->
            val result = build(project, ":app:proguardShieldBaselineRelease")

            assertThat(result.output).contains("ProGuard Shield baseline created")
            assertThat(project.readBaselineFile(ACCURATE_BASELINE)).contains("-keepattributes")
        }
    }

    @Test
    fun `fast baseline task writes the fast baseline on AGP 9`() {
        newProject().use { project ->
            val result = build(project, ":app:proguardShieldFastBaselineRelease")

            assertThat(result.output).contains("ProGuard Shield baseline created")
            assertThat(project.readBaselineFile(FAST_BASELINE)).contains("-keepattributes")
            assertThat(result.task(":app:minifyReleaseWithR8")).isNull()
        }
    }

    @Test
    fun `guard passes when rules have not changed on AGP 9`() {
        newProject().use { project ->
            build(project, ":app:proguardShieldBaseline")

            val result = build(project, ":app:proguardShield")
            assertThat(result.output).doesNotContain("rules changed")
        }
    }

    @Test
    fun `fast guard fails when a new rule is added on AGP 9`() {
        newProject().use { project ->
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
    fun `verifyParity passes on AGP 9`() {
        newProject().use { project ->
            val result = build(project, ":app:proguardShieldVerifyParity")
            assertThat(result.output).contains("parity holds")
        }
    }

    private companion object {
        const val AGP_VERSION = "9.4.1"

        // AGP 9.4.1 requires Gradle 9.6.0+.
        const val GRADLE_VERSION = "9.6.1"

        const val ACCURATE_BASELINE = "proguardShield/releaseRules.txt"
        const val FAST_BASELINE = "proguardShield/releaseFastRules.txt"
    }
}
