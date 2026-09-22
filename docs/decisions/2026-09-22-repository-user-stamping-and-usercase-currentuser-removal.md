---
title: "Repository stamps ambient userId on create; drop userId params from input classes and use cases"
date: 2026-09-22
tags: [repository, currentuser, userid, draft-store, use-case, koin]
---

## Context

PR 1 established that `ProfileAwareCurrentUser` should live at the repository layer, not the presentation layer. `TaskRepositoryImpl` and `ProjectsRepositoryImpl` were identified as the primary `create` entry points where the ambient user ID must be stamped onto entities.

Three patterns needed to be cleaned up simultaneously:

1. **`create` methods** in `TaskRepositoryImpl` and `ProjectsRepositoryImpl` trusted whatever `userId` was on the entity — callers (VMs, use cases) passed it explicitly. If a caller ever passed the wrong user ID, data would be silently written to the wrong user's scope.

2. **`CreateTaskInput` / `CreateProjectInput`** carried a `userId: UserId` field. This field existed on the *input* object because the use cases needed it to construct the entity. But it created an invariant problem: the input is a pure domain object that shouldn't logically know about ambient scoping — that's a repository concern.

3. **`TaskCreateViewModel`** manually built draft keys as `"${currentUser.scopedUserId.value.value}:${DRAFT_KEY}"` in three places (init restore, debounce save, discard). This string拼接 is error-prone and leaks user-scoping concerns into the presentation layer.

## Decision

### 1. Repository `create` — stamp ambient `userId` with cross-user guard

`TaskRepositoryImpl.create` and `ProjectsRepositoryImpl.create` now resolve `currentUser.scopedUserId` and stamp it onto the entity before inserting. A cross-user guard fails loud rather than silently mis-scoping:

```kotlin
override suspend fun create(item: Task): Result<Task> = runCatching {
    val currentUid = currentUser.scopedUserId.value
    val toInsert = if (item.userId == currentUid || item.userId == UserId.anonymous) {
        item.copy(userId = currentUid)
    } else {
        throw IllegalStateException(
            "Cross-user create attempted: entity.userId=${item.userId}, current=$currentUid",
        )
    }
    taskDao.upsert(toInsert.toEntity())
    // ...
}
```

`UserId.anonymous` is accepted and stamped with the real user — this handles AI tools that construct entities without a real user context.

### 2. Drop `userId` from input classes and domain builders

`CreateTaskInput` and `CreateProjectInput` no longer carry a `userId` field. `TaskDomain.createInput` and `TaskDomain.buildTask` were updated accordingly:

- `createInput(...)` no longer takes a `userId` parameter.
- `buildTask(input, ..., userId: UserId)` now takes `userId` as a separate parameter (resolved by the caller, i.e., the use case).

`ProjectsDomain.buildProject(input, ..., userId: UserId)` similarly updated.

### 3. Use cases resolve ambient `userId` internally

`CreateTaskUseCase`, `CreateTaskFromDraftUseCase`, and `CreateProjectUseCase` now inject `ProfileAwareCurrentUser` and resolve the ambient user ID internally, before calling the repository:

```kotlin
class CreateTaskUseCase(
    private val repo: TaskRepository,
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,  // new
) {
    suspend operator fun invoke(input: CreateTaskInput): Result<TaskId> {
        // ...
        val userId = currentUser.scopedUserId.value
        val task = TaskDomain.buildTask(input, ..., userId = userId)
        return repo.create(task).map { it.id }
    }
}
```

Callers (VMs, AI tools) no longer pass `userId` to use cases or input builders.

### 4. `UserScopedDraftStore` — presentation-layer key isolation

Introduced `core/draft/UserScopedDraftStore.kt` — a `DraftStore` wrapper that prepends `"${currentUser.scopedUserId.value.value}:"` to every key internally. `TaskCreateViewModel` now works with bare draft keys (`"task_create_draft"`), and the wrapper handles user isolation:

```kotlin
class UserScopedDraftStore(
    private val currentUser: ProfileAwareCurrentUser,
    private val delegate: DraftStore,
) : DraftStore {
    private val userIdPrefix: String
        get() = "${currentUser.scopedUserId.value.value}:"
    override suspend fun <T> load(key: String, ...) = delegate.load(userIdPrefix + key, ...)
    override suspend fun <T> save(key: String, ...) = delegate.save(userIdPrefix + key, ...)
    override suspend fun clear(key: String) = delegate.clear(userIdPrefix + key)
}
```

Registered as `single<DraftStore> { UserScopedDraftStore(get(), get()) }` in `TasksDiModule`, replacing the bare `DataStoreDraftStore` binding for that module.

### 5. `TaskCreateViewModel` drops `currentUser` from `TaskCreateDeps`

`TaskCreateDeps` no longer has `currentUser: ProfileAwareCurrentUser`. All three `"${userId}:${DRAFT_KEY}"`拼接 sites were replaced with bare `TaskCreateDeps.DRAFT_KEY`. The use case (`CreateTaskFromDraftUseCase`) resolves ambient `userId` internally.

## Rationale

**Cross-user guard**: silently writing to the wrong user is a data-integrity hole. Fail-loud is the correct behavior for what should be impossible in healthy code.

**Dropping `userId` from inputs**: input classes are pure domain objects — their contract is "here is the business data for this entity". User scoping is an infrastructure concern, not a domain concern. The repository stamps it.

**`UserScopedDraftStore`**: this is the same pattern already documented in the `DraftStore` interface KDoc (`Key format: per-user prefixed`). The difference is the prefix拼接 now lives in one place (the wrapper), not in three places in the VM.

**Use cases injecting `ProfileAwareCurrentUser`**: this is the correct place for ambient context resolution — use cases are the outermost domain layer before the repository. VMs delegate to use cases, so the `userId` concern stops at the use case boundary.

## Consequences

- `TaskRepositoryImpl.create` and `ProjectsRepositoryImpl.create` now enforce user scoping. Any caller passing a mismatched `userId` will get a loud `IllegalStateException`.
- AI tools (`CreateTaskTool`, `CreateProjectTool`) still pass `userId` in their input classes — those are separate from this PR's scope (the AI tool MCP adapter work).
- `RoomNotesRepository.createWithContent` and `createNoteWithTitle` already resolved ambient `userId` internally — no change needed.
- `AttachmentRepository.addUrlAttachment` and `saveFileAttachment` already resolved ambient `userId` internally — no change needed.
- `RoomReminderRepository.deleteByTask` already resolved ambient internally — no change needed.
- `InternalLinkRepositoryImpl` methods (`searchNotes`, `searchTasks`, `getBacklinkNotes`) already resolved ambient internally — no change needed.
- `RoomSavedAgendaViewsRepository.upsert` delegates to `create`/`update`; since `SavedAgendaView` is constructed by the VM with `userId` already on it (from the domain model), stamping happens inside the repository. The Create branch was already handled by the existing `userId` on the entity — no structural change needed.
- **PR 3** (VM cleanup) is unblocked: all repository `create` methods now stamp ambient `userId`, so VMs no longer need to pass it. `currentUser` can be dropped from remaining VMs (`TaskDetailViewModel`, `NotePreview`, `NoteEditor`, `ProjectsViewModel`, `NotesListViewModel`, `ProjectEditorViewModel`, `AttachmentsViewModel`, `SavedAgendaViewModel`, `ProjectDetailViewModel`).

## Links

- Files created: `shared/src/commonMain/kotlin/com/singularity/todo/core/draft/UserScopedDraftStore.kt`
- Files changed: `TaskRepositoryImpl.kt`, `ProjectsRepositoryImpl.kt`, `CreateTask.kt`, `CreateTaskFromDraft.kt`, `CreateProject.kt`, `TaskDomain.kt`, `ProjectsDomain.kt`, `CreateTaskInput` (in `Task.kt`), `CreateProjectInput` (in `Project.kt`), `TaskCreateViewModel.kt`, `TasksDiModule.kt`, `ProjectsDiModule.kt`
- Test files updated: `ProjectsUseCaseTest.kt`, `TasksDomainTest.kt`, `TaskCreateDebounceTest.kt`, `TaskCreateViewModelTest.kt`, `TaskDetailViewModelTest.kt`, `ProjectEditorViewModelTest.kt`, `ProjectDetailViewModelTest.kt`, `ProjectEditorScreen.kt`
- Detekt baseline: 0 findings
- `jvmTest`: green
