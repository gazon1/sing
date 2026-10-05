# Spec coverage index

Which modules have an OpenSpec capability spec, and — more importantly — which do not.

**Read this before authoring a change.** OpenSpec's own guidance is explicit:

> Resist the urge to back-fill everything. Writing specs for code you aren't changing
> feels productive and usually isn't. Those specs go stale, because nothing forces them
> to track reality. Let real changes drive your specs.

So an entry under **Not covered** is not debt to pay down on its own. It means: the
first change that touches this module should add the spec as part of that change.

Nested spec paths are supported — ids are `area/capability`, so a spec at
`specs/<area>/<capability>/spec.md` has the id `<area>/<capability>`. Every spec
in the tree is currently flat, so every id below has no `/`. Renaming a spec to
nest it changes its id, and every reference to the old id breaks with it.

## Covered

| Spec id | Source of truth | Notes |
|---|---|---|
| `nav3-desktop-jvm-entry-dispatch` | `docs/decisions/2026-09-16-nav3-desktop-in-memory-no-savedstate.md` | Entry-dispatch contract shared by the Android and JVM shells. |
| `navigation-open-policy` | the archived `navigation-open-policy` change (2026-10-04) | Every screen open resolved through one policy. |
| `test-execution-integrity` | `docs/decisions/2026-10-04-test-execution-integrity.md` | The "tests ran" floors, and why a green pass/fail is not a baseline. |
| `crash-reporting` | the archived `background-handler-injection` change (2026-10-05), plus `docs/decisions/2026-10-05-background-failure-handler-and-the-guard-it-behind.md` | Where a background failure goes, and why that is the component's choice rather than a process-wide default. REQ-1..REQ-4 of the `failure-visibility` change and REQ-7/REQ-8 of `scope-reporter-agreement` also target this capability and are **not** here yet. `failure-visibility` is open on REQ-2 alone (#132); `scope-reporter-agreement` is open on one task — REQ-8's requirement that the check be *shown* to fail, which was demonstrated by hand and not yet performed by the build. This row is not the place to record work that has not shipped. |

## Not covered

Every module below has behaviour that a spec could describe, and no spec today.
Author one **when a change touches that module** — not before.

| Module | Candidate spec id | Source ADRs (starting points) |
|---|---|---|
| `core/write-pipeline` | `core/write-pipeline` | `2026-09-21-generic-user-scoped-repository`, `2026-09-27-write-layer-soundness` (20 citations) |
| `core/log-export` | `core/log-export` | `add-log-export` change (3/28 tasks) |
| `core/sync-state` | `core/sync-state` | `2026-09-23-sync-state-model`, `2026-09-23-sync-pull-handlers-and-ui`, `2026-09-23-sync-scheduling-abstraction` |
| `core/security`, `core/auth` | `core/auth-token-storage` | `2026-09-05-secret-storage-split` |
| `core/di` | `core/di-module-aggregation` | `2026-09-27-di-module-aggregator-narrative` |
| `core/ui` (MVI) | `core/ui-mvi` | `2026-09-27-draft-mvi-single-state-source` |
| `core/platform` (time) | `core/time-semantics` | `2026-09-27-remove-platform-clock-object`, `2026-10-01-startdate-vs-duedate-semantics` |
| `core/files`, `core/backup` | `core/backup-codec` | `2026-09-29-kotlinx-datetime-androidapp-missing` |
| `core/reminders` | `core/reminder-scheduling` | `2026-09-22-alarmmanager-reminders` |
| `feature/tasks` | `tasks/task-lifecycle` | `2026-09-23-task-dependencies-completion` |
| `feature/agenda` | `agenda/saved-views` | `2026-09-16-agenda-mr4-saved-views-create-reorder` |
| `feature/projects` | `projects/hierarchy-and-counts` | `2026-09-08-projects-ux-rework` |
| `feature/notes` | `notes/note-links` | `2026-10-02-note-entity-dual-task-linkage`, `2026-09-07-notes-internal-links-backlinks`, `2026-09-09-notes-outgoing-links-extraction` |
| `feature/calendar` | `calendar/entry-navigation` | — |
| `feature/ai` | `ai/tool-registry` | `2026-09-07-write-tools-in-koog-registry`, `2026-09-26-writer-reviewer-pattern` |
| `feature/pomodoro` | `pomodoro/timer-model` | `2026-09-22-pomodoro-hybrid-timer` |
| `feature/attachments` | `attachments/storage-and-linking` | — |
| `feature/search`, `feature/tags` | — | — |
| `:mcp-server` tool surface | `mcp/write-tool-surface` | `singularity-todo-cli-tool-surface` skill |

## Backfill candidates (audited separately)

Two rows above are the only genuine backfill candidates, because the ADR debt is large
enough to be worth capturing and the behaviour is stable:

- `core/sync-state` — ~800 lines across three oversized ADRs.
- `notes/note-links` — cross-cutting FK and backlink invariants.

Both are **droppable**. If a review concludes the backfill would go stale before the
next change in that area, delete the row instead of writing the spec.

## Maintenance

Add a row to **Covered** in the same change that adds or renames a spec. Keep this file
honest about what is *not* covered — a visible gap list is the point.
