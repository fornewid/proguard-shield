package io.github.fornewid.gradle.plugins.proguardshield.internal.optimization

import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import java.io.File
import java.io.Serializable

/**
 * Where a rule file comes from.
 *
 * @property label version-less name written to baselines, e.g. `com.example:sdk`, `:lib`, `<agp>`
 */
internal data class RuleOrigin(val label: String) : Serializable

internal object RuleOrigins {

    const val AGP = "<agp>"
    const val UNRESOLVED = "<unresolved>"

    /** Origin of a dependency that ships keep rules (AAR consumer rules, JAR rules). */
    fun of(id: ComponentIdentifier): RuleOrigin = when (id) {
        is ModuleComponentIdentifier -> RuleOrigin("${id.group}:${id.module}")
        is ProjectComponentIdentifier -> RuleOrigin(id.projectPath)
        else -> RuleOrigin(UNRESOLVED)
    }

    /**
     * Origin of a rule [file] R8 reads. Library files are looked up by absolute
     * path in [libraryOrigins]; the AGP default file and files inside
     * [projectDir] are recognized by location. The AGP default file lives under
     * the module's build directory, so it is checked first.
     */
    fun resolve(
        file: File,
        libraryOrigins: Map<String, RuleOrigin>,
        projectDir: File,
        projectPath: String,
    ): RuleOrigin {
        libraryOrigins[file.absolutePath]?.let { return it }
        if ("/default_proguard_files/" in file.absoluteFile.invariantSeparatorsPath) {
            return RuleOrigin(AGP)
        }
        if (file.absoluteFile.startsWith(projectDir.absoluteFile)) {
            return RuleOrigin(projectPath)
        }
        return RuleOrigin(UNRESOLVED)
    }
}
