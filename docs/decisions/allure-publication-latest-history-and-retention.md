---
title: "Define Allure publication, latest-report, history and retention contract"
date: 2026-11-10
status: open
tags: ["ci", "allure", "github-pages"]
---

## Context

The Allure report is generated in the build and published to GitHub Pages on
`main` (`.github/workflows/ci.yml`, `docs/decisions/2026-11-06-the-allure-report-is-generated-in-the-build.md`,
`docs/decisions/2026-11-10-the-published-tree-carries-no-git-metadata.md`). The
tracking issue `#2756` ("Stabilize Allure reports publication and GitHub Pages
lifecycle") records confirmed problems: concurrent publications can overwrite the
root landing page with an older run, fast and full test populations share one
Allure history so trends compare different populations, the Pages branch is
already large, and publication/state-selection logic is not specified as a
contract.

Issue `#2761` requires an agreement on the externally visible and operational
contract *before* workflow changes. This ADR records that contract; it does not
implement it. Downstream work (`#2764`, `#2766`, `#2767`, `#2769`) must not be
coupled to an unresolved decision recorded here.

**Current state (at the time of this decision):**

- `tests` uploads per-module raw results as `allure-results-${run_id}-${run_attempt}` (14-day retention),
  renders `build/reports/allure`, and uploads it as `allure-report-${run_id}-${run_attempt}` (14-day retention).
  `build/allure-results-merged` is produced in-job and consumed by report generation/Pages build but is not uploaded as a separate artifact.
- Pages publication runs only on `refs/heads/main` and `!cancelled()`: history is loaded from `gh-pages` as a tarball,
  `simple-elf/allure-report-action@v1.15` builds from `build/allure-results-merged` into `allure-history`, and
  `peaceiris/actions-gh-pages@v4` publishes `publish_dir: allure-history` to `gh-pages` with defaults
  (`keep_files: false`, `force_orphan: false`, `exclude_assets: .github`).
- Reports are published at root-level `/<github.run_number>/` with a root `index.html` redirect and a single shared
  `last-history/`, so fast and full runs currently mix into one trend/history namespace.

## Idea

Options considered for each area of the contract:

1. **Latest ordering.** Compare by wall-clock finish time, by `run_id`, or by a deterministic CI key.
   Finish time and `run_id` do not encode reruns/retries and can be observed out of order; `(run_number, run_attempt)`
   is monotonic per repository/run sequence and distinguishes reruns.
2. **Idempotency.** Key publications by mutable "latest" state only, or by a stable publication identity.
   A stable identity `(run_id, run_attempt)` allows safe retries without duplicate logical entries or latest regression.
3. **Population namespaces.** Keep one shared namespace with metadata labels, or give `fast` and `full` independent
   report/history namespaces. Shared history cannot represent comparable trends across different populations.
4. **Mixed history.** Attempt heuristic retroactive separation, or explicitly reset the mixed history.
   Fast/full provenance is not reliably separable from existing mixed history, so reset is the only honest migration.
5. **Root URLs.** Preserve `/<run_number>/` as the canonical location, make it legacy-only, or redirect/replace it.
   Compatibility matters for existing links, but the new contract must not depend on the old flat layout.
6. **Retention.** Keep unbounded Pages history, or bound Pages per population while relying on artifacts for the full
   per-run report within artifact retention.
7. **Publication concurrency.** Rely on workflow concurrency alone, or define an explicit publish serialization/staleness
   rule. GitHub Actions concurrency serializes/queues/cancels run admission but does not by itself guarantee that the
   latest pointer is monotonic if publications finish or are applied out of order.
8. **Recovery.** Treat `gh-pages` as authoritative state only, or define rebuild from a manifest plus retained artifacts
   with deterministic fallback when state is missing/corrupt.
9. **Compatibility.** Replace existing artifacts/consumers as part of publication changes, or preserve them unless a
   migration is explicitly specified.

## Decision

### 1. Latest report ordering

The deterministic latest-report key is the tuple **`(run_number, run_attempt)`** compared lexicographically:
a publication is newer iff `run_number` is greater, or `run_number` is equal and `run_attempt` is greater.

- Source: GitHub Actions `github.run_number` and `github.run_attempt` (integers, >= 1) of the publishing run.
- Missing or invalid metadata: if either value is absent, non-integer, or < 1, the publisher **must not** update any
  "latest" pointer/landing selection for that namespace. It may still publish the run's report directory as an immutable
  per-run publication only when the publication identity (decision 2) is valid and the target path is unambiguous;
  otherwise the publication step fails. A publication with invalid ordering metadata must never be treated as newer than
  a known latest.
- Ordering is evaluated **per population namespace** (decision 3). Concurrency does not establish ordering.

### 2. Idempotency

The publication identity is **`(run_id, run_attempt)`** (GitHub Actions `github.run_id`, `github.run_attempt`).

- A repeated publication of the same identity is a no-op at the logical level: it must not create duplicate manifest
  entries and must not regress any latest pointer.
- Re-publishing the same identity may overwrite/regenerate the same per-run report directory contents idempotently, but
  must leave manifest/latest state unchanged unless that identity is already the recorded latest by key (decision 1) and
  the recorded state is incomplete/corrupt for that same identity (recovery-safe repair).
- A different `run_attempt` of the same `run_id` is a different publication identity and is ordered by
  `(run_number, run_attempt)`.

### 3. Population and namespaces

Supported populations are **`fast`** and **`full`** (the repository's test populations; see
`config/test-populations.properties` and CI population selection). No other population value is valid for publication
unless this ADR is superseded.

Each population has an **independent report and history namespace** on Pages and in publication state:

- Per-run report directories, `last-history`/trend state, "latest" pointer, and manifest/state are scoped per population.
- Fast and full publications must never read or write each other's `last-history` or latest selection.
- The namespace layout (paths/subfolders) is an implementation detail of the follow-up issues, but the separation is a
  contract: one population's results/trends are never derived from the other population's history.

### 4. History migration (mixed history reset)

The currently mixed `last-history` (and any trend state derived from it) is **explicitly reset** when per-population
namespaces are introduced. It is not separated retroactively.

**User-visible effect:** existing trend/history continuity is lost at migration time. After migration, trend graphs and
history start fresh per population from publications produced under the new contract. Previously published per-run report
URLs that remain on the Pages branch remain viewable until removed by retention or explicit cleanup (decision 5/6), but
they are not backfilled into per-population histories.

### 5. Public URL compatibility

- **Legacy root-level per-run URLs** of the form `/<run_number>/` (e.g. `/2808/`) are **legacy-only**.
  - They remain available only while their directories still exist on the Pages branch (subject to retention/cleanup/migration).
  - They are not updated, not used as the canonical destination for new publications, and not guaranteed after bounded
    retention or a history reset/rebuild that does not preserve them.
- **New publications** are addressed within their population namespace (decision 3). Canonical "latest" and per-run links
  exposed to users must resolve within the correct population namespace.
- **Root landing behavior** (e.g. root `index.html`/`CNAME` handling) is part of the publication contract to be
  implemented: it must not regress latest selection (decision 1) and must not point users at a report from a different
  population than the one advertised. Any compatibility redirect from root/legacy paths must be documented in the
  implementing change; absence of a redirect is acceptable as long as legacy-only status is preserved as specified above.

### 6. Retention

- **GitHub Pages (per population):** keep the **20 most recent published per-run reports per population**, ordered by
  `(run_number, run_attempt)` (latest key). Older per-run report directories in that population namespace may be pruned
  during publication. `last-history`/trend state and the manifest/latest state for retained namespace state are kept as
  required to satisfy this contract; pruning must not make the recorded latest inconsistent.
- **Artifacts (current contract, preserved):** `allure-results-${run_id}-${run_attempt}` and
  `allure-report-${run_id}-${run_attempt}` remain uploaded with **14-day** retention and existing names/consumers unless
  a separate, explicitly specified migration changes them. Full per-run rendered reports and raw results are obtained from
  artifacts within that window; Pages is a bounded presentation layer, not the archive of every full report.
- Retention counts are per population namespace and apply to Pages publication state only; artifact retention is per run
  artifact as today.

### 7. Publication concurrency and stale-run behavior

- **Serialization:** publication writers that mutate the same Pages/publication state must be serialized (queued), not
  left to race. A concurrency group dedicated to publication of the affected state must be used by the implementation so
  only one writer mutates that state at a time. If queueing is used, it must be configured per GitHub Actions semantics
  (`queue: single` default, or `queue: max` for up to 100 pending runs) and **`queue: max` must not be combined with
  `cancel-in-progress: true`** in the same concurrency configuration. Queue order follows GitHub Actions admission/wait
  ordering and is not, by itself, the definition of "latest".
- **Stale-run behavior:** a run is stale for "latest" selection if its `(run_number, run_attempt)` is not greater than the
  currently recorded latest key for the same population namespace. A stale run **must not** update the latest
  pointer/landing selection or regress published "latest" state, even if it finishes publication later.
- **Idempotent replays:** a run whose identity `(run_id, run_attempt)` is already recorded is handled per decision 2.
- **Non-latest publications:** a non-stale but not-yet-latest-eligible publication attempt that cannot become latest must
  not overwrite a newer latest; implementation must compare against current recorded state before committing latest.
- Concurrency serializes writers; it does not establish latest-report ordering. Ordering is always decided by
  `(run_number, run_attempt)`.

### 8. Recovery

If the `gh-pages` branch, manifest, or publication state is lost or corrupted, recovery is performed as follows:

1. **Quarantine/assess:** stop trusting the published "latest" pointer and manifest until validated. Preserve any
   recoverable branch state for inspection.
2. **Recreate publication branch/state:** create or reset the Pages branch to a valid empty-or-minimal publication tree
   that satisfies the namespace contract (per-population roots, history placeholders, manifest/state file if used).
3. **Rebuild from retained sources:** for runs whose `allure-results-*` and/or `allure-report-*` artifacts are still
   within artifact retention (14 days), republish in ascending `(run_number, run_attempt)` order per population,
   applying the same ordering, idempotency, and retention rules. The merged results directory required by the report
   action/build can be reconstructed from retained results artifacts or from an uploaded merged-results artifact if the
   implementation provides one.
4. **If artifacts are unavailable:** rebuild manifest/latest state from the per-run report directories that still exist on
   Pages (if any), treating directory names/embedded metadata only when trustworthy; otherwise reset that population's
   history/latest to an empty state and record the reset. Do not fabricate history or infer ordering without evidence.
5. **Verify:** confirm per-population latest pointers reference the highest valid `(run_number, run_attempt)` present,
   no duplicate manifest identities exist, histories are namespace-isolated, and legacy URLs remain only as specified.
6. **Record:** the recovery action and its user-visible effect (possible history/reset/report loss) must be documented in
   the implementing PR/issue or an operational note.

### 9. Compatibility

- Existing Allure artifacts and their current consumers are preserved unless a migration is explicitly specified in a
  follow-up ADR/issue: `allure-results-${run_id}-${run_attempt}`, `allure-report-${run_id}-${run_attempt}`, their paths,
  and 14-day retention remain unchanged by this contract.
- `build/allure-results-merged` remains the build/CI workspace input for report generation and publication. It **can** be
  uploaded and downloaded as a separate artifact without changing existing evidence artifacts (a distinct artifact name/path
  can be added later); this ADR does not require or implement that change.
- Workflow/job names, gates, and unrelated artifact names remain unchanged by publication-contract work unless separately
  decided.

## Investigation (required)

Verified against upstream sources (2026-11-10):

- **`simple-elf/allure-report-action@v1.15`** (`action.yml`, `entrypoint.sh`, README, release tag commit
  `385367cde61ac68c2568a117f7579f7f148dc459`): inputs include `allure_results`, `allure_report`, `gh_pages`,
  `allure_history`, `subfolder` (default empty), `keep_reports` (default `20`), `github_run_num` (default
  `github.run_number`), `report_url`, `report_name`. Behavior: copies `gh_pages` → `allure_history` (including `.git` if
  present), nests under `subfolder` when set, prunes old numeric report directories when count exceeds the keep threshold
  (preserves `CNAME`, removes `index.html`/`last-history` then deletes oldest sorted numeric dirs), writes root
  `index.html` as a redirect to `${site}/${github_run_num}/` (or report URL equivalent), writes `executor.json` with
  `buildOrder`/report URL based on run number, copies previous `gh_pages/last-history` into results `history`, runs
  `allure generate --clean`, copies the generated report to `${allure_history}/${github_run_num}` and generated history to
  `${allure_history}/last-history`. Per-run directory naming and executor ordering use `github.run_number` (not
  `run_attempt`, not `run_id`); history is a single `last-history` per published tree/subfolder.
- **GitHub Actions concurrency** (official docs, "Control the concurrency of workflows and jobs"): at most one running and,
  by default, one pending job/run per concurrency group; `cancel-in-progress: true` cancels running/pending runs per
  configuration. `queue` supports `single` (default, one pending; new pending replaces existing pending) and `max` (up to
  100 pending; additional runs canceled when full). `queue: max` cannot be combined with `cancel-in-progress: true`.
  Queued runs are processed in order based on when each run started waiting on the concurrency group. Concurrency controls
  admission/serialization, not publication-state correctness or latest-key monotonicity.
- **`peaceiris/actions-gh-pages@v4`** (`action.yml`, `src/git-utils.ts`, README): defaults `publish_branch=gh-pages`,
  `publish_dir=public`, `keep_files=false`, `force_orphan=false`, `exclude_assets=.github`. With `force_orphan=true` it
  creates an orphan branch and copies assets. Otherwise it clones the existing publish branch; with `keep_files=false` it
  runs `git rm -r --ignore-unmatch *` in the destination directory then copies `publish_dir` contents into it (replacing
  tracked contents in that destination scope) and applies `exclude_assets`; with `keep_files=true` it preserves existing
  files in the destination directory. `copyAssets` copies contents of `publish_dir`, removes `.git` from the copied source
  if present, and deletes excluded assets.
- **Merged results artifact compatibility:** `build/allure-results-merged` is a workspace directory produced by the Gradle
  merge step and consumed in the same `tests` job. It can be uploaded/downloaded with `actions/upload-artifact`/download
  as a separate artifact (distinct name/path) without changing existing `allure-results-*` or `allure-report-*` artifacts
  or their consumers. The current workflow does not upload it; adding such an artifact is a compatible, explicitly scoped
  implementation choice, not part of this ADR's required behavior.

## Rationale

- `(run_number, run_attempt)` is deterministic, human-comprehensible, and stable across publication retries for ordering,
  while `(run_id, run_attempt)` uniquely identifies an execution attempt for idempotency. Using run number for ordering and
  run id for identity avoids collisions and makes reruns representable.
- Per-population namespaces are required because fast and full suites are different measurements; a shared trend/history
  conflates them and makes regressions ambiguous.
- Resetting mixed history is preferred over retroactive separation because historical runs cannot be reliably attributed to
  a population after the fact; an explicit reset makes the user-visible discontinuity honest.
- Bounding Pages to a small per-population window keeps the branch/repository cost predictable while artifacts remain the
  source for full per-run evidence within their retention window.
- Serializing writers and enforcing monotonic latest selection are complementary: queue/concurrency prevents races, but
  only key comparison prevents stale publications from regressing "latest".
- Preserving current artifacts/consumers avoids breaking existing evidence flows while the publication contract evolves.

## Consequences

- Follow-up implementation issues (`#2764`, `#2766`, `#2767`, `#2769`, `#2768` as applicable) must implement this contract
  without changing test populations or weakening existing CI gates.
- Publication implementation must maintain a per-population manifest/state sufficient to enforce latest ordering
  `(run_number, run_attempt)` and idempotency `(run_id, run_attempt)`.
- Introducing per-population namespaces requires a one-time reset of mixed `last-history`; users will see trend/history
  restart per population at migration.
- Existing root-level `/<run_number>/` URLs become legacy-only and may disappear under retention/rebuild; new canonical
  links live in population namespaces.
- Pages size is bounded by retaining 20 reports per population (plus namespace state/history/manifest as needed); older
  reports remain available only via artifacts within 14 days unless separately archived.
- If `simple-elf/allure-report-action` behavior is insufficient for manifest/idempotent/monotonic latest handling, the
  implementation must add the required control around it rather than relying on the action's default root redirect/history
  semantics alone.

## Links

- Issues: [#2756](https://github.com/gazon1/sing/issues/2756), [#2761](https://github.com/gazon1/sing/issues/2761),
  [#2759](https://github.com/gazon1/sing/issues/2759), [#2764](https://github.com/gazon1/sing/issues/2764),
  [#2766](https://github.com/gazon1/sing/issues/2766), [#2767](https://github.com/gazon1/sing/issues/2767),
  [#2768](https://github.com/gazon1/sing/issues/2768), [#2769](https://github.com/gazon1/sing/issues/2769)
- Workflow/build: `.github/workflows/ci.yml`, `build.gradle.kts`
- Prior decisions: `docs/decisions/2026-11-06-the-allure-report-is-generated-in-the-build.md`,
  `docs/decisions/2026-11-10-the-published-tree-carries-no-git-metadata.md`
- Upstream verification (2026-11-10): `simple-elf/allure-report-action@v1.15` (`action.yml`, `entrypoint.sh`, README,
  release notes), `peaceiris/actions-gh-pages@v4` (`action.yml`, `src/git-utils.ts`, README),
  GitHub Docs "Control the concurrency of workflows and jobs" (`queue: single|max`, queue+cancel-in-progress restriction)
