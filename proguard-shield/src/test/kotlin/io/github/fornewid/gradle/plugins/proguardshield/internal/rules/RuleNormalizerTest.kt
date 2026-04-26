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

        // Output is sorted by rule-unit header.
        assertThat(result).isEqualTo(
            """
            -keep class com.example.Bar
            -keep class com.example.Foo
            """.trimIndent(),
        )
    }

    @Test
    fun `sorts single-line directives by header`() {
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
    fun `keeps multi-line block body anchored to its header`() {
        // Two blocks whose headers sort B-before-A. The body of A must
        // travel with its header during sort — that's the whole point of
        // unit-level sort vs. line-level sort.
        val input = """
            -keep class com.example.B {
                <init>();
            }
            -keep class com.example.A {
                public *;
            }
        """.trimIndent()

        val result = RuleNormalizer.normalizeLines(input)

        assertThat(result).containsExactly(
            "-keep class com.example.A {",
            "public *;",
            "}",
            "-keep class com.example.B {",
            "<init>();",
            "}",
        ).inOrder()
    }

    @Test
    fun `multi-line directives without braces stay grouped`() {
        // R8 wraps long -keepattributes onto continuation lines that don't
        // start with '-'. They must not be treated as separate units.
        val input = """
            -dontwarn xxx
            -keepattributes AnnotationDefault,
            EnclosingMethod,
            Signature
            -dontwarn yyy
        """.trimIndent()

        val result = RuleNormalizer.normalizeLines(input)

        assertThat(result).containsExactly(
            "-dontwarn xxx",
            "-dontwarn yyy",
            "-keepattributes AnnotationDefault,",
            "EnclosingMethod,",
            "Signature",
        ).inOrder()
    }

    @Test
    fun `strips our injected -printconfiguration directive`() {
        // R8's merged output echoes back the -printconfiguration line from
        // our injected .pro file. That line carries an absolute build-dir
        // path, so leaving it in the baseline would break reproducibility
        // across machines.
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

    @Test
    fun `nested braces are tolerated`() {
        // Rare in proguard rules but the parser should not fall apart.
        val input = """
            -keep class A {
                int foo() { return 1; }
            }
            -keep class B
        """.trimIndent()

        val result = RuleNormalizer.normalizeLines(input)

        assertThat(result).containsExactly(
            "-keep class A {",
            "int foo() { return 1; }",
            "}",
            "-keep class B",
        ).inOrder()
    }
}
