package io.github.fornewid.gradle.plugins.proguardshield.internal.optimization

import com.google.common.truth.Truth.assertWithMessage
import io.github.fornewid.gradle.plugins.proguardshield.internal.rules.RuleNormalizer
import org.junit.jupiter.api.Test

class OptimizationBlockingRuleMatcherTest {

    private fun matches(rule: String): Boolean =
        OptimizationBlockingRuleMatcher.matches(RuleNormalizer.normalizeUnits(rule).single())

    private fun assertMatches(vararg rules: String) =
        rules.forEach { assertWithMessage(it).that(matches(it)).isTrue() }

    private fun assertDoesNotMatch(vararg rules: String) =
        rules.forEach { assertWithMessage(it).that(matches(it)).isFalse() }

    @Test
    fun `disabled optimization steps match`() {
        assertMatches("-dontobfuscate", "-dontshrink", "-dontoptimize")
    }

    @Test
    fun `keepattributes with a bare star or no filter matches`() {
        assertMatches("-keepattributes *", "-keepattributes", "-keepattributes Signature, *")
    }

    @Test
    fun `keepattributes with named or partially wildcarded attributes does not match`() {
        assertDoesNotMatch("-keepattributes *Annotation*", "-keepattributes Signature,InnerClasses,EnclosingMethod")
    }

    @Test
    fun `keep of every class matches regardless of the body`() {
        assertMatches(
            "-keep class ** { *; }",
            "-keep class ** {\n*;\n}",
            "-keep class *",
            "-keep public class * { public protected *; }",
            "-keepnames class **",
            "-keep class **\$*",
            "-keep,allowobfuscation class ** { *; }",
        )
    }

    @Test
    fun `member keeps on every class match only with unrestricted member specs`() {
        assertMatches(
            "-keepclassmembers class * { *; }",
            "-keepclassmembers class ** { <fields>; <methods>; }",
            "-keepclassmembernames class * { public *; }",
            "-keepclasseswithmembers class * { <init>(...); }",
        )
        assertDoesNotMatch(
            "-keepclasseswithmembers class * { <init>(...); native <methods>; }",
            "-keepclassmembers class * { public static ** Companion; }",
        )
    }

    @Test
    fun `allowshrinking together with allowobfuscation does not match`() {
        assertDoesNotMatch("-keep,allowshrinking,allowobfuscation class ** { *; }")
    }

    @Test
    fun `option modifiers with spaces around the commas read like the compact form`() {
        assertMatches("-keep, includedescriptorclasses class * { *; }")
        assertDoesNotMatch("-keep , allowshrinking , allowobfuscation class ** { *; }")
    }

    @Test
    fun `real library and AGP default rules do not match`() {
        assertDoesNotMatch(
            "-keepclasseswithmembers class * { @androidx.annotation.Keep <methods>; }",
            "-keepclasseswithmembers class * { @androidx.annotation.Keep <fields>; }",
            "-keepclasseswithmembers class * { @androidx.annotation.Keep <init>(...); }",
            "-keepclassmembers,allowobfuscation class * { @androidx.annotation.DoNotInline <methods>; }",
            "-keepclassmembers class ** { @androidx.lifecycle.OnLifecycleEvent *; }",
            "-keepclassmembernames class * { static <1> *; }",
            "-keepclassmembernames class * { @com.google.android.gms.common.annotation.KeepName *; }",
            "-keepclassmembers,includedescriptorclasses class * { @dagger.internal.KeepFieldType <fields>; }",
            "-keepclasseswithmembers class * { @com.squareup.moshi.* <methods>; }",
            "-keepclassmembers,allowshrinking,allowobfuscation interface * { @retrofit2.http.* <methods>; }",
            "-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }",
            "-keepclassmembers enum * { public static **[] values(); public static ** valueOf(java.lang.String); }",
            "-keep @androidx.annotation.Keep class * {*;}",
            "-keep class * implements androidx.versionedparcelable.VersionedParcelable",
            "-keep !interface * implements androidx.lifecycle.LifecycleObserver {\n}",
            "-keepclassmembers class * extends android.app.Activity { public void *(android.view.View); }",
            "-keepclassmembers class **.R\$* { public static <fields>; }",
            "-keepclassmembers public class **\$\$serializer { private ** descriptor; }",
            "-keep class com.example.** { *; }",
            "-dontwarn **",
            "-allowaccessmodification",
        )
    }
}
