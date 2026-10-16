---
title: "The nightly CI that failed on a crash, and the gate that could not see one"
date: 2026-10-16
status: accepted
tags: ["ci", "gates", "measurement-integrity", "sabotage-controls"]
---

## Context

Nightly CI failed (#659). The proximate cause was small: `check-gate-wiring.py`
carried a positive control whose command was `./scripts/check-skill-frontmatter.sh`,
a shim deleted in #657. `run_gate()` called `subprocess.run()` on a path that no
longer existed and the script died with `FileNotFoundError` partway through Part B.

The interesting part is what a crash mid-Part-B costs. Parts C through J never
ran, and neither did the error report. Four defects were sitting in the tree at
that moment, all of them reported by parts that were never reached:

1. `skill-frontmatter` had been retired from the registry in #657 but only its
   `.sh` shim was deleted. `check_skill_frontmatter.py`, its `sgate` command
   module, its `SCRIPT_GATES` control and the comment citing it as a live example
   all survived — the same "removed from one place but not another" shape FR-011
   in spec 013 exists to catch.
2. `check-dead-settings-baseline.txt` shipped `Notifications.no_such_field` — the
   literal replacement string from that gate's own sabotage control. A control
   had mutated the real file, failed to restore it, and the residue was committed.
3. Two blocking traceability gates had no positive control at all, because
   `check_script_gates_vs_registry()` joined on `cmd[1]`. For a `python3 -m
   traceability validate` control that is the literal string `"-m"`, which matches
   no registry entry. The same expression also dropped every single-element shell
   command (`["./scripts/check-detekt-registrations.sh"]`).
4. The `gate-liveness` control sabotaged `check-backlog-issue-refs.py`, retired
   in #657. `check-gate-liveness.py` probes only registered gates, so the
   mutation was invisible to it and the control passed against a defect it had
   not created.

## Idea

Fixing the `FileNotFoundError` is a one-line change and would have turned CI
green while leaving every one of those in place. Each is the same defect class
the file itself warns about twice: a derivation that quietly sees a third of its
input. So the fix has to reach the class, not the instance.

## Decision

**Removal is completed, not reversed.** The owner closed `skill frontmatter` in
#657; the surviving files are removed (`check_skill_frontmatter.py`, its `sgate`
command module and its test). The `SCRIPT_GATES` entry and its `_drop_second_line`
control go with them, and the Part H comment stops citing a deleted file as its
hyphen-spelling example — it now cites `check-detekt-registrations.sh` and
`check_adr_status.py`, which both exist.

**Part J's two blocking traceability gates.** This change originally re-aimed
Part J's join at resolved identity, because it keyed on `cmd[1]` — the literal
`"-m"` for a `python3 -m traceability validate` control — and silently dropped
every single-element shell command. #669 reached the same two gates by a
different route: a `GATE_EXEMPTIONS` entry for `traceability`, with
`scripts/tests/test_traceability.py` as the proving, on the grounds that a
sabotage control would mean corrupting a real scenario spec and re-running a full
corpus scan on every gate-wiring check. That decision is kept here.

**`sgate` forwards the child's stderr.** This is the load-bearing change, and it
is not one of the four above.

## What #669 changed underneath this

While this was in review, #669 landed on `main` and independently diagnosed three
of the same defects. Rebasing onto it changed what this change still had to do:

- It removed the `Notifications.no_such_field` baseline residue and re-aimed the
  `dead-settings` control at `Notifications.sound`. That entry is kept here as the
  comment explaining why `reminderDefault` stopped being a valid target.
- It removed the `gate-liveness` and `skill-frontmatter` `SCRIPT_GATES` entries and
  documented `GATE_EXEMPTIONS` for both, with `scripts/tests/` as the proving.
  Those decisions are **kept**; this change no longer re-aims a control #669
  deliberately retired.
- It removed the orphaned `skill-frontmatter` control but left the script, its
  `sgate` command module and its test on disk. Those are deleted here, because
  nothing else will.

So after the rebase this change is: the stderr fix, the three file deletions, and
the two comments that record provenance.

## Rationale

`run()` in `scripts/src/sgate/commands/_runner.py` forwarded `result.stdout` and
discarded `result.stderr`. `check-gate-liveness.py` decides "died instead of
reported" by matching traceback markers on stderr. After the sgate migration every
registry gate runs through `run()`, so no gate could ever present a traceback: a
gate that raised was recorded as `exit 1: violation` and judged **alive**.

The liveness gate was therefore not weak on one gate — it was blind on all of
them, and it reported "all alive" with the same confidence it would have used had
the crash been real. Its own control could not catch this, because the control
only asks "did check-gate-liveness exit non-zero?", and the answer was a
well-earned zero.

Forwarding stderr is a one-line change with the correct blast radius: a *finding*
is reported on stdout as `ERROR:` lines with a non-zero exit, and its exit status
and text are unchanged. Only the discarded channel is restored.

Measured after the change, with `check-suppression-intent.py` sabotaged to raise
at import: `check-gate-liveness.py` exits 1 and names
`gate "file-level suppressions explain themselves" died instead of reporting`.
Before it exited 0.

This is also why #669's `gate-liveness` exemption is weaker than it looks. Its
reason records three sabotage targets that "passed a deliberately sabotaged
input" and concludes that no target works. Two of those three were aimed at
scripts no registered gate runs — a real error — but the third failure has a
different cause: even for the *right* script the injected raise was invisible,
because the runner discarded the stderr carrying it. With the channel restored, a
control does work again; #669's own text allows for one being added back.

## Consequences

- `check-gate-liveness.py` can fail again. It is a blocking gate, so any gate that
  starts crashing on stderr will now redden CI instead of passing quietly. That is
  the intent, but it is a new class of red the project has not been absorbing yet.
- Gates that write warnings to stderr on success will print them. Forwarding is
  still preferable to discarding, but the noise is real and should be cleaned at
  the source rather than filtered here.
- The `dead-settings` control target was corrected against measurement rather
  than assumption, and verified by running the gate with the change applied.
- #669 keeps its exemptions. This change does not restore the `gate-liveness`
  sabotage control, so that gate's proving remains `scripts/tests/` plus
  `check.sh` — the stderr fix improves what it *can* catch, not what is asserted
  against it.
- `Notifications.reminderDefault` no longer appears in
  `check-dead-settings-baseline.txt`. The narrower question from
  [2026-10-07-reminders-capability-gate.md](2026-10-07-reminders-capability-gate.md)
  — whether *per-task* reminders ever receive it — is unanswered and belongs in a
  new entry if the gate reports it.
- Locally, `check-test-runs.py` still fails its clean-tree run, because this
  checkout has no full-suite JUnit XML. CI runs it after Gradle and it passes
  there; it is not a repository defect.

## Links

- Issues: #659 (this failure), #657 (the strangler that caused it)
- [2026-10-16-gate-registry-wave-b-strangler.md](2026-10-16-gate-registry-wave-b-strangler.md)
- [2026-09-23-tech-debt-audit.md](2026-09-23-tech-debt-audit.md)
- Spec 013 (gate removal), FR-011: a control proving a deleted gate and a
  registry row naming a deleted script are exactly what Parts F and J cross-check

---

## Addendum: #629 is closed won't-fix

Recorded here because it is the same failure class in a different place: a
prescribed change whose justification was never verified.

#629 asked for `scope.launch { repeatOnLifecycle(STARTED) { … } }` across 20+
ViewModels, on the grounds that coroutines keep running when the app goes to
background. It does not hold:

- `repeatOnLifecycle` is an extension on `LifecycleOwner`, and **no** file under
  `shared/.../feature/` references one. Every VM takes an injected
  `AutoCloseableCoroutineScope` that `MviViewModel` cancels in `onCleared()`.
- The lifecycle-aware collection already exists at the layer that owns the
  lifecycle: **42 files** use `collectAsStateWithLifecycle` in Composables.
- The cited symptom is gone. `SavedAgendaViewModelTest` no longer exists; the one
  live `UncompletedCoroutinesError` reference is a documented, deliberate test
  pattern in `AttachmentAnnotationViewModelTest`.
- The root claim is an unvalidated guess in
  [2026-09-23-tech-debt-audit.md](2026-09-23-tech-debt-audit.md) §2: *"Likely
  `scope.launch` without `repeatOnLifecycle` or premature scope cancellation."*
  The second half of that sentence describes the opposite failure.

Applying it would also re-couple VM lifetime to UI visibility — collection would
stop and restart with the UI, and every VM test would need a lifecycle to drive.
That is the coupling the injected-scope pattern exists to avoid.

The observation underneath it is fair: a retained ViewModel does keep its
collectors alive while the app is backgrounded. Whether that costs anything is a
question about *which* collectors run long, and the answer is a measurement, not
an audit line. Issue closed with the disproof posted.