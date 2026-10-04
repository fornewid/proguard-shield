package io.github.fornewid.gradle.plugins.proguardshield.internal.optimization

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale

class R8ContextTest {

    @Test
    fun `lines give the AGP version, then every R8 mode property as set or default`() {
        assertThat(R8Context.lines("9.4.1") { if (it == "android.enableR8.fullMode") "false" else null })
            .containsExactly(
                "# agp=9.4.1",
                "# android.enableR8.fullMode=false",
                "# android.r8.strictFullModeForKeepRules=default",
                "# android.r8.globalOptionsInConsumerRules.disallowed=default",
            )
            .inOrder()
    }

    @Test
    fun `AGP versions keep their preview suffix`() {
        assertThat(R8Context.agpVersion(9, 4, 1, null, 0)).isEqualTo("9.4.1")
        assertThat(R8Context.agpVersion(9, 5, 0, "alpha", 3)).isEqualTo("9.5.0-alpha03")
        assertThat(R8Context.agpVersion(9, 5, 0, "dev", 0)).isEqualTo("9.5.0-dev")
    }

    @Test
    fun `AGP versions do not depend on the locale`() {
        val default = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertThat(R8Context.agpVersion(9, 5, 0, "alpha", 3)).isEqualTo("9.5.0-alpha03")
        } finally {
            Locale.setDefault(default)
        }
    }

    @Test
    fun `only lines R8Context writes are header lines`() {
        assertThat(R8Context.isHeader("# agp=9.4.1")).isTrue()
        assertThat(R8Context.isHeader("# android.r8.strictFullModeForKeepRules=default")).isTrue()
        assertThat(R8Context.isHeader("# accepted")).isFalse()
        assertThat(R8Context.isHeader("# reason=vendor")).isFalse()
    }
}
