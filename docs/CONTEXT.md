# Domain Context

Single source of truth for domain vocabulary. All terms are canonicalized here — code, ADRs, and skills reference these names.

## Profile / User

**Profile** is the multi-profile isolation boundary. Each profile has its own Room database, DataStore, and SecureStorage. Profiles are switched at runtime.

**User** is the Room entity — `entity/User.sq`. A profile always has exactly one root `User`.

**Never use** `User` to mean "the currently active profile" — use `Profile` or `currentProfile`.

**Used in:** `core/di`, `ProfileRepository`, `ProfileAwareCurrentUser`, multi-profile ADRs

---

## TagGroup

**TagGroup** is the grouping entity for tags. Tags belong to a group. A group has a name and color.

**Also known as:** TagCategory (informal, avoid)

**Used in:** `feature/tags/`, `TagGroupRepository`, `TagGroupsViewModel`

---

## SavedAgendaView

**SavedAgendaView** is the Room entity — stored user layout for an agenda tab. It holds selector configuration and display preferences.

**Also known as:** AgendaView (informal), SavedView

**Contrast with:** `AgendaDefinition` — the pure-domain selector+evaluator config (not stored)

**Used in:** `feature/agenda/`, `SavedAgendaViewsRepository`

---

## AgendaDefinition

**AgendaDefinition** is the pure-domain selector+evaluator configuration for an agenda. It is not stored — it is constructed at runtime from `Agenda` settings.

**Contrast with:** `SavedAgendaView` — the stored layout

**Used in:** `AgendaEvaluator`, `AgendaNavGraph`, `AgendaScreen`

---

## Inbox

**Inbox** is the default task collection — all tasks not assigned to a project. It is represented as `TaskFilter.Inbox`.

**Also known as:** Inbox tasks, unfiled tasks

**Used in:** `TaskFilter`, `TaskRepository.observeByFilter`, `AgendaEvaluator`

---

## MviViewModel / MviIntent / UiState

**MviViewModel** is the base ViewModel class using the MVI pattern. It takes `initialState`, `scope`, and exposes `updateState` / `setState` and the `catchTo` / `emitError` error helpers.

**MviIntent** is the sealed interface for user intents — each screen defines its own `sealed interface FooIntent : MviIntent`.

**UiState** is the sealed interface for screen state — each screen defines `sealed interface FooUiState`.

**Used in:** all feature ViewModels, `core/ui/MviViewModel.kt`

---

## Domain ID types (value classes)

All domain IDs are `@JvmInline value class` wrappers over `String`:

| Type | Wraps | Location |
|---|---|---|
| `TaskId` | `String` | `core/ids/TaskId.kt` |
| `NoteId` | `String` | `core/ids/NoteId.kt` |
| `ProjectId` | `String` | `core/ids/ProjectId.kt` |
| `TagId` | `String` | `core/ids/TagId.kt` |
| `ProfileId` | `String` | `core/ids/ProfileId.kt` |
| `SavedAgendaViewId` | `String` | `core/ids/SavedAgendaViewId.kt` |
| `TagGroupId` | `String` | `feature/tags/domain/model/TagGroupId.kt` |

**Never compare IDs with `==` in tests** — use `==` on the value class directly, which delegates to `String.equals`.

**Used in:** all domain models, repository interfaces

---

## cascadeUp

**cascadeUp** is the tree inheritance query in `core/tree/Cascade.kt`. It walks ancestors to collect inherited properties (effective priority, color, tags).

**Never walk ancestors ad-hoc** with `find { it.parentId == ... }` chains — always use `cascadeUp`.

**Used in:** `core/tree/Cascade.kt`, `Task`, `Project`

---

## traverseDepthFirst

**traverseDepthFirst** is the TreeVisitor traversal in `core/tree/TreeVisitor.kt`. Use for recursive tree operations.

**Never write recursive `.filter { … }.map { … }` chains** — use `traverseDepthFirst`.

**Used in:** `AgendaEvaluator`, tree-walking operations

---

## HLC (Hybrid Logical Clocks)

**HLC** is the `sync/Hlc.kt` implementation for distributed timestamps. Each entity carries `hlc: HlcTimestamp` for conflict resolution.

**Never use `System.currentTimeMillis()`** for ordering — use `Hlc.now()`.

**Used in:** `SyncOutbox`, `ConflictResolver`, all synced entities

---

## Koin Bridge

**koinBridge { }** is the coroutine bridge for use inside Koin `module { }` blocks. It wraps `runBlocking` safely and is required for any suspend call in a factory.

**Never use `runBlocking { }` inside `module { }`** — always use `koinBridge { }`.

**Used in:** `PlatformModule.jvm.kt`, `PlatformModule.android.kt`, all Koin modules

---

## ProfileAwareCurrentUser

**ProfileAwareCurrentUser** is the current-user accessor injected into repositories. It reads from `SecureStoragePort` and provides `profileId: ProfileId`.

**Never hard-code a static user** — always inject `ProfileAwareCurrentUser`.

**Used in:** all repository factories, `ProfileAwareCurrentUserTestHelper`
