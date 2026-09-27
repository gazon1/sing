---
title: Post-Phase-5.5 Out-of-Scope Decisions
date: 2026-09-21
status: accepted
---

# Post-Phase-5.5 Out-of-Scope Decisions

## Context

Phase 5.5 (AutoCloseableCoroutineScope migration, 25 VMs) surfaced three issues that are intentionally **not** fixed in that PR, documented here so they are not lost.

---

## Issue G — BackupRepository hardcodes `UserId.anonymous`

**Area:** `core/backup/BackupRepositoryImpl`

### Problem

`BackupRepository` passes `UserId.anonymous` to remote operations:

```kotlin
// BackupRepositoryImpl
override suspend fun export(): Result<BackupData> {
    val userId = UserId.anonymous  // ← hardcoded
    val token = authRepository.getAccessToken(userId)
    // ...
}
```

`StubRemoteBackupService` ignores `userId` entirely, making the hardcode invisible. When a real Supabase Storage backend is connected (Phase 12), this will silently use the wrong user prefix.

### Why not fixed now

Phase 12 (Supabase Storage integration) is not yet scheduled. The remote service interface (`RemoteBackupService`) needs to change to support per-user bucket paths, which requires API design decisions that belong to Phase 12.

### How to fix

When Phase 12 begins:
1. Change `BackupRepository.export()` / `import()` to accept `userId: UserId` as parameter
2. Inject `ProfileAwareCurrentUser` into `BackupRepositoryImpl`
3. Use `currentUser.scopedUserId.value` instead of `UserId.anonymous`
4. Update all call sites (`BackupViewModel`)

### Supersedes

[2026-09-21-user-scoped-repository](2026-09-21-user-scoped-repository.md) § BackupRepository.

---

## Issue H — SettingsViewModel uses `settings.userId` for display label

**Area:** `feature/settings/SettingsViewModel`

### Problem

`SettingsViewModel` reads the signed-in user identity from `settings.userId` (a DataStore preference, per-device label) rather than `currentUser.scopedUserId` (profile-scoped):

```kotlin
// SettingsViewModel
private val signedInUser: Flow<Profile?> = settings.userId
    .flatMapLatest { uid -> profileRepo.getById(UserId(uid)) }
```

`settings.userId` is a **display label** — it persists the last signed-in email even after logout. This means:
- After switching profiles, Settings screen still shows the old user's email
- After logout, Settings screen shows the last user's email (wrong)

### Why this is intentional (sort of)

The DataStore `userId` is the **display** label used on the login screen ("Continue as {email}"). It is intentionally separate from `ProfileAwareCurrentUser` which is session-scoped. The Settings screen is intended to show "who was last logged in on this device", not "which profile is currently active in the app".

However, the UI label "Signed in as {email}" in Settings is misleading after profile switch.

### Why not fixed now

Fixing this requires clarifying the UX contract: should Settings show the last device login, or the current active profile? This is a product decision, not a pure bug.

### How to fix (when decided)

If the desired behavior is "show current profile":
```kotlin
private val signedInUser: Flow<Profile?> = currentUser.scopedUserId
    .flatMapLatest { uid -> profileRepo.getById(uid) }
```

If the desired behavior is "show last device login" (current):
```kotlin
// Add KDoc explaining this is intentional
private val signedInUser: Flow<Profile?> = settings.userId
    .flatMapLatest { uid -> profileRepo.getById(UserId(uid)) }
```

Add a clarifying comment so future agents don't "fix" it as a bug.

---

## Issue I — `TaskRepository.changes` is cross-user (dormant)

**Area:** `feature/tasks/domain/port/TaskRepository`

### Problem

```kotlin
// TaskRepository
val changes: SharedFlow<Task>
    get() = _changes.asSharedFlow()
```

`_changes` emits **all** task mutations from **all** users, not just the current profile's tasks. Any consumer subscribing to `taskRepo.changes` without filtering by `currentUserId` would see cross-user updates.

### Why not a problem today

No production consumer subscribes to `changes` in the current codebase. The flow exists as infrastructure for a future `SyncEngine` (Phase 12), which will be responsible for filtering.

### Rule for future consumers

> **Never** subscribe to `TaskRepository.changes` without filtering by `currentUser.scopedUserId` — the flow emits cross-user. See DIGEST.md Critical rule.

### How to fix (when Phase 12 sync begins)

Option A (producer-side filter — recommended):
Change `TaskRepositoryImpl._changes` to only emit tasks for the current user:
```kotlin
_changes.tryEmit(task)  // currently emits all; needs userId guard
```

Option B (consumer-side filter — if producer filtering is too expensive):
Document clearly that consumers must filter:
```kotlin
taskRepo.changes
    .filter { it.userId == currentUser.scopedUserId.value }
```

Option A is preferred because it is harder for future developers to misuse.

---

## Status

All three issues are **deferred** pending Phase 12 (Supabase + Sync) or product decisions. This ADR serves as a tracking record and prevents these from being "fixed" prematurely as isolated bugs.
