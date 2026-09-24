---
title: "GenUI — Server-Driven UI via A2UI v0.9"
status: accepted
---

# GenUI — Server-Driven UI via A2UI v0.9

## Context

The app needs to display dynamic, AI-generated UI surfaces that can evolve without a full app update. Three concrete use cases exist today:

1. **Onboarding** — new users see a guided surface introducing core features.
2. **Project Review** — after AI analysis of a project, a surface renders the formatted review.
3. **WhatsNew** — after an app update, a surface shows release highlights.

All three share the same rendering engine: a declarative UI described by JSON lines from the LLM, rendered by Compose via a registry of component builders.

## Decision

Implement **GenUI** (Generative UI) as a shared KMP feature module at `feature/genui/`, using the **A2UI v0.9** wire format — a JSON-Lines protocol where each line is a self-contained `UiEvent`.

### A2UI v0.9 Schema

Each message is one JSON object with a required `op` field:

```json
{"op": "createSurface", "surfaceId": "...", "rootId": "...", "components": {...}, "schemaVersion": 1}
{"op": "updateComponents", "surfaceId": "...", "components": {...}}
{"op": "updateData", "surfaceId": "...", "path": "field.subfield", "value": {...}}
{"op": "deleteSurface", "surfaceId": "..."}
```

Component nodes use a `kind` discriminator (`text`, `button`, `column`, `row`, `card`, `list`, `divider`, `badge`, `text_field`, `checkbox`, `tabs`, `icon`, `modal`) and are serialized via `@JsonClassDiscriminator("kind")`.

`schemaVersion` in each event defaults to `A2UI_CURRENT_SCHEMA_VERSION = 1`. The parser silently drops events with `schemaVersion > A2UI_CURRENT_SCHEMA_VERSION`.

### Allow-List Component Strategy

The LLM is instructed (via `BasicCatalog.systemPromptAppendix`) to emit only the 14 component kinds in `BasicCatalog.componentNames`. Any unknown `kind` at parse time returns `null` (silent drop). Any unknown `kind` at render time logs at WARN and skips (no crash).

```
text | heading | button | column | row | card | list | divider |
badge | text_field | checkbox | tabs | icon | modal
```

### Forward Compatibility

| Failure point | Policy |
|---|---|
| Unknown `op` in UiEvent | Parser returns `null` — silent skip |
| Unknown `kind` in UiNode | Renderer logs WARN — silent skip |
| Newer `schemaVersion` | Parser drops the event |
| Older `schemaVersion` | Accepted (server is behind, not ahead) |
| `StableJson` unknown fields | `ignoreUnknownKeys = true` — silently ignored |

### Architecture

```
feature/genui/
  catalog/
    UiNode.kt            — sealed interface + 14 component types
    BasicCatalog.kt      — allow-list + system prompt appendix + kind regex
  parser/
    UiEvent.kt           — sealed hierarchy: CreateSurface, UpdateComponents,
                          UpdateData, DeleteSurface, ParseError
    A2uiParser.kt        — parses JSON-Lines → UiEvent?
  schema/
    UiPath.kt            — path DSL: UiPath.Root, UiPath.Child(index), UiPath.Prop(key)
    DataModel.kt         — in-memory JsonObject store with per-path Flows
  surface/
    Surface.kt           — id + rootId + components map + DataModel
    SurfaceId.kt         — @JvmInline value class
    SurfaceController.kt — manages all active surfaces; pure-ish (single MutableStateFlow)
  render/
    ComponentRegistry.kt — kind → Compose builder; silent skip for unknown kinds
    DefaultDataContext.kt — surfaceId + action handler + data controller
    DataContext.kt       — interface: surfaceId, controller, onAction, onDataChange
    GenuiRenderer.kt     — top-level: collects surfaces → renders root node
    material3/
      atoms/             — TextRenderer, ButtonRenderer, IconRenderer, ...
      containers/        — CardRenderer, ColumnRenderer, RowRenderer, ...
      complex/           — TabsRenderer, ModalRenderer, TextFieldRenderer, ...
  transport/
    GenuiTransport.kt    — interface: send(input, systemPrompt) → Flow<String>
    KoogGenuiTransport.kt — LLM streaming via Koog prompt executor
  GenuiEngine.kt        — orchestrates: transport → parser → controller → surfaces
```

### Schema Versioning

- `A2UI_CURRENT_SCHEMA_VERSION = 1` lives in `A2uiParser`.
- Each `UiEvent` subclass carries `schemaVersion: Int = A2UI_CURRENT_SCHEMA_VERSION` so the parser knows the version without looking at a top-level envelope.
- `SyncProtocol.CURRENT_PROTOCOL_VERSION` (sync layer) and `BackupFormat.MIN_SUPPORTED_SCHEMA_VERSION` (backup) follow the same pattern — each domain has its own version constant.

### Data Flow

```
LLM (Koog) → KoogGenuiTransport → A2uiParser → SurfaceController
                                                    ↓
                                            surfaces StateFlow
                                                    ↓
                                              GenuiRenderer
                                                    ↓
                                              Compose UI
```

## Rationale

**Why JSON-Lines instead of a single JSON object?** Streaming. The LLM streams tokens; we need to render incrementally as lines arrive rather than waiting for the complete response. Each `UiEvent` is self-contained, so partial renders are possible.

**Why a sealed hierarchy for UiEvent rather than a generic `Map<String, JsonElement>`?** Type safety and compile-time exhaustiveness in `SurfaceController`. Adding a new `op` forces a new `when` branch — no silent runtime drops.

**Why silent skip for unknown kinds?** Forward compatibility without version negotiation. The server controls what surfaces are shown; a newer app version encountering an older server payload should render what it can and ignore the rest.

**Why not Flutter-style diffing?** DataModel is append-only per path. `updateData` mutates a path in-place. The SurfaceController workaround for double-emission (Surface copy-on-write) is a known limitation tracked separately.

## Consequences

- New component kinds require a new `UiNode` subtype + new renderer + `@SerialName` annotation + update to `BasicCatalog.systemPromptAppendix`. No schema migration needed.
- The `WhatsNew` screen is the first production surface using GenUI, rendered at startup when a new `RemoteConfigSnapshot.whatsNewPayload` is present.
- GenUI is purely client-side rendering; the LLM controls content. No server-side validation of the payload happens — trust comes from the authenticated Supabase session.

## Links

- `feature/genui/` — full implementation
- `docs/decisions/2026-09-22-checklist-usecase-delete-and-dead-deps-cleanup.md` — GenUI onboarding use case origin
- `docs/decisions/2026-09-22-system-calendar-sync.md` — pattern for feature module structure
