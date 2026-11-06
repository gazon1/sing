---
title: "The Allure report is generated in the build, from results the suite already collects"
date: 2026-11-06
status: accepted
tags: ["tooling", "testing", "ci"]
---

# The Allure report is generated in the build, from results the suite already collects

## Context

Three modules already collect Allure results: the `allure-junit5` agent is on
the test classpath in `:shared`, `:desktopApp` and `:mcp-server`. CI already
uploaded the raw JSON as the `allure-results` artifact.

What nobody could do with it was **read** it. A pile of `*-result.json` files is
evidence, not a report: the per-test history, the retry grouping, the failure
clustering and the attachment viewer exist only in the rendered HTML. The
issue series that asked "can we have Allure?" was asking for something the
repository had been collecting and throwing away.

Two things stood in the way.

**The results were one directory level too deep.** `shared` and `mcp-server`
set the forked JVM's `workingDir` to `<module>/build/allure-results` and let
the listener resolve `allure-results` against it, so the files landed in
`build/allure-results/allure-results/`. Every upload glob, every report command
and the CI artifact path expected the shallower directory. The comment in
`shared/build.gradle.kts` explained this as a fact about the listener —
"allure-kotlin's `allure.results.directory` key is NOT read by allure-junit5's
listener" — and that explanation was **wrong**: `AllureLifecycle.getDefaultWriter()`
reads the key from the system properties
(`PropertiesUtils.loadAllureProperties()` is the classpath `allure.properties`
plus `System.getProperties()`), and falls back to a working-directory-relative
path only when the key is absent. `desktopApp` had been setting the property
all along, and its results were flat — which is why the layout looked like a
per-module accident rather than a bug.

**The generator does not recurse.** `allure generate <dir>` reads one
directory. Pointed at a tree of per-module directories it produces an **empty
report and exit code 0** — the quietest possible failure, and the reason a
naive "generate per module" solution looks like it worked.

The report generator itself was not available as a repository tool. The
standalone distribution is a GitHub release archive; the Maven artifact
`io.qameta.allure:allure-commandline` publishes a POM whose jar carries
`io.qameta.allure.CommandLine`, with the generation work in the transitive
`allure-generator`.

## Idea

- Generate the report where the results are already produced: in the build.
- Resolve the CLI as a Maven artifact through Gradle. No PATH assumption, no
  binary to install, no new toolchain pin — the build already resolves a
  classpath for everything else.
- Flatten the results into one directory before generating, because the
  generator reads exactly one directory.
- Fix the layout at the source: set `allure.results.directory` in the two
  modules that were relying on the working directory.

## Decision

**The layout.** `:shared` and `:mcp-server` set
`systemProperty("allure.results.directory", <module>/build/allure-results)`.
`workingDir` stays: the tests read files relative to the module, and the
working directory is what made those paths resolve. The property decides where
the **results** go; the working directory decides where the test's own relative
paths go. Two different questions.

**The task.** The root build has three tasks:

- `allureMergeResults` (a `Sync`) collects `*-result.json` and
  `*-attachment.*` from the three modules that carry the agent into
  `build/allure-results-merged/`, flattened. The flattening is not cosmetic:
  a file that stays one level down is a file the generator never sees.
- `allureRequireResults` fails the build when no module produced a result.
  It is a separate task rather than a `doLast` on the merge, because a
  `doLast` never runs when the merge is `NO-SOURCE` — and "no source" is
  exactly the case the check is for.
- `allureReport` (a `JavaExec`) runs `io.qameta.allure.CommandLine generate
  --clean -o build/reports/allure build/allure-results-merged` with the CLI
  resolved from the `allureCommandline` configuration.

The report task deliberately does **not** depend on the test tasks. It
aggregates the results that are on disk. A dependency would be actively
expensive: the report runs in its own Gradle invocation without
`-Ptest.population`, so the test tasks would be out of date and the report
would cost a second full suite.

**CI.** The `tests` job runs `./gradlew allureReport` after the suite and
uploads `build/reports/allure` as `allure-report-<run_id>`. The raw
`allure-results` artifact stays: the report is the reading of it, the JSON is
the evidence.

**The CLI version** lives in `gradle/libs.versions.toml` next to the agent
version, and is deliberately not the same number. The agent is on the test
classpath and cannot move without a test-suite change; the CLI only reads what
the agent wrote. Nothing requires the two to agree.

## Rationale

**The integration was already there.** The issue asked whether Allure could be
adopted. The measurement said the collection was done and the *rendering* was
missing — so the work was one task and one CI step, not an investigation.

**The Gradle-resolved CLI beats a downloaded binary.** The standalone
distribution is an archive to fetch, verify and keep on PATH; the Maven
artifact is a coordinate the build already knows how to resolve. It costs one
dependency entry and no toolchain pin — and the pins file
(`config/toolchain-versions.env`) has exactly one owner and exactly six keys,
which is not a place to add a seventh kind of thing.

**Measured, not assumed.** The official CLI 2.46.1 generated the report for a
two-test sample in 1.2–1.4 s. The `data/test-cases/*.json` files carried the
full `statusMessage`, the full `statusTrace` (including the `Caused by:` chain),
the nested steps and the attachments.

**The report must not re-run the suite.** A CI step that costs a second full
suite to draw a picture of the first one is a step that gets disabled.

**The empty-report trap is designed out.** Merging into one flat directory
means the generator is pointed at a directory whose contents it will read. The
failure mode that produced an empty report with exit code 0 — pointing it at a
tree — is no longer reachable from the build.

The other empty report — the generator pointed at a directory with *nothing* in
it — is reachable, and it is the quietest one of all: a complete-looking HTML
page with zero tests, exit code 0, uploaded to CI as `allure-report-<run_id>`.
A human opens it and reads "nothing ran" as "nothing failed". So a separate
task, `allureRequireResults`, fails the build when no module produced a
result, and `allureReport` depends on it.

The check counts the **source** directories, not the merged one. A `Sync` that
has no source does not clean its destination either, so a previous run's
results would still sit in `build/allure-results-merged/` and the merged
directory would look populated even though nothing ran now. Counting the
sources catches that too. The count is over all three modules together:
`mcp-server` legitimately contributes nothing (its only test class is
`@Tag("slow")`), so a per-module requirement would fail the build for a
module that is behaving correctly.

## Consequences

- CI produces a readable report per run; the raw results stay available.
- One new dependency: `io.qameta.allure:allure-commandline:2.46.1`, resolved
  through Gradle. The report is not generated by anything else, and nothing
  else depends on it.
- The merge task names the modules that carry the agent explicitly
  (`:shared`, `:desktopApp`, `:mcp-server`). A module that lost the agent
  would otherwise contribute nothing and the report would just be shorter.
- The build fails when no module produced a result, rather than producing an
  empty report that reads like a clean run. The check is the separate task
  `allureRequireResults`, over the source directories of all three modules.
- The Allure **agent** version (`2.35.5`) and the **CLI** version (`2.46.1`)
  differ. That is intended; see the decision above.
- **Rollback**: delete the three tasks and the CI step, and remove the
  `allure-commandline` entry from the catalogue. The results keep being
  collected and keep being uploaded — the report is an addition to them, not a
  replacement, so removing it costs the rendered HTML and nothing else.

## Links

- `build.gradle.kts` — `allureMergeResults`, `allureRequireResults`, `allureReport`
- `shared/build.gradle.kts`, `mcp-server/build.gradle.kts` — the results
  directory
- `gradle/libs.versions.toml` — `allure-commandline`
- `.github/workflows/ci.yml` — the `Allure report` step and the
  `allure-report-<run_id>` artifact
- `docs/engineering/test-reporting-audit.md` — the audit that found the
  missing report (finding F9, plan step P6)
