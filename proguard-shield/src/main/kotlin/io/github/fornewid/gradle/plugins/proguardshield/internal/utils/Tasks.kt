package io.github.fornewid.gradle.plugins.proguardshield.internal.utils

import org.gradle.api.Task

internal object Tasks {
    fun Task.declareCompatibilities() {
        doNotTrackState("Checks or rewrites the committed baseline")
    }
}
