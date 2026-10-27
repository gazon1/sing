---
change: the-refusal-is-reachable-from-the-screen
verdict: pass
date: 2026-10-26
pr: https://github.com/gazon1/sing/pull/1176
reviewed: f44199215ed6e3e7e9d3de41091bcf2b94ba2d12
documents: sha256:03ef6e0d2d64197751cc68785df1a1e71c17164ff7badc5fc208545d46ef033e
---

## What was reviewed

The whole change: `openspec/changes/the-refusal-is-reachable-from-the-screen/` — the
proposal, the tasks and the `skip_specs` marker — against the code it describes. The
external-agent screen, its ViewModel's refusal path, and the two tests that pin them.

The reviewer was a separate agent with read-only access, given the branch's diff against
`origin/main` and the artifacts, and nothing else: no history of writing the code and no
pressure to find nothing. It was asked to check the claims against the code and to run the
gates itself, not to summarise the diff.

`reviewed:` is the commit the review was made against — the head of the branch at that
moment, `f44199215ed6e3e7e9d3de41091bcf2b94ba2d12`. It is a pointer, not a claim the gate can
check: the commit is not an ancestor of `main`, because the pull request lands by squash.
`documents:` is the digest of the change's contract documents — the proposal (there is no spec
delta; `skip_specs: true` is set, and the reason is written in `.openspec.yaml`) — recomputed by
`python3 scripts/check-review-verdicts.py --digest the-refusal-is-reachable-from-the-screen`.

## The review: PASS, with two should-fix findings, both closed

The reviewer reproduced the gates (the shared ViewModel test, the desktop screen test with
`-Ptest.tags=slow`, `lint-changed`, the issue registry, the tag catalogue, dead refs, and
`openspec validate --strict`), read `AcpExternalAgentViewModel.send()` and the screen's
notification wiring, and confirmed the fix does what it claims: the refusal is emitted before
the input is cleared and before a turn starts, and the message reaches a dialog the reader
sees.

**Two findings worth closing, and the reason they mattered:**

1. **The screen test stopped one step short of its own title.** It proved the input was
   enabled; it never proved the refusal reached a person. That is the exact defect shape this
   change exists to describe — a rule verified through a double while the wiring the
   application uses goes unexercised. The test now sends a second message from the enabled
   field and asserts the refusal's dialog is on screen (`TestTags.RESULT_DIALOG`), and it ends
   by disconnecting so the harness is left with nothing to wait for.

2. **The task list recorded a gate that cannot run.** It named
   `./gw :desktopApp:jvmTest`, and `:desktopApp` has no `jvmTest` task; and the class is
   `@Tag("slow")`, so a plain `:desktopApp:test` finds no tests and fails on
   `failOnNoDiscoveredTests`. The line now records the command that actually ran:
   `./gw :desktopApp:test -Ptest.tags=slow`.

The remaining observations were notes, not defects, and are recorded rather than acted on:

- **The affordance is a shape call.** `ChatInputBar` documents its `onStop` as "the same
  button becomes the stop"; this screen keeps a live Send beside a separate Stop, so during a
  run the reader sees a control that can only produce a refusal. Using `onStop` would re-hide
  the refusal, so the deviation is kept — and it is more visible now than when Send was greyed
  out. Noted for a future UX pass.
- **The refusal is a modal dialog titled "Error".** A turn running is not an error, and the
  dialog sits in front of the Stop button the message tells the reader to press. The message
  text is right; the vehicle is heavy. Noted, not changed here.
- **Guard ordering in `send()`.** `openSession ?: return` sits before the `isRunning` guard, so
  "no session" is a silent return. Unreachable while `connection is Connected` (the only state
  in which the input is enabled), and pre-existing. Noted.
- **The two AI screens now answer the same question differently.** The built-in chat keeps a
  disabled input and a silent `isLoading` guard; the external-agent screen refuses in words.
  That is deliberate and declared out of scope: the chat has no requirement to say why.

## How the fixes were checked

- `./gw :shared:jvmTest --tests "*AcpExternalAgentViewModelTest*"` — pass, including the
  refusal's message.
- `./gw :desktopApp:test -Ptest.tags=slow --tests "*AcpExternalAgentScreenTest*"` — pass,
  including the enabled input, the Stop control, and the refusal's dialog.
- `python3 scripts/lint-changed.py` — no findings.
- `openspec validate --all --strict` — pass.
- `python3 scripts/check-issue-registry.py` — pass; the record names only documents the
  change has (`specs_dir: null`, because `skip_specs` means there is no delta).

## Links

- OpenSpec: `openspec/changes/the-refusal-is-reachable-from-the-screen/`
- Requirement: REQ-EXTAG-007, in `openspec/changes/acp-external-agent-desktop/specs/ai/external-agent/spec.md`
- ADR `docs/decisions/2026-10-23-acp-client-and-agent-activity.md`
- The gate `scripts/check-review-verdicts.py`
