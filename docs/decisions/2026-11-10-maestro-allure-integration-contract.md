---
title: "The Maestro → Allure contract: one identity, one evidence rule, no second execution"
date: 2026-11-10
status: accepted
tags: ["testing", "ci", "allure", "maestro"]
---

# The Maestro → Allure contract: one identity, one evidence rule, no second execution

## Context

The JVM tier already produces Allure: `allure-junit5` writes results, the
`allureReport` task renders them, and `.github/workflows/ci.yml` publishes them
to GitHub Pages through `scripts/ci/allure_pages.py`
(`docs/decisions/2026-11-06-the-allure-report-is-generated-in-the-build.md`,
`docs/decisions/allure-publication-latest-history-and-retention.md`).

The Maestro tier does not appear in any of that. It already produces everything a
report needs — `.github/workflows/e2e.yml` runs `maestro test … --format JUNIT`,
the runner writes `build/maestro-results/<flow>.xml`, the failure evidence
(`<flow>/<flow>_screen.png`, `<flow>/<flow>_logcat.txt`), `environment.json` and
`run-summary.json`; `scripts/ci/check-maestro-results.py` measures the run,
`scripts/test-evidence.py` indexes it, and the artifacts are uploaded. What is
missing is only a **translation** from those artifacts into Allure result files,
and a place to publish them.

Issue #2771 is the contract phase for that translation. It has to be decided
before #2772 (conversion) and #2773 (attachments) can be built, because the
identity, the run metadata and the failure semantics decide what those two
issues may and may not do.

The contract below was written **after** inspecting real artifacts, not from
assumptions: run
[42787254172](https://github.com/gazon1/sing/actions/runs/42787254172) (main,
2026-11-10, head `41c66556`), four nightly shards, 123 testcases, 68 failures.
The sanitised subset lives in `scripts/tests/fixtures/maestro-allure/` and its
`README.md` records the measured shape.

## Decision

### 1. Ownership

- The **existing Maestro workflow** owns execution and its gates.
- `scripts/test-evidence.py` and `scripts/ci/check-maestro-results.py` keep owning
  evidence indexing and the run's verdict. Allure replaces neither.
- A **converter** (`scripts/maestro-allure.py`) owns only translation: JUnit
  reading, identity, labels, result JSON, and the run manifest. It never runs
  Maestro, never renders, never publishes.
- `scripts/ci/allure_pages.py` keeps owning report generation, history and
  publication. No second publisher, no custom history engine.

### 2. Testcase identity

The primary key is the **repo-relative flow path** from
`<testcase file="…">` (measured: Maestro writes it already repo-relative, e.g.
`Maestro/flows/attachments/01-add-url.yaml`).

- `testCaseId = "maestro:" + flow_path`
- `historyId = md5(testCaseId)` (32 hex, the shape Allure's own adapters write)
- `uuid = uuid5(NAMESPACE_URL, testCaseId + "@" + run_id + "." + run_attempt)`
  — converting the same artifact twice is byte-identical, and two shards can be
  merged without rewriting each other's files
- `fullName = flow_path`; `name` = the testcase's `name` attribute (the flow's
  declared `name:`), presentation only
- Labels: `parentSuite=maestro`, `suite=<category directory>`,
  `subSuite=<flow file stem>`, `framework=maestro`, `tag=<each flow tag>`,
  `scope=<smoke|full>`, `shard=<n>`, `retried=true` only for a retried flow

Measured, and the reason the key is the path: `<testcase id>`, `<name>` and
`<classname>` all carry the flow's `name:` field (`attachments-add-url`), which is
not unique across categories and is not a path. The file name stem is the
fallback only when `file` is absent, resolved through
`config/docs/maestro-flows.txt` the same way `check-maestro-results.py` already
does it.

`scenario:<ID>` tags are traceability labels, never the key: a flow may carry
zero or several, and one scenario may be carried by several flows (the desktop
tier carries the other half of `TASK-REC-01`).

Display name, XML order, screenshot filename and fuzzy matching are never
identities.

### 3. Run, shard and attempt identity

Isolation key: **`run_id + run_attempt + commit_sha + scope`**. Each field has
exactly one authoritative source, and the source is recorded because the
artifacts do not all carry every field:

| Field            | Authoritative source                              | In `environment.json`? |
|------------------|---------------------------------------------------|------------------------|
| `run_id`         | `github.run_id`                                    | no                     |
| `run_number`     | `github.run_number` (publication ordering)         | no                     |
| `run_attempt`    | `github.run_attempt`                               | no                     |
| `commit_sha`     | `github.sha`                                       | `commit` is the runner's HEAD — cross-check only |
| `scope`          | the `plan` job's suite output                      | no                     |
| `shard`, `shards`| `environment.json` written by the runner           | yes (current schema)   |
| `device`, `api`  | `environment.json`                                 | yes                    |
| `maestro_version`| pinned `MAESTRO_VERSION` / `maestro --version`     | no                     |
| `apk_sha256`     | SHA-256 of the downloaded `e2e-apk`                | `apk_commit_sha` is a different fact |

Consequences of the table:

- The converter takes the run-level facts as inputs (from the workflow, in
  #2772) and **cross-checks** them against every shard's `environment.json`;
  any disagreement is a hard failure (exit 1, nothing written).
- `maestro_version` and `apk_sha256` are not recorded today; #2772 adds them.
  Until then the manifest records `null` for both and the run is not
  publishable.
- A shard whose `environment.json` carries no `commit` is incomplete: the run
  cannot be tied to a checkout, so it converts but is not publishable.
- **Retries are not representable as Allure retries.** The runner deletes the
  previous attempt's JUnit before retrying, so the XML is the terminal attempt;
  `run-summary.json` is the only record that a retry happened. It is therefore a
  **required input** with four arrays of flow stems (`passed`, `failed`,
  `not_measured`, `retried`); a field that is not an array of stems is a
  blocking finding, not an empty list.
- A summary names a flow by its **stem**, and a stem is not an identity. Each
  stem is resolved through the flow catalogue; a stem that matches no flow, or
  more than one, is a blocking finding and no flow is labelled. No shard-wide
  label, no synthetic attempts, no guessing.
- A shard set merges only when every identity field agrees, and is publishable
  only when the observed shard numbers equal `shards`. Shards are never merged
  across attempts, commits or scopes.

Recorded defect: the `maestro-results` job downloads with
`merge-multiple: true`, so per-shard `environment.json` and `run-summary.json`
overwrite each other. The converter reads **per-shard directories**; the
existing gate's merged path is untouched.

### 4. JUnit XML → Allure mapping

Measured across the four shards: `<testcase status>` is `SUCCESS` or `ERROR`;
every non-`SUCCESS` testcase carries a `<failure>` child; there is **no**
attribute-only outcome. The mapping therefore does not read the status attribute,
and the fixtures pin that shape so a future Maestro change is noticed instead of
being read as a pass.

| Source                          | Allure                                                       |
|---------------------------------|--------------------------------------------------------------|
| `<failure>`                     | `failed`; `statusDetails.message` = first line of the real text, `statusDetails.trace` = the whole real text. **No fabricated stack trace** |
| `<error>`                       | `broken`                                                     |
| `<skipped>`                     | `skipped`                                                    |
| `timestamp` + `time`            | `stop` = the testcase's `timestamp`, `start = stop - time`; report mtime is the fallback when the attribute is absent. A downloaded artifact's mtime is extraction time, not execution time |
| unsupported JUnit outcome       | no result; a blocking finding. Allure statuses are never invented |
| report without a testcase       | no result; a manifest finding                                |
| unreadable XML                  | no result; a manifest finding                                |
| flow of the scope with no report| no invented testcase; `missing[]` in the manifest            |
| retried flow                    | label `retried=true`; never an Allure retry                  |

- One Allure result per `<testcase>`; the suite is the flow, so there is no
  extra nesting to invent.
- `executor.json` is written once per converted set, from `run_id`/`run_attempt`
  (`buildName`) and the workflow URL when present.
- Output is standard Allure 2 JSON (`*-result.json`, `executor.json`). No Allure
  Java APIs, no `allurectl`, no renderer internals.

### 5. Evidence and attachments (implemented in #2773)

- Correlation key: the flow stem → `<results>/<stem>/`, exactly the rule
  `scripts/test-evidence.py::diagnostics_dirs` already uses for Maestro. No
  similarity matching, no file order.
- First-version attachments, on failure only:
  - `<stem>_screen.png`, `image/png`, copied into the results if ≤ 2 MB;
  - the **tail** of `<stem>_logcat.txt`, `text/plain`, ≤ 256 KB.
  Full logcat, videos and the raw screen tree stay in the GitHub artifact.
- Missing or malformed evidence is a manifest finding. It never hides, replaces
  or downgrades the test's own failure, and it does not block publication.
- No second collector: the evidence directory is the same one the runner wrote.
  An `evidence/manifest.json` is used when present, but no artifact in the
  measured run carried one, so it cannot be required.

### 6. Partial runs, duplicates and failure semantics

- A cancelled or failed shard is still converted; the manifest records
  `task_outcome`, `complete_shards=false`, `publishable=false`.
- **A pass never replaces a failure.** If two records share a testcase identity:
  a failing/broken record wins over a passing one; equal outcomes keep the first
  in the order the reports were read. Losing a failure in aggregation is the
  one outcome the contract forbids. **Any** duplicate identity is a blocking
  finding — an aggregation that had to choose is a diagnostic, not valid
  evidence — and a status conflict is reported separately.
- Findings (missing/empty/malformed XML, absent flow, duplicate, missing
  evidence) are warnings: the converter exits 0. It exits 1 only for its own
  errors: unreadable inputs, no results directory, an identity disagreement
  between shards, an output path that is not a directory this tool wrote, or a
  `--now` that is not an instant.
- `publishable=true` requires: at least one result, every identity field
  known, shard set complete, `task_outcome` in `{passed, failed}`, and no
  finding that removes a fact the report is built from — a missing or
  unreadable `environment.json`/`run-summary.json`, a report that cannot be
  read or holds no testcase, an unreadable or absent flow file, a testcase
  whose flow cannot be identified, an unsupported JUnit outcome, an incomplete
  `environment.json`, an invalid summary, an unresolvable summary stem, an
  identity disagreement, or any duplicate testcase identity. A shard set that
  cannot be established — no total, two totals, or a total below one — is a
  finding, so a manifest that is not publishable says why.
- **Exit 0 means converted, never publishable.** Only
  `manifest.publishable == true` permits publication; #2772 wires the publisher
  to that field, not to the exit code.
- The converter is not a gate: it runs with neither `|| true` nor
  `continue-on-error`, it cannot change the Maestro verdict, and a converter
  failure is a failure of its own job.

### 7. Populations and publication (implemented in #2772)

- Maestro gets two independent namespaces, `maestro-smoke` and `maestro-full`.
  They are never mixed with each other or with the JVM `fast`/`full` histories.
- The existing publication contract is reused unchanged: paths
  `<population>/<run_number>/`, per-namespace `last-history`, retention of 20
  reports, ordering by `(run_number, run_attempt)`, idempotency by
  `(run_id, run_attempt)`. The only change is
  `scripts/ci/allure_pages.py::SUPPORTED_POPULATIONS`.
- The publication job lives in `e2e.yml` — the workflow that owns the run — and
  mirrors `publish-allure`: push to main, schedule and manual runs only.
- Artifacts to add in #2772: `maestro-allure-results-<scope>-<run_id>-<run_attempt>`.
  `maestro-junit-*`, `maestro-evidence-*` and `maestro-failures-*` are unchanged.

### 8. Allure 2 / Allure 3

The selected renderer is the pinned CLI **2.46.1** (`gradle/libs.versions.toml`);
the agent version (`2.35.5`) is a JVM concern and stays independent. The adapter
writes Allure 2 result JSON only and depends on no report internals, so a Report 3
migration is a rendering decision with its own proof of concept on these
fixtures — not a change to this contract.

### 9. Rollout

1. Fixtures (done, `scripts/tests/fixtures/maestro-allure/`).
2. Local converter proof of concept on those fixtures (done, see Links).
3. Shadow conversion in `e2e.yml` — produce and upload results, publish nothing;
   compare counts, statuses and evidence with the gate and the index (#2772).
4. Publish the `maestro-smoke` namespace; then enable `maestro-full` (#2772).
5. Attach screenshots and logcat tails (#2773).

## Rationale

**The identity is the flow path because it is the only path-shaped fact in the
XML.** Measured: `id`, `name` and `classname` are the flow's declared name, not a
path, and a declared name is not unique. `file` is already repo-relative and is
the attribute `check-maestro-results.py` and the traceability pipeline already
join on — one identity, three consumers, no mapping table between them.

**Deterministic `uuid`/`historyId` make the converter idempotent.** A re-run of
the job rewrites identical bytes, two shards cannot collide, and history is keyed
by something that does not depend on the order files were read.

**No status-attribute fallback, because the artifacts do not need one.** 123
testcases across four shards, every failure carried a `<failure>` child. Adding a
fallback "just in case" would mean changing the shared reader for a case nobody
has observed, and the fixture-shape assertion is the cheaper early warning.

**Tags come from the flow file, not a second XML parse.** The XML also carries a
`tags` property, but reading it would mean extending the shared reader's result
type for the POC. `scanners/maestro_flow.py` is already the single reader of a
flow's `tags:` block, and the flow file is present in the same checkout the run
used, so the adapter reads tags from there. If the two ever disagree, the flow
file is the source the runner filtered on.

**`run-summary.json` is required, not optional.** It is the only artifact that
distinguishes a retried flow from a first-attempt pass and a not-measured flow
from a missing report. Treating it as metadata would quietly lose both facts.

**The publication contract is extended, not duplicated.** `allure_pages.py`
already implements ordering, idempotency, per-namespace history, pruning and
validation; Maestro only needs two more namespaces. A parallel publisher would
be a second implementation of monotonic latest and retention — the exact class of
drift the Allure publication ADR was written to stop.

**A merging rule that prefers failures is the only safe one.** Duplicate
testcase identities are a defect in the artifact set; the wrong response to that
defect is to let whichever record was read second decide whether the run failed.

## Consequences

- One new tool, one ADR, and a fixture tree. No Maestro flow, tag, selection,
  runner, gate or evidence collector changes.
- The XML shape the contract depends on is pinned by fixtures; a Maestro upgrade
  that changes it fails the fixture-shape test instead of silently reporting
  passes.
- `maestro_version` and `apk_sha256` are missing from today's artifacts; #2772
  adds them, and until then converted runs are not publishable.
- Per-shard `environment.json`/`run-summary.json` collision in the existing
  `merge-multiple: true` download is worked around by reading per-shard
  directories; the existing gate is not changed.
- Allure retries are not reconstructible from artifacts; the contract records
  retries as a label rather than inventing attempts.
- The converter refuses to clean a results directory holding `*-result.json`
  files without a Maestro testcase id, so a mistyped `--out` cannot delete the
  JVM results that publication needs; its own interrupted output stays
  recognisable and recoverable. The directory is cleaned only after the
  conversion succeeds, so a crash leaves the previous run intact. A result file
  damaged before atomic writes (unparseable) is treated as foreign evidence and
  needs one manual deletion; the guard prefers that to deleting somebody else's
  results.
- **Rollback**: delete the converter, its tests, the fixtures and (when they
  exist) the workflow steps. The JUnit XML, the evidence and the gate are
  untouched, so removing the report costs the report and nothing else.

## Links

- `scripts/maestro-allure.py`, `scripts/tests/test_maestro_allure.py`
- `scripts/tests/fixtures/maestro-allure/` — the measured artifact shape
- `scripts/src/traceability/junit_xml.py` — the one XML reader
- `scripts/src/scanners/maestro_flow.py` — the one tag reader
- `scripts/test-evidence.py` — the flow→evidence rule reused here
- `scripts/ci/allure_pages.py` — the publication contract extended by #2772
- `.github/workflows/e2e.yml` — the run this converts
- Decisions: `2026-11-06-one-junit-xml-reader.md`,
  `2026-11-06-the-allure-report-is-generated-in-the-build.md`,
  `2026-11-09-the-evidence-of-a-run-is-indexed-by-one-reporter.md`,
  `allure-publication-latest-history-and-retention.md`
- Issues: #2771 (this contract), #2772 (conversion), #2773 (attachments),
  #2761, #2764, #2766, #2767, #2769 (publication)
