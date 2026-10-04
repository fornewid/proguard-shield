package io.github.fornewid.gradle.plugins.proguardshield.internal.optimization

import com.google.common.truth.Truth.assertWithMessage
import io.github.fornewid.gradle.plugins.proguardshield.internal.rules.RuleNormalizer
import org.junit.jupiter.api.Test

class LibraryRuleMatcherTest {

    private val packages = LibraryPackages(
        mapOf(
            "com.bar:sdk" to setOf("com.bar", "com.bar.internal"),
            "com.bar:ext" to setOf("com.bar.ext"),
            "com.google.code.gson:gson" to setOf("com.google.gson", "com.google.gson.reflect"),
            "com.squareup.okhttp3:okhttp" to setOf("okhttp3", "okhttp3.internal"),
            "androidx.recyclerview:recyclerview" to setOf("androidx.recyclerview.widget"),
            "androidx.room:room-runtime" to setOf("androidx.room"),
            "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm" to setOf("kotlinx.coroutines"),
            "androidx.compose.ui:ui-android" to setOf("androidx.compose.ui", "androidx.compose.ui.graphics.painter"),
            "androidx.versionedparcelable:versionedparcelable" to setOf("androidx.versionedparcelable"),
            "org.jetbrains.kotlin:kotlin-stdlib" to setOf("kotlin"),
        ),
    )

    private fun listed(rule: String, label: String = "com.bar:sdk", packages: LibraryPackages = this.packages) =
        LibraryRuleMatcher.matches(RuleNormalizer.normalizeUnits(rule).single(), label, packages)

    private fun assertListed(vararg rules: String, label: String = "com.bar:sdk") =
        rules.forEach { assertWithMessage(it).that(listed(it, label)).isTrue() }

    private fun assertNotListed(vararg rules: String, label: String = "com.bar:sdk") =
        rules.forEach { assertWithMessage(it).that(listed(it, label)).isFalse() }

    @Test
    fun `whole packages the library does not ship are listed`() {
        assertListed(
            "-keep class com.google.gson.** { *; }",
            "-keep class okhttp3.**",
            "-keepnames class com.google.gson.**",
            "-keepclassmembers class com.google.gson.** { *; }",
            "-keep,allowobfuscation class com.google.gson.** { *; }",
            "-keep,allowshrinking class com.google.gson.** { *; }",
            "-keep public class com.google.gson.** { public *; }",
            "-keep public interface com.applovin.sdk** {*; }",
            "-keep class com.facebook.** { *; }",
            "-keep class com.google.gson.** {\n*;\n}",
            "-keep class com.bar.Api, com.google.gson.** { *; }",
        )
    }

    @Test
    fun `all fields or methods of a class the library does not ship are listed`() {
        assertListed(
            "-keep class androidx.recyclerview.widget.RecyclerView { *;}",
            "-keep class com.google.gson.Gson { public <methods>; }",
            "-keepclassmembers class com.google.gson.Gson { <fields>; }",
        )
    }

    @Test
    fun `app classes kept through a type the library does not own are listed`() {
        assertListed(
            "-keep class * extends android.app.Activity",
            "-keepnames class * extends androidx.compose.ui.graphics.painter.Painter",
        )
        val onlyOwn = LibraryPackages(mapOf("com.bar:sdk" to setOf("com.bar")))
        assertWithMessage("classpath of only the library's packages")
            .that(listed("-keep class * extends android.app.Activity", packages = onlyOwn)).isTrue()
    }

    @Test
    fun `assume rules on code the library does not ship and app-wide options are listed`() {
        assertListed(
            "-assumenosideeffects class android.util.Log { *; }",
            "-assumevalues class com.google.gson.Gson { boolean DEBUG return false; }",
            "-ignorewarnings",
            "-repackageclasses",
            "-keeppackagenames com.google.**",
            "-keeppackagenames",
        )
    }

    @Test
    fun `rules on the library's own packages or its Maven group are not listed`() {
        assertNotListed(
            "-keep class com.bar.** { *; }",
            "-keep class com.bar.internal.Impl { *; }",
            "-keep class com.bar.ext.** { *; }",
            "-keeppackagenames com.bar.**",
            "-assumenosideeffects class com.bar.internal.Log { *; }",
        )
        assertNotListed(
            "-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }",
            label = "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm",
        )
        assertNotListed("-keep class com.bar.** { *; }", label = "com.bar:ghost")
        assertWithMessage("library without artifacts").that(listed("-keep class com.google.gson.** { *; }", "com.ghost:lib"))
            .isTrue()
    }

    @Test
    fun `app classes kept through the library's own types or an annotation are not listed`() {
        assertNotListed(
            "-keep class * extends com.bar.Base { *; }",
            "-keep @androidx.annotation.Keep class * {*;}",
            "-keepnames @com.bar.Marker class * extends androidx.work.ListenableWorker",
        )
        assertNotListed(
            "-keep class * extends androidx.room.RoomDatabase { void <init>(); }",
            label = "androidx.room:room-runtime",
        )
    }

    @Test
    fun `a named class without members or only some members are not listed`() {
        assertNotListed(
            "-keep class kotlin.Metadata",
            "-keep class com.google.gson.Gson { void toJson(); }",
            "-keepclassmembers class * implements android.os.Parcelable { public static final *** CREATOR; }",
            "-keepclasseswithmembers class * { native <methods>; }",
        )
        assertNotListed(
            "-keepclassmembers,allowshrinking,allowobfuscation class androidx.compose.**.* { static void throw*Exception(...); }",
            label = "androidx.compose.ui:ui-android",
        )
    }

    @Test
    fun `rules with both allowshrinking and allowobfuscation are not listed`() {
        assertNotListed(
            "-keep,allowshrinking,allowobfuscation class com.google.gson.** { *; }",
            "-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation",
        )
    }

    @Test
    fun `name patterns, back references and the rules phase 1 decides are not listed`() {
        assertNotListed(
            "-keep public class androidx.**Parcelizer { *; }",
            "-keep class com.adjust.sdk.DeviceInfo**",
            "-keep class <1>",
            "-keepclassmembers class <1> { *; }",
            "-keep class ** { *; }",
            "-dontobfuscate",
            "-keepattributes *",
            "-keepattributes Signature",
            "-dontwarn com.google.**",
            "-dontnote",
            "-if class com.bar.Api",
        )
    }

    @Test
    fun `malformed rules are not listed and do not throw`() {
        assertNotListed(
            "-keep",
            "-keep class",
            "-keep class {",
            "-keep,allowshrinking",
            "-keepclassmembers class * {",
            "-keepclasseswithmembers class",
            "-assumenosideeffects",
            "-assumevalues class {",
        )
    }
}
