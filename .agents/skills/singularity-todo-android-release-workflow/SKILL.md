---
name: singularity-todo-android-release-workflow
description: Android release build workflow for Singularity Todo — signing configuration, R8/ProGuard minification, iterative rule fixing, APK verification, and the release checklist. Use when building a release APK, fixing R8 missing-class warnings, or preparing a release.
---

# Android Release Workflow

## Prerequisites

Before building a release APK, ensure the following are configured:

### 1. Keystore

A Java keystore (`.jks` or `.keystore`) with a signing key. Generate a test keystore:

```bash
keytool -genkey -v -keystore /tmp/singularity-test.keystore \
  -alias singularity -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass teststore -keypass testkey -dname "CN=Test"
```

**For production:** use a real keystore managed by your team. Never commit keystore passwords to source control.

### 2. Gradle Properties

Set credentials in `~/.gradle/gradle.properties` (user-level, not project-level):

```properties
singularity.keystore.path=/path/to/your/keystore.jks
singularity.keystore.password=your_store_password
singularity.key.alias=your_alias
singularity.key.password=your_key_password
```

Or pass them as environment variables:
```bash
export SINGULARITY_KEYSTORE_PATH=/path/to/keystore.jks
export SINGULARITY_KEYSTORE_PASSWORD=...
export SINGULARITY_KEY_ALIAS=...
export SINGULARITY_KEY_PASSWORD=...
```

### 3. Local SDK

Ensure `local.properties` exists in the project root (not checked in):
```
sdk.dir=/path/to/android/sdk
```

## Build Commands

### Debug (no signing, no minify)

```bash
./gradlew :androidApp:assembleDebug
# APK: androidApp/build/outputs/apk/debug/app-debug.apk
```

### Release (signing + minification)

```bash
./gradlew :androidApp:assembleRelease
# APK: androidApp/build/outputs/apk/release/app-release.apk
```

If keystore is not configured, signing is **skipped** and a debug-style unsigned APK is produced. The `release` buildType still enables minification.

### Release with verbose signing info

```bash
./gradlew :androidApp:assembleRelease --info 2>&1 | grep -i signing
```

## Verifying the APK

### Check signing

```bash
apksigner verify --verbose androidApp/build/outputs/apk/release/app-release.apk
```

Expected output for a signed APK:
```
Verifies
Verified using v1 scheme (JAR signing): true
Verified using v2 scheme (APK signing): true
```

### Check minification

```bash
apkanalyzer dex size androidApp/build/outputs/apk/release/app-release.apk
```

Compare against the debug APK size:
```bash
ls -lh androidApp/build/outputs/apk/debug/app-release.apk   # after rename
```

### Inspect DEX classes

```bash
apkanalyzer dex packages androidApp/build/outputs/apk/release/app-release.apk
```

## R8/ProGuard — Iterative Rule Fixing

When `isMinifyEnabled = true`, R8 may emit warnings about missing classes from third-party libraries. These are **warnings only** — the build succeeds, but the DEX may contain unnecessary code.

### Step 1: Build and capture warnings

```bash
./gradlew :androidApp:assembleRelease 2>&1 | grep "Missing class"
```

R8 writes missing-class rules to:
```
androidApp/build/outputs/mapping/release/missing_rules.txt
```

### Step 2: Add rules to proguard-rules.pro

Copy the `-dontwarn` rules from `missing_rules.txt` into `androidApp/proguard-rules.pro`.

Example:
```proguard
# Added by R8 iterative fix
-dontwarn ai.koog.utils.io.Coroutines_jvmKt
-dontwarn com.google.auto.value.AutoValue$Builder
-dontwarn com.google.auto.value.AutoValue$CopyAnnotations
-dontwarn com.google.auto.value.AutoValue
-dontwarn io.opentelemetry.api.incubator.metrics.ExtendedDoubleHistogram
```

### Step 3: Rebuild and verify

```bash
./gradlew :androidApp:assembleRelease 2>&1 | grep -E "error|Missing class|warning"
```

Repeat until no new missing-class warnings appear.

### Common Missing Classes (known)

| Library | Rule |
|---------|------|
| Koog (ai.koog.*) | `-dontwarn ai.koog.**` |
| OpenTelemetry | `-dontwarn io.opentelemetry.**` |
| AutoValue | `-dontwarn com.google.auto.value.**` |
| Ktor debug detectors | `-dontwarn org.slf4j.**` |
| JDK management | `-dontwarn java.lang.management.**` |

### Keep Rules (required for runtime)

```proguard
# Room
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }

# Koin
-keep class org.koin.core.annotation.** { *; }

# kotlinx-serialization
-keepclassmembers class * { @kotlinx.serialization.Serializable <fields>; }
-keepclasseswithmembers class * { kotlinx.serialization.KSerializer serializer(...); }

# Koog
-keep class ai.koog.** { *; }
```

## Release Checklist

Before publishing:

- [ ] `./gradlew :androidApp:assembleRelease` completes without errors
- [ ] `apksigner verify --verbose` shows `Verified using v1 scheme` and `Verified using v2 scheme`
- [ ] No `Missing class` warnings in the build output
- [ ] `apkanalyzer dex size` shows meaningful reduction vs debug APK (typically 30-50% smaller)
- [ ] Version name and version code are correct in `androidApp/build.gradle.kts`
- [ ] ProGuard rules are stable (running the build twice produces identical rules warnings)
- [ ] Tests pass: `./gradlew :shared:test --no-daemon`

## Version Bump

Version name and code are in `androidApp/build.gradle.kts`:

```kotlin
android {
    defaultConfig {
        versionName = "1.0.0"
        versionCode = 1
    }
}
```

For a hotfix release, increment `versionCode` and rebuild.

## Troubleshooting

### "Keystore file not set for signing config release"

The signing config is conditional — it only activates when ALL four properties are set:
- `singularity.keystore.path`
- `singularity.keystore.password`
- `singularity.key.alias`
- `singularity.key.password`

If any is missing, the release build uses the default debug keystore (which is unsigned and cannot be published). See **Prerequisites** above.

### R8/ProGuard removes a class it shouldn't

Add a keep rule:
```proguard
-keep class com.singularity.todo.SomeClass { *; }
```

### "Missing class" for an internal class

This means R8 can't find the class during minification. Possible causes:
- The class is only referenced via reflection
- The class is in a source set not included in the release build
- KSP-generated code is missing a keep rule

Add the appropriate `-keep` rule or check that all relevant source sets are included in the release variant.

## See Also

- `singularity-todo-quality-tools` — detekt, ktlint, kover tooling
- `RELEASE.md` in project root — full release documentation
- `android-dev` skill — Android-specific development workflow
