import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  `kotlin-dsl`
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.dokka)
  alias(libs.plugins.maven.publish)
  alias(libs.plugins.binary.compatibility.validator)
  alias(libs.plugins.plugin.publish)
}

repositories {
  mavenCentral()
  google()
  gradlePluginPortal()
}

val VERSION_NAME: String by project
version = VERSION_NAME

tasks.withType<KotlinCompile>().configureEach {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_8)
    languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_8)
  }
}

tasks.withType<JavaCompile>().configureEach {
  options.release.set(11)
}

kotlin {
  explicitApi()
}

gradlePlugin {
  website.set("https://github.com/fornewid/proguard-shield")
  vcsUrl.set("https://github.com/fornewid/proguard-shield")
  plugins {
    plugins.create("proguard-shield") {
      id = "io.github.fornewid.proguard-shield"
      implementationClass = "io.github.fornewid.gradle.plugins.proguardshield.ProGuardShieldPlugin"
      displayName = "ProGuard Shield"
      description = "A Gradle plugin that detects unintentional changes to Android's merged ProGuard/R8 rules."
      tags.set(listOf("android", "proguard", "r8", "security", "gradle-plugin"))
    }
  }
}

mavenPublishing {
  publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
  signAllPublications()
}

dependencies {
  compileOnly(gradleApi())
  compileOnly("com.android.tools.build:gradle:8.0.0")
}

tasks.named<ProcessResources>("processResources") {
  val pluginVersion = project.version.toString()
  inputs.property("pluginVersion", pluginVersion)
  filesMatching("proguard-shield.properties") {
    expand("version" to pluginVersion)
  }
}

val deleteOldGradleTests = tasks.register<Delete>("deleteOldGradleTests") {
  delete(layout.buildDirectory.file("gradleTest"))
}

@Suppress("UnstableApiUsage")
testing {
  suites {
    val test by getting(JvmTestSuite::class) {
      useJUnitJupiter()
      dependencies {
        implementation(libs.truth)
      }
    }

    val gradleTest by registering(JvmTestSuite::class) {
      useJUnitJupiter()
      dependencies {
        implementation(project())
        implementation(libs.truth)
      }

      targets {
        configureEach {
          testTask.configure {
            shouldRunAfter(test)
            dependsOn(deleteOldGradleTests)
            maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
          }
        }
      }
    }
  }
}

gradlePlugin.testSourceSets(sourceSets.named("gradleTest").get())

// Pass the plugin JAR path to gradleTest for buildscript classpath injection.
// We avoid withPluginClasspath() due to AGP classloader isolation issues.
//
// AGP version the integration tests run against. Override with
//   ./gradlew :proguard-shield:gradleTest -PagpVersion=8.0.0
// to sweep a matrix (see CI workflow).
val agpVersionForTests: String =
  (project.findProperty("agpVersion") as? String) ?: libs.versions.agp.get()

afterEvaluate {
  val jarTask = tasks.named("jar", Jar::class.java).get()
  tasks.named("gradleTest", Test::class.java) {
    dependsOn(jarTask)
    systemProperty("pluginJar", jarTask.archiveFile.get().asFile.absolutePath)
    systemProperty("agpVersion", agpVersionForTests)
  }
}

@Suppress("UnstableApiUsage")
tasks.named("check") {
  dependsOn(testing.suites.named("gradleTest"))
}

tasks.register("printVersionName") {
  doLast {
    println(VERSION_NAME)
  }
}
