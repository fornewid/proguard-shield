# :shield: ProGuard Shield

[![Maven Central](https://img.shields.io/maven-central/v/io.github.fornewid.proguard-shield/proguard-shield)](https://central.sonatype.com/artifact/io.github.fornewid.proguard-shield/proguard-shield)
[![Gradle Plugin Portal](https://img.shields.io/gradle-plugin-portal/v/io.github.fornewid.proguard-shield)](https://plugins.gradle.org/plugin/io.github.fornewid.proguard-shield)
[![Build](https://github.com/fornewid/proguard-shield/actions/workflows/build.yml/badge.svg)](https://github.com/fornewid/proguard-shield/actions/workflows/build.yml)
[![License](https://img.shields.io/github/license/fornewid/proguard-shield)](LICENSE)

> :warning: This project is in an experimental stage. APIs and behavior may change without notice.

A Gradle plugin that flags ProGuard/R8 rules that block R8's optimization, and library rules that reach beyond the library.

## Why?

R8/ProGuard rules come from your app, AAR consumer rules, AGP defaults, and AAPT2.
Adding a dependency or upgrading a library can silently inject rules that
disable obfuscation, keep every class, or otherwise weaken R8 — without
touching your project's own `proguard-rules.pro`.

**ProGuard Shield** keeps a baseline of the rules that block R8's optimization,
can group them by the library that adds each one, and fails `check` when a new
one appears.

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

Creates `proguardShield/releaseOptimizationBlockingRules.txt`: the AGP version
and R8 mode properties, then the rules that block R8's optimization (often none).
Commit it, so the next such rule — or an AGP or R8 mode change — shows up as a
failure.

### Step 3: Detect changes

```bash
./gradlew check
```

`check` reads the rules without running R8 or compiling the variant, and fails
when an optimization-blocking rule appears or disappears:

```diff
ProGuard Shield: optimization-blocking rules changed in :app (release).
+ -dontobfuscate

If this is intentional, re-baseline using ./gradlew :app:proguardShieldOptimizationBaselineRelease
Or use ./gradlew proguardShieldOptimizationBaseline to re-baseline in entire project.
```

## Tasks

| Task | Does | On `check` |
|---|---|---|
| `proguardShieldOptimization{Variant}` | Fails when an optimization-blocking rule appears or disappears | yes |
| `proguardShieldOptimizationBaseline{Variant}` | Writes `<variant>OptimizationBlockingRules.txt` (+ `.tree.txt`) | no |

`proguardShieldOptimization` and `proguardShieldOptimizationBaseline` run them
for every configuration. Besides public AGP API, they read an AGP-internal
artifact type and two values of the R8 task.

## Optimization-blocking rules

The optimization mode reads the rules that exist without compiling the variant,
so `check` doesn't compile it: the app's rule files and AGP's default file,
keep-rule source sets (AGP 9.1+), external libraries' consumer rules (Maven
modules and AAR/JAR files), and the rules AGP passes to R8 as strings. It
doesn't read the consumer rules of the project's own modules, including dynamic
feature modules, rules generated while compiling, or AAPT2 rules: those exist
only after compiling or processing resources.

It lists these rules:

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
- an app-wide option that reduces what R8 does: `-keepparameternames`,
  `-keepkotlinmetadata`, `-dontrepackage`, or `-keeppackagenames` reaching
  other packages

On AGP 9.5 and later, AGP removes app-wide options such as `-dontobfuscate` and
`-dontrepackage` from AARs' consumer rules
(`android.r8.globalOptionsInConsumerRules.disallowed`, on by default), so they
are not listed.

A library's rule is not listed when it:

- targets its own packages or another module of its Maven group, without
  reaching the app's namespace
- keeps app classes through its own types or an annotation:
  `-keep class * extends androidx.room.RoomDatabase { void <init>(); }`
- keeps a single named class without members, or lists only some members:
  `-keep class kotlin.Metadata`, `{ <init>(); }`, `{ volatile <fields>; }`
- has both `allowshrinking` and `allowobfuscation`
- is also declared by the app's rule files, AGP's default file or the library
  that owns its target, so dropping it wouldn't change R8's output
- matches no class on the runtime classpath or in the app's namespace:
  `-keep class com.absent.** { *; }`. Classes of the app and the project's
  modules outside that namespace aren't read, and the type in `extends` or
  `implements` isn't checked.
- is an `-assume*` rule, or an app-wide option that doesn't reduce what R8 does,
  such as `-ignorewarnings`, `-printmapping`, `-repackageclasses` or an option
  R8 ignores

Each rule is written on one line with its whitespace normalized, so the same
rule written with other spacing is one entry, and a library that only reformats
its rules doesn't change the list.

The list starts with what decides how R8 reads these rules: the AGP version, the
R8 version when `android.r8.versionOverride` sets one (AGP 9.5+), and the R8
mode properties as set (`default` when unset).

```
# agp=9.4.1
# android.enableR8.fullMode=default
# android.r8.strictFullModeForKeepRules=default
# android.r8.globalOptionsInConsumerRules.disallowed=default

-keep class com.google.gson.** { *; }
```

With `tree = true`, `<variant>OptimizationBlockingRules.tree.txt` groups the
rules by origin (Maven versions omitted, so upgrading a library alone does not change it):

```
[:app]
-keepattributes *

[com.example:analytics]
-keep class com.google.gson.** { *; }
```

and `check` failures show the changed rules under their origin:

```diff
ProGuard Shield: optimization-blocking rules changed in :app (release).
  [com.example:analytics]
+ -keep class com.google.gson.** { *; }
```

Origins are the module path for the module's own files (`:app`),
`group:artifact` for external libraries, the file name for file dependencies
(`files("libs/x.aar")` → `x.aar`), `<agp>` for the AGP default rule file and
the rules AGP adds itself, and `<unresolved>` for anything else, such as a rule
file outside the module. Libraries excluded with AGP's
`optimization.keepRules.ignoreFrom` are skipped, like R8 does.

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
| `optimization` | `true` | Track optimization-blocking rules (with their origins when `tree = true`). On `check`. `false` registers no tasks for this configuration. |
| `tree` | `false` | Also write the by-origin tree. |

## Migrating from 0.0.11

- Rules are written on one line with their whitespace normalized.
- A library's rule that matches no class, or that the app's rule files, AGP's
  default file or the library owning its target also declare, is no longer
  listed.
- `-assume*` rules and app-wide options that don't reduce what R8 does, such as
  `-printconfiguration` and `-ignorewarnings`, are no longer listed. If `check`
  fails once, run `./gradlew proguardShieldOptimizationBaseline` and commit the
  result.

## Migrating from 0.0.10

- The full and fullFast modes, `proguardShieldVerifyParity` and
  `forbiddenPatterns` are removed. Delete `full`, `fullFast` and
  `forbiddenPatterns` from your `configuration(...)` blocks, and the
  `*FullRules.txt` and `*FullFastRules.txt` baseline files.
- A library's `-printconfiguration` is now listed like other app-wide options.
- The optimization mode no longer compiles the variant, so it skips the sources
  listed under [Optimization-blocking rules](#optimization-blocking-rules). If
  `check` fails once, run `./gradlew proguardShieldOptimizationBaseline` and
  commit the result.

## Migrating from 0.0.9

- On AGP 9.3+, the optimization and fullFast modes read the rules of dynamic
  feature modules again. If `check` fails after the upgrade, re-run the
  baselines and commit the result.
- The optimization and fullFast modes also read the rules AGP passes to R8 as
  strings (JaCoCo's keep rules when testing a minified build with coverage).
- With `android.r8.versionOverride` set (AGP 9.5+), the optimization list
  records it.

## Migrating from 0.0.7

- The optimization list starts with the AGP version and R8 mode properties, so
  `check` fails once after the upgrade: run
  `./gradlew proguardShieldOptimizationBaseline` and commit the result.
- AAR/JAR file dependencies (`files("libs/x.aar")`) are now checked like
  external libraries, and `.tree.txt` lists their rules under the file name
  (`[x.aar]`) instead of `<unresolved>`.
- On AGP 9.3+, the fullFast baseline now includes AAPT2-generated keep rules
  (emitted unless R8's optimized resource shrinking runs), as on earlier AGP
  versions.

## Migrating from 0.0.6

- The optimization mode also lists an external library's rules that reach code
  outside the library (see [Optimization-blocking rules](#optimization-blocking-rules)).
  If your libraries ship such rules, `check` fails after the upgrade — run
  `./gradlew proguardShieldOptimizationBaseline` and commit the result.
- Failure messages show only the diff. With `tree = true`, the changed rules are
  grouped by origin.
- Keep options with spaces around the commas (`-keep, allowobfuscation class * { *; }`)
  are now read like the compact form (`-keep,allowobfuscation`).

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

ProGuard Shield compares the text of the rules. An AGP upgrade or an R8
mode property change fails `check` once, but it cannot tell what the change does
to R8's output, or whether your rules are sufficient (new reflection without a
matching keep rule produces no diff). An R8 version set apart from AGP's is
recorded only through `android.r8.versionOverride`. After such changes, test
your release build.

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
