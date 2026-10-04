package io.github.fornewid.gradle.plugins.proguardshield.internal.optimization

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.fornewid.gradle.plugins.proguardshield.internal.rules.RuleNormalizer
import org.junit.jupiter.api.Test

class ClassSpecificationTest {

    private fun parse(rule: String) = ClassSpecification.parse(RuleNormalizer.normalizeUnits(rule).single())

    @Test
    fun `class name patterns know their package and whether they cover whole packages`() {
        listOf(
            Triple("com.foo.Bar", "com.foo", "exact"),
            Triple("com.foo.**", "com.foo", "package-wide"),
            Triple("com.foo.*", "com.foo", "package-wide"),
            Triple("com.foo.**\$*", "com.foo", "package-wide"),
            Triple("com.applovin.sdk**", "com.applovin", "package-wide"),
            Triple("**", "", "package-wide"),
            Triple("androidx.**Parcelizer", "androidx", "name"),
            Triple("com.adjust.sdk.DeviceInfo**", "com.adjust.sdk", "name"),
            Triple("com.foo.Bar\$*", "com.foo", "name"),
            Triple("**.R\$*", "", "name"),
        ).forEach { (text, pkg, kind) ->
            val pattern = ClassNamePattern(text)
            val actualKind = when {
                pattern.isExact -> "exact"
                pattern.isPackageWide -> "package-wide"
                else -> "name"
            }
            assertWithMessage(text).that(pattern.packageLiteral).isEqualTo(pkg)
            assertWithMessage(text).that(actualKind).isEqualTo(kind)
        }
    }

    @Test
    fun `patterns reach only the packages they can match`() {
        assertThat(ClassNamePattern("com.foo.Bar").reaches("com.foo")).isTrue()
        assertThat(ClassNamePattern("com.foo.Bar").reaches("com.foo.sub")).isFalse()
        assertThat(ClassNamePattern("com.foo.*").reaches("com.foo")).isTrue()
        assertThat(ClassNamePattern("com.foo.*").reaches("com.foo.sub")).isFalse()
        assertThat(ClassNamePattern("com.foo.**").reaches("com.foo")).isTrue()
        assertThat(ClassNamePattern("com.foo.**").reaches("com.foo.sub")).isTrue()
        assertThat(ClassNamePattern("com.foo.**").reaches("com.foobar")).isFalse()
        assertThat(ClassNamePattern("com.applovin.sdk**").reaches("com.applovin.sdk.ads")).isTrue()
        assertThat(ClassNamePattern("**").reaches("anything.at.all")).isTrue()
    }

    @Test
    fun `back references are recognized`() {
        assertThat(ClassNamePattern("<1>").hasBackReference).isTrue()
        assertThat(ClassNamePattern("<2>\$<3>").hasBackReference).isTrue()
        assertThat(ClassNamePattern("com.foo.**").hasBackReference).isFalse()
    }

    @Test
    fun `parses directive, modifiers, annotation, names, inheritance and members`() {
        val spec = parse(
            "-keep, allowshrinking ,allowobfuscation @com.bar.Keep public class com.bar.Api, !com.bar.Skip, " +
                "com.google.gson.** extends com.bar.Base { *; void run(); }",
        )!!
        assertThat(spec.directive).isEqualTo("keep")
        assertThat(spec.modifiers).containsExactly("allowshrinking", "allowobfuscation")
        assertThat(spec.annotation).isEqualTo("com.bar.Keep")
        assertThat(spec.names.map { it.text }).containsExactly("com.bar.Api", "com.google.gson.**").inOrder()
        assertThat(spec.inheritance?.text).isEqualTo("com.bar.Base")
        assertThat(spec.members).containsExactly("*", "void run()").inOrder()
    }

    @Test
    fun `parses multi-line bodies, implements, annotation types and missing bodies`() {
        assertThat(parse("-keep class com.google.gson.** {\n*;\n}")!!.members).containsExactly("*")
        assertThat(parse("-keep class * implements android.os.Parcelable")!!.inheritance?.text)
            .isEqualTo("android.os.Parcelable")
        val annotationType = parse("-keep @interface androidx.annotation.Keep")!!
        assertThat(annotationType.annotation).isNull()
        assertThat(annotationType.names.map { it.text }).containsExactly("androidx.annotation.Keep")
        assertThat(parse("-keep class kotlin.Metadata")!!.members).isEmpty()
        val worker = parse("-keepnames @com.bar.Marker class * extends androidx.work.ListenableWorker")!!
        assertThat(worker.annotation).isEqualTo("com.bar.Marker")
        assertThat(worker.inheritance?.text).isEqualTo("androidx.work.ListenableWorker")
        assertThat(parse("-assumenosideeffects class android.util.Log { public static int d(...); }")!!.directive)
            .isEqualTo("assumenosideeffects")
    }

    @Test
    fun `rules without a class specification are not parsed`() {
        assertThat(parse("-dontwarn com.foo.**")).isNull()
        assertThat(parse("-if class com.foo.Bar")).isNull()
        assertThat(parse("-keepattributes Signature")).isNull()
        assertThat(parse("-ignorewarnings")).isNull()
    }
}
