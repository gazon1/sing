---
title: "Entity-mapper completeness — guard against silent data destruction"
date: 2026-10-03
status: accepted
tags: [database, write-path, room, testing]
---

## Context

Room `@Upsert` is a full-row REPLACE: every column not explicitly set in the entity
takes its Kotlin default. A mapper that omits a field silently destroys that field's
value on **every read-modify-write cycle**.

The codebase had at least two such bugs caught during Phase 0:

| Bug | What was lost |
|---|---|
| `TaskEntity.toTask` missing `estimateMinutes` | Task duration estimate — never survived edits |
| `ProjectsRepositoryImpl` duplicate `toProject` | `serverVersion`/`hlc` — sync watermark zeroed on every edit |
| `TagEntity.icon` unmapped (known gap) | Tag icon — never restored, column default used |

The bugs compiled, passed unit tests, and only manifested as silent data loss in production.

## Decision

A Konsist arch test (`EntityMapperCompletenessTest`) enumerates every `@Entity` in the
`commonMain` source tree and verifies:

1. **Completeness**: every entity constructor parameter appears in the corresponding
   `toX()` mapper (i.e., there is a `entity.fieldName` access for each column).
   Fields intentionally dropped must be listed in `FIELD_ALLOWLIST` with a comment
   citing an ADR.

2. **No phantoms**: every parameter declared in a `toX()` mapper exists in the
   entity constructor. Supplemental params (`tags`, `dependsOn`) loaded from join
   tables are excluded via `SUPPLEMENTAL_PARAMS`.

```kotlin
// Every entity constructor param must be accessed by its toX() mapper
everyEntityParamAppearsInMapper()

// No phantom params in toX() that don't exist in the entity
everyMapperParamExistsInEntityOrIsSupplemental()
```

The test is in `shared/src/jvmTest/kotlin/com/singularity/todo/arch/EntityMapperCompletenessTest.kt`
and runs as part of the standard JVM test suite.

## Consequences

### Positive
- Any future mapper that drops a column fails the build, not production
- The allowlist documents known gaps (e.g. `TagEntity.icon`) and forces a decision
- Positive control test ensures the rule itself doesn't silently stop detecting

### Negative
- Test requires manual synchronization when schema changes — a comment in the test
  file reminds authors to update `ENTITY_PARAMS` when adding/removing columns
- `ChecklistItemEntity` is excluded because its `toItem()` mapper is private and
  intentionally omits `createdAt`/`updatedAt`/`rowVersion` (managed by the repository
  via `existing?.createdAt` in `toEntity()`)

## References

- Konsist spec: `arch/EntityMapperCompletenessTest.kt`
- MR 0.2: fixed `estimateMinutes` gap and duplicate `toProject`
- MR 0.5: added the Konsist test
- ADR `2026-10-03-synccolumns-live-field-set` — related sync field discipline
