package io.github.fornewid.gradle.plugins.proguardshield.fixture

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal class AndroidProject(
    private val proguardRules: String = DEFAULT_PROGUARD_RULES,
    private val pluginConfig: String = DEFAULT_PLUGIN_CONFIG,
    private val minifyEnabled: Boolean = true,
    private val shrinkResources: Boolean = false,
    private val extraProguardFiles: String = "",
    val agpVersion: String = System.getProperty("agpVersion") ?: DEFAULT_AGP_VERSION,
    val gradleVersion: String? = null,
    private val releaseExtra: String = "",
    private val dependencies: String = "",
) : AutoCloseable {

    val dir: File = File("build/gradleTest/${UUID.randomUUID()}").apply { mkdirs() }

    init {
        val pluginJar = System.getProperty("pluginJar")
            ?: error("pluginJar system property not set. Run via './gradlew :proguard-shield:gradleTest'")
        val escapedJar = pluginJar.replace("\\", "/")

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
                    maven { url = rootProject.file('$LOCAL_REPO') }
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
                        shrinkResources $shrinkResources
                        proguardFiles(
                            getDefaultProguardFile('proguard-android-optimize.txt'),
                            'proguard-rules.pro'$extraProguardFiles
                        )
                        $releaseExtra
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

            dependencies {
                $dependencies
            }

            $pluginConfig
            """.trimIndent(),
        )

        appDir.resolve("proguard-rules.pro").writeText(proguardRules)

        val srcDir = appDir.resolve("src/main").apply { mkdirs() }
        srcDir.resolve("AndroidManifest.xml").writeText(DEFAULT_MANIFEST)
    }

    /**
     * Publishes a minimal AAR carrying [consumerRules] as `proguard.txt` and empty
     * [classes] (e.g. `com.vendor.sdk.Api`) in `classes.jar` into the project-local
     * Maven repo, so it resolves as an external module (`ModuleComponentIdentifier`)
     * without network access.
     */
    fun publishLocalAar(
        group: String,
        name: String,
        version: String,
        consumerRules: String,
        classes: List<String> = emptyList(),
    ) {
        writeAar(localRepoArtifact(group, name, version, "aar"), "$group.$name", consumerRules, classes)
    }

    /** Publishes a JAR library whose keep rules are in `META-INF/proguard/`, where JARs ship them. */
    fun publishLocalJar(group: String, name: String, version: String, rules: String, classes: List<String> = emptyList()) {
        localRepoArtifact(group, name, version, "jar").writeBytes(classesJar(classes, "META-INF/proguard/$name.pro" to rules))
    }

    /** Writes the POM of `group:name:version` to the local repository and returns its artifact file. */
    private fun localRepoArtifact(group: String, name: String, version: String, packaging: String): File {
        val moduleDir = dir.resolve("$LOCAL_REPO/${group.replace('.', '/')}/$name/$version").apply { mkdirs() }
        moduleDir.resolve("$name-$version.pom").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>$group</groupId>
              <artifactId>$name</artifactId>
              <version>$version</version>
              <packaging>$packaging</packaging>
            </project>
            """.trimIndent(),
        )
        return moduleDir.resolve("$name-$version.$packaging")
    }

    /** Writes an AAR to `app/libs/[fileName]`, for a `files('libs/...')` dependency. */
    fun writeAppLibsAar(fileName: String, consumerRules: String, classes: List<String> = emptyList()) {
        writeAar(dir.resolve("app/libs/$fileName").apply { parentFile.mkdirs() }, "local.libs", consumerRules, classes)
    }

    private fun writeAar(target: File, manifestPackage: String, consumerRules: String, classes: List<String>) {
        ZipOutputStream(target.outputStream()).use { aar ->
            aar.putNextEntry(ZipEntry("AndroidManifest.xml"))
            aar.write(
                "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"$manifestPackage\" />"
                    .toByteArray(),
            )
            aar.putNextEntry(ZipEntry("classes.jar"))
            aar.write(classesJar(classes))
            aar.putNextEntry(ZipEntry("R.txt"))
            aar.putNextEntry(ZipEntry("proguard.txt"))
            aar.write(consumerRules.toByteArray())
        }
    }

    private fun classesJar(classes: List<String>, vararg textFiles: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { jar ->
            jar.putNextEntry(ZipEntry("META-INF/"))
            textFiles.forEach { (path, text) ->
                jar.putNextEntry(ZipEntry(path))
                jar.write(text.toByteArray())
            }
            classes.forEach { className ->
                val internalName = className.replace('.', '/')
                jar.putNextEntry(ZipEntry("$internalName.class"))
                jar.write(minimalClassFile(internalName))
            }
        }
        return bytes.toByteArray()
    }

    /** Bytes of an empty public class [internalName] (e.g. `com/vendor/sdk/Api`) extending Object, class file version 52. */
    private fun minimalClassFile(internalName: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(0xCAFEBABE.toInt())
            out.writeShort(0) // minor version
            out.writeShort(52) // major version (Java 8)
            out.writeShort(5) // constant pool count: entries #1-#4
            out.writeByte(7) // #1 Class -> #2
            out.writeShort(2)
            out.writeByte(1) // #2 Utf8 internalName
            out.writeUTF(internalName)
            out.writeByte(7) // #3 Class -> #4
            out.writeShort(4)
            out.writeByte(1) // #4 Utf8 java/lang/Object
            out.writeUTF("java/lang/Object")
            out.writeShort(0x0021) // ACC_PUBLIC | ACC_SUPER
            out.writeShort(1) // this_class
            out.writeShort(3) // super_class
            out.writeShort(0) // interfaces
            out.writeShort(0) // fields
            out.writeShort(0) // methods
            out.writeShort(0) // attributes
        }
        return bytes.toByteArray()
    }

    fun updateProguardRules(newContent: String) {
        dir.resolve("app/proguard-rules.pro").writeText(newContent)
    }

    /** Adds a `:feature` dynamic feature module whose release build type ships [rules]. */
    fun addDynamicFeature(rules: String) {
        dir.resolve("settings.gradle").appendText("\ninclude ':feature'\n")
        appendToAppBuildFile("android.dynamicFeatures = [':feature']")
        val featureDir = dir.resolve("feature").apply { mkdirs() }
        featureDir.resolve("build.gradle").writeText(
            """
            apply plugin: 'com.android.dynamic-feature'

            android {
                compileSdk 34
                namespace "io.github.fornewid.test.feature"
                defaultConfig {
                    minSdk 23
                }
                buildTypes {
                    release {
                        proguardFiles 'feature-rules.pro'
                    }
                }
            }

            dependencies {
                implementation project(':app')
            }
            """.trimIndent(),
        )
        featureDir.resolve("feature-rules.pro").writeText(rules)
        featureDir.resolve("src/main").apply { mkdirs() }.resolve("AndroidManifest.xml").writeText("<manifest />")
    }

    fun appendToAppBuildFile(text: String) {
        dir.resolve("app/build.gradle").appendText("\n$text\n")
    }

    /** Rewrites part of `app/build.gradle`, e.g. to change the plugin config or a dependency version. */
    fun replaceInAppBuildFile(old: String, new: String) {
        val buildFile = dir.resolve("app/build.gradle")
        val content = buildFile.readText()
        check(old in content) { "'$old' not found in app/build.gradle" }
        buildFile.writeText(content.replace(old, new))
    }

    fun readBaselineFile(path: String): String? {
        val file = dir.resolve("app/$path")
        return if (file.exists()) file.readText() else null
    }

    /** The rules of an optimization list baseline, without its R8 context header. */
    fun readOptimizationRules(path: String): String? = readBaselineFile(path)?.substringAfter("\n\n", "")

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
        private const val LOCAL_REPO = "local-repo"

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

        /** Enables the full-rule modes so the parity-era tests keep their meaning. */
        val DEFAULT_PLUGIN_CONFIG = """
            proguardShield {
                configuration("release") {
                    full = true
                    fullFast = true
                }
            }
        """.trimIndent()

        /** The configuration a user writes without any mode flags. */
        val MINIMAL_PLUGIN_CONFIG = """
            proguardShield {
                configuration("release")
            }
        """.trimIndent()

        val TREE_PLUGIN_CONFIG = """
            proguardShield {
                configuration("release") {
                    tree = true
                }
            }
        """.trimIndent()
    }
}
