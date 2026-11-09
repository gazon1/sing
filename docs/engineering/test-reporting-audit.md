# Test reporting and diagnostics audit

> **Reference, not a procedure.** Inventory of every producer, artifact and consumer of
> test evidence, taken on 2026-11-06 against commit `a6feb0062`. It goes stale whenever
> the reporting surfaces change, so a change to them updates this file in the same commit.

Scope: the audit issue [#2658](https://github.com/gazon1/sing/issues/2658) and the
sibling epic #2659–#2671. Product behaviour is out of scope; this document changes
nothing at runtime.

The question this document answers is not "how few scripts do we have". It is
**which failures are currently invisible, and which of the custom behaviours can be
replaced by a supported tool**. A behaviour that hides a failure is worse than a
duplicate script, because it is silent.

## Baseline

Measured on the checkout at `a6feb0062`:

| Surface | Result |
|---|---|
| JUnit-XML parsers under `scripts/` | 15 (13 Python + 2 shell) |
| Independent reporters with own failure semantics | 3 (`junit-annotations.py`, `print-test-failures.sh`, `traceability/junit_xml.py`) |
| `FailureBundle` implementations | 3 (desktop, android, shared-JVM extensions) |
| `build/diagnostics/` roots | 4 |
| Test tasks in the build | 11 |
| Tasks with `failOnNoDiscoveredTests` | 5 |
| Test tasks with a single unified `testLogging` block | 0 (one conditional `pro` block) |
| `junit-platform.properties` files | 0 |
| Allure report generation sites | 0 (results are produced and uploaded, report is not) |
| Test-report artifacts uploaded by CI | 9 |

## Audit table

| Producer | Artifact | Consumer | Current limitations | Supported tool can replace it? |
|---|---|---|---|---|
| Gradle test tasks (11) | `<module>/build/test-results/<task>/*.xml` | `check-test-runs.py`, `junit-annotations.py` (CI), `print-test-failures.sh` (hk), `traceability/junit_xml.py`, `fetch-ci-failures.sh` | 15 parsers, 3 divergent failure semantics; `<error>` invisible to CI annotations | No — the shared reader `scripts/src/traceability/junit_xml.py` already exists and is the candidate; `junitparser` was rejected on evidence |
| Gradle test tasks | `<module>/build/reports/tests/<task>/index.html` | humans only; CI uploads only `desktopApp` HTML | never configured, never read by a script, 10 of 11 modules' HTML discarded | Yes — Gradle's own HTML report is enough for humans; stop pretending it is evidence |
| Gradle test tasks | `allure-results/` (`shared`, `desktopApp`, `mcp-server`) | CI upload step only (`ci.yml:1286`) | results are collected but **no report is ever generated**; two different mechanisms used to point Allure at the directory | Yes — `allureGenerate` + `allure-report`; the missing piece is generation, not the results |
| `scripts/junit-annotations.py` | GitHub `::error` / `::notice` annotations | GitHub PR UI | `<error>` testcases counted as clean passes; terminal failure of a retried test dropped; always exits 0 | No — but it should be rewritten on top of the shared reader, not kept as a third parser |
| `scripts/ci/print-test-failures.sh` | console output | `hk.pkl` only (never CI) | parses `failure|error` only, body truncated to 500 chars, swallows parse errors, exits 0 | No — same fix as above, shared reader |
| `scripts/src/traceability/junit_xml.py` | parsed test records | traceability matrix | correct precedence `failure > error > skipped > passed`, keeps `file` attribute; truncates messages at 2000 chars for a consumer (Kiwi TCMS) that no longer exists | Keep — it is the canonical reader |
| `scripts/check-test-runs.py` | exit code + floor report | `hk.pkl:125`, `ci.yml:969-1000` | floors apply only to `--require`d source sets; baseline file format documented as 3-column but is 2-column; the floor file and the measurement file did not say which kind of number each held (see F5) | No — the floor logic is a repository rule and stays custom |
| `config/test-populations.properties` | population names → tag filters | `build.gradle.kts:88-96`, `settings.gradle.kts:151` | uses `population=tags` + `population.property: value`, so Java's `Properties` parser advertises 4 population names, 2 of them phantom | No — but the format must be fixed; see Findings |
| `shared/build.gradle.kts` | `shared/build/diagnostics/<Class>/coroutines.txt` | developers, CI | `AGENTS.md:101,154` documents a repo-root path that does not exist | No — the path is a contract; the doc is wrong |
| desktop `FailureBundle.kt` | `desktopApp/build/diagnostics/<Class>/attempt-N/` | desktop CI (not uploaded) | its artifact name `desktop-diagnostics` matches no real artifact (`ci-evidence-<run_id>`) | No — bundles are the right idea; the naming and the upload step are missing |
| android `FailureBundle.kt` | `androidApp/build/outputs/…/diagnostics/` | `android-failure-diagnostics-*` artifact | separate implementation, separate contract | Partly — a shared bundle contract is feasible; the platform capture is not |
| `FailureContextExtension.kt` / `TestFailureDetailsWriter.kt` | per-test failure files | developers | third implementation of the same concept | Partly — same as above |
| `scripts/step-duration-report.py` | duration report | developer tool, documented in `.agents/skills/singularity-todo-desktop-compose-ui-tests/SKILL.md:282,289` | no *automated* caller; the skill is the caller | Keep — it is a developer tool, not an orphan |
| `build.log` (Gradle log, `ci.yml:922`) | console log | nobody | **not uploaded by any artifact step** — a compile or task failure leaves evidence only in the run UI | No — just upload it |
| `mcp-server/build/diagnostics/**` | directory | CI upload (`ci.yml:1302`) | **no writer exists** in `mcp-server/src` | No — remove the upload or add the writer; do not upload an empty tree |

## Findings

Ordered by severity. Each finding is a defect in the *current* system, not a style
preference.

### F1 — CI reports `<error>` testcases as clean passes

`issubclass(junitparser.Error, junitparser.Failure) == False`: `Error` extends
`FinalResult`, `Failure` extends `FinalResult`, they are siblings.
`scripts/junit-annotations.py:110-114` only checks `isinstance(result, junitparser.Failure)`,
so a testcase whose only outcome is `<error>` (OOM, timeout, crashed fixture, JVM crash)
falls through to `passed += 1`.

Reproduced on a synthetic XML with four testcases (passing / failing / errored /
flaky-then-failed): the script emitted one `::error` and `2 clean pass(es)` — the
errored testcase was counted as a pass.

### F2 — terminal failure of a retried test is dropped

`scripts/junit-annotations.py:100-108` `continue`s on the first `<flakyFailure>` child. A
testcase with `<flakyFailure>` **and** a terminal `<failure>` is reported as "passed on
retry" with `0 failure(s)`, while `print-test-failures.sh` prints the terminal failure.
The two reporters never agree on the same file.

### F3 — the only copy of a traceback is truncated for a consumer that no longer exists

`scripts/src/traceability/junit_xml.py:52-56` sets `MESSAGE_LIMIT = 2000` because the
message was "tied to the downstream Kiwi TEXT column". Kiwi TCMS is retired (the
`git log` entry is literally "refactor: retire Kiwi TCMS stand"); no xmlrpc/TCMS code
remains. The limit now truncates the only remaining copy of the failure text for no
reason. Issue #2662 asks to "not silently truncate the only available copy of a
traceback" — the current code does exactly that.

### F4 — `junitparser` is not a dependency of the project

`scripts/src/pyproject.toml` declares `click`, `loguru`, `rich`, `pyyaml`, `pydantic` —
no `junitparser`. CI installs it ad hoc in
`.github/actions/setup-python-tools/action.yml:66`. Consequence: `scripts/junit-annotations.py`
is **inert outside CI** — locally it prints a warning and exits 0
(`scripts/junit-annotations.py:28-33`).

### F5 — two tracked artifacts about the same floors, and nobody said which is which

`config/docs/test-runs-baseline.txt` (generated block) says `shared:jvmTest 4149 0`,
`shared:archTest 219 0`. `build-investigation/gates-baseline.md:52`, dated the same day
against the same tree `62bc2e012`, says **4306 / 235**.

**Correction (2026-11-06): these two numbers never disagreed.** The first file is the
**floor** — the minimum a run must reach. The second is a **measurement** of one run at
one tree. A measurement above the floor (4306 ≥ 4149, 235 ≥ 219) is exactly what a
healthy run looks like; reading them as two claims about the same quantity is what
made them look contradictory. The defect was not the numbers but that neither file
said which kind of number it held — a reader has to know that one is a threshold and
the other is a sample. The fix is the pointer, not a regeneration.

### F6 — the baseline format is documented as 3 columns and is 2

`scripts/check-test-runs.py:92` documents `<source-set> <classes> <tests> <max-skipped>`;
the file is `<source-set> <classes> <tests>`. `load_baseline` (`:562-597`) tolerates
both, and a legacy comment inside it says the first number of a 3-column line is the
test count — contradicting its own docstring.

### F7 — test tasks are configured inconsistently

`core-domain/build.gradle.kts:49-51` and `detekt-rules/build.gradle.kts:67-68` have a
bare `useJUnitPlatform()`: no `failOnNoDiscoveredTests`, no tag filter, no
`mergeReruns`. `core-domain` nevertheless carries a floor of 5 tests, and its single
test class is `@Tag("fast")`. A test task that silently discovers nothing is exactly
what `failOnNoDiscoveredTests` exists to prevent.

### F8 — there is no unified `testLogging` configuration

Exactly one `testLogging` block exists in the repository
(`pro/build.gradle.kts:241-243`), in a module that only exists with `-PwithPro=true`.
No `exceptionFormat`, no `showStandardStreams`, no `maxStackTraceLines`, no
`shortStackTrace`, and no `junit-platform.properties` anywhere. The only truncation in
the system is `body[:500]` in `print-test-failures.sh` and `MESSAGE_LIMIT = 2000`.

### F9 — the Gradle log is not an artifact

`ci.yml:922` writes `build.log` with `tee`, and no artifact step uploads it. A
compile failure or a failing non-test task leaves evidence only in the CI run UI, and
no script parses `BUILD FAILED` / `Execution failed for task` — `check-test-runs.py:190-215`
only parses `UP-TO-DATE` / `FROM-CACHE` outcomes.

### F10 — a population format that invents phantom populations

`config/test-populations.properties` writes `population=tags` plus
`population.property: value`. Java's `Properties` parser reads `:` as a separator, so
`populations.stringPropertyNames()` — used by the "unknown population" error at
`build.gradle.kts:88-96` and `settings.gradle.kts:151` — reports 4 names where only 2
populations exist. The error message would name a population that does not exist.

### F11 — CI never calls the console reporter

`scripts/ci/print-test-failures.sh` is invoked only from `hk.pkl:77,89,95`. CI uses
`junit-annotations.py` instead, with `continue-on-error: true`
(`ci.yml:947`). The reporter that gets the failure semantics right
(`failure|error`, and the terminal failure of a retried test) is the one that never
runs in CI.

### F12 — `AGENTS.md` documents a diagnostics path that does not exist

`AGENTS.md:101,154` says any failing JVM test writes
`build/diagnostics/<TestClass>/coroutines.txt`. The real path is module-scoped:
`shared/build/diagnostics/<Class>/coroutines.txt` (via the `shared.build.dir` system
property, `shared/build.gradle.kts:1029`), and desktop uses
`desktopApp/build/diagnostics/<Class>/attempt-N/coroutines.txt`.

### F13 — an artifact name that matches no artifact

`androidApp/src/androidTest/.../FailureBundle.kt:19` references the artifact
`desktop-diagnostics`. The real artifact is `ci-evidence-<run_id>` (`ci.yml:1298`).

### F14 — a diagnostics tree that is uploaded but never written

`ci.yml:1302` uploads `mcp-server/build/diagnostics/**`, but no code in
`mcp-server/src/` writes to it (`grep -rn "diagnostics" mcp-server/src/` → empty).

### F15 — a developer tool with no automated caller

`scripts/step-duration-report.py` has no *automated* caller — nothing in `hk.pkl`,
nothing in CI. But it is not an orphan: it is documented as a developer tool in
`.agents/skills/singularity-todo-desktop-compose-ui-tests/SKILL.md:282,289`
("write steps.txt for *passing* tests too — feeds `scripts/step-duration-report.py`"),
and an ADR records its output as the evidence behind the desktop step timeouts
(`docs/decisions/2026-10-04-desktop-test-failure-diagnostics.md:99`).

**Correction (2026-11-06): the original finding said "no callers anywhere" and
recommended deleting the script. That was wrong** — the grep that produced it
looked for an *invocation*, not a *documented consumer*. The script stays. The real
gap is narrower: it aggregates one run and throws the history away, which the same
ADR already identified as the next step (keep a run history, flag p95 creep).

## What the issue itself gets wrong

The audit issue (#2658) and its siblings were written from a partial view of the
system. These premises do not hold:

1. **"Allure is unexplored" (#2660).** Allure results are already produced by three
   modules and already uploaded to CI (`ci.yml:1286-1292`; `allure-junit5` 2.35.5 in
   `shared`, `desktopApp`, `mcp-server`). The only missing thing is **report
   generation**. #2660's PoC should be a `allureGenerate` step, not a "can we collect
   results?" investigation.
2. **`junitparser` as an "existing dependency" (#2658, #2662).** It is not declared in
   `scripts/src/pyproject.toml`; CI installs it ad hoc. Any plan that treats it as
   available is wrong outside CI (F4).
3. **The Kiwi TCMS rationale for the 2000-char truncation (#2662).** Kiwi is retired.
   The truncation now destroys the only copy of the traceback (F3).
4. **"Evaluate whether `junitparser` can replace any parsing code" (#2662).** That
   evaluation already happened and the answer was *no*: `junitparser` does not expose
   the `file` attribute on a testcase, which the traceability matrix needs. The
   rejection is recorded in the docstring of
   `scripts/src/traceability/junit_xml.py:21-43`. Re-running the evaluation is wasted
   work; the real question is how many readers are left.
5. **"Reporting is centralised enough to audit a single pipeline" (#2658).** There are
   15 parsers and 3 reporters with divergent semantics; the two that run in different
   places (CI vs. hk) disagree on the same file (F1, F2).
6. **A parallel "profile" vocabulary (#2663).** `fast`/`full` already exist as
   *populations* in `config/test-populations.properties`. Adding `device`/`e2e`/`all`
   as profiles creates a second selection mechanism with overlapping meaning, and
   "all" is not a profile but the absence of one.

## Plan

Ordered by dependency, smallest change first. Each step is a separate commit that
builds and passes gates on its own.

### P1 — make failure reporting correct (fixes F1, F2, F11)

Rewrite `scripts/junit-annotations.py` to read XML through
`scripts/src/traceability/junit_xml.py` instead of `junitparser`, and add
`junitparser` to `scripts/src/pyproject.toml` only if something still needs it
(probably nothing does — the script can drop the dependency entirely).

Concretely:

- delete the `junitparser` import and the `isinstance(result, junitparser.Failure)`
  check; take the outcome from the shared reader, which already encodes
  `failure > error > skipped > passed`;
- do not `continue` on `<flakyFailure>` — a retried testcase is only "retry-passed" if
  it has **no** terminal failure; when both are present, report the terminal failure;
- keep emitting `::notice` for a genuine retry-pass, but count it separately from
  `clean pass(es)` so the summary line cannot overstate success;
- make the exit code non-zero when any failure was found, or drop the script's
  "always exit 0" contract (`scripts/junit-annotations.py:175`) — the CI step already
  uses `continue-on-error: true`, so the exit code is not load-bearing today; keep the
  behaviour but make it explicit.

Consequence: `scripts/ci/print-test-failures.sh` and `junit-annotations.py` stop
disagreeing. Decide which one survives. Since `print-test-failures.sh` is the one used
locally by `hk.pkl` and gets `failure|error` right, and `junit-annotations.py` is the
one that emits GitHub annotations, neither can be deleted outright — but both should
call the same reader. That is one shared reader, two presentation shells.

Acceptance: a synthetic XML with `<error>` and with `<flakyFailure>` + `<failure>`
produces an `::error` for both, and the pass count never includes them.

### P2 — stop truncating the only copy of the traceback (F3)

Raise or remove `MESSAGE_LIMIT` in `scripts/src/traceability/junit_xml.py:52-56`, and
delete the stale Kiwi rationale. If a bound is still wanted, make it explicit and
configurable, and mark the truncation in the output (never silent).

### P3 — declare `junitparser` or stop using it (F4)

After P1, `junitparser` should have no callers. Verify with
`grep -rn "import junitparser" --include="*.py" .` and then remove the ad-hoc install
from `.github/actions/setup-python-tools/action.yml:66`.

### P4 — one source of truth for the floors (F5, F6)

Pick `config/docs/test-runs-baseline.txt` as the single source and regenerate it;
either correct `build-investigation/gates-baseline.md:52` or mark it explicitly as a
historical snapshot that is *not* the floor. Then fix the format contract: make the
file 3 columns as documented (`<source-set> <classes> <tests> <max-skipped>`) or fix
the docstring at `scripts/check-test-runs.py:92` — pick one and make `load_baseline`
strict about it instead of tolerating both shapes.

### P5 — uniform test-task configuration (F7, F8)

Add a shared convention (a small convention plugin or a `subprojects {}` block in
`build-logic`) that gives every `Test` task the same baseline:
`useJUnitPlatform()`, `failOnNoDiscoveredTests = true`, the population tag filter,
`mergeReruns = true`, and a `testLogging` block with `exceptionFormat = FULL` and a
bounded `maxStackTraceLines`. Apply it to `core-domain` and `detekt-rules` first
(today they are bare), then remove the per-module duplication in `shared`.

Careful: `failOnNoDiscoveredTests = true` on a task that legitimately has no tests in
some configurations fails the build. Verify per module before enabling.

### P6 — publish the evidence that already exists (F3-adjacent, F9, F14)

- upload `build.log` as an artifact next to `ci-evidence-<run_id>`;
- remove the `mcp-server/build/diagnostics/**` upload until a writer exists, or add the
  writer;
- add `allureGenerate` + a published Allure report to CI — this is the whole of #2660;
- decide the fate of the HTML reports: either upload all modules' HTML or drop the
  `html-reports-<run_id>` artifact and rely on the JUnit XML.

### P7 — unify the failure-bundle contract (F12, F13)

Define one bundle contract (directory layout, file names, the coroutine dump) and make
the three implementations conform to it, or explicitly document them as three
platform-specific bundles with distinct names. Fix the `desktop-diagnostics` artifact
name reference and the `AGENTS.md` diagnostics paths in the same commit — those are
documentation fixes and belong with the code that changed them.

### P8 — clean up the leftovers (F10, F14, F15)

- fix `config/test-populations.properties` so it declares exactly 2 populations
  (change the format so the parser cannot invent names, and make the "unknown
  population" error list the real set);
- keep `scripts/step-duration-report.py` — it has a documented consumer in
  `.agents/skills/singularity-todo-desktop-compose-ui-tests/SKILL.md` (see F15). The
  open work is the *history* the ADR already asks for, not the script's existence;
- re-check `scripts/find-unwired-surfaces.py` afterwards, since removing a script is
  exactly the kind of change that gate reports.

### P9 — decision log

Create `docs/decisions/YYYY-MM-DD-single-junit-xml-reader.md` recording: one canonical
reader (`traceability/junit_xml.py`), why `junitparser` was rejected (already in its
docstring), why the 3 reporter shells are presentation-only, and why Allure report
generation is a CI step and not a library. Then
`python3 scripts/refresh-decisions-digest.py`.

### P10 — regression tests

Add tests that pin the two behaviours that were broken:

- a testcase with `<error>` is a failure, not a pass;
- a testcase with `<flakyFailure>` + `<failure>` reports the terminal failure;
- the two reporters agree on the same file;
- `MESSAGE_LIMIT` no longer truncates the message.
These belong next to the shared reader (`scripts/src/traceability/`), per the repo rule
that the canonical reader has one owner.

## Next steps beyond the fix plan

Ordered by value per unit of effort.

### N1 — merge the "profiles" idea into populations (#2663)

Do not create a profile vocabulary. Extend
`config/test-populations.properties` with the two missing selections
(`device`, `e2e`) and keep `fast` / `full` as the two profiles that CI actually uses.
"all" is expressed by *not* filtering. One mechanism, not two.

### N2 — make the floor mechanism a ratchet, not a snapshot

`check-test-runs.py` currently enforces floors per source set. Extend the existing
mechanism so a floor can only move **up** automatically when a run is strictly better
than the recorded one, and only down through an explicit, reviewed waiver. This is
already the shape of the script's ratchet support; the issue series should reuse it
rather than invent a parallel one.

### N3 — a single "what failed here" surface

After P1, the natural product is one command that answers, for any CI run or local
failure: *which tests failed, what was the message, where are the full diagnostics,
and which artifact holds each piece*. Today that answer is spread over five artifact
names and two reporters. A small `scripts/ci/test-failure-index.py` that emits a
markdown table from the JUnit XML + the diagnostics tree would close #2664, #2665,
issues #2666 and #2669 with one file instead of four.

### N4 — make the CI artifact set self-describing

Every uploaded artifact should carry a manifest saying which module, which task and
which population produced it, and which floor it was checked against. The manifest
removes the need to correlate artifact names with jobs by hand (the job that broke
`desktop-diagnostics` in F13 exists because that correlation is manual).

### N5 — close #2662 and #2667 as not-planned, with evidence

Issue #2662 asks to evaluate `junitparser` — done, rejected, documented. Issue #2667 asks for a
conditional collector — the gap it looks for (a central place that sees every failure)
is exactly what the shared reader + the failure index (N3) provides, and that is
cheaper than a new collector. Both should be closed with the evidence, not implemented.

### N6 — the Allure report is the cheapest large win

Results already exist for three modules. Adding `allureGenerate` and publishing the
report gives per-test history, retries and failure grouping for free — the single
highest value-per-line change in the whole series, and it does not require touching
the reporters at all.

## What stays custom, and why

- **The floors and the ratchet.** No library enforces a repository-specific floor per
  source set. Stays custom (`check-test-runs.py`).
- **The population/tag selection.** Repository-specific meaning for `fast` / `full`.
  Stays custom, but one mechanism, not two (N1).
- **The runnable-test scanner.** `scripts/src/scanners/test_classes.py` and its
  contract fixture `config/test-fixtures/runnable-test-members.txt` answer "is this a
  runnable test?" — a question Gradle does not answer the same way. Stays custom.
- **The failure bundles.** Platform capture is genuinely platform-specific; the
  *contract* is not (P7).
- **Presentation shells.** `::error` annotations and console summaries are two
  renderings of one parse; the parse is shared, the renderings are not.
- **XML parsing.** One reader, `scripts/src/traceability/junit_xml.py`, with the
  `failure > error > skipped > passed` precedence and the `file` attribute preserved.
  The other 14 parsers should not exist; P1 removes the two that are actively wrong.

## Not covered

- The Android instrumentation and Maestro diagnostics were mapped but not audited to
  the same depth (#2665, #2666) — they need a separate pass on the emulator/host side.
- Performance: no measurement of how long the reporters take on a full CI run, so the
  cost of consolidating them is estimated, not measured.
