---
name: singularity-todo-kiwi-tcm-stand
description: Run and extend the local Kiwi TCMS test-case stand in infra/kiwi — the legacy per-test-class case mapping, the scenario traceability layer (user scenarios in Git, coverage/result matrices, Kiwi as a projection), importing Gradle JUnit results as test runs, and reporting what has never been run. The stand is local and intentionally behind the specs, so the coverage claim lives in git (docs/testing/coverage-matrix.md), never in Kiwi. Use when the question is "what do I still need to test", when planning what to verify next, when a test was added and should appear in the case database, when deciding whether a Kiwi failure is a bug in the stand or in the app, or when touching infra/kiwi/*, sync.py, gaps.py, or the just kiwi-* recipes. Read before editing anything under infra/kiwi/ — most Kiwi 16 API behaviour there is non-obvious and contradicts its documentation.
---

# Kiwi TCMS stand

`infra/kiwi/` tracks **test intent** in two layers, and knowing which one you are
in saves an hour:

| Layer | Unit | Code | State |
|---|---|---|---|
| **Legacy** (frozen) | one test **class** | `sync.py` → plans `Automated/*` | 259 cases, as-is |
| **Scenarios** (current) | one user **scenario** (`TASK-REC-01`) | `traceability/` → Kiwi plan named Scenarios | specs in Git, matrices derived |

The legacy layer still runs and is still gated by `just kgaps` / `just kfloor`; it is
simply no longer where new work goes. New scenarios go through `traceability/` only.

`infra/kiwi/` tracks intent: each unit becomes a Kiwi TestCase, each Gradle run becomes
a TestRun, and a unit with no TestExecution is a visible hole.

Kover answers "how many lines did the tests touch". That is not the same question as
"what do I intend to verify, and what have I never run". A class can contribute
statements to coverage and still never have been executed.

Kiwi does **not** run tests. Gradle runs them; Kiwi stores intent and results.

## What the stand is, and what it is not

**It is a local Kiwi instance, not a mirror of the specs.** Measured 2026-10-05: it
was **18 of 19 scenarios behind** the corpus, no workflow calls `seed` or `publish`,
and this build's RPC surface has no `getPlans` at all. It will stay behind. Treating
it as a mirror is the worst state to be in, because a Kiwi that looks authoritative
and is wrong is worse than no Kiwi.

Consequences, so nobody has to rediscover them:

- **The coverage claim lives in git**, in `docs/testing/coverage-matrix.md`,
  regenerated and diffed byte-for-byte in CI. That file is the answer to "what do we
  verify". The stand is not, and cannot be — it is a projection, and the direction is
  only ever Git → Kiwi.
- **Do not read a green `kiwi-seed-check` as evidence it is in sync.** It is a tool
  for the moment you are deliberately syncing, not a state to assert.
- **`just kgaps` is a legacy-layer report.** 18 of its 19 plans are the old per-class
  `Automated/*` cases, which is why it looks authoritative and does not describe the
  scenario layer at all. It now prints a `!! LEGACY` banner saying so; the banner
  exists because the ambiguity was never in the documentation, it was in the report
  looking convincing.
- **Seeding is a manual step, on purpose.** Nothing in CI holds Kiwi credentials, and
  it should stay that way: a Kiwi outage must never be able to fail a build, and a
  committed credential is a worse trade than a stale local database.
- **If you are working against the stand, seed first** — `just kiwi-seed` — and know
  that you are looking at the specs as of now, not as of the last time somebody
  remembered.

## Operating it

```bash
just kiwi-start      # containers in background, returns immediately (~6 s)
just kiwi-wait       # block until ready + initialise the DB
just kiwi-status     # containers / HTTP / DB state, reported separately
just kiwi-logs       # tail logs
just kiwi-stop       # stop, keep data
just ksync           # repo -> test cases (idempotent)
just kresults        # JUnit XML -> test runs
just kgaps           # what has never been run
```

Full cycle:

```bash
./gradlew :shared:jvmTest -Ptest.tags=fast,slow
just kresults
just kgaps
```

`kiwi-start` and `kiwi-wait` are separate on purpose. On a cold start, Postgres initdb
takes ~2 minutes; "start in the background" cannot also mean "the database is ready".
Until migrations finish Kiwi serves 500 on `/` — expected, not a fault. `kiwi-status`
shows that as its own line so an uninitialised stand is never mistaken for a broken one.

## Before you change anything here

Nearly every Kiwi 16 behaviour this stand depends on contradicts what its docs and
names suggest. Each is recorded in the docstring at the place it bites and in
`infra/kiwi/README.md`. Do not "simplify" past one of these without reading it — the
obvious-looking code is the broken one.

| Symptom | Cause |
|---|---|
| `Authentication failed` with a correct password | Kiwi authenticates by **session cookie**. `xmlrpc.client.Transport` does not keep `Set-Cookie`, so every call after `Auth.login` is anonymous. See `SessionTransport`. |
| 301 to `https://…` on every URL | Port 8080 inside the image is an nginx block that only redirects. The app is on 8443, and 8443 is what compose publishes. |
| `certificate has CN=buildkitsandbox` | The bundled cert was issued to the image's build host. `infra/kiwi/gen-tls.sh` makes one with `SAN=localhost,127.0.0.1`. |
| nginx will not start after touching `tls/` | The key must be 0644. The container runs as uid 1001 and cannot read a 0600 root key. |
| `dependency failed to start` on a fresh volume | The postgres image has one init script that dies under `set -u` on an unset SSL-cert env var. `postgres-initdb.d/` is mounted empty over it. Do not delete that directory. |
| `This field is required` on `Product/Plan/Case.create` | Kiwi 16 requires `classification`, plan `type` + `product_version`, and case `case_status` + `category` + `priority` (which is `P1..P5`, not `Priority.NORMAL`). All are empty on a fresh DB; `ensure_*` creates them. |
| Case created but `plan=None` | `TestCase.create` accepts a `plan` key and the form ignores it. `TestPlan.add_case` is a separate, required call. |
| `Select a valid choice` on `TestExecution.create` | It is a bare ModelForm: every FK takes a **pk**, the field is `case` (not `testcase`), `build` is required and is not inherited from the run. |
| `int + NoneType` on the second `add_case` | `TestRun.add_case` writes `sortkey=None`; the next call adds to it. Create executions directly with an explicit `sortkey`. |
| No case matched any JUnit result | The class name in JUnit is `<testcase classname>`, not `<testsuite name>` — Gradle writes `Foo[jvm]` there. |
| Sync recreates every case each run | `TestCase.properties` filters on `case`, not `pk`; `pk__in` returns another case's property without erroring. |

## Things that are decisions, not bugs

Do not "fix" these without an ADR.

**Two case granularities, on purpose — do not "unify" them.**

*Legacy, still true for `Automated/*`:* one case per test **class**, not per test
method, so that changing a test's signature does not mint a new case or split run
history.

*Scenarios, current:* one case per user **scenario** (`TASK-REC-01`), because "one
case = one class" cannot express *"TASK-REC-01 passes on Android and fails on
Desktop"*. Kiwi holds several TestExecutions under one TestCase, and **one run per
(commit, target)** is what keeps the per-tier status — two executions in one run are
one observation written twice, and a last-wins reader loses the tiers.
See ADR `2026-10-05-scenario-test-cases-in-kiwi.md`, which supersedes the class-level
invariant.

**Scenario id is a prefix token, and strictness is deliberate.** In Kotlin
`@DisplayName("TASK-REC-01 …")` and in Maestro `tags: [scenario:TASK-REC-01]`. An id
not at the start, two ids on one method, an id with no spec, or two tests claiming one
scenario are **errors**, not shapes to accommodate — a regex that starts tolerating
whitespace variance is the first step toward reimplementing a Kotlin parser in Python.

**JUnit writes `@DisplayName` into `<testcase name>`, not the method name.** Verified
on this repo's output. A link keyed on `fun` matches nothing while looking perfectly
correct, and the only symptom is an empty result matrix.

**`ModalBottomSheet` is unreachable from a desktop JVM Compose test.** It renders into a
separate semantics root on Compose Multiplatform desktop. `AlertDialog` *is* reachable.
Do not add tags inside a sheet hoping a JVM test can drive it — that is ballast.

**Idempotency key is `source_path`, not FQN.** `CoroutineDiagnosticsTest` exists in both
`shared/src/jvmTest` and `desktopApp/src/jvmTest`. Keying on FQN would silently collapse
one and lose its results. Classes with a genuinely ambiguous FQN are skipped from result
binding with a warning rather than assigned to an arbitrary module.

**Plans are per module, not per source set.** `commonTest` is executed by the `jvmTest`
task; splitting them across plans would double the case count without adding a test.

**Orphan cases are reported, not deleted.** A case whose file no longer exists can never
receive a result, so it would inflate "never run" forever. Deletion in Kiwi is
irreversible and whether a case is obsolete or should be rewritten is a human call, so
`gaps.py` lists them under `КЕЙСЫ-БЛИЗНЕЦЫ`, explicitly not counted as a testing gap.

**Four files match `*Test.kt` and are not tests** — the two `abstract` contract bases
`feature/tasks/TaskRepositoryContractTest.kt` and
`core/files/FileSystemContractTest.kt`, plus the harness helpers
`test/helpers/RunVmTest.kt` and `test/helpers/IsolatedComposeTest.kt`.

Note the naming trap: `core/files/FileSystemContractTest.kt` declares the class
`FileSystemContract<F : FileSystem>` — the file adds `Test`, the class does not. Kiwi
identifiers come from the file path, so the case is named after the *file* while no
such class exists in Kotlin. Reference the file, not the class. `is_runnable_test_class` filters them by asking
whether the file declares a method JUnit executes. `@ParameterizedTest` counts:
`RecurrenceRuleMapperTest` and `RruleGeneratorTest` are real suites with no `@Test` in
the file, and an `@Test`-only filter would drop them. The excluded list is asserted in
`test_abstract_bases_and_helpers_are_excluded` — adding a fifth is a deliberate act.

## Traps when verifying a change

**Test what you changed, on a running stand.** The XML-RPC client is not mock-tested: a
mock would assert the mock, and every defect in the table above is a contract mismatch
that a fake would happily agree with. `BatchPropertiesTest` runs against a live stand
and skips cleanly when it is down.

**`sync --results` can import stale XML.** `build/test-results` is a build artefact that
outlives commits. Sync warns past 24 h, because the Build is labelled with the *current*
git-sha while the results describe older code — a report that looks fresh and is not.
Regenerate before trusting a coverage number.

**Check the whole cycle on a purged stand, not a warm one.** Two real defects here
(cold-start compose failure, phantom cases) were invisible on a stand with volumes
already initialised. `just kiwi-purge <<< y` then `kiwi-start` / `kiwi-wait` / `ksync` /
`kgaps` is the only sequence that exercises them.

**Time the change.** Sync and gaps both walk every case; the N+1 shape costs ~3.6 s at
263 cases and hides easily because the result is still correct. `get_all_cases_properties`
collapses it to one call per plan.

## If the stand is unreachable

Errors are raised as `KiwiError` carrying the endpoint and the failing method, never
as a bare `xmlrpc.client` fault. A raw fault means something reaches the proxy directly
and bypasses the wrapping in `KiwiClient.call`.

## Scenario traceability (`infra/kiwi/traceability/`)

```bash
just trace-validate              # specs + links + namespace; no stand, no network
just trace-coverage              # regenerate the committed coverage matrix
just kiwi::trace-coverage-check  # fail if the committed copy is stale (CI)
just trace-results [maestro]     # JUnit/Maestro XML → build/traceability/results.json
just kiwi-seed-dry-run           # what seeding would write
just kiwi-seed                   # project specs into Kiwi (idempotent on the id)
just kiwi-seed-check             # fail on drift between Kiwi and the specs
just kiwi-publish                # results.json → Kiwi run history
```

**`trace-results` is for a run you just made, and a fast-only run is a subset.**
It exits 2 when a claimed scenario has no result, which is correct for CI
(`-Ptest.tags=fast,slow`) and wrong for the default local cycle: that one
excludes `@Tag("slow")`, and every scenario carrier is `slow`. The recipe passes
`--partial` for you, so use the recipe rather than calling `traceability results`
directly. If you call the module yourself after `./gradlew :desktopApp:test` with
no `-Ptest.tags`, add `--partial` or you will read a deliberate subset as a
broken build.

One direction only: **Git → Kiwi**. Editing a scenario case in the UI is overwritten
by the next seed, so every seeded case carries `extra_link` back to its spec and
`seed --check` exists.

**Two API traps that cost real time here** (both found by using them, not by reading):

- `get_all_cases_properties(...)` takes **plan** ids; `get_cases_properties(...)` takes
  **case** ids. Passing case ids to the former returns `{}` silently — which reads
  exactly like "no such scenario" and makes every seed create a duplicate.
- Case status comes back as `case_status__name`. Reading `status` yields `None`,
  so a drift check reports every case as drifted, forever.

**Exit codes matter in CI**: `1` bad spec/link/stale commit · `2` a claimed target
produced zero testcases (the "quietly green" case) · `3` the stand is unreachable. A
stand outage must never read as a broken spec, and Kiwi is a projection, never a gate.

**Coverage is committed; results are not.** `docs/testing/coverage-matrix.md` is
regenerated and diffed byte-for-byte in CI. The result matrix is a build artifact
(under `build/traceability/`, gitignored) that names one identified commit and is
uploaded as an artifact — a file mixing "who claimed this" with "what passed on
abc1234" goes stale at merge time and conflicts across branches.

## Adding a scenario — the order that works

The pieces are independent, so do them in this order; each step is verifiable
before the next exists.

1. **Write the spec.** `infra/kiwi/scenarios/<area>/<subarea>/<ID>.yaml`. The id
   prefix must abbreviate the directory (`TASK-REC` in `tasks/recurrence/`), and
   `area` is derived from the path — state it only to have it cross-checked. Do
   **not** list tests or Kiwi ids; linkage lives in step 2.
2. **`just trace-validate`.** A new spec with no automation is valid and shows a
   `○` hole. That is the expected state here, not a failure.
3. **Probe, then add the Kotlin carrier** — `@DisplayName("<ID> <what it does>")` on a
   test in `desktopApp/src/jvmTest` (desktop) or `androidApp/src/androidTest` (android).
   The id is the first token. Probe reachability *before* writing the assertion —
   see "the cheap carrier is a reachability probe" below. If no test can reach the
   UI from JVM (see `ModalBottomSheet` below), go straight to step 4 and say so in
   the spec.
4. **Add the Maestro carrier** — `scenario:<ID>` in the flow's `tags:`. Tag
   matching is exact string comparison, so `TAGS=scenario:<ID> scripts/run-maestro.sh`
   selects exactly that flow with no code change. Pick the tier that can actually
   assert the behaviour; do not add tags to a surface no test can drive.
5. **`just trace-validate` again, then `just trace-coverage`.** The committed
   matrix must be regenerated and committed; CI diffs it byte-for-byte.
6. **`just trace-results`, then `just kiwi-seed` and `just kiwi-publish`** to
   project it. Kiwi steps are local-only and never gate a build.

### The cheap carrier is a reachability probe, then the test

**Write the probe first, always.** A probe is a test whose only job is to answer
"can this tier reach that node at all" — and it is throwaway. The recipe is three
minutes and it is the difference between one probe and one failed commit.

```kotlin
@Test
@Tag("slow")
fun probe() = composeTestRule {
    // Navigate exactly as the real test would — a probe that does not reproduce
    // the navigation proves nothing about the node.
    onNodeWithTag(NAV).performClick()
    onAllNodesWithTag(TARGET).assertCountEquals(1)   // plural, on purpose
}
```

Two measured results, so you know what you are choosing between:

| Сценарий | Зонд | Что он на самом деле сказал |
|---|---|---|
| `AUTH-FIRSTRUN-01` | прошёл | Сессия desktop-харнесса — `Anonymous`, поэтому «стены входа нет» утверждается с JVM без фикстур вообще. |
| `TASK-TIME-01` | **не прошёл, и это была ложь, а не факт о платформе** | Зонд доказал, что на desktop секции тайм-трекинга нет. Я записал это как «UI только на Android» и сузил спеку до `targets: [android]`. На самом деле вся фича лежит в `commonMain` (10 файлов, репозиторий и фейс в общем модуле), а desktop-экран её не рендерил из-за расхождения экранов, о котором никто не знал. Теперь секция на обеих платформах, спека заявляет оба тира. |

**Провалившийся зонд читается двумя противоположными способами, и оба применялись.**

| | Правильно | Ошибка |
|---|---|---|
| Фича в `commonMain` | узел отсутствует → **дыра в коде**, починить | сузить спеку до одного тира (сделано с `TASK-TIME-01`) |
| Нужно 2 устройства / управление сетью | тир не дотянется → `unreachable: [android, desktop]` | оставить `○` и писать Compose-тест, который не может пройти (так выглядели все 8 sync-сценариев) |

Матрица рисовала оба случая одним `○`, поэтому исправление одного выглядело лекарством от другого. Теперь у клетки пять состояний: `—` не заявлен · `●` автоматизирован · `○` дыра, нужен тест · `◇` заявлено, но ни один носитель на этом тире не достанет · `⊘` выведено из эксплуатации.

**`unreachable` — поле спеки, а не вывод из провала зонда.** Оно читается, а не вычисляется: зонд измеряет код перед ним, и «узла нет на этом экране» — это утверждение о коде, а не о платформе. Ограничение жёсткое: `unreachable` обязан быть подмножеством `targets`, иначе это замаскированное сужение — ровно то, ради чего всё затевалось. `just trace-carrier` отказывается на таком таргете и объясняет, что вместо теста здесь нужен второй девайс или ручной прогон.

Размечать надо по требованию сценария, а не по удобству: `SYNC-STATUS-01` («нажать при выключенной сети и без аккаунта») достижим с одного десктопа и остаётся пробуемым, тогда как его соседи по sync-области требуют второго устройства. Сейчас размечено 6 клеток из 32.

**Зонд измеряет код, а не платформу.** «Зонд не прошёл» означает «этого узла нет в этом дереве» — и это не то же самое, что «эта возможность существует только здесь». Прежде чем сузить спеку до одного тира, спросите: **фича лежит в `commonMain`?** Если да, то отсутствие узла — это дыра в коде, а не свойство платформы, и правильный ответ — починить, а не сузить спеку. Ошибочное сужение выглядит как аккуратность и необратимо: оно делает spec корректным по отношению к коду, который неверен.

Есть честный случай сужения: `ModalBottomSheet` на desktop — отдельное окно, и кросс-рутовый селектор для него строить не надо. Там различие **структурное и измеренное** (дерево побайтово идентично, клик по контрольной строке работает), а не «фича случайно не долетела».

If the probe fails, **delete the test and the spec's desktop target** rather than
leaving a test that asserts the control exists. An unreachable `●` is worse than
an honest `○`: it is a claim the suite cannot keep.

**Use `onAllNodesWithTag` in the probe.** `onRoot()` and the singular
`onNodeWithTag` both throw when the composition has two semantics roots, so a
probe written with them fails with "ambiguous node" and you go looking for a
semantics bug that is not there. The plural selector does not, so its failure
means exactly one thing: the node is not there.

**Absence is asserted by the act that would break it.** `AuthFirstRunScenarioTest`
clicks the FAB and asserts there is no sign-in wall — that click *is* the
assertion of absence. No `testTag` was added to the auth screens to make it
possible. Reach for that shape before you add a tag to production code for a
test's benefit.

### Which target can assert what — measure, do not assume

- **desktop** (`desktopApp/jvmTest`): anything reachable by `testTag` in the main
  semantics tree. `AlertDialog` content is reachable — on skiko an alert renders
  into the main tree rather than into its own window, which is the opposite of a
  sheet and the reason the sheet is a special case at all.
- **android** (`androidApp/src/androidTest` or Maestro): anything else, and the
  only tier that can drive a sheet.

`ModalBottomSheet` is the trap here, and the reason it is written this way: on
desktop it is a `Dialog`, which is a **separate window**, not a separate semantics
root. A cross-root search returns nothing, and the tree is byte-identical (98
nodes) before and after the click. Earlier notes in this repository said
"separate semantics root"; that was the assumption, and it was wrong. The
practical rule is unchanged and was verified by a control click on a reachable
pin row in the same tree: **if a probe cannot find it on desktop, the sheet is a
window and the carrier is Maestro.** Do not add a root-crossing selector to make
it work — that is the abstraction that has to be built once and debugged forever.


A `●` in the matrix should mean "a real user behaviour is verified here". If the
best you can write is "the control exists", that is still worth having — but say
so in the test's KDoc, as `TaskRecurrenceScenarioTest` does, so the next reader
does not read `●` as more than it is.

### The four things that will bite you

1. **JUnit writes `@DisplayName` into `<testcase name>`, not the method name.** A
   key built on `fun` matches nothing and looks entirely correct; the only symptom
   is an empty matrix.
2. **Gradle appends `()` and, on KMP targets, `[jvm]`.** `shared` emits
   `foo()[jvm]`, desktop emits `foo()`. Both normalise to the same key — one
   function does it, and adding a second normalisation breaks the join silently.
3. **Same commit, new outcome.** A commit is routinely re-tested. An existing
   execution is overwritten, never skipped, or Kiwi keeps a stale `PASSED` while
   the matrix says `❌`.
4. **A target with no result directory is not a violation.** A desktop-only run
   must not fail on Android having no XML; only a target that *ran* and produced
   nothing is an error (exit 2).
