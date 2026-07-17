<!-- Formatting: never write "#<number>" (e.g. #1) anywhere — GitHub auto-links it. Use "1." for numbered lists. -->

## Summary

<!-- What changed and why. -->

## Release checklist

<!-- For any PR that changes SDK behavior (not needed for docs/CI-only changes): -->

- [ ] Bumped `version` in `releva-sdk/build.gradle.kts` (per SemVer: major = breaking, minor = features, patch = fixes; JitPack publishes from the git tag).
- [ ] Bumped the `VERSION` constant in `RelevaClient.kt` to match — it is sent as `options.client.version` / `sdkVersion` on every push, so it must move with each behavior change. (Keep it equal to the build.gradle.kts version, with the `-kotlin` suffix.)
- [ ] Added a matching entry at the top of `CHANGELOG.md` (mark breaking changes with a migration note).
- [ ] Tests added/updated and `./gradlew test` passes.

## Breaking changes

<!-- List each breaking change and its migration, or write "None". -->
