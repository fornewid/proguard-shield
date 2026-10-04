package io.github.fornewid.gradle.plugins.proguardshield.internal.optimization

import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import java.io.File

/**
 * Where a rule file comes from.
 *
 * @property label version-less name written to baselines, e.g. `com.example:sdk`, `:lib`, `<agp>`
 * @property detail name shown in failure messages, e.g. `com.example:sdk:1.2.3`, `:app (proguard-rules.pro)`
 */
internal data class RuleOrigin(val label: String, val detail: String)

internal object RuleOrigins {

    const val AGP = "<agp>"
    const val UNRESOLVED = "<unresolved>"

    /** Origin of a dependency that ships keep rules (AAR consumer rules, JAR rules). */
    fun of(id: ComponentIdentifier): RuleOrigin = when (id) {
        is ModuleComponentIdentifier ->
            RuleOrigin("${id.group}:${id.module}", "${id.group}:${id.module}:${id.version}")
        is ProjectComponentIdentifier -> RuleOrigin(id.projectPath, id.projectPath)
        else -> RuleOrigin(UNRESOLVED, id.displayName)
    }

    /**
     * Origin of a rule [file] R8 reads. Library files are looked up by absolute
     * path in [libraryLabels] / [libraryDetails]; the AGP default file and files
     * inside [projectDir] are recognized by location. The AGP default file lives
     * under the module's build directory, so it is checked first.
     */
    fun resolve(
        file: File,
        libraryLabels: Map<String, String>,
        libraryDetails: Map<String, String>,
        projectDir: File,
        projectPath: String,
    ): RuleOrigin {
        val key = file.absolutePath
        libraryLabels[key]?.let { return RuleOrigin(it, libraryDetails[key] ?: it) }
        if ("/default_proguard_files/" in file.absoluteFile.invariantSeparatorsPath) {
            return RuleOrigin(AGP, "$AGP (${file.name})")
        }
        if (file.absoluteFile.startsWith(projectDir.absoluteFile)) {
            return RuleOrigin(projectPath, "$projectPath (${file.name})")
        }
        return RuleOrigin(UNRESOLVED, file.absolutePath)
    }
}
