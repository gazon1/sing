# Desktop Application

JVM Desktop Compose application targeting macOS, Linux, Windows. All UI and business logic live in `shared/`.

## Build & Run

```bash
./gradlew :desktopApp:run                    # Run with GUI
xvfb-run -a ./gradlew :desktopApp:run        # Headless Linux
./gradlew :desktopApp:jvmTest                # JVM UI tests
```

## Entry point

`src/jvmMain/kotlin/com/singularity/todo/desktop/Main.kt` — uses `singleWindowApplication` (Jetbrains Compose for Desktop), starts Koin with JVM-only modules.

## Platform bindings

All JVM-specific bindings are in `shared/src/jvmMain/kotlin/com/singularity/todo/`: JDBC SQLite driver, `secret-tool` (AES-GCM secure storage), `MultiLLMPromptExecutor` + `OpenAILLMClient` (AI), `notify-send` + `at` (desktop notifications), AWT `secondaryClick` handling.

## Desktop-specific notes

- No Room auto-migrations on desktop (schema managed manually)
- Secure storage uses `secret-tool` (keychain on macOS, libsecret on Linux)
- AI tools available on JVM (not on Android, which uses `AndroidKoogFactory`)
