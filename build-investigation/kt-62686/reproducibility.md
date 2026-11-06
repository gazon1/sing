# Stage 1 — the `:shared` A/B: `in-process` vs `daemon`

Question: does the compiler execution strategy explain the `KT-62686` fallback, as
`gradle.properties:77-83`, ADR `2026-11-05-the-koin-plugin-forced-the-aggregator-compile-to-rerun`
(`:176-213`) and ADR `2026-10-22-gradle-memory-budget` (2026-11-05 amendment) say — or does
`baseline.md` have it right with "both `KT-62686` — `in-process` is not the trigger"?

Two independent cycles, each with its own settle state and the same one-file body edit. A settle run
under one strategy followed by a measured run under the other measures three things at once and could
not settle this.

## Input

`edit-shared.patch` — one behaviour-preserving edit inside a private function body:

```diff
     private fun redact(text: String): String {
+        val patterns = REDACTION_PATTERNS
         var result = text
-        for ((pattern, replacement) in REDACTION_PATTERNS) {
+        for ((pattern, replacement) in patterns) {
             result = pattern.replace(result, replacement)
         }
```

No signature, no visibility, no call site changes; `shared/src/commonMain/…/core/log/RedactingLogWriter.kt`.

## Commands

For `<strategy>` in `in-process`, then `daemon`:

```bash
./gw :shared:compileKotlinJvm --rerun-tasks --no-build-cache -Pcoverage.enabled=false \
  -Pkotlin.build.report.output=file -Pkotlin.compiler.execution.strategy=<strategy> --info
./gw :shared:compileKotlinJvm --no-build-cache -Pcoverage.enabled=false \
  -Pkotlin.build.report.output=file -Pkotlin.compiler.execution.strategy=<strategy> --info   # must be UP-TO-DATE
git apply --check build-investigation/kt-62686/edit-shared.patch
git apply build-investigation/kt-62686/edit-shared.patch
./gw :shared:compileKotlinJvm --no-build-cache -Pcoverage.enabled=false \
  -Pkotlin.build.report.output=file -Pkotlin.compiler.execution.strategy=<strategy> --info   # measured
git apply -R build-investigation/kt-62686/edit-shared.patch
```

The forced first run is required: `--no-build-cache` keeps Gradle's up-to-date check, so a "settle"
command can return `UP-TO-DATE` without ever invoking the compiler. The second run of each cycle is
the settle confirmation — and for `daemon` it also warms the Kotlin daemon, so its startup cost lands
on the forced run rather than on the measurement.

## Results

| Cycle | Strategy | Forced run | Settle | Measured run | Strategy line | Verdict |
|---|---|---|---|---|---|---|
| A | `in-process` | 141 s | `UP-TO-DATE` (4 s) | 64 s wall / **62.028 s** task | `Finished executing kotlin compiler using IN_PROCESS strategy` | **non-incremental**, `KT-62686` |
| B | `daemon` | 93 s | `UP-TO-DATE` (1 s) | 54 s wall / **53.384 s** task | `Compiling using the daemon strategy`, `found daemon on port 17558 (98285 ms old)` | **non-incremental**, `KT-62686` |

Both measured runs:

- `Task ':shared:compileKotlinJvm'` executed (not `UP-TO-DATE`), `:core-database:kspKotlinJvm`
  `UP-TO-DATE`, `:core-database:compileKotlinJvm` `UP-TO-DATE`;
- `Classpath changes info passed from Gradle task: NoChanges`;
- `Source changes: Known(modified=[…/core/log/RedactingLogWriter.kt, …])` — the edit, and nothing else;
- `Non-incremental compilation will be performed: Incremental compilation might be incorrect (KT-62686)`;
- `REBUILD_REASON: Incremental compilation might be incorrect (KT-62686)`;
- 1,237 `.kt` files in the compile iteration (the whole `:shared` JVM source set).

Evidence: `report-extracts/cycleA-in-process-shared-summary.txt`,
`report-extracts/cycleB-daemon-shared-summary.txt`, logs in `logs/cycle{A,B}-*.log`.

## Verdict

`daemon` does **not** remove the fallback. It makes the same non-incremental compile faster
(53.4 s against 62.0 s), which is the shape the older measurements recorded as "47–60 s vs
67–99 s" — those runs differ in speed, not in kind.

`baseline.md` was right; the ADR and the `gradle.properties` comment are wrong. The strategy is
therefore not the lever, and the memory-budget trade-off (`daemon` needs a Kotlin daemon JVM beside
the 6 GB Gradle daemon) does not have to be re-opened for this symptom.

The earlier probe result that a "bare KMP" module is "incremental under `daemon` and
non-incremental under `in-process`" did not reproduce: `module-probes.md` measures the bare KMP
module as non-incremental under both. It is not established why; a plausible difference is that the
earlier probe was measured without a fresh forced compile, but that is not proven.
