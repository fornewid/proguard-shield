# proguard-shield

A Gradle plugin that detects unintentional changes to Android's merged ProGuard/R8 rules and forbidden rule patterns.

> Status: **scaffolding only.** Plugin functionality is under active development and not yet available.

## Roadmap

- [x] Repository bootstrap (Gradle wrapper, workflows, sample module layout)
- [ ] Plugin module skeleton (extension DSL, variant handler)
- [ ] Approach 1 prototype: `-printconfiguration` baseline + diff
- [ ] Approach 2-B prototype: R8 task input interception (AGP internal API)
- [ ] Forbidden pattern checker (regex-based)
- [ ] AGP 8.0 / 8.x / 9.0 compatibility matrix
- [ ] Publishing (Maven Central + Gradle Plugin Portal)

## Development

```bash
# Build the plugin
./gradlew :proguard-shield:check --stacktrace
```

Requires JDK 17.

## Related

- [manifest-shield](https://github.com/fornewid/manifest-shield) — sibling plugin for Android manifest changes. proguard-shield mirrors its structure.

## License

Apache License 2.0. See [LICENSE](LICENSE).
