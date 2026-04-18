# proguard-shield

A Gradle plugin that detects unintentional changes to Android's merged ProGuard/R8 rules.

> Status: **0.0.1 (evaluation release).** Two implementations ship side-by-side so users can confirm parity on their own projects before a single approach is locked in.

## What it does

Snapshots the full ProGuard/R8 rule set for your `release` build as a baseline, then fails the build whenever subsequent builds produce a different set of rules — catching silent dependency-driven rule changes (new AAR consumer rules, R8 default shifts, etc.) before they hit production.

## Two implementations

Both produce bit-identical baselines (same rules, same order); they differ in how they gather the rules.

| Task family | What runs | Speed | AGP coupling |
|---|---|---|---|
| `proguardShield{Variant}` (accurate) | full R8 with `-printconfiguration` | slow (minutes on real apps) | public AGP API only |
| `proguardShieldFast{Variant}` | R8 inputs read directly via reflection, R8 itself skipped | fast (seconds) | uses AGP internal class (`ProguardConfigurableTask`) |

Version 0.0.1 runs **both on every `check`** so you can commit both baseline files and verify on your own project that the fast path holds parity with the accurate one. A future release will keep just one path.

## Install

```kotlin
// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("io.github.fornewid.proguard-shield") version "0.0.1"
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

Requires AGP 8.0+. `com.android.application` modules only (library modules do not run R8).

## Usage

### First-time baseline

```bash
./gradlew :app:proguardShieldBaseline
# Writes both:
#   app/proguardShield/releaseRules.txt       (accurate path)
#   app/proguardShield/releaseFastRules.txt   (fast path)
```

Commit both files to version control.

### Check for drift (every build)

```bash
./gradlew :app:check
# Runs both proguardShieldRelease and proguardShieldFastRelease.
# If either detects a rule change against its baseline, the build fails.
```

### Re-baseline after an intentional rule change

Same as first-time baseline — overwrites both files.

### Run only one path (optional)

```bash
./gradlew :app:proguardShieldFast            # fast path, all declared variants
./gradlew :app:proguardShieldFastBaseline    # fast baseline only
./gradlew :app:proguardShieldRelease         # accurate path, single variant
```

## Roadmap

- [x] Repository bootstrap (Gradle wrapper, workflows, sample module layout)
- [x] Plugin module skeleton (extension DSL, variant handler)
- [x] Approach 1: `-printconfiguration` baseline + diff
- [x] Approach 2-B: R8 task input interception
- [x] Parity — both approaches produce bit-identical baselines
- [x] 0.0.1 evaluation release — both run side-by-side
- [ ] Pick one path based on user feedback, drop the other
- [ ] Forbidden-rule pattern check (e.g. overly broad `-keep class **`)
- [ ] GradleRunner integration tests
- [ ] Publish to Maven Central / Gradle Plugin Portal

## Related

- [manifest-shield](https://github.com/fornewid/manifest-shield) — sibling plugin for Android manifest changes. proguard-shield mirrors its structure.

## License

Apache License 2.0. See [LICENSE](LICENSE).
