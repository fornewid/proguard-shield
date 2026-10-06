package io.github.fornewid.gradle.plugins.proguardshield

import com.google.common.truth.Truth.assertThat
import io.github.fornewid.gradle.plugins.proguardshield.fixture.AndroidProject
import io.github.fornewid.gradle.plugins.proguardshield.fixture.Builder.build
import io.github.fornewid.gradle.plugins.proguardshield.fixture.Builder.buildAndFail
import io.github.fornewid.gradle.plugins.proguardshield.fixture.R8Oracle
import io.github.fornewid.gradle.plugins.proguardshield.fixture.R8TaskInputs
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test

internal class ProGuardShieldPluginTest {

    companion object {
        private const val FULL_BASELINE = "proguardShield/releaseFullRules.txt"
        private const val FULL_FAST_BASELINE = "proguardShield/releaseFullFastRules.txt"
        private const val FULL_FAST_BASELINE_NAME = "releaseFullFastRules.txt"
        private const val OPTIMIZATION_LIST = "proguardShield/releaseOptimizationBlockingRules.txt"
        private const val OPTIMIZATION_TREE = "proguardShield/releaseOptimizationBlockingRules.tree.txt"
    }

    @Test
    fun `full baseline task writes the full baseline only`() {
        AndroidProject().use { project ->
            val result = build(project, ":app:proguardShieldFullBaselineRelease")

            assertThat(result.output).contains("ProGuard Shield baseline created")
            assertThat(project.baselineFileExists(FULL_BASELINE)).isTrue()
            assertThat(project.baselineFileExists(FULL_FAST_BASELINE)).isFalse()

            val baseline = project.readBaselineFile(FULL_BASELINE)!!
            assertThat(baseline).contains("-keepattributes")
        }
    }

    @Test
    fun `fullFast baseline task writes the fullFast baseline only`() {
        AndroidProject().use { project ->
            val result = build(project, ":app:proguardShieldFullFastBaselineRelease")

            assertThat(result.output).contains("ProGuard Shield baseline created")
            assertThat(project.baselineFileExists(FULL_FAST_BASELINE)).isTrue()
            assertThat(project.baselineFileExists(FULL_BASELINE)).isFalse()
        }
    }

    @Test
    fun `full and fullFast baselines are bit-identical`() {
        AndroidProject().use { project ->
            build(project, ":app:proguardShieldFullBaseline", ":app:proguardShieldFullFastBaseline")

            val accurate = project.readBaselineFile(FULL_BASELINE)!!
            val fast = project.readBaselineFile(FULL_FAST_BASELINE)!!
            assertThat(fast).isEqualTo(accurate)
        }
    }

    @Test
    fun `fullFast baseline task does not run the R8 task`() {
        AndroidProject().use { project ->
            val result = build(project, ":app:proguardShieldFullFastBaselineRelease")
            assertThat(result.task(":app:minifyReleaseWithR8")).isNull()
        }
    }

    @Test
    fun `full baseline task runs the R8 task`() {
        AndroidProject().use { project ->
            val result = build(project, ":app:proguardShieldFullBaselineRelease")
            assertThat(result.task(":app:minifyReleaseWithR8")).isNotNull()
        }
    }

    @Test
    fun `full mode shares R8's build cache across checkouts`() {
        // #31
        AndroidProject().use { first ->
            AndroidProject().use { second ->
                build(first, ":app:proguardShieldFullBaselineRelease", "--build-cache")
                val result = build(second, ":app:proguardShieldFullBaselineRelease", "--build-cache")

                assertThat(result.task(":app:minifyReleaseWithR8")?.outcome).isEqualTo(TaskOutcome.FROM_CACHE)
            }
        }
    }

    @Test
    fun `full baseline explains when another -printconfiguration takes precedence`() {
        AndroidProject(dependencies = "implementation 'com.vendor:sdk:1.0'").use { project ->
            project.publishLocalAar("com.vendor", "sdk", "1.0", "")
            project.publishLocalAar("com.vendor", "sdk", "2.0", "-printconfiguration vendor-config.txt")
            build(project, ":app:proguardShieldFullBaselineRelease")
            // R8 runs again and writes the library's file instead: the earlier merged-rules.txt must not be read.
            project.replaceInAppBuildFile("com.vendor:sdk:1.0", "com.vendor:sdk:2.0")

            val output = buildAndFail(project, ":app:proguardShieldFullBaselineRelease").output

            assertThat(output).contains("another -printconfiguration in the rules R8 reads took precedence")
        }
    }

    @Test
    fun `full guard aggregate runs only the full per-variant task`() {
        AndroidProject().use { project ->
            build(project, ":app:proguardShieldFullBaseline", ":app:proguardShieldFullFastBaseline")

            val result = build(project, ":app:proguardShieldFull")
            assertThat(result.task(":app:proguardShieldFullRelease")).isNotNull()
            assertThat(result.task(":app:proguardShieldFullFastRelease")).isNull()
        }
    }

    @Test
    fun `check runs fullFast but not full`() {
        AndroidProject().use { project ->
            build(project, ":app:proguardShieldFullBaseline", ":app:proguardShieldFullFastBaseline")

            // --dry-run inspects the task graph without executing tasks, so
            // we can confirm what `check` would trigger without paying the
            // lint / unit-test cost the throwaway fixture isn't set up for.
            val scheduledTasks = checkTasks(project)
            assertThat(scheduledTasks).contains(":app:proguardShieldFullFastRelease")
            // Full stays out of `check` so CI does not pay the R8 cost on
            // every build.
            assertThat(scheduledTasks).doesNotContain(":app:proguardShieldFullRelease")
            assertThat(scheduledTasks).doesNotContain(":app:minifyReleaseWithR8")
        }
    }

    @Test
    fun `verifyParity passes when both baselines are bit-identical`() {
        AndroidProject().use { project ->
            val result = build(project, ":app:proguardShieldVerifyParity")
            assertThat(result.output).contains("parity holds")
            // Both baseline files exist after the verify task runs.
            assertThat(project.baselineFileExists(FULL_BASELINE)).isTrue()
            assertThat(project.baselineFileExists(FULL_FAST_BASELINE)).isTrue()
            assertThat(project.readBaselineFile(FULL_BASELINE))
                .isEqualTo(project.readBaselineFile(FULL_FAST_BASELINE))
        }
    }

    @Test
    fun `verifyParity runs in the same build as the guard tasks`() {
        AndroidProject().use { project ->
            build(project, ":app:proguardShieldFullBaseline", ":app:proguardShieldFullFastBaseline")

            val result = build(
                project,
                ":app:proguardShieldFull",
                ":app:proguardShieldFullFast",
                ":app:proguardShieldVerifyParity",
            )
            assertThat(result.output).contains("parity holds")
            // Guards must check the committed baselines before the parity
            // task regenerates them.
            val order = result.tasks.map { it.path }
            assertThat(order.indexOf(":app:proguardShieldFullRelease"))
                .isLessThan(order.indexOf(":app:proguardShieldFullBaselineRelease"))
            assertThat(order.indexOf(":app:proguardShieldFullFastRelease"))
                .isLessThan(order.indexOf(":app:proguardShieldFullFastBaselineRelease"))
        }
    }

    @Test
    fun `verifyParity fails when the two baselines diverge`() {
        AndroidProject().use { project ->
            // Generate both baselines, then mutate the fullFast one out-of-band
            // so the byte-compare must fail. Touching the file directly is
            // the only way to simulate a parity break — the regular full
            // and fullFast modes agree by construction.
            build(project, ":app:proguardShieldFullBaseline", ":app:proguardShieldFullFastBaseline")

            val fastFile = project.dir.resolve("app/proguardShield/$FULL_FAST_BASELINE_NAME")
            fastFile.writeText("-keep class com.example.NotInTheAccurateBaseline\n")

            val result = buildAndFail(
                project,
                ":app:proguardShieldVerifyParityRelease",
                // Stop the dependent baseline tasks from regenerating the
                // file we just mutated, so the verify step actually compares
                // the divergent inputs.
                "-x", ":app:proguardShieldFullFastBaselineRelease",
                "-x", ":app:proguardShieldFullBaselineRelease",
            )
            assertThat(result.output).contains("parity FAILED")
            assertThat(result.output).contains("com.example.NotInTheAccurateBaseline")
        }
    }

    @Test
    fun `fullFast drops consumer rules ignored via keepRules DSL so parity holds`() {
        AndroidProject(
            // `ignoreExternalDependencies` exists on every supported AGP;
            // `ignoreFrom` (8.8+) is a newer alias feeding the same filter.
            releaseExtra = """
                optimization {
                    keepRules {
                        ignoreExternalDependencies 'com.example:ignored'
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
    fun `guard passes when rules have not changed`() {
        AndroidProject().use { project ->
            build(project, ":app:proguardShieldFullBaseline", ":app:proguardShieldFullFastBaseline")

            val result = build(project, ":app:proguardShieldFull")
            assertThat(result.output).doesNotContain("rules changed")
        }
    }

    @Test
    fun `full guard fails when a new rule is added`() {
        AndroidProject().use { project ->
            build(project, ":app:proguardShieldFullBaseline", ":app:proguardShieldFullFastBaseline")

            project.updateProguardRules(
                AndroidProject.DEFAULT_PROGUARD_RULES + "\n-keep class com.example.Added { *; }",
            )

            val result = buildAndFail(project, ":app:proguardShieldFullRelease")
            assertThat(result.output).contains("rules changed")
            assertThat(result.output).contains("-keep class com.example.Added")
        }
    }

    @Test
    fun `fullFast guard fails when a new rule is added`() {
        AndroidProject().use { project ->
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
    fun `guard fails when an existing rule is removed`() {
        AndroidProject().use { project ->
            build(project, ":app:proguardShieldFullBaseline", ":app:proguardShieldFullFastBaseline")

            project.updateProguardRules("# all rules removed")

            val result = buildAndFail(project, ":app:proguardShieldFullRelease")
            assertThat(result.output).contains("rules changed")
            assertThat(result.output).contains("-keepattributes")
        }
    }

    @Test
    fun `rebaseline overwrites the stored baselines`() {
        AndroidProject().use { project ->
            build(project, ":app:proguardShieldFullBaseline", ":app:proguardShieldFullFastBaseline")
            val before = project.readBaselineFile(FULL_BASELINE)!!

            project.updateProguardRules(
                AndroidProject.DEFAULT_PROGUARD_RULES + "\n-keep class com.example.Rebaseline { *; }",
            )
            build(project, ":app:proguardShieldFullBaseline", ":app:proguardShieldFullFastBaseline")
            val after = project.readBaselineFile(FULL_BASELINE)!!

            assertThat(after).isNotEqualTo(before)
            assertThat(after).contains("-keep class com.example.Rebaseline")

            // The fullFast baseline tracks the same change.
            assertThat(project.readBaselineFile(FULL_FAST_BASELINE)).isEqualTo(after)
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
            val result = buildAndFail(project, ":app:check", "--dry-run")
            assertThat(result.output).contains("could not resolve configuration")
            assertThat(result.output).contains("nonexistent")
            assertThat(result.output).contains("configuration(\"release\")")
            // `debug` has no minification, so suggesting it would only lead to the next error.
            assertThat(result.output).doesNotContain("configuration(\"debug\")")
        }
    }

    @Test
    fun `forbidden pattern in app rules trips both full and fullFast`() {
        val pluginConfig = """
            proguardShield {
                configuration("release") {
                    full = true
                    fullFast = true
                    forbiddenPatterns = ["-dontobfuscate"]
                }
            }
        """.trimIndent()

        // full
        AndroidProject(
            pluginConfig = pluginConfig,
            proguardRules = AndroidProject.DEFAULT_PROGUARD_RULES + "\n-dontobfuscate",
        ).use { project ->
            val result = buildAndFail(project, ":app:proguardShieldFullRelease")
            assertThat(result.output).contains("forbidden rule patterns detected")
            assertThat(result.output).contains("-dontobfuscate")
        }

        // fullFast — same DSL, same rules, same outcome.
        AndroidProject(
            pluginConfig = pluginConfig,
            proguardRules = AndroidProject.DEFAULT_PROGUARD_RULES + "\n-dontobfuscate",
        ).use { project ->
            val result = buildAndFail(project, ":app:proguardShieldFullFastRelease")
            assertThat(result.output).contains("forbidden rule patterns detected")
            assertThat(result.output).contains("-dontobfuscate")
        }
    }

    @Test
    fun `forbidden patterns empty list is a no-op`() {
        AndroidProject(
            // Default DSL — no forbiddenPatterns at all.
            proguardRules = AndroidProject.DEFAULT_PROGUARD_RULES + "\n-dontobfuscate",
        ).use { project ->
            // Plain baseline + check should sail through; -dontobfuscate is
            // legal absent a policy that bans it.
            build(project, ":app:proguardShieldFullBaseline", ":app:proguardShieldFullFastBaseline")
            val result = build(project, ":app:proguardShieldFull")
            assertThat(result.output).doesNotContain("forbidden rule patterns")
        }
    }

    @Test
    fun `forbidden check fires before drift detection`() {
        // Baseline a clean state, then add a forbidden rule. Both drift and
        // forbidden are now true; the failure message should still be the
        // forbidden one — re-baselining must not silence forbidden.
        AndroidProject(
            pluginConfig = """
                proguardShield {
                    configuration("release") {
                        full = true
                        fullFast = true
                        forbiddenPatterns = ["-dontobfuscate"]
                    }
                }
            """.trimIndent(),
        ).use { project ->
            build(project, ":app:proguardShieldFullBaseline", ":app:proguardShieldFullFastBaseline")

            project.updateProguardRules(
                AndroidProject.DEFAULT_PROGUARD_RULES + "\n-dontobfuscate",
            )

            val result = buildAndFail(project, ":app:proguardShieldFullRelease")
            assertThat(result.output).contains("forbidden rule patterns detected")
            // We never reach the drift report because forbidden short-circuits.
            assertThat(result.output).doesNotContain("rules changed")
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
            val result = buildAndFail(project, ":app:proguardShieldFull")
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
            val result = buildAndFail(project, ":app:proguardShieldFull")
            assertThat(result.output).contains("android.buildTypes.release.isMinifyEnabled = true")
            assertThat(result.output).doesNotContain("android.buildTypes.devRelease")
        }
    }

    @Test
    fun `configuration without flags registers no full or fullFast tasks`() {
        AndroidProject(pluginConfig = AndroidProject.MINIMAL_PLUGIN_CONFIG).use { project ->
            val tasks = build(project, ":app:tasks", "--all").output
            assertThat(tasks).doesNotContain("proguardShieldFullRelease")
            assertThat(tasks).doesNotContain("proguardShieldFullFastRelease")
            assertThat(tasks).doesNotContain("proguardShieldVerifyParityRelease")
            // Without full, nothing is injected into R8's inputs.
            assertThat(tasks).doesNotContain("generateProguardShieldInjectRelease")
        }
    }

    @Test
    fun `full alone is not on check and has no parity task`() {
        AndroidProject(
            pluginConfig = """
                proguardShield {
                    configuration("release") {
                        full = true
                    }
                }
            """.trimIndent(),
        ).use { project ->
            val scheduled = checkTasks(project)
            assertThat(scheduled).doesNotContain(":app:proguardShieldFullRelease")
            assertThat(scheduled).doesNotContain(":app:minifyReleaseWithR8")
            assertThat(build(project, ":app:tasks", "--all").output).doesNotContain("proguardShieldVerifyParityRelease")
        }
    }

    @Test
    fun `check runs the optimization guard by default`() {
        AndroidProject(pluginConfig = AndroidProject.MINIMAL_PLUGIN_CONFIG).use { project ->
            val scheduled = checkTasks(project)
            assertThat(scheduled).contains(":app:proguardShieldOptimizationRelease")
            assertThat(scheduled).doesNotContain(":app:proguardShieldFullFastRelease")
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
            assertThat(project.baselineFileExists(FULL_BASELINE)).isFalse()
            assertThat(project.baselineFileExists(FULL_FAST_BASELINE)).isFalse()
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
    fun `optimization and fullFast read the rules AGP passes to R8 as strings`() {
        AndroidProject(
            pluginConfig = """
                proguardShield {
                    configuration("release") {
                        full = true
                        fullFast = true
                        tree = true
                    }
                }
            """.trimIndent(),
        ).use { project ->
            // Stands in for AGP, which passes some rules this way (JaCoCo's keeps when testing with coverage).
            project.appendToAppBuildFile(
                "afterEvaluate { tasks.named('minifyReleaseWithR8').configure { proguardConfigurations.add('-dontoptimize') } }",
            )

            val result = build(project, ":app:proguardShieldOptimizationBaseline", ":app:proguardShieldVerifyParity")

            assertThat(result.output).contains("parity holds")
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
                "implementation 'com.jarvendor:util:1.0'",
        ).use { project ->
            project.publishLocalAar(
                "com.vendor", "sdk", "1.0",
                """
                -keep class com.google.gson.** { *; }
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

            assertThat(project.readOptimizationRules(OPTIMIZATION_LIST)).isEqualTo(
                "-ignorewarnings\n-keep class com.google.gson.** { *; }\n-keep class io.github.fornewid.** { *; }\n" +
                    "-keep class okhttp3.** { *; }\n-keep class okio.** { *; }\n",
            )
            assertThat(project.readBaselineFile(OPTIMIZATION_TREE)).isEqualTo(
                "[com.jarvendor:util]\n-keep class okio.** { *; }\n\n" +
                    "[com.vendor:sdk]\n-ignorewarnings\n-keep class com.google.gson.** { *; }\n\n" +
                    "[io.github.fornewid:core]\n-keep class io.github.fornewid.** { *; }\n\n" +
                    "[vendor-file.aar]\n-keep class okhttp3.** { *; }\n",
            )
        }
    }

    @Test
    fun `optimization failure with tree shows the changed rules under the library`() {
        AndroidProject(
            pluginConfig = AndroidProject.TREE_PLUGIN_CONFIG,
            dependencies = "implementation 'com.vendor:sdk:1.0'",
        ).use { project ->
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

            build(project, ":app:proguardShieldOptimizationBaseline")

            assertThat(project.readOptimizationRules(OPTIMIZATION_LIST)).isEmpty()
        }
    }

    @Test
    fun `optimization and parity run in one build`() {
        AndroidProject().use { project ->
            build(project, ":app:proguardShieldOptimizationBaseline")

            val result = build(project, ":app:proguardShieldOptimization", ":app:proguardShieldVerifyParity")
            assertThat(result.output).contains("parity holds")
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
