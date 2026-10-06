package io.github.fornewid.gradle.plugins.proguardshield

import com.google.common.truth.Truth.assertThat
import io.github.fornewid.gradle.plugins.proguardshield.fixture.AndroidProject
import io.github.fornewid.gradle.plugins.proguardshield.fixture.Builder.build
import io.github.fornewid.gradle.plugins.proguardshield.fixture.Builder.buildAndFail
import io.github.fornewid.gradle.plugins.proguardshield.fixture.R8Oracle
import io.github.fornewid.gradle.plugins.proguardshield.fixture.R8TaskInputs
import org.junit.jupiter.api.Test

internal class ProGuardShieldPluginTest {

    companion object {
        private const val OPTIMIZATION_LIST = "proguardShield/releaseOptimizationBlockingRules.txt"
        private const val OPTIMIZATION_TREE = "proguardShield/releaseOptimizationBlockingRules.tree.txt"
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
            val result = buildAndFail(project, ":app:check", "--dry-run")
            assertThat(result.output).contains("could not resolve configuration")
            assertThat(result.output).contains("nonexistent")
            assertThat(result.output).contains("configuration(\"release\")")
            // `debug` has no minification, so suggesting it would only lead to the next error.
            assertThat(result.output).doesNotContain("configuration(\"debug\")")
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
            val result = buildAndFail(project, ":app:proguardShieldOptimization")
            assertThat(result.output).contains("does not have minification enabled")
            assertThat(result.output).contains("isMinifyEnabled")
        }
    }

    @Test
    fun `minify hint names the build type rather than the flavored variant`() {
        AndroidProject(
            minifyEnabled = false,
            pluginConfig = """
                android {
                    flavorDimensions "env"
                    productFlavors {
                        dev { dimension "env" }
                    }
                }
                proguardShield {
                    configuration("devRelease")
                }
            """.trimIndent(),
        ).use { project ->
            val result = buildAndFail(project, ":app:proguardShieldOptimization")
            assertThat(result.output).contains("android.buildTypes.release.isMinifyEnabled = true")
            assertThat(result.output).doesNotContain("android.buildTypes.devRelease")
        }
    }

    @Test
    fun `check runs the optimization guard by default`() {
        AndroidProject(pluginConfig = AndroidProject.MINIMAL_PLUGIN_CONFIG).use { project ->
            val scheduled = checkTasks(project)
            assertThat(scheduled).contains(":app:proguardShieldOptimizationRelease")
            assertThat(scheduled).doesNotContain(":app:minifyReleaseWithR8")
        }
    }

    @Test
    fun `optimization baseline task writes only its own file without running R8`() {
        AndroidProject().use { project ->
            val result = build(project, ":app:proguardShieldOptimizationBaseline")

            assertThat(result.task(":app:minifyReleaseWithR8")).isNull()
            assertThat(project.readOptimizationRules(OPTIMIZATION_LIST)).isEmpty()
            assertThat(project.baselineFileExists(OPTIMIZATION_TREE)).isFalse()
        }
    }

    @Test
    fun `optimization compiles neither the app nor its modules`() {
        AndroidProject(pluginConfig = AndroidProject.TREE_PLUGIN_CONFIG).use { project ->
            // Out of scope: a module's consumer rules only exist once it compiles (AGP merges in the generated ones).
            project.addLibraryModule(consumerRules = "-keep class ** { *; }")

            val tasks = build(project, ":app:proguardShieldOptimizationBaseline").tasks.map { it.path }
            assertThat(tasks).containsExactly(
                ":app:preBuild",
                ":app:extractProguardFiles",
                ":app:proguardShieldOptimizationBaselineRelease",
                ":app:proguardShieldOptimizationBaseline",
            )
            assertThat(project.readBaselineFile(OPTIMIZATION_TREE)).isEmpty()
        }
    }

    @Test
    fun `optimization lists the blocking rules R8 reads from the app and libraries`() {
        AndroidProject(
            proguardRules = AndroidProject.DEFAULT_PROGUARD_RULES + "\n-keepattributes *",
            pluginConfig = AndroidProject.MINIMAL_PLUGIN_CONFIG,
            dependencies = """
                implementation 'com.example:aar:1.0'
                implementation 'com.example:jar:1.0'
                implementation files('libs/local.aar')
            """.trimIndent(),
        ).use { project ->
            project.publishLocalAar("com.example", "aar", "1.0", "-keep class ** { *; }")
            project.publishLocalJar("com.example", "jar", "1.0", "-keepnames class **")
            project.writeAppLibsAar("local.aar", "-keepclasseswithmembers class * { *; }")
            // Read by R8 but out of the optimization mode's scope, so not listed.
            project.addLibraryModule(consumerRules = "-keepclassmembers class * { *; }")
            project.appendToAppBuildFile(
                "afterEvaluate { tasks.named('minifyReleaseWithR8').configure { proguardConfigurations.add('-dontoptimize') } }",
            )

            R8Oracle.assertOptimizationListMatchesR8(project, OPTIMIZATION_LIST)
            // Each source contributes one: AGP's string rules, the AAR, the app, the file AAR, the JAR.
            assertThat(project.readOptimizationRules(OPTIMIZATION_LIST)).isEqualTo(
                "-dontoptimize\n-keep class ** { *; }\n-keepattributes *\n-keepclasseswithmembers class * { *; }\n-keepnames class **\n",
            )
        }
    }

    @Test
    fun `optimization lists blocking rules and groups them by origin`() {
        AndroidProject(
            pluginConfig = AndroidProject.TREE_PLUGIN_CONFIG,
            proguardRules = AndroidProject.DEFAULT_PROGUARD_RULES + "\n-keepattributes *",
            dependencies = "implementation 'com.example:risky:1.0'",
        ).use { project ->
            project.publishLocalAar("com.example", "risky", "1.0", "-dontobfuscate\n-keep class ** { *; }")

            build(project, ":app:proguardShieldOptimizationBaseline")

            assertThat(project.readOptimizationRules(OPTIMIZATION_LIST))
                .isEqualTo("-dontobfuscate\n-keep class ** { *; }\n-keepattributes *\n")
            assertThat(project.readBaselineFile(OPTIMIZATION_TREE)).isEqualTo(
                "[:app]\n-keepattributes *\n\n[com.example:risky]\n-dontobfuscate\n-keep class ** { *; }\n",
            )
            assertThat(build(project, ":app:proguardShieldOptimization").output)
                .doesNotContain("optimization-blocking rules changed")
        }
    }

    @Test
    fun `optimization reads the rules AGP passes to R8 as strings`() {
        AndroidProject(pluginConfig = AndroidProject.TREE_PLUGIN_CONFIG).use { project ->
            // Stands in for AGP, which passes some rules this way (JaCoCo's keeps when testing with coverage).
            project.appendToAppBuildFile(
                "afterEvaluate { tasks.named('minifyReleaseWithR8').configure { proguardConfigurations.add('-dontoptimize') } }",
            )

            build(project, ":app:proguardShieldOptimizationBaseline")

            assertThat(project.readBaselineFile(OPTIMIZATION_TREE)).isEqualTo("[<agp>]\n-dontoptimize\n")
        }
    }

    @Test
    fun `optimization list starts with the AGP version and R8 mode properties`() {
        AndroidProject().use { project ->
            build(project, ":app:proguardShieldOptimizationBaseline")
            assertThat(project.readBaselineFile(OPTIMIZATION_LIST)).isEqualTo(
                "# agp=${project.agpVersion}\n# android.enableR8.fullMode=default\n" +
                    "# android.r8.strictFullModeForKeepRules=default\n# android.r8.globalOptionsInConsumerRules.disallowed=default\n",
            )

            val output = buildAndFail(project, ":app:proguardShieldOptimization", "-Pandroid.enableR8.fullMode=false").output
            assertThat(output).contains("- # android.enableR8.fullMode=default")
            assertThat(output).contains("+ # android.enableR8.fullMode=false")
        }
    }

    @Test
    fun `optimization failure shows only the rule diff`() {
        AndroidProject(
            pluginConfig = AndroidProject.MINIMAL_PLUGIN_CONFIG,
            dependencies = "implementation 'com.example:sdk:1.0'",
        ).use { project ->
            project.publishLocalAar("com.example", "sdk", "1.0", "-keep class com.example.sdk.Marker")
            project.publishLocalAar("com.example", "sdk", "2.0", "-keep class com.example.sdk.Marker\n-dontobfuscate")
            build(project, ":app:proguardShieldOptimizationBaseline")
            assertThat(project.readOptimizationRules(OPTIMIZATION_LIST)).isEmpty()

            project.replaceInAppBuildFile("com.example:sdk:1.0", "com.example:sdk:2.0")

            val result = buildAndFail(project, ":app:proguardShieldOptimization")
            assertThat(result.output).contains("optimization-blocking rules changed in :app (release)")
            assertThat(result.output).contains("+ -dontobfuscate")
            assertThat(result.output).doesNotContain("from com.example:sdk")
            assertThat(result.output).contains("./gradlew :app:proguardShieldOptimizationBaselineRelease")
        }
    }

    @Test
    fun `optimization lists library rules that reach code outside the library`() {
        AndroidProject(
            pluginConfig = AndroidProject.TREE_PLUGIN_CONFIG,
            dependencies = "implementation 'com.vendor:sdk:1.0'\nimplementation 'com.vendor:core:1.0'\n" +
                "implementation 'io.github.fornewid:core:1.0'\nimplementation files('libs/vendor-file.aar')\n" +
                "implementation 'com.jarvendor:util:1.0'\nimplementation 'com.thirdparty:classes:1.0'",
        ).use { project ->
            // Other libraries' classes for the rules below to match. A rule that matches no class is not listed.
            project.publishLocalJar(
                "com.thirdparty", "classes", "1.0", "",
                classes = listOf("com.google.gson.Gson", "okhttp3.OkHttpClient", "okio.Buffer"),
            )
            project.publishLocalAar(
                "com.vendor", "sdk", "1.0",
                """
                -keep class com.google.gson.** { *; }
                -keep class com.absent.** { *; }
                -ignorewarnings
                -keep class com.vendor.sdk.** { *; }
                -keep class * extends com.vendor.sdk.Api { *; }
                -keep class com.vendor.core.** { *; }
                """.trimIndent(),
                classes = listOf("com.vendor.sdk.Api"),
            )
            project.publishLocalAar("com.vendor", "core", "1.0", "", classes = listOf("com.vendor.core.Util"))
            // Same group as the app's namespace (io.github.fornewid.test): the rule reaches the app's code.
            project.publishLocalAar(
                "io.github.fornewid", "core", "1.0",
                "-keep class io.github.fornewid.** { *; }",
                classes = listOf("io.github.fornewid.core.Util"),
            )
            // A file dependency: checked like an external library, labeled by its file name.
            project.writeAppLibsAar(
                "vendor-file.aar",
                "-keep class okhttp3.** { *; }\n-keep class com.vendor.file.** { *; }",
                classes = listOf("com.vendor.file.Util"),
            )
            // A JAR library: its own packages come from the same package-list transform as an AAR's.
            project.publishLocalJar(
                "com.jarvendor", "util", "1.0",
                "-keep class com.jarvendor.util.** { *; }\n-keep class okio.** { *; }",
                classes = listOf("com.jarvendor.util.Util"),
            )

            build(project, ":app:proguardShieldOptimizationBaseline")

            // -ignorewarnings is an app-wide option, but it doesn't reduce what R8 does.
            assertThat(project.readOptimizationRules(OPTIMIZATION_LIST)).isEqualTo(
                "-keep class com.google.gson.** { *; }\n-keep class io.github.fornewid.** { *; }\n" +
                    "-keep class okhttp3.** { *; }\n-keep class okio.** { *; }\n",
            )
            assertThat(project.readBaselineFile(OPTIMIZATION_TREE)).isEqualTo(
                "[com.jarvendor:util]\n-keep class okio.** { *; }\n\n" +
                    "[com.vendor:sdk]\n-keep class com.google.gson.** { *; }\n\n" +
                    "[io.github.fornewid:core]\n-keep class io.github.fornewid.** { *; }\n\n" +
                    "[vendor-file.aar]\n-keep class okhttp3.** { *; }\n",
            )
        }
    }

    @Test
    fun `optimization failure with tree shows the changed rules under the library`() {
        AndroidProject(
            pluginConfig = AndroidProject.TREE_PLUGIN_CONFIG,
            dependencies = "implementation 'com.vendor:sdk:1.0'\nimplementation 'com.thirdparty:gson:1.0'",
        ).use { project ->
            project.publishLocalJar("com.thirdparty", "gson", "1.0", "", classes = listOf("com.google.gson.Gson"))
            val own = "-keep class com.vendor.sdk.** { *; }"
            project.publishLocalAar("com.vendor", "sdk", "1.0", own, classes = listOf("com.vendor.sdk.Api"))
            project.publishLocalAar(
                "com.vendor", "sdk", "2.0",
                "$own\n-keep class com.google.gson.** {\n    *;\n}",
                classes = listOf("com.vendor.sdk.Api"),
            )
            build(project, ":app:proguardShieldOptimizationBaseline")
            assertThat(project.readOptimizationRules(OPTIMIZATION_LIST)).isEmpty()

            project.replaceInAppBuildFile("com.vendor:sdk:1.0", "com.vendor:sdk:2.0")

            val output = buildAndFail(project, ":app:proguardShieldOptimization").output
            assertThat(output).contains("[com.vendor:sdk]")
            assertThat(output).contains("+ -keep class com.google.gson.** {")
            assertThat(output).contains("+ *;")
            assertThat(output).contains("+ }")
            assertThat(output).doesNotContain("Origins changed")
            assertThat(output).doesNotContain("from com.vendor")
        }
    }

    @Test
    fun `optimization ignores libraries excluded via the keepRules DSL`() {
        AndroidProject(
            pluginConfig = AndroidProject.MINIMAL_PLUGIN_CONFIG,
            releaseExtra = """
                optimization {
                    keepRules {
                        ignoreExternalDependencies 'com.example:ignored'
                    }
                }
            """.trimIndent(),
            dependencies = "implementation 'com.example:ignored:1.0'",
        ).use { project ->
            project.publishLocalAar("com.example", "ignored", "1.0", "-dontobfuscate")

            R8Oracle.assertOptimizationListMatchesR8(project, OPTIMIZATION_LIST)

            assertThat(project.readOptimizationRules(OPTIMIZATION_LIST)).isEmpty()
        }
    }

    @Test
    fun `turning on tree later writes the tree file and keeps passing`() {
        AndroidProject(
            pluginConfig = AndroidProject.MINIMAL_PLUGIN_CONFIG,
            proguardRules = AndroidProject.DEFAULT_PROGUARD_RULES + "\n-keepattributes *",
        ).use { project ->
            build(project, ":app:proguardShieldOptimizationBaseline")

            project.replaceInAppBuildFile(AndroidProject.MINIMAL_PLUGIN_CONFIG, AndroidProject.TREE_PLUGIN_CONFIG)

            val result = build(project, ":app:proguardShieldOptimization")
            assertThat(result.output).contains("ProGuard Shield baseline created")
            assertThat(project.readBaselineFile(OPTIMIZATION_TREE)).isEqualTo("[:app]\n-keepattributes *\n")
        }
    }

    @Test
    fun `optimization mode reuses the configuration cache`() {
        AndroidProject(
            pluginConfig = AndroidProject.TREE_PLUGIN_CONFIG,
            dependencies = "implementation 'com.example:sdk:1.0'",
        ).use { project ->
            project.publishLocalAar("com.example", "sdk", "1.0", "-dontobfuscate")

            build(project, ":app:proguardShieldOptimizationBaseline", "--configuration-cache")
            build(project, ":app:proguardShieldOptimization", "--configuration-cache")
            val result = build(project, ":app:proguardShieldOptimization", "--configuration-cache")

            assertThat(result.output).contains("Reusing configuration cache.")
            assertThat(project.readBaselineFile(OPTIMIZATION_TREE)).isEqualTo("[com.example:sdk]\n-dontobfuscate\n")
        }
    }

    @Test
    fun `every file input of the R8 task is classified`() {
        AndroidProject().use { R8TaskInputs.assertAllClassified(it) }
    }

    /**
     * Tasks `check` would run. BuildResult.task() returns null for dry-run
     * skipped tasks, so parse the printed task names instead. --console=plain
     * pins the output format.
     */
    private fun checkTasks(project: AndroidProject): Set<String> {
        val output = build(project, ":app:check", "--dry-run", "--console=plain").output
        val taskLine = Regex("^:app:(\\S+)")
        return output.lines()
            .mapNotNull { taskLine.find(it)?.groupValues?.get(1) }
            .map { ":app:$it" }
            .toSet()
    }
}
