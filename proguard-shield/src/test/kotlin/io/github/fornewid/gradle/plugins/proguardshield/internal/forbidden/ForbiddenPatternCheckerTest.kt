package io.github.fornewid.gradle.plugins.proguardshield.internal.forbidden

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ForbiddenPatternCheckerTest {

    @Test
    fun `empty patterns produces no violations`() {
        val rules = listOf("-keep class com.example.Foo", "-dontobfuscate")

        val violations = ForbiddenPatternChecker.check(rules, emptyList())

        assertThat(violations).isEmpty()
    }

    @Test
    fun `empty rules produces no violations`() {
        val violations = ForbiddenPatternChecker.check(emptyList(), listOf("-dontobfuscate"))

        assertThat(violations).isEmpty()
    }

    @Test
    fun `single substring match`() {
        val rules = listOf(
            "-keep class com.example.Foo",
            "-dontobfuscate",
            "-dontwarn javax.annotation.**",
        )

        val violations = ForbiddenPatternChecker.check(rules, listOf("-dontobfuscate"))

        assertThat(violations).hasSize(1)
        assertThat(violations[0].pattern).isEqualTo("-dontobfuscate")
        assertThat(violations[0].matchedRules).containsExactly("-dontobfuscate")
    }

    @Test
    fun `regex with whitespace metachars matches one or more spaces`() {
        val rules = listOf(
            "-keep class com.example.Foo",
            "-keep  class  **",
            "-keep class com.example.Bar",
        )

        val violations = ForbiddenPatternChecker.check(rules, listOf("-keep\\s+class\\s+\\*\\*"))

        assertThat(violations).hasSize(1)
        assertThat(violations[0].matchedRules).containsExactly("-keep  class  **")
    }

    @Test
    fun `multiple patterns each report independently`() {
        val rules = listOf(
            "-keep class ** { *; }",
            "-dontobfuscate",
            "-dontshrink",
            "-keep class com.example.Foo",
        )

        val violations = ForbiddenPatternChecker.check(
            rules,
            listOf("-keep\\s+class\\s+\\*\\*", "-dontobfuscate", "-dontshrink"),
        )

        assertThat(violations.map { it.pattern }).containsExactly(
            "-keep\\s+class\\s+\\*\\*",
            "-dontobfuscate",
            "-dontshrink",
        ).inOrder()
        assertThat(violations[0].matchedRules).containsExactly("-keep class ** { *; }")
        assertThat(violations[1].matchedRules).containsExactly("-dontobfuscate")
        assertThat(violations[2].matchedRules).containsExactly("-dontshrink")
    }

    @Test
    fun `same pattern with multiple matches groups them under one violation`() {
        val rules = listOf(
            "-keep class ** extends Activity",
            "-keep class **.R$* { *; }",
            "-keep class com.example.Foo",
        )

        val violations = ForbiddenPatternChecker.check(rules, listOf("-keep\\s+class\\s+\\*\\*"))

        assertThat(violations).hasSize(1)
        assertThat(violations[0].matchedRules).containsExactly(
            "-keep class ** extends Activity",
            "-keep class **.R$* { *; }",
        )
    }

    @Test
    fun `non-matching patterns produce no violations`() {
        val rules = listOf("-keep class com.example.Foo", "-dontwarn java.**")

        val violations = ForbiddenPatternChecker.check(rules, listOf("-dontobfuscate"))

        assertThat(violations).isEmpty()
    }

    @Test
    fun `failure message lists pattern then matched rules`() {
        val violations = ForbiddenPatternChecker.check(
            listOf("-dontobfuscate", "-keep class **.R$* { *; }"),
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
