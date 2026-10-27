---
change: the-summary-names-only-what-identifies
verdict: pass
date: 2026-10-26
pr: https://github.com/gazon1/sing/pull/1199
reviewed: f7c9227c56ecca0b6745653083632666518b0f94
documents: sha256:9d8eff558a3fb779d790260fb369ab63b3728cf2cdca6b2a11e6eda46d34e2f6
---

## What was reviewed

The change: `openspec/changes/the-summary-names-only-what-identifies/` (proposal, tasks,
`skip_specs`), the amended FR-020 in `specs/014-acp-client-agent-activity/spec.md`, and the
code it describes — `core/llm/ToolCallSummary.kt` and its tests — against FR-020.

The reviewer was a separate agent with read-only access, given the branch's diff (against
`feat/the-second-turn-is-refused-on-screen`, because `origin/main` does not currently
compile) and the artifacts. It ran the gates itself and was asked for defects with a
location, not a summary.

`reviewed:` is the commit the review's findings were fixed against — the branch head at that
moment, `f7c9227c56ecca0b6745653083632666518b0f94`. It is a pointer, not a claim the gate can
check: the pull request lands by squash, so the commit is not an ancestor of `main`.
`documents:` is the digest of the change's contract documents — the proposal, there being no
spec delta (`skip_specs: true`; the requirement it amends lives in the feature
specification) — recomputed by
`python3 scripts/check-review-verdicts.py --digest the-summary-names-only-what-identifies`.

## The review: PASS, with four should-fix findings, all closed

No blocker: the reviewer's independent sweep of every string argument of the app's thirty
tool input models found **no free-text key in the allow-list**, so the leak the change exists
to close is closed for the tools the app registers. Four findings were worth fixing, and
they were:

1. **A real identifier was missing.** `parentTaskId` — sent by `create_task` and
   `decompose_and_create` — was absent from the list, so a pure identifier had become
   `parentTaskId=…`. `colorHex`, `icon`, `supersededBy` and `projectName` were missing for the
   same reason. Added.
2. **The list carried entries nothing can read.** Booleans and numbers are quoted by shape
   before the list is consulted, so its flags/numbers group was decorative — and a list that
   *looks* like a reviewed inventory while a third of it is inert is the opposite of what an
   allow-list is for. Removed, with the reason stated and a note that the list is
   hand-maintained against the `*Input` models.
3. **Two tests named the value rules but were satisfied by the field rule.** They used `body`,
   which is elided as free text before either the 61-character bound or the multi-line branch
   is reached. They now use `title`, and a new test covers the length bound directly.
4. **Stale documentation in the file the change edits** — the test class's KDoc still
   described the deleted length rule — and the change's tasks were unticked.

The reviewer's notes are recorded rather than acted on: the allow-list is a heuristic on the
*external* agent's surface (whose tool schemas the app does not own), so `label`/`name` from a
third-party tool could still carry prose — the same exposure the old rule had, not widened by
this change; several entries are unreachable for the app's own tools; and `∅`/`null` are the
degenerate values the amended wording passes over. The reviewer also confirmed the change is
not a regression anywhere else: `{"id":"t-42"}` still renders, `AcpSdkBoundaryTest`'s
`title=…`/`description=…` still holds, and the proposal's own summary — which *should* show a
value, because the user approves what they read — is a different field that never passes
through this function.

## How the fixes were checked

- `./gw :shared:jvmTest --tests "*ToolCallSummary*" --tests "*AgentActivity*" --tests "*ToolCallActivity*" --rerun`
  — pass, including the new length-bound and multi-line cases on an identifying field.
- `python3 scripts/lint-changed.py --base feat/the-second-turn-is-refused-on-screen` — clean.
- `openspec validate the-summary-names-only-what-identifies --strict` — valid.
- `python3 scripts/check-issue-registry.py` and `check-spec-requirement-traceability.py` —
  pass.

## Links

- OpenSpec: `openspec/changes/the-summary-names-only-what-identifies/`
- FR-020, `specs/014-acp-client-agent-activity/spec.md`
- ADR `docs/decisions/2026-10-23-acp-client-and-agent-activity.md`
- The gate `scripts/check-review-verdicts.py`
