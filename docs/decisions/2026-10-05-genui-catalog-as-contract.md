---
title: "The GenUI layer had a catalog that described nothing, and a path to the model that no code called"
date: 2026-10-05
tags: [genui, a2ui, architecture, llm, testing]
status: accepted
---

## Context

`feature/genui/` shipped in R23 (`2026-09-26-genui-subsystem-applied-r23.md`) and renders
production UI: `WhatsNewScreen` shows release notes as an A2UI surface. It looked finished, and
three independent things were wrong with it, none of which a test could have caught because
every test exercised the part that was wired.

**The engine was never called.** `GenuiEngine` is registered in both platform `AiToolsModule`
files (`AiToolsModule.jvm.kt:141-152`, `AiToolsModule.android.kt:130-141`) and has no other
reference anywhere in the tree. `WhatsNewScreen.kt:58-60` builds its own `SurfaceController`,
`A2uiParser` and `ComponentRegistry` inside `remember`, so it bypasses both the engine and the
singletons. Every artifact in the layer — the engine, the transport, the whole LLM path — was
reachable from the dependency graph and unreachable from the app.

**The transport and the parser could not agree even if it had been wired.** `KoogGenuiTransport`
emits `StreamFrame.TextDelta` — token deltas — and `GenuiEngine.submit` hands each one straight to
`A2uiParser.parseLine`, which expects one complete JSON object per string
(`KoogGenuiTransport.kt:31-37`). The comment claiming "A2uiParser.parseLine handles buffering" is
false; no buffering exists. A model that answered in three chunks would have produced zero parsed
events. This is the exact defect the "unwired feature" class of bug exists to prevent, one level
down: the code was both unwired and broken.

**The catalog described nothing.** `BasicCatalog.componentNames` derives the allow-list by
reflecting over `UiNode::class.sealedSubclasses` and reading `@SerialName` annotations
(`BasicCatalog.kt:19-24`). It produces a name list and nothing else: no property types, no
required/optional distinction, no nesting rules, no functions. The agent is told which kinds
exist and is then left to invent the properties of each. Everything the parser rejects comes back
as `null` (`A2uiParser.kt:36-52`) — unknown operation, unknown kind, missing required field,
wrong type all fail identically and silently. `UiEvent.ParseError` exists in the sealed hierarchy
and is never constructed, so even the logging that was planned never happened. A hallucinated
`kind: "Chart"` is dropped, and the agent is never told.

Two further gaps were found while reading, and both are load-bearing for the catalog work: the
`op` key is taken as `obj.keys.firstOrNull()` and then forced through `.jsonObject`
(`A2uiParser.kt:44-45`), which throws an uncaught `IllegalArgumentException` for any line whose
first key is not an object; and the reactivity is nominal rather than real — `updateData` mutates
the data model and re-inserts the *same* `Surface` instance to force an emission
(`SurfaceController.kt:56-62`), while every renderer subscribes to the whole `surfaces` StateFlow
(`RenderSingle.kt:20`), so a single-component update invalidates the entire tree.

## Idea

The layer could be taken in three directions, and the choice is not "how much to build" but
"what the catalog means".

**Grow the name list.** Keep appending kinds and properties to the `UiNode` hierarchy, derive the
prompt from it, and call it done. Cheapest, and it leaves every problem above untouched.

**Adopt the A2UI specification.** Adopt the A2UI v0.9 wire format properly — `version` envelope,
`component` discriminator in PascalCase, `updateDataModel`, JSON-Pointer paths — and implement the
two-phase validation the specification describes. Highest fidelity, and it invalidates the payloads
that `RemoteConfigSnapshot.whatsNewPayload` serves from Supabase, which this repository does not
control.

**Make the catalog a contract in its own right.** Declare components, their property types, their
requiredness and their nesting rules once, in one declaration, and derive from it three things
that currently disagree with each other: the system prompt the agent reads, the validator that
rejects what the agent sends, and the JSON Schema an agent could pre-validate against. Keep the
wire format private, and pin it as a versioned dialect.

The third is the one this ADR records. The second is not refused on merit — it is the right answer
if interoperability with third-party A2UI agents ever becomes a requirement, and the `a2ui-core`
seam described below is where that adapter would be written. It is refused *now* because the
change would be paid for by breaking content served from outside this repository.

## Decision

**The catalog becomes the single source of truth, and the wire format stays private.**

1. `A2uiCatalog` declares, per component: name, properties (type, requiredness, default,
   description) and a child rule. One declaration in the module, no second list anywhere.

2. Three artefacts are derived from it, and none is hand-maintained: the system prompt the agent
   reads, the validator that checks what the agent sends, and a JSON Schema export for agent-side
   pre-validation. `BasicCatalog`'s reflection over sealed subclasses is deleted — a subtype with
   no `@SerialName` was already silently dropping out of the allow-list, and R8 can make that set
   depend on the build.

3. The dialect is pinned, not borrowed. `catalogId = "singularity.todo/genui"`, `protocolVersion = 1`,
   `kind` discriminator in snake_case, `updateData`, JSON-Pointer paths. Nothing from the v0.9
   envelope is accepted. This is a deliberate divergence from the specification, and it is recorded
   here so that a future reader meets it as a decision rather than as a bug.

4. **Nothing fails silently.** Every rejected line produces a typed error with a machine-readable
   code, a JSON-Pointer into the offending message, and a severity. Component-level failures drop
   one node and rendering continues — that is progressive rendering, and the specification asks
   for it. Message-level failures drop the message. Neither is silent, and both are reported to
   the agent.

5. **The agent is told what it got wrong, in a bounded loop.** Validation errors are fed back to
   the model as corrections on the next turn, with a hard cap. The most useful thing a client can
   send back is the list of valid component names when the model invented one; that single field is
   worth more than the rest of the catalog put together.

6. **The engine is wired or it is deleted.** The transport is fixed to frame token deltas into
   complete lines, the engine is connected to a real screen, and the DI bindings move into one
   module shared by both platforms. `scripts/find-unwired-surfaces.py` must not report the layer
   afterwards.

7. **Reactivity is per component.** A component store keyed by id, a subscription per node, and
   no re-subscription of the whole tree per child. This also fixes form fields, which currently
   render `initial` and never read the data model, so what the user types never returns to the
   surface.

## Rationale

**Why the catalog beats a name list.** The allow-list answers "which kinds may I use" and stops
there. The properties are where generation actually fails, and today there is no contract for them
at all: `Button` takes `label`/`action`/`data`, and the only thing the agent has been told is that
`button` exists. A catalog that carries types and requiredness is simultaneously a better prompt
and the first half of a validator, which is why it is worth declaring once rather than maintaining
twice.

**Why errors go back to the model.** A dropped message is invisible from both sides: the surface
renders partially, and the model has no signal to correct against. Feeding the failure back converts
a silent degradation into a second attempt, and because the cap is bounded, a model that cannot
converge ends at a reported failure rather than at a loop.

**Why the private dialect is not a compromise but the point.** The catalogue contract is a
*client-side* idea: the client declares what it can render and validates against its own
declaration. That idea is independent of the field names on the wire. Renaming `kind` to `component`
would buy interoperability at the cost of every payload served from Supabase by content this
repository cannot see or migrate atomically — for a feature whose only live consumer is a release
note sheet.

**Why one shared DI module.** The bindings were duplicated verbatim in the Android and JVM
`aiToolsModule` actuals. A component that must be registered twice is a component that will be
registered once.

**Why progressive rendering is not silent rendering.** Components routinely reference children and
data that arrive in later messages; that is the design of a streaming protocol. Rendering a
placeholder and recording why is the difference between a surface that fills in and a surface that
silently has a hole in it.

## Consequences

- The system prompt grows from one line of names to a structured document. The descriptions in the
  catalog are the tuning surface: if the model starts ignoring rules, the descriptions shorten —
  and the validator stays, because the fallback is never to go back to silent drops.
- A correction turn costs a second model call. Two attempts is the default and the cap is a constant,
  not a setting; there is no user-facing "retrying" affordance, because the outcome is what matters.
- A third-party A2UI agent can still not talk to this client. When that becomes a requirement, the
  adapter goes in `a2ui-core`, behind the same catalog interface, and the dialect stays as the
  default implementation.
- Domain components (`task_card`, `due_date`, `project_chip`) read the surface's data model and
  never the repositories. That keeps `feature/genui` free of task/project dependencies; the cost is
  that the agent must send the data, which is the same contract the rest of the catalog has.
- The three existing formatters for dates (`formatDueChip`, `formatRussianDueDate`) are reused by
  the new date component and functions instead of a fourth copy. A "Today/Tomorrow/Yesterday"
  string exists once in the project.
- `find-unwired-surfaces.py` reporting an orphan binding is now a regression signal for this layer
  specifically, which is the intended use of that script.

## Links

- `2026-09-23-genui-server-driven-ui.md` — the original dialect and the silent-skip policy this
  ADR replaces. Its allow-list paragraph is superseded; the file is kept for the schema-versioning
  decision, which still holds.
- `2026-09-26-genui-subsystem-applied-r23.md` — the change that declared the subsystem applied
  while the engine had no caller.
- `feature/genui/` — the implementation.
- `openspec/changes/genui-catalog-contract/` — the observable behavior this ADR produces.
- https://a2ui.org/specification/v0.9-a2ui/ and
  https://developer.android.com/develop/ui/compose/agentic/manage-catalogs — the catalog-as-contract
  and validation model the ideas were taken from. Code was not.
