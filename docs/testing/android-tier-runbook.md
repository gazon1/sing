# Android tier runbook — the first Maestro result

One page, for the host that has a working emulator. This is the single
highest-information action left in the traceability layer: no Maestro flow has
ever produced a result on any host, so the entire Android half of the scenario
system is inference.

## What is unverified, and what is not

Not inference — measured, from the Maestro 2.10.0 jar:

- `JUnitTestSuiteReporter$TestCase` declares a `file: String` field, so the JUnit
  reporter emits something the flow join can read.

Pure inference, and this runbook is how it stops being inference:

- the **base** of that `file` value — the join is deliberately base-agnostic
  rather than guessing, and has never met real output;
- that `text: Done` is reachable as the sheet's confirm;
- that `TestTags.RECURRENCE_OPTION_*` are on a row a device actually exposes;
- that the whole `--format=JUNIT` path produces XML the normaliser accepts.

## The run

```bash
./scripts/ensure-emulator.sh                 # serial or AVD; relaunches if the device died
TAGS=scenario:TASK-REC-01 scripts/run-maestro.sh
just trace-results maestro
```

Read the outcome in this order, because the first two are decided before any
assumption is tested:

1. **`run-maestro.sh` exits 0?** A failure here is the emulator, not the flow.
   This environment has a documented gfxstream crash (`2026-09-28-emulator-gfxstream-colorbuffer-segv`);
   the script relaunches on a lost device. Do not "fix" it by pinning `-gpu`.
2. **Does `build/maestro-results/` contain XML?** If not, the reporter produced
   nothing and nothing downstream can be diagnosed. Check the script's `--format`
   and `--output` are actually reaching the invocation.
3. **Did `just trace-results maestro` fill the Android cell?** `TASK-REC-01`
   should read `✅` on android. It will still read `⌛` in CI — the flows run in
   `maestro-smoke.yml`, a different workflow, and #150 settled that the header
   says so rather than moving an emulator into the main pipeline. Local filling
   is the point of this run.

## What each failure tells you

| Symptom | What it disproves |
|---|---|
| XML present, android cell still `⌛` | the `file`-attribute join — the base does not resolve against any of the three candidate roots |
| XML present, `unmapped` counts every flow row | the reporter emits no `file`, or emits one this JVM does not populate |
| `blocked`/unreachable in the Maestro output | the flow itself, not the join. Fix the flow and re-run; the join is still unproven |
| flow passes, normaliser rejects the commit | a stale `results.json`; `just trace-results` regenerates it from the current XML |

## After a green run

`just kiwi-seed && just kiwi-publish` writes the execution into Kiwi. Publish is
idempotent per `(commit, target)`, so a second run of the same commit overwrites
rather than duplicating. Verify in Kiwi that `TASK-REC-01` has **two**
executions for the commit — one android, one desktop — and that they are in
separate runs. Two executions in one run is the same observation counted twice,
which is the defect `2026-10-05-scenario-test-cases-in-kiwi` decision 4 exists to
prevent.

Then close #151 with the result, and the Android column stops being a footnote.

## What still will not be proven by this run

`TASK-TIME-01` is Android-only by measurement (#187): its UI lives in
`TaskDetailViewScreen`, wired only in `TasksNavGraph.android.kt`. Its `○` on
android is a hole this runbook cannot close — it needs a *second* Maestro flow,
for a scenario whose screen exists on no other platform.
