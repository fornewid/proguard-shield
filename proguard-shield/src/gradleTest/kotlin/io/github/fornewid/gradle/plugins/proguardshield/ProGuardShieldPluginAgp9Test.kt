package io.github.fornewid.gradle.plugins.proguardshield

import com.google.common.truth.Truth.assertThat
import io.github.fornewid.gradle.plugins.proguardshield.fixture.AndroidProject
import io.github.fornewid.gradle.plugins.proguardshield.fixture.Builder.build
import io.github.fornewid.gradle.plugins.proguardshield.fixture.R8Oracle
import io.github.fornewid.gradle.plugins.proguardshield.fixture.R8TaskInputs
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
        pluginConfig: String = AndroidProject.MINIMAL_PLUGIN_CONFIG,
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
    fun `optimization matches R8 for a library's -dontobfuscate on AGP 9`() {
        newProject(dependencies = "implementation 'com.example:global:1.0'").use { project ->
            // AGP 9.5+ removes it before R8 reads it. Either way, the list must match what R8 reads.
            project.publishLocalAar("com.example", "global", "1.0", "-dontobfuscate")

            R8Oracle.assertOptimizationListMatchesR8(project, OPTIMIZATION_LIST)
        }
    }

    @Test
    fun `optimization leaves out dynamic feature rules on AGP 9`() {
        // AGP 9.3+ passes them through their own input (#55). They are a module's rules, which only exist once it compiles.
        newProject().use { project ->
            project.addDynamicFeature("-keepnames class **")

            build(project, ":app:proguardShieldOptimizationBaseline")
            assertThat(project.readOptimizationRules(OPTIMIZATION_LIST)).isEmpty()
        }
    }

    @Test
    fun `optimization ignores libraries excluded via ignoreFrom on AGP 9`() {
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
            project.publishLocalAar("com.example", "ignored", "1.0", "-keepattributes *")
            project.publishLocalAar("com.example", "kept", "1.0", "-keepnames class **")

            R8Oracle.assertOptimizationListMatchesR8(project, OPTIMIZATION_LIST)

            assertThat(project.readOptimizationRules(OPTIMIZATION_LIST)).isEqualTo("-keepnames class **\n")
        }
    }

    @Test
    fun `optimization lists the blocking rules R8 reads from the app and libraries on AGP 9`() {
        newProject(
            proguardRules = AndroidProject.DEFAULT_PROGUARD_RULES + "\n-keepattributes *",
            pluginConfig = AndroidProject.MINIMAL_PLUGIN_CONFIG,
            dependencies = "implementation 'com.example:aar:1.0'",
        ).use { project ->
            // Not a -keep of every class: R8 would then keep all of the Kotlin stdlib AGP 9 adds, which is slow.
            project.publishLocalAar("com.example", "aar", "1.0", "-keepclassmembers class * { *; }")
            project.dir.resolve("app/src/main/keepRules").apply { mkdirs() }.resolve("app.keep").writeText("-keepnames class **")
            project.appendToAppBuildFile(
                "afterEvaluate { tasks.named('minifyReleaseWithR8').configure { proguardConfigurations.add('-dontoptimize') } }",
            )

            R8Oracle.assertOptimizationListMatchesR8(project, OPTIMIZATION_LIST)
            assertThat(project.readOptimizationRules(OPTIMIZATION_LIST))
                .isEqualTo("-dontoptimize\n-keepattributes *\n-keepclassmembers class * { *; }\n-keepnames class **\n")
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
            // Not -dontobfuscate: AGP 9.5+ removes such app-wide options from consumer rules before R8 reads them.
            project.publishLocalAar("com.example", "risky", "1.0", "-keepattributes *\n-keep class ** { *; }")

            build(project, ":app:proguardShieldOptimizationBaseline")

            assertThat(project.readBaselineFile(OPTIMIZATION_TREE))
                .isEqualTo("[com.example:risky]\n-keep class ** { *; }\n-keepattributes *\n")
        }
    }

    @Test
    fun `optimization lists a library keep of another package on AGP 9`() {
        newProject(
            pluginConfig = AndroidProject.MINIMAL_PLUGIN_CONFIG,
            dependencies = "implementation 'com.vendor:sdk:1.0'\nimplementation 'com.thirdparty:gson:1.0'",
        ).use { project ->
            project.publishLocalJar("com.thirdparty", "gson", "1.0", "", classes = listOf("com.google.gson.Gson"))
            project.publishLocalAar(
                "com.vendor", "sdk", "1.0",
                "-keep class com.google.gson.** { *; }\n-keep class com.vendor.sdk.** { *; }",
                classes = listOf("com.vendor.sdk.Api"),
            )

            build(project, ":app:proguardShieldOptimizationBaseline")

            assertThat(project.readOptimizationRules(OPTIMIZATION_LIST)).isEqualTo("-keep class com.google.gson.** { *; }\n")
        }
    }

    @Test
    fun `every file input of the R8 task is classified on AGP 9`() {
        newProject().use { R8TaskInputs.assertAllClassified(it) }
    }

    private companion object {
        // The newest-AGP workflow sets both with -Pagp9Version and -Pagp9GradleVersion.
        val AGP_VERSION: String = System.getProperty("agp9Version") ?: "9.4.1"

        // AGP 9.4.1 requires Gradle 9.6.0+.
        val GRADLE_VERSION: String = System.getProperty("agp9GradleVersion") ?: "9.6.1"

        const val OPTIMIZATION_LIST = "proguardShield/releaseOptimizationBlockingRules.txt"
        const val OPTIMIZATION_TREE = "proguardShield/releaseOptimizationBlockingRules.tree.txt"
    }
}
