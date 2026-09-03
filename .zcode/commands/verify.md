# /verify — Full local verification

Runs the complete check suite for the Singularity Todo KMP project:
commonTest → jvmTest → desktopApp:test → assembleDebug

## Usage

```
/verify
```

## What it does

1. `gradlew :shared:commonTest` — Pure Kotlin tests
2. `gradlew :shared:jvmTest` — Room + logic tests on JVM
3. `gradlew :desktopApp:test` — Desktop JVM tests
4. `gradlew :androidApp:assembleDebug` — Android APK build

## Alternative (faster, less thorough)

```bash
./gradlew :shared:jvmTest
```

## Exit codes

- 0 = all checks passed
- 1 = one or more checks failed

## Notes

- Uses `--no-daemon --quiet` for cleaner output
- All steps run sequentially (not parallelized)
- Full run typically takes 2-3 minutes
