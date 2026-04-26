# proguard-shield

A Gradle plugin that detects unintentional changes to Android's merged ProGuard/R8 rules.

> Status: **0.0.x evaluation series.** Two implementations ship side-by-side so users can confirm parity on their own projects before a single approach is locked in.

## What it does

Snapshots the full ProGuard/R8 rule set for your `release` build as a baseline, then fails the build whenever subsequent builds produce a different set of rules — catching silent dependency-driven rule changes (new AAR consumer rules, R8 default shifts, etc.) before they hit production.

## Two implementations

Both produce bit-identical baselines (same rules, same order); they differ in how they gather the rules.

| Task family | What runs | Speed | AGP coupling |
|---|---|---|---|
| `proguardShield{Variant}` (accurate) | full R8 with `-printconfiguration` | slow (minutes on real apps) | public AGP API only |
| `proguardShieldFast{Variant}` | R8 inputs read directly via reflection, R8 itself skipped | fast (seconds) | uses AGP internal class (`ProguardConfigurableTask`) |

`./gradlew check` runs only the **fast** path so daily CI is cheap. The accurate path is reserved for the explicit parity-verification flow below.

## Install

```kotlin
// app/build.gradle.kts
plugins {
    id("com.android.application")
    id("io.github.fornewid.proguard-shield") version "0.0.2"
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

## Usage — three modes

### Mode 1: First install / baseline (run once, then on intentional rule changes)

```bash
./gradlew :app:proguardShieldBaseline
# Writes both files at once:
#   app/proguardShield/releaseRules.txt       (accurate path)
#   app/proguardShield/releaseFastRules.txt   (fast path)
```

Commit both files. The fast path needs both committed so the verification task below can compare them.

### Mode 2: Parity verification (first install, then on every AGP upgrade)

```bash
./gradlew :app:proguardShieldVerifyParity
```

Regenerates both baselines and byte-compares them. If they diverge, the task fails with a clear list of the lines that disagree — that's the signal that the fast path's reflection contract has shifted under the new AGP version. Until parity is restored you should fall back to `:app:proguardShield` (accurate) and please file an issue.

### Mode 3: Daily CI (every build)

```bash
./gradlew :app:check
# Runs proguardShieldFastRelease — cheap, no R8 invocation.
```

Drift detected by the fast path against the committed baseline fails the build with a colored `+`/`-` diff and a rebaseline hint.

### Re-baseline after an intentional rule change

Same as mode 1 — overwrites both files. After committing the new baselines, mode 3 stays green again.

### Forbidden patterns (optional)

Project-defined regex patterns that fail the build whenever a matching rule sneaks into the merged R8 input — overly broad keeps, disabled obfuscation, etc. Empty by default; the plugin enforces no policy on its own.

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

Both the accurate and fast paths run the same check on the same normalized inputs, so a violation surfaces consistently regardless of which task runs. Forbidden detection runs **before** drift detection — re-baselining cannot silence a forbidden match.

### Run only one path (optional)

```bash
./gradlew :app:proguardShield               # accurate, single variant — runs R8
./gradlew :app:proguardShieldFast           # fast aggregate, all declared variants
./gradlew :app:proguardShieldFastBaseline   # fast baseline only
```

## Roadmap

- [x] Repository bootstrap (Gradle wrapper, workflows, sample module layout)
- [x] Plugin module skeleton (extension DSL, variant handler)
- [x] Approach 1: `-printconfiguration` baseline + diff
- [x] Approach 2-B: R8 task input interception
- [x] Parity — both approaches produce bit-identical baselines
- [x] 0.0.1 evaluation release — both run side-by-side
- [x] GradleRunner integration tests — baseline + drift + parity + DSL validation
- [x] AGP version matrix — integration tests run against every supported AGP on CI
- [x] 0.0.3 — `check` runs only the fast path; explicit `proguardShieldVerifyParity` for accurate↔fast comparison
- [x] Forbidden-rule pattern check (regex-based, opt-in via `forbiddenPatterns` DSL)
- [x] Publish to Maven Central / Gradle Plugin Portal
- [ ] Pick one path based on user feedback, drop the other

## Releasing

See [`RELEASING.md`](RELEASING.md) for the release process and required GitHub secrets.

## Related

- [manifest-shield](https://github.com/fornewid/manifest-shield) — sibling plugin for Android manifest changes. proguard-shield mirrors its structure.

## License

Apache License 2.0. See [LICENSE](LICENSE).
