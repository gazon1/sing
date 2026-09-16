# ADR 2026-09-16 — Saved Agenda Views Persistence

## Status

Accepted — MR2c implementation.

## Context

MR1 provides a fixed set of built-in agenda presets (`Inbox`, `Today`, `Upcoming`) and
project-specific views. Users cannot save custom agenda configurations or share them across profiles.

MR2c adds persistence for user-created agenda views (name + section list), enabling:
- Save custom configurations
- Switch between saved views at runtime
- Delete or rename saved views

**Out of scope for MR2c (→ MR3):**
- UI switching (dropdown to select active saved view)
- `AgendaStartRoute.SavedView` navigation route
- Sharing or duplicating views
- Limits on saved views per profile

## Decision

### Storage: all-in-blob

The entire `AgendaDefinition` (title + sections) is stored as a single JSON column.
No partial indexing or hybrid columns.

```kotlin
@Entity(
    tableName = "agenda_views",
    primaryKeys = ["user_id", "id"],
)
data class AgendaViewEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "user_id", index = true) val userId: String,
    @ColumnInfo(name = "name") val name: String,                   // user-facing label
    @ColumnInfo(name = "sections_json") val sectionsJson: String,  // StableJson.encodeToString(List<Section>)
    @ColumnInfo(name = "created_at") val createdAt: Long,           // epoch millis
    @ColumnInfo(name = "updated_at") val updatedAt: Long,            // epoch millis
)
```

**Rationale:** Users do not need to filter or query saved views by section content.
All access is by `(user_id, id)` — either load all for a profile, or load one by ID.
A separate `title` column (user-facing name) is kept for list display without JSON parsing.
No composite index needed beyond the primary key.

### Composite primary key `(user_id, id)`

Ensures views are strictly isolated per profile. Same view ID in two profiles does not collide.

### No limits

No artificial cap on saved views per profile. Defer limits to MR4 based on user feedback.

### Domain types

```kotlin
@JvmInline
value class SavedAgendaViewId(val raw: String)

@Serializable
data class SavedAgendaView(
    val id: SavedAgendaViewId,
    val userId: UserId,
    val name: String,
    val sections: List<Section>,   // deserialized from sections_json via StableJson
    val createdAt: Instant,
    val updatedAt: Instant,
)

// Composite key for in-memory fake store
@JvmInline
value class SavedAgendaViewKey(val raw: String) // format: "${userId}:${id}"
```

### Repository interface

```kotlin
interface SavedAgendaViewsRepository {
    fun watchAll(userId: UserId): Flow<List<SavedAgendaView>>
    fun watchById(id: SavedAgendaViewId, userId: UserId): Flow<SavedAgendaView?>
    suspend fun upsert(view: SavedAgendaView): Result<SavedAgendaView>
    suspend fun delete(id: SavedAgendaViewId, userId: UserId): Result<Unit>
}
```

### ViewModels (2)

**`SavedAgendaListViewModel`** — no runtime parameters:
```kotlin
class SavedAgendaListViewModel(deps: SavedAgendaListDeps) : ViewModel() {
    val views: StateFlow<List<SavedAgendaView>> = deps.repo.watchAll(deps.userId)
        .stateIn(scope, WhileSubscribed(5_000), Loading)
    // intents: DeleteClicked, EditClicked
}
```

**`SavedAgendaEditViewModel`** — runtime `viewId: SavedAgendaViewId` parameter:
```kotlin
class SavedAgendaEditViewModel(
    deps: SavedAgendaEditDeps,
    val viewId: SavedAgendaViewId,
) : ViewModel() {
    val view: StateFlow<SavedAgendaView?> = deps.repo.watchById(viewId, deps.userId)
        .stateIn(scope, WhileSubscribed(5_000), null)
    // intents: NameChanged, SaveClicked, DeleteClicked
}
```

Both VMs are separate from `AgendaViewModel` — no coupling.

### StableJson mapping

```kotlin
// Entity → Domain
fun AgendaViewEntity.toDomain(): SavedAgendaView {
    val sections = StableJson.decodeFromString<List<Section>>(sectionsJson)
    return SavedAgendaView(
        id = SavedAgendaViewId(id),
        userId = UserId(userId),
        name = name,
        sections = sections,
        createdAt = Instant.fromEpochMilliseconds(createdAt),
        updatedAt = Instant.fromEpochMilliseconds(updatedAt),
    )
}

// Domain → Entity
fun SavedAgendaView.toEntity(): AgendaViewEntity {
    return AgendaViewEntity(
        id = id.raw,
        userId = userId.raw,
        name = name,
        sectionsJson = StableJson.encodeToString(sections),
        createdAt = createdAt.toEpochMilliseconds(),
        updatedAt = updatedAt.toEpochMilliseconds(),
    )
}
```

## Consequences

### Positive
- Simple schema, no migration complexity beyond bumping SCHEMA_VERSION.
- Domain/repo/data layers are fully isolated.
- StableJson round-trip test verifies no data loss.

### Negative
- Cannot filter by `name` in SQL without parsing JSON — acceptable; user-facing
  list is small and fetched entirely.

### Neutral
- `AgendaViewModel` binding is unchanged — does not consume saved views.
- UI switching (MR3) requires adding `definition: AgendaDefinition` to `AgendaViewModel`
  and wiring the saved-view selection mechanism.

## Alternatives considered

### Hybrid storage (title + layout + sections_json)
Rejected. The `title` from `AgendaDefinition.title` is already the user-facing name.
Adding a separate `name` column creates redundancy without meaningful queryability.
All-in-blob is simpler and fully sufficient.

### Unique constraint on `(user_id, name)`
Not added. Users may have multiple views with the same name (e.g., "Work Tasks" in
different profiles, or intentionally duplicated). The composite PK `(user_id, id)` is
the only uniqueness guarantee.
