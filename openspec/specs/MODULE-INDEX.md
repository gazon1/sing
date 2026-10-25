# Spec coverage index

Which modules have an OpenSpec capability spec, and — more importantly — which do not.

**Read this before authoring a change.** OpenSpec's own guidance is explicit:

> Resist the urge to back-fill everything. Writing specs for code you aren't changing
> feels productive and usually isn't. Those specs go stale, because nothing forces them
> to track reality. Let real changes drive your specs.

So an entry under **Not covered** is not debt to pay down on its own. It means: the
first change that touches this module should add the spec as part of that change.

Nested spec paths are supported — ids are `area/capability`, so a spec at
`specs/<area>/<capability>/spec.md` has the id `<area>/<capability>`. Some ids
below carry an area and some do not. Renaming a spec to nest it changes its id,
and every reference to the old id breaks with it.

## Covered

| Spec id | Source of truth | Notes |
|---|---|---|
| `acp/client-lifecycle` | the archived `agent-surface-follow-ups` change (2026-10-25) | The ACP client's lifecycle: the connect and close transitions, which exclude each other, the terminate transition, which does not wait for that exclusion, and what a turn in flight may read while any of them happens. |
| `ai/tool-registry` | the archived `the-chat-can-call-tools` change (2026-10-22) | The tools an assistant may be offered, and what it is told when a tool fails, does not exist, or keeps being asked for. |
| `ai/write-classification` | the archived `acp-client-data-safety` and `agent-surface-follow-ups` changes (2026-10-25) | How every tool an agent can call declares the one write behaviour it has, and how that declaration is checked against what the tool does before it is proposed to a user. |
| `build/detekt-coverage` | the archived `agent-surface-follow-ups` change (2026-10-25) | That every Gradle module holding Kotlin source is judged by the repository's detekt configuration and by the project's own rules, and that a class carrying more than one concern is split. |
| `core/database-safety` | the archived `acp-client-data-safety` change (2026-10-25) | What the start-up probe may delete, what it does when it cannot be certain that a file is not the application's database, and what a failed start-up leaves behind. |
| `mcp/protocol-output` | the archived `agent-surface-follow-ups` change (2026-10-25) | That the protocol channel has one owner — the value that holds the stream — and that everything which is not the protocol stays off it. |
| `mcp/write-tool-surface` | the archived `acp-client-data-safety` change (2026-10-25) | The tool server's profile argument and its refusals: which profile it works with, what an unknown one earns, and what the client is told when it cannot start. |
| `nav3-desktop-jvm-entry-dispatch` | `docs/decisions/2026-09-16-nav3-desktop-in-memory-no-savedstate.md` | Entry-dispatch contract shared by the Android and JVM shells. |
| `navigation-open-policy` | the archived `navigation-open-policy` change (2026-10-04) | Every screen open resolved through one policy. |
| `test-execution-integrity` | `docs/decisions/2026-10-04-test-execution-integrity.md` | The "tests ran" floors, and why a green pass/fail is not a baseline. REQ-19..REQ-23 came from the archived `android-test-hardening` change (2026-10-20) and are stated here because the code ships with them: the floor ratchets in both directions, the device tier answers on every pull request and never blocks a merge, a wait's budget belongs to the element's kind, a flow's fixtures belong to that flow, and a declared class that produced no report fails the build. |
| `user-authentication` | the archived `offline-local-session` change (2026-10-20) | Signing in, signing out, and the local session that works with no network. REQ-UA-020..REQ-UA-022 came from that change: the session survives the process, the sign-out is local, and the local owner is not the profile. |
| `tasks` | the archived `working-days-are-a-set` and `working-day-switches-reach-the-agenda` changes (2026-10-20) | The task capability's observable behaviour, plus the two working-day requirements: a setting whose heading names a set is modelled as that set, and a setting that changes the agenda changes the agenda. |
| `write-pipeline` | the archived `baseline-write-pipeline` change (2026-10-20) | The profile-scoped write pipeline: a write cannot cross a profile boundary, the database enforces the same rule the code does, and a write is not a write until it is queued. |
| `crash-reporting` | the archived `background-handler-injection` change (2026-10-05), plus `docs/decisions/2026-10-05-background-failure-handler-and-the-guard-it-behind.md` | Where a background failure goes, and why that is the component's choice rather than a process-wide default. REQ-1..REQ-4 of the `failure-visibility` change and REQ-7/REQ-8 of `scope-reporter-agreement` also target this capability and are **not** here yet. `failure-visibility` is open on REQ-2 alone (#132); `scope-reporter-agreement` is open on one task — REQ-8's requirement that the check be *shown* to fail, which was demonstrated by hand and not yet performed by the build. This row is not the place to record work that has not shipped. |
| `calendar-sync` | `docs/decisions/2026-10-05-google-sync-known-gaps-and-field-drift.md`, `2026-10-06-google-sync-failures-were-shaped-like-skips.md`, `2026-10-05-google-sync-local-side-came-from-the-shadow.md` | The system projection and the Google two-way peer, kept apart; a pass's three outcomes; one definition of which tasks belong on a calendar; the import window's single source. Authored 2026-10-07 from shipped behaviour, not as backfill — the drift it records had already produced five defects. |
| `version-gate` | the archived `version-gate-read-timeout` change (2026-10-20), plus `docs/decisions/2026-10-20-version-gate-read-timeout.md` | What the version gate does when the remote config cannot be read: it fails **open** — reports the failure, admits the user on the defaults, and records the bypass with its reason. REQ-1 and REQ-2 came from the archived change; REQ-3 — the read bound belongs to the *port*, not only to the caller — was added on 2026-10-20 with the `android-test-hardening` change, and is stated here because the code ships with it. |
| `search` | the archived `the-search-asks-the-index` change (2026-10-21), plus `docs/decisions/2026-10-22-the-index-answers-the-positive-terms.md` | What a search finds, and who answers it: the index answers the positive terms, the rule answers the negated ones, and a term matches a word by its beginning. The rule has one owner — the full-text index — and the change that made it so is recorded in the decision of 2026-10-22. |

## Not covered

Every module below has behaviour that a spec could describe, and no spec today.
Author one **when a change touches that module** — not before.

| Module | Candidate spec id | Source ADRs (starting points) |
|---|---|---|
| `core/log-export` | `core/log-export` | `add-log-export` change (3/28 tasks) |
| `core/sync-state` | `core/sync-state` | `2026-09-23-sync-state-model`, `2026-09-23-sync-pull-handlers-and-ui`, `2026-09-23-sync-scheduling-abstraction` |
| `core/security`, `core/auth` | `core/auth-token-storage` | `2026-09-05-secret-storage-split` |
| `core/di` | `core/di-module-aggregation` | `2026-09-27-di-module-aggregator-narrative` |
| `core/ui` (MVI) | `core/ui-mvi` | `2026-09-27-draft-mvi-single-state-source` |
| `core/platform` (time) | `core/time-semantics` | `2026-09-27-remove-platform-clock-object`, `2026-10-01-startdate-vs-duedate-semantics` |
| `core/files`, `core/backup` | `core/backup-codec` | `2026-09-29-kotlinx-datetime-androidapp-missing`; the open `the-backup-keeps-the-verb` change (2026-10-23) authors `backup/dependency-graph` — a backup keeps what a dependency edge means |
| `core/reminders` | `core/reminder-scheduling` | `2026-09-22-alarmmanager-reminders` |
| `feature/tasks` | `tasks/task-lifecycle` | `2026-09-23-task-dependencies-completion`; the open `blocked-completion-guard` change (2026-10-21) adds the blocked-completion requirement to the `tasks` capability and the `projects/project-closing` capability for `feature/projects`; the open `close-rule-consolidation` change (2026-10-22) gives the blocking rule one owner, moves the refusal into the write, and adds the bulk close and the reopen cascade; the open `every-close-refuses-in-its-own-write` change (2026-10-23) makes the recurring close refuse in its write and makes the bulk close ask the same question |
| `feature/agenda` | `agenda/saved-views` | `2026-09-16-agenda-mr4-saved-views-create-reorder` |
| `feature/projects` | `projects/project-closing` | `2026-09-08-projects-ux-rework`; the open `blocked-completion-guard` change (2026-10-21) authors `projects/project-closing` — closing a project, and the cascade to its open tasks; the open `close-rule-consolidation` change (2026-10-22) makes the cascade land as a whole and opens a closed project when one of its tasks comes back to work; the open `a-closed-project-is-reachable` change (2026-10-23) keeps a closed project reachable and gives it a way back |
| `feature/notes` | `notes/note-links` | `2026-10-02-note-entity-dual-task-linkage`, `2026-09-07-notes-internal-links-backlinks`, `2026-09-09-notes-outgoing-links-extraction` |
| `feature/calendar` | `calendar/entry-navigation` | — |
| `feature/pomodoro` | `pomodoro/timer-model` | `2026-09-22-pomodoro-hybrid-timer` |
| `feature/attachments` | `attachments/storage-and-linking` | — |
| `feature/tags` | — | the `search` capability (2026-10-21) covers the *finding* of a tag by name; managing the tags themselves is still uncovered |

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
