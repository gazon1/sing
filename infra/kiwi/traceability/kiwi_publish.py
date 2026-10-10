"""Kiwi publish: ``results.json`` → Kiwi execution history.

Kiwi is a **projection**, never a gate. The matrices are already written by the
time this runs, and a Kiwi outage must degrade reporting without turning a
green build red or "unknown". Every failure path here therefore exits non-zero
*loudly* but the surrounding CI step is documented as non-blocking, and a
missing run never renders as a pass.

## One run per (commit, target) — the rule that makes the model work

Kiwi's model puts several ``TestExecution``s under a *single* ``TestCase``, which
is exactly what is needed for "Android passed, Desktop failed" to be visible as
two facts about one scenario. It only works if the two targets land in
*different* ``TestRun``s: two executions in one run are the same test observed
once, and a "last execution wins" reader would report whichever was written
last, losing the per-tier status entirely. So the run summary is keyed on
``(commit, target)`` and publishing is idempotent on that key.

## Outcome → Kiwi status, in one table

The official ``kiwitcms-junit.xml-plugin`` maps a skipped test to ``WAIVED``
while this codebase's ``sync.py`` has always mapped it to ``IDLE``. That
divergence is a deliberate, recorded difference rather than something to "fix"
in two places, so it lives in exactly one table below where a reader can see it.
"""

from __future__ import annotations

import sys
from dataclasses import dataclass
from pathlib import Path

from traceability.coverage import Outcome
from traceability.links import Link
from traceability.normalize import NormalisedResult, read_results
from traceability.spec import ScenarioSpec, Target

# ``kiwi_client`` is a sibling of the ``traceability`` package in the same
# directory.  Python resolves a relative import (``from .name``) by looking
# for ``sys.modules["<parent>.<name>"]``; importing it with its full package
# name first guarantees that entry exists and the relative lookup succeeds.
import infra.kiwi.kiwi_client as kiwi_client
from infra.kiwi.kiwi_client import KiwiClient, KiwiError

__all__ = ["OUTCOME_TO_KIWI", "run_publish", "build_runs", "RunBatch"]

#: The one place the outcome vocabulary is translated. `error` never appears:
#: normalisation has already folded it into `failed`, because an infrastructure
#: error must not be laundered into a distinct, quieter status.
OUTCOME_TO_KIWI = {
    Outcome.PASSED.value: "PASSED",
    Outcome.FAILED.value: "FAILED",
    # Plugin says WAIVED, sync.py says IDLE. Recorded, not reconciled.
    Outcome.SKIPPED.value: "WAIVED",
    # A claimed target with no result must never be written as a pass. It is
    # simply not published: absence in Kiwi is the honest representation, and
    # `not-run` is stated in the result matrix instead.
    Outcome.NOT_RUN.value: None,
    # MISSING: CI claimed the target and attempted it, but this scenario's flow
    # was never executed. Like NOT_RUN, not published to Kiwi — the matrix is
    # the honest record of what CI reported.
    Outcome.MISSING.value: None,
}

PLAN_NAME = "Scenarios"


@dataclass(frozen=True, slots=True)
class RunBatch:
    """One ``TestRun``'s worth of results: a single (commit, target) pair."""

    commit: str
    target: Target
    rows: tuple[NormalisedResult, ...]

    @property
    def summary(self) -> str:
        # The summary is the run's identity in the UI and the key `prune.py`
        # and the gaps gate read. Naming the target explicitly is what stops an
        # Android run and a Desktop run from being mistaken for each other.
        return f"traceability {self.commit} {self.target.value}"


def build_runs(results: list[NormalisedResult], commit: str) -> list[RunBatch]:
    """Group results into one batch per (commit, target).

    Pure, so the grouping rule — the thing the whole model rests on — is
    testable without a stand.

    A row whose commit differs from ``commit`` is a hard error rather than being
    bucketed under its own commit. Silently honouring the row would let a
    mixed-vintage file publish under a name that lies about half of it, and the
    symptom (a run summary that disagrees with its executions) only shows up
    later, in Kiwi, where it is hard to trace back.
    """
    mismatched = sorted({row.commit for row in results if row.commit != commit})
    if mismatched:
        raise ValueError(
            f"результаты относятся к коммитам {mismatched}, а публикуется {commit}: "
            f"смешанный файл результатов публиковать нельзя"
        )
    # An unrecognised outcome is an error, not a skip. Treating "not-run" and
    # "any other string" alike meant a typo, a renamed Outcome member or a
    # hand-edited results.json published nothing and still reported success —
    # a green exit code over an empty publish.
    unknown = sorted({row.outcome for row in results if row.outcome not in OUTCOME_TO_KIWI})
    if unknown:
        raise ValueError(
            f"неизвестный исход {unknown}; допустимы {sorted(OUTCOME_TO_KIWI)}"
        )
    buckets: dict[tuple[str, Target], list[NormalisedResult]] = {}
    for row in results:
        if OUTCOME_TO_KIWI[row.outcome] is None:
            continue  # not-run: absence in Kiwi is the honest representation
        buckets.setdefault((row.commit, Target(row.target)), []).append(row)
    return [
        RunBatch(commit=bucket_commit, target=target, rows=tuple(rows))
        for (bucket_commit, target), rows in sorted(buckets.items(), key=lambda kv: (kv[0][0], kv[0][1].value))
    ]


def _find_case(client, plan_id: int, scenario: str) -> int | None:
    """Locate a seeded case by scenario id.

    Returns ``None`` rather than raising: a result for an unseeded scenario is a
    reporting gap, and a gap must not abort publishing of the scenarios that
    *are* seeded.
    """
    cases = client.get_cases(plan_id)
    if not cases:
        return None
    properties = client.get_cases_properties([case["id"] for case in cases])
    for case in cases:
        if properties.get(case["id"], {}).get("scenario_id") == scenario:
            return case["id"]
    return None


def _existing_executions(client, run_id: int) -> dict[int, int]:
    """``{case_id: execution_id}`` already recorded in this run.

    Reusing a run is not enough for idempotency: without this a second publish
    of the same ``results.json`` appends a *duplicate* execution for the same
    case, and a "last execution wins" reader then reports whichever the publish
    order favoured rather than what actually happened.
    """
    rows = client.call("TestExecution.filter", {"run": run_id}) or []
    return {row["case"]: row["id"] for row in rows if row.get("case") is not None}


def _set_execution_status(client, execution_id: int, status: str) -> None:
    """Overwrite an execution's status.

    This exists because the obvious alternative — skip a case that already has
    an execution in the run — is **wrong**, and dangerously so. A commit is
    commonly re-tested: the same HEAD that passed yesterday fails after a
    dependency bump, and `commit` defaults to the current HEAD. Skipping would
    keep the old ``PASSED`` in Kiwi while the result matrix honestly said
    ``❌``, i.e. it would preserve a pass where the truth is a failure.

    So an existing execution is *updated*, not skipped. Re-publishing unchanged
    results stays idempotent (the status written is the same), and re-publishing
    changed results becomes correct instead of stale.
    """
    client.update_execution(execution_id, status)


def _find_or_create_run(client, plan: dict, product_id: int, batch: RunBatch) -> dict:
    """Reuse the run for this (commit, target) if it exists, else create it.

    Reuse is what makes republishing safe: a second run with the same summary
    would double every execution in the history.
    """
    for run in client.get_runs(plan["id"]):
        if run.get("summary") == batch.summary:
            return run
    build = _plan_build(client, plan, batch)
    return client.create_run(
        plan_id=plan["id"],
        build_id=build["id"],
        summary=batch.summary,
    )


def _plan_build(client, plan: dict, batch: "RunBatch") -> dict:
    """The Build a run in ``plan`` may use, or a loud failure.

    Deliberately does **not** create a Build. ``kiwi_client.ensure_build`` falls
    through to creating one under a ``Version`` named after the commit, whose id
    is not the plan's ``product_version`` — and ``NewRunForm`` constrains builds
    by exactly that field, so every later ``TestRun.create`` would fail with
    "Select a valid choice." and publishing would be permanently broken rather
    than degraded.

    The commit does not need to live in the Build dimension: the run *summary*
    is the (commit, target) identity this system joins on, and that is what
    makes the history queryable per commit.
    """
    compatible = client.get_plan_builds(plan)
    if not compatible:
        raise RuntimeError(
            f"у плана '{plan['name']}' нет ни одного активного Build — создать прогон "
            f"нельзя, а создать Build под произвольную Version можно (и он потом "
            f"не пройдёт валидацию формы прогона)"
        )
    return compatible[0]


def run_publish(
    results_path: Path,
    commit: str | None = None,
    dry_run: bool = False,
) -> int:
    """Publish normalised results as Kiwi execution history.

    Takes only the results file: scenario specs and links are already baked
    into ``results.json`` by normalisation, and re-deriving them here would mean
    a second, silently-diverging copy of the same mapping.
    """
    if not results_path.exists():
        print(f"✗ нет файла результатов: {results_path}", file=sys.stderr)
        print("  (сначала: just trace-results)", file=sys.stderr)
        return 1

    file_commit, results = read_results(results_path)
    effective = commit or file_commit
    if not effective:
        print("✗ результаты не несут коммита — публиковать нечего", file=sys.stderr)
        return 1
    if commit and file_commit and commit != file_commit:
        # Staleness is decided by commit identity: a report for another commit
        # would attach this commit's name to that commit's results.
        print(
            f"✗ результаты за коммит {file_commit}, а публикуется {commit}: отказ",
            file=sys.stderr,
        )
        return 1

    try:
        batches = build_runs(results, effective)
    except ValueError as exc:
        print(f"✗ {exc}", file=sys.stderr)
        return 1
    if not batches:
        print("! нет результатов для публикации (все цели — not-run)")
        return 0

    for batch in batches:
        print(f"  {batch.summary}: {len(batch.rows)} результат(ов)")

    if dry_run:
        print("(dry-run) ничего не записано")
        return 0

    try:
        client = KiwiClient()
        client.login()
        product = client.get_product("Singularity Todo")
        if product is None:
            # 1, not 3: the stand answered, it is simply not set up. Reporting
            # this as "unreachable" would point an operator at the network
            # instead of at the missing seed.
            print("✗ продукт 'Singularity Todo' не найден на стенде", file=sys.stderr)
            return 1
        plan = client.get_plan(product["id"], PLAN_NAME)
        if plan is None:
            print(
                f"✗ план '{PLAN_NAME}' отсутствует — сначала: just kiwi-seed",
                file=sys.stderr,
            )
            return 1

        published = skipped = updated_total = 0
        for batch in batches:
            case_ids: dict[str, int] = {}
            for row in batch.rows:
                if row.scenario not in case_ids:
                    case_id = _find_case(client, plan["id"], row.scenario)
                    if case_id is None:
                        print(f"  ! нет кейса для {row.scenario} — пропущено", file=sys.stderr)
                        continue
                    case_ids[row.scenario] = case_id

            run = _find_or_create_run(client, plan, product["id"], batch)
            existing = _existing_executions(client, run["id"])
            updated = 0
            sortkey = 10
            for row in batch.rows:
                case_id = case_ids.get(row.scenario)
                if case_id is None:
                    skipped += 1
                    continue
                status = OUTCOME_TO_KIWI[row.outcome]
                prior = existing.pop(case_id, None)
                if prior is not None:
                    # Re-testing the same commit is normal, and its outcome can
                    # have changed. Overwrite, never skip — skipping would keep
                    # a stale PASSED over a real failure.
                    _set_execution_status(client, prior, status)
                    updated += 1
                    continue
                client.add_execution(
                    run_id=run["id"],
                    case_id=case_id,
                    status=status,
                    comment=f"{row.scenario} @ {row.commit} ({row.carrier})",
                    # Explicit and mandatory in practice: TestRun.add_case writes
                    # sortkey=None, after which the next add to the same run
                    # raises. See kiwi_client.add_execution.
                    sortkey=sortkey,
                )
                sortkey += 10
                published += 1
            updated_total += updated
        print(f"✓ опубликовано {published}, обновлено {updated_total} (пропущено {skipped})")
        return 0
    except KiwiError as exc:
        # Loud, but not a build failure: reporting degraded, CI unaffected.
        print(f"✗ публикация в Kiwi не удалась: {exc}", file=sys.stderr)
        print(
            "  матрицы уже записаны; Kiwi — проекция, а не гейт. "
            "История Kiwi теперь неполна.",
            file=sys.stderr,
        )
        return 3
