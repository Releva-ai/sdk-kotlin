# SDK Release Guide

Complete guide for releasing new versions of the Releva SDK.

---

## Quick Reference

### TL;DR

```bash
make release VERSION=1.0.1
```

That's it! The Makefile handles everything: clean, test, build, version update, commit, tag, and push.

### What Happens

When you run `make release VERSION=x.y.z`:

1. ✓ Cleans build artifacts
2. ✓ Runs all tests
3. ✓ Builds the SDK
4. ✓ Updates version in `build.gradle.kts` and `README.md`
5. ✓ Commits and pushes changes
6. ✓ Creates and pushes git tag
7. ✓ Triggers JitPack to build and publish

### Verification

1. **Check JitPack**: https://jitpack.io/#Releva-ai/sdk-kotlin
   - Wait for green ✅ checkmark
2. **Add GitHub release notes**: https://github.com/Releva-ai/sdk-kotlin/releases

---

## Overview

The Releva SDK is distributed through [JitPack](https://jitpack.io), which builds the SDK directly from GitHub releases. JitPack automatically creates Maven artifacts from your GitHub tags.

**SDK Repository**: https://github.com/Releva-ai/sdk-kotlin
**JitPack Page**: https://jitpack.io/#Releva-ai/sdk-kotlin

---

## Releasing a New Version

### Prerequisites

- Clean working directory (no uncommitted changes)
- All changes tested locally
- Version number decided (see [Semantic Versioning](#semantic-versioning))

### Using Make (Recommended)

```bash
make release VERSION=1.0.1
```

### Manual Release

If you need manual control:

```bash
# 1. Clean and test
make clean
make test
make build

# 2. Update version numbers
vim releva-sdk/build.gradle.kts  # Update version
vim README.md                     # Update version

# 3. Commit and push
git add .
git commit -m "Release version 1.0.1"
git push origin main

# 4. Create and push tag
git tag -a 1.0.1 -m "Release version 1.0.1"
git push origin 1.0.1
```

---

## Semantic Versioning

Follow [Semantic Versioning](https://semver.org/): `MAJOR.MINOR.PATCH`

### Version Guidelines

**MAJOR** (x.0.0) - Breaking changes requiring developer action
- API changes that break existing code
- Removed features or methods
- Changed behavior affecting integrations

**MINOR** (1.x.0) - New features (backward compatible)
- New methods or classes
- New functionality that doesn't break existing code
- Deprecations (with backward compatibility)

**PATCH** (1.0.x) - Bug fixes (backward compatible)
- Bug fixes
- Performance improvements
- Documentation updates

### Examples

```bash
# Patch release (bug fix)
make release VERSION=1.0.1

# Minor release (new feature)
make release VERSION=1.1.0

# Major release (breaking changes)
make release VERSION=2.0.0

# Pre-release (beta testing)
make release VERSION=1.1.0-beta
```

### Version Examples

```
1.0.0 → Initial release
1.0.1 → Bug fix (e.g., fix crash in push notifications)
1.1.0 → New feature (e.g., add wishlist tracking)
2.0.0 → Breaking change (e.g., rename RelevaClient methods)
```

### Pre-release Versions

For testing before official release:

```
1.1.0-alpha    → Alpha testing
1.1.0-beta     → Beta testing
1.1.0-rc1      → Release candidate 1
```

---

## Verification Steps

### 1. Check JitPack Build

After releasing, verify the build succeeded:

1. Go to: https://jitpack.io/#Releva-ai/sdk-kotlin
2. Find your version in the list
3. Wait for the status icon:
   - 🔄 **Yellow**: Building (wait)
   - ✅ **Green**: Build successful
   - ❌ **Red**: Build failed (click for logs)

### 2. Test in Example App

Update the example app to use the new version:

```bash
cd ../shopping-android-app

# Update build.gradle.kts
sed -i 's/com.github.Releva-ai:sdk-kotlin:[0-9.]*/com.github.Releva-ai:sdk-kotlin:1.0.1/' app/build.gradle.kts

# Build and test
./gradlew clean build
./run-on-device.sh
```

### 3. Update Documentation

After a successful release:
- Add release notes on GitHub
- Update CHANGELOG.md (if you maintain one)
- Update integration documentation (if API changed)

---

## Makefile Commands

```bash
make help                  # Show available commands
make build                 # Build SDK
make test                  # Run tests
make clean                 # Clean artifacts
make release VERSION=x.y.z # Release new version
```

---

## Troubleshooting

### JitPack Build Fails

**Check build logs:**
1. Go to https://jitpack.io/#Releva-ai/sdk-kotlin
2. Click the ❌ icon next to your version
3. Review the error logs

**Common issues:**
- Gradle/Kotlin version issues (check jitpack.yml)
- Compilation errors (test locally with `make build`)
- Missing dependencies (verify build.gradle.kts)

**Fix and re-release:**
```bash
# Fix the issue
git add .
git commit -m "Fix build issue"
git push

# Delete old tag
git tag -d 1.0.1
git push origin :refs/tags/1.0.1

# Re-release
make release VERSION=1.0.1
```

### Wrong Version in build.gradle.kts

```bash
# Update manually
sed -i 's/version = "[0-9.]*"/version = "1.0.1"/' releva-sdk/build.gradle.kts

# Commit and re-tag
git add releva-sdk/build.gradle.kts
git commit -m "Update version to 1.0.1"
git push

# Delete and recreate tag
git tag -d 1.0.1
git push origin :refs/tags/1.0.1
git tag -a 1.0.1 -m "Release version 1.0.1"
git push origin 1.0.1
```

### Tag Already Exists

```bash
# Delete local tag
git tag -d 1.0.1

# Delete remote tag
git push origin :refs/tags/1.0.1

# Create new tag
git tag -a 1.0.1 -m "Release version 1.0.1"
git push origin 1.0.1
```

**⚠️ Warning**: Only do this for unreleased or broken versions. Never delete tags that developers are using!

### JitPack Cache Issues

If JitPack shows old version:

1. Visit: https://jitpack.io/#Releva-ai/sdk-kotlin/1.0.1
2. Click "Look up" button to force rebuild
3. Wait for new build to complete

---

## Release Checklist

Use this checklist for every release:

### Before Release
- [ ] All changes committed
- [ ] Tests pass locally: `make test`
- [ ] Build succeeds: `make build`
- [ ] Example app tested with changes
- [ ] Version number decided (MAJOR.MINOR.PATCH)
- [ ] Release notes prepared

### Release
- [ ] Run: `make release VERSION=x.y.z`
- [ ] Verify tag pushed to GitHub
- [ ] Check JitPack build status

### After Release
- [ ] JitPack build shows green ✅
- [ ] Test in example app with new version
- [ ] Add release notes on GitHub
- [ ] Update CHANGELOG.md
- [ ] Notify users (if breaking changes)

---

## Best Practices

### For SDK Maintainers

1. **Test Before Release**
   - Always run `make test` before releasing
   - Test with the example app
   - Verify on real devices if possible

2. **Version Carefully**
   - Follow semantic versioning strictly
   - Be conservative with major version bumps
   - Document breaking changes clearly

3. **Communicate Changes**
   - Write clear release notes
   - Mention breaking changes in bold
   - Provide migration guides for major versions

4. **Don't Rush**
   - Take time to test thoroughly
   - Review code changes before releasing
   - Consider beta versions for major changes

5. **Keep History**
   - Never delete released tags
   - Maintain CHANGELOG.md
   - Document all version changes

---

## Advanced Topics

### Multiple Release Branches

For supporting multiple major versions:

```bash
# Create release branch for v1.x
git checkout -b release/1.x
git push -u origin release/1.x

# Release from specific branch
git checkout release/1.x
make release VERSION=1.0.5
```

### Hotfix Releases

For urgent bug fixes:

```bash
# Create hotfix from tag
git checkout -b hotfix/1.0.1 1.0.0

# Fix bug
git commit -am "Fix critical bug"

# Release hotfix
make release VERSION=1.0.1

# Merge back to main
git checkout main
git merge hotfix/1.0.1
git push
```

---

## App Developer Instructions

Share these instructions with app developers integrating the SDK:

### Add Repository

In `settings.gradle.kts`:
```kotlin
repositories {
    google()
    mavenCentral()
    maven { url = uri("https://jitpack.io") }
}
```

### Add Dependency

In `app/build.gradle.kts`:
```kotlin
dependencies {
    implementation("com.github.Releva-ai:sdk-kotlin:1.0.0")
}
```

### Update Version

To update to a new version:
```kotlin
implementation("com.github.Releva-ai:sdk-kotlin:1.0.1")
```

Then sync:
```bash
./gradlew clean build --refresh-dependencies
```

---

## Version History Template

Maintain a version history in your GitHub releases:

### Version 1.0.1 (2025-10-16)

**Bug Fixes:**
- Fixed crash when tracking push notifications
- Improved error handling for network failures

**Improvements:**
- Updated dependencies for better performance

### Version 1.0.0 (2025-10-15)

**Initial Release:**
- E-commerce tracking
- Push notification engagement
- Cart and wishlist management
- Advanced product filtering

---

## Resources

- [JitPack Documentation](https://jitpack.io/docs/)
- [Semantic Versioning](https://semver.org/)
- [Git Tagging](https://git-scm.com/book/en/v2/Git-Basics-Tagging)
- [Maven Publishing Plugin](https://docs.gradle.org/current/userguide/publishing_maven.html)

---

## Full Documentation

- [README.md](README.md) - Project overview and quick start
- [INTEGRATION_GUIDE.md](INTEGRATION_GUIDE.md) - Complete integration guide for app developers
- [DEVELOPMENT.md](DEVELOPMENT.md) - SDK architecture and contribution guide for maintainers

---

## Support

**SDK Issues**: Open an issue on https://github.com/Releva-ai/sdk-kotlin
**JitPack Issues**: Check https://jitpack.io/docs/
**Questions**: Contact tech-support@releva.ai
