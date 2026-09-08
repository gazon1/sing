---
title: MCP schema dialect bug + profile-aware userId defaults
date: 2026-09-08
tags: [mcp, koog, schema, profiles, bugfix]
status: accepted
---

## Context

MCP clients (e.g. Fred Perry Todo List app, Claude Code) validate tool input schemas
against JSON Schema before making calls. Two separate bugs caused `list_tasks` and
related read tools to fail validation or return empty results:

1. **`$schema` embedded inside a JSON Schema object** — `KoogJsonSchemaBuilder` was
   putting the entire schema JSON string (serialized) as the value of a `$schema`
   *property* inside the schema itself. The MCP client resolved `$schema` as a dialect
   URI reference and failed with "unsupported dialect".

2. **Hardcoded `"local-user"` defaults** — `list_tasks`, `list_linked_tasks`, and
   `search_tasks` all defaulted `userId` to `"local-user"`. The AI Agent profile
   uses scoped userIds of the form `"{profileId}/{rawUserId}"`. Queries always returned
   empty because the default never matched any real data.

---

## Decision

### Fix 1: Remove `$schema` embedding from KoogJsonSchemaBuilder

The `ToolSchema` type carries the schema string in its `schema` field. Embedding
it *again* inside `properties` as a `$schema` property is redundant and breaks clients
that interpret `$schema` as a normative dialect URI.

**Change:** `KoogJsonSchemaBuilder.build()` no longer injects `$schema` into the
schema object. The schema string is carried solely by `ToolSchema.schema`.

```kotlin
// KoogJsonSchemaBuilder.kt
val schemaJson = JsonObject(
    buildMap {
        put("type", JsonPrimitive("object"))
        put("properties", JsonObject(properties))
        if (required.isNotEmpty()) {
            put("required", JsonArray(required.map { JsonPrimitive(it) }))
        }
        // NOTE: Do NOT embed "$schema" as a property inside schemaJson
    },
)
return ToolSchema(
    schema = json.encodeToString(JsonElement.serializer(), schemaJson),
    properties = schemaJson,
    required = required,
    defs = JsonObject(emptyMap()),
)
```

### Fix 2: ProfileAwareCurrentUser as default in read tools

Three tools now inject `ProfileAwareCurrentUser` and use `currentUser.scopedUserId.value`
as the default when no `userId` argument is provided:

| Tool | File | Pattern |
|---|---|---|
| `list_tasks` | `ListTasksTool.kt` | `val effectiveUserId = if (args.userId.isNotBlank()) UserId(args.userId) else currentUser.scopedUserId.value` |
| `list_linked_tasks` | `ListLinkedTasksTool.kt` | Same |
| `search_tasks` | `SearchTasksTool.kt` | Same |

DI factories in `AiToolsDiModule.kt`, `AiToolsModule.jvm.kt`, and `AiToolsModule.android.kt`
were updated to pass `get<ProfileAwareCurrentUser>()` as the second constructor argument.

### Fix 3: ProjectDetailViewModel scoped userId

`ProjectDetailViewModel` used `UserId(project.userId)` — the raw, un-scoped userId from
the project entity. Changed to `currentUser.scopedUserId.value` so tasks are queried
with the correct scoped identifier.

---

## Rationale

- The `$schema` property in JSON Schema has a specific meaning: it declares the
  dialect (e.g. `https://json-schema.org/draft/2020-12/schema`). Putting a JSON
  string literal there causes URI resolution to fail.
- `ProfileAwareCurrentUser.scopedUserId` is a `StateFlow<UserId>` derived from the
  active profile's `profileId` + the raw `userId`. Using `.value` extracts the
  `UserId` inline value class underlying `String`.
- `CreateTaskTool`, `DecomposeAndCreateTool`, and all write tools already used
  `ProfileAwareCurrentUser` correctly — only read-side tools were missed.

---

## Consequences

- `list_tasks`, `list_linked_tasks`, and `search_tasks` now return correct results
  when called without an explicit `userId`.
- MCP clients that validate `$schema` as a URI will no longer reject tool schemas.
- All 3 tools now require `ProfileAwareCurrentUser` in DI — tested via
  `JvmAiDiGraphTest` (already covers all tool factories).

---

## Links

- `KoogJsonSchemaBuilder.kt` — schema builder (mcp-server)
- `ListTasksTool.kt`, `ListLinkedTasksTool.kt`, `SearchTasksTool.kt` — affected tools
- `ProjectDetailViewModel.kt` — ProjectDetail task query fix
- `AiToolsDiModule.kt`, `AiToolsModule.jvm.kt`, `AiToolsModule.android.kt` — DI updates
