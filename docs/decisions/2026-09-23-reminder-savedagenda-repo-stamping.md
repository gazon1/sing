---
title: Reminder + SavedAgenda repository ambient stamping
date: 2026-09-23
status: accepted
---

# Reminder + SavedAgenda repository ambient stamping

## Context

PR 2 established the pattern: repositories own `ProfileAwareCurrentUser` and stamp ambient `userId` on entity creation, with a cross-user guard. This was applied to `TaskRepositoryImpl` and `ProjectsRepositoryImpl`. Two entities were missed:

1. **`Reminder`** — `RoomReminderRepository.upsert` accepted `Reminder.userId` as-is without stamping ambient.
2. **`SavedAgendaView`** — `RoomSavedAgendaViewsRepository.upsert` accepted `SavedAgendaView.userId` as-is; `SavedAgendaViewModel` resolved `currentUser` and passed it to the factory.

Additionally, `TaskDetailViewModel` and `SavedAgendaViewModel` still injected `ProfileAwareCurrentUser` when the data layer is now the correct place to resolve ambient context.

## Decision

### B.1 `RoomReminderRepository.upsert` — stamp ambient with cross-user guard

```kotlin
override suspend fun upsert(reminder: Reminder): Result<Unit> = runCatching {
    val uid = currentUser.scopedUserId.value
    val toInsert = if (reminder.userId == uid || reminder.userId == UserId.anonymous) {
        reminder.copy(userId = uid)
    } else {
        throw IllegalStateException(
            "Cross-user reminder upsert: reminder.userId=${reminder.userId}, current=$uid",
        )
    }
    dao.upsert(toInsert.toEntity(clock.now().toEpochMillis()))
}
```

`UserId.anonymous` is accepted as the "stamp me" sentinel — consistent with the pattern established in PR 2 for other entities.

### B.2 `Reminder.userId` stays required

**Not** made nullable. `Reminder.userId: UserId` is required because:
- `ReminderScheduler.schedule(reminder)` needs the `userId` to construct the alarm key (`"reminder:${userId.value}:${reminderId.value}"`).
- Making it nullable would touch every construction site.

VMs pass `UserId.anonymous` (sentinel) when creating reminders; the repo replaces it with the real ambient ID.

### B.3 `TaskDetailViewModel` — drop `currentUser`

`currentUser` was used only for `Reminder.userId` construction and `ReminderScheduler.cancelByTask`. Resolved by:

1. Added `suspend fun TaskRepository.currentUserId(): UserId` — a simple accessor for the ambient user ID.
2. VM calls `deps.taskRepo.currentUserId()` inline where needed (in `SetReminder` and `DeleteReminder` intent handlers, and `Delete` intent).

This keeps the VM stateless about auth infrastructure — the repository owns ambient context.

### B.4 `RoomSavedAgendaViewsRepository.upsert` — stamp ambient with sentinel

`SavedAgendaView.userId` is `String` (not `UserId`). The factory creates views with `""` as the "stamp me" sentinel. The repository now guards and stamps:

```kotlin
override suspend fun upsert(view: SavedAgendaView): Result<SavedAgendaView> {
    return runCatching {
        val uid = currentUser.scopedUserId.value
        val toInsert = if (view.userId == uid.value || view.userId == "") {
            view.copy(userId = uid.value)
        } else {
            throw IllegalStateException(
                "Cross-user SavedAgendaView upsert: view.userId=${view.userId}, current=${uid.value}",
            )
        }
        agendaViewDao.upsert(toInsert.toEntity())
        toInsert
    }
}
```

`SavedAgendaViewModel` no longer resolves `currentUser` for Create mode — passes `""` to the factory and the repo stamps ambient.

### B.5 `SavedAgendaViewsRepository.duplicateForProfile`

Added a dedicated method to support profile migration without overloading `upsert`:

```kotlin
override suspend fun duplicateForProfile(
    view: SavedAgendaView,
    targetUserId: String,
): Result<SavedAgendaView> {
    val now = clock.now()
    val copy = view.copy(
        id = SavedAgendaViewId.generate(),
        userId = targetUserId,
        createdAt = now,
        updatedAt = now,
    )
    return upsert(copy)
}
```

The `upsert` cross-user guard will throw if `targetUserId != currentUser && targetUserId != ""` — which is correct behavior for cross-user copies that bypass the normal scope.

### B.6 `SavedAgendaDeps` — drop `currentUser`

`SavedAgendaDeps` no longer includes `ProfileAwareCurrentUser`. The `onSave()` method in `SavedAgendaViewModel` now passes `""` (sentinel) to `SavedAgendaViewFactory.create`, letting the repository stamp ambient.

## New interface members

| Interface | New member | Return type |
|---|---|---|
| `TaskRepository` | `suspend fun currentUserId(): UserId` | `UserId` |
| `SavedAgendaViewsRepository` | `suspend fun currentUserId(): String` | `String` |
| `SavedAgendaViewsRepository` | `suspend fun duplicateForProfile(view, targetUserId): Result<SavedAgendaView>` | `Result<SavedAgendaView>` |

## Fake implementations updated

- `FakeTaskRepository`: added `currentUserId()` — delegates to internal `currentUser.scopedUserId.value`.
- `FakeSavedAgendaViewsRepository`: added `currentUserId()` and `duplicateForProfile()`.

## Consequences

- `RoomReminderRepository.upsert` now stamps ambient on insert — no more stale/missing userId.
- `RoomSavedAgendaViewsRepository.upsert` now stamps ambient on insert — consistent with other repos.
- `TaskDetailViewModel` no longer injects `ProfileAwareCurrentUser`.
- `SavedAgendaViewModel` (via `SavedAgendaDeps`) no longer injects `ProfileAwareCurrentUser`.
- Profile migration via `duplicateForProfile` is explicit and testable.
- detekt: 60 warnings (pre-existing, non-blocking) | jvmTest: green.

## Links

- PR 2: `b4ce40b` — repository ambient userId stamping
- ADR: `2026-09-23-dead-currentuser-and-orphan-vm-cleanup.md` (PR A)
- Skill: `singularity-todo-repository-architecture`
- Skill: `singularity-todo-vm-migration-playbook`
- Skill: `singularity-todo-multi-profile`
