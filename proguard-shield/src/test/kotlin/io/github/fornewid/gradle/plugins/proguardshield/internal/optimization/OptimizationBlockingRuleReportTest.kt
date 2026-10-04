package io.github.fornewid.gradle.plugins.proguardshield.internal.optimization

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OptimizationBlockingRuleReportTest {

    private val app = RuleOrigin(":app")
    private val lib = RuleOrigin(":lib")
    private val sdk = RuleOrigin("com.example:sdk")
    private val other = RuleOrigin("com.other:lib")
    private val agp = RuleOrigin("<agp>")
    private val unresolved = RuleOrigin("<unresolved>")
    private val keepAll = "-keep class ** {\n*;\n}"

    @Test
    fun `renderList is empty when nothing blocks optimization`() {
        assertThat(OptimizationBlockingRuleReport.renderList(emptyList())).isEmpty()
        assertThat(OptimizationBlockingRuleReport.renderTree(emptyList())).isEmpty()
    }

    @Test
    fun `renderList dedups and sorts rules, keeping multi-line rules intact`() {
        val rules = listOf(
            BlockingRule(keepAll, app),
            BlockingRule("-dontobfuscate", sdk),
            BlockingRule("-dontobfuscate", other),
        )
        assertThat(OptimizationBlockingRuleReport.renderList(rules))
            .isEqualTo("-dontobfuscate\n-keep class ** {\n*;\n}\n")
    }

    @Test
    fun `renderTree lists a rule under every origin that adds it`() {
        val rules = listOf(
            BlockingRule("-dontobfuscate", unresolved),
            BlockingRule("-dontobfuscate", sdk),
            BlockingRule("-keepattributes *", agp),
            BlockingRule("-dontobfuscate", lib),
            BlockingRule("-keepattributes *", app),
        )
        assertThat(OptimizationBlockingRuleReport.renderTree(rules)).isEqualTo(
            """
            [:app]
            -keepattributes *

            [:lib]
            -dontobfuscate

            [com.example:sdk]
            -dontobfuscate

            [<agp>]
            -keepattributes *

            [<unresolved>]
            -dontobfuscate

            """.trimIndent(),
        )
    }

    @Test
    fun `diffList round-trips multi-line rules`() {
        val rules = listOf(BlockingRule(keepAll, app), BlockingRule("-dontobfuscate", sdk))
        val baseline = OptimizationBlockingRuleReport.renderList(rules)
        assertThat(OptimizationBlockingRuleReport.diffList(baseline, rules).isEmpty).isTrue()
    }

    @Test
    fun `diffList ignores CRLF, comments and blank lines`() {
        val rules = listOf(BlockingRule(keepAll, app), BlockingRule("-dontobfuscate", sdk))
        val baseline = "# accepted\r\n-dontobfuscate\r\n\r\n-keep class ** {\r\n*;\r\n}\r\n"
        assertThat(OptimizationBlockingRuleReport.diffList(baseline, rules).isEmpty).isTrue()
    }

    @Test
    fun `diffList reports added and removed rules`() {
        val changes = OptimizationBlockingRuleReport.diffList(
            "-keepattributes *\n",
            listOf(BlockingRule("-dontobfuscate", sdk)),
        )
        assertThat(changes).isEqualTo(RuleChanges(added = listOf("-dontobfuscate"), removed = listOf("-keepattributes *")))
    }

    @Test
    fun `diffTree catches a rule that moved to another origin`() {
        val rules = listOf(BlockingRule("-dontobfuscate", other))
        val baseline = OptimizationBlockingRuleReport.renderTree(listOf(BlockingRule("-dontobfuscate", sdk)))
        assertThat(OptimizationBlockingRuleReport.diffList(OptimizationBlockingRuleReport.renderList(rules), rules).isEmpty).isTrue()
        assertThat(OptimizationBlockingRuleReport.diffTree(baseline, rules)).isEqualTo(
            RuleChanges(
                added = listOf(BlockingRule("-dontobfuscate", other)),
                removed = listOf(BlockingRule("-dontobfuscate", sdk)),
            ),
        )
    }

    @Test
    fun `diffTree round-trips its own output`() {
        val rules = listOf(BlockingRule(keepAll, sdk), BlockingRule("-dontobfuscate", app))
        val baseline = OptimizationBlockingRuleReport.renderTree(rules)
        assertThat(OptimizationBlockingRuleReport.diffTree(baseline, rules).isEmpty).isTrue()
    }

    private fun message(list: RuleChanges<String>, tree: RuleChanges<BlockingRule>) =
        OptimizationBlockingRuleReport.failureMessage(":app", "release", list, tree, "re-baseline hint")

    @Test
    fun `failureMessage shows only the rule diff without a tree diff`() {
        val list = RuleChanges(added = listOf(keepAll), removed = listOf("-keepattributes *"))
        assertThat(message(list, RuleChanges.none())).isEqualTo(
            """
            ProGuard Shield: optimization-blocking rules changed in :app (release).
            + -keep class ** {
            + *;
            + }
            - -keepattributes *

            re-baseline hint
            """.trimIndent(),
        )
    }

    @Test
    fun `failureMessage with a tree diff groups the changed rules under each origin`() {
        val tree = RuleChanges(
            added = listOf(
                BlockingRule("-dontobfuscate", sdk),
                BlockingRule("-ignorewarnings", sdk),
                BlockingRule(keepAll, other),
            ),
            removed = listOf(BlockingRule("-keepattributes *", app)),
        )
        assertThat(message(RuleChanges.none(), tree)).isEqualTo(
            """
            ProGuard Shield: optimization-blocking rules changed in :app (release).
              [:app]
            - -keepattributes *
              [com.example:sdk]
            + -dontobfuscate
            + -ignorewarnings
              [com.other:lib]
            + -keep class ** {
            + *;
            + }

            re-baseline hint
            """.trimIndent(),
        )
    }
}
