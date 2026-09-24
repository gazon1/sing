---
title: "Tag Groups — Orgzly :name: pattern, project inheritance, merge semantics"
date: 2026-09-23
tags: [tags, tag-groups, inheritance]
status: accepted
---

## Context

Tags need grouping. Orgzly uses `:work:`, `:urgent:` namespaces. Users want to:
1. Organize tags into named groups (e.g. `:work:`, `:urgent:`) with a shared color.
2. Inherit entire groups into projects — all tags in the group become visible to tasks in that project.
3. Tags in a group can still be assigned individually to tasks.

Three orthogonal concerns:
1. **Tag grouping**: `Tag.groupId: TagGroupId?` links a tag to its group.
2. **Project inheritance**: `project_tag_groups(project_id, tag_group_id)` join table — a project inherits all tags from assigned groups.
3. **Merge semantics**: effective tags = task's own tags + inherited group tags (deduplicated, own tags take priority).

## Decision

**`TagGroup` domain model:**
```kotlin
@Serializable
@JvmInline value class TagGroupId(val value: String)

data class TagGroup(
    val id: TagGroupId,
    val name: String,          // "work", "urgent"
    val color: Int,            // ARGB
    val createdAt: Instant,
    val updatedAt: Instant,
    val userId: String,
    val deletedAt: Instant? = null,  // soft delete
    val serverVersion: Long = 0,
    val hlc: Hlc? = null,
) : SyncableEntity
```

**`Tag.groupId` replaces `parentId`** (dead schema, removed in migration v20):
```kotlin
data class Tag(
    val id: TagId,
    val name: String,
    val color: Int,
    val groupId: TagGroupId? = null,  // replaces dead parentId
    // ...
)
```

**Join table** — `ProjectInheritedTagGroupCrossRef`:
```kotlin
@Entity(
    tableName = "project_tag_groups",
    primaryKeys = ["project_id", "tag_group_id"],
    indices = [Index("tag_group_id")],
)
data class ProjectInheritedTagGroupCrossRef(
    @ColumnInfo("project_id") val projectId: String,
    @ColumnInfo("tag_group_id") val tagGroupId: String,
)
```

**`EffectiveTagsResolver`** — pure resolver combining own tags + inherited group tags:
```kotlin
class EffectiveTagsResolver(
    private val tagRepo: TagsRepository,
    private val tagGroupRepo: TagGroupRepository,
) {
    fun resolveEffectiveTags(taskOwnTagIds: Set<TagId>, projectId: ProjectId?): Flow<List<Tag>>
    fun resolveEffectiveTagIds(taskOwnTagIds, projectId, allTags, inheritedGroupIds): Set<TagId>
}
```

Uses `LinkedHashSet` to preserve insertion order and deduplicate: own tags added first, group tags appended. If the same tag ID appears in both, own tags take priority (first-seen wins).

**`TagGroupRepository`** — full CRUD + inheritance management:
```kotlin
interface TagGroupRepository {
    fun observeAll(): Flow<List<TagGroup>>
    fun observe(id: TagGroupId): Flow<TagGroup?>
    suspend fun get(id: TagGroupId): TagGroup?
    suspend fun create(input: CreateTagGroupInput): Result<TagGroup>
    suspend fun update(input: UpdateTagGroupInput): Result<TagGroup>
    suspend fun delete(id: TagGroupId): Result<Unit>  // soft delete + clear groupId on member tags
    fun observeInheritedByProject(projectId: ProjectId): Flow<Set<TagGroupId>>
    suspend fun setInheritedForProject(projectId: ProjectId, groupIds: Set<TagGroupId>): Result<Unit>
    suspend fun upsert(tagGroup: TagGroup): TagGroup  // for sync
}
```

**Migration v20 (MR-9)**:
- `tags.parent_id` dropped via `@DeleteColumn` (was already nullable, dead schema)
- `tags.group_id TEXT DEFAULT NULL` added (Room auto-detects from schema diff)
- `tag_groups` table added (new)
- `project_tag_groups` join table added (new)

**Use cases** (MR-10):
- `CreateTagGroupUseCase` — validates name non-blank, color set
- `UpdateTagGroupUseCase` — validates name non-blank
- `DeleteTagGroupUseCase` — soft deletes the group, clears `groupId` on member tags
- `SetProjectInheritedGroupsUseCase` — replaces the full set of inherited groups for a project

## Rationale

**Soft delete over hard delete**: When a tag group is deleted, member tags become ungrouped (groupId = null) rather than being deleted. This preserves tag history and avoids orphaned references.

**Join table over JSON column**: `project_tag_groups` as a proper join table enables efficient SQL queries for "all tags in this project" (including inherited). A JSON column would require parsing and filtering in Kotlin.

**Merge via LinkedHashSet**: Deduplication while preserving order is best expressed by `LinkedHashSet`. Own tags are added first; inherited tags appended. If a tag appears in both, the first (own) wins — correct priority semantics.

**Flat groups (no nesting)**: Nested tag groups (groups of groups) add complexity without clear user need. Design is intentionally flat.

**Sync**: `TagGroup` implements `SyncableEntity` and syncs via `SyncBootstrapper` (DocType.TagGroup). Delete emits a deletion event for the group entity.

## Consequences

- `Task.tags` in list views now needs `EffectiveTagsResolver` to show inherited tags — `TaskExtras` helper (MR-0) loads tags efficiently in batch.
- `TagsRepository.observeAll()` now returns tags with `groupId` populated — UI can display group badges.
- Filter UI (MR-11) can filter by tag group (future): `TaskFilter.ByTagGroups(Set<TagGroupId>)`.
- Tag group deletion is a write operation that cascades to untag member tags — requires `TagDao.bulkUpdateGroupId()` (future improvement, currently a TODO in delete handler).
