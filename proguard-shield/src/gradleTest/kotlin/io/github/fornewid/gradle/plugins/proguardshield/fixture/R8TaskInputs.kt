package io.github.fornewid.gradle.plugins.proguardshield.fixture

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import io.github.fornewid.gradle.plugins.proguardshield.internal.r8input.R8TaskInputExtractor

/**
 * The file inputs of AGP's R8 task, by getter. AGP has moved keep rules to new inputs before (AAPT2 and
 * dynamic feature rules in 9.3, #30 and #55), and the optimization and fullFast modes then missed them
 * silently. [assertAllClassified] fails instead when an AGP version adds an input listed in neither set.
 */
internal object R8TaskInputs {

    /** Keep rules, as [R8TaskInputExtractor] reads them. */
    private val RULES = (R8TaskInputExtractor.REQUIRED_METHOD_NAMES + R8TaskInputExtractor.OPTIONAL_METHOD_NAMES).toSet()

    /** No keep rules for an app's R8 run, or only ones [RULES] already has. */
    private val NOT_RULES = setOf(
        // The library rules of getConfigurationFiles again.
        "getLibraryKeepRulesFileCollection",
        // Library modules only.
        "getAarKeepRulesFiles",
        // Every AGP default rule file; R8 reads the one getConfigurationFiles names.
        "getExtractedDefaultProguardFile",
        // The tested app's mapping, for test components.
        "getTestedMappingFile",
        // Rules for the main dex list only.
        "getMainDexRulesFiles",
        "getMultiDexKeepProguard",
        "getMultiDexKeepFile",
        // Classes, Java resources and profiles.
        "getClasses",
        "getReferencedClasses",
        "getBootClasspath",
        "getR8Classpath",
        "getBaseJar",
        "getFeatureClassJars",
        "getReferencedResources",
        "getResources", // AGP 8.0's getResourcesJar
        "getResourcesJar",
        "getFeatureJavaResourceJars",
        "getInputArtProfile",
        "getInputProfileForDexStartupOptimization",
        // Android resources for resource shrinking, the manifest, and a marker of the duplicate class check.
        "getResourceShrinkingParams.getFeatureLinkedResourcesInputFiles",
        "getResourceShrinkingParams.getLinkedResourcesInputDir",
        "getResourceShrinkingParams.getMergedNotCompiledNavigationResourcesInputDir",
        "getResourceShrinkingParams.getMergedNotCompiledResourcesInputDir",
        "getToolParameters.getPackagedManifestDirectory",
        "getDuplicateClassesCheck",
    )

    fun assertAllClassified(project: AndroidProject) {
        project.appendToAppBuildFile(PROBE)
        val inputs = Builder.build(project, ":app:printR8FileInputs").output.lines()
            .filter { it.startsWith(PREFIX) }
            .map { it.removePrefix(PREFIX) }

        assertThat(inputs).containsAtLeastElementsIn(R8TaskInputExtractor.REQUIRED_METHOD_NAMES)
        assertWithMessage(
            "New inputs of AGP's R8 task. Read the ones with keep rules in R8TaskInputExtractor, and list the others in NOT_RULES.",
        ).that(inputs.toSet() - RULES - NOT_RULES).isEmpty()
    }

    private const val PREFIX = "R8 file input: "

    // Reads each AGP class's own getters: Gradle's generated subclass overrides them without annotations.
    // Inputs of @Nested getters count too.
    private val PROBE = """
        def fileInputs
        fileInputs = { Class type, String prefix ->
            def names = []
            for (def c = type; c?.name?.startsWith('com.android.'); c = c.superclass) {
                c.declaredMethods.each { m ->
                    def annotations = m.declaredAnnotations*.annotationType()*.simpleName
                    if (annotations.any { it in ['InputFiles', 'InputFile', 'InputDirectory', 'Classpath', 'CompileClasspath'] }) {
                        names << prefix + m.name
                    } else if ('Nested' in annotations) {
                        names.addAll(fileInputs(m.returnType, prefix + m.name + '.'))
                    }
                }
            }
            names
        }
        tasks.register('printR8FileInputs') {
            def inputs = fileInputs(tasks.named('minifyReleaseWithR8').get().getClass(), '')
            doLast { inputs.each { println '$PREFIX' + it } }
        }
    """.trimIndent()
}
