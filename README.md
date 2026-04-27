# :shield: ProGuard Shield

[![Maven Central](https://img.shields.io/maven-central/v/io.github.fornewid.proguard-shield/proguard-shield)](https://central.sonatype.com/artifact/io.github.fornewid.proguard-shield/proguard-shield)
[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/io.github.fornewid.proguard-shield)](https://plugins.gradle.org/plugin/io.github.fornewid.proguard-shield)
[![Build](https://github.com/fornewid/proguard-shield/actions/workflows/build.yml/badge.svg)](https://github.com/fornewid/proguard-shield/actions/workflows/build.yml)
[![License](https://img.shields.io/github/license/fornewid/proguard-shield)](LICENSE)

> :warning: This project is in an experimental stage. APIs and behavior may change without notice.

A Gradle plugin that detects unintentional changes to Android's merged ProGuard/R8 rules.

## Why?

R8/ProGuard rules come from your app, AAR consumer rules, AGP defaults, and AAPT2.
Adding a dependency or upgrading AGP can silently inject new rules — disabling
obfuscation, broadening keeps, weakening optimizer behavior — without touching
your project's own `proguard-rules.pro`.

**ProGuard Shield** saves a baseline of the fully-merged rule set and fails the
build when something changes. Optional regex patterns also fail the build when
a known-dangerous rule appears.

## Quick Start

### Step 1: Apply the plugin

Add the plugin to your **application** module's `build.gradle.kts` (library modules are not supported — they don't run R8):

```kotlin
// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("io.github.fornewid.proguard-shield") version "<latest-version>"
}

android {
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
}

proguardShield {
    configuration("release")
}
```

### Step 2: Generate a baseline

```bash
./gradlew proguardShieldBaseline
```

Creates baseline files under `proguardShield/`:

```
proguardShield/
├── releaseRules.txt        # accurate path — what R8 emits via -printconfiguration
└── releaseFastRules.txt    # fast path — same content, captured without running R8
```

Commit both files to version control.

### Step 3: Detect changes

```bash
./gradlew check
```

The `check` lifecycle runs the **fast** path (no R8 invocation, seconds instead
of minutes). If the merged rule set differs from the baseline, the build fails
with a diff:

```diff
ProGuard/R8 rules changed in :app for release.
+ -keep class com.example.NewlyAdded { *; }

If this is intentional, re-baseline using ./gradlew :app:proguardShieldFastBaselineRelease
Or use ./gradlew proguardShieldBaseline to re-baseline in entire project.
```

The recommended re-baseline command is the aggregate `./gradlew proguardShieldBaseline`
— it regenerates **both** baseline files together so the fast and accurate paths
stay in sync.

## Two implementations

Internally `proguard-shield` ships two extraction strategies. Both produce
bit-identical baselines when parity holds, and both run the same forbidden-pattern
check. They differ in how they reach the rules:

| Task family | What runs | Speed | AGP coupling |
|---|---|---|---|
| `proguardShield{Variant}` | full R8 with `-printconfiguration` | slow (minutes on real apps) | public AGP API only |
| `proguardShieldFast{Variant}` | reads R8 inputs directly via reflection | fast (seconds) | uses AGP internal class (`ProguardConfigurableTask`) |

Daily `check` runs only the fast path. After every AGP upgrade, run the parity
verification task to confirm the fast path's reflection contract still holds:

```bash
./gradlew :app:proguardShieldVerifyParity
```

This regenerates both baselines and byte-compares them. Divergence means the
fast path can no longer be trusted on this AGP version — fall back to the
accurate `proguardShield` task and please file an issue.

## Forbidden patterns

Empty by default. Declare regex patterns that fail the build whenever a
matching rule appears in the merged input — useful for projects that want
to ban overly broad keeps, disabled obfuscation, and similar foot-guns.

```kotlin
proguardShield {
    configuration("release") {
        forbiddenPatterns = listOf(
            "-keep\\s+class\\s+\\*\\*",     // overly broad keeps
            "-dontobfuscate",                // obfuscation disabled
            "-dontshrink",                   // shrinking disabled
        )
    }
}
```

Each pattern is matched against the directive head of every rule unit
(continuation lines of multi-line directives like `-keepattributes A,\nB,\nC`
are joined; bodies of `-keep ... { ... }` blocks are excluded). Forbidden
detection runs **before** drift detection — re-baselining cannot silence
a forbidden match.

## Configuration

```kotlin
proguardShield {
    baselineDir.set("custom-dir")  // default: "proguardShield"
    configuration("release") {
        forbiddenPatterns = listOf(
            "-dontobfuscate",
        )
    }
}
```

| Option | Default | Description |
|---|---|---|
| `baselineDir` | `"proguardShield"` | Directory (relative to the module) where baseline files are written. |
| `forbiddenPatterns` | `[]` | Regex patterns that fail the build whenever a matching rule appears. |

## Requirements

- Android Gradle Plugin 8.0.0+
- Gradle 8.0+
- `com.android.application` modules only — library modules don't run R8

## AI Agent Guide

If you use an AI coding assistant (Claude Code, GitHub Copilot, Gemini, Cursor, etc.),
reference the [setup guide](docs/setup-guide.md.txt) for accurate installation
instructions and common pitfalls.

## Acknowledgements

Sibling project to [manifest-shield](https://github.com/fornewid/manifest-shield)
and [highlander](https://github.com/fornewid/highlander); shares their build,
release, and integration-test infrastructure. Releasing details live in
[`RELEASING.md`](RELEASING.md).

## License

[Apache License 2.0](LICENSE)
