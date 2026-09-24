# Release Process

> How to build, sign, and publish Singularity Todo for Android and Desktop.

**Table of contents:**
1. [Prerequisites](#prerequisites)
2. [Keystore Setup](#keystore-setup)
3. [Gradle Properties](#gradle-properties)
4. [Version Bump](#version-bump)
5. [Build Commands](#build-commands)
6. [Verification Checklist](#verification-checklist)
7. [Known Limitations](#known-limitations)

---

## Prerequisites

| Tool | Required version | Notes |
|---|---|---|
| JDK | 21 (Temurin) | `java -version` should show 21 |
| Android SDK | API 37 | `ANDROID_HOME` must be set |
| Gradle | 9.7.1 | Bundled via gradle wrapper |
| `keytool` | JDK 21 | For keystore generation |
| `apksigner` | Android SDK | For APK signature verification |

Verify:
```bash
java -version          # should be 21
echo $ANDROID_HOME     # should point to Android SDK
keytool -help          # should not error
```

---

## Keystore Setup

For release builds, you need an RSA keypair in a JKS or PKCS12 keystore.

### Generate a new keystore

```bash
mkdir -p keystore

keytool -genkeypair -v \
  -keystore keystore/singularity-release.jks \
  -alias singularity \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -storetype JKS \
  -storepass "$STORE_PASSWORD" \
  -keypass "$KEY_PASSWORD" \
  -dname "CN=Singularity Todo, O=Singularity, C=US"
```

### Security note

**Never commit keystore files or passwords to source control.**

Recommended storage:
- **Option A** (recommended): Environment variables (`SINGULARITY_KEYSTORE_PATH`, etc.)
- **Option B**: User-level `~/.gradle/gradle.properties` (not committed):

```
# ~/.gradle/gradle.properties  (DO NOT COMMIT)
singularity.keystore.path=/home/user/singularity/keystore/singularity-release.jks
singularity.keystore.password=your-store-password
singularity.key.alias=singularity
singularity.key.password=your-key-password
```

### What if no signing config is found?

The build falls back gracefully:
- `debug` build type always uses Android's default debug keystore
- `release` build type without a configured keystore builds an **unsigned** APK
- To verify if signing succeeded, use `apksigner verify --verbose <apk>`

---

## Gradle Properties

Create or update `~/.gradle/gradle.properties` (NOT in the repo):

```properties
# ~/.gradle/gradle.properties

# Android signing (only needed for release builds)
singularity.keystore.path=/absolute/path/to/keystore/singularity-release.jks
singularity.keystore.password=<store-password>
singularity.key.alias=singularity
singularity.key.password=<key-password>

# Optional: JVM args for the Gradle daemon
org.gradle.jvmargs=-Xmx4096m -XX:+HeapDumpOnOutOfMemoryError
```

Or use environment variables:

```bash
export SINGULARITY_KEYSTORE_PATH=/path/to/keystore/singularity-release.jks
export SINGULARITY_KEYSTORE_PASSWORD=store-password
export SINGULARITY_KEY_ALIAS=singularity
export SINGULARITY_KEY_PASSWORD=key-password
```

---

## Version Bump

### Android

Edit `androidApp/build.gradle.kts`:

```kotlin
defaultConfig {
    versionCode = 2        // increment for every release
    versionName = "0.2.0"  // semantic version
}
```

### Desktop

Edit `desktopApp/build.gradle.kts`:

```kotlin
val desktopAppVersion = "0.2.0"
val desktopAppVersionCode = 2
```

Also update the `compose.desktop.application` block:

```kotlin
nativeDistributions {
    packageVersion = "0.2.0"
    // ...
}
```

---

## Build Commands

### Android

```bash
# Debug APK (no signing needed)
just android::build-debug

# Release APK (signing required — configure keystore first)
just android::build-release

# Install on connected device
just android::install

# Verify APK signature
$ANDROID_HOME/cmdline-tools/latest/bin/apksigner verify --verbose \
  androidApp/build/outputs/apk/release/app-release.apk
```

### Desktop

```bash
# Development run (no packaging)
just desktop::run

# Build Debian package (.deb)
just desktop::build-dist

# Build + install Debian package
just desktop::build-deb
just desktop::install-deb
```

### Full local verification pipeline

```bash
# Run all tests, lint, and debug build
./check.sh

# With coverage report
just coverage

# With slow tests (takes ~15 min)
./gradlew :shared:jvmTest -Ptest.tags=slow
```

---

## Verification Checklist

Before publishing, verify each item:

```bash
# 1. Tests pass
./check.sh                          # → "ALL CHECKS PASSED"
./gradlew :shared:jvmTest           # → BUILD SUCCESSFUL
./gradlew :shared:testAndroidHostTest  # → BUILD SUCCESSFUL
./gradlew :desktopApp:test          # → BUILD SUCCESSFUL

# 2. Release APK is signed
$ANDROID_HOME/cmdline-tools/latest/bin/apksigner verify \
  androidApp/build/outputs/apk/release/app-release.apk

# Expected output: "Verified using signer(s) 'RSA'... CN=Singularity Todo"

# 3. APK is minified (size should be smaller than debug)
ls -lh androidApp/build/outputs/apk/release/app-release.apk
# Compare with: ls -lh androidApp/build/outputs/apk/debug/app-debug.apk

# 4. Desktop .deb builds
ls -lh desktopApp/build/compose/binaries/main/deb/singularity-todo_*_amd64.deb

# 5. No FATAL or uncaught exceptions in desktop startup
xvfb-run -a ./gradlew :desktopApp:run > /tmp/smoke.log 2>&1 &
sleep 15
grep -E "FATAL|UncaughtException" /tmp/smoke.log   # should be empty
pkill -TERM -f "com.singularity.todo.MainKt"
```

---

## Known Limitations

The following features are stubs (intentionally unimplemented in MR-1) and do not affect Play Store eligibility:

| Feature | Status | Notes |
|---|---|---|
| Authentication | Stub | `AuthRepository.signUp()`/`signIn()` validate locally only. Backend Supabase integration is deferred to MR-2. |
| Cloud Sync | Stub | `SyncApi` is a `TODO` skeleton. Sync UI works locally. Supabase backend is deferred. |
| Analytics | Stub | `NoopAnalytics` is wired in production. Analytics backend integration deferred. |
| In-App Purchases | Not started | Play Billing integration not started. |
| App Version | Hardcoded | `AppVersion.android.kt` returns `"0.1.0"`/`1`. BuildConfig wiring is deferred to MR-2 — see `docs/decisions/2026-09-23-versioning-and-runtime-gates.md`. |

---

## Play Store Submission Notes

- **App bundle**: Use `androidApp/build/outputs/apk/release/app-release.apk` (or `.aab` if using Android App Bundle)
- **Release notes**: Document new features and known issues above
- **Screenshots**: Required for Phone (6.5", 7"), Tablet (7" and 10"), and Wear OS if applicable
- **Content rating**: Complete the questionnaire at play.google.com/console before your first paid release
- **Multi-profile**: Data is isolated per profile. AI Agent profile (`--profile=ai-agent`) is for dogfooding only.
