# Release and artifact verification

For maintainers. Current version: `0.7.0` (`subprojects.version` in the root `build.gradle.kts`).

## Local release flow

```bash
./scripts/release-local.sh 0.7.0
```

The script runs the following in order:

1. `./gradlew clean build` (compile and test the JVM + Android targets)
2. `./gradlew publishToMavenLocal` (publish `io.github.whywood.superpi.sdk:pi-ai-jvm`, `pi-ai-android`, `pi-agent-*`, `pi-coding-agent-*`, including sources jars and POMs)
3. `node scripts/verify-release.mjs 0.7.0` (verify artifact completeness and bytecode version)
4. `./gradlew :examples:jvm-demo:run` (JVM end-to-end demo)
5. Build the Android demo APK (standalone project consuming the locally published artifacts)
6. Generate `build/release-manifest.txt` (version, generation time, sha256 of jar/aar/apk)

On low-memory machines the script should run builds serially through `scripts/gw.sh` to avoid multiple Gradle instances competing for memory.

## Artifact list

| Artifact | Path |
|---|---|
| JVM jar | `~/.m2/repository/io/github/whywood/superpi/sdk/<module>-jvm/0.7.0/<module>-jvm-0.7.0.jar` |
| JVM sources | `~/.m2/repository/io/github/whywood/superpi/sdk/<module>-jvm/0.7.0/<module>-jvm-0.7.0-sources.jar` |
| Gradle metadata | `~/.m2/repository/io/github/whywood/superpi/sdk/<module>-jvm/0.7.0/<module>-jvm-0.7.0.module` |
| POM | `~/.m2/repository/io/github/whywood/superpi/sdk/<module>-jvm/0.7.0/<module>-jvm-0.7.0.pom` |
| Android AAR | `~/.m2/repository/io/github/whywood/superpi/sdk/<module>-android/0.7.0/<module>-android-0.7.0.aar` |
| Demo APK | `examples/android-demo/build/outputs/apk/debug/pi2kotlin-android-demo-debug.apk` |

## Checks

`scripts/verify-release.mjs` verifies:

- All four modules have a JVM jar / POM / Gradle module metadata / Android AAR / sources jar
- Class files in the JVM jar have major version `61` (Java 17); Android dependencies are compatible with `minSdk 26`
- POM versions match the release version, and inter-module dependency coordinates are correct (`pi-agent-jvm` → `pi-ai-jvm`, `pi-coding-agent-jvm` → `pi-agent-jvm`)
- Android AARs contain `classes.jar` and `AndroidManifest.xml`

## Version and JVM target

- The version is set in one place: `subprojects { version = ... }` in the root `build.gradle.kts`
- After a `v<version>` tag is pushed, JitPack builds that tag; the dependency version is the tag name (for example `v0.7.0`)
- The JVM target uses `jvmToolchain(17)`, so published artifacts are Java 17 bytecode; the Android target compiles to 17 as well
- The JVM requirements in README / SDK docs must match the actual bytecode; re-run `verify-release.mjs` after changing the toolchain

## Known release limitations

- No Maven Central or private repository credentials are configured; external distribution goes through JitPack (tag builds, slower on first request). Publishing to Maven Central still requires adding `publishing.repositories` to each module
- The Android demo is a standalone Gradle project (`examples/android-demo`) that depends on the artifacts published to `~/.m2`; rebuild it after publishing a new version
- Before releasing, run the Android demo once on a machine with a real device (install, launch, offline conversation, destroy and relaunch); keep device evidence locally as needed rather than publishing it with the public repository
