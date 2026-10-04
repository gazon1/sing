---
name: singularity-todo-kiwi-tcm-stand
description: Run and extend the local Kiwi TCMS test-case stand in infra/kiwi — mapping repository test classes to test cases, importing Gradle JUnit results as test runs, and reporting what has never been run. Use when the question is "what do I still need to test", when planning what to verify next, when a test was added and should appear in the case database, when deciding whether a Kiwi failure is a bug in the stand or in the app, or when touching infra/kiwi/*, sync.py, gaps.py, or the just kiwi-* recipes. Read before editing anything under infra/kiwi/ — most Kiwi 16 API behaviour there is non-obvious and contradicts its documentation.
---

# Kiwi TCMS stand

`infra/kiwi/` tracks **test intent**: each test class becomes a Kiwi TestCase, each
Gradle run becomes a TestRun, and a case with no TestExecution is a visible hole.

Kover answers "how many lines did the tests touch". That is not the same question as
"what do I intend to verify, and what have I never run". A class can contribute
statements to coverage and still never have been executed.

Kiwi does **not** run tests. Gradle runs them; Kiwi stores intent and results.

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

**One case per test class, not per test method.** A Kiwi case id must be stable, and a
Kotlin class name changes far less often than the set of `@Test` methods inside it.
Changing a test's signature must not mint a new case or split run history.

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
