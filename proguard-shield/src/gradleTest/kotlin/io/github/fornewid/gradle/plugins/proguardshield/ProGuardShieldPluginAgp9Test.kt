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
        pluginConfig: String = AndroidProject.DEFAULT_PLUGIN_CONFIG,
        releaseExtra: String = "",
        dependencies: String = "",
    ) = AndroidProject(
        proguardRules = proguardRules,
        pluginConfig = pluginConfig,
        releaseExtra = releaseExtra,
        dependencies = dependencies,
        agpVersion = AGP_VERSION,
        gradleVersion = GRADLE_VERSION,
    )

    @Test
    fun `full baseline task writes the full baseline on AGP 9`() {
        newProject().use { project ->
            val result = build(project, ":app:proguardShieldFullBaselineRelease")

            assertThat(result.output).contains("ProGuard Shield baseline created")
            assertThat(project.readBaselineFile(FULL_BASELINE)).contains("-keepattributes")
        }
    }

    @Test
    fun `fullFast baseline task writes the fullFast baseline on AGP 9`() {
        newProject().use { project ->
            val result = build(project, ":app:proguardShieldFullFastBaselineRelease")

            assertThat(result.output).contains("ProGuard Shield baseline created")
            assertThat(project.readBaselineFile(FULL_FAST_BASELINE)).contains("-keepattributes")
            assertThat(result.task(":app:minifyReleaseWithR8")).isNull()
        }
    }

    @Test
    fun `guard passes when rules have not changed on AGP 9`() {
        newProject().use { project ->
            build(project, ":app:proguardShieldFullBaseline", ":app:proguardShieldFullFastBaseline")

            val result = build(project, ":app:proguardShieldFull")
            assertThat(result.output).doesNotContain("rules changed")
        }
    }

    @Test
    fun `fullFast guard fails when a new rule is added on AGP 9`() {
        newProject().use { project ->
            build(project, ":app:proguardShieldFullBaseline", ":app:proguardShieldFullFastBaseline")

            project.updateProguardRules(
                AndroidProject.DEFAULT_PROGUARD_RULES + "\n-keep class com.example.Added { *; }",
            )

            val result = buildAndFail(project, ":app:proguardShieldFullFastRelease")
            assertThat(result.output).contains("rules changed")
            assertThat(result.output).contains("-keep class com.example.Added")
        }
    }

    @Test
    fun `verifyParity passes on AGP 9`() {
        newProject().use { project ->
            val result = build(project, ":app:proguardShieldVerifyParity")
            assertThat(result.output).contains("parity holds")
            // Resource shrinking is off, so AAPT2 keeps MainActivity through AGP 9.3+'s own input (#30).
            assertThat(project.readBaselineFile(FULL_FAST_BASELINE)).contains("MainActivity")
        }
    }

    @Test
    fun `dynamic feature rules reach fullFast and optimization on AGP 9`() {
        // AGP 9.3+ passes them through their own input (#55).
        newProject().use { project ->
            project.addDynamicFeature("-keepnames class **")

            // Like `check`: the optimization task alone, without R8.
            val optimization = build(project, ":app:proguardShieldOptimizationBaseline")
            assertThat(optimization.task(":app:minifyReleaseWithR8")).isNull()
            assertThat(project.readOptimizationRules(OPTIMIZATION_LIST)).isEqualTo("-keepnames class **\n")

            assertThat(build(project, ":app:proguardShieldVerifyParity").output).contains("parity holds")
        }
    }

    @Test
    fun `fullFast drops consumer rules ignored via ignoreFrom on AGP 9`() {
        newProject(
            releaseExtra = """
                optimization {
                    keepRules {
                        ignoreFrom 'com.example:ignored'
                    }
                }
            """.trimIndent(),
            dependencies = """
                implementation 'com.example:ignored:1.0'
                implementation 'com.example:kept:1.0'
            """.trimIndent(),
        ).use { project ->
            project.publishLocalAar("com.example", "ignored", "1.0", "-keep class com.example.ignored.Marker")
            project.publishLocalAar("com.example", "kept", "1.0", "-keep class com.example.kept.Marker")

            val result = build(project, ":app:proguardShieldVerifyParity")

            assertThat(result.output).contains("parity holds")
            val fast = project.readBaselineFile(FULL_FAST_BASELINE)!!
            assertThat(fast).contains("com.example.kept.Marker")
            assertThat(fast).doesNotContain("com.example.ignored.Marker")
        }
    }

    @Test
    fun `optimization records app blocking rules on AGP 9`() {
        newProject(proguardRules = AndroidProject.DEFAULT_PROGUARD_RULES + "\n-keepattributes *").use { project ->
            build(project, ":app:proguardShieldOptimizationBaseline")

            assertThat(project.readOptimizationRules(OPTIMIZATION_LIST)).isEqualTo("-keepattributes *\n")
            assertThat(build(project, ":app:proguardShieldOptimization").output)
                .doesNotContain("optimization-blocking rules changed")
        }
    }

    @Test
    fun `optimization names the library that adds blocking rules on AGP 9`() {
        newProject(
            pluginConfig = AndroidProject.TREE_PLUGIN_CONFIG,
            dependencies = "implementation 'com.example:risky:1.0'",
        ).use { project ->
            project.publishLocalAar("com.example", "risky", "1.0", "-dontobfuscate\n-keep class ** { *; }")

            build(project, ":app:proguardShieldOptimizationBaseline")

            // The library's -dontobfuscate still reaches R8 on AGP 9.4 (it is in R8's own
            // -printconfiguration output), so it is reported along with the keep.
            assertThat(project.readBaselineFile(OPTIMIZATION_TREE))
                .isEqualTo("[com.example:risky]\n-dontobfuscate\n-keep class ** { *; }\n")
        }
    }

    @Test
    fun `optimization lists a library keep of another package on AGP 9`() {
        newProject(
            pluginConfig = AndroidProject.MINIMAL_PLUGIN_CONFIG,
            dependencies = "implementation 'com.vendor:sdk:1.0'",
        ).use { project ->
            project.publishLocalAar(
                "com.vendor", "sdk", "1.0",
                "-keep class com.google.gson.** { *; }\n-keep class com.vendor.sdk.** { *; }",
                classes = listOf("com.vendor.sdk.Api"),
            )

            build(project, ":app:proguardShieldOptimizationBaseline")

            assertThat(project.readOptimizationRules(OPTIMIZATION_LIST)).isEqualTo("-keep class com.google.gson.** { *; }\n")
        }
    }

    private companion object {
        const val AGP_VERSION = "9.4.1"

        // AGP 9.4.1 requires Gradle 9.6.0+.
        const val GRADLE_VERSION = "9.6.1"

        const val FULL_BASELINE = "proguardShield/releaseFullRules.txt"
        const val FULL_FAST_BASELINE = "proguardShield/releaseFullFastRules.txt"
        const val OPTIMIZATION_LIST = "proguardShield/releaseOptimizationBlockingRules.txt"
        const val OPTIMIZATION_TREE = "proguardShield/releaseOptimizationBlockingRules.tree.txt"
    }
}
