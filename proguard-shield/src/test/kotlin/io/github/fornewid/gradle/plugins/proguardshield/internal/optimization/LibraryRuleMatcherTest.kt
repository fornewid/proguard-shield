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
            "com.kakao.sdk:user" to setOf("com.kakao.sdk.user"),
            "com.kakao.sdk:common" to setOf("com.kakao.sdk.common"),
            "com.applovin:applovin-sdk" to setOf("com.applovin.sdk"),
            "com.facebook.android:facebook-core" to setOf("com.facebook"),
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
            "-keepclasseswithmembers class com.google.gson.** { *; }",
            "-keepclasseswithmembernames class com.google.gson.**",
        )
        assertListed("-keep class com.google.gson.** { *; }", label = "com.ghost:lib")
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
    fun `rules whose targets match no class R8 processes are not listed`() {
        assertNotListed(
            "-keep class com.qq.e.ads.rewardvideo** { *; }",
            "-keep class com.google.gson.examples.android.model.** { *; }",
            "-keep class androidx.recyclerview.* { *; }",
            "-keep class androidx.recyclerview.widget.RecyclerView.LayoutManager { *; }",
            "-keep class javax.xml.** { *; }",
            "-keep class com.qq.** extends android.app.Activity { *; }",
            "-keeppackagenames com.qq.**",
        )
        val app = LibraryPackages(mapOf("com.bar:sdk" to setOf("com.bar")), appNamespace = "com.example.app")
        assertWithMessage("a package in the app's namespace")
            .that(listed("-keep class com.example.app.ui.** { *; }", packages = app)).isTrue()
    }

    @Test
    fun `rules that only reach excluded packages are not listed`() {
        val ads = LibraryPackages(
            mapOf(
                "com.applovin:applovin-sdk" to setOf("com.applovin.sdk", "com.applovin.mediation"),
                "com.google.code.gson:gson" to setOf("com.google.gson"),
            ),
            excludePackages = listOf("com.applovin"),
        )
        listOf(
            "-keep class com.applovin.** { *; }",
            "-keep class * extends com.applovin.mediation.MaxAdapter { *; }",
            "-keeppackagenames com.applovin.**",
        ).forEach { assertWithMessage(it).that(listed(it, label = "com.vendor:adapter", packages = ads)).isFalse() }
        assertWithMessage("a rule that also reaches a package that is not excluded")
            .that(listed("-keep class com.applovin.**, com.google.gson.** { *; }", label = "com.vendor:adapter", packages = ads))
            .isTrue()
    }

    @Test
    fun `app-wide options that reduce what R8 does are listed`() {
        assertListed(
            "-keepparameternames",
            "-keepkotlinmetadata",
            "-dontrepackage",
            "-keeppackagenames com.google.**",
            "-keeppackagenames",
            "-keeppackagenames !com.bar.**",
        )
    }

    @Test
    fun `assume rules and app-wide options that do not reduce what R8 does are not listed`() {
        assertNotListed(
            "-assumenosideeffects class android.util.Log { *; }",
            "-assumevalues class com.google.gson.Gson { boolean DEBUG return false; }",
            "-assumenoescapingparameters class com.google.gson.Gson { *; }",
            "-printconfiguration rules.txt",
            "-printmapping proguard.map",
            "-adaptresourcefilenames okhttp3/internal/publicsuffix/PublicSuffixDatabase.gz",
            "-adaptresourcefilecontents",
            "-repackageclasses",
            "-allowaccessmodification",
            "-ignorewarnings",
            "-optimizations !class/unboxing/enum",
            "-dontusemixedcaseclassnames",
            "-useuniqueclassmembernames",
        )
    }

    @Test
    fun `rules on the library's own packages or its Maven group are not listed`() {
        assertNotListed(
            "-keep class com.bar.** { *; }",
            "-keep class com.bar.internal.Impl { *; }",
            "-keep class com.bar.ext.** { *; }",
            "-keeppackagenames com.bar.**",
            "-keeppackagenames com.bar",
        )
        assertNotListed(
            "-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }",
            label = "org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm",
        )
        assertNotListed("-keep class com.bar.** { *; }", label = "com.bar:ghost")
        assertNotListed("-keeppackagenames com.kakao.sdk.**", label = "com.kakao.sdk:user")
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
    fun `app classes of a type outside the library kept with only some members are not listed`() {
        assertNotListed(
            "-keep public class * extends androidx.coordinatorlayout.widget.CoordinatorLayout\$Behavior " +
                "{ public <init>(android.content.Context, android.util.AttributeSet); public <init>(); }",
            label = "com.google.android.material:material",
        )
        assertNotListed(
            "-keep class * extends androidx.work.InputMerger { <init>(); }",
            "-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }",
            label = "com.unity3d.ads:unity-ads",
        )
    }

    @Test
    fun `a named class without members or only some members are not listed`() {
        assertNotListed(
            "-keep class kotlin.Metadata",
            "-keep class com.google.gson.Gson { void toJson(); }",
            "-keep class com.google.gson.** { void foo(); }",
            "-keepclasseswithmembers class com.google.gson.** { *; void foo(); }",
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
        )
    }
}
