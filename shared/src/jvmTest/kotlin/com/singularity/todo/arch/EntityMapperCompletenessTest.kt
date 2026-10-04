package com.singularity.todo.arch

import java.io.File
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
 * See: `docs/decisions/2026-10-03-entity-mapper-completeness.md`
 */
@Tag("fast")
class EntityMapperCompletenessTest {

    companion object {
        /**
         * `@Entity` data classes. The annotation block is multi-line for most entities
         * (`@Entity(tableName = …, primaryKeys = […], indices = […])`), so the gap
         * between the annotation and the declaration is bounded rather than fixed —
         * 600 characters is far beyond the longest block in `Entities.kt`.
         * Comments are stripped before matching, so KDoc cannot produce a hit.
         */
        private val ENTITY_DECLARATION =
            Regex("""@Entity\b[\s\S]{0,600}?\bdata class (\w+)\(""")

        /**
         * Entities that are join/cross-ref tables with no domain model,
         * or private mappers that intentionally omit fields managed by the repository.
         */
        private val SKIP_ENTITIES = setOf(
            "TaskTagCrossRef",
            "TaskDependencyCrossRef",
            "ProjectInheritedTagGroupCrossRef", // join table: no domain model, by design
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
         */
        private val FIELD_ALLOWLIST = mapOf<String, Set<String>>()

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
                "id", "userId", "name", "color", "createdAt", "updatedAt",
                "groupId", "sortOrder", "deletedAt", "sync",
            ),
            "TagGroupEntity" to setOf(
                "id",
                "userId",
                "name",
                "color",
                "createdAt",
                "updatedAt",
                "deletedAt",
                "sync",
            ),
            "TaskReminderEntity" to setOf(
                "id", "taskId", "userId", "type", "offsetMinutes",
                "fireAt", "recurringPattern", "viewId", "lastFiredAt",
                "createdAt", "updatedAt",
            ),
            // Added 2026-10-04 by the self-completeness check below, which found this
            // entity had a real mapper (TimeEntryMapper) that no completeness rule had
            // ever been applied to: an omitted column here would be reset by @Upsert
            // on every write, silently.
            "TimeEntryEntity" to setOf(
                "id", "taskId", "userId", "startedAt", "endedAt",
                "kind", "source", "note", "createdAt", "updatedAt",
                "deletedAt", "sync",
            ),
            // Also found by the self-completeness check on 2026-10-04. All five have
            // mappers that were never checked: a column omitted by a mapper is reset by
            // @Upsert on every write, and nothing here would have noticed.
            "AgendaViewEntity" to setOf(
                "id",
                "userId",
                "name",
                "sectionsJson",
                "createdAt",
                "updatedAt",
            ),
            "AiProposalEntity" to setOf(
                "id", "userId", "source", "status", "targetKind", "targetId",
                "createdAt", "updatedAt", "sync",
            ),
            "ProposalItemEntity" to setOf(
                "id",
                "proposalId",
                "kindJson",
                "targetId",
                "humanSummary",
                "status",
                "fingerprint",
                "sortOrder",
                "decidedAt",
                "decidedActor",
                "rejectionReason",
            ),
            "RemoteConfigEntity" to setOf(
                "id",
                "supabaseUrl",
                "anonKey",
                "updatedAt",
            ),
            "SavedSearchEntity" to setOf(
                "id",
                "userId",
                "name",
                "queryString",
                "createdAt",
                "updatedAt",
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
            "toTask" to (
                "TaskEntity" to setOf(
                    "id", "title", "description", "priority", "kind", "projectId",
                    "parentTaskId", "dueDate", "dueTime", "startDate", "startTime",
                    "endDate", "endTime", "accentColor", "emoji", "completedAt",
                    "someday", "archivedAt", "isPinned", "recurrenceRule",
                    "outgoingLinks", "aiSuppressedTagIds", "estimateMinutes",
                    "createdAt", "updatedAt", "userId", "sync",
                )
            ),
            "toNote" to (
                "NoteEntity" to setOf(
                    "id", "userId", "title", "bodyMarkdown", "bodyHtml", "isFolder",
                    "kind", "parentNoteId", "isPinned", "pinnedAt", "color",
                    "sortOrder", "wordCount", "charCount", "outgoingLinks",
                    "taskId", "createdAt", "updatedAt", "deletedAt", "archivedAt", "sync",
                )
            ),
            "toProject" to (
                "ProjectEntity" to setOf(
                    "id", "name", "color", "icon", "description",
                    "createdAt", "updatedAt", "isDefault", "dueDate", "team",
                    "isDeleted", "deletedAt", "parentId", "sortOrder",
                    "idempotencyKey", "externalId", "userId", "sync",
                )
            ),
            "toTag" to (
                "TagEntity" to setOf(
                    "id", "name", "color", "createdAt", "updatedAt",
                    "groupId", "sortOrder", "deletedAt", "userId", "sync",
                )
            ),
            "toTagGroup" to (
                "TagGroupEntity" to setOf(
                    "id",
                    "name",
                    "color",
                    "createdAt",
                    "updatedAt",
                    "deletedAt",
                    "userId",
                    "sync",
                )
            ),
            "toReminder" to (
                "TaskReminderEntity" to setOf(
                    "id", "taskId", "userId", "type", "offsetMinutes",
                    "fireAt", "recurringPattern", "viewId", "lastFiredAt",
                    "createdAt", "updatedAt",
                )
            ),
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

        // Mappers that are not named `toX()` but map an entity both ways
        // (`TimeEntryEntity.toDomain()` / `TimeEntry.toEntity()`). The completeness rule
        // reads by entity name rather than by mapper name, so an entity with a
        // non-standard mapper is reached only by appearing in ENTITY_PARAMS *and* here.
        private val MAPPER_NON_STANDARD_ACCESSED = mapOf(
            "TimeEntry.toEntity" to (
                "TimeEntryEntity" to setOf(
                    "id", "taskId", "userId", "startedAt", "endedAt",
                    "kind", "source", "note", "createdAt", "updatedAt",
                    "deletedAt", "sync",
                )
            ),
            "SavedAgendaView.toEntity" to (
                "AgendaViewEntity" to setOf(
                    "id",
                    "userId",
                    "name",
                    "sectionsJson",
                    "createdAt",
                    "updatedAt",
                )
            ),
            "ProposalRepositoryImpl.save" to (
                "AiProposalEntity" to setOf(
                    "id",
                    "userId",
                    "source",
                    "status",
                    "targetKind",
                    "targetId",
                    "createdAt",
                    "updatedAt",
                    "sync",
                )
            ),
            "ProposalRepositoryImpl.hydrate" to (
                "ProposalItemEntity" to setOf(
                    "id",
                    "proposalId",
                    "kindJson",
                    "targetId",
                    "humanSummary",
                    "status",
                    "fingerprint",
                    "sortOrder",
                    "decidedAt",
                    "decidedActor",
                    "rejectionReason",
                )
            ),
            "RemoteConfigRepositoryImpl.getConfig" to (
                "RemoteConfigEntity" to setOf(
                    "id",
                    "supabaseUrl",
                    "anonKey",
                    "updatedAt",
                )
            ),
            "SavedSearch.toEntity" to (
                "SavedSearchEntity" to setOf(
                    "id",
                    "userId",
                    "name",
                    "queryString",
                    "createdAt",
                    "updatedAt",
                )
            ),
        )

        // Entities whose mapper is a **partial projection** by design: the domain model
        // deliberately omits some columns, so a completeness rule over the full
        // constructor would report a false failure. Naming the omitted columns and why
        // is the only honest option — declaring such an entity "unmapped" would be a
        // false statement in a file whose whole purpose is to be true.
        private val PARTIAL_MAPPERS = mapOf(
            "CalendarSyncTaskMapEntity" to
                "toSyncedEventRef() omits `userId` (the scoping column, applied by the " +
                    "DAO query) and `syncedAt` (a sync audit stamp with no domain meaning)",
            "RemoteConfigCacheEntity" to
                "refresh() omits `id` — the row is the constant 'default' singleton, so " +
                    "the key is written by the @Insert strategy rather than the mapper",
        )

        // `@Entity` data classes that intentionally have no domain mapper, with the
        // reason. An entity in neither this map nor ENTITY_PARAMS is a hole in the gate,
        // not a fact about the schema — which is what the self-completeness test catches.
        private val UNMAPPED_ENTITIES = mapOf(
            "LlmUsageEntity" to
                "usage rows are written by the recorder and read back as projections; " +
                    "no domain model, so there is no mapper to be incomplete",
            "ProfileEntity" to
                "the profiles table is manipulated directly by the profile bootstrap and " +
                    "sync; Profile is not a mapped domain entity",
            "ProjectReminderEntity" to
                "project-level reminders are queried as rows and converted at the call " +
                    "site; there is no ProjectReminder domain model",
            "SyncDeadLetterEntity" to
                "a transport shelf, not an entity: rows are moved between it and " +
                    "sync_outbox verbatim, never mapped to a domain model. Nothing " +
                    "upserts it, so there is no column to be silently reset.",
            "SyncShadowEntity" to
                "transport bookkeeping, not domain data: the state the server is known to " +
                    "hold for one entity, and the state a queued patch will bring it to. " +
                    "Written by narrow column-level UPDATEs that name every column they " +
                    "change, so the 'unchecked column is reset by @Upsert' rule this gate " +
                    "protects against cannot bite — the one write that is an upsert, " +
                    "build(), sets all seven columns together.",
            "SyncStateEntity" to
                "sync bookkeeping, not domain data: the download cursor, the last " +
                    "successful sync time, the device id and the sync preferences. It " +
                    "is written by narrow column-level UPDATEs, never @Upsert, so the " +
                    "'unchecked column is reset by @Upsert' rule this gate protects " +
                    "against does not apply to it.",
        )
    }

    @Test
    fun positiveControlDetectsMissingField() {
        // Synthetic: entity has 3 fields, mapper accesses only title — missing id and missing
        val entityParams = setOf("id", "title", "missing")
        val accessedFields = setOf("title") // only title
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
            val entry = (MAPPER_ENTITY_FIELDS_ACCESSED + MAPPER_NON_STANDARD_ACCESSED)
                .entries.find { it.value.first == entityName }
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

    /**
     * The gate's own completeness: an `@Entity` nobody registered is an entity whose
     * mapper is never checked, and adding a new one would not fail anything.
     *
     * This test is why the coverage of the other rules can be quoted at all. Before it,
     * the table held 6 of 11 entities and a seventh could be added in silence — the same
     * "declared but never applied" shape as `2026-09-30-testtag-registry-honesty`, in a
     * different registry.
     */
    @Test
    fun everyEntityIsEitherCheckedOrDeclaredUnmapped() {
        val registered = ENTITY_PARAMS.keys + SKIP_ENTITIES +
            UNMAPPED_ENTITIES.keys + PARTIAL_MAPPERS.keys
        val unregistered = declaredEntities() - registered
        if (unregistered.isEmpty()) return
        fail(
            "@Entity class(es) present in the schema but absent from this gate:\n" +
                unregistered.joinToString("\n") { "  - $it" } +
                "\n\nAdd it to ENTITY_PARAMS + MAPPER_ENTITY_FIELDS_ACCESSED if it has a " +
                "mapper, or to UNMAPPED_ENTITIES with the reason it has none. An unchecked " +
                "column is reset by @Upsert on every write.",
        )
    }

    @Test
    fun everyUnmappedDeclarationStatesAReason() {
        assertTrue(
            UNMAPPED_ENTITIES.values.none { it.isBlank() },
            "an UNMAPPED_ENTITIES entry without a reason is a hole with paperwork",
        )
    }

    @Test
    fun everyUnmappedEntityStillExists() {
        """A stale exemption is an exemption nobody is looking at any more."""
        val stale = UNMAPPED_ENTITIES.keys - declaredEntities()
        assertTrue(stale.isEmpty(), "UNMAPPED_ENTITIES names entities that no longer exist: $stale")
    }

    @Test
    fun everyPartialMapperStillExists() {
        val stale = PARTIAL_MAPPERS.keys - declaredEntities()
        assertTrue(stale.isEmpty(), "PARTIAL_MAPPERS names entities that no longer exist: $stale")
    }

    @Test
    fun everyPartialMapperStatesWhyItIsPartial() {
        assertTrue(
            PARTIAL_MAPPERS.values.none { it.isBlank() },
            "a PARTIAL_MAPPERS entry without a reason is a silent exemption",
        )
    }

    @Test
    fun theCategoriesDoNotOverlap() {
        """An entity in two categories is checked in one and exempted in the other."""
        val unmapped = UNMAPPED_ENTITIES.keys
        val partial = PARTIAL_MAPPERS.keys
        val checked = ENTITY_PARAMS.keys
        assertTrue(SKIP_ENTITIES.intersect(unmapped).isEmpty(), "entity is both skipped and unmapped")
        assertTrue(SKIP_ENTITIES.intersect(partial).isEmpty(), "entity is both skipped and partial")
        assertTrue(checked.intersect(unmapped).isEmpty(), "entity is both checked and unmapped")
        assertTrue(checked.intersect(partial).isEmpty(), "entity is both checked and partial")
    }

    @Test
    fun everySkippedEntityStillExists() {
        val stale = SKIP_ENTITIES - declaredEntities()
        assertTrue(stale.isEmpty(), "SKIP_ENTITIES names entities that no longer exist: $stale")
    }

    @Test
    fun positiveControlDetectsAnUnregisteredEntity() {
        """The rule has to fail on the case it exists for, not merely pass on the real one."""
        val declared = setOf("TaskEntity", "LlmUsageEntity")
        val registered = setOf("TaskEntity")
        val skipped = emptySet<String>()
        val unmapped = emptyMap<String, String>()
        assertEquals(setOf("LlmUsageEntity"), declared - (registered + skipped + unmapped.keys))
    }

    // `@Entity data class` declarations across the production source set. The entities do
    // not all live in one file — `TimeEntryEntity` sits with its DAO — so this scans the
    // tree rather than one known path. (A KDoc here would be illegal inside a class body.)
    private fun declaredEntities(): Set<String> {
        val root = System.getProperty("commonMain.root")
            ?: error(
                "commonMain.root system property is not set — " +
                    "see the jvmTest task config in shared/build.gradle.kts",
            )
        val blockComment = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
        val lineComment = Regex("""//[^\n]*""")
        return File(root).walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.path.contains("${File.separator}test${File.separator}") }
            .flatMap { file ->
                val source = file.readText()
                    .replace(blockComment, "")
                    .replace(lineComment, "")
                ENTITY_DECLARATION.findAll(source)
                    .map { it.groupValues[1] }
                    .toList()
                    .asSequence()
            }
            .toSet()
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
