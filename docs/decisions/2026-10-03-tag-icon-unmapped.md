---
title: "Tag.icon — no such column exists"
date: 2026-10-03
status: closed-wontdo
tags: [database, tags, migration]
---

## Context

`EntityMapperCompletenessTest` initially flagged `TagEntity` as having an unmapped `icon` column,
based on a v20 migration claim. Investigation confirmed: **`TagEntity` does not have an `icon`
field in code, and no such column exists in the Room schema.**

The apparent source of confusion: the `TagEntity` entity definition in `Entities.kt`
does not contain `icon`. The original ADR claim about the column existing was incorrect.

## Decision

**No action needed.** `TagEntity` and `Tag` are consistent: neither has an `icon` field.
`EntityMapperCompletenessTest` passes for `TagEntity` without any allowlist entry.

`Tag.icon` as a domain feature can be revisited as a product decision when tag icons
are actually needed — at which point a proper schema migration (v31+) would add the
column and map it symmetrically.

## References

- `EntityMapperCompletenessTest.kt` — no allowlist entry needed for TagEntity
- `TagEntity` definition in `core/database/Entities.kt:173`
- `Tag` domain model in `feature/tags/Ids.kt:28`
