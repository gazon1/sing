---
title: "Tag.icon column — unmapped, no domain field, no ADR"
date: 2026-10-03
status: open
tags: [database, tags, migration]
---

## Context

`TagEntity` carries an `icon` column added in v20 auto-migration (MR-9):
```kotlin
// Entities.kt:173 — TagEntity
data class TagEntity(
    ...
    val icon: String? = null,  // ← never mapped to Tag domain model
    ...
)
```

`TagEntity.toTag()` in `TagsRepositoryImpl.kt:84` does not read `icon`:
```kotlin
internal fun TagEntity.toTag(): Tag = Tag(
    id = TagId.fromString(id),
    name = name,
    color = color,
    // ... no icon field set ...
)
```

The `Tag` domain model in `feature/tags/Ids.kt` has no `icon` field.

**No data is destroyed**: writes via `Tag.toEntity()` pass `icon = null` (the domain model
has no icon), so `TagEntity.icon` stays at its default `null`. The column exists in the
DB but is never restored on read.

**No ADR was written** when the column was added.

## Decision

**TRIAGE NEEDED** — this ADR is a placeholder. Two options:

### Option A — Add `icon` to `Tag` domain model
- Add `icon: String? = null` to `Tag` in `feature/tags/Ids.kt`
- Map `icon` in `TagEntity.toTag()` and `Tag.toEntity()`
- v20→v21 migration: no-op (column already exists, null is the default)
- Cost: moderate — requires updating all call sites that construct `Tag`

### Option B — Drop the column
- The UI has never rendered per-tag icons; the column was added speculatively
- Drop via `@DeleteColumn` in next schema version
- Cost: low — one migration line
- Requires confirming no live data uses non-null values first

### Option C — Keep as-is
- Accept the column as unused but non-harmful infrastructure
- No code change; revisit when/if tag icons become a product priority

## Action Required

Product decision: which option? Until then, `icon` is in `FIELD_ALLOWLIST` in
`EntityMapperCompletenessTest` to prevent false failures.

## References

- `EntityMapperCompletenessTest.kt` — allowlist entry: `TagEntity → setOf("icon")`
- MR 0.5: `EntityMapperCompletenessTest` caught this gap
