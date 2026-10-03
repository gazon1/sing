# Tasks — desktop-test-failure-diagnostics

**Status:** in-progress

---

## MR-1 — Runtime isolation + StepRecorder + bundle enrichment

### Module: desktopApp/build.gradle.kts

- [ ] Add `junit.jupiter.execution.parallel.mode.default = same_thread` system property
- [ ] Add `junit.jupiter.execution.parallel.mode.classes.default = same_thread` system property
- [ ] Add `forkEvery = 1` to test task
- [ ] Add `maxParallelForks = 2` to test task
- [ ] Remove dead `attempt: Int = 1` parameter from `runDesktopAppTest`
- [ ] Measure `:desktopApp:test` duration before and after; document delta in ADR
- [ ] If duration grows > 25%, degrade to methods-only (`mode.default = same_thread` only) and document limitation in ADR

### Module: desktopApp (new StepRecorder.kt)

- [ ] `StepRecorder` class: `ArrayList<StepRecord>`, `format()`, `summary(last: Int)`
- [ ] `StepRecord` data class: `(offsetMs, name, detail, durationMs, ok, errorLine)`
- [ ] `ThreadLocal<StepRecorder?>` with no-op fallback
- [ ] `step(name, detail, block)` inline extension on `DesktopComposeUiTest`
- [ ] `StepRecorderTest` pure JVM test: ok/fail statuses, non-zero duration, thread isolation
- [ ] Wire into `runDesktopAppTest`: create before body, set in ThreadLocal, clear in finally
- [ ] Pass `StepRecorder` to `FailureBundle.capture`

### Module: desktopApp (DesktopNavigation.kt)

- [ ] Wrap `awaitTag(tag: String)` in `step("awaitTag", tag)`
- [ ] Wrap `awaitTag(matcher, timeoutMs)` in `step("awaitTag(matcher)", matcher.description)`
- [ ] Wrap `awaitTagGone` in `step("awaitTagGone", tag)`
- [ ] Wrap `awaitAnyDisplayed` in `step("awaitAnyDisplayed", tag)`
- [ ] Wrap `tapTab` in `step("tapTab", label)`
- [ ] Wrap `openDrawer` in `step("openDrawer")`
- [ ] Wrap `goBack` in `step("goBack")`
- [ ] Wrap `assertCurrentTab` in `step("assertCurrentTab", label)`

### Module: desktopApp (TasksRobot.kt)

- [ ] Wrap all public `given*` / `assert*` / `open` methods in `step`

### Module: desktopApp (FailureBundle.kt)

- [ ] Add `steps: StepRecorder?` parameter to `capture()`
- [ ] Write `steps.txt` in own `runCatching` (empty string when no steps recorded)
- [ ] Write `tree.txt` = `dumpSemantics()` output (suppressed exception already provides this — now also a file)
- [ ] Add `steps.txt` and `tree.txt` to `addSuppressedTo` list
- [ ] Add suppressed `AssertionError("Last steps:\n" + summary)` in harness (not in FailureBundle)
- [ ] Correct stale KDoc lines 28-32 ("semantics tree not captured")
- [ ] Remove stale comment referencing `toTree()` / `onRoot()` availability

### Module: desktopApp (DesktopNavigation.kt — timeout enrichment)

- [ ] `awaitTag` timeout: add poll counter, elapsed time, preserve original exception as suppressed
- [ ] Message format: `"Tag '$tag' not found after N polls / X ms (timeout Y ms). Last exception: <type>: <message>"`
- [ ] `awaitTagGone` and `awaitAnyDisplayed`: same enrichment
- [ ] Matcher overload: align error message format

### Documentation

- [ ] Create `docs/decisions/2026-10-04-desktop-test-failure-diagnostics.md` skeleton with Debt section
- [ ] Record any findings from MR-1 retrospective into Debt section

---

## MR-2 — Annotated screenshot + nodes.txt

### Module: desktopApp (new SemanticOverlay.kt)

- [ ] `captureAnnotated(highlightTag: String?, file: File): Int` on `DesktopComposeUiTest`
- [ ] Fetch nodes with `TestTag` from `onRoot(useUnmergedTree = true).fetchSemanticsNodes()`
- [ ] Draw gray boxes and tag labels for all tagged nodes using `Graphics2D`
- [ ] Highlight `highlightTag` and nearest candidates in red (use `explainMissingTag` logic)
- [ ] Handle `boundsInRoot` vs image size mismatch (scale rectangles)
- [ ] Return count of annotated nodes
- [ ] `SemanticOverlayTest` smoke test: annotated image is created, node count > 0

### Module: desktopApp (FailureBundle.kt)

- [ ] Write `nodes.txt` unconditionally: tag / text / contentDescription / Selected / bounds per node; header with node count
- [ ] Write `screenshot-annotated.png` in own `runCatching`, only when screenshot is enabled
- [ ] Pass `highlightTag = lastFailStepDetail` from StepRecorder to overlay
- [ ] Verify: both files exist after forced failure, node count > 0, screenshot readable

### Documentation

- [ ] Record MR-2 retrospective findings into ADR Debt section

---

## MR-3 — a11y warn-wiring + guards expansion + skill + ADR final

### Module: desktopApp (DesktopAppHarness.kt)

- [ ] After successful test body: if `checkA11y == true`, call `A11yChecker(this).scan()` in `runCatching`
- [ ] Violations → stdout + `a11y.txt` in diagnostics directory
- [ ] Do not fail the test (warn-only)
- [ ] Add `singularity.test.a11y` system property; if value equals `"fail"` → throw on violations
- [ ] Propagate property in `desktopApp/build.gradle.kts` alongside other `singularity.*` properties
- [ ] Document in KDoc that `checkA11y` is now wired

### Module: desktopApp (HarnessConventionTest.kt)

- [ ] Expand scan root from `feature/flows` to entire `src/jvmTest`
- [ ] Fix `SavedAgendaCreateFlowTest`: add `checkA11y = true` or add to `EXEMPT_A11Y` with reason
- [ ] New rule: no bare `onNodeWithTag(` or `onAllNodesWithTag(` outside `test/helpers`
- [ ] `EXEMPT_RAW_TAGS: Map<String, String>` (reason required, same convention as `knownUnapplied`)
- [ ] Two-sided check: undeclared raw tag use → fail; stale EXEMPT entry → fail
- [ ] Migrate existing raw calls to helpers or add to EXEMPT
- [ ] Positive control: temporarily add raw call → test is red → revert → test is green
- [ ] Positive control: add stale EXEMPT entry → test is red → remove → test is green

### Documentation / skill

- [ ] `singularity-todo-desktop-compose-ui-tests` skill: add "Reading the failure bundle" section (steps.txt → annotated screenshot → tree.txt → kermit.log → db-state.txt), rule "use helpers not raw onNodeWithTag", new diagnostic flags
- [ ] Update KDoc `DesktopAppHarness.kt` lines 76-78 (forkEvery comment — now accurate)
- [ ] Finalize `docs/decisions/2026-10-04-desktop-test-failure-diagnostics.md`: all Debt items from retrospectives, runtime isolation measurements, updated FailureBundle table, Ultron decisions (what taken, what not, why, trigger for #94)
- [ ] Update `docs/decisions/2026-09-30-test-infra-known-gaps.md`: tree file now written (remove from gaps)
- [ ] Run `./scripts/refresh-decisions-digest.sh`

### Final checks

- [ ] `python3 scripts/find-unwired-surfaces.py` — zero new unwired surfaces
- [ ] `openspec validate --all --strict` — exit 0, no new findings
- [ ] Archive: `openspec archive desktop-test-failure-diagnostics`
- [ ] `./gradlew :shared:jvmTest :desktopApp:test` — green
- [ ] `just lint` — green
