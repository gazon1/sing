---
name: singularity-todo-android-release-workflow
description: Android release build workflow for Singularity Todo — signing configuration, version injection, R8/ProGuard minification (not yet enabled), iterative rule fixing, APK verification, and the release checklist. Use when building a release APK, fixing R8 missing-class warnings, or preparing a release.
---

# Android Release Workflow

> **Current state (2026-10-09):** signing is implemented (env-driven, fail-closed);
> minification is NOT yet enabled (`isMinifyEnabled = false`). Until R8 rules are
> written, shipping a minified build is blocked. See
> `docs/decisions/deferred-backlog.md` → `release-apk-is-unsigned-and-unminified`.

## Prerequisites

### 1. Keystore (CI)

In GitHub Actions, set four repository secrets (Settings → Secrets and variables →
Actions):

| Secret | Value |
|--------|-------|
| `ANDROID_KEYSTORE_BASE64` | Base-64 encoded `.jks`/`.keystore` file |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias (e.g. `singularity`) |
| `ANDROID_KEY_PASSWORD` | Key password |

Also set a repository variable (Settings → Secrets and variables → Actions → Variables):

| Variable | Value |
|----------|-------|
| `ANDROID_CERT_FINGERPRINT` | SHA-256 fingerprint of the signing cert (hex, no colons). Run: `keytool -exportcert -alias singularity -keystore keystore.jks \| openssl sha256 -binary \| xxd -p -c 256` |

**Without all four secrets the release workflow fails-closed** — it never silently
produces an APK with the JDK debug key. Losing the keystore means Android will
never accept an update over the installed app. Back it up in at least two places.

### 2. Local Development

For local builds, do NOT set the signing env vars. The `release` buildType in
`androidApp/build.gradle.kts` activates the signing config only when all four
`SIGNING_*` environment variables are present; otherwise it falls back to the
default JDK keystore (which produces an unsigned APK).

```bash
# Local release build (unsigned — for testing only)
./gradlew :androidApp:assembleRelease
# APK: androidApp/build/outputs/apk/release/app-release.apk (unsigned)
```

### 3. SDK

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

### Release (CI: signed + version-injected; local: unsigned)

```bash
# CI: VERSION_NAME and VERSION_CODE are injected by release.yml
./gradlew :androidApp:assembleRelease \
  -PVERSION_NAME="1.2.3" \
  -PVERSION_CODE=1002003
# APK: androidApp/build/outputs/apk/release/app-release.apk (or *-unsigned.apk locally)
```

**In CI:** `release.yml` passes these from the tag. Do not set them manually in
`androidApp/build.gradle.kts` — the hardcoded `versionName = "0.1.0"` is replaced
at build time via `project.findProperty("VERSION_NAME")`.

**Version code scheme:** `major * 1_000_000 + minor * 1_000 + patch`.
Example: `1.2.3` → `1_000_000 + 2_000 + 3 = 1_002_003`.
Asserted: `minor < 1000`, `patch < 1000`.

## Verifying the APK

### Check signing (certificate fingerprint)

```bash
# Extract cert SHA-256 from APK
cert_sha256=$(keytool -printcert -jarfile androidApp/build/outputs/apk/release/app-release.apk \
  | sed -n 's/SHA-256: \(.*\)/\1/p' | tr -d ': \n' | tr 'A-Z' 'a-z')
echo "$cert_sha256"
# Compare against ANDROID_CERT_FINGERPRINT repo variable
```

`apksigner verify` alone is insufficient — it only confirms the APK is signed,
not that it is signed with the right key. Always compare the certificate fingerprint.

### Check version

```bash
aapt2 dump badging androidApp/build/outputs/apk/release/app-release.apk \
  | sed -n "s/^package: name='[^']*' versionCode='\([^']*\)' versionName='\([^']*\)'.*/versionCode: \1  versionName: \2/p"
```

The `versionName` must match the tag exactly. `release.yml` fails the build if
`embedded != tag` (exact string comparison).

## R8/ProGuard — NOT YET ENABLED

Minification is blocked on writing the rules. See
`docs/decisions/deferred-backlog.md` → `release-apk-is-unsigned-and-unminified`.

**Order matters:** minification before signing. A minified APK that crashes is worse
than an unsigned APK that installs cleanly. The rules must be written and exercised
in CI before the flag is flipped.

When `isMinifyEnabled = true`, R8 may emit `Missing class` warnings. These are
**warnings only** — the build succeeds but the DEX may contain unnecessary code.

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

Before tagging a release:

- [ ] `./gradlew :androidApp:assembleRelease` completes without errors
- [ ] `apksigner verify` shows `v1: true  v2: true`
- [ ] Certificate SHA-256 fingerprint matches `ANDROID_CERT_FINGERPRINT`
- [ ] `aapt2 dump badging` shows the correct `versionName` matching the tag
- [ ] No `Missing class` warnings in the build output (only after minification is enabled)
- [ ] Tests pass: `./gradlew :shared:jvmTest --no-daemon`
- [ ] Draft GitHub Release is created and reviewed before publishing

## Troubleshooting

### "Keystore file not set for signing config release" (local)

The signing config is conditional — it only activates when all four `SIGNING_*`
environment variables are set. If they are absent, Gradle uses the default JDK
keystore. This is correct local dev behaviour (unsigned builds for testing).

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

Add the appropriate `-keep` rule or check that all relevant source sets are
included in the release variant.

## See Also

- `singularity-todo-quality-tools` — detekt, ktlint, kover tooling
- `docs/decisions/deferred-backlog.md` — `release-apk-is-unsigned-and-unminified`
- `.github/workflows/release.yml` — the release pipeline
- `android-dev` skill — Android-specific development workflow
