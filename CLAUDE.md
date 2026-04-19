# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository Status

**0.0.1 evaluation release.** Repo structure mirrors the sibling project [`manifest-shield`](https://github.com/fornewid/manifest-shield). Two implementations of the same feature ship side-by-side; users run both on real projects so we can confirm they stay bit-identical before dropping one.

- **Approach 1 (accurate)** — `proguardShield{Variant}`: runs R8 with `-printconfiguration`. Uses only public AGP API. Slow.
- **Approach 2-B (fast)** — `proguardShieldFast{Variant}`: reads `ProguardConfigurableTask.configurationFiles` + `generatedProguardFile` via reflection, skips R8 entirely. Much faster but depends on AGP internal class names.

Both paths produce the **same baseline** (`RuleNormalizer` sorts, so R8's output order doesn't matter). The aggregate `proguardShield` / `proguardShieldBaseline` tasks run both paths, and `check` depends on the combined aggregate — any drift from either approach fails the build. Baseline files: `<baselineDir>/<variant>Rules.txt` (accurate) + `<baselineDir>/<variant>FastRules.txt` (fast). Commit both.

Remaining roadmap:
- Pick one path and remove the other based on real-project user feedback.
- Forbidden-rule pattern check (e.g. overly broad `-keep class **`).
- GradleRunner integration tests + AGP 8.0 / 8.x / 9.x version matrix.
- Publish to Maven Central / Gradle Plugin Portal.

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

`ProGuardShieldPlugin` (package `io.github.fornewid.gradle.plugins.proguardshield`) registers four aggregate tasks (`proguardShield`, `proguardShieldBaseline`, `proguardShieldFast`, `proguardShieldFastBaseline`) and delegates per-variant task registration to `internal.AndroidVariantHandler`, which hooks AGP's `onVariants` and wires the accurate + fast per-variant tasks. The combined aggregates (`proguardShield`, `proguardShieldBaseline`) depend on both paths so `check` exercises parity on every build; the `fast` aggregates are kept for users who want to run only approach 2-B.

### References

- `manifest-shield` sibling repo uses the same patterns: variant handler, shield-flag interface, baseline file utils, `gradleTest` GradleRunner fixture.

## Publishing

Distribution targets: Maven Central (via Sonatype Central Portal) and the Gradle Plugin Portal. The full release runbook — required GitHub secrets, workflow triggers, smoke tests — lives in [`RELEASING.md`](RELEASING.md).

Quick reference of the four workflows:
- `build.yml` — on push to `main` + every PR. Runs `:proguard-shield:check` plus an AGP version matrix.
- `publish.yml` — on push to `main`. Skips `-SNAPSHOT` versions. Publishes to both registries, tags the commit, bumps to the next `-SNAPSHOT`.
- `release.yml` — manual `workflow_dispatch`. Opens a PR that strips `-SNAPSHOT`.
- `release-drafter.yml` — updates the draft GitHub Release on every main push / tag.
