package io.github.fornewid.gradle.plugins.proguardshield.internal.optimization

import org.gradle.api.artifacts.dsl.DependencyHandler
import org.gradle.api.artifacts.transform.InputArtifact
import org.gradle.api.artifacts.transform.TransformAction
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.artifacts.transform.TransformParameters
import org.gradle.api.artifacts.type.ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity

/**
 * Turns an AAR or JAR into the list of its packages ([LibraryPackages.writeList]). Gradle caches transform
 * outputs per artifact, so each library is read once instead of on every check.
 */
internal abstract class LibraryPackagesTransform : TransformAction<TransformParameters.None> {

    @get:InputArtifact
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val artifact: Provider<FileSystemLocation>

    override fun transform(outputs: TransformOutputs) {
        LibraryPackages.writeList(artifact.get().asFile, outputs.file("packages.txt"))
    }

    companion object {
        /** The `artifactType` of the package lists. */
        const val ARTIFACT_TYPE = "proguard-shield-packages"

        fun register(dependencies: DependencyHandler) {
            listOf("aar", "jar").forEach { type ->
                dependencies.registerTransform(LibraryPackagesTransform::class.java) {
                    from.attribute(ARTIFACT_TYPE_ATTRIBUTE, type)
                    to.attribute(ARTIFACT_TYPE_ATTRIBUTE, ARTIFACT_TYPE)
                }
            }
        }
    }
}
