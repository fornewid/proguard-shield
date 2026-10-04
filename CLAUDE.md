# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository Status

**0.0.x evaluation series.** Repo structure mirrors the sibling project [`manifest-shield`](https://github.com/fornewid/manifest-shield). Each `proguardShield { configuration("<variant>") { … } }` enables modes with flags:

- **optimization** (`optimization`, default `true`) — `proguardShieldOptimization{Variant}` / `proguardShieldOptimizationBaseline{Variant}`, `internal.optimization.ProGuardShieldOptimizationTask`. Reads R8's rule inputs like fullFast (no R8 run), keeps the optimization-blocking rules (`OptimizationBlockingRuleMatcher`) in `<variant>OptimizationBlockingRules.txt`, and with `tree = true` groups them by origin (`RuleOrigins`, `LibraryKeepRuleOrigins` via AGP's `getLibraryKeepRules()`) in `.tree.txt`. External libraries' rules (Maven modules and AAR/JAR file dependencies) that reach code outside the library are added by `LibraryRuleMatcher`, using each library's own packages (`LibraryPackages`, read from the variant's public `runtimeConfiguration`; a pattern that reaches the app's `namespace` is never a library's own). On `check`.
- **fullFast** (`fullFast`, default `false`) — `proguardShieldFullFast{Variant}`, `ProGuardShieldFastListTask`. Full rule baseline `<variant>FullFastRules.txt` from `ProguardConfigurableTask` getters via reflection (`R8TaskInputExtractor`, `IgnoredLibraryKeepRules`). On `check`.
- **full** (`full`, default `false`) — `proguardShieldFull{Variant}`, `ProGuardShieldListTask`. Runs R8 with an injected `-printconfiguration` (only for variants that enable full) and keeps `<variant>FullRules.txt`. **Public AGP API only — this invariant must not change.** Not on `check`.

`forbiddenPatterns` (`internal.forbidden.ForbiddenPatternChecker`) runs only in full and fullFast, on the same normalized input, before the drift comparison. Empty by default.

**Parity (AI / maintainer verification):** `proguardShieldVerifyParity{Variant}` exists when both full and fullFast are enabled; it regenerates both baselines and byte-compares them. Run it on the sample (which enables every mode) and run the gradleTest parity tests whenever you change rule-input extraction (`R8TaskInputExtractor`, `IgnoredLibraryKeepRules`, `LibraryKeepRuleOrigins`) or the supported AGP versions:

```bash
ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew :sample:app:proguardShieldVerifyParity
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

`ProGuardShieldPlugin` (package `io.github.fornewid.gradle.plugins.proguardshield`) registers seven aggregate tasks — `proguardShieldOptimization` / `proguardShieldOptimizationBaseline`, `proguardShieldFull` / `proguardShieldFullBaseline`, `proguardShieldFullFast` / `proguardShieldFullFastBaseline`, `proguardShieldVerifyParity` — and delegates per-variant registration to `internal.AndroidVariantHandler`, which hooks AGP's `onVariants` and registers only the modes a configuration enables. There is no cross-mode aggregate (`proguardShield` / `proguardShieldBaseline` are intentionally unused). Every aggregate validates the configuration names. `check` depends on `proguardShieldOptimization` and `proguardShieldFullFast`.

### References

- `manifest-shield` sibling repo uses the same patterns: variant handler, shield-flag interface, baseline file utils, `gradleTest` GradleRunner fixture.

## Publishing

Distribution targets: Maven Central (via Sonatype Central Portal) and the Gradle Plugin Portal. The full release runbook — required GitHub secrets, workflow triggers, smoke tests — lives in [`RELEASING.md`](RELEASING.md).

Quick reference of the four workflows:
- `build.yml` — on push to `main` + every PR. Runs `:proguard-shield:check` plus an AGP version matrix.
- `publish.yml` — on push to `main`. Skips `-SNAPSHOT` versions. Publishes to both registries, tags the commit, bumps to the next `-SNAPSHOT`.
- `release.yml` — manual `workflow_dispatch`. Opens a PR that strips `-SNAPSHOT`.
- `release-drafter.yml` — updates the draft GitHub Release on every main push / tag.
