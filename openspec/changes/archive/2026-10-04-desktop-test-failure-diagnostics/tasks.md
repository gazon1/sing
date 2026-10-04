# Tasks — desktop-test-failure-diagnostics

**Status:** completed (2026-10-04, archived)

Deviation notes are inline under the tasks they concern.

---

## MR-1 — Runtime isolation + StepRecorder + bundle enrichment

### Module: desktopApp/build.gradle.kts

- [x] Add `junit.jupiter.execution.parallel.mode.default = same_thread` system property
- [x] Add `junit.jupiter.execution.parallel.mode.classes.default = same_thread` system property
- [x] Add `forkEvery = 1` to test task — **ADDED, THEN REMOVED**: measured +93s (2m53s vs 80s); same_thread on both axes is sufficient. ADR documents the measurement.
- [x] Add `maxParallelForks = 2` to test task — **NOT SET**: governs concurrent forked JVMs, irrelevant without forkEvery.
- [x] Remove dead `attempt: Int = 1` parameter from `runDesktopAppTest`
- [x] Measure `:desktopApp:test` duration before and after; document delta in ADR
- [x] If duration grows > 25%, degrade to methods-only (`mode.default = same_thread` only) and document limitation in ADR

### Module: desktopApp (new StepRecorder.kt)

- [x] `StepRecorder` class: `ArrayList<StepRecord>`, `format()`, `summary(last: Int)`
- [x] `StepRecord` data class: `(offsetMs, name, detail, durationMs, ok, errorLine)`
- [x] `ThreadLocal<StepRecorder?>` with no-op fallback
- [x] `step(name, detail, block)` inline extension on `DesktopComposeUiTest`
- [x] `StepRecorderTest` pure JVM test — **DEVIATION**: not created as a file. Coverage instead: `CreateTaskFlowTest.a_missing_tag_emits_nearby_tags_in_the_failure_message` (live positive control through the harness) + forced-failure verification of steps.txt/statuses/durations.
- [x] Wire into `runDesktopAppTest`: create before body, set in ThreadLocal, clear in finally
- [x] Pass `StepRecorder` to `FailureBundle.capture`

### Module: desktopApp (DesktopNavigation.kt)

- [x] Wrap `awaitTag(tag: String)` in `step("awaitTag", tag)`
- [x] Wrap `awaitTag(matcher, timeoutMs)` in `step("awaitTag(matcher)", matcher.description)`
- [x] Wrap `awaitTagGone` in `step("awaitTagGone", tag)`
- [x] Wrap `awaitAnyDisplayed` in `step("awaitAnyDisplayed", tag)`
- [x] Wrap `tapTab` in `step("tapTab", label)`
- [x] Wrap `openDrawer` in `step("openDrawer")`
- [x] Wrap `goBack` in `step("goBack")`
- [x] Wrap `assertCurrentTab` in `step("assertCurrentTab", label)`

### Module: desktopApp (TasksRobot.kt)

- [x] Wrap all public `given*` / `assert*` / `open` methods in `step` — **DEVIATION**: not wrapped directly; every robot assertion/click goes through `awaitTag`, which steps internally, so robot actions appear in the step log.

### Module: desktopApp (FailureBundle.kt)

- [x] Add `steps: StepRecorder?` parameter to `capture()`
- [x] Write `steps.txt` in own `runCatching` (empty string when no steps recorded)
- [x] Write `tree.txt` = `dumpSemantics()` output (suppressed exception already provides this — now also a file)
- [x] Add `steps.txt` and `tree.txt` to `addSuppressedTo` list
- [x] Add suppressed `AssertionError("Last steps:\n" + summary)` in harness (not in FailureBundle)
- [x] Correct stale KDoc lines 28-32 ("semantics tree not captured")
- [x] Remove stale comment referencing `toTree()` / `onRoot()` availability

### Module: desktopApp (DesktopNavigation.kt — timeout enrichment)

- [x] `awaitTag` timeout: add poll counter, elapsed time, preserve original exception as suppressed
- [x] Message format: `"Tag '$tag' not found after N polls / X ms (timeout Y ms). Last exception: <type>: <message>"`
- [x] `awaitTagGone` and `awaitAnyDisplayed`: same enrichment
- [x] Matcher overload: align error message format

### Documentation

- [x] Create `docs/decisions/2026-10-04-desktop-test-failure-diagnostics.md` skeleton with Debt section
- [x] Record any findings from MR-1 retrospective into Debt section

---

## MR-2 — Annotated screenshot + nodes.txt

### Module: desktopApp (new SemanticOverlay.kt)

- [x] `captureAnnotated(highlightTag: String?, file: File): Int` on `DesktopComposeUiTest`
- [x] Fetch nodes with `TestTag` — **DEVIATION**: `onAllNodesWithTag("*")` is a literal match (empty result), so nodes are collected via `onRoot().fetchSemanticsNode()` + recursive children traversal. Documented in ADR.
- [x] Draw gray boxes and tag labels for all tagged nodes using `Graphics2D`
- [x] Highlight `highlightTag` and nearest candidates in red (use `explainMissingTag` logic)
- [x] Handle `boundsInRoot` vs image size mismatch (scale rectangles)
- [x] Return count of annotated nodes
- [x] `SemanticOverlayTest` smoke test — **DEVIATION**: not created as a file; verified via forced failure (nodes.txt with 2 tagged nodes, readable PNG) and later by the baseline positive control (nodes-diff.txt).

### Module: desktopApp (FailureBundle.kt)

- [x] Write `nodes.txt` unconditionally: tag / text / contentDescription / Selected / bounds per node; header with node count
- [x] Write `screenshot-annotated.png` in own `runCatching`, only when screenshot is enabled
- [x] Pass `highlightTag = lastFailStepDetail` from StepRecorder to overlay
- [x] Verify: both files exist after forced failure, node count > 0, screenshot readable

### Documentation

- [x] Record MR-2 retrospective findings into ADR Debt section

---

## MR-3 — a11y warn-wiring + guards expansion + skill + ADR final

### Module: desktopApp (DesktopAppHarness.kt)

- [x] After successful test body: if `checkA11y == true`, call `A11yChecker(this).scan()` in `runCatching`
- [x] Violations → stdout + `a11y.txt` in diagnostics directory
- [x] Do not fail the test (warn-only)
- [x] Add `singularity.test.a11y` system property; if value equals `"fail"` → throw on violations
- [x] Propagate property in `desktopApp/build.gradle.kts` alongside other `singularity.*` properties
- [x] Document in KDoc that `checkA11y` is now wired

### Module: desktopApp (HarnessConventionTest.kt)

- [x] Expand scan root from `feature/flows` to entire `src/jvmTest`
- [x] Fix `SavedAgendaCreateFlowTest`: add `checkA11y = true` or add to `EXEMPT_A11Y` with reason
- [x] New rule: no bare `onNodeWithTag(` or `onAllNodesWithTag(` outside `test/helpers`
- [x] `EXEMPT_RAW_TAGS: Map<String, String>` (reason required, same convention as `knownUnapplied`)
- [x] Two-sided check: undeclared raw tag use → fail; stale EXEMPT entry → fail
- [x] Migrate existing raw calls to helpers or add to EXEMPT — **DONE BEYOND SCOPE**: 12 flow tests + DesktopAppBootTest migrated (80+ call sites), 16-helper set created; EXEMPT list now only composable-level tests.
- [x] Positive control: raw-call violation verified via the widened guard's first run (12 files red) before exemptions were added.
- [x] Positive control: add stale EXEMPT entry → test is red → remove → test is green

### Documentation / skill

- [x] `singularity-todo-desktop-compose-ui-tests` skill: add "Reading the failure bundle" section (steps.txt → annotated screenshot → tree.txt → kermit.log → db-state.txt), rule "use helpers not raw onNodeWithTag", new diagnostic flags
- [x] Update KDoc `DesktopAppHarness.kt` lines 76-78 (forkEvery comment — now accurate)
- [x] Finalize `docs/decisions/2026-10-04-desktop-test-failure-diagnostics.md`: all Debt items from retrospectives, runtime isolation measurements, updated FailureBundle table, Ultron decisions (what taken, what not, why, trigger for #94)
- [x] Update `docs/decisions/2026-09-30-test-infra-known-gaps.md`: tree file now written (remove from gaps)
- [x] Run `./scripts/refresh-decisions-digest.sh`

### Final checks

- [x] `python3 scripts/find-unwired-surfaces.py` — zero new unwired surfaces
- [x] `openspec validate --all --strict` — exit 0, no new findings
- [x] Archive: `openspec archive desktop-test-failure-diagnostics`
- [x] `./gradlew :shared:jvmTest :desktopApp:test` — green
- [x] `just lint` — green
