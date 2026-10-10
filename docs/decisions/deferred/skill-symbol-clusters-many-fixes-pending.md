---
title: "Skill Symbol Clusters Many Fixes Pending"
date: 2000-01-01
status: OPEN
tags: ["deferred"]
---

**Status: OPEN**

**Tracked as:** #446

**Found in:** Phase 1.7 (`refactor/openspec-adoption`), via
`check-doc-dead-refs.py --skill-symbols` (detector 8). All ~840 findings
in 9 skill files are accepted in `config/docs/skill-symbol-baseline.txt`.
Zero NEW findings at baseline creation.

The top clusters identified:

1. **`ai-tool` + `llm-usage-tracking` + `cli-tool-surface` + `mcp-server`**:
   `UsageRecorder` (interface, exists), `RoomUsageRecorder` (class, exists),
   `ModelPricing`, `LlmUsageEntity`, `UsageExtractor` — describe an architecture
   that was partially built; the AI usage screen was never completed.
2. **`nav3-nested-graphs` + `cross-feature-navigation`**:
   `AppNavHost.kt` (file does not exist), `AgendaNavGraph` (exists but
   described differently), `NavKey` vs `AppNavKey` (same concept, inconsistent
   naming), `NavDisplay` (exists in `desktopShellNav3`).
3. **`icon-registry`**:
   `TagIconRegistry`, `PriorityIconRegistry`, `NoteColorRegistry` (none exist;
   only `ProjectIconRegistry` is real).
4. **`task-callback-groups`**:
   `NoteCardActions` (should be `NotesActions`), `TaskDetailActions`
   (check if this file actually exists in `feature/tasks/components/`).

**Status:** OPEN. These skills describe an architecture that no longer matches
the code. Fixing them requires reading the actual code and rewriting the
skills — too large for a single PR. They are guarded by the baseline:
if an agent adds a NEW dangling symbol reference in any of these skills,
CI will fail. The backlog owner should prioritize `nav3-nested-graphs`
(first referenced by `wayfinder`) and `ai-tool` (most complex).

---
