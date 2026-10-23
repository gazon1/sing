# Rule Control Audit

Audit date: 2026-10-22. Scope: every rule-like check **not** a registered script gate —
custom detekt rules, Konsist architecture tests, other rule-like checks. Read-only; no build
was run. Method: enumerate rule sources → map each rule to a test that *proves it can fail* →
classify the gap.

---

## 1. Summary

| Group | Rules / assertions | With positive control | Without |
|---|---|---|---|
| Detekt rules (`detekt-rules/`) | 32 rule classes | **32** | 0 |
| Konsist / arch tests (`shared/src/jvmTest/.../arch/`) | 253 test methods in 53 files | 114 (Type A) + 113 (Type B) | **26** (Type C) |
| Other rule-like checks | 54 registry gates + 12 orphan scripts | 52 / 54 gates | **2 gates + 12 scripts** |

Type A = explicit sabotage (committed fixture, inline synthetic source, or a
`positiveControl…` test). Type B = corpus guard only (`files.size > 10`) — proves the scan
ran, not that it detects a violation. Type C = neither.

**Shape of the gap:** group 1 is closed, group 2 is the gap, group 3 is a small leak. The
detekt module derives its inventory from source and fails on a rule with no control
(`scripts/gen-detekt-rule-table.py:266`). The Konsist layer has no equivalent derivation:
26 test methods in 4 files assert "no violations now" with neither fixture nor corpus guard,
so they pass vacuously if the scanner breaks.

---

## 2. Detekt rules — 32 rule classes, all controlled

32 `: Rule(` classes in `detekt-rules/src/main/kotlin/com/singularity/todo/detekt/`; every
one is reached by a positive assertion:

| Rule source (`detekt-rules/src/main/kotlin/…/detekt/`) | Control | Evidence |
|---|---|---|
| `AppErrorCodeRule.kt:46` | yes | `AppErrorCodeRuleTest.kt:44` (`assertEquals(1, findings.size)`) |
| `KDocEnforcementRules.kt:16` ViewModelMustHaveKDoc | yes | `RuleFiresSmokeTest.kt:293` |
| `KDocEnforcementRules.kt:57` RepositoryInterfaceMustHaveKDoc | yes | `RuleFiresSmokeTest.kt:308` |
| `MviViewModelRulesProvider.kt` IntentMethodName | yes | `RuleFiresSmokeTest.kt:483` |
| `MviViewModelRulesProvider.kt` MviViewModelExt | yes | `RuleFiresSmokeTest.kt:236,256,325` |
| `MviViewModelRulesProvider.kt` ShadowedState | yes | `RuleFiresSmokeTest.kt:591` |
| `MviViewModelRulesProvider.kt` VmCloseable | yes | `RuleFiresSmokeTest.kt:551` |
| `MviViewModelRulesProvider.kt` VmScopePosition | yes | `RuleFiresSmokeTest.kt:519` |
| `NoCombineSideEffectRule.kt:40` | yes | `NoCombineSideEffectRuleTest.kt` (9 positive assertions) |
| `NoCoroutineLaunchInInit.kt:38` | yes | `RuleFiresSmokeTest.kt:363` |
| `NoDirectClockSystemRule.kt:40` | yes | `NoDirectClockSystemRuleTest.kt` (4) — rule is `active: false` |
| `NoDirectDispatchersRule.kt:61` | yes | `NoDirectDispatchersRuleTest.kt` (3) |
| `NoDivergentScopeAndReporterRule.kt:58` | yes | `NoDivergentScopeAndReporterRuleTest.kt` (4) |
| `NoDroppedUiStatePropertyRule.kt:69` | yes | `NoDroppedUiStatePropertyRuleTest.kt` (4) |
| `NoEmptyOnClickLambdaRule.kt:55` | yes | `NoEmptyOnClickLambdaRuleTest.kt:117` |
| `NoFactoryViewModelRule.kt:28` | yes | `NoFactoryViewModelRuleTest.kt` |
| `NonTaggedMaestroElements.kt:61` | yes | `NonTaggedMaestroElementsRuleTest.kt:46` — rule is `active: false` |
| `NoOpUpdateStateRule.kt:38` | yes | `NoOpUpdateStateRuleTest.kt:31` |
| `NoPopupWithoutResourceIdMapping.kt:84` | yes | `NoPopupWithoutResourceIdMappingTest.kt:48` |
| `NoRealDelayInTestRule.kt:55` | yes | `NoRealDelayInTestRuleTest.kt` |
| `NoRunBlockingRule.kt:25` | yes | `NoRunBlockingRuleTest.kt` (2) |
| `NoRunCatchingInSuspend.kt:50` | yes | `NoRunCatchingInSuspendTest.kt` |
| `NoStateInRule.kt:38` | yes | `NoStateInRuleTest.kt` |
| `NoStaticProfileAwareCurrentUserRule.kt:51` | yes | `RuleFiresSmokeTest.kt:169` |
| `NoSwallowedCancellation.kt:37` | yes | `NoSwallowedCancellationTest.kt` |
| `NoTextFieldStateInViewModelRule.kt:69` | yes | `NoTextFieldStateInViewModelRuleTest.kt:50` |
| `NoUnreportedFailurePathRule.kt:45` | yes | `NoUnreportedFailurePathRuleTest.kt` (10) |
| `NoUnwiredReporterInBindingRule.kt:57` | yes | `NoUnwiredReporterInBindingRuleTest.kt` (5) |
| `NoValueBasedTextFieldRule.kt:41` | yes | `NoValueBasedTextFieldRuleTest.kt:42` |
| `NoViewModelScopeInProductionRule.kt:30` | yes | `NoViewModelScopeInProductionRuleTest.kt:34` |
| `PassThroughUseCaseRule.kt:41` | yes | `PassThroughUseCaseRuleTest.kt` |
| `UserScopedRepositoryRulesProvider.kt` ProhibitUserIdInObserve | yes | `RuleFiresSmokeTest.kt:346` |

Mechanical enforcement already exists: `scripts/gen-detekt-rule-table.py:266` fails
`--check` when a rule's "Test" column is `—`; the generated table
(`.agents/skills/singularity-todo-detekt-rules-authoring/RULES.md:10-41`) lists all 32 rules
with a control.

**Residual risk inside group 1 (both confirmed):**

- **DetektConfigWiringTest's provider list is short.** `DetektConfigWiringTest.kt:137-157`
  enumerates 19 providers; the tree has 25. Absent: `NoCoroutineLaunchInInitProvider`,
  `NoDroppedUiStatePropertyProvider`, `NoPopupWithoutResourceIdMappingProvider`,
  `NoTextFieldStateInViewModelProvider`, `NoValueBasedTextFieldProvider`,
  `NonTaggedMaestroElementsProvider`. Its KDoc claims it "mirrors
  `META-INF/services/...`" (`:136`), so this is a stale mirror, not a choice. Effect:
  `every provided custom rule has a block in detekt yml` (`:95`) never sees those six rule
  sets, and `every configured rule name is supplied by a registered provider` (`:71`) skips
  them via the `continue` at `:80`. **Missing control:** derive the list from the services
  file, or add the six.
- **Two rules are `active: false`** — `NoDirectClockSystem`
  (`config/detekt/detekt.yml:571`) and `NonTaggedMaestroElements` (`:742`). Their tests
  pass, but the rules never run in the build, so the tests prove code CI does not execute.
  `NonTaggedMaestroElements` is a deliberate deferral (`config/detekt/detekt.yml:731-741`);
  `NoDirectClockSystem` is retired (`scripts/check-detekt-registrations.sh:37`).

## 3. Konsist / architecture tests — 26 uncontrolled assertions

Konsist is used in 9 files under `shared/src/jvmTest/kotlin/com/singularity/todo/arch/`:
`ArchitectureTest.kt:5`, `CrashReportingWiringTest.kt:3`, `DiFacadeTest.kt:3`,
`SelectableTextScan.kt:3`, `SingleOfKotlinDefaultsTest.kt:3`, `TextFieldShapeScan.kt:3`,
`UnannotatedTestMemberTest.kt:3`.

**Type C — 26 assertions in 4 files that can pass vacuously:**

| Source | Assertion | Why it can never fail | What a control must mutate |
|---|---|---|---|
| `ArchitectureTest.kt:312` | feature domain does not import presentation or data | 119 files under `feature/*/domain/`; the only one that imports a presentation type is the allowlisted `CreateTaskFromDraft.kt` (`LAYER_ALLOWLIST`, `:136`) | a `feature/*/domain/X.kt` importing `.presentation.` |
| `ArchitectureTest.kt:321` | feature presentation does not import data layer | no offenders in tree | a presentation file importing `…data.` |
| `ArchitectureTest.kt:329` | feature use cases do not import repository implementations | no offenders in tree | a usecase importing `…RepositoryImpl` |
| `ArchitectureTest.kt:358` | feature data does not import presentation layer | no offenders in tree | a data file importing `…presentation.` |
| `ArchitectureTest.kt:366` | colour literals live in theme files | 7 files contain `Color(0x`; in `feature/` only `notes/Ids.kt` (allowlisted) and `tasks/presentation/theme/TaskSemanticColors.kt` (`/theme/`, excluded) | a feature file with `Color(0x…)` outside `/theme/` |
| `ArchitectureTest.kt:390` | a preview uses the app theme, not a bare `MaterialTheme` | **zero** `MaterialTheme {` in commonMain (`grep` = 0) | a `@Preview` file containing `MaterialTheme {` |
| `ArchitectureTest.kt:434` | only the theme itself reads `isSystemInDarkTheme` | exactly 1 file in commonMain calls it, and that file *is* the allowlist entry (`:146`) | a second file calling `isSystemInDarkTheme(` |
| `ArchitectureTest.kt:459` | koog imports confined to ai packages | 47 files import `ai.koog`; 0 sit outside `KOOG_ALLOWED_PACKAGES` (`:302`) | a `ai.koog` import from `feature/tasks/…` |
| `ArchitectureTest.kt:470` | commonMain does not use JVM file APIs | zero `java.*`/`kotlin.io.path` imports in commonMain | a commonMain file importing `java.io.File` |
| `ArchitectureTest.kt:502` | repository impls imported only from di modules | 10 files import a `*RepositoryImpl`; all 10 are in `core/di` or a `*DiModule.kt` | a screen importing `…RepositoryImpl` |
| `ArchitectureTest.kt:519` | repository interfaces declare no `Blocking` methods | no `*Blocking` methods exist | an interface with `fun xBlocking()` |
| `ArchitectureTest.kt:530` | DAO mutations are ownership-scoped | 95 `UPDATE`/`DELETE` queries; the 10 without `user_id`/`owner_id` are all in the 20-entry allowlist (`:222`) | a `@Query("DELETE FROM …")` with no ownership column and no allowlist entry |
| `ArchitectureTest.kt:548` | core reaches DAO mutations only via a repository | no offenders | a `core/` file calling `dao.insert(` |
| `ArchitectureTest.kt:583` | repository read methods are user-scoped | 3 allowlisted methods (`:613`); rest scoped | a `RepositoryImpl` getter whose SQL lacks `user_id` |
| `ArchitectureTest.kt:641` | nav3 backStack seed mismatch | no mismatched seeds | an `entry<AppDestination.TasksGraph>` with `Create` seed + `Detail` start, no `backStack.add` |
| `ArchitectureTest.kt:719` | SyncEngine owns no business logic | no `init{}`, no `outboxDao.` | `SyncEngine.kt` gaining an `init { }` |
| `ArchitectureTest.kt:752` | OutboxService owns no stateful logic | no `init{}` | `OutboxService.kt` gaining `init { }` |
| `ArchitectureTest.kt:794` | SyncBootstrapper owns no event-handling logic | no `pullEventHandler.handleEvent` | `SyncBootstrapper.kt` calling `handleEvent` |
| `ArchitectureTest.kt:833` | Sync phases create no own StateFlow | no `MutableStateFlow(` in Push/PullPhase | `PushPhase.kt` with `MutableStateFlow(` |
| `ArchitectureTest.kt:414` | colour allowlist does not grow | ceiling 1, actual 1 (`:186`, `:163`) — a *ratchet*, self-limiting | a second allowlist entry |
| `SelectableTextConventionTest.kt:51,69` | every text-widget file is in the skill inventory | the inventory matches the tree today; the skill table is the oracle | a feature file using `TextField` absent from the table |
| `TextFieldShapeConventionTest.kt:76,94` | every text-field file has a classified shape | classifications match | a bridged field file with no shape row |
| `TextFieldSourceContractTest.kt:74,112` | a field bound to a surface path reads it through the source | conforms | a surface reading a static value |

For contrast, the controls that exist in the other two types:

| Type | Example controls |
|---|---|
| A (sabotage) | `DiFacadeTest.kt:100` (`positiveControlDetectsLooseBinding`), `SingleOfKotlinDefaultsTest.kt:106`, `UnannotatedTestMemberTest.kt:114-132` (+ fixture `shared/src/jvmTest/fixtures/arch/UnannotatedMemberFixtureSource.kt`), `FieldOwnedTextSeedContractTest.kt:61,119,145`, `SyncTransportIdentityTest.kt:86`, `CommonMainJvmApiTest.kt:182`, `CachedIdentityReadArchitectureTest.kt:39` |
| B (corpus guard only) | `CrashReportingWiringTest.kt:70,93,98` (guard `:113`), `ViewModelTestCoverageTest.kt:127`, `MaestroFlowTagsTest.kt:116`, `ViewModelInitOrderTest.kt:77`, `TextFieldRewriteContractTest.kt:111` |

`ArchitectureTest` is `@Tag("slow")` (`ArchitectureTest.kt:24`) and CI runs it
(`.github/workflows/ci.yml:406`), so it does execute — the gap is that it cannot
*distinguish* "clean" from "scanner broken".

**One assertion has left this class since the audit.** The rule *the question
asked of the index is built by the rule* (`ArchitectureTest.kt:330`) was added on
2026-10-23 and shipped without a control; it gained one the same day —
`positiveControlDetectsAQuestionBuiltByHand` runs the scan over a synthetic
file that builds the question by hand, outside `core/search`, and fails if the
scan finds nothing. The count above is therefore 26 as of the audit and 25
after it. The two layers still differ: the detekt layer derives its inventory
from the sources (`scripts/gen-detekt-rule-table.py:266`), and the Konsist
layer has no equivalent derivation — so this count is a reading, and a rule
added today is invisible to it until someone re-reads this file.

## 4. Other rule-like checks

### 4a. Script gates — registry coverage is enforced; two gaps

`_registry.py:751` holds `GATE_CONTROLS` (45) and `:950` `GATE_EXEMPTIONS` (14).
`scripts/check-gate-wiring.py:1681` (`check_registry_completeness`) fails any gate script
invoked from a gate surface with neither. Joining `GATE_CONTROLS[*].proves` with
`GATE_EXEMPTIONS` keys over all 54 registered gates leaves exactly **two with neither**:

| Gate | Source | Control | Missing control must mutate |
|---|---|---|---|
| `GitHub workflows are valid` | `_registry.py:571` (`bash_func="github_workflows_valid"` → `static-gates.sh:171`, actionlint) | **no** | a `.github/workflows/*.yml` with an invalid workflow |
| `YAML is structurally valid` | `_registry.py:577` (`yaml_is_structurally_valid` → `static-gates.sh:181`, yamllint) | **no** | a `*.yml` violating `.yamllint.yml` |

Both are `CmdType.BASH_C` with no `script=`, so `registered_gate_scripts()`
(`check-gate-wiring.py:1617`; regex `:1659` matches only `scripts/check-*.py|sh`) never sees
them, and `check_registry_completeness` cannot report them.

### 4b. Orphan script checks — no control, no unit test

24 Python scripts under `scripts/` and `scripts/ci/` are not registered gates. 10 have a
dedicated test in `scripts/tests/`; **12 have none or only indirect coverage**:

| Script | Dedicated test? | Missing control must mutate |
|---|---|---|
| `scripts/check_adr_status.py` | none | an ADR with an invalid/legacy `status` — also note `adr_status_policy` is classified a *gate* at `_registry.py:84` yet has no `GateSpec` and is not reachable from `static-gates.sh`/`check.sh` |
| `scripts/openspec-import-all.py` | indirect (`test_traceability_speckit.py`) | a malformed `openspec/changes/<slug>/` |
| `scripts/refresh-issues-snapshot.py` | none | a stale `.specify/issues/*.yaml` |
| `scripts/resolve_stale_adr_status.py` | none | an ADR past its stale window |
| `scripts/junit-annotations.py` | none | a JUnit XML with an unexpected annotation |
| `scripts/kover-markdown-table.py` | none | a Kover XML with no coverage rows |
| `scripts/print-source-tree.py` | none | an unparseable source path |
| `scripts/scanning.py` | indirect (imported by 4 tests) | a scan target that does not exist |
| `scripts/instrumented-run-summary.py` | none | a device test result set with a missing class |
| `scripts/run-android-ui-tests.py` | none | a run that produced no results |
| `scripts/run-evals.py` | none | an eval set with no results |
| `scripts/step-duration-report.py` | none | a report with no step durations |

`scripts/run-maestro-e2e.py` and `scripts/docs_tree.py` do have coverage
(`test_run_maestro_e2e_script.py`, `test_refresh_decisions_digest.py`).

### 4c. Skill-backed scripts

30 script paths are referenced from `.agents/skills/**/SKILL.md`. 12 are rule-like
(`check-*`); the other 18 are utilities, runners and templates, not rules, and are out of
scope. Of the 12:

| Script | Status |
|---|---|
| `scripts/check-detekt-registrations.sh`, `check-doc-dead-refs.py`, `check-doc-links.sh`, `check-flaky-tests.py`, `check-room-schema-integrity.py`, `check-script-conventions.py`, `check-selectable-text-coverage.py`, `check-skill-drift.py`, `check-test-runs.py`, `check-test-task-inputs.py` | registered gates with a control or a stated exemption — e.g. `check-selectable-text-coverage.py` is exempt with `SelectableTextConventionTest.kt` named as its proving (`_registry.py:951-962`) |
| `scripts/check-gate-wiring.py` | exempt with a reason (`_registry.py:956`) |
| `scripts/check-openspec-stale.py` | has a control (`_registry.py:879`) but **no `GateSpec`** — the control proves a script that no registered gate runs |
| `scripts/check_adr_status.py` | no control, no `GateSpec` (see §4b) |

So one skill-documented rule (`check-openspec-stale.py`) has a control for a script that
never runs as a gate, and one (`check_adr_status.py`) has neither.

---

## 5. Top 5 rules most needing a control

Ranked by how likely the rule is to be *silently* broken — broken while the tree stays
green.

| # | Rule | Why it is silently breakable | Control must mutate |
|---|---|---|---|
| 1 | `ArchitectureTest.kt:470` — commonMain does not use JVM file APIs | zero `java.*` imports in commonMain; the only allowlist entry (`feature/ai/tools/AdrTools.kt`, `:197`) no longer imports any file API. The rule matches nothing | a commonMain file importing `java.io.File` |
| 2 | `ArchitectureTest.kt:390` — a preview must not use a bare `MaterialTheme` | zero `MaterialTheme {` occurrences in commonMain — the detector has never fired here | a `@Preview` file containing `MaterialTheme {` |
| 3 | `ArchitectureTest.kt:502` / `:583` — repository-impl import boundary, user-scoped reads | 10 files import a `*RepositoryImpl`, all in `core/di` / a `*DiModule.kt`; the read rule's 3 exceptions are the allowlist at `:613`. Neither filter's negative branch is exercised | a screen importing `…RepositoryImpl`; a `RepositoryImpl` getter whose SQL lacks `user_id` |
| 4 | `GitHub workflows are valid` + `YAML is structurally valid` (`_registry.py:571,577`) | two blocking CI gates never observed to fail; the completeness gate structurally cannot see them (`BASH_C`, no `script=`) | an invalid `.github/workflows/*.yml`; a `*.yml` violating `.yamllint.yml` |
| 5 | `ArchitectureTest.kt:530` / `:548` — DAO mutation ownership scoping, core-layer write boundary | the scoping rule leans on a 20-entry allowlist (`GLOBAL_DAO_MUTATION_ALLOWLIST`, `:222`) that could be over-broad: a rule matching *every* mutation would be masked by it | a `@Query("DELETE FROM …")` with no ownership column and no allowlist entry |

Runner-up: `ArchitectureTest.kt:434` and `:641` (the latter returns silently when
`JvmNavEntries.kt` is not found, `ArchitectureTest.kt:645`).

## 6. Recommendation — a mechanical gate that keeps this from rotting

Mirror the detekt side: derive the inventory from source, fail on a rule with no control,
put the derivation in the gate. New gate `scripts/check-arch-rule-controls.py`:

| # | Rule the gate enforces |
|---|---|
| 1 | **Derive, do not hand-list.** Parse `shared/src/jvmTest/.../arch/*.kt` for test methods whose body has a Konsist scope query (`Konsist.scopeFromExternalDirectory`, `scope.files`, `scope.interfaces()`) *and* a "no offenders" assertion (`assertNoOffenders`, `fail(`, `assertTrue(x.isEmpty())`). Each is a *rule*. |
| 2 | **Registry** `scripts/arch-rule-controls.tsv`: `rule_id <TAB> control_kind <TAB> control_ref`; `control_kind ∈ {fixture, synthetic}`; `control_ref` = `fixtures/arch/<file>.kt` or `synthetic:<TestClass>.<method>`. |
| 3 | **Every derived rule has a row** — fail naming the rule. |
| 4 | **Every `control_ref` exists** and is referenced by the test class it claims. |
| 5 | **Every `fixture` still contains the violation.** The gate greps it for the shape the rule hunts (e.g. a `java.io.File` import for `common-main-no-jvm-file-api`), so a fixture that was "fixed" fails the gate rather than ceasing to prove anything. |
| 6 | **Every `synthetic` control asserts a non-empty finding set.** A control asserting emptiness is rejected as a control. |
| 7 | **The gate proves itself:** delete one TSV row → assert non-zero exit naming the rule → restore. Same sabotage model as `scripts/check-gate-wiring.py`. |
| 8 | **Wiring:** register in `_registry.py` (`GATES` + `GATE_CONTROLS`) and `scripts/ci/static-gates.sh`. Give the two `BASH_C` gates real controls *or* exempt them with a reason — and extend `registered_gate_scripts()` (`check-gate-wiring.py:1617`) to also report `GateSpec`s with `cmd_type == BASH_C`, so the completeness check sees them. |
| 9 | **Migration order:** register the 26 Type-C rules with their current state (`no-control`) plus a `tracked_by` note, then land fixtures for the top 5. A registry that starts empty and fails CI is a change nobody merges; one that starts populated and ratchets down is one that gets merged. |

**Do not** build this on the skill tables (`.agents/skills/**/SKILL.md`): those are outputs,
kept in sync by `check-skill-drift.py`. The source of truth for *which rules exist* is the
test source set.

## 7. Limitations of this audit

- No build, no test run, no Gradle: claims are from static reading plus `grep`/`python`.
  Assertion *strength* was judged by reading the assertion, not by executing it.
- Detekt controls were verified by locating a non-empty-finding assertion in each rule's
  test; whether it currently passes was not executed.
- `shared/src/commonTest` has no Konsist usage (Konsist is only in `jvmTest`); other modules'
  test source sets were not enumerated.
- The 12 scripts in §4b were classified as "not registered as gates and without a dedicated
  unit test"; whether they are meant to be gates is not decided here.
- The A/B/C classification is a static heuristic over assertion text; the Type-C list was
  re-verified by reading each assertion, but a Type-B file may still hide a real control.
