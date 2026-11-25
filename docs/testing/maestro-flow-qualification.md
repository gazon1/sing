# Maestro flow qualification

A new Maestro flow, or a materially changed one, must not enter a blocking automated scope on the strength of a single run. The qualification policy is the evidence that the flow is repeatable in the same documented environment.

## What requires qualification

Qualification is required when a flow can affect a blocking automated scope and either:

- the flow is **new** (in `Maestro/flows/`, listed in `config/docs/maestro-flows.txt`, and not admitted yet), or
- the flow or one of the files it runs (transitively, via `runFlow` / `file:` / `runScript` references) **materially changes**: selectors/ids or text waits, timing/waits, navigation, setup/seed/helpers, launch/state assumptions, or assertions/outcomes.

The gate fingerprints each in-scope flow as the SHA-256 of the flow file and every file it runs, transitively — helper flows (`runFlow`, `file:`) *and* helper scripts (`runScript`). Any fingerprint change for a blocking-scope flow requires requalification (or an explicit evidence-backed exception for that fingerprint).

The references are read from the flow as YAML, not from its lines. A flow is read as the document it is: `file:`, `runScript:` and `runFlow:` are found wherever they are, with or without a trailing comment (`file: ../../helpers/seed.yaml # shared setup`), in a nested form (`- runFlow:` with a `file:` under it) or inline, and a flow named instead of pathed (`runFlow: SubFlow`) is the flow `SubFlow.yaml` next to it. A reference the scanner misses is a dependency the fingerprint does not cover — the helper changes, the fingerprint does not, and a changed helper enters a blocking scope unrequalified. A document YAML cannot parse falls back to a line scan, so the gate still says what it found.

A flow that reads content the tree cannot cover is not qualified at all. The qualification tree is `Maestro/` — the tree the fingerprint covers and the tree the snapshot copies. A reference by absolute path, a relative path that climbs out of the repository, a path that stays in the repository but outside `Maestro/`, or a path inside `Maestro/` that does not resolve to a file (a missing helper, a directory) points at content that is in neither the fingerprint nor the snapshot the attempts run; the gate reports such a flow as **untracked dependency** and the runner refuses it before the first attempt. Keep every reference inside `Maestro/`, and keep it pointing at a file that exists.

Flows tagged `manual` or `auth-live` are outside the automated blocking suites and are not subject to this gate.

## Blocking-scope admission

Two files carry the admission, and they are kept apart on purpose.

`config/docs/maestro-flow-qualification-initial.yaml` holds the **frozen set**: the flows that were in a blocking scope when this rule was introduced. It is pinned by a digest kept in the gate itself (`FROZEN_SET_DIGEST` in `scripts/check-maestro-flow-qualification.py`), and the gate refuses to run when the artifact does not match that digest. The frozen set is therefore not something a flow can join by being listed: adding a flow to it is a deliberate, reviewed change — a source change that says in the diff that flows are admitted without being qualified.

The frozen set is a **record** and it only grows: a flow that is retired stays in it, because it was in a blocking scope when the rule was introduced. What retires a flow is its absence from the catalogue — the gate walks the catalogue, never the frozen set, and admits a flow out of the catalogue. A name in the frozen set without a file behind it is therefore a record of the past, not an admission, and it qualifies nothing.

"Only grows" is a rule the **review** holds, not a rule the gate enforces. The gate enforces the pin: an artifact that does not match the pinned digest is refused, so the set cannot be edited silently — but a change that removes a name and refreshes the pin is a source change, and the review of that change is where the growth is checked. The gate has no way to know which frozen entries were there before: it reads one artifact and one digest.

`config/docs/maestro-flow-qualification-baseline.yaml` holds the **fingerprints** of the frozen set's flows that are still in the catalogue — a retired flow leaves the map. A flow is admitted when it is in the frozen set *and* its current fingerprint still matches the baseline. A fingerprint that no longer matches is not an admission, it is a change that needs qualification.

**Retiring a flow splits the two artifacts in opposite directions.** The frozen set keeps the name — it is the record of the flows that were in a blocking scope when the rule was introduced, and a name without a file behind it qualifies nothing. The baseline map drops the fingerprint: the map only shrinks, and a flow that comes back to the catalogue has to be qualified rather than admitted by a fingerprint nobody re-ran. The fingerprint is dropped in the same change that removes the flow from the catalogue, and `BASELINE_DIGEST` is refreshed to match the map in that change. The two entries earlier retirements left behind — `nav/02-menu-parity.yaml` (#3119) and `search/03-clear.yaml` (#3142) — were dropped from the map in the change that settled the rule, so every entry left in the map points at a flow that is still in the catalogue. The map is not the whole catalogue: a flow introduced after the rule was introduced has no entry and enters through qualification or an exception. ADR `2026-11-25-a-retired-flow-leaves-the-baseline-map`.

The baseline map is pinned the same way the frozen set is: `BASELINE_DIGEST` in `scripts/check-maestro-flow-qualification.py` holds its digest, and the gate refuses to run when the artifact does not match. An edited fingerprint — a changed flow nobody qualified — is therefore a source change, reviewed as one, and not a data change. Print the current digest with `python3 scripts/check-maestro-flow-qualification.py --print-baseline-digest`.

The separation matters: a baseline that carried its own frozen set could be edited once — a new flow added to both the map and the set — and the gate would read that flow as one that was there when the rule was introduced. `--init-baseline` used to be exactly that way to admit a flow, which is why it now writes the fingerprint map for the frozen set only, refuses to overwrite an existing baseline, and never touches the frozen set or its pin.

A flow that is in the baseline map but not in the frozen set was added to the baseline afterwards, and the gate refuses it as a baseline expansion. After that, a blocking-scope flow is admitted only by:

- a **qualification record** in `config/docs/maestro-flow-qualification.yaml` for the current fingerprint, or
- an explicit, **evidence-backed exception** in the same file for the current fingerprint (reason + evidence/issue reference).

The two are evaluated independently: a qualification recorded for an older fingerprint does not hide a valid exception, and an exception that does not match the current fingerprint does not cancel a valid qualification. When neither holds, the gate reports both reasons.

`scripts/check-maestro-flow-qualification.py` is the blocking gate. It does not rewrite the baseline, the frozen set or the evidence files. `required_attempts` in the evidence file is a policy: a missing value means three attempts, and a value that is not a positive integer is a configuration error the gate refuses rather than silently rounding down to three.

The attempt count also has a **floor**, and the floor lives in the gate, not in the file. `MIN_REQUIRED_ATTEMPTS = 3` in `scripts/check-maestro-flow-qualification.py` is the minimum a policy may ask for: the evidence file may ask for *more* attempts, but a value below the floor is refused. The rule this gate holds is that a flow enters a blocking scope on the strength of a *repeated* run; a policy that asked for one attempt would turn "passes repeatedly" into "passes once" — the exact failure the rule was introduced to prevent. Because the floor is a constant in the gate's source, lowering it is a source change that shows up in a diff and is reviewed as one, not a line somebody edits in an evidence file.

## Qualification run (bounded, affected flows only)

Use `scripts/maestro-qualify-flows.py` to run only the affected flow(s), not the whole catalogue. Default policy: **3 consecutive attempts** of each flow in the same documented environment/build.

```bash
# flows that need admission/qualification
python3 scripts/maestro-qualify-flows.py --admission-needed --list

# qualify specific flows and record when they qualify
python3 scripts/maestro-qualify-flows.py \
  --flow Maestro/flows/tasks/05-archive-via-menu.yaml \
  --attempts 3 --serial <serial> --record

# qualify all unadmitted blocking-scope flows
python3 scripts/maestro-qualify-flows.py --admission-needed --serial <serial> --record
```

Each attempt runs the flow through `python3 -m emulator.maestro` and writes its own result directory under `build/maestro-qualification/<flow-path-without-ext>/attempt-N/` (preserving the flow's repository-relative path), reusing the runner's existing outputs: the flow's JUnit XML (`<flow>.xml`), `environment.json`, and `run-summary.json`. A JSON qualification report can be written with `--report`.

## Evidence and identity

Every attempt is represented in the qualification record/report with:

- attempt number and outcome (`pass`, `test_failure`, `infra_failure`) and reason,
- flow identity (path) and content fingerprint,
- environment identity (`kind`, `api`, `device`, `serial`) from `environment.json`,
- build/commit identity (`commit`, `apk_commit_sha`) and Maestro version,
- the attempt's JUnit report and results directory path.

Environment/build identity must be consistent across the attempts that form a qualification run. The identity is the **device**, not only its model: `kind`, `api`, `device` and `serial` have to agree, so three attempts on three devices of the same model are three runs in three environments and do not form a qualification. An attempt that does not name the device it ran on does not record a documented environment, and is refused rather than admitted. The attempts run the **APK built from this checkout**: the runner refuses an installed APK that is not this checkout's build, and the qualification runner does not accept `--allow-stale-apk` — a qualification that ran another build would certify another program than the one the change is about. The gate holds the same rule from its side: every attempt has to record **both** the checkout it was qualified in (`commit`) and the build that ran on the device (`apk_commit_sha`), and the two have to be the same build. A record that names only one of them, or names two different builds, is refused — the runner-side check is defence in depth, not the rule. Qualification records live in `config/docs/maestro-flow-qualification.yaml` (repo-owned evidence); raw per-attempt artifacts live under `build/` and are preserved as generated run output.

### What the gate proves, and what the record attests

The gate reads the record; it does not re-run the flows. Everything it verifies, it verifies *about the record*: the fingerprint matches the flow in the working tree, the record carries at least `required_attempts` attempts and every one of them is a `pass`, the attempts share one device, one build (the checkout's own), and one engine version that is *recognised* as a version — the same recognition the runner applies, so a record cannot name "Maestro version unavailable" as the engine that ran the flow.

The **currency** of a qualification is the fingerprint, not the application build. The fingerprint covers the flow and the helpers it runs (`Maestro/` is the qualification tree — ADR `2026-11-20-maestro-qualification-tree-boundary`), so a change to the *application* leaves every fingerprint untouched and the record stays admitted. The build identity in the record is the name of the program the attempts certified, not a claim about every later build: the gate refuses a record whose two build values name two different programs, and the qualification runner refuses to write one at all from an APK that is not the checkout's build (ADR `2026-11-20-maestro-qualification-apk-provenance`). An application regression is caught where it belongs — the admitted flow keeps running in the blocking suite (`.github/workflows/e2e.yml`: smoke on every push to `main`, full every night), so a flow an application change breaks is red in the suite rather than silently green. Binding the admission to the current build instead would make every application-code merge invalidate every qualification — the fifty-one flows the baseline admits carry no build identity at all — and would be a rule about merge cadence rather than about the quality of a flow; ADR `2026-11-25-qualification-currency-is-the-flow-not-the-build` records the decision and its cost.

What the gate cannot verify is that the attempts were executed on a device, and that the recorded outcomes match the JUnit reports the attempts produced — the artifacts live under `build/` and are not committed. So the record is an **attestation** the gate checks for consistency and currency, and it is never described as proof that the flow passed. The fact of the run is established by the qualification runner — the only supported way to produce a record — but *established* is the runner's word, not the gate's: the artifacts are not committed, so a reviewer of the pull request cannot re-derive the outcomes either. What review adds is a reading of the record against the diff (this flow changed here, the record's fingerprint matches it, the device and the build are named) and a human's judgement of whether the record is credible. That is a process step, not something the tooling enforces — nothing in the repository makes a reviewer re-run the flow. The boundary is therefore trust in the author of the record, and the gate's job is to make that trust as narrow as it can be: it refuses every record that is internally inconsistent or stale, so the reviewer's remaining question is about the world, not about the file. The runner's own refusal rules (no stale APK, no unrecognised engine version, no untracked dependency, an immutable snapshot) are what make the record worth reading in the first place. The gate is the last line, not the only line.

The attempts do not run the working tree. Before the first attempt the runner copies the `Maestro/` tree into an immutable snapshot under `build/maestro-qualification-snapshot/` and runs every attempt from there, with the layout kept so a helper reference resolves inside the snapshot exactly as it resolves in the tree. The attempts of one qualification therefore always see the same content, whatever happens to the checkout while the run is in progress. A flow that references content outside `Maestro/` is refused before the snapshot is taken: the attempts would not run it either. A copy that fails halfway leaves nothing behind — the half-copied directory is removed, because a directory that looks like a snapshot and holds part of the tree is not something any attempt may read. The snapshot itself is validated as well — the copy is what the attempts read, so a reference that leaves the tree between the check and the copy is caught there, and the flow is refused without spending an attempt.

The **fingerprint** in the record is the one of the snapshot — the content the attempts ran. The fingerprint of the working tree is taken again after the last attempt, and a difference means the tree changed while the flow was being qualified: the record is then not written, because the tree no longer holds the version the attempts ran. A flow edited and restored between two attempts changes nothing for the record — the attempts ran the snapshot, and the tree matches it again.

The **engine version** is part of that identity, and it is read from the engine that runs the attempt (`maestro --version`, or the runner's own version for the `maestro-runner` engine) before the attempt starts — a version read afterwards names a build that nobody ran. The version is taken for the engine the run actually uses: `--engine`, else `MAESTRO_ENGINE`, else the Maestro CLI. The version is a prerequisite, not a note: if it cannot be detected, the runner refuses the run before the first attempt and writes **no** qualification record. A record without the version is not admissible, and spending three device runs per flow to produce a record the gate would reject is device time for nothing. The same holds per attempt: an attempt whose own version probe fails is not run at all, and the run reports it as an `infra_failure`.

A version counts as detected only when it is *recognised* as one. Both engines are read the same way: the engine build is asked for its version, and the answer is recognised as *that engine's* version — from the engine's own banner (`Maestro 1.39.0`, `Maestro version 1.39.0`, `maestro-runner 1.2.3`) or from an output that is a version number and nothing else (`2.10.0`). The rules are whole-line rules: a version that shares its line with other words (`Maestro 1.39.0 version unavailable`) is part of a sentence, not a version, and the banner has to name the engine that was probed, so `maestro-runner 1.2.3` is never read as the CLI's version. A build that exits non-zero has named no version either. Output that names no version names no version: the run refuses instead of recording a version nobody established. This matters because the prerequisite is only worth anything if it cannot be satisfied by any output at all — otherwise "Maestro version unavailable" would be recorded as a version, and the record would name a build that never ran. A number that belongs to something else is not the engine's either: the JVM's version in a warning line above the banner, or a build number in a message about a missing version, is not read as the engine's version. The recognition lives in one place (`emulator.maestro._engine`), so the qualification runner and the runner's own diagnostic logging cannot drift apart.

## Outcomes

- **`pass`**: the flow's JUnit report shows a passing testcase.
- **`test_failure`**: the flow failed its assertions or a test-level failure was reported. An initial failure followed by a later pass means the flow is **flaky**; every attempt remains visible and the flow is **not qualified**.
- **`infra_failure`**: the run did not produce a conclusive test attempt (no/unreadable report, flow not measured, device/app/runner setup failure). Infrastructure failures are **never** counted as successful test attempts and do not qualify a flow.

Only a sequence meeting `required_attempts` with every attempt a `pass` in the same documented environment/build qualifies the flow.

## Exceptions

An exception is an explicit, evidence-backed decision recorded in `config/docs/maestro-flow-qualification.yaml` under `exceptions` for the flow's current fingerprint:

```yaml
exceptions:
  Maestro/flows/example/01-flow.yaml:
    fingerprint: "sha256:..."
    reason: "why the flow cannot be qualified now"
    evidence: "#1234"   # issue, PR, or note with retained evidence
```

Exceptions are pinned to the current fingerprint and must be reissued after material changes. They are for dispositioned cases, not a blanket retry/quarantine, and must not weaken assertions, scopes, or failure floors.

## The gate's own proof

A gate that cannot fail proves nothing, so this gate carries a positive control: the sgate registry declares the one input change that must make it refuse, and the wiring harness applies that change and expects a failure (`scripts/src/sgate/commands/_registry.py`, control `maestro-flow-qualification`).

The control drops the `manual` tag from `Maestro/flows/capture/01-today.yaml`. That flow then leaves the manual scope and enters a blocking automated scope with nothing qualified it and nothing baselined it, and the gate has to refuse it. The control deliberately does **not** tamper with the baseline: the baseline is pinned by a digest and has unit tests of its own, so a control that broke it would only prove that the pin holds — a different property from the one the gate exists to enforce. The control has to exercise the admission rule, and the admission rule is what refuses a flow nobody qualified.

## Constraints honored

- Targets only affected flow(s); never the whole suite by default.
- No blanket retry that turns a failing test green; every attempt stays represented.
- Qualification is separate from the normal test verdict/floor; a retry pass does not erase an earlier failure.
- Reuses the existing Maestro runner (`python3 -m emulator.maestro`) and its JUnit/environment outputs; no device farm, no second result registry.
- Works within local-device/CI constraints: qualification is a deliberate, device-backed step for the affected flows and is enforced at admission by the gate.
