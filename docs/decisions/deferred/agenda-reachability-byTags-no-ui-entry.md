---
title: "Agenda Reachability Bytags No Ui Entry"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Tracked as:** [#81](https://github.com/gazon1/sing/issues/81) · OpenSpec change `agenda-tags-entry-point` (proposed)

**Found in:** MR-0, кодовая разведка навигации.

**Symptom:** `AgendaPresets.byTags(ids: Set<TagId>)` существует (multi-tag), но UI-входа нет. `byTag(single)` доступен через Search → tag chip → `AgendaStartRoute.Tag`. Multi-tag view (matchAll и any-tag) недоступен через UI.

**Status: CLOSED** (issue #81 closed; multi-tag agenda entry now exists).

The premise no longer holds. `SelectorTemplate.ByTags` is in the section
configurator's catalogue and resolves to `Selector.Tags(ids)`, with the user's
tags as a **multi-select** option list (`SavedAgendaScreen.kt`:
`is SelectorTemplate.ByTags -> tags.map { … }`). A multi-tag view is reachable
from the editor today, and the `// TODO: known gap — no UI entry for byTags`
marker this entry referred to is gone from the tree.

What genuinely had no UI was the other half: `ByTags(matchAll = true)` existed
in the engine with `matchAll = false` hardcoded as the only constructible value
from the editor, so "tasks carrying **all** of these tags" could not be built.
`SelectorParameterSheet` now renders a "Match all of these tags" checkbox for
that one template, threads the flag through `onConfirm`, and rebuilds the
template with `.copy(matchAll = …)` before resolving — any other template
returns itself, so the shared path is untouched. Pinned by
`SelectorTemplateTest.by tag matchAll flips the resolved selector semantics`,
which asserts the *semantics* differ and not merely the ids, because both
resolutions carry the same id set.

**Still open:** a first-class "view these N tags as an agenda" entry point
(e.g. from the Tags screen). The editor can build the section; nothing starts
one. That is a product decision, not a gap in the engine.

---
