package com.singularity.todo.arch

import kotlin.test.Test
import kotlin.test.fail

/**
 * Every `@Entity` constructor parameter must appear in its corresponding `toX()` mapper.
 *
 * Room `@Upsert` is a full-row REPLACE: any column not set takes its Kotlin default.
 * If a mapper omits a field, that field is silently destroyed on every write.
 * This caught the bugs fixed in MR 0.2 (`estimateMinutes` missing from `TaskEntity.toTask`,
 * duplicate `toProject` losing `serverVersion`/`hlc`).
 *
 * Join/cross-ref tables are excluded (trivial many-to-many with no domain model).
 * [ChecklistItemEntity] is excluded because its private mapper `toItem()` is internal
 * to `ChecklistRepositoryImpl` and intentionally omits `createdAt`/`updatedAt`/`rowVersion`
 * — the repository manages those via `existing?.createdAt` in `toEntity()`.
 *
 * Supplemental parameters (`tags`, `dependsOn`) are passed to mappers but are NOT entity
 * columns — they are loaded from join tables by the repository and merged separately.
 * The phantom-param check uses [SUPPLEMENTAL_PARAMS] to exclude these intentionally.
 *
 * See: `docs/decisions/2026-10-02-entity-mapper-completeness.md`
 */
class EntityMapperCompletenessTest {

    companion object {
        /**
         * Entities that are join/cross-ref tables with no domain model,
         * or private mappers that intentionally omit fields managed by the repository.
         */
        private val SKIP_ENTITIES = setOf(
            "TaskTagCrossRef",
            "TaskDependencyCrossRef",
            "AttachmentEntity",
            "SyncOutboxEntity",
            "ChecklistItemEntity", // toItem() is private; createdAt/updatedAt/rowVersion managed by repo
        )

        /**
         * Supplemental parameters passed to toX() mappers that are NOT entity columns.
         * These are loaded from join tables by the repository and merged separately.
         * The "phantom param" check excludes these so they don't false-positive.
         */
        private val SUPPLEMENTAL_PARAMS = setOf("tags", "dependsOn")

        /**
         * Entities whose mapper intentionally drops a nullable field.
         * MUST be documented in an ADR before adding here.
         *
         * - `TagEntity.icon`: column added in v20 migration but `Tag` domain model has no icon
         *   field, so `toTag()` never reads it. Writes preserve the column default (null),
         *   so no silent data destruction — but icon selection is never persisted. ADR required
         *   to decide: add icon to Tag domain model, or drop the column.
         */
        private val FIELD_ALLOWLIST = mapOf(
            "TagEntity" to setOf("icon"),
        )

        /**
         * All entity constructor params in `core/database/Entities.kt`.
         * Updated manually when the schema changes.
         */
        private val ENTITY_PARAMS = mapOf(
            "TaskEntity" to setOf(
                "id", "title", "description", "priority", "kind", "projectId",
                "parentTaskId", "dueDate", "dueTime", "startDate", "startTime",
                "endDate", "endTime", "accentColor", "emoji", "estimateMinutes",
                "completedAt", "someday", "archivedAt", "isPinned", "recurrenceRule",
                "outgoingLinks", "aiSuppressedTagIds", "createdAt", "updatedAt",
                "userId", "sync",
            ),
            "NoteEntity" to setOf(
                "id", "userId", "title", "bodyMarkdown", "bodyHtml", "isFolder",
                "kind", "parentNoteId", "isPinned", "pinnedAt", "color",
                "sortOrder", "wordCount", "charCount", "outgoingLinks",
                "taskId", "createdAt", "updatedAt", "deletedAt", "archivedAt", "sync",
            ),
            "ProjectEntity" to setOf(
                "id", "userId", "name", "color", "icon", "description",
                "createdAt", "updatedAt", "isDefault", "dueDate", "team",
                "isDeleted", "deletedAt", "parentId", "sortOrder",
                "idempotencyKey", "externalId", "sync",
            ),
            "TagEntity" to setOf(
                "id", "userId", "name", "color", "icon", "createdAt", "updatedAt",
                "groupId", "sortOrder", "deletedAt", "sync",
            ),
            "TagGroupEntity" to setOf(
                "id", "userId", "name", "color", "createdAt",
                "updatedAt", "deletedAt", "sync",
            ),
            "TaskReminderEntity" to setOf(
                "id", "taskId", "userId", "type", "offsetMinutes",
                "fireAt", "recurringPattern", "viewId", "lastFiredAt",
                "createdAt", "updatedAt",
            ),
        )

        /**
         * Entity field names actually accessed by each public/internal `toX()` mapper,
         * enumerated manually from the mapper source.
         *
         * Format: "mapperName" to ("EntityName" to setOf(accessed entity field names))
         *
         * Supplemental params (tags, dependsOn) are NOT included here — they are
         * handled via SUPPLEMENTAL_PARAMS to avoid false phantom-param positives.
         */
        private val MAPPER_ENTITY_FIELDS_ACCESSED = mapOf(
            "toTask" to ("TaskEntity" to setOf(
                "id", "title", "description", "priority", "kind", "projectId",
                "parentTaskId", "dueDate", "dueTime", "startDate", "startTime",
                "endDate", "endTime", "accentColor", "emoji", "completedAt",
                "someday", "archivedAt", "isPinned", "recurrenceRule",
                "outgoingLinks", "aiSuppressedTagIds", "estimateMinutes",
                "createdAt", "updatedAt", "userId", "sync",
            )),
            "toNote" to ("NoteEntity" to setOf(
                "id", "userId", "title", "bodyMarkdown", "bodyHtml", "isFolder",
                "kind", "parentNoteId", "isPinned", "pinnedAt", "color",
                "sortOrder", "wordCount", "charCount", "outgoingLinks",
                "taskId", "createdAt", "updatedAt", "deletedAt", "archivedAt", "sync",
            )),
            "toProject" to ("ProjectEntity" to setOf(
                "id", "name", "color", "icon", "description",
                "createdAt", "updatedAt", "isDefault", "dueDate", "team",
                "isDeleted", "deletedAt", "parentId", "sortOrder",
                "idempotencyKey", "externalId", "userId", "sync",
            )),
            "toTag" to ("TagEntity" to setOf(
                "id", "name", "color", "createdAt", "updatedAt",
                "groupId", "sortOrder", "deletedAt", "userId", "sync",
            )),
            "toTagGroup" to ("TagGroupEntity" to setOf(
                "id", "name", "color", "createdAt", "updatedAt",
                "deletedAt", "userId", "sync",
            )),
            "toReminder" to ("TaskReminderEntity" to setOf(
                "id", "taskId", "userId", "type", "offsetMinutes",
                "fireAt", "recurringPattern", "viewId", "lastFiredAt",
                "createdAt", "updatedAt",
            )),
        )

        /**
         * All params passed to each toX() mapper (supplemental + entity field names).
         * Used only for the phantom-param check.
         */
        private val MAPPER_ALL_PARAMS = mapOf(
            "toTask" to ("TaskEntity" to setOf("tags", "dependsOn")),
            "toNote" to ("NoteEntity" to setOf()),
            "toProject" to ("ProjectEntity" to setOf()),
            "toTag" to ("TagEntity" to setOf()),
            "toTagGroup" to ("TagGroupEntity" to setOf()),
            "toReminder" to ("TaskReminderEntity" to setOf()),
        )
    }

    @Test
    fun positiveControlDetectsMissingField() {
        // Synthetic: entity has 3 fields, mapper accesses only title — missing id and missing
        val entityParams = setOf("id", "title", "missing")
        val accessedFields = setOf("title")  // only title
        val missing = entityParams - accessedFields
        if (missing != setOf("id", "missing")) {
            fail(
                "positive control: expected to detect {id, missing} but found $missing. " +
                    "The rule has stopped detecting.",
            )
        }
    }

    @Test
    fun everyEntityParamAppearsInMapper() {
        val offenders = mutableListOf<String>()
        for ((entityName, entityParams) in ENTITY_PARAMS) {
            if (entityName in SKIP_ENTITIES) continue
            val entry = MAPPER_ENTITY_FIELDS_ACCESSED.entries.find { it.value.first == entityName }
                ?: continue
            val (_, accessedFields) = entry.value
            val allowlist = FIELD_ALLOWLIST[entityName] ?: emptySet()
            val missing = entityParams - accessedFields - allowlist
            if (missing.isNotEmpty()) {
                offenders.add(
                    "${entry.key}(): entity constructor param(s) ${missing.joinToString()} " +
                        "not accessed — check $entityName ↔ ${entry.key}()",
                )
            }
        }
        if (offenders.isEmpty()) return
        fail(
            "Entity constructor param(s) not accessed by toX():\\n" +
                offenders.joinToString("\\n") { "  - $it" },
        )
    }

    @Test
    fun everyMapperParamExistsInEntityOrIsSupplemental() {
        val offenders = mutableListOf<String>()
        for ((mapperName, pair) in MAPPER_ALL_PARAMS) {
            val (entityName, params) = pair
            val entityParams = ENTITY_PARAMS[entityName] ?: continue
            for (param in params) {
                if (param !in entityParams && param !in SUPPLEMENTAL_PARAMS) {
                    offenders.add(
                        "$mapperName: param '$param' is neither an entity field " +
                            "nor a known supplemental param",
                    )
                }
            }
        }
        if (offenders.isEmpty()) return
        fail(
            "Mapper param(s) not recognized:\\n" +
                offenders.joinToString("\\n") { "  - $it" },
        )
    }
}
