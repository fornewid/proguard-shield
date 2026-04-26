package io.github.fornewid.gradle.plugins.proguardshield.internal.forbidden

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ForbiddenPatternCheckerTest {

    @Test
    fun `empty patterns produces no violations`() {
        val units = listOf(
            listOf("-keep class com.example.Foo"),
            listOf("-dontobfuscate"),
        )

        val violations = ForbiddenPatternChecker.check(units, emptyList())

        assertThat(violations).isEmpty()
    }

    @Test
    fun `empty units produces no violations`() {
        val violations = ForbiddenPatternChecker.check(emptyList(), listOf("-dontobfuscate"))

        assertThat(violations).isEmpty()
    }

    @Test
    fun `blank pattern strings are silently dropped`() {
        // A stray "" would otherwise containsMatchIn every header and report
        // every rule as a violation.
        val units = listOf(
            listOf("-keep class com.example.Foo"),
            listOf("-dontwarn javax.annotation.**"),
        )

        val violations = ForbiddenPatternChecker.check(units, listOf("", "   "))

        assertThat(violations).isEmpty()
    }

    @Test
    fun `single pattern match against header`() {
        val units = listOf(
            listOf("-keep class com.example.Foo"),
            listOf("-dontobfuscate"),
            listOf("-dontwarn javax.annotation.**"),
        )

        val violations = ForbiddenPatternChecker.check(units, listOf("-dontobfuscate"))

        assertThat(violations).hasSize(1)
        assertThat(violations[0].pattern).isEqualTo("-dontobfuscate")
        assertThat(violations[0].matchedRules).containsExactly("-dontobfuscate")
    }

    @Test
    fun `body lines do not match — only the unit header is tested`() {
        // The body line `*;` would superficially match a `\*;` pattern, but
        // forbidden checking targets unit headers, so a keep block whose
        // body contains `*;` does not trip a `*;` pattern.
        val units = listOf(
            listOf("-keep class com.example.Foo {", "*;", "}"),
            listOf("-keep class com.example.Bar"),
        )

        val violations = ForbiddenPatternChecker.check(units, listOf("\\*;"))

        assertThat(violations).isEmpty()
    }

    @Test
    fun `multi-line directive continuation lines participate in matching`() {
        // R8 wraps long -keepattributes onto continuation lines that don't
        // start with '-'. A user policy targeting `Signature` should catch
        // a Signature continuation token even when it sits on its own line.
        val units = listOf(
            listOf("-keepattributes AnnotationDefault,", "EnclosingMethod,", "Signature"),
            listOf("-keep class com.example.Foo"),
        )

        val violations = ForbiddenPatternChecker.check(units, listOf("Signature"))

        assertThat(violations).hasSize(1)
        assertThat(violations[0].matchedRules)
            .containsExactly("-keepattributes AnnotationDefault,\nEnclosingMethod,\nSignature")
    }

    @Test
    fun `header-matching violation reports the entire unit verbatim`() {
        val units = listOf(
            listOf("-keep class ** {", "*;", "}"),
            listOf("-keep class com.example.Bar"),
        )

        val violations = ForbiddenPatternChecker.check(units, listOf("-keep\\s+class\\s+\\*\\*"))

        assertThat(violations).hasSize(1)
        assertThat(violations[0].matchedRules).containsExactly("-keep class ** {\n*;\n}")
    }

    @Test
    fun `regex with whitespace metachars matches one or more spaces`() {
        val units = listOf(
            listOf("-keep class com.example.Foo"),
            listOf("-keep  class  **"),
            listOf("-keep class com.example.Bar"),
        )

        val violations = ForbiddenPatternChecker.check(units, listOf("-keep\\s+class\\s+\\*\\*"))

        assertThat(violations).hasSize(1)
        assertThat(violations[0].matchedRules).containsExactly("-keep  class  **")
    }

    @Test
    fun `multiple patterns each report independently`() {
        val units = listOf(
            listOf("-keep class ** { *; }"),
            listOf("-dontobfuscate"),
            listOf("-dontshrink"),
            listOf("-keep class com.example.Foo"),
        )

        val violations = ForbiddenPatternChecker.check(
            units,
            listOf("-keep\\s+class\\s+\\*\\*", "-dontobfuscate", "-dontshrink"),
        )

        assertThat(violations.map { it.pattern }).containsExactly(
            "-keep\\s+class\\s+\\*\\*",
            "-dontobfuscate",
            "-dontshrink",
        ).inOrder()
    }

    @Test
    fun `same pattern with multiple matches groups them under one violation`() {
        val units = listOf(
            listOf("-keep class ** extends Activity"),
            listOf("-keep class **.R\$* { *; }"),
            listOf("-keep class com.example.Foo"),
        )

        val violations = ForbiddenPatternChecker.check(units, listOf("-keep\\s+class\\s+\\*\\*"))

        assertThat(violations).hasSize(1)
        assertThat(violations[0].matchedRules).containsExactly(
            "-keep class ** extends Activity",
            "-keep class **.R\$* { *; }",
        )
    }

    @Test
    fun `invalid regex throws with the offending pattern in the message`() {
        val units = listOf(listOf("-keep class com.example.Foo"))

        val ex = assertThrows(IllegalArgumentException::class.java) {
            ForbiddenPatternChecker.check(units, listOf("[unclosed"))
        }
        assertThat(ex.message).contains("[unclosed")
    }

    @Test
    fun `failure message lists pattern then matched rules`() {
        val violations = ForbiddenPatternChecker.check(
            listOf(
                listOf("-dontobfuscate"),
                listOf("-keep class **.R\$* { *; }"),
            ),
            listOf("-dontobfuscate", "-keep\\s+class\\s+\\*\\*"),
        )

        val message = ForbiddenPatternChecker.renderFailureMessage(":app", "release", violations)

        assertThat(message).contains("forbidden rule patterns detected in :app (release)")
        assertThat(message).contains("Pattern: -dontobfuscate")
        assertThat(message).contains("Matched: -dontobfuscate")
        assertThat(message).contains("Pattern: -keep\\s+class\\s+\\*\\*")
        assertThat(message).contains("Matched: -keep class **.R\$* { *; }")
        assertThat(message).contains("forbiddenPatterns")
    }
}
