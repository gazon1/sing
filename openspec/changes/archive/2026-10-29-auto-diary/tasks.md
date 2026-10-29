- [x] T001 [P] Add `projectId: ProjectId?` to `Note` and `NoteEntity` with default null
  - Files: `shared/src/commonMain/kotlin/com/singularity/todo/feature/notes/Ids.kt`, `shared/src/commonMain/kotlin/com/singularity/todo/core/database/Entities.kt` (import `ProjectId` from `com.singularity.todo.feature.projects.domain.model`)
  - Depends on: none
  - Verification: `./gradlew :shared:compileKotlinJvm`
  - Acceptance: AC-01

- [x] T002 Add Room migration v49→v50: column `project_id TEXT` + partial unique index
  - Files: `shared/src/commonMain/kotlin/com/singularity/todo/core/database/Migration49To50.kt`, `AppDatabase.kt`
  - Depends on: T001
  - Verification: `Migration49To50Test::roundTrip` — `./gradlew :shared:jvmTest -Ptest.tags=slow --tests 'Migration49To50Test'`
  - Acceptance: AC-01

- [x] T003 [P] Update backup DTO to include `projectId: String? = null`
  - Files: `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/BackupNoteDtos.kt`
  - Depends on: T001
  - Verification: `./gradlew :shared:compileKotlinJvm`
  - Acceptance: AC-01

- [x] T004 Extend `NotesRepository.getOrCreateDailyNote` with `projectId` parameter
  - Files: `shared/src/commonMain/kotlin/com/singularity/todo/feature/notes/domain/port/NotesRepository.kt`, `NotesRepositoryImpl.kt`
  - Depends on: T001
  - Verification: `AutoDiaryRepositoryTest::getOrCreate_isIdempotent` — `./gradlew :shared:jvmTest --tests 'AutoDiaryRepositoryTest'`
  - Acceptance: AC-01

- [x] T005 [P] Update `FakeNotesRepository` with new method signature
  - Files: `shared/src/commonMain/kotlin/com/singularity/todo/test/fakes/FakeRepositories.kt`
  - Depends on: T004
  - Verification: `./gradlew :shared:compileKotlinJvm`
  - Acceptance: AC-01

- [x] T006 Expose `get_daily_note` and add `includeArchived: Boolean = false` to `ListProjectsInput`
  - Files: `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/tools/ListProjectsTool.kt`, `shared/src/jvmMain/kotlin/com/singularity/todo/mcp/...`, `McpToolCatalog.kt`
  - Depends on: T004
  - Verification: `McpServerEndToEndTest` — `./gradlew :mcp-server:test`
  - Acceptance: AC-02, AC-03

- [x] T007 [P] Write unit tests V-01..V-06
  - Files: `shared/src/commonTest/kotlin/com/singularity/todo/feature/notes/AutoDiaryRepositoryTest.kt`, `AutoDiaryTriggerTest.kt`
  - Depends on: T004, T005
  - Verification: `./gradlew :shared:jvmTest --tests 'AutoDiary*'`
  - Acceptance: AC-01, AC-02, AC-03

- [x] T008 Write integration concurrency test V-04
  - Files: `shared/src/jvmTest/kotlin/com/singularity/todo/feature/notes/AutoDiaryConcurrencyTest.kt`
  - Depends on: T002, T004
  - Verification: `./gradlew :shared:jvmTest --tests 'AutoDiaryConcurrencyTest'`
  - Acceptance: AC-01