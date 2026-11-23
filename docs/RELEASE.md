# Release & Maven Publishing

Maintainer guide for publishing `io.github.whywood.superpi.sdk:*` artifacts. Default version is controlled by `sdkVersion` in `build.gradle.kts` (`0.7.0`, overridable via `-PsdkVersion=...`).

## Automated Tag Release (Recommended)

Pushing a semver tag `vX.Y.Z` triggers `.github/workflows/release-maven.yml`:

1. Verifies the tag matches `build.gradle.kts`.
2. Runs `./gradlew clean build publishToMavenLocal publishAllPublicationsToPagesRepository`.
3. Runs `node scripts/verify-release.mjs <version>` to validate JAR/AAR bytecode target (Java 17) and POM coordinates.
4. Clones `Sadami3066/SuperPI-Maven` using the `MAVEN_DEPLOY_KEY` deploy key, syncs artifacts under `io/`, commits, and pushes to `main`.
5. Creates a GitHub Release with `SHA256SUMS.txt`.

```bash
git tag v0.7.0
git push origin main v0.7.0
```

## Local Full Verification

```bash
./scripts/release-local.sh 0.7.0
```

Runs clean build, local Maven publish, `verify-release.mjs`, JVM demo, standalone `examples/android-demo` APK build, and generates `build/release-manifest.txt`.
