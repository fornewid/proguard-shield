# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository Status

**0.0.x evaluation series.** Repo structure mirrors the sibling project [`manifest-shield`](https://github.com/fornewid/manifest-shield). Two implementations of the same feature ship side-by-side; users run both on real projects so we can confirm they stay bit-identical before dropping one.

- **full** (formerly accurate) — `proguardShieldFull{Variant}`, `ProGuardShieldListTask`: runs R8 with `-printconfiguration`. Uses only public AGP API — this invariant must not change. Slow.
- **fullFast** (formerly fast) — `proguardShieldFullFast{Variant}`, `ProGuardShieldFastListTask`: reads `ProguardConfigurableTask` rule-file getters via reflection (`R8TaskInputExtractor`, `IgnoredLibraryKeepRules`), skips R8 entirely. Much faster but depends on AGP internal class names.

Both modes produce the **same baseline** when parity holds (`RuleNormalizer` sorts by rule unit so order doesn't matter). Baseline files: `<baselineDir>/<variant>FullRules.txt` (full) + `<baselineDir>/<variant>FullFastRules.txt` (fullFast). Commit both.

Lifecycle:
- `./gradlew :app:proguardShieldFullBaseline :app:proguardShieldFullFastBaseline` — first install / re-baseline. There is no combined baseline aggregate.
- `./gradlew :app:proguardShieldVerifyParity` — first install + every AGP upgrade. Regenerates both baselines and byte-compares them; fails if they diverge.
- `./gradlew check` — daily CI. Runs only `proguardShieldFullFast{Variant}`; full is reserved for explicit invocation so CI does not pay the R8 cost on every build.

Both list tasks run a shared `internal.forbidden.ForbiddenPatternChecker` over the same normalized rule input before the drift comparison. The pattern set is supplied per variant via `proguardShield.configuration("release").forbiddenPatterns`. Empty by default — the plugin ships no policy. Running both checks under both modes is what keeps the `proguardShieldVerifyParity` invariant honest.

Remaining roadmap:
- Pick one path and remove the other based on real-project user feedback.

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

`ProGuardShieldPlugin` (package `io.github.fornewid.gradle.plugins.proguardshield`) registers five aggregate tasks (`proguardShieldFull`, `proguardShieldFullBaseline`, `proguardShieldFullFast`, `proguardShieldFullFastBaseline`, `proguardShieldVerifyParity`) and delegates per-variant task registration to `internal.AndroidVariantHandler`, which hooks AGP's `onVariants`. Each aggregate is mode-specific; there is no combined aggregate (`proguardShield` / `proguardShieldBaseline` are intentionally unused in 0.0.5). `proguardShieldVerifyParity{Variant}` regenerates both baselines and byte-compares them via `internal.verify.ProGuardShieldVerifyParityTask`. `check` is wired to `proguardShieldFullFast` only.

### References

- `manifest-shield` sibling repo uses the same patterns: variant handler, shield-flag interface, baseline file utils, `gradleTest` GradleRunner fixture.

## Publishing

Distribution targets: Maven Central (via Sonatype Central Portal) and the Gradle Plugin Portal. The full release runbook — required GitHub secrets, workflow triggers, smoke tests — lives in [`RELEASING.md`](RELEASING.md).

Quick reference of the four workflows:
- `build.yml` — on push to `main` + every PR. Runs `:proguard-shield:check` plus an AGP version matrix.
- `publish.yml` — on push to `main`. Skips `-SNAPSHOT` versions. Publishes to both registries, tags the commit, bumps to the next `-SNAPSHOT`.
- `release.yml` — manual `workflow_dispatch`. Opens a PR that strips `-SNAPSHOT`.
- `release-drafter.yml` — updates the draft GitHub Release on every main push / tag.
