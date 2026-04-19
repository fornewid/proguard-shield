package io.github.fornewid.gradle.plugins.proguardshield.fixture

import java.io.File
import java.util.UUID

internal class AndroidProject(
    private val proguardRules: String = DEFAULT_PROGUARD_RULES,
    private val pluginConfig: String = DEFAULT_PLUGIN_CONFIG,
    private val minifyEnabled: Boolean = true,
    private val extraProguardFiles: String = "",
) : AutoCloseable {

    val dir: File = File("build/gradleTest/${UUID.randomUUID()}").apply { mkdirs() }

    init {
        val pluginJar = System.getProperty("pluginJar")
            ?: error("pluginJar system property not set. Run via './gradlew :proguard-shield:gradleTest'")
        val escapedJar = pluginJar.replace("\\", "/")
        val agpVersion = System.getProperty("agpVersion") ?: DEFAULT_AGP_VERSION

        dir.resolve("settings.gradle").writeText(
            """
            rootProject.name = "test-project"
            include ':app'
            """.trimIndent(),
        )

        // Root build.gradle — inject both AGP and proguard-shield via buildscript.
        dir.resolve("build.gradle").writeText(
            """
            buildscript {
                repositories {
                    google()
                    mavenCentral()
                }
                dependencies {
                    classpath 'com.android.tools.build:gradle:$agpVersion'
                    classpath files('$escapedJar')
                }
            }
            allprojects {
                repositories {
                    google()
                    mavenCentral()
                }
            }
            """.trimIndent(),
        )

        dir.resolve("gradle.properties").writeText(
            """
            android.useAndroidX=true
            org.gradle.jvmargs=-Xmx1g
            """.trimIndent(),
        )

        val androidHome = System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: findSdkDirFromLocalProperties()
            ?: error("ANDROID_HOME or ANDROID_SDK_ROOT must be set")
        // Properties files treat `\` as an escape character, so paths like
        // C:\Users\... must be normalized to forward slashes on Windows.
        // Gradle/AGP accept either form on every OS.
        dir.resolve("local.properties").writeText("sdk.dir=${androidHome.replace("\\", "/")}")

        val appDir = dir.resolve("app").apply { mkdirs() }

        val buildTypeBlock = if (minifyEnabled) {
            """
                buildTypes {
                    release {
                        minifyEnabled true
                        proguardFiles(
                            getDefaultProguardFile('proguard-android-optimize.txt'),
                            'proguard-rules.pro'$extraProguardFiles
                        )
                    }
                }
            """.trimIndent()
        } else {
            ""
        }

        appDir.resolve("build.gradle").writeText(
            """
            apply plugin: 'com.android.application'
            apply plugin: 'io.github.fornewid.proguard-shield'

            android {
                compileSdk 34
                namespace "io.github.fornewid.test"
                defaultConfig {
                    minSdk 23
                    targetSdk 34
                }
                $buildTypeBlock
            }

            $pluginConfig
            """.trimIndent(),
        )

        appDir.resolve("proguard-rules.pro").writeText(proguardRules)

        val srcDir = appDir.resolve("src/main").apply { mkdirs() }
        srcDir.resolve("AndroidManifest.xml").writeText(DEFAULT_MANIFEST)
    }

    fun updateProguardRules(newContent: String) {
        dir.resolve("app/proguard-rules.pro").writeText(newContent)
    }

    fun readBaselineFile(path: String): String? {
        val file = dir.resolve("app/$path")
        return if (file.exists()) file.readText() else null
    }

    fun baselineFileExists(path: String): Boolean {
        return dir.resolve("app/$path").exists()
    }

    override fun close() {
        dir.deleteRecursively()
    }

    private fun findSdkDirFromLocalProperties(): String? {
        var current: File? = File("").absoluteFile
        while (current != null) {
            val localProps = current.resolve("local.properties")
            if (localProps.exists()) {
                val props = java.util.Properties().apply { localProps.reader().use { load(it) } }
                val sdkDir = props.getProperty("sdk.dir")
                if (sdkDir != null) return sdkDir
            }
            current = current.parentFile
        }
        return null
    }

    companion object {
        private const val DEFAULT_AGP_VERSION = "8.8.0"

        val DEFAULT_MANIFEST = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:label="Test">
                    <activity android:name=".MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """.trimIndent()

        val DEFAULT_PROGUARD_RULES = """
            # Default test rules
            -keepattributes SourceFile,LineNumberTable
        """.trimIndent()

        val DEFAULT_PLUGIN_CONFIG = """
            proguardShield {
                configuration("release")
            }
        """.trimIndent()
    }
}
