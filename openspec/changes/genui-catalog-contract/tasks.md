# Tasks — genui-catalog-contract

Spec: `genui-catalog`, REQ-GC-001 … REQ-GC-006 (added by this change).
ADR: `docs/decisions/2026-10-05-genui-catalog-as-contract.md`.

Tests are written before the behaviour they pin. The first four tasks exist because the current
behaviour is what the plan found wrong, and a test written only against the new code would have
passed against the old.

## REQ-GC-001 — the catalog is the single declaration

- [x] `shared/` Declare the component set once: name, properties with types, requiredness,
      enumerated values, child rules. **Test:** every component type in the code has exactly one
      schema; schema names are unique; the component that exists only as a nested structure is not
      offered to the model as a component.
- [x] `shared/` Derive the model's instructions from the declaration, deterministically, including
      property types, which properties are required, and the available functions. **Test:** two
      generations of an unchanged declaration are byte-identical; every component and function name
      appears in the instructions.
- [x] `shared/` Export the declaration as a machine-readable schema usable by the agent to check its
      own output. **Test:** regenerating without changing the declaration reproduces the same
      schema; a component's required property is marked required in the export.
- [x] `shared/` Delete the name list derived by reflection over the type hierarchy, and add a check
      that a component with no renderer is reported. **Test:** a declaration naming a component
      with no renderer fails the check.

## REQ-GC-002 — nothing fails silently

- [x] `shared/` Return a typed outcome for every input line instead of an absent value: parsed,
      skipped with a reason, or failed with a reason. **Test:** a blank line, a malformed line, and
      a line whose first key is not a message are each classified rather than dropped.
- [x] `shared/` Stop deriving the operation name from the first key in the object. **Test:** a
      message carrying a version field before the operation is classified, and no input throws.
- [x] `shared/` Record a machine-readable code, a pointer into the message, and a severity for every rejection: unknown operation, unknown component, missing required property, wrong property type, illegal enumerated value, disallowed child, unresolved reference, cycle, duplicate component identifier, re-created surface, newer schema version, oversized line. **Test:** one case per code; the unknown-component reason lists the components that exist.
- [x] `shared/` Keep going after a component-level rejection. **Test:** nine valid components and one invalid in one message produce a surface of nine; a message that cannot be attributed to a usable surface is discarded whole and leaves earlier surfaces intact.
- [x] `shared/` Treat prose mixed into the model's output as the assistant's text, not as a message. **Test:** a response of prose around a fenced block yields both the text and the surface; a fenced block parses identically to an unfenced one.

## REQ-GC-003 — severity decides what is discarded

- [x] `shared/` Give each rejection a severity, and let severity decide whether one component or the whole message is dropped. **Test:** the two severities produce the two different outcomes on the same input shape.

## REQ-GC-004 — the model is told what it got wrong

- [x] `shared/` Frame a stream of model output fragments into complete messages, handling a
      fragment boundary inside a message, both line endings, an unterminated trailing line, and a
      line that exceeds the size bound. **Test:** a message split at every offset parses the same
      as an unsplit one; an over-long line is skipped to the next line and reported.
- [x] `shared/` Collect the reasons from a response and offer them to the model on the next attempt, up to a fixed bound of two. **Test:** one invalid component produces exactly one further attempt, and the reasons from the first response are present in the second request.
- [x] `shared/` Replace the affected surface wholesale at the start of each further attempt, so a half-applied attempt cannot persist. **Test:** after a failed attempt followed by a corrected one, the surface contains no component from the failed attempt.
- [x] `shared/` End at a reported failure when the bound is reached, leaving the partially rendered surface visible, and record the model call. **Test:** an always-invalid response stops after the bound, reports to the user, and leaves the surface rendered; the usage record contains the call.

## REQ-GC-005 — updates are per component

- [x] `shared/` Hold components in a per-surface store keyed by identifier and expose an
      observation per identifier that emits only when that component changes. **Test:** updating one
      component emits on that component's observation and not on an unrelated one.
- [x] `shared/` Stop re-publishing an unchanged surface to force an emission on a data update. **Test:** a data update emits on the surface's data observation without a spurious re-publication of the component map.
- [x] `shared/` Bind form fields both ways: a field renders the value at its path, falling back to
      its initial value, and a user edit reaches the data model. **Test:** a field shows a value
      written by an update; a user edit appears at the path; a read-only surface neither persists
      nor fails.
- [x] `shared/` Resolve templates that read the data model at render time, and check the template
      when the message is parsed. **Test:** a value written after the component arrives still renders;
      an unknown function in a template is rejected at parse time; a missing path renders as empty
      rather than failing.

## REQ-GC-006 — the layer is reachable

- [x] `shared/` Surface a generated surface in the assistant conversation, alongside the assistant's
      text, and report a control's activation with its surface, component and bound data.
      **Test:** a response carrying a message produces an assistant reply that holds both text and
      surface; activating a control reports the expected surface and component.
- [x] `shared/` Frame model output correctly end to end, so a fragmented response produces a
      surface. **Test:** a fake model that emits a valid surface in small fragments yields a
      rendered surface.
- [x] `shared/` Register the layer's dependencies in one module shared by both platforms, and remove
      the duplicated registrations. **Test:** `python3 scripts/find-unwired-surfaces.py` reports
      nothing for this layer; `./gw :desktopApp:compileKotlin` resolves the graph.
- [ ] `shared/` `just gate` passes end to end. **Why it is still open:** the module is done and the
      module-level checks pass, but detekt is red on the files this change introduced and `just
      gate` is a gate, not a suggestion. Tracked with the detekt work, not with this task.
- [x] `shared/` Add the three domain components, which read the surface's data model only.
      **Test:** a surface containing a task card renders its title, due chip and project chip from
      the data model and reports its activation with the bound values.
      **Done by:** `GenuiCatalogRenderTest` (desktopApp), through the `runGenuiRenderTest` harness —
      a task card draws the title its data model resolved, and a press reports the action the model
      named. The due and project chips are pinned by the corpus (`add-task-form.jsonl`), which
      asserts they validate and are stored, not that they draw.
- [x] `docs/` Move the release-note surface onto the shared facade, so it stops assembling its own parser, controller and registry. **Test:** the screen's dropped-line count comes from the classified outcomes rather than a manual count.
