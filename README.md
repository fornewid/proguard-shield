# :shield: ProGuard Shield

[![Maven Central](https://img.shields.io/maven-central/v/io.github.fornewid.proguard-shield/proguard-shield)](https://central.sonatype.com/artifact/io.github.fornewid.proguard-shield/proguard-shield)
[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/io.github.fornewid.proguard-shield)](https://plugins.gradle.org/plugin/io.github.fornewid.proguard-shield)
[![Build](https://github.com/fornewid/proguard-shield/actions/workflows/build.yml/badge.svg)](https://github.com/fornewid/proguard-shield/actions/workflows/build.yml)
[![License](https://img.shields.io/github/license/fornewid/proguard-shield)](LICENSE)

> :warning: This project is in an experimental stage. APIs and behavior may change without notice.

A Gradle plugin that guards Android's merged ProGuard/R8 rules: it flags rules that block R8's optimization, and can keep a baseline of the full rule set.

## Why?

R8/ProGuard rules come from your app, AAR consumer rules, AGP defaults, and AAPT2.
Adding a dependency or upgrading a library can silently inject rules that
disable obfuscation, keep every class, or otherwise weaken R8 — without
touching your project's own `proguard-rules.pro`.

**ProGuard Shield** keeps a baseline of the rules that block R8's optimization,
can group them by the library that adds each one, and fails `check` when a new
one appears.
Optional modes also keep a baseline of the full merged rule set.

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

`configuration(...)` takes a variant name. With product flavors, pass the full
variant name (e.g. `configuration("freeRelease")`), not the build type — add one
`configuration(...)` per variant you want to guard.

### Step 2: Generate a baseline

```bash
./gradlew proguardShieldOptimizationBaseline
```

Creates `proguardShield/releaseOptimizationBlockingRules.txt`. It is empty
when no rule blocks R8's optimization — commit it anyway, so the next rule that
does shows up as a failure.

### Step 3: Detect changes

```bash
./gradlew check
```

`check` reads R8's rule inputs without running R8 (it still runs the tasks that
produce them, including compilation of the variant) and fails when an
optimization-blocking rule appears or disappears:

```diff
ProGuard Shield: optimization-blocking rules changed in :app (release).
+ -dontobfuscate

If this is intentional, re-baseline using ./gradlew :app:proguardShieldOptimizationBaselineRelease
Or use ./gradlew proguardShieldOptimizationBaseline to re-baseline in entire project.
```

## Modes

Each `configuration(...)` turns modes on and off with the flags in
[Configuration](#configuration):

| Mode | Default | Tasks | Baseline file | On `check` | AGP coupling |
|---|---|---|---|---|---|
| optimization | on | `proguardShieldOptimization{Variant}`, `proguardShieldOptimizationBaseline{Variant}` | `<variant>OptimizationBlockingRules.txt` (+ `.tree.txt`) | yes | AGP internal class (`ProguardConfigurableTask`) |
| fullFast | off | `proguardShieldFullFast{Variant}`, `proguardShieldFullFastBaseline{Variant}` | `<variant>FullFastRules.txt` | yes | AGP internal class (`ProguardConfigurableTask`) |
| full | off | `proguardShieldFull{Variant}`, `proguardShieldFullBaseline{Variant}` | `<variant>FullRules.txt` | no | public AGP API only |

`proguardShieldOptimization`, `proguardShieldFullFast` and `proguardShieldFull`
(and their `…Baseline` counterparts) run the mode for every configuration that
enables it, and do nothing otherwise.
Only full runs R8: it adds a `-printconfiguration` file to R8's inputs for the
variants that enable it, and the other modes leave R8's inputs untouched. full is
the reference — it uses only public AGP API and records exactly what R8 prints.

## Optimization-blocking rules

The optimization mode lists these rules:

- `-dontobfuscate`, `-dontshrink`, `-dontoptimize`
- `-keepattributes` with no filter or a bare `*`
- a `-keep` rule on every class (`*`, `**`, …) that is not scoped by an
  annotation, `extends` or `implements` — for `-keepclassmembers` and
  `-keepclasseswithmembers`, only when the member specs are unrestricted
  (`*`, `<fields>`, `<methods>`, `<init>(...)`)

It also lists an external library's rule when it reaches code outside the
library:

- a whole package: `-keep class com.google.gson.** { *; }`
- all fields or methods of a class:
  `-keep class androidx.recyclerview.widget.RecyclerView { *; }`
- every class that extends or implements a type outside the library:
  `-keep class * extends android.app.Activity`
- `-assumenosideeffects`, `-assumevalues`
- an app-wide option such as `-ignorewarnings`, except `-dontwarn`, `-dontnote`
  and `-keepattributes` with a filter

A library's rule is not listed when it:

- targets its own packages or another module of its Maven group
- keeps app classes through its own types or an annotation:
  `-keep class * extends androidx.room.RoomDatabase { void <init>(); }`
- keeps a single named class without members, or lists only some members:
  `-keep class kotlin.Metadata`, `{ <init>(); }`, `{ volatile <fields>; }`
- has both `allowshrinking` and `allowobfuscation`

With `tree = true`, `<variant>OptimizationBlockingRules.tree.txt` groups the
rules by origin (versions omitted, so upgrading a library alone does not change it):

```
[:app]
-keepattributes *

[com.example:analytics]
-dontobfuscate
```

and `check` failures show the changed rules under their origin:

```diff
ProGuard Shield: optimization-blocking rules changed in :app (release).
  [com.example:analytics]
+ -dontobfuscate
```

Origins are the module path for project dependencies and the module's own
files, `group:artifact` for external libraries, `<agp>` for the AGP default
rule file, and `<unresolved>` for anything else. Libraries excluded with AGP's
`optimization.keepRules.ignoreFrom` are skipped, like R8 does.

## Forbidden patterns

Used by the full and fullFast modes. Empty by default. Declare regex patterns
that fail the build whenever a matching rule appears in the merged input:

```kotlin
proguardShield {
    configuration("release") {
        fullFast = true
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

## Verifying fullFast against full

`proguardShieldVerifyParity{Variant}` (registered when both full and fullFast are
enabled) regenerates both baselines and byte-compares them. It is a
verification aid for maintainers and AI agents working on the plugin, not part
of the everyday workflow.

## Configuration

```kotlin
proguardShield {
    baselineDir.set("custom-dir")  // default: "proguardShield"
    configuration("release") {
        tree = true
    }
}
```

| Option | Default | Description |
|---|---|---|
| `baselineDir` | `"proguardShield"` | Directory (relative to the module) where baseline files are written. |
| `optimization` | `true` | Track optimization-blocking rules (with their origins when `tree = true`). On `check`. |
| `tree` | `false` | Also write the by-origin tree for the optimization mode. |
| `fullFast` | `false` | Keep a full rule baseline read from R8's inputs without running R8. On `check`. |
| `full` | `false` | Keep a full rule baseline as R8 prints it (runs R8, public AGP API only). Not on `check`. |
| `forbiddenPatterns` | `[]` | Regex patterns that fail the full / fullFast modes whenever a matching rule appears. |

## Migrating from 0.0.6

- The optimization mode also lists an external library's rules that reach code
  outside the library (see [Optimization-blocking rules](#optimization-blocking-rules)).
  If your libraries ship such rules, `check` fails after the upgrade — run
  `./gradlew proguardShieldOptimizationBaseline` and commit the result.
- Failure messages show only the diff. With `tree = true`, the changed rules are
  grouped by origin.

## Migrating from 0.0.5

- `configuration("release")` now runs only the optimization mode. To keep the
  0.0.5 behavior, set `full = true` and `fullFast = true`.
- `check` runs the optimization mode by default (previously fullFast).
- `proguardShieldFull`, `proguardShieldFullFast` and `proguardShieldVerifyParity`
  pass without checking anything until full / fullFast are enabled.
- `check` now fails on an unknown `configuration(...)` name (previously only the
  full tasks validated it).
- `forbiddenPatterns` applies only to the full and fullFast modes.

## Migrating from 0.0.4

0.0.5 renames the two modes; their behavior is unchanged.

| 0.0.4 | 0.0.5 |
|---|---|
| `proguardShield{Variant}` / `proguardShieldBaseline{Variant}` (accurate) | `proguardShieldFull{Variant}` / `proguardShieldFullBaseline{Variant}` |
| `proguardShieldFast{Variant}` / `proguardShieldFastBaseline{Variant}` | `proguardShieldFullFast{Variant}` / `proguardShieldFullFastBaseline{Variant}` |
| `proguardShield`, `proguardShieldBaseline` (aggregates) | `proguardShieldFull`, `proguardShieldFullBaseline` (no combined baseline aggregate) |
| `proguardShieldFast`, `proguardShieldFastBaseline` (aggregates) | `proguardShieldFullFast`, `proguardShieldFullFastBaseline` |
| `<variant>Rules.txt` | `<variant>FullRules.txt` |
| `<variant>FastRules.txt` | `<variant>FullFastRules.txt` |

Rename the committed baseline files (`git mv`), or regenerate them with
`./gradlew proguardShieldFullBaseline proguardShieldFullFastBaseline`.

## Limitations

ProGuard Shield compares the text of the merged rule set. It does not detect
changes in how R8 interprets unchanged rules — such as AGP's
`strictFullModeForKeepRules`, full vs compat mode, or AGP/R8 upgrades — and it
does not tell whether your rules are sufficient (new reflection without a
matching keep rule produces no diff). After such changes, test your release
build.

On AGP 9, the optimization and fullFast modes do not read the rules of dynamic
feature modules or AAPT2-generated rules, which AGP keeps in separate R8 inputs;
the full mode sees everything R8 sees.

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
