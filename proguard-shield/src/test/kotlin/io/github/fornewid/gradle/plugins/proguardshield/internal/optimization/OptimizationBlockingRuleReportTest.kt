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
                added = listOf(LabeledRule("com.other:lib", "-dontobfuscate")),
                removed = listOf(LabeledRule("com.example:sdk", "-dontobfuscate")),
            ),
        )
    }

    @Test
    fun `diffTree round-trips its own output`() {
        val rules = listOf(BlockingRule(keepAll, sdk), BlockingRule("-dontobfuscate", app))
        val baseline = OptimizationBlockingRuleReport.renderTree(rules)
        assertThat(OptimizationBlockingRuleReport.diffTree(baseline, rules).isEmpty).isTrue()
    }

    @Test
    fun `failureMessage shows only the rule diff without a tree diff`() {
        val message = OptimizationBlockingRuleReport.failureMessage(
            projectPath = ":app",
            configurationName = "release",
            list = RuleChanges(added = listOf(keepAll), removed = listOf("-keepattributes *")),
            tree = RuleChanges.none(),
            rebaselineMessage = "re-baseline hint",
        )
        assertThat(message).isEqualTo(
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
        val message = OptimizationBlockingRuleReport.failureMessage(
            projectPath = ":app",
            configurationName = "release",
            list = RuleChanges(added = listOf("-dontobfuscate", "-ignorewarnings"), removed = listOf("-keepattributes *")),
            tree = RuleChanges(
                added = listOf(
                    LabeledRule("com.example:sdk", "-dontobfuscate"),
                    LabeledRule("com.example:sdk", "-ignorewarnings"),
                    LabeledRule("com.other:lib", "-dontobfuscate"),
                ),
                removed = listOf(LabeledRule(":app", "-keepattributes *")),
            ),
            rebaselineMessage = "re-baseline hint",
        )
        assertThat(message).isEqualTo(
            """
            ProGuard Shield: optimization-blocking rules changed in :app (release).
              [:app]
            - -keepattributes *
              [com.example:sdk]
            + -dontobfuscate
            + -ignorewarnings
              [com.other:lib]
            + -dontobfuscate

            re-baseline hint
            """.trimIndent(),
        )
    }

    @Test
    fun `failureMessage shows a rule another origin also adds under that origin only`() {
        val message = OptimizationBlockingRuleReport.failureMessage(
            projectPath = ":app",
            configurationName = "release",
            list = RuleChanges.none(),
            tree = RuleChanges(added = listOf(LabeledRule("com.other:lib", keepAll)), removed = emptyList()),
            rebaselineMessage = "re-baseline hint",
        )
        assertThat(message).isEqualTo(
            """
            ProGuard Shield: optimization-blocking rules changed in :app (release).
              [com.other:lib]
            + -keep class ** {
            + *;
            + }

            re-baseline hint
            """.trimIndent(),
        )
    }
}
