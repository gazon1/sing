# Platform reference

Reference tables for the KMP seam: which actual implements which port, which
factory functions exist, and how time is obtained. Moved out of `AGENTS.md` on
2026-10-05, which had reached 250/250 lines with zero headroom.

It lives here because none of it is a decision. The *rule* — a port is an
interface in `commonMain` with two actuals and a `single<Port>` in both
`PlatformModule.*.kt`, and `platformModule()` is the only expect/actual seam —
stays in `AGENTS.md` and in `openspec/config.yaml`, because that is the part an
agent must not get wrong. The inventory below is what to read when *adding* a
port, which is not every task.

Nothing here is generated. If a port is added or renamed, this file is the thing
that goes stale, and no gate will notice — which is the honest cost of moving it
out of the one file every agent reads.

## Ports

`commonMain` declares the interface unless noted; implementations live in
`jvmMain` / `androidMain`.

| Port | jvmMain | androidMain |
|---|---|---|
| `SecureStoragePort` | secret-tool + AES-GCM | EncryptedSharedPreferences |
| `NotificationPort` | notify-send + at | AlarmManager + NotificationManager |
| `SharePort` / `FileSharePort` | `JvmSharePort` / `JvmFileSharePort` | `AndroidSharePort` / `AndroidFileSharePort` |
| `FileRevealer` | `JvmFileRevealer` | `AndroidFileRevealer` |
| `FileSystem` | `JvmFileSystem` | `AndroidFileSystem` |
| `BackupCodec` | `JvmBackupCodec` (java.util.zip) | `AndroidBackupCodec` |
| `TimeZoneProvider` | actual | actual |

`AttachmentStorage` is a **class**, not an interface.

## Time

`core.platform.Clock` as an object is gone (ADR
`2026-09-27-remove-platform-clock-object.md`). Use `kotlin.time.Clock.System.now()`
and inject `Clock` as a parameter for tests. For `LocalDate`:
`core.platform.todayFlow()`, `todayAt(zone)`, `todayInSystemZone()`, and
`delayUntilNextMidnight()`.

## Factory functions

`createSqlDriver()`, `createHttpClient()`, `createBackgroundScope()`
(`Dispatchers.Default`), `initLogging()`, `platformModule()`, `aiToolsModule()`
(32 Koog tools), `createKoogPromptExecutor()`, `onSecondaryClick()`.

`isDesktop` was removed — determine the platform through a concrete actual, not a
flag.

## Navigation

expect/actual NavGraphs: `TasksNavGraph`, `ProjectsNavGraph`, `NotesNavGraph`,
`SearchNavGraph`, `SettingsNavGraph`, `CalendarNavGraph`, `AgendaNavGraph`, each
with its paired `*EntryProvider`.
