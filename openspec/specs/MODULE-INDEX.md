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
| `acp/real-bridge` | the archived `the-client-meets-a-real-bridge` change (2026-10-25) | The ACP client driven against a real agent bridge rather than a fake: the probe's exit code is the verdict, and the shapes the bridge sends are covered by a test. |
| `agent-toolbox-surface` | the archived `agent-toolbox-surface` change (2026-10-25) | The surface the agent is given: which tools exist, which are switched off, which build; the project's knowledge, searchable through the API the agent already writes to; and a record of what it called. The catalogue is derived from the same place the agent is told from, so the two cannot drift. |
| `backup/dependency-graph` | the archived `the-backup-keeps-the-verb` change (2026-10-25) | That a backup keeps what a dependency edge means: the verb travels with the edge, so a relation that follows up on another comes back from a restore as the relation that follows up. |
| `branch-hygiene` | the archived `the-merged-branch-is-pruned` change (2026-10-23) | That the local branches of merged pull requests stop accumulating: the plan names them, nothing is deleted without an explicit request, and the request to GitHub is part of the contract. |
| `change-review-integrity` | the archived `the-verdict-names-the-commit` change (2026-10-23) | That a review verdict names what it reviewed — the commit and the digest of the change's contract documents — and that a claim about CI names the workflow, the run and the conclusion. |
| `decision-digest` | the archived `the-full-digest-does-not-grow` change (2026-10-23) | That the complete index of decisions does not grow with the corpus: a folded index, a budget that is a rate rather than a wall, an entry findable by slug, by title and by tag, and an orientation layer that stays the same size. |
| `gate-tooling` | the archived `the-gates-run-on-every-machine` change (2026-10-25) | That the gate suite runs on a machine that has never run it, and that a waiver's premise is a checked fact rather than a sentence. |
| `genui` | the archived `the-genui-count-travels-with-the-value` change (2026-10-25) | That the count of the replacements that came from outside travels inside the value it counts, per path, and that a value and its count are read together. |
| `internal-link-grammar` | the archived `one-grammar-for-internal-links` change (2026-10-22) | The one definition of what an internal link is, and that every reader, writer, matcher and actor derives its reading from that definition rather than from a copy of it. |
| `lint-scope-policy` | the archived `androidapp-debug-lint-policy` change (2026-10-07) | That every source set a module declares is scanned by detekt, and that the scan list is verified against the filesystem — so a source set missing from `detekt.source` is a gate failure rather than an unscanned gap that looks like a clean module. |
| `notes` | the archived `the-editor-and-the-rewrite-from-outside` change (2026-10-25) | That the note editor's bridged field tells a replacement from a keystroke, that the user's unsaved edits win over an incoming replacement, and that every bridged site records who may rewrite the field. |
| `notification-targeting` | the archived `a-reminder-that-points-at-no-view` change (2026-10-22) | Where a reminder's target comes from, what the notification carries, and what happens when the target is gone by the time the notification is tapped. |
| `offline-sync` | the archived sync changes (2026-10-04…2026-10-29), among them `the-row-keeps-what-the-document-does-not-say` (2026-10-23), `the-row-keeps-what-the-document-does-not-say-for-every-type` and `the-project-is-read-against-the-row` (2026-10-29) | That local edits stay safe when the network is not there: every change is queued durably before it is reported as saved, ordering survives clock drift, and a pull cycle either applies a page completely or says plainly that it could not. REQ-OS-029..REQ-OS-031 are the row-writing rules: the row keeps what the document does not say, for every document type, and the project is read against the row. |
| `projects` | the archived `the-next-layer-of-contracts` change (2026-10-25) | That the project detail screen tells a replacement from a keystroke — the same shape the note editor learned. |
| `projects/project-closing` | the archived `blocked-completion-guard` change (2026-10-25) | That closing a project is distinct from archiving it and from deleting it: closing takes the project's still-open tasks with it, asks first when one of them is blocked, lands as a whole, and leaves a closed project reachable and reopenable. |
| `proposals` | the archived `the-proposal-has-a-lifecycle` change (2026-10-23) | That a proposal has a lifecycle and a typed vocabulary: a change the user may edit before it is applied, a visible reason for a refusal, a status that is what the proposal's changes say, and one vocabulary that survives the database. |
| `task-series` | the archived `task-series` change (2026-10-25) | That one piece of work can be scheduled on several dates: one task row per date, all sharing one series identity, with the shared fields edited for the whole series in one operation. |
| `text-input` | the archived `text-field-state-migration` change (2026-10-25) | That every text field's source is static or owned, that the count of the replacements that came from outside travels with the value, and that the shape of a field is derived from the code rather than read at runtime. |

## Not covered

Every module below has behaviour that a spec could describe, and no spec today.
Author one **when a change touches that module** — not before.

| Module | Candidate spec id | Source ADRs (starting points) |
|---|---|---|
| `core/log-export` | `core/log-export` | `add-log-export` change (3/28 tasks) |
| `core/sync-state` | `core/sync-state` | `2026-09-23-sync-state-model`, `2026-09-23-sync-pull-handlers-and-ui`, `2026-09-23-sync-scheduling-abstraction` |
| `core/security`, `core/auth` | — (the candidate `core/auth-token-storage` was **not** taken) | `2026-09-05-secret-storage-split`; `2026-10-25-auth-adapter-replaced-by-kmpauth` — the observable token-storage contract already has an owner: `REQ-UA-002`, `REQ-UA-012` and `REQ-UA-013` of the `user-authentication` capability, with `REQ-UA-023` added by that change. A second capability would restate the same behaviour under a new name and give it two owners and two places to update |
| `core/di` | `core/di-module-aggregation` | `2026-09-27-di-module-aggregator-narrative` |
| `core/ui` (MVI) | `core/ui-mvi` | `2026-09-27-draft-mvi-single-state-source` |
| `core/platform` (time) | `core/time-semantics` | `2026-09-27-remove-platform-clock-object`, `2026-10-01-startdate-vs-duedate-semantics` |
| `core/files`, `core/backup` | `core/backup-codec` | `2026-09-29-kotlinx-datetime-androidapp-missing`; the archived `the-backup-keeps-the-verb` change (2026-10-25) authors the covered `backup/dependency-graph` — the codec that writes the payload is still uncovered |
| `core/reminders` | `core/reminder-scheduling` | `2026-09-22-alarmmanager-reminders` |
| `feature/tasks` | `tasks/task-lifecycle` | `2026-09-23-task-dependencies-completion`; the archived `blocked-completion-guard` (2026-10-25), `close-rule-consolidation` (2026-10-25) and `every-close-refuses-in-its-own-write` (2026-10-25) changes put the blocked completion, the single owner of the blocking rule, the bulk close and the reopen cascade into the covered `tasks` capability — the lifecycle itself is still uncovered |
| `feature/agenda` | `agenda/saved-views` | `2026-09-16-agenda-mr4-saved-views-create-reorder` |
| `feature/notes` | `notes/note-links` | `2026-10-02-note-entity-dual-task-linkage`, `2026-09-07-notes-internal-links-backlinks`, `2026-09-09-notes-outgoing-links-extraction`; the covered `notes` capability (2026-10-25) states the editor's rewrite contract, and the links and backlinks are still uncovered |
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
