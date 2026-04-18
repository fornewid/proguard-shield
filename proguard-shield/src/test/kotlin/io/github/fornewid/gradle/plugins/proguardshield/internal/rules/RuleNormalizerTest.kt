package io.github.fornewid.gradle.plugins.proguardshield.internal.rules

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class RuleNormalizerTest {

    @Test
    fun `strips full-line comments`() {
        val input = """
            # This is a comment
            -keep class com.example.Foo
            # Another comment
        """.trimIndent()

        val result = RuleNormalizer.normalize(input)

        assertThat(result).isEqualTo("-keep class com.example.Foo")
    }

    @Test
    fun `strips inline comments`() {
        val input = "-keep class com.example.Foo   # keep this for reflection"

        val result = RuleNormalizer.normalize(input)

        assertThat(result).isEqualTo("-keep class com.example.Foo")
    }

    @Test
    fun `removes blank lines and trims whitespace`() {
        val input = """

            -keep class com.example.Foo
                -keep class com.example.Bar

        """.trimIndent()

        val result = RuleNormalizer.normalize(input)

        // Output is sorted — makes the baseline stable across R8 versions
        // and lets the fast and accurate tasks produce bit-identical files.
        assertThat(result).isEqualTo(
            """
            -keep class com.example.Bar
            -keep class com.example.Foo
            """.trimIndent(),
        )
    }

    @Test
    fun `sorts rules alphabetically`() {
        val input = """
            -keep class B
            -keep class A
            -keep class C
        """.trimIndent()

        val result = RuleNormalizer.normalizeLines(input)

        assertThat(result).containsExactly(
            "-keep class A",
            "-keep class B",
            "-keep class C",
        ).inOrder()
    }

    @Test
    fun `strips our injected -printconfiguration directive`() {
        // R8's merged output echoes back the -printconfiguration line from our
        // injected .pro file. That line contains an absolute build-dir path, so
        // leaving it in the baseline would break reproducibility across machines.
        val input = """
            -printconfiguration /Users/ci/work/proguard-shield/sample/app/build/proguardShield/release/merged-rules.txt
            -keep class com.example.Foo
        """.trimIndent()

        val result = RuleNormalizer.normalize(input)

        assertThat(result).isEqualTo("-keep class com.example.Foo")
    }

    @Test
    fun `empty input produces empty output`() {
        assertThat(RuleNormalizer.normalize("")).isEqualTo("")
        assertThat(RuleNormalizer.normalize("# only comment")).isEqualTo("")
        assertThat(RuleNormalizer.normalize("\n\n  \n")).isEqualTo("")
    }
}
