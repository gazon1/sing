# CI restructure — a fast signal on the PR path and coverage off it

## Request

The plan is `/home/max/.minimax/v2/sessions/2026/10/23/16-30-34-563-session_bXZzX2Y2ZWJmYTg4ZWExNDQxNGE5ZGJhZDM2YjM4ZTlmNjIz/artifacts/plan.md`
(440 lines, Russian). It proposes restructuring `.github/workflows/ci.yml` so that a pull
request gets a fast merge signal and the expensive coverage work moves off the PR path:

- **D1 (confirmed in the plan):** take Kover off the PR path; add a `coverage` job; raise
  the `FULL_*` floors to the `FAST_*` level.
- **D2 (confirmed in the plan):** detekt becomes its own job with one run instead of several.
- **D3 (open in the plan):** when the "the gates can fail" control harness runs.
- **D4 (open in the plan):** whether detekt stays in the local pre-push hook.
- **D5 (open in the plan):** which test population lives on the PR path.

This investigation establishes **what the code does today**. It does not decide D3/D4/D5, and
it does not write a cause for anything — that belongs in the spec.

Change slug: `ci-fast-signal-and-coverage-off-the-pr-path`
(`openspec/changes/ci-fast-signal-and-coverage-off-the-pr-path/`).

## What the code actually does

### The workflow today

`.github/workflows/ci.yml` declares ten jobs: `changes` (:39), `static` (:82, display name
"Static gates"), `meta` (:237, display name "Gate tooling"), `tests` (:273, display name "JVM
tests, detekt, coverage"), `android` (:968), `agent-review` (:1064), `ci-gate` (:1111),
`adr-gate` (:1135), `spec-create` (:1207), `nightly-alert` (:1285).

Triggers (`.github/workflows/ci.yml:12-27`): `push` to `main`; `pull_request` to `main` with
`types: [opened, synchronize, reopened, labeled]`; `workflow_dispatch`; a nightly schedule at
`01:00 UTC`. Concurrency is `ci-<pr number || ref>` with `cancel-in-progress: true`
(`.github/workflows/ci.yml:29-30`).

`ci-gate` is the job branch protection would require. It needs
`[static, meta, tests, android, changes, agent-review]`
(`.github/workflows/ci.yml:1114`) and accepts `success` or `skipped` for every one of them
(`.github/workflows/ci.yml:1125-1130`). There is no `quick` job and no `coverage` job today.

### How the population is chosen

`ci.yml:357-374` ("Select test population") writes `TEST_POPULATION` into `$GITHUB_ENV`:
`full` for `schedule` and `workflow_dispatch`; `full` when the label list contains
`run-full-tests`; `fast` otherwise. `ci.yml:388-394` ("Test population is one of the known
ones") rejects a name that `config/test-populations.properties` does not declare.

**The `run-full-tests` label escape hatch already exists** — `ci.yml:370`, documented in
`docs/decisions/2026-10-22-ci-fast-only-pr-full-on-schedule.md:52` and
`docs/decisions/2026-10-22-ci-fast-only-pr-full-on-schedule.md:201` ("The label
`run-full-tests` was created in the repository"). The plan's D5 presents the label as a
possible new mechanism; it is not new.

`config/test-populations.properties` declares `fast=fast` and `full=fast,slow`, plus the
properties `fast.includes-slow: false` and `full.includes-slow: true`.

`settings.gradle.kts:107-124` resolves the selection: a non-empty `-Ptest.population` wins
and is looked up in the properties file (an unknown name is a `require` failure at
`settings.gradle.kts:115-119`); otherwise `-Ptest.tags` is used raw.
`settings.gradle.kts:124` computes `measuredFullPopulation = selectedTags.any { it == "slow" }`
— the floor set is chosen by the **resolved tag list**, not by the population's name.

`build.gradle.kts:77-85` throws `GradleException` when `-Ptest.population` and `-Ptest.tags`
are given together ("One selection, one spelling: name a population, or spell the tags").

### How the test tasks get into the Gradle graph — the load-bearing fact for D1

The `tests` job runs one Gradle invocation (`ci.yml:520-537`):

```
./gradlew --continue --no-parallel \
  :mcp-server:jar :shared:verifyToolSurface \
  koverReport koverHtml \
  :detekt-rules:test :shared:verifyToolSurface \
  :shared:detekt :desktopApp:detekt :androidApp:detekt :mcp-server:detekt \
  :adr-cli:detekt :adr-core:detekt :core-base:detekt :detekt-rules:detekt \
  -Ptest.population="$TEST_POPULATION" ...
```

That invocation names **no test task except `:detekt-rules:test`**. Every other test task
reaches the graph through `koverReport`, whose `dependsOn` list is
`:shared:jvmTest`, `:shared:testAndroidHostTest`, `:desktopApp:test`, `:mcp-server:test`,
`:androidApp:test`, `:adr-core:jvmTest`, `:detekt-rules:test`, `:koverXmlReport`
(`build.gradle.kts:390-399`). `build.gradle.kts:471-472` states it in words: *"Depending on
`koverReport` is what puts the test tasks in the graph; `:koverHtmlReport` then reuses their
execution data."* `ci.yml:428-432` says the same: *"Naming koverReport is the whole entry
point."*

`koverReport` therefore does two jobs at once: it writes the coverage report **and** it is
the only thing that puts the test suite into the job's task graph.

### Coverage

`:koverVerify` is the blocking coverage gate (`ci.yml:841-842`,
`./gradlew :koverVerify -Ptest.population="$TEST_POPULATION"`), and it depends on
`koverReport` (`build.gradle.kts:423`). The advisory renderers are `ci.yml:847-849`
(`./gw :koverMarkdownTable …`, `|| true`) and `ci.yml:858-878` (`./gradlew -q :koverLog …`,
`|| true`). A separate blocking floor for the custom rules lives at `ci.yml:887-888`
(`:detekt-rules:koverVerify`), and `ci.yml:884-886` notes that `./check.sh` names the same
task, or `scripts/check-gate-wiring.py` Part I would fail.

The floors are declared in `settings.gradle.kts` — `FAST_*` at `settings.gradle.kts:179-181`
(27 / 23 / 31) and `FULL_*` at `settings.gradle.kts:156-158` (27 / 20 / 27) — with
`require` blocks at `settings.gradle.kts:183-203`. The settings file is the declaration site
because the root project has no Kover extension of its own
(`docs/decisions/2026-10-23-a-population-is-a-name.md`, D4).

`scripts/kover-markdown-table.py` supports `--summary` and reads only
`build/reports/kover/report.xml` — it never invokes Gradle.

The instrumented cost is published in `config/docs/coverage-cost.properties`
(`fast.ms=2245000`, `full.ms=2003000`) and the timeout is checked against it by
`scripts/check-coverage-timeout.py`, whose rule is that the job running `:koverVerify`
declares a `timeout-minutes` covering the **larger** of the two measured populations with a
2× margin. `tests` declares `timeout-minutes: 75` (`ci.yml:288`).

`scripts/check-coverage-timeout.py` reads **only** `.github/workflows/ci.yml`
(`check-coverage-timeout.py:127`, hardcoded) and discovers the coverage job as *the first
job in file order whose step text matches* `(?<![\w:.\-]):koverVerify(?![\w\-])`
(`:142`, `:275-310`). `:detekt-rules:koverVerify` is excluded by the lookbehind (`:141-142`).
The required timeout is `ceil(measured × 2 / 60_000)` with `measured` the larger of the two
populations (`:136`).

### Detekt

Detekt is named in three places on the PR path:

1. `ci.yml:208-211` — the `static` job's "Detekt auto-correct (commit + push)":
   `bash scripts/ci/detekt-auto-fix.sh "static" "${{ github.run_id }}"`.
2. `ci.yml:423-426` — the `tests` job's step of the same name, with `"tests"` as the job
   argument.
3. `ci.yml:531` — eight detekt tasks inside the `tests` job's Gradle invocation.

`scripts/ci/detekt-auto-fix.sh:33-38` runs those same eight modules with `--auto-correct
--no-build-cache --console=plain`; `:45-48` exits 0 without pushing when `git diff --quiet`
reports no change; the branch is `detekt-auto-fix/<job>/<run-id>` (`:20`).

`config/hooks/pre-push.json:13-22` records the eight modules CI judges
(`:shared:detekt`, `:desktopApp:detekt`, `:androidApp:detekt`, `:mcp-server:detekt`,
`:adr-cli:detekt`, `:adr-core:detekt`, `:core-base:detekt`, `:detekt-rules:detekt`), and
`:26` records `:pro:detekt` separately. The same eight appear at `ci.yml:531` and at
`scripts/ci/detekt-auto-fix.sh:33-34`.

### The gate registry, the static job and the control phase

`scripts/src/sgate/commands/_registry.py` holds 71 gates. Their *placements* — 54
`static`, 9 `cluster`, 8 `elsewhere` — live in
`scripts/src/sgate/gate_manifest.py:967-980`.
`scripts/ci/static-gates.sh` is generated between the markers at
`scripts/ci/static-gates.sh:223` and `:781`; the head (`:1-222`) and tail (`:782-793`) are
hand-written. `scripts/src/sgate/commands/run_all.py:161-183` reads that file to learn the
gates, runs them in a `ThreadPoolExecutor` (`run_all.py:553-560`), and — only when
`--with-controls` is given — appends a **separate** phase at `run_all.py:562-564` that runs
`python3 scripts/check-gate-wiring.py --can-fail` alone, with a 1200-second deadline
(`run_all.py:91`, `:440`). The phase is separate because it edits the tree the gates read
(`run_all.py:435-437`, `ci.yml:180-186`).

The `static` job runs exactly that: `ci.yml:187-188`,
`sgate run-all --with-controls || exit 1`.

The `tests` job runs the other half: `ci.yml:909-913`,
`python3 scripts/check-gate-wiring.py --except-can-fail`, with `GATE_WIRING_GRADLE=1`. The
comment at `ci.yml:904-908` states the split explicitly: *"the fast controls are proved by
the static job (`sgate run-all --with-controls`) … This job proves the rest."* The
`Gate tooling` job runs a third pass, `ci.yml:270` "Gates are alive".

### The gate scripts are string-sensitive, not structure-sensitive

Nothing under `scripts/` reads a job's `needs:` list — the job graph is not gated. What the
gates assert are **exact strings**: step names, Gradle task spellings, YAML indentation and
file paths. Four registry controls mutate `.github/workflows/ci.yml` by literal text with
`count=1` (first occurrence):

| control | `old` text | sabotage |
|---|---|---|
| `workflow-exit-status` | `set -o pipefail` + `./gradlew --continue \` (10-space indent) | drops the pipefail |
| `build-output-readers` | `      - name: Coverage floors` + `        run: ./gradlew :koverVerify -Ptest.population="$TEST_POPULATION"` | appends a reader step |
| `gradle-test-population` | `run: ./gradlew :koverVerify -Ptest.population="$TEST_POPULATION"` | drops `-Ptest.population` |
| `coverage-timeout` | `    timeout-minutes: 75` | shrinks it to 30 |

All four are declared in `scripts/src/sgate/commands/_registry.py` (the `workflow-exit-status`
entry at `:1213-1216`, `build-output-readers` at `:1217`, `gradle-test-population` at
`:1218`, `coverage-timeout` alongside it). Each control must still *hit* the intended line
after the restructure; a control that sabotages the wrong line proves nothing, and the
`--can-fail` phase fails.

`scripts/check-gate-wiring.py`'s Part I compares `check.sh` with the workflow through
`GRADLE_TASK_PARITY`, a table the registry owns (`_registry.py:190-269`, consumed at
`check-gate-wiring.py:444-447`). Its values are `covered` | `ci` | `local` | `both`.
**`:shared:jvmTest` and `:desktopApp:test` are `covered`** — CI reaches them *through*
`koverReport` (`check-gate-wiring.py:2285-2289`) — while `:shared:testAndroidHostTest`,
`:shared:verifyToolSurface`, `:pro:detekt` and the compile tasks are `ci` or `both`.
`koverReport` and `koverHtml` are not table entries: the task regex
`:[a-zA-Z][\w-]*:[a-zA-Z][\w-]*` (`check-gate-wiring.py:2291`) matches only `:module:task`.

Part E's **CI surface is exactly `.github/workflows/ci.yml` plus
`scripts/ci/static-gates.sh`** (`check-gate-wiring.py:1500-1520`). `GATE_ARG_PARITY` has
three entries (`check-gate-wiring.py:1424`): `check-doc-dead-refs.py --skill-symbols` is
`both`; `check-test-runs.py --require shared:testAndroidHostTest` and
`--require mcp-server:test` are `ci`. The invocation
`check-test-runs.py --require shared:jvmTest,desktopApp:test` (`ci.yml:583`) is **not** in
the table, so it must appear on both surfaces.

Step names are load-bearing anchors: `tracked_by=".github/workflows/ci.yml#<step name>"`
is resolved against the step `name:` values by `scripts/check-waiver-references.py`.
Renaming a step breaks the anchor; moving it to another job in the same file does not.
The anchors into `ci.yml` are `#Executed test counts`, `#Gradle (tests + coverage + detekt)`,
`#Kover verify (detekt-rules)`, `#Normalise desktop results into the scenario matrix`,
`#Post-rebase DI compile`, `#Slow tests ran`, `#The review the agent left on the pull
request`, `#Late flake pass (advisory)`, `#Gates are alive`, `#Gate scripts' own unit tests`.

By contrast, `scripts/check-gradle-test-population.py` and
`scripts/check-build-output-readers.py` read **every** workflow file and discover jobs
dynamically (`scripts/src/workflow_gradle.py:324-343`); neither hardcodes a job, step or task
name. `scripts/check-workflow-exit-status.py` globs all workflows and requires
`set -o pipefail` in every piped `run:` block. `scripts/check-hook-gates.py` hardcodes
`.github/workflows/ci.yml` and derives the CI detekt module set by regex over the raw file
text, comments included (`check-hook-gates.py:69`, `:109-113`).

### A prior attempt at this split exists and was withdrawn

`ci.yml:213-232` records it: a six-leaf split of the `tests` job *"made a green run that
measured the wrong thing, and it was withdrawn for that reason — see
docs/decisions/2026-10-05-ci-checks-run-in-parallel.md"*. The three couplings it names are
the run stamp, the count and coverage floors, and the flake comparison — all of which read
results the same job produced.

### The pre-push hook

`.githooks/pre-push` runs the structural gates against a throwaway worktree of the pushed
sha (`:196-207`), then delegates the detekt decision to `scripts/pre-push-verdict.py`
(`:637-648`). The hook itself names no detekt module — `scripts/check-hook-gates.py:199-203`
fails the build if it does, because the module set is computed from the module graph in
`scripts/changed-modules.py`, not written down.
`scripts/check-hook-gates.py:179-190` cross-checks `config/hooks/pre-push.json` against the
CI workflow in **both** directions, and `:169-170` asserts that the hook still calls the
delegate.

### The impact map

`scripts/test-impact-map.py` maps changed files to test classes. Its CLI is `--base`
(default `origin/main`, `:308`), `--explain`, `--command`, `--run` (`:306-316`).

- The changed set is `git diff --name-only {base}...HEAD` **plus**
  `git status --porcelain` (`:145-176`).
- A failing `git diff` is **silently swallowed** at `:157` (`if committed.returncode == 0:`);
  the function then continues with only the working-tree paths.
- `GATE_AREAS` (`:68-75`) — `scripts/`, `.github/`, `docs/`, `config/`, `.specify/`,
  `openspec/` — selects **no test class at all** (`:253-255`).
- The emitted Gradle command is `./gradlew <task> -Ptest.tags=fast,slow --tests <Class>`
  (`:299-301`), with `TAGS = 'fast,slow'` hardcoded at `:87`. It invokes `./gradlew`, not
  the repository's `./gw`.
- An empty map prints `nothing to run: the change claims no test class.` to stderr and
  **exits 0** (`:341-343`).

The module-level sibling is `scripts/changed-modules.py`, which computes Gradle *tasks*
rather than test classes and has the same silent swallow at `:130`.
`scripts/lint-changed.py:100-102` is the precedent for failing loudly instead.

## Reproduction

All measurements come from the GitHub API; no file outside this change directory was
modified.

**1. Baseline runner-minutes per pull request.**

```bash
gh api repos/gazon1/sing/actions/runs/39981868734/jobs
```

Run `39981868734` (2026-10-23, branch `feature/the-external-agent-is-hosted`, a code PR):

| job | wall time | conclusion |
|---|---|---|
| JVM tests, detekt, coverage | 29.4 min | **failure** |
| Static gates | 10.5 min | success |
| Gate tooling | 5.2 min | success |
| Android (pro) | 6.5 min | success |
| Android (free) | 3.6 min | success |
| adr-gate / spec-create / changes | 1.6 / 1.9 / 0.1 min | success |
| CI gate | 0.0 min | failure |
| **total runner time** | **58.9 runner-minutes** | |

Run `39982183903` (2026-10-23, branch `docs/the-documents-match-the-world`, a docs PR):
**37.3 runner-minutes** total; `JVM tests, detekt, coverage` 17.2 min, `Static gates`
8.3 min, `Gate tooling` 5.0 min, `Android (pro)` 1.3 min, `Android (free)` 1.2 min.

**2. Step-level durations (the plan's table, checked).**

```bash
gh api repos/gazon1/sing/actions/runs/39981868734/jobs --jq '.jobs[].steps[]|"…\(.started_at) \(.completed_at) \(.name)"'
```

Code PR:

| step | measured |
|---|---|
| Gradle (tests + coverage + detekt) | 1376 s — "BUILD FAILED in 22m 55s" |
| Detekt auto-correct (commit + push) — `tests` | 231 s |
| Detekt auto-correct (commit + push) — `static` | 196 s |
| Script gates (sgate run-all) — `static` | 400 s |
| Gates are alive — `meta` | 147 s |
| Gate scripts' own unit tests — `meta` | 148 s |
| Coverage table (advisory, CI summary) | 89 s |
| Coverage floors | skipped (the Gradle step failed) |

Docs PR: Gradle step 756 s ("BUILD SUCCESSFUL in 12m 36s"), `Detekt auto-correct` 123 s,
`Script gates` 289 s, `Gates are alive` 143 s, `Coverage floors` 40 s, `Coverage table` 18 s.

**3. The Gradle task split inside the Gradle step.**

```bash
gh api repos/gazon1/sing/actions/jobs/120511020566/logs --allow-escape-sequences > job.log
grep -oE '^2026-[0-9T:.Z-]+ > Task :[a-z-]+:[a-zA-Z]+' job.log
```

Code PR: `:shared:jvmTest` occupies 16:01:36 → 16:18:56 = **1040 s (17 min 20 s)**, i.e.
75.6 % of the 1376 s Gradle step. Docs PR: 15:59:02 → 16:10:31 = **689 s (11 min 29 s)**.
The code PR's Gradle step ended with `> Task :mcp-server:compileTestKotlin FAILED` at
16:00:07 and `BUILD FAILED in 22m 55s` at 16:19:06 — the run is a **failed** one, and the
failure is a compile error, not a flake.

**4. The impact map with a missing base ref.**

```bash
$ python3 scripts/test-impact-map.py --base origin/does-not-exist --command
nothing to run: the change claims no test class.
$ echo $?
0
```

Exit code 0, no warning that the base ref was missing. The plan's Step 4 requires the
opposite behaviour ("(а) реф базы отсутствует → шаг падает с внятным сообщением"); the tool
today produces a silent vacuous green instead.

**5. The population/tags conflict the `quick` job would hit.**

`scripts/test-impact-map.py:87` hardcodes `TAGS = 'fast,slow'` and `:299` puts
`-Ptest.tags=fast,slow` into the emitted command. `build.gradle.kts:80-85` throws when
`-Ptest.population` is given alongside `-Ptest.tags`. The two cannot be combined in one
invocation, and the tags alone select the **full** population
(`config/test-populations.properties`: `full=fast,slow`; `settings.gradle.kts:124`).

## Verified facts

### Measurements

1. A pull request costs **58.9 runner-minutes** on a code change (run `39981868734`) and
   **37.3 runner-minutes** on a docs change (run `39982183903`). The plan's "~53" is below
   the measured code-PR baseline; the difference is the Android legs (6.5 + 3.6 min measured
   here, against "~1 min" assumed in the plan).
2. `:shared:jvmTest` under the Kover agent occupies **1040 s** (run `39981868734`) and
   **689 s** (run `39982183903`) of the Gradle step. The plan's "17.3 мин (76 %)" and "11.5 мин" reproduce
   exactly.
3. The plan's "Gradle-шаг 22.9 мин", "static (auto-fix, 196 с)", "tests (auto-fix, 231 с)",
   "`sgate run-all --with-controls` = 400 с", "`Gates are alive` (meta, 147 с)" and
   "Coverage table … 89 с" all reproduce exactly against run `39981868734`.
4. Run `39981868734`'s `tests` job **failed**, on `:mcp-server:compileTestKotlin`
   (16:00:07, job log `120511020566`). The
   22m56s Gradle baseline is therefore a failed run; the suite itself ran to completion.
5. Run `39981868734`'s Gradle step reported `0 failure(s), 0 retry-pass(es), 6168 clean
   pass(es)`. **No
   retry happened in this run**, so the 1040 s contains no retry overhead (the plan's risk
   #3 is unmeasured, not confirmed).
6. `sgate run-all --with-controls` costs **400 s** (run `39981868734`) and **289 s** (run
   `39982183903`) — the same command, a 111 s spread. There is no single "control harness = 6 min"
   number; the control phase is the *second* phase of the same command
   (`run_all.py:562-564`) and its cost is not separately published.
7. `docs/testing/gate-costs.md` records `static gates` at 38.3 s — a **local** measurement of
   the same step **without** `--with-controls`, on a 16-vCPU host. The plan's comparison
   ("400 с с `--with-controls` против 38.3 с без него") puts a CI-runner number and a local
   number side by side.
8. The `FULL_*` floors are 27/20/27 (`settings.gradle.kts:156-158`) and the `FAST_*` floors
   are 27/23/31 (`settings.gradle.kts:179-181`). The plan's D1 target (raise `FULL_*` to
   27/23/31) is consistent with the measured full values 55.88/40.44/57.23.
   `settings.gradle.kts:144-148` and
   `docs/decisions/2026-10-23-a-population-is-a-name.md` (Consequences) record that the
   gap was left open **deliberately**.

### Wiring

9. The `tests` job's Gradle invocation reaches six test tasks only through `koverReport`
   (`build.gradle.kts:390-399`, `build.gradle.kts:471-472`, `ci.yml:428-432`).
   **Deleting `koverReport`/`koverHtml` from `ci.yml:527-528` without naming those test
   tasks leaves the `tests` job with no tests to run.** The plan's Step 3 and its
   acceptance criterion #1 do not mention naming them.
10. `:adr-core:jvmTest` is inside `koverReport`'s list (`build.gradle.kts:396`), so it is in
    the `tests` job's graph today. `ci.yml:513-518` argues the ADR modules are kept out of
    the coverage aggregate by *not naming them in the invocation* — which the
    `koverReport` dependency contradicts.
11. The `run-full-tests` label already selects the `full` population (`ci.yml:370`); it is
    documented at `docs/decisions/2026-10-22-ci-fast-only-pr-full-on-schedule.md:52`.
12. `--with-controls` is one extra phase of `sgate run-all`, and it runs
    `scripts/check-gate-wiring.py --can-fail` (`run_all.py:440`, `:562-564`).
    `ci.yml:904-908` states that the `static` job proves the fast controls and the `tests`
    job proves the rest via `--except-can-fail` (`ci.yml:909-913`).
13. `scripts/check-ci-workflow-shape.py` is a **cluster of three** checks
    (`check-test-task-inputs.py`, `check-gradle-test-population.py`,
    `check-build-output-readers.py`), as the plan states.
14. `scripts/check-gradle-test-population.py`'s rule is one test population per job; its
    docstring records the 2026-10-16 incident where the advisory `:koverMarkdownTable`
    re-ran `:desktopApp:test` **without** `-Ptest.tags`.
15. `scripts/check-build-output-readers.py`'s rule is positional: a step that reads a
    build-output path must precede every Gradle invocation in its job that can run tests.
16. `scripts/check-coverage-timeout.py` requires the job that runs `:koverVerify` to declare
    a timeout covering the **larger** of the two measured populations × 2. It reads **only**
    `.github/workflows/ci.yml` (`:127`, hardcoded) and discovers that job as *the first in
    file order* whose step text matches `:koverVerify` (`:142`, `:275-310`);
    `:detekt-rules:koverVerify` is excluded by the lookbehind (`:141-142`).
17. `scripts/check-coverage-task-list.py` requires every project the coverage job **builds**
    to be either in the `koverReport` list or excluded by `skipProjects(...)` in
    `settings.gradle.kts`. `:adr-cli` is excluded by `skipProjects(":adr-cli")`
    (`docs/decisions/2026-10-23-a-population-is-a-name.md`, D3).
18. `config/hooks/pre-push.json:13-22` records exactly the eight detekt modules the plan
    names, in the same order as `ci.yml:531` and `scripts/ci/detekt-auto-fix.sh:33-34`.
19. `scripts/check-hook-gates.py` asserts the record and the CI workflow agree **in both
    directions** (`:179-190`), that the hook calls the delegate (`:169-170`), and that the
    hook names no detekt module itself (`:199-203`).
20. `scripts/test-impact-map.py` selects **no** test class for a change under `scripts/`,
    `.github/`, `docs/`, `config/`, `.specify/` or `openspec/` (`:68-75`, `:253-255`).
21. `scripts/test-impact-map.py` swallows a missing base ref and exits 0 with an empty map
    (`:157`, `:341-343`) — reproduced above.
22. The map's emitted command always carries `-Ptest.tags=fast,slow` (`:87`, `:299`).
23. `build.gradle.kts:80-85` throws if a single invocation carries both
    `-Ptest.population` and `-Ptest.tags`.
24. `settings.gradle.kts:124` chooses the floor set from the resolved tag list, so a run
    selecting `fast,slow` is measured against the `FULL_*` floors.
25. `scripts/ci/static-gates.sh` is generated between the markers at `:223` and `:781`;
    `scripts/src/sgate/gate_manifest.py` `PLACEMENTS` holds the placement of every gate,
    and `the shell registry is current` (`scripts/ci/static-gates.sh:577`) fails when the
    two drift.
26. `ci-gate` accepts `skipped` for every job it needs (`:1125-1130`), so a path-filtered
    or skipped job is not a failure.
27. The device tier is a separate workflow (`.github/workflows/android-device-tests.yml`,
    `pull_request` types `[opened, synchronize, ready_for_review, labeled]` at `:49`) and is
    not in `ci-gate`'s `needs` — it does not block a merge.
28. `scripts/check-gate-wiring.py` Part I compares `./check.sh` with the workflow;
    `ci.yml:884-886` names `:detekt-rules:koverVerify` as a task `check.sh` must also name.
29. **No gate reads a job's `needs:` list** — a repo-wide grep for `needs=` /
    `.get("needs")` under `scripts/` returns only an unrelated shell local. Adding a
    `coverage`, `detekt` or `quick` job
    changes no gate directly; only GitHub acts on `ci-gate`'s `needs`.
30. Four registry controls mutate `ci.yml` by literal text with `count=1`
    (`_registry.py:1213-1218` and the `coverage-timeout` entry):
    - `gradle-test-population` sabotages the **first**
      `run: ./gradlew :koverVerify -Ptest.population="$TEST_POPULATION"` in the file — today
      `ci.yml:842`. A `coverage` job carrying the same line and placed **before** `tests`
      would be sabotaged instead, and the control would prove nothing.
    - `coverage-timeout` sabotages the **first** `    timeout-minutes: 75` — today `ci.yml:288`.
      A `coverage` job with `timeout-minutes: 75` placed before `tests` has the same failure
      mode.
    - `build-output-readers` requires the three-line `Coverage floors` block to exist
      **verbatim**, including its six-space indent.
    - `workflow-exit-status` sabotages the first Gradle invocation's `set -o pipefail` line.
31. `GRADLE_TASK_PARITY` (`_registry.py:190-269`) marks `:shared:jvmTest` and
    `:desktopApp:test` as **`covered`** — CI reaches them through `koverReport`
    (`check-gate-wiring.py:2285-2289`). Removing `koverReport` from the `tests` job without
    naming those tasks changes what `covered` means and leaves the parity table describing
    a graph that no longer exists.
32. `scripts/check-gate-wiring.py`'s Part E CI surface is exactly `.github/workflows/ci.yml`
    plus `scripts/ci/static-gates.sh` (`:1500-1520`). A gate moved to a **new workflow file**
    is invisible to the CI side; a gate moved to a **new job inside `ci.yml`** is not.
33. `scripts/check-hook-gates.py` hardcodes `.github/workflows/ci.yml` and derives the CI
    detekt module set by regex over the raw text, comments included (`:69`, `:109-113`).
    Splitting detekt into a job inside `ci.yml` keeps the set identical; moving it to a
    separate workflow file shrinks the set and breaks the record.
34. `scripts/check-gradle-test-population.py` and `scripts/check-build-output-readers.py`
    glob **all** workflow files and discover jobs dynamically
    (`scripts/src/workflow_gradle.py:324-343`); neither hardcodes a job, step or task name.
    A job with exactly one test-capable invocation and no build-output readers is ignored
    **by design** (`check-gate-wiring.py:341-343`; `scripts/tests/test_check_build_output_readers.py:245-248`).
35. `ci.yml:213-232` records a **prior six-leaf split of the `tests` job that was
    withdrawn** because it "made a green run that measured the wrong thing" (see
    `docs/decisions/2026-10-05-ci-checks-run-in-parallel.md`). The three couplings it names
    are the run stamp, the count and coverage floors, and the flake comparison.

### Corrections to the plan's own measurement table

36. **Detekt does not run three times per PR.** Measured on run `39981868734`: the `tests`
    job's auto-fix step ran detekt for real on six modules (15:53:02–15:53:53), and inside
    the main Gradle invocation five of the eight detekt tasks printed `UP-TO-DATE`
    (16:01:48). Detekt is analysed **twice** per PR — once in `static` (196 s) and once in
    `tests` (231 s) — and the third occurrence costs ~0 s. The plan's "~430 с" for "тройной
    detekt" is the sum of the two real auto-fix steps; a `detekt` job that replaces both
    would save one of them, not both.
37. **The `tests` job's baseline is a failed run** (fact 4, run `39981868734`). Its 29.4 min
    is a valid
    measurement of the suite, but the job concluded `failure`.
38. **`Coverage floors` was skipped** on the code PR (run `39981868734`) because the Gradle
    step failed
    (fact 4). The plan's floor story ("полы проверяются на PR") currently rests on a step
    that did not run in the measured run.

## Hypotheses

Each entry names what would confirm it and what would refute it.

**H1 — The `tests` job without Kover lands near 13 minutes.**
The plan's `tests` ≈ 13 min is a projection from a locally measured 6 min 44 s for
`:shared:jvmTest` on a 16-vCPU host under load 34–50.
*Confirm:* a CI-runner measurement of `:shared:jvmTest` **without** the Kover agent on
`ubuntu-24.04` (4 vCPU), from the plan's Step 0.
*Refute:* a runner measurement above ~17 min — the Kover agent's overhead is smaller than
the 17m20s it appears to add, and the un-instrumented suite does not shrink as assumed.

**H2 — The `quick` job can select the `fast` population.**
The plan states "population остаётся fast" for `quick`. The map emits `-Ptest.tags=fast,slow`
and the build rejects both spellings at once.
*Confirm:* a change to `scripts/test-impact-map.py` that emits `-Ptest.population=fast`
(or `full`, chosen by the caller), after which `settings.gradle.kts:124` resolves the floor
set from `fast`.
*Refute:* the map's command is used unchanged, in which case `quick` selects `fast,slow`,
runs the slow half and is measured against the `FULL_*` floors.

**H3 — Detekt's third occurrence is free.**
The plan counts three detekt executions per PR.
*Confirm:* the `UP-TO-DATE` markers observed on run `39981868734` are reproduced on a run
where the `static` job and the `tests` job check out different trees (they do — each job
has its own checkout), so the `static` job's detekt is a real run and the `tests` job's
auto-fix is a real run, and only the third is free.
*Refute:* a run where the `tests` job's auto-fix branch push changes the tree and forces the
Gradle invocation's detekt tasks to re-analyse.

**H4 — Removing the control harness from the PR path does not weaken the `tests` job's
proof.**
`ci.yml:904-908` assigns the fast controls to `static` and the rest to `tests`.
*Confirm:* `scripts/check-gate-wiring.py --except-can-fail` still proves the controls that
are not fast, and the fast controls move to the schedule/label path without losing their
own proof (e.g. the same `--with-controls` run on the nightly).
*Refute:* `--except-can-fail` depends on the `static` job having proved the fast controls —
i.e. the two halves are not independent.

**H5 — The `coverage` job's timeout of 75 minutes satisfies `check-coverage-timeout.py`.**
*Confirm:* the gate reads the job that runs `:koverVerify`; if `coverage` runs it with
`timeout-minutes: 75`, and the measured larger population is 2245000 ms, the 2× margin
(74.8 min) is covered.
*Refute:* the gate also inspects the *other* job — if `tests` no longer runs `:koverVerify`,
the gate may find no job, or may still expect one.

## Open questions

1. **Which decision does D3 actually turn on?** The control phase is one command inside
   `sgate run-all` (`run_all.py:562-564`), not a separate gate. Moving it "off the PR path"
   means either a separate job that runs only that phase, or a conditional flag on the
   existing one. The plan does not say which, and the two have different runner-minute
   costs.
2. **Does the `quick` job need a base ref it can actually see?** `actions/checkout@v7`
   defaults to `fetch-depth: 1`, which has no `origin/main`. The plan proposes
   `fetch-depth: 0`; that is a fetch of the whole history on every PR.
3. **What does `quick` do for a change under `scripts/`, `config/`, `.github/`, `docs/`,
   `openspec/`, `.specify/`?** The map selects no test for those (fact 20), so the job
   would be green and empty for exactly the changes D3 says must be watched hardest.
4. **Is the `quick` job allowed to be vacuously green?** The plan's acceptance criterion
   #4 asks the job to report the reason ("нет рефа базы / нет затронутых тестов"), but the
   gate the plan lists (`scripts/check-test-runs.py`, code 3 on a missing report) is about
   the `tests` job, not about `quick`.
5. **What replaces the `run-full-tests` label as the PR escape hatch?** It exists today
   (fact 11) and selects `full`. D1 wants the full population off the PR path, which would
   make the label the *only* way to run it on a PR — or remove it.
6. **Should `FULL_*` really rise to 27/23/31?** `settings.gradle.kts:144-148` records the
   current gap as deliberate; the plan's D1 calls the new number "политика, а не
   измерение". That is a policy decision with a recorded counter-position, and it belongs
   in the ADR the plan's Step 1 already schedules.
7. **(Withdrawn — not a question.)** `scripts/check-coverage-task-list.py` never reads the
   workflow; its subject is `koverReport`'s `dependsOn` list, which this change does not
   touch. The question came from a misreading and does not arise.
8. **What is the non-instrumented `:shared:jvmTest` cost on the runner?** Unmeasured. The
   plan's own Step 0 exists for this, and acceptance criterion #9 ("Runner-минуты на PR —
   ≤ 40") cannot be checked without it.
9. **Does the `detekt` job's verdict need the auto-fix branch pushed?** `ci.yml:419-422`
   says the CI verdict comes from a separate `git diff` step measuring an already-fixed
   tree. The plan's D2 keeps that order but moves it to a new job; the plan does not
   restate which step produces the verdict.
10. **`scripts/check-hook-gates.py` and D4.** The record is asserted against the CI
    workflow in both directions (fact 19). Removing detekt from the hook changes
    `.githooks/pre-push` (the delegate call), `config/hooks/pre-push.json` (the `detekt`
    block) and `scripts/check-hook-gates.py:169-170` — three files, not one.
11. **Does the new work move to a new job inside `ci.yml`, or to a new workflow file?**
    This is the decision every string-sensitive gate keys off (facts 30–33). A new **job in
    `ci.yml`** is invisible to Part E and to `check-hook-gates.py`; a new **workflow file**
    breaks Part E's CI surface, shrinks the CI detekt module set, and leaves
    `check-coverage-timeout.py` reading a file that no longer holds the coverage job.
12. **Where in `ci.yml` must the `coverage` job sit?** `check-coverage-timeout.py` takes the
    **first** job in file order that runs `:koverVerify` (fact 16), and the
    `gradle-test-population` and `coverage-timeout` controls sabotage the **first**
    occurrence of their literal strings (fact 30). A `coverage` job placed before `tests`
    changes which line each of those three reads.
13. **What do the `covered` entries of `GRADLE_TASK_PARITY` become?** `:shared:jvmTest` and
    `:desktopApp:test` are `covered` today because CI reaches them through `koverReport`
    (fact 31). Naming them directly in the `tests` job turns them into `ci` or `both`, and
    `./check.sh` must then name them too.
14. **Does the `detekt` job's Gradle step keep the name `Gradle (tests + coverage + detekt)`?**
    That name is a `tracked_by` anchor (fact: `#Gradle (tests + coverage + detekt)`), and the
    `workflow-exit-status` control sabotages the **first** Gradle invocation's
    `set -o pipefail` line in the file (fact 30). A `detekt` job placed before `tests` would
    make the control hit the wrong step.

## Constraints found

Existing patterns the solution must respect:

- **One population per job, one spelling.** `build.gradle.kts:80-85` throws when
  `-Ptest.population` and `-Ptest.tags` meet in one invocation;
  `scripts/check-gradle-test-population.py` enforces one population per job;
  `config/test-populations.properties` is the single definition
  (`docs/decisions/2026-10-23-a-population-is-a-name.md`, D1).
- **The floor set follows the resolved tags, not the population's name**
  (`settings.gradle.kts:124`).
- **A gate lives where the registry says it lives**
  (`scripts/src/sgate/commands/_registry.py`; `scripts/ci/static-gates.sh` is generated —
  `PYTHONPATH=scripts/src python3 -m sgate render-static-gates --write`).
- **A reader of build output must precede the invocation that rewrites it**
  (`scripts/check-build-output-readers.py`).
- **"No report" is not "pass."** `scripts/check-test-runs.py` (exit 3) and
  `scripts/check-slow-tests-ran.py` fail on a missing report. A step that moves must not
  turn "there was no run" into "the run found nothing".
- **The coverage floor is declared in `settings.gradle.kts`** and nowhere else
  (`scripts/check-coverage-floor-declaration.py`; ADR D4).
- **The coverage timeout is derived from the published measurement**, and the published
  number is the larger of the two populations (`scripts/check-coverage-timeout.py`).
- **A published coverage number is compared with the record it was read from**
  (`scripts/check-published-measurements.py`; `config/docs/coverage-measurements.properties`).
- **`./check.sh` must name the same tasks CI names** (`ci.yml:884-886`;
  `scripts/check-gate-wiring.py` Part I).
- **The pre-push hook names no detekt module** and delegates the decision
  (`scripts/check-hook-gates.py:169-170`, `:199-203`).
- **The hook's detekt set is computed, not listed** — `scripts/changed-modules.py`'s
  downstream closure (`scripts/pre-push-verdict.py:190-221`).
- **`--with-controls` runs alone, after the gates**, because it edits the tree they read
  (`run_all.py:435-437`, `:562-564`; `ci.yml:180-186`).
- **The `static` job's control phase and the `tests` job's `--except-can-fail` are two
  halves of one proof** (`ci.yml:904-908`).
- **Exact strings survive a move; structure does not.** The gates assert step names, task
  spellings, YAML indentation and file paths — not job identity. Moving a block inside
  `ci.yml` is invisible; renaming a step or a task is not. The four control `old` strings
  (fact 30) and the ten `tracked_by` step-name anchors must each still resolve.
- **A soft step must name itself "advisory"** — `check-gate-wiring.py` Part D matches the
  marker against the step `name:` (`check-gate-wiring.py:1274`, `:1311`), and
  `scripts/check-workflow-exit-status.py` enforces the same over all workflows.
- **A job with one test-capable invocation and no build-output readers is ignored by
  design** (`scripts/src/workflow_gradle.py`; `scripts/tests/test_check_build_output_readers.py:245-248`).
  A new `coverage` job whose only invocation is `:koverVerify` would be invisible to the
  readers rule — but it is still test-capable, so the one-population rule applies to it.
- **The CI job graph is not gated** (fact 29): nothing reads `needs:`. The gate that
  aggregates them is GitHub's.
- **The `static` job's control phase and the `tests` job's `--except-can-fail` are two
  halves of one proof** (`ci.yml:904-908`).
- **`concurrency` cancels the superseded run** (`ci.yml:29-30`).
- **Gradle stays `--no-parallel` inside a job** (EOFException in the jvmTest executor,
  #471 — `ci.yml:463-465`); the parallelism comes from jobs.
- **`./gw`, not `./gradlew`** — a private `GRADLE_USER_HOME` per worktree (`AGENTS.md`).
  `scripts/test-impact-map.py:299` uses `./gradlew` and does not follow this.
- **The device tier does not block a merge** (a separate workflow; fact 27).
- **Review verdicts are files**: `.specify/reviews/<slug>.md` with `verdict`, `date`, `pr`,
  `reviewed:` and `documents:` — the gate `review verdicts cover every change` requires
  one, and the digest of the contract documents is recomputed by
  `scripts/check-review-verdicts.py`.
- **The registry is the only list of gates** — `scripts/check-gate-registries.py` compares
  `scripts/ci/static-gates.sh` against the `GATES` list in `_registry.py`. It compares
  *gates*, not jobs: a new job that runs no registered gate does not affect it.

## References

- Plan: `/home/max/.minimax/v2/sessions/2026/10/23/16-30-34-563-session_bXZzX2Y2ZWJmYTg4ZWExNDQxNGE5ZGJhZDM2YjM4ZTlmNjIz/artifacts/plan.md`
- `.github/workflows/ci.yml` — jobs, steps and the population decision
- `build.gradle.kts:57-100` (population → tags, "both given" error), `:385-400` (`koverReport`),
  `:470-477` (`koverHtml`)
- `settings.gradle.kts:107-124` (selection), `:156-158` / `:179-203` (floors)
- `config/test-populations.properties`, `config/docs/coverage-cost.properties`,
  `config/docs/coverage-measurements.properties`
- `scripts/test-impact-map.py`, `scripts/changed-modules.py`, `scripts/lint-changed.py`
- `scripts/pre-push-verdict.py`, `.githooks/pre-push`, `config/hooks/pre-push.json`,
  `scripts/check-hook-gates.py`
- `scripts/src/sgate/commands/_registry.py`, `scripts/src/sgate/commands/run_all.py`,
  `scripts/src/sgate/gate_manifest.py`, `scripts/ci/static-gates.sh`
- `scripts/check-ci-workflow-shape.py`, `scripts/check-gradle-test-population.py`,
  `scripts/check-build-output-readers.py`, `scripts/check-coverage-timeout.py`,
  `scripts/check-coverage-floor-declaration.py`, `scripts/check-coverage-task-list.py`,
  `scripts/check-gate-wiring.py`, `scripts/check-published-measurements.py`,
  `scripts/check-hook-gates.py`, `scripts/check-test-runs.py`,
  `scripts/check-slow-tests-ran.py`, `scripts/check-workflow-exit-status.py`,
  `scripts/check-waiver-references.py`, `scripts/src/workflow_gradle.py`
- `docs/decisions/2026-10-22-ci-fast-only-pr-full-on-schedule.md`,
  `docs/decisions/2026-10-23-a-population-is-a-name.md`,
  `docs/decisions/2026-10-22-the-test-cycle-has-a-map.md`,
  `docs/decisions/2026-10-23-the-local-verdict-judges-what-the-change-reaches.md`,
  `docs/decisions/2026-10-05-ci-checks-run-in-parallel.md`,
  `docs/decisions/2026-10-06-ci-single-gate-registry-and-leaf-split.md`
- `docs/testing/gate-costs.md`, `docs/testing/what-each-tier-proves.md`,
  `docs/CONTEXT.md`, `docs/decisions/DIGEST.md`
- `.agents/skills/singularity-todo-ci-cd/SKILL.md`, `.agents/skills/singularity-todo-quality-tools/SKILL.md`
- CI runs: `39981868734` (code PR), `39982183903` (docs PR) — `gh api repos/gazon1/sing/actions/runs/<id>/jobs`
