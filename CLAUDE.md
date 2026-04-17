# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository Status

**Bootstrap phase.** Repo structure mirrors the sibling project [`manifest-shield`](https://github.com/fornewid/manifest-shield). The plugin entry class (`ProGuardShieldPlugin`) is intentionally empty — real functionality will land in follow-up PRs.

Current roadmap (see `README.md`):
1. Plugin module skeleton (extension DSL, variant handler).
2. Approach 1 prototype: `-printconfiguration` baseline + diff.
3. Approach 2-B prototype: R8 task input interception (AGP internal API via reflection).
4. Forbidden pattern checker (regex-based).

The approach for v0: two strategies (`-printconfiguration` + R8 task input interception via reflection) will be implemented as swappable prototypes in follow-up PRs so the author can benchmark and pick one for the final release.

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

`ProGuardShieldPlugin` (package `io.github.fornewid.gradle.plugins.proguardshield`) — currently a no-op; will register tasks via an `AndroidVariantHandler` that hooks into AGP's `onVariants` once feature development begins.

### References

- `manifest-shield` sibling repo uses the same patterns: variant handler, shield-flag interface, baseline file utils, `gradleTest` GradleRunner fixture.

## Publishing

- **Maven Central** via Sonatype Central Portal (`SonatypeHost.CENTRAL_PORTAL`).
- **Gradle Plugin Portal** via `com.gradle.plugin-publish` plugin.
- **In-memory GPG signing** via `ORG_GRADLE_PROJECT_signingInMemoryKey*` environment variables.
- **Workflows**:
  - `build.yml` — Triggered on push to `main` and all pull requests. Runs `:proguard-shield:check`.
  - `publish.yml` — Triggered on main push. Skips SNAPSHOT versions. Publishes to Maven Central + Gradle Plugin Portal, creates a git tag, bumps to next SNAPSHOT.
  - `release.yml` — Manual trigger (`workflow_dispatch`). Creates a release PR that removes `-SNAPSHOT` from version.
  - `release-drafter.yml` — Updates draft release notes on every main push.
- **Branch protection bypass**: Uses `GH_PAT` (Fine-grained PAT) + Ruleset bypass for "Repository admin" to allow SNAPSHOT bump commits.
