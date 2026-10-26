---
change: the-turn-is-one-model-call
verdict: pass
date: 2026-10-25
pr: https://github.com/gazon1/sing/pull/1166
reviewed: ddca979f0069b39cf3684a09458404d54b34e146
documents: sha256:4d64f2abede2a663a6d6bf42bfddf5d05c001ec60efbbd51c10a7575260f64bb
---

## What was reviewed

The whole change: `openspec/changes/the-turn-is-one-model-call/` — the
proposal, the design, the spec delta and the tasks — against the code it
describes. The turn of the built-in chat, the format the screen travels in,
and the two records the change rests on (REQ-CHTURN-007's usage rule and
FR-043's version rule, in
`docs/decisions/2026-10-25-the-version-we-can-compare.md`).

The reviewer was a separate agent with read-only access, given the diff and the
documents and nothing else: no history of writing the code and no pressure to
find nothing wrong. It was asked for defects with a location, and to check the
claims in the documents against the code rather than to run the build.

`reviewed:` is the commit the review was made against — the head of the branch
at that moment. It is a pointer, not a claim the gate can check: the commit is
not an ancestor of `main`, because the pull request lands by squash.

`documents:` is the digest of the contract as this verdict speaks for it — the
change's proposal and its spec delta (the change's own `design.md` is not part
of the digest). The gate recomputes it and compares, so the value here is the
one the tree carries now
(`python3 scripts/check-review-verdicts.py --digest the-turn-is-one-model-call`).

## The review: FAIL, six findings

Six findings, all closed in this branch. Each names what was wrong, and what
closed it.

1. **One turn wrote two or three usage rows; REQ-CHTURN-007 said "one row per
   turn."** `UsageRecordingTextGen.streamChat` recorded in both `onCompletion`
   and `catch`, so one failed call wrote two rows. The requirement was the
   thing that could not be kept as written: a correction is a *further* call
   and a real expense, so the rule is one row **per call**, and the spec is
   amended to say so. The code keeps exactly one writer — `onCompletion`, which
   sees every ending of the flow and the cause that ended it —
   `ChatTurnCostTest` drives a failing stream and a completed one and asserts
   one row each.

2. **Multi-line JSON fragments reached the reader's bubble.** A model asked for
   JSON Lines often pretty-prints instead, and a message spread over several
   lines was read line by line: the framer offered the parser a truncated
   object to reject, and the answer's own filter showed the message's body as
   prose. The depth is counted once, in `JsonMessageDepth`, and both the framer
   and the answer's text ask it; a message that closes mid-line is followed by
   prose again. `ChatTurnTest` drives a pretty-printed surface and asserts no
   fragment reaches the reader and the screen is drawn in one call.

3. **The connect outcome carried neither version (ADR + FR-043).** The protocol
   version is carried by `AcpConversationOutcome.Connected` and
   `AcpConnection.Connected`, and the screen names it; the tool-server build is
   *reported* — the app's own build, since the tool server it hands over is its
   own executable — and the screen names that too. `AcpExternalAgentViewModelTest`
   asserts the outcome carries both.

4. **"completed" was reported for a truncated final attempt.** The turn
   completes on the strength of its *last* attempt now: `completed` is reset per
   attempt and `TurnCompleted` is emitted once, at the end, only if the last
   attempt finished. `ChatTurnOutcomeTest` drives a rejected surface then a
   truncated second attempt and asserts the turn is failed.

5. **Two promised tests did not exist (no-credentials, cost).** Both exist:
   `ChatTurnCostTest` (the ledger rule) and the no-credentials case in
   `ChatTurnOutcomeTest`, which asserts the rule the turn relies on rather than
   instantiating the service wired to settings, tools and a remote config.

6. **A stale pointer to the deleted `feature/genui/transport/`.** `tasks.md`
   names `UsageRecordingTextGen.kt`, the file the rule now lives in.

## The second pass

The six fixes were reviewed by the second pass, which returned a `fail` for one
reason and a set of smaller ones, all closed here:

- the ledger rule itself, again — the amend was right, and the code now keeps
  it in one writer;
- the version report, again — the tool-server build is stated and not compared,
  which is what the ADR decided and what the requirement, re-scoped, asks for;
- the two promised tests, driven and passing;
- the stale pointer, gone;
- ktlint and detekt on the touched files — the parameter lists on one line, the
  blank line between the `when` conditions, the multiline-string closing quotes
  — all clean;
- the divergence with `main`: the branch was rebased onto `origin/main`, and the
  gate registry — `scripts/src/sgate/commands/_registry.py` as the source of
  truth, with `scripts/ci/static-gates.sh` and `docs/scripts/gate-registry.md`
  rendered from it — carries both this branch's two gates and `main`'s, with
  both rendered artifacts current (`sgate render-registry --check`,
  `sgate render-static-gates --check`).

## How the fixes were checked

- `:shared:compileTestKotlinJvm` and `:desktopApp:compileTestKotlin` pass.
- The filtered `:shared:jvmTest` run — the ACP, permission, chat-turn,
  chat-ViewModel, GenUI, MCP-error, agent-activity and MCP-tool classes — passes,
  including the new `ChatTurnCostTest`, the pretty-printed-message case, the
  truncated-final-attempt case, the no-credentials case and the connect-outcome
  case.
- `scripts/lint-changed.py` is clean on the changed files.
- `sgate render-registry --check` and `sgate render-static-gates --check` say the
  generated artifacts are current after the rebase.

## Links

- OpenSpec: `openspec/changes/the-turn-is-one-model-call/`
- ADR `docs/decisions/2026-10-25-the-turn-is-one-model-call.md`
- ADR `docs/decisions/2026-10-25-the-version-we-can-compare.md`
- ADR `docs/decisions/2026-10-25-the-sdk-lives-in-one-file.md`
- The gate `scripts/check-review-verdicts.py`
