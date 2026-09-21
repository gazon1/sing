---
status: accepted
date: 2026-09-21
---

# ProfileRepository Migration to GenericUserScopedRepository

## Context

`ProfileRepository` was the only repository not extending `GenericUserScopedRepository<Profile, ProfileId>`. Its interface used a custom `create(name, emoji, colorIdx): ProfileId` signature instead of the standard `create(item: Profile): Result<Profile>`, and `all()` instead of `observeAll()`.

Callers existed on both paths: Android/Desktop UI (`ProfileSwitcherViewModel`, `SavedAgendaListScreen`, `SavedAgendaListViewModel`) and MCP server bootstrap (`ProfileBootstrapper`).

## Decision

Migrate `ProfileRepository` to extend `GenericUserScopedRepository<Profile, ProfileId>` (Option A).

**Interface changes:**

```kotlin
// Before
interface ProfileRepository {
    fun all(): Flow<List<Profile>>
    suspend fun create(name: String, emoji: String, colorIdx: Int): ProfileId
    suspend fun update(id: ProfileId, name: String, emoji: String, colorIdx: Int)
    suspend fun getById(id: ProfileId): Profile?
    suspend fun switchTo(id: ProfileId)   // was: throws
}

// After
interface ProfileRepository : GenericUserScopedRepository<Profile, ProfileId> {
    fun activeProfile(): Flow<Profile>
    val activeProfileId: StateFlow<ProfileId>
    suspend fun switchTo(id: ProfileId): Result<Unit>  // returns Result
    suspend fun ensureDefaults(extraProfiles: List<Triple<String, String, Int>> = emptyList())
}
```

**Implementation changes:**

- `create(item: Profile): Result<Profile>` — new canonical signature; old `create(name, emoji, colorIdx)` deprecated as `@Deprecated("Use create(Profile)", ReplaceWith("create(Profile(...))")`
- `update(item: Profile): Result<Profile>` — new canonical signature; old `update(id, name, emoji, colorIdx)` deprecated
- `observeAll()` — replaces `all()`
- `observe(id: ProfileId): Flow<Profile?>` — added (maps `profileDao.all()` to find by id)
- `get(id: ProfileId)` — replaces `getById(id)`
- `switchTo(id: ProfileId): Result<Unit>` — now returns `Result<Unit>` instead of throwing
- `delete(id: ProfileId): Result<Unit>` — already returned `Result<Unit>`

**Mapping helpers added to `ProfileRepositoryImpl`:**

```kotlin
private fun Profile.toEntity(): ProfileEntity = ProfileEntity(
    id = id.value, name = name, emoji = emoji, colorIdx = colorIdx,
    isDefault = isDefault,
    createdAt = instantToEpochMillis(createdAt),
    updatedAt = instantToEpochMillis(updatedAt),
)
```

**Callers migrated:**

| File | Change |
|---|---|
| `ProfileSwitcherViewModel` | `all()` → `observeAll()`, `getById(id)` → `get(id)`, `update(id, name, ...)` → `update(profile.copy(name=name))`, `create(name,...)` → `create(Profile(...))` |
| `SavedAgendaListScreen` | `all()` → `observeAll()` |
| `SavedAgendaListViewModel` | `getById(id)` → `get(id)` |
| `ProfileBootstrapper` | `all()` → `observeAll()` |
| `mcp-server/Main.kt` | `all()` → `observeAll()` |

## Rationale

- `ProfileRepository` is the last outlier — bringing it into the generic contract simplifies the codebase and enables future generic tooling (e.g., generic sync, export).
- `switchTo` returning `Result<Unit>` is more consistent with other repositories and the caller-trust model.
- Legacy overloads are deprecated (not removed) to avoid breaking external callers during migration.
- The `Profile` domain model already has all fields needed for the generic interface — no new fields required.

## Consequences

- All 6 repositories now extend `GenericUserScopedRepository`: Tasks, Notes, Projects, Tags, SavedAgendaViews, Profile.
- Detekt `ParameterNaming` rule suppressed in two places (`TagsRepository.kt:54,59`) because `create(item: Tag)` vs `create(item: E)` parameter naming follows the domain convention — not a bug.
- `FakeProfileRepository` implements both new generic methods and deprecated legacy overloads for test compatibility.

## Links

- Parent ADR: [ADR-0015 GenericUserScopedRepository](./2026-09-15-generic-user-scoped-repository.md)
- PR9: `refactor/repository-naming-final` branch
