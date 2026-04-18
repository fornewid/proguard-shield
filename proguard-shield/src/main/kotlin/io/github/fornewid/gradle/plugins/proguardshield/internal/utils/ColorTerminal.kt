package io.github.fornewid.gradle.plugins.proguardshield.internal.utils

internal object ColorTerminal {
    const val ANSI_RESET = "\u001B[0m"
    const val ANSI_RED = "\u001B[31m"
    const val ANSI_GREEN = "\u001B[32m"
    const val ANSI_YELLOW = "\u001B[33m"

    fun colorify(ansiColor: String?, str: String): String = if (ansiColor != null) {
        ansiColor + str + ANSI_RESET
    } else {
        str
    }
}
