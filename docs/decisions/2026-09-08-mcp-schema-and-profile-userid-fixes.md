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

### Fix 1: Stop passing the schema JSON as the `$schema` field

The MCP SDK defines `ToolSchema.schema: String?` (serialized as `$schema`) as
**the JSON Schema dialect URI** (e.g. `"https://json-schema.org/draft/2020-12/schema"`).
When absent, the SDK sample `samples/weather-stdio-server/McpWeatherServer.kt:90`
demonstrates the correct pattern: omit `schema` entirely and the client falls
back to the default (2020-12).

Previous implementation passed `json.encodeToString(JsonElement.serializer(), schemaJson)`
into that field, so the literal schema JSON became the value of `$schema`. MCP
clients (Fred Perry Todo List, Claude Code) interpreted this as a dialect URI and
rejected every tool with:

```
JSON Schema declares an unsupported dialect ("$schema": "{...}")
The default validator supports JSON Schema 2020-12, 2019-09, draft-07, and draft-06
```

**Change:** `KoogJsonSchemaBuilder.build()` now passes `schema = null` to
`ToolSchema`, exactly like the SDK sample does. Only `properties`, `required`,
and `defs` are populated.

```kotlin
// KoogJsonSchemaBuilder.kt
return ToolSchema(
    schema = null,                                    // <-- was: schemaJsonStringified
    properties = JsonObject(properties),
    required = required.takeIf { it.isNotEmpty() },
    defs = null,                                      // <-- was: JsonObject(emptyMap())
)
```

### Regression test

`McpServerEndToEndTest.server_handles_initialize_and_lists_tools` was extended
to assert that no tool's `inputSchema.schema` field is set:

```kotlin
for (tool in listed) {
    val schemaProp = tool.inputSchema.schema
    assertEquals(expected = null, actual = schemaProp,
        message = "Tool ${tool.name} inputSchema still carries \$schema = $schemaProp")
}
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

- The `$schema` field in JSON Schema has a specific meaning: it declares the
  dialect URI (e.g. `https://json-schema.org/draft/2020-12/schema`). The MCP SDK
  serializes `ToolSchema.schema: String?` as `$schema`, so passing anything other
  than a dialect URI (or null) will be rejected by strict clients.
- The official SDK sample `samples/weather-stdio-server/McpWeatherServer.kt:90`
  demonstrates the canonical pattern: leave `schema` null and populate only
  `properties`, `required`, `defs`. We adopt that exact pattern.
- `ProfileAwareCurrentUser.scopedUserId` is a `StateFlow<UserId>` derived from
  the active profile's `profileId` + the raw `userId`. Using `.value` extracts
  the `UserId` inline value class underlying `String`.
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
