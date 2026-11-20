# Maestro flow qualification

A new Maestro flow, or a materially changed one, must not enter a blocking automated scope on the strength of a single run. The qualification policy is the evidence that the flow is repeatable in the same documented environment.

## What requires qualification

Qualification is required when a flow can affect a blocking automated scope and either:

- the flow is **new** (in `Maestro/flows/`, listed in `config/docs/maestro-flows.txt`, and not admitted yet), or
- the flow or one of the helpers it runs (transitively, via `runFlow` / `file:` references) **materially changes**: selectors/ids or text waits, timing/waits, navigation, setup/seed/helpers, launch/state assumptions, or assertions/outcomes.

The gate fingerprints each in-scope flow as the SHA-256 of the flow file and every helper it runs, transitively (`Maestro/**/*.yaml`). Any fingerprint change for a blocking-scope flow requires requalification (or an explicit evidence-backed exception for that fingerprint).

Flows tagged `manual` or `auth-live` are outside the automated blocking suites and are not subject to this gate.

## Blocking-scope admission

Two files carry the admission, and they are kept apart on purpose.

`config/docs/maestro-flow-qualification-initial.yaml` holds the **frozen set**: the flows that were in a blocking scope when this rule was introduced. It is pinned by a digest kept in the gate itself (`FROZEN_SET_DIGEST` in `scripts/check-maestro-flow-qualification.py`), and the gate refuses to run when the artifact does not match that digest. The frozen set is therefore not something a flow can join by being listed: adding a flow to it is a deliberate, reviewed change — a source change that says in the diff that flows are admitted without being qualified.

`config/docs/maestro-flow-qualification-baseline.yaml` holds the **fingerprints** of the frozen set's flows. A flow is admitted when it is in the frozen set *and* its current fingerprint still matches the baseline. A fingerprint that no longer matches is not an admission, it is a change that needs qualification.

The separation matters: a baseline that carried its own frozen set could be edited once — a new flow added to both the map and the set — and the gate would read that flow as one that was there when the rule was introduced. `--init-baseline` used to be exactly that way to admit a flow, which is why it now writes the fingerprint map for the frozen set only, refuses to overwrite an existing baseline, and never touches the frozen set or its pin.

A flow that is in the baseline map but not in the frozen set was added to the baseline afterwards, and the gate refuses it as a baseline expansion. After that, a blocking-scope flow is admitted only by:

- a **qualification record** in `config/docs/maestro-flow-qualification.yaml` for the current fingerprint, or
- an explicit, **evidence-backed exception** in the same file for the current fingerprint (reason + evidence/issue reference).

`scripts/check-maestro-flow-qualification.py` is the blocking gate. It does not rewrite the baseline, the frozen set or the evidence files. `required_attempts` in the evidence file is a policy: a missing value means three attempts, and a value that is not a positive integer is a configuration error the gate refuses rather than silently rounding down to three.

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
- environment identity (`kind`, `api`, `device`, `serial` when available) from `environment.json`,
- build/commit identity (`commit`, `apk_commit_sha`) and Maestro version,
- the attempt's JUnit report and results directory path.

Environment/build identity must be consistent across the attempts that form a qualification run. Qualification records live in `config/docs/maestro-flow-qualification.yaml` (repo-owned evidence); raw per-attempt artifacts live under `build/` and are preserved as generated run output.

The **engine version** is part of that identity, and it is read from the engine that runs the attempt (`maestro --version`, or the runner's own version for the `maestro-runner` engine) before the attempt starts — a version read afterwards names a build that nobody ran. The version is taken for the engine the run actually uses: `--engine`, else `MAESTRO_ENGINE`, else the Maestro CLI. If the version cannot be detected, the runner says so before it spends the attempts and writes **no** qualification record: a record without the version is not admissible, and a record the gate would reject is worse than no record.

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

## Constraints honored

- Targets only affected flow(s); never the whole suite by default.
- No blanket retry that turns a failing test green; every attempt stays represented.
- Qualification is separate from the normal test verdict/floor; a retry pass does not erase an earlier failure.
- Reuses the existing Maestro runner (`python3 -m emulator.maestro`) and its JUnit/environment outputs; no device farm, no second result registry.
- Works within local-device/CI constraints: qualification is a deliberate, device-backed step for the affected flows and is enforced at admission by the gate.
