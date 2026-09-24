# Shared Library

KMP library containing all business logic, data layer, and Compose UI. Targets Android (API 26+) and JVM Desktop.

## Structure

```
shared/
├── src/
│   ├── commonMain/kotlin/com/singularity/todo/
│   │   ├── core/           # Infrastructure: database, DI, auth, backup, sync, notifications, security
│   │   ├── feature/        # UI features: tasks, notes, projects, tags, agenda, AI, settings, etc.
│   │   └── test/fakes/    # Fake implementations for unit tests
│   ├── androidMain/        # Android platform bindings (EncryptedSharedPreferences, AlarmManager)
│   └── jvmMain/           # JVM platform bindings (JDBC SQLite, secret-tool, AWT)
└── build.gradle.kts
```

## Entry points

| Component | Entry point |
|---|---|
| App start | `shared/App.kt` → `startKoin(modules(...))` |
| DI modules | `domainModule`, `platformModule()`, `aiToolsModule()` via `Modules.kt` |
| Room schema | `shared/schemas/` (auto-migrated, schema v1 → v12+) |
| Nav graphs | `feature/*/nav/*NavGraph.kt` (expect/actual per platform) |

## Build commands

```bash
./gradlew :shared:jvmTest              # Run JVM tests (Room + pure Kotlin)
./gradlew :shared:testAndroidHostTest   # Robolectric Android tests
./gradlew :shared:detekt               # Static analysis
./gradlew :shared:koverXmlReport       # Coverage report → shared/build/reports/kover/
```

## Key infrastructure

- **Database**: Room with auto-migrations, `SyncableEntity` mixin for Supabase sync
- **DI**: Koin 4.x pure DSL (no annotations) — see `core/di/Modules.kt`
- **Async**: Kotlin Coroutines + Flow, `createBackgroundScope()` via DI
- **Logging**: Kermit (multiplatform), Logcat (Android), Logback (JVM)

See `AGENTS.md` for the full cheatsheet.
