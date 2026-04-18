package io.github.fornewid.gradle.plugins.proguardshield

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import javax.inject.Inject

/**
 * Extension for [ProGuardShieldPlugin] which leverages [ProGuardShieldConfiguration].
 */
public open class ProGuardShieldPluginExtension @Inject constructor(
    private val objects: ObjectFactory,
) {
    /** Name of the directory to store baseline files (default: "proguardShield"). */
    public val baselineDir: Property<String> = objects.property(String::class.java).convention("proguardShield")

    internal val configurations = objects.domainObjectContainer(ProGuardShieldConfiguration::class.java)

    public fun configuration(name: String) {
        configurations.add(newConfiguration(name))
    }

    /**
     * Supports configuration in build files.
     *
     * proguardShield {
     *   baselineDir.set("custom-dir")
     *   configuration("release") {
     *   }
     * }
     */
    public fun configuration(name: String, config: Action<ProGuardShieldConfiguration>) {
        configurations.add(newConfiguration(name, config))
    }

    private fun newConfiguration(
        name: String,
        config: Action<ProGuardShieldConfiguration>? = null,
    ): ProGuardShieldConfiguration {
        return objects.newInstance(ProGuardShieldConfiguration::class.java, name).apply {
            config?.execute(this)
        }
    }
}
