# Releasing

The repo ships to **Maven Central** (via Sonatype Central Portal) and the **Gradle Plugin Portal** through `com.vanniktech.maven.publish` and `com.gradle.plugin-publish`. Both are wired into `proguard-shield/build.gradle.kts`.

## One-time setup: GitHub secrets

The publish workflow needs these repository secrets. All but `GH_PAT` come from the respective service accounts.

| Secret | What it is | Where to get it |
|---|---|---|
| `SONATYPE_NEXUS_USERNAME` | Sonatype Central user token name | https://central.sonatype.com/ → Account → User Token |
| `SONATYPE_NEXUS_PASSWORD` | Sonatype Central user token password | same as above |
| `SIGNING_KEY_ID` | 8-char GPG key id | `gpg -K --keyid-format short` |
| `SIGNING_KEY_PASSWORD` | GPG key passphrase | whatever was used when creating the key |
| `SIGNING_KEY` | ASCII-armored GPG private key, single line (replace newlines with `\n`) | `gpg --armor --export-secret-key $KEY_ID` |
| `GRADLE_PUBLISH_KEY` | Gradle Plugin Portal API key | https://plugins.gradle.org/u/ `<user>` → API Keys |
| `GRADLE_PUBLISH_SECRET` | Gradle Plugin Portal API secret | same as above |
| `GH_PAT` | GitHub fine-grained PAT (`contents: write`) used by the workflow to push the `-SNAPSHOT` bump commit | Settings → Developer settings → Personal access tokens → Fine-grained |

The `GH_PAT` also needs a repository ruleset bypass for **Repository admin** so the SNAPSHOT bump commit can push directly to `main` without going through a PR.

The release workflow uses a GitHub **environment** named `release` (see `release.yml`). Create it in the repo settings before the first release. Adding a "Required reviewers" protection rule on that environment is optional but useful as a final gate before the release PR is opened.

Note that this gate sits on **`Create Release PR`** (release.yml), not on the actual `Publish` job (publish.yml). The publish workflow runs automatically once the release PR merges into `main`, with no additional approval step.

## Normal release flow

1. **Trigger the release PR.** Run the `Create Release PR` workflow from GitHub Actions (manual dispatch):
   - `use_current_version = true` strips `-SNAPSHOT` from `proguard-shield/gradle.properties`.
   - Or provide `custom_version` explicitly (must be `major.minor.patch`).
   This creates a `release/<version>` branch and opens a PR titled `Release <version>`.
2. **Wait for CI** on that PR. The `build` workflow detects `release/...` branches and validates the version format.
3. **Merge the release PR** into `main`.
4. The `Publish` workflow runs on the `main` push:
   - Publishes to Maven Central (`:proguard-shield:publish`).
   - Publishes to the Gradle Plugin Portal (`:proguard-shield:publishPlugins`).
   - Creates a git tag `<version>`.
   - Bumps `VERSION_NAME` to the next `-SNAPSHOT` and pushes that commit to `main`.
5. `Release Drafter` turns the tag into a GitHub Release with the accumulated changelog.

The automatic SNAPSHOT bump in step 4 always increments the patch digit (`0.0.1` → `0.0.2-SNAPSHOT`). If the next release should be a minor or major bump, edit `proguard-shield/gradle.properties` manually after the publish workflow finishes.

## Smoke test before cutting a release

Publish locally to catch POM / descriptor / resource issues before pushing to Central:

```bash
./gradlew :proguard-shield:publishToMavenLocal
find ~/.m2/repository/io/github/fornewid/proguard-shield -type f
```

`signAllPublications()` auto-skips its tasks when `ORG_GRADLE_PROJECT_signingInMemoryKey*` env vars are absent, so `publishToMavenLocal` works locally without real GPG keys.

Inspect the generated `proguard-shield-<version>.pom` and the plugin descriptor inside the jar:

```bash
unzip -p ~/.m2/repository/io/github/fornewid/proguard-shield/proguard-shield/<version>/proguard-shield-<version>.jar \
    META-INF/gradle-plugins/io.github.fornewid.proguard-shield.properties
```

Expected output: `implementation-class=io.github.fornewid.gradle.plugins.proguardshield.ProGuardShieldPlugin`.

## If something goes wrong

- **Publish workflow fails before the Central upload**: safe to re-run the workflow. Central has received nothing yet.
- **Publish workflow fails after the Central upload** (e.g. Plugin Portal rejected, or the tag push / SNAPSHOT bump failed): **do not re-run the workflow.** Maven Central refuses duplicate uploads of the same version, so a retry will just fail on the first step. Recover manually: publish to the Plugin Portal from a clean local checkout (`./gradlew :proguard-shield:publishPlugins`), then `git tag <version> && git push origin <version>`, then edit `proguard-shield/gradle.properties` to bump `VERSION_NAME` and push the bump to `main`.
- **`GH_PAT` rejects the SNAPSHOT bump push**: verify the Ruleset bypass still lists the PAT's user under *Repository admin*.
