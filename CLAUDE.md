# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository Status

**0.0.x evaluation series.** Repo structure mirrors the sibling project [`manifest-shield`](https://github.com/fornewid/manifest-shield). Each `proguardShield { configuration("<variant>") { … } }` registers `proguardShield{Variant}` / `proguardShieldBaseline{Variant}` (`internal.optimization.ProGuardShieldOptimizationTask`). It reads the rules R8 reads that exist without compiling the variant (no R8 run): `variant.proguardFiles`, keep-rule source sets, external libraries' keep rules (an `android-filtered-proguard-rules` artifact view of `runtimeConfiguration`, project modules left out) and AGP's string rules. It keeps the optimization-blocking rules (`OptimizationBlockingRuleMatcher`) in `<variant>OptimizationBlockingRules.txt`, and with `tree = true` groups them by origin (`RuleOrigins.byPath` of that artifact view) in `.tree.txt`. External libraries' rules (Maven modules and AAR/JAR file dependencies) that reach code outside the library are added by `LibraryRuleMatcher`, using each library's own packages (`LibraryPackages`, read from the variant's public `runtimeConfiguration` through a cached artifact transform, `LibraryPackagesTransform`; a pattern that reaches the app's `namespace` is never a library's own, and a configuration's `excludePackages` count as every library's own); a rule whose class names reach no package of that classpath or the namespace matches nothing and is skipped, as is a library rule that an unlisted source (the app's or AGP's rules, or the library owning its target) also declares. `RuleNormalizer` puts each rule unit on one line with whitespace normalized; rules are compared in that form. On `check`.

**Verification (AI / maintainer):** the optimization mode never compiles the variant and never runs R8, so whether it reads what R8 reads is checked in gradleTest: `R8Oracle` compares the list with R8's own `-printconfiguration` output, without the sources the mode leaves out, and `R8TaskInputs` fails when an AGP version adds a file input to the R8 task. Run the gradleTests whenever you change how rules are read (`AndroidVariantHandler`, `R8TaskInputExtractor`, `IgnoredLibraryKeepRules`) or the supported AGP versions:

```bash
ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :proguard-shield:gradleTest
```

## Build & Test Commands

```bash
# Build the plugin
./gradlew :proguard-shield:compileKotlin

# Run unit tests only
./gradlew :proguard-shield:test

# Run integration tests (GradleRunner-based, requires ANDROID_HOME or local.properties)
./gradlew :proguard-shield:gradleTest

# Run all tests + API compatibility check
./gradlew :proguard-shield:check

# Regenerate API dump after public API changes
./gradlew :proguard-shield:apiDump
```

Note: CI uses JDK 17 (Zulu). Locally, Android Studio's bundled JDK works. If `JAVA_HOME` is not set, use:
```bash
JAVA_HOME="/path/to/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew ...
```

## Architecture

The repo is a Gradle **included build**: the root project pulls in the plugin module (`proguard-shield/`) via `includeBuild`, and the `sample/` Android app depends on the plugin.

### Module Structure

- `proguard-shield/` — The publishable Gradle plugin (included build).
- `sample/app/` — Android app demonstrating the plugin (`isMinifyEnabled = true`).
- `sample/module1/` — Android library that contributes `consumer-rules.pro`, used as a fixture for AAR consumer rule collection.

### Plugin Entry

`ProGuardShieldPlugin` (package `io.github.fornewid.gradle.plugins.proguardshield`) registers two aggregate tasks — `proguardShield` / `proguardShieldBaseline` — and delegates per-variant registration to `internal.AndroidVariantHandler`, which hooks AGP's `onVariants`. Both aggregates validate the configuration names. `check` depends on `proguardShield`.

### References

- `manifest-shield` sibling repo uses the same patterns: variant handler, shield-flag interface, baseline file utils, `gradleTest` GradleRunner fixture.

## Publishing

Distribution targets: Maven Central (via Sonatype Central Portal) and the Gradle Plugin Portal. The full release runbook — required GitHub secrets, workflow triggers, smoke tests — lives in [`RELEASING.md`](RELEASING.md).

Quick reference of the workflows:
- `build.yml` — on push to `main` + every PR. Runs `:proguard-shield:check` plus an AGP version matrix.
- `publish.yml` — on push to `main`. Skips `-SNAPSHOT` versions. Publishes to both registries, tags the commit, bumps to the next `-SNAPSHOT`.
- `release.yml` — manual `workflow_dispatch`. Opens a PR that strips `-SNAPSHOT`.
- `release-drafter.yml` — updates the draft GitHub Release on every main push / tag.
- `newest-agp.yml` — every PR + manual. Runs the AGP 9 gradleTests on the newest AGP and Gradle (previews included), also as AGP 10 would behave. If only this check fails on a PR, run it on main (manually) to see whether a new AGP or Gradle broke it rather than the PR.
- `agp-release.yml` — daily + manual. For each new AGP version on Google Maven (the newest, and the newest stable), runs `newest-agp.yml` on main and opens an `agp-release` issue with the result and a checklist, for whoever takes the verification.
