package io.github.fornewid.gradle.plugins.proguardshield.internal.optimization

/**
 * What decides how R8 reads the rules, written as the header of the optimization list: the AGP version and
 * the R8 mode properties as set (`default` when unset, so AGP's default for that version applies). An AGP
 * upgrade or a changed property then fails `check` once.
 */
internal object R8Context {

    private val PROPERTIES = listOf(
        "android.enableR8.fullMode",
        "android.r8.strictFullModeForKeepRules",
        "android.r8.globalOptionsInConsumerRules.disallowed",
    )
    private val KEYS = setOf("agp") + PROPERTIES

    /** `# agp=<version>`, then `# <property>=<value>` for each R8 mode property; [property] reads a Gradle property. */
    fun lines(agpVersion: String, property: (String) -> String?): List<String> =
        listOf("# agp=$agpVersion") + PROPERTIES.map { "# $it=${property(it) ?: "default"}" }

    /** The AGP version as AGP writes it: `9.4.1`, `9.5.0-alpha03`, `9.5.0-dev`. */
    fun agpVersion(major: Int, minor: Int, micro: Int, previewType: String?, preview: Int): String =
        "$major.$minor.$micro" + previewType?.let { "-$it" + if (preview > 0) preview.toString().padStart(2, '0') else "" }.orEmpty()

    /** A `# key=value` line written from [lines]; other comments in a baseline are not. */
    fun isHeader(line: String): Boolean = line.startsWith("# ") && line.drop(2).substringBefore('=', "") in KEYS
}
