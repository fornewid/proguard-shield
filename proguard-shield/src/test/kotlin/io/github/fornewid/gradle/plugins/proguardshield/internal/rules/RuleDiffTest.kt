package io.github.fornewid.gradle.plugins.proguardshield.internal.rules

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class RuleDiffTest {

    @Test
    fun `identical normalized content returns NoDiff`() {
        val result = RuleDiff.compare(
            projectPath = ":sample:app",
            configurationName = "release",
            expectedContent = "-keep class Foo\n-keep class Bar",
            actualContent = "-keep class Foo\n-keep class Bar",
        )

        assertThat(result).isInstanceOf(RuleDiffResult.NoDiff::class.java)
    }

    @Test
    fun `comment-only differences are ignored`() {
        val result = RuleDiff.compare(
            projectPath = ":sample:app",
            configurationName = "release",
            expectedContent = "-keep class Foo",
            actualContent = "# comment\n-keep class Foo\n",
        )

        assertThat(result).isInstanceOf(RuleDiffResult.NoDiff::class.java)
    }

    @Test
    fun `added rule appears in addedLines`() {
        val result = RuleDiff.compare(
            projectPath = ":sample:app",
            configurationName = "release",
            expectedContent = "-keep class Foo",
            actualContent = "-keep class Foo\n-keep class Bar",
        ) as RuleDiffResult.HasDiff

        assertThat(result.addedLines).containsExactly("-keep class Bar")
        assertThat(result.removedLines).isEmpty()
    }

    @Test
    fun `removed rule appears in removedLines`() {
        val result = RuleDiff.compare(
            projectPath = ":sample:app",
            configurationName = "release",
            expectedContent = "-keep class Foo\n-keep class Bar",
            actualContent = "-keep class Foo",
        ) as RuleDiffResult.HasDiff

        assertThat(result.removedLines).containsExactly("-keep class Bar")
        assertThat(result.addedLines).isEmpty()
    }

    @Test
    fun `duplicate count drop is reported as removed`() {
        // R8's merged output emits the same rule from multiple AARs. If one
        // AAR is removed the duplicate count drops by one; the old list-based
        // filter would miss this since the rule still exists in `actual`.
        val result = RuleDiff.compare(
            projectPath = ":sample:app",
            configurationName = "release",
            expectedContent = "-dontwarn kotlin.*\n-dontwarn kotlin.*\n-keep class A",
            actualContent = "-dontwarn kotlin.*\n-keep class A",
        ) as RuleDiffResult.HasDiff

        assertThat(result.removedLines).containsExactly("-dontwarn kotlin.*")
        assertThat(result.addedLines).isEmpty()
    }

    @Test
    fun `matching duplicate counts produce NoDiff`() {
        val result = RuleDiff.compare(
            projectPath = ":sample:app",
            configurationName = "release",
            expectedContent = "-dontwarn kotlin.*\n-dontwarn kotlin.*",
            actualContent = "-dontwarn kotlin.*\n-dontwarn kotlin.*",
        )

        assertThat(result).isInstanceOf(RuleDiffResult.NoDiff::class.java)
    }

    @Test
    fun `diff message contains both plus and minus entries sorted`() {
        val result = RuleDiff.compare(
            projectPath = ":sample:app",
            configurationName = "release",
            expectedContent = "-keep class Old",
            actualContent = "-keep class New",
        ) as RuleDiffResult.HasDiff

        val message = result.format(withColor = false, rebaselineMessage = "rebaseline hint")

        assertThat(message).contains("- -keep class Old")
        assertThat(message).contains("+ -keep class New")
        assertThat(message).contains("rebaseline hint")
    }

    @Test
    fun `body lines swapped between blocks are reported`() {
        val result = RuleDiff.compare(
            projectPath = ":sample:app",
            configurationName = "release",
            expectedContent = "-keep class * implements A {\n<init>(...);\n}\n-keep class * implements B {\n<fields>;\n}",
            actualContent = "-keep class * implements A {\n<fields>;\n}\n-keep class * implements B {\n<init>(...);\n}",
        )

        assertThat(result).isInstanceOf(RuleDiffResult.HasDiff::class.java)
    }

    @Test
    fun `body line moved to another block is reported`() {
        val result = RuleDiff.compare(
            projectPath = ":sample:app",
            configurationName = "release",
            expectedContent = "-keep class A {\n<init>();\n<fields>;\n}\n-keep class B {\n<methods>;\n}",
            actualContent = "-keep class A {\n<fields>;\n}\n-keep class B {\n<init>();\n<methods>;\n}",
        ) as RuleDiffResult.HasDiff

        assertThat(result.removedLines).containsExactly(
            "-keep class A {", "<init>();", "<fields>;", "}",
            "-keep class B {", "<methods>;", "}",
        )
        assertThat(result.addedLines).containsExactly(
            "-keep class A {", "<fields>;", "}",
            "-keep class B {", "<init>();", "<methods>;", "}",
        )
    }

    @Test
    fun `body order change within a block produces NoDiff`() {
        val result = RuleDiff.compare(
            projectPath = ":sample:app",
            configurationName = "release",
            expectedContent = "-keep class A {\n<init>();\n<fields>;\n}",
            actualContent = "-keep class A {\n<fields>;\n<init>();\n}",
        )

        assertThat(result).isInstanceOf(RuleDiffResult.NoDiff::class.java)
    }
}
