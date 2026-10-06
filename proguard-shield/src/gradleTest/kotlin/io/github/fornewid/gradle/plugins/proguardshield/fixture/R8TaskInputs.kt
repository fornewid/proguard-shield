package io.github.fornewid.gradle.plugins.proguardshield.fixture

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage

/**
 * The file inputs of AGP's R8 task, by getter. AGP has moved keep rules to new inputs before (AAPT2 and
 * dynamic feature rules in 9.3, #30 and #55). [assertAllClassified] fails when an AGP version adds an input
 * listed in none of the sets, so a new source of keep rules is not missed silently.
 */
internal object R8TaskInputs {

    /** Keep rules the optimization mode reads, in part for getConfigurationFiles ([R8Oracle] checks which part). */
    private val READ = setOf("getConfigurationFiles", "getKeepRulesFiles")

    /** Keep rules that only exist after compiling or processing resources: out of the optimization mode's scope. */
    private val COMPILED = setOf("getGeneratedProguardFile", "getAaptProguardFiles", "getFeatureProguardFiles")

    /** No keep rules for an app's R8 run, or only ones the sets above already have. */
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

        assertThat(inputs).contains("getConfigurationFiles")
        assertWithMessage(
            "New inputs of AGP's R8 task. Read the ones with keep rules that exist without compiling in the optimization " +
                "mode, then list each in R8TaskInputs.",
        ).that(inputs.toSet() - READ - COMPILED - NOT_RULES).isEmpty()
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
