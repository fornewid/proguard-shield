package io.github.fornewid.gradle.plugins.proguardshield.fixture

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner

internal object Builder {

    fun build(
        project: AndroidProject,
        vararg args: String,
    ): BuildResult = runner(project, *args).build()

    fun buildAndFail(
        project: AndroidProject,
        vararg args: String,
    ): BuildResult = runner(project, *args).buildAndFail()

    private fun runner(
        project: AndroidProject,
        vararg args: String,
    ): GradleRunner = GradleRunner.create().apply {
        forwardOutput()
        // Do NOT use withPluginClasspath() - AGP classloader isolation issues.
        // Plugin JAR is injected via buildscript classpath in the test project.
        withProjectDir(project.dir)
        withArguments(args.toList() + "-s")
    }
}
