package io.github.fornewid.gradle.plugins.proguardshield.internal.optimization

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LibraryPackagesTest {

    @TempDir
    lateinit var dir: File

    private val packages = LibraryPackages(
        mapOf(
            "com.bar:sdk" to setOf("com.bar", "com.bar.internal"),
            "com.bar:ext" to setOf("com.bar.ext"),
            "com.kakao.sdk:user" to setOf("com.kakao.sdk.user"),
            "com.kakao.sdk:common" to setOf("com.kakao.sdk.common"),
            "com.google.code.gson:gson" to setOf("com.google.gson"),
        ),
    )

    @Test
    fun `reads packages from an AAR's classes jar and libs jars`() {
        val aar = zip(
            dir.resolve("sdk.aar"),
            "classes.jar" to jar("com/bar/Api.class", "com/bar/internal/Impl.class"),
            "libs/extra.jar" to jar("com/bar/extra/Util.class"),
            "proguard.txt" to "-dontwarn com.bar.**".toByteArray(),
        )
        assertThat(LibraryPackages.packagesOf(aar)).containsExactly("com.bar", "com.bar.internal", "com.bar.extra")
    }

    @Test
    fun `reads packages from a JAR without module-info, the default package or the multi-release prefix`() {
        val file = dir.resolve("okhttp.jar").apply {
            writeBytes(
                jar(
                    "okhttp3/OkHttpClient.class",
                    "META-INF/versions/9/okhttp3/internal/Platform.class",
                    "module-info.class",
                    "Default.class",
                    "META-INF/MANIFEST.MF",
                ),
            )
        }
        assertThat(LibraryPackages.packagesOf(file)).containsExactly("okhttp3", "okhttp3.internal")
    }

    @Test
    fun `an AAR without classes and a file that is neither AAR nor JAR have no packages`() {
        val aar = zip(dir.resolve("rules-only.aar"), "proguard.txt" to "-ignorewarnings".toByteArray())
        val pom = dir.resolve("sdk.pom").apply { writeText("<project/>") }
        assertThat(LibraryPackages.packagesOf(aar)).isEmpty()
        assertThat(LibraryPackages.packagesOf(pom)).isEmpty()
    }

    @Test
    fun `read groups the packages of every artifact by library`() {
        val api = zip(dir.resolve("a.aar"), "classes.jar" to jar("com/bar/Api.class"))
        val extra = dir.resolve("b.jar").apply { writeBytes(jar("com/bar/extra/Util.class")) }
        val read = LibraryPackages.read(mapOf(api to "com.bar:sdk", extra to "com.bar:sdk"), appNamespace = "com.example.app")
        assertThat(read.ownPackages("com.bar:sdk")).containsExactly("com.bar", "com.bar.extra")
    }

    @Test
    fun `own packages include the other modules of the Maven group`() {
        assertThat(packages.ownPackages("com.bar:sdk")).containsExactly("com.bar", "com.bar.internal", "com.bar.ext")
    }

    @Test
    fun `a pattern inside the library's packages is its own`() {
        assertThat(packages.isOwn("com.bar:sdk", ClassNamePattern("com.bar.**"))).isTrue()
        assertThat(packages.isOwn("com.bar:sdk", ClassNamePattern("com.bar.internal.Impl"))).isTrue()
        assertThat(packages.isOwn("com.bar:sdk", ClassNamePattern("com.bar.ext.**"))).isTrue()
    }

    @Test
    fun `a pattern that reaches only the group's packages is its own`() {
        assertThat(packages.isOwn("com.kakao.sdk:user", ClassNamePattern("com.kakao.sdk.**"))).isTrue()
    }

    @Test
    fun `a pattern that reaches another library's package is not its own`() {
        assertThat(packages.isOwn("com.bar:sdk", ClassNamePattern("com.google.gson.**"))).isFalse()
        assertThat(packages.isOwn("com.bar:sdk", ClassNamePattern("com.**"))).isFalse()
    }

    @Test
    fun `a package missing from the classpath is not the library's own`() {
        assertThat(packages.isOwn("com.bar:sdk", ClassNamePattern("com.facebook.**"))).isFalse()
        assertThat(packages.isOwn("com.bar:sdk", ClassNamePattern("android.app.Activity"))).isFalse()
    }

    @Test
    fun `a pattern without a package is never the library's own, even on a classpath of only its packages`() {
        val onlyOwn = LibraryPackages(mapOf("com.bar:sdk" to setOf("com.bar")))
        assertThat(onlyOwn.isOwn("com.bar:sdk", ClassNamePattern("*"))).isFalse()
        assertThat(onlyOwn.isOwn("com.bar:sdk", ClassNamePattern("**"))).isFalse()
    }

    @Test
    fun `a pattern that reaches the app's namespace is never the library's own`() {
        val acme = LibraryPackages(mapOf("com.acme:core" to setOf("com.acme", "com.acme.core")), appNamespace = "com.acme.app")
        assertThat(acme.isOwn("com.acme:core", ClassNamePattern("com.acme.**"))).isFalse()
        assertThat(acme.isOwn("com.acme:core", ClassNamePattern("com.acme.core.**"))).isTrue()
        val noRootClasses = LibraryPackages(mapOf("com.acme:core" to setOf("com.acme.core")), appNamespace = "com.acme.app")
        assertThat(noRootClasses.isOwn("com.acme:core", ClassNamePattern("com.acme.**"))).isFalse()
    }

    @Test
    fun `a library without artifacts uses its group's packages`() {
        assertThat(packages.isOwn("com.bar:ghost", ClassNamePattern("com.bar.**"))).isTrue()
        assertThat(packages.isOwn("com.ghost:lib", ClassNamePattern("com.ghost.**"))).isFalse()
    }

    private fun zipBytes(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun jar(vararg entries: String): ByteArray = zipBytes(*entries.map { it to ByteArray(0) }.toTypedArray())

    private fun zip(file: File, vararg entries: Pair<String, ByteArray>): File = file.apply { writeBytes(zipBytes(*entries)) }
}
