# Android Application

Android shell project — `MainActivity` and `AndroidManifest`. All UI and business logic live in `shared/`.

## Build

```bash
./gradlew :androidApp:assembleDebug         # Debug APK
./gradlew :androidApp:assembleRelease       # Signed release APK
./gradlew :androidApp:installDebug          # Install on connected device
```

## Entry point

`src/main/kotlin/com/singularity/todo/android/MainActivity.kt` — inflates `App()` from `shared/` and starts Koin.

## Permissions

Declared in `src/main/AndroidManifest.xml`:
- `INTERNET` — Supabase sync
- `POST_NOTIFICATIONS` — reminder notifications (API 33+)
- `SCHEDULE_EXACT_ALARM` — exact-time reminders via `AlarmManager`
- `RECEIVE_BOOT_COMPLETED` — reschedule reminders after reboot
- `USE_BIOMETRIC` — app lock

## Platform bindings

All Android-specific bindings are in `shared/src/androidMain/kotlin/com/singularity/todo/`: `EncryptedSharedPreferences` (secure storage), `AlarmManager` + `NotificationManager` (notifications), `AndroidFileSystem` (attachments), `AndroidBackupCodec`.
