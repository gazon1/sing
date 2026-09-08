---
name: singularity-todo-relational-counts
description: How to display aggregate counts (task count per project, note count per tag) in list screens. Covers JOIN+GROUP BY queries in Room (default for small data), denormalized counter columns (when to use), data class with @Embedded + aggregate, and the ProjectWithCountRow pattern for ProjectCard counts.
---

# Relational Counts Pattern

## The Problem

`ProjectCard` needs to show `"5/12"` (completed/total tasks). The task count is not on the `projects` table — it's on the `tasks` table. How do you get it?

## Two Approaches

### Approach A: JOIN+GROUP BY (default for <10k rows)

```kotlin
// core/database/Daos.kt
data class ProjectWithCountRow(
    @Embedded val project: ProjectEntity,
    val totalCount: Int,
    val completedCount: Int,
)

@Query("""
    SELECT p.*,
           COUNT(t.id) AS totalCount,
           SUM(CASE WHEN t.completed_at IS NOT NULL THEN 1 ELSE 0 END) AS completedCount
    FROM projects p
    LEFT JOIN tasks t ON t.project_id = p.id AND t.archived_at IS NULL
    WHERE p.user_id = :userId AND p.is_deleted = 0
    GROUP BY p.id
    ORDER BY p.sort_order ASC, p.name ASC
""")
fun watchAllWithCounts(userId: String): Flow<List<ProjectWithCountRow>>
```

```kotlin
// feature/projects/ProjectsRepository.kt
data class ProjectWithCount(
    val project: Project,
    val totalCount: Int,
    val completedCount: Int,
)

fun watchProjectsWithCounts(userId: String): Flow<List<ProjectWithCount>> =
    projectDao.watchAllWithCounts(userId).map { rows ->
        rows.map { row ->
            ProjectWithCount(
                project = row.project.toDomain(),
                totalCount = row.totalCount,
                completedCount = row.completedCount,
            )
        }
    }
```

**Pros:** Always consistent, no trigger maintenance, single query.
**Cons:** Slower for very large datasets (>50k rows per user).

### Approach B: Denormalized counter column

```kotlin
// In projects table migration:
ALTER TABLE projects ADD COLUMN task_count INTEGER NOT NULL DEFAULT 0

// tasks table: ON INSERT trigger
CREATE TRIGGER task_count_insert AFTER INSERT ON tasks
BEGIN
    UPDATE projects SET task_count = task_count + 1 WHERE id = NEW.project_id;
END;

// tasks table: ON DELETE trigger
CREATE TRIGGER task_count_delete AFTER DELETE ON tasks
BEGIN
    UPDATE projects SET task_count = task_count - 1 WHERE id = OLD.project_id;
END;
```

**Pros:** Instant count read (no JOIN).
**Cons:** Consistency risk if triggers fire incorrectly; more complex migration; adding `completed_count` requires a second column + more triggers.

## When to Use Which

| Scenario | Approach |
|---|---|
| <10k tasks per user, reactive updates needed | **JOIN (A)** — Room re-emits the Flow on any task change |
| >50k tasks per user, count only needed on list load | Denormalized (B) — load once, not reactive |
| Need completed count too | JOIN (A) — both in one query |
| Count is purely for display (not filter/sort) | JOIN (A) |
| Count is in a hot path (scrolled every frame) | Denormalized (B) — no recomputation |

**Default: Approach A (JOIN).** The performance difference is negligible for <10k rows and the consistency guarantees are worth it.

## Repository Pattern

```kotlin
// ProjectsRepository.kt
interface ProjectsRepository {
    /**
     * Watches all projects for a user WITH task counts.
     * Use this for list screens that show "5/12" on each card.
     * Re-emits when any task's project_id or completed_at changes.
     */
    fun watchProjectsWithCounts(userId: String): Flow<List<ProjectWithCount>>

    /**
     * Watches all projects (no counts).
     * Use for editors and detail screens where counts are not needed.
     */
    fun watchProjects(userId: String): Flow<List<Project>>
}
```

```kotlin
// ProjectsRepositoryImpl.kt
class ProjectsRepositoryImpl(
    private val projectDao: ProjectDao,
    private val taskDao: TaskDao,
) : ProjectsRepository {

    override fun watchProjectsWithCounts(userId: String): Flow<List<ProjectWithCount>> =
        projectDao.watchAllWithCounts(userId).map { rows ->
            rows.map { it.toDomain() }
        }

    override fun watchProjects(userId: String): Flow<List<Project>> =
        projectDao.watchAll(userId).map { entities ->
            entities.map { it.toDomain() }
        }
}
```

## Using in ProjectCard

```kotlin
// feature/projects/components/ProjectCard.kt
@Composable
fun ProjectCard(
    projectWithCount: ProjectWithCount,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (project, totalCount, completedCount) = projectWithCount

    Card(/* ... */) {
        Row {
            ColorCircle(color = Color(project.color))
            Column(modifier = Modifier.weight(1f)) {
                Text(project.name, style = MaterialTheme.typography.titleMedium)
                if (totalCount > 0) {
                    Text(
                        "$completedCount/$totalCount tasks",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ProjectIcon(iconKey = project.icon)
        }
    }
}
```

## Note: `completedCount` is an Int, Not Boolean

```kotlin
// The SQL SUM returns Int (0 if no rows match)
SUM(CASE WHEN t.completed_at IS NOT NULL THEN 1 ELSE 0 END) AS completedCount
// NOT: COUNT(CASE WHEN t.completed_at IS NOT NULL THEN 1 END)
// The latter would return 0L or NULL, which is harder to work with
```

## Testing the JOIN Query

```kotlin
@Test
fun `watchAllWithCounts returns correct counts`() = runTest {
    database.insertProject(project(id = "p1", userId = "u1"))
    database.insertTask(task(id = "t1", projectId = "p1", completedAt = null))
    database.insertTask(task(id = "t2", projectId = "p1", completedAt = Instant.now()))
    database.insertTask(task(id = "t3", projectId = "p1", completedAt = Instant.now()))

    val counts = dao.watchAllWithCounts("u1").first()
    val row = counts.find { it.project.id == "p1" }

    assertEquals(3, row?.totalCount)
    assertEquals(2, row?.completedCount)
}
```

## Extension to Other Entities

This pattern generalises to:

| Parent | Child | Count query |
|---|---|---|
| Project | Task | `COUNT(*) WHERE project_id = p.id` |
| Tag | Note | `COUNT(*) WHERE tag_id = t.id` (via NoteTag cross-ref) |
| Project | Note | `COUNT(*) WHERE project_id = p.id` (if notes have project_id) |
| User | Task (all) | `COUNT(*) WHERE user_id = u.id` |

For Tags ↔ Notes (many-to-many), the JOIN includes the cross-ref table:
```sql
SELECT t.*, COUNT(nt.note_id) AS noteCount
FROM tags t
LEFT JOIN note_tags nt ON nt.tag_id = t.id
LEFT JOIN notes n ON n.id = nt.note_id AND n.is_deleted = 0
WHERE t.user_id = :userId
GROUP BY t.id
```

## Anti-Patterns

1. **Separate query + zip in VM** — `watchProjects().combine(flowOf(taskCounts)) { ... }` works but adds complexity. The JOIN is cleaner.
2. **Denormalize without triggers** — manually updating `task_count` in app code creates inconsistency windows.
3. **`COUNT(*)` instead of `SUM(CASE ... END)`** for completed count — NULL vs 0 edge case.
4. **Filtering `archived_at IS NOT NULL` on the JOIN** — must filter archived tasks out of the count; the SQL above does this correctly.
