# Stage 1 — ownership manifest (no moves)

Commit: `ce143f36f` + `androidApp` `compileSdkMinor = 1` (uncommitted). No file has been moved.

Method: `rg`/IDE symbol search over the direct Room set, its imports, and the same-package
references; the compiler in Stages 2/3 is the final resolver (a missed reference is a compile error,
not a silent gap). No hand-written Kotlin dependency analyzer.

Counts: `core/database/**` has **60** files (the plan's 57 was stale — it omitted the three
`contract/` files). The 28 `@Entity`/`@Dao` files are a subset of the rows below; the union of the
28 annotated files and the 60-file package is the `:core-database` set. The `:core-domain` set is
the confirmed dependency closure, not the whole 55-file `domain/model` package.

## Corrections after the Stage 1 review (2026-11-05)

- **Compose.** The review's premise that `Task`/`Project`/`TagGroup`/`Note` carry `@Immutable` is
  not borne out by the source: none of the moved models imports `androidx.compose`; their `stable`
  verdict in the Stage 0 report comes from the stability configuration, which already lists `Task`,
  `Project`, `Note`, `TagGroup`. The only Compose annotations in the moved set are `@Immutable` on
  `core/text/FieldOwnedText.kt`, `Revisioned.kt`, `TextFieldSource.kt`. Stage 2 removes those three
  annotations and adds the types to `config/compose/stability_config.conf`; the after-move report is
  compared with `build-investigation/compose-stability-before/`.
- **Conditional files are now definite** — see the compiler-check section; each has an owner and the
  test that would overturn it.
- **`core/ids/**`** was a copy/paste row in the `:shared` table; it is removed. The four ID value
  classes and `IdGen.kt` (`nextId()`, called by `TaskId.generate()`) move to `:core-domain`; the
  `IdGenerator` port stays in `:shared`.
- **Platform source sets.** Stage 2/3 verify every `expect` has its `actual` in `jvmMain`/`androidMain`;
  the module targets only `jvm()` + `androidTarget()`; no iOS, no platform code in `commonMain`.

## Stage 2 — compiler-verified closure (2026-11-05)

The Stage 1 31-file list was not the full closure. The compiler found these additional files (each is a
real reference, not a guess):

| File | Why it is in `:core-domain` |
|---|---|
| `feature/tags/Ids.kt` | `Task` uses `TagId`; `Mappers` imports `TagId` |
| `feature/proposals/domain/model/ProposalValues.kt` | `ProposalItemKind`'s `ReadResult`/`ProposedProject`/`ProposedNote`; `AiProposal` |
| `core/database/LocalTimeFormats.kt` | `ProposalValues` formats/parses time; moved to `core.format` (the DB module depends on the domain, not the reverse) |
| `core/format/Padding.kt` | `LocalTimeFormats` uses `formatPadded`; pure |
| `core/search/TextMatch.kt` | `SearchMode.matches` |
| `core/search/TextMatchRange.kt` | `TextMatch` uses `TextMatchRange`/`SearchColumn` |
| `core/search/DiacriticFold.kt` | `TextMatch` folds diacritics |
| `feature/agenda/domain/model/SelectorSerializer.kt` | `Selector` is `@Serializable(with = SelectorSerializer::class)` |
| `feature/proposals/domain/model/ProposalStatusReducer.kt` | `AiProposal` uses it |

That makes **40 `:core-domain` production files** plus `DiacriticFoldTest.kt`, which moved with its
class because `DiacriticFold` is `internal`.

Stage 2 `:shared` changes forced by the module boundary:

- 12 cross-module smart-cast sites replaced with local vals (`Task.dueDate`/`dueTime`/`recurrence`,
  `Note.color`/`bodyHtml`, `Project.parentId`) in `SelectorMatcher`, `CalendarEventMapper`,
  `NoteCard`, `NotePreviewScreen`, `ProjectDetailViewModel`, `TaskDomain`, `Computed`,
  `TaskDetailContent`.
- `LocalTimeFormats` moved to `core.format`; the `:shared` imports updated in `Mappers`,
  `LocalTimeSerializer`, `BackupTaskDtos`, `UpdateTaskTool`, `ProposalKindCodec` and the two tests.
- `DiacriticFoldTest` moved to `:core-domain` `commonTest`; its deps are `kotlin-test-junit5` +
  `junit-jupiter`, and `jvmTest` uses the JUnit platform.

Build: `:core-domain:compileKotlinJvm`, `:core-domain:compileTestKotlinJvm`,
`:shared:compileKotlinJvm`, `:shared:compileTestKotlinJvm` green.

The after-move Compose report first flagged `RecurrenceSpec`, `TimeEntry` and `EditorState.Editing`
as runtime-unstable (they were inferred stable before only because they shared the Compose module);
they are now in the config. The re-run has every moved-type parameter `stable`, and the `desktopApp`
report is unchanged. The after reports are in `build-investigation/compose-stability-after/`.

## `:core-database` — Room/KSP, DAOs, entities, mappers, schemas, migrations

| File | Owner | Reason |
|---|---|---|
| `core/database/AppDatabase.kt` | `:core-database` | Room `@Database`; the aggregating output that regenerates all DAO impls |
| `core/database/AppDatabaseFactory.kt` | `:core-database` | builds `AppDatabase`; DB-only |
| `core/database/Daos.kt` | `:core-database` | Room `@Dao` for tasks/projects/notes/tags/etc. |
| `core/database/Entities.kt` | `:core-database` | Room `@Entity` classes + `SyncColumns`; imports `TaskKind`/`TaskPriority` from `:core-domain` |
| `core/database/Mappers.kt` | `:core-database` | entity↔domain mappers; imports the 12 domain models (the seed) |
| `core/database/AgendaViewDao.kt` | `:core-database` | Room DAO for agenda views |
| `core/database/AgendaViewEntity.kt` | `:core-database` | Room entity (primitive `sectionsJson`; the model mapping stays in `:shared`) |
| `core/database/FtsDao.kt` | `:core-database` | Room FTS DAO |
| `core/database/FtsEntities.kt` | `:core-database` | Room FTS entities |
| `core/database/FtsMatchQuery.kt` | `:core-database` | FTS query builder |
| `core/database/FtsMatchRows.kt` | `:core-database` | FTS result rows |
| `core/database/FtsOffsets.kt` | `:core-database` | FTS offset mapping |
| `core/database/ProjectInheritedTagGroupCrossRef.kt` | `:core-database` | Room cross-reference |
| `core/database/SavedSearchDao.kt` | `:core-database` | Room DAO |
| `core/database/SavedSearchEntity.kt` | `:core-database` | Room entity |
| `core/database/OwnerEraseDao.kt` | `:core-database` | owner-scoped erase DAO |
| `core/database/OwnerReassignDao.kt` | `:core-database` | owner reassignment DAO |
| `core/database/OwnerScopeDao.kt` | `:core-database` | owner-scope DAO |
| `core/database/UnitOfWork.kt` | `:core-database` | DB transaction unit |
| `core/database/contract/PlatformPragmas.kt` | `:core-database` | SQLite pragma contract |
| `core/database/contract/SqlDriverFactory.kt` | `:core-database` | SQLite driver factory (common) |
| `core/database/contract/WrappingDriver.kt` | `:core-database` | driver wrapper (common) |
| `core/database/Migrations.kt` | `:core-database` | migration registry |
| `core/database/Migration15To16.kt` | `:core-database` | Room migration |
| `core/database/Migration17To18.kt` | `:core-database` | Room migration |
| `core/database/Migration18To19.kt` | `:core-database` | Room migration |
| `core/database/Migration19To20.kt` | `:core-database` | Room migration |
| `core/database/Migration20To21.kt` | `:core-database` | Room migration |
| `core/database/Migration21To22.kt` | `:core-database` | Room migration |
| `core/database/Migration22To23.kt` | `:core-database` | Room migration |
| `core/database/Migration23To24.kt` | `:core-database` | Room migration |
| `core/database/Migration24To25.kt` | `:core-database` | Room migration |
| `core/database/Migration25To26.kt` | `:core-database` | Room migration |
| `core/database/Migration26To27.kt` | `:core-database` | Room migration |
| `core/database/Migration27To28.kt` | `:core-database` | Room migration |
| `core/database/Migration28To29.kt` | `:core-database` | Room migration |
| `core/database/Migration29To30.kt` | `:core-database` | Room migration |
| `core/database/Migration30To31.kt` | `:core-database` | Room migration |
| `core/database/Migration31To32.kt` | `:core-database` | Room migration |
| `core/database/Migration32To33.kt` | `:core-database` | Room migration |
| `core/database/Migration33To34.kt` | `:core-database` | Room migration |
| `core/database/Migration34To35.kt` | `:core-database` | Room migration |
| `core/database/Migration35To36.kt` | `:core-database` | Room migration |
| `core/database/Migration36To37.kt` | `:core-database` | Room migration |
| `core/database/Migration37To38.kt` | `:core-database` | Room migration |
| `core/database/Migration38To39.kt` | `:core-database` | Room migration |
| `core/database/Migration39To40.kt` | `:core-database` | Room migration |
| `core/database/Migration40To41.kt` | `:core-database` | Room migration |
| `core/database/Migration41To42.kt` | `:core-database` | Room migration |
| `core/database/Migration42To43.kt` | `:core-database` | Room migration |
| `core/database/Migration44To45.kt` | `:core-database` | Room migration |
| `core/database/Migration45To46.kt` | `:core-database` | Room migration |
| `core/database/Migration46To47.kt` | `:core-database` | Room migration |
| `core/database/Migration47To48.kt` | `:core-database` | Room migration |
| `core/database/Migration48To49.kt` | `:core-database` | Room migration |
| `core/database/Migration49To50.kt` | `:core-database` | Room migration |
| `core/database/Migration50To51.kt` | `:core-database` | Room migration |
| `core/database/Migration51To52.kt` | `:core-database` | Room migration |
| `core/database/Migration52To53.kt` | `:core-database` | Room migration |
| `core/attachments/AttachmentEntity.kt` | `:core-database` | Room `@Entity` (primitive columns) |
| `core/attachments/AttachmentDao.kt` | `:core-database` | Room DAO |
| `core/attachments/AttachmentConverters.kt` | `:core-database` | Room type converters; references `AttachmentType`/`AttachmentSyncStatus` from `:core-domain` |
| `core/attachments/annotation/AttachmentAnnotationEntity.kt` | `:core-database` | Room `@Entity` |
| `core/attachments/annotation/AttachmentAnnotationDao.kt` | `:core-database` | Room DAO |
| `core/config/RemoteConfigCacheEntity.kt` | `:core-database` | Room entity + DAO (both declared in this file) |
| `core/sync/SyncOutbox.kt` | `:core-database` | Room entity + DAO |
| `core/sync/SyncState.kt` | `:core-database` | Room entity + DAO |
| `core/sync/SyncShadow.kt` | `:core-database` | Room entity + DAO |
| `core/sync/SyncDeadLetter.kt` | `:core-database` | Room entity + DAO |
| `core/sync/RemoteConfig.kt` | `:core-database` | Room entity + DAO |
| `feature/calendarSync/data/GoogleSyncEntities.kt` | `:core-database` | whole file: 3 Room entities + 3 DAOs, no domain imports |
| `feature/calendarSync/data/CalendarSyncTaskMapDao.kt` | `:core-database` | Room DAO |
| `feature/calendarSync/data/CalendarSyncTaskMapMappers.kt` | `:core-database` | entity→domain mapper; imports `SyncedEventRef` from `:core-domain` |
| `feature/proposals/data/ProposalDao.kt` | `:core-database` | Room DAO + `AiProposalEntity` |
| `feature/proposals/data/ProposalItemDao.kt` | `:core-database` | Room DAO + `ProposalItemEntity` |
| `feature/proposals/data/ProposalMapper.kt` | `:core-database` | entity→domain mapper; imports the proposals models |
| `feature/timetracking/data/TimeEntryDao.kt` | `:core-database` | Room DAO + `TimeEntryEntity` |
| `feature/timetracking/data/TimeEntryMapper.kt` | `:core-database` | entity→domain mapper; imports `TimeEntry`/`TimeEntryKind`/`TimeEntrySource` |
| `shared/src/jvmMain/.../core/database/AppDatabaseFactoryJvm.kt` | `:core-database` | DB factory actual (JVM) |
| `shared/src/jvmMain/.../core/database/contract/SqlDriverFactory.jvm.kt` | `:core-database` | driver actual (JVM) |
| `shared/src/jvmMain/.../core/database/contract/StaleDbCleaner.kt` | `:core-database` | DB cleanup (JVM) |
| `shared/src/jvmMain/.../core/database/contract/WrappingDriver.jvm.kt` | `:core-database` | driver wrapper actual (JVM) |
| `shared/src/androidMain/.../core/database/contract/SqlDriverFactory.android.kt` | `:core-database` | driver actual (Android) |
| `shared/src/androidMain/.../core/database/contract/WrappingDriver.android.kt` | `:core-database` | driver wrapper actual (Android) |
| `shared/schemas/**` | `:core-database` | Room exported schemas; the one schema owner after the move |

The migration tests stay in `:shared` (which depends on `:core-database`); only their schema path
changes. `check-room-schema-integrity.py` gets one `SCHEMA_DIR` constant.

## `:core-domain` — confirmed closure (pure KMP: no Room/KSP/Compose/Koin)

| File | Owner | Reason |
|---|---|---|
| `feature/tasks/domain/model/Task.kt` | `:core-domain` | `Task`/`TaskId`/`TaskPriority`/`TaskKind`/`TaskFilter`; imported by `Mappers.kt`/`Entities.kt`; `TaskFilter.ByStatuses` needs `TaskStatus` |
| `feature/tasks/domain/model/TaskSeries.kt` | `:core-domain` | `Task.seriesId: TaskSeriesId`; `toTask` references `TaskSeriesId`; the file also declares `TaskSeries` (pure) |
| `feature/tasks/domain/model/TaskStatus.kt` | `:core-domain` | `TaskFilter.ByStatuses` (nested in `Task.kt`); `Selector` |
| `feature/tasks/domain/model/TaskWorkflowState.kt` | `:core-domain` | `Task.workflowState`; `toTask`; `Selector` |
| `feature/tasks/domain/model/RecurrenceSpec.kt` | `:core-domain` | `Task.recurrence`; `toTask` |
| `feature/projects/domain/model/Project.kt` | `:core-domain` | `Project`/`ProjectId`; `Mappers.toProject` |
| `feature/tags/domain/model/TagGroup.kt` | `:core-domain` | `TagGroup`/`TagGroupId`; `Mappers.toTagGroup` |
| `feature/notes/Ids.kt` | `:core-domain` | `NoteId` imported by `Mappers.kt`; the file also declares `Note`/`NoteColor`/`NoteKind` |
| `feature/agenda/domain/model/Selector.kt` | `:core-domain` | `Task.kt` imports `RelativeBucket`; the file declares `Selector`/`RelativeBucket` |
| `core/ids/{UserId,ProposalId,ProposalItemId,TimeEntryId}.kt` | `:core-domain` | the IDs used by the models/mappers |
| `core/ids/IdGen.kt` | `:core-domain` | `nextId()`, called by `TaskId.generate()` in the moved `Task.kt` |
| `core/search/Searchable.kt` | `:core-domain` | the models implement `Searchable` |
| `core/search/SearchMode.kt` | `:core-domain` | imported by `Task.kt` |
| `core/serialization/StableJson.kt` | `:core-domain` | shared serialization contract used by the models + `Mappers` |
| `core/text/FieldOwnedText.kt` | `:core-domain` | the `Note` imports it; `@Immutable` is removed in Stage 2 and the type is added to the stability config |
| `core/text/Revisioned.kt` | `:core-domain` | the `Note` imports it; `@Immutable` is removed in Stage 2 |
| `core/text/TextFieldSource.kt` | `:core-domain` | the `Note` imports `TextSource`/`StaticText`/`OwnedText`; the three `@Immutable` are removed in Stage 2 |
| `core/sync/DocType.kt` | `:core-domain` | `SyncableEntity.docType` |
| `core/sync/Hlc.kt` | `:core-domain` | `hlc` field on the models; `Mappers` |
| `core/sync/SyncableEntity.kt` | `:core-domain` | the models implement it |
| `core/attachments/AttachmentType.kt` | `:core-domain` | `AttachmentConverters` (Room converter) references it |
| `core/attachments/AttachmentSyncStatus.kt` | `:core-domain` | `AttachmentConverters` references it |
| `feature/timetracking/domain/TimeEntry.kt` | `:core-domain` | `TimeEntryMapper` imports `TimeEntry`/`TimeEntryKind`/`TimeEntrySource` (declared here) |
| `feature/proposals/domain/model/AiProposal.kt` | `:core-domain` | `ProposalMapper`; the file declares the proposal envelope |
| `feature/proposals/domain/model/ProposalItem.kt` | `:core-domain` | `ProposalMapper`; the file declares `ProposalItemStatus`/`ProposalSource`/`DecidedActor` |
| `feature/proposals/domain/model/ProposalItemKind.kt` | `:core-domain` | `ProposalMapper` |
| `feature/proposals/domain/model/ProposalTargetKind.kt` | `:core-domain` | `ProposalMapper` |
| `feature/calendarSync/domain/model/SyncedEventRef.kt` | `:core-domain` | `CalendarSyncTaskMapMappers` |

## `:shared` — excluded from the cut (kept where they are)

| File/package | Owner | Reason |
|---|---|---|
| `tasks/.../TaskSeriesPolicy.kt` | `:shared` | series logic, not in the DB mapper |
| `tasks/.../TaskMarking.kt` | `:shared` | UI/marking model, not a DB field |
| `tasks/.../TaskAi.kt` | `:shared` | AI feature model |
| `tasks/.../TaskDependency.kt` | `:shared` | dependency relations, not in the DB mapper |
| `tasks/.../TaskDocument.kt` | `:shared` | rich document model, not a DB column |
| `tasks/.../DependencyVerb.kt` | `:shared` | dependency vocabulary |
| `tasks/.../BlockedCloseRequest.kt` | `:shared` | close-request logic |
| `tasks/.../BlockedBulkCloseRequest.kt` | `:shared` | bulk-close logic |
| `tasks/.../TaskDetailDeps.kt` | `:shared` | DI/deps — imports use cases and repositories |
| `tasks/.../TaskDraftKeys.kt` | `:shared` | draft/UI keys |
| `tasks/.../AttachmentSaver.kt` | `:shared` | port, not a persistence model |
| `projects/.../ProjectDocument.kt` | `:shared` | document model, not in the DB mapper |
| `projects/.../ProjectTreeNode.kt` | `:shared` | tree/UI model |
| `tags/.../TagEditActor.kt` | `:shared` | domain actor, not a DB field |
| `notes/.../NoteBody.kt` | `:shared` | note-editor model |
| `notes/.../NoteBodyMarkdown.kt` | `:shared` | note-editor model |
| `calendarSync/.../CalendarAppInfo.kt` | `:shared` | sync/UI model, not used by the DB mapper |
| `calendarSync/.../CalendarSyncEvent.kt` | `:shared` | sync event |
| `calendarSync/.../CalendarSyncStatus.kt` | `:shared` | sync status |
| `calendarSync/.../GoogleEvent.kt` | `:shared` | sync model |
| `calendarSync/.../ImportWindow.kt` | `:shared` | sync model |
| `calendarSync/.../RecurrenceRule.kt` | `:shared` | sync model |
| `calendarSync/.../SyncPlan.kt` | `:shared` | sync model |
| `calendar/.../CalendarDateSelection.kt` | `:shared` | UI |
| `calendar/.../CalendarTaskStatus.kt` | `:shared` | UI |
| `calendar/.../CalendarTaskUi.kt` | `:shared` | UI model |
| `calendar/.../CalendarViewMode.kt` | `:shared` | UI |
| `agenda/.../AgendaDefinition.kt` | `:shared` | UI/serialization model; the DB entity is primitive and its mapper stays |
| `agenda/.../AgendaIntent.kt` | `:shared` | UI intent |
| `agenda/.../AgendaUiEvent.kt` | `:shared` | UI event |
| `agenda/.../AgendaUiState.kt` | `:shared` | UI state |
| `agenda/.../SavedAgendaView.kt` | `:shared` | model mapping stays in `:shared` |
| `agenda/.../SavedAgendaViewFactory.kt` | `:shared` | factory |
| `agenda/.../SavedAgendaViewKey.kt` | `:shared` | UI key |
| `plans/.../PlansGranularity.kt` | `:shared` | UI |
| `plans/.../TimelinePeriod.kt` | `:shared` | UI |
| `plans/.../TimelineSection.kt` | `:shared` | UI |
| `plans/.../TimelineTask.kt` | `:shared` | UI |
| `timetracking/.../TaskTimeSlotState.kt` | `:shared` | UI slot state |
| `core/sync/**` except `DocType`/`Hlc`/`SyncableEntity` + the five entity files | `:shared` | the sync engine stays |
| `core/search/**` except `Searchable`/`SearchMode`/`TextMatch`/`TextMatchRange`/`DiacriticFold` | `:shared` | the search index stays |
| `core/attachments/**` except `AttachmentType`/`AttachmentSyncStatus` + the DB files | `:shared` | repository/storage/upload/viewer/domain model |
| `core/config/**` except `RemoteConfigCacheEntity` | `:shared` | repository/port/snapshot |
| `core/text/**` except the three listed in `:core-domain` | `:shared` | text tools not used by the models |
| `core/text/FieldOwnedTextAsString.kt` | `:shared` | serializer used by `TaskDraft`; serializes the `:core-domain` `FieldOwnedText` |
| `core/text/TextMetrics.kt` | `:shared` | no references in the moved closure |
| `core/ids/IdGenerator.kt` | `:shared` | the generator port used by view models/repositories |
| `feature/proposals/data/ProposalKindCodec.kt` | `:shared` | no references in `shared/src`; if a DAO/entity needs it, move it with the DAO |
| `core/attachments/{Attachment,AttachmentId,AttachmentDomain}.kt` | `:shared` | the converter/entity use only `AttachmentType`/`AttachmentSyncStatus`; no DB reference to the model |
| `feature/tasks/domain/TaskDomain.kt` | `:shared` | validation logic (`AppError`/`Either`); the DB mapper does not import it |
| `feature/*/domain/port/**`, `feature/*/data/*RepositoryImpl.kt`, `feature/*/*DiModule.kt`, UI/ViewModels | `:shared` | repository interfaces/impls, DI, UI — the later `:core-data` question |

## Definitive ownership of the former "conditional" files

| File | Owner | Evidence / test that would overturn it |
|---|---|---|
| `Attachment.kt`, `AttachmentId.kt`, `AttachmentDomain.kt` | `:shared` | `AttachmentConverters`/`AttachmentEntity` reference only `AttachmentType`/`AttachmentSyncStatus`; no DB file imports the model. Overturned if a moved DB file imports it. |
| `TaskDomain.kt` | `:shared` | validation logic (`AppError`/`Either`); the DB mapper does not import it. Overturned if a moved file imports `TaskDomain`. |
| `FieldOwnedTextAsString.kt` | `:shared` | only `TaskDraft` (draft state, `:shared`) uses it; it serializes the `:core-domain` `FieldOwnedText`. Overturned if `FieldOwnedText` declares `@Serializable(with = ...)`. |
| `TextMetrics.kt` | `:shared` | no references in the moved closure. Overturned if a moved file imports it. |
| `IdGen.kt` | `:core-domain` | `TaskId.generate()` calls `nextId()`; the function is pure commonMain. Overturned only by removing that call. |
| `IdGenerator.kt` | `:shared` | the port is used by view models/repositories, not by the moved models. |
| `ProposalKindCodec.kt` | `:shared` | no references anywhere in `shared/src`. Overturned if a DAO/entity needs it — then it moves with the DAO. |
| `GoogleSyncEntities.kt` | `:core-database` | whole file: 3 Room entities + 3 DAOs, no domain imports. Overturned if it contains a mapper/logic that must stay (it does not). |

The compiler remains the final proof for the closure; a missed reference is a compile error. If one
of the tests above is overturned, revise this manifest — do not move more files to silence it.

## Known coupling (documented, not expanded in this cut)

`feature/proposals/domain/model/ProposalValues.kt` (`:shared`) imports
`core.database.LocalTimeFormats` (`:core-database`) for time formatting: feature code depends on a
database package. The cut keeps it — relocating the formatter is a separate small change and a new
module is not justified. The `:shared` → `:core-database` edge is also deliberate for the repository
implementations/DAOs; the clean graph is the later `:core-data`/composition-root step.

## Platform source-set check (Stage 2/3)

- `:core-database` targets `jvm()` + `androidTarget()` only — the platforms the repository supports;
  no iOS target.
- Every moved `expect` (`AppDatabaseFactory`, `SqlDriverFactory`, `WrappingDriver`, the
  `PlatformPragmas`) keeps its `actual` in `jvmMain`/`androidMain`; no platform API in `commonMain`.
- `:core-domain` is `commonMain` code with empty `jvm()`/`androidTarget()` source sets.

## Stage 1 checks

- [x] The manifest covers all **28** annotated Room files and every **60** `core/database/**` file.
- [x] The UI/DI/port/sync-engine exclusions are explicit (above).
- [x] The 12 `Mappers.kt` imports are a seed; the closure includes `TaskStatus` (`TaskFilter`),
      `TaskSeriesId` (`Task.seriesId`), `RelativeBucket` (`Task.kt`), `NoteId`, `AttachmentType`/
      `AttachmentSyncStatus` (`AttachmentConverters`), and the `TimeEntry`/proposal/`SyncedEventRef`
      mapper models.
- [x] Every row has a reason.
- [x] No file is in two modules (the four `core/ids` value classes + `IdGen.kt` are one owner; the
      three `core/text` files are one owner; `IdGenerator.kt` and the two text helpers are
      `:shared` only).
- [x] No moved model imports Compose; the only Compose annotations in the moved set are the three
      `core/text` files, removed in Stage 2 and replaced by stability-config entries.
- [x] Dependency direction: `:core-database → :core-domain`; `:shared → :core-domain` and
      `:shared → :core-database`; no `:core-domain → :shared`/`:core-database`, no
      `:core-database → :shared`.

**Status: waiting for human review. No file has been moved.**
