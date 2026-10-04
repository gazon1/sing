# desktop-test-failure-diagnostics

## What

Enrich the FailureBundle emitted when a desktop UI flow test fails so that every failure is diagnosed from the bundle alone, without re-running the test.

Four improvements over the current state (FailureBundle: screenshot.png + db-state.txt + kermit.log):

1. **Step recorder.** Every helper action (`awaitTag`, `tapTab`, `openDrawer`, etc.) is recorded as a named step with a timestamp, duration, and pass/fail status. On failure the step log is written to `steps.txt` in the bundle and appended to the failure message as a suppressed exception. A step also carries a `detail` field (e.g. the testTag being awaited) so the failing step is self-describing.

2. **Semantic tree as a file.** The semantics tree is already attached to the failure as a suppressed exception; this change writes it to `tree.txt` inside the bundle directory so it is available without parsing the JUnit XML report.

3. **Timeout diagnostics.** When `awaitTag` times out, the error message gains the number of poll attempts, wall-clock elapsed time, and the last caught exception — distinguishing "element never appeared" from "element appeared but check kept failing". Each helper also preserves the original waitUntil exception as a suppressed exception instead of discarding it.

4. **Runtime isolation.** Desktop tests run with `forkEvery = 1`, `mode.default = same_thread`, and `mode.classes.default = same_thread` so that each test class executes in an isolated JVM process and Kermit logs / FailureBundle directories cannot be corrupted by concurrent tests in other classes.

## Why

A failing desktop flow test currently requires re-running with diagnostic flags to understand what happened. The failure bundle is incomplete: it has no step history, no tree file, no timeout detail, and — because tests share a JVM — Kermit logs from concurrent tests can overwrite each other. This makes CI post-mortems slow and often inconclusive.

The five Ultron ideas from the internal talk (step as report unit, unified interception point, retry-until-timeout, visual element binding, soft assertions) were evaluated in `docs/decisions/2026-09-30-ultron-ideas-evaluation.md`. Three are implemented here (step recording, unified interception, timeout diagnostics); two are out of scope (soft assertions — low value, Allure — no JVM target; both revisited when issue #94 on the ultron tracker is closed).

## How

### StepRecorder

`StepRecorder` is a simple `ArrayList<StepRecord>` where each record carries `(offsetMs, name, detail, durationMs, ok, errorLine)`. It is stored in a `ThreadLocal<StepRecorder?>` so it is available inside any helper call without threading confusion. The harness creates it before the test body and clears it in `finally`.

`step(name, detail, block)` wraps any helper call:

```kotlin
inline fun <T> DesktopComposeUiTest.step(
    name: String,
    detail: String? = null,
    block: () -> T,
): T {
    val recorder = currentRecorder
    val start = System.currentTimeMillis()
    return try { block().also { recorder?.recordOk(name, detail, elapsed(start)) } }
    catch (t: Throwable) { recorder?.recordFail(name, detail, elapsed(start), t); throw t }
}
```

All seven helpers (`awaitTag`, `awaitTagGone`, `awaitAnyDisplayed`, `tapTab`, `openDrawer`, `goBack`, `assertCurrentTab`) are wrapped. The `detail` field is the tag or matcher string — no regex extraction needed.

`StepRecorder.format()` produces the `steps.txt` format:

```
+0.00s  awaitTag(project_detail_quick_add)    OK    0.12s
+0.13s  tap(FAB_CREATE_PROJECT)               OK    0.03s
+0.16s  awaitTag(project_detail_quick_add)    FAIL  5.00s  Tag not found after N polls / X ms
```

`StepRecorder.summary(last = 3)` returns the trailing lines for the suppressed exception message.

### FailureBundle changes

`capture(..., steps: StepRecorder?)` — one new nullable parameter. Writes:
- `steps.txt` (own `runCatching`; empty when no steps recorded, not absent)
- `tree.txt` = existing `dumpSemantics()` output
- Both added to `addSuppressedTo` list

Suppressed exception on the failure: `"Last steps:\n" + summary`.

Stale KDoc in `FailureBundle.kt` lines 28-32 ("semantics tree dumps are not captured in this version") is corrected.

### Timeout diagnostics

`awaitTag(tag, timeoutMs)` gains a poll counter and elapsed timer. The `catch (_: Throwable)` block now preserves the original exception as suppressed and formats the message as:

```
Tag 'X' is not in the semantics tree after N polls / X ms (timeout Y ms).
Last exception: <class>: <message>
```

`awaitTagGone` and `awaitAnyDisplayed` receive the same enrichment.

### Runtime isolation

`desktopApp/build.gradle.kts` — three changes to the JUnit Platform configuration:

```
systemProperty("junit.jupiter.execution.parallel.mode.default", "same_thread")
systemProperty("junit.jupiter.execution.parallel.mode.classes.default", "same_thread")
forkEvery = 1
```

`maxParallelForks = 2` is added (governs how many forked JVMs run concurrently; with `forkEvery = 1` and `same_thread` on both axes the practical concurrency is 1 regardless of this value, but the setting is kept for observability).

Dead parameter `attempt: Int = 1` removed from `runDesktopAppTest`.

Gate: duration of `./gradlew :desktopApp:test` is measured before and after. If suite duration grows by more than 25%, the configuration degrades to methods-only (`mode.default = same_thread` only, classes remain `concurrent`) and the remaining cross-class Kermit log mixing is documented as a known limitation in the ADR.

### Annotated screenshot (separate MR)

`SemanticOverlay.kt` draws bounding-box overlays onto `captureToImage()` using `Graphics2D`. Nodes with `TestTag` are rendered with gray boxes and tag labels; the target tag and its nearest candidates (by `explainMissingTag` logic) are highlighted in red. Output is `screenshot-annotated.png`. A text fallback `nodes.txt` (tag / text / contentDescription / Selected / bounds) is always written regardless of screenshot success.

## Not in scope

- Allure or any external reporting tool (no JVM target — revisit if ultron issue #94 closes)
- Soft assertions (`assertAll`) — deferred, low value
- Global `@Timeout` configuration — separate follow-up
- Changes to the production app (no production code touched)

## References

- `docs/decisions/2026-09-30-ultron-ideas-evaluation.md` — Ultron ideas evaluation (why Allure and soft assertions are deferred)
- `docs/decisions/2026-09-30-desktop-test-diagnostics.md` — FailureBundle rationale (current state)
- `docs/decisions/2026-09-30-test-infra-known-gaps.md` — known gaps (tree not written as file)
- `docs/decisions/2026-10-04-desktop-test-failure-diagnostics.md` — this change (created during MR-0, updated each phase)
- `docs/agents/issue-tracker.md` — GitHub Issues tracker
