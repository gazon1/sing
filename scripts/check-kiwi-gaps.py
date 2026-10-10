#!/usr/bin/env python3
"""check-kiwi-gaps.py — fail when the number of never-run test cases rises.

## What this measures

`check-test-runs.py` guards "the tests ran" by comparing executed counts against
a floor. It cannot see the opposite failure: a test that was *written, tagged,
and skipped* still inflates the executed counts of everything else, so the run
looks healthy. `check-coverage.py` guards "the tests reached the code".

Neither can see a test class that exists in the repository and has **no recorded
run at all**. That is what this reads from the Kiwi stand (`infra/kiwi`): cases
with a TestCase and no TestExecution.

## Why the floor is per-plan, not a single number

One aggregate "47 never run" figure is not a floor — it moves for unrelated
reasons. A test moved from `shared` to `desktopApp` changes the total by zero
while changing both per-plan numbers, and a plan that is merely not scheduled
yet would be indistinguishable from a plan that regressed. Per-plan floors make
a move visible as two opposite changes instead of nothing.

## What a RISE means and does not mean

A rise is not automatically a failure and not automatically fine:

- fewer results recorded (stale `build/test-results`, a test task that was
  UP-TO-DATE and never rewrote its XML) — a tooling fault, not a coverage fault;
- a plan that legitimately has no runs yet — the floor is absent for it, see below.

The gate fails only on a **drop against the floor**, which is the one direction
that is unambiguous. It prints the delta on a rise so a human decides, rather
than failing on noise every time a test is added to a plan that runs rarely.

## Plans with no floor

A plan absent from the baseline is reported as `no floor` and skipped. Recording
a floor requires a legitimate run, and inventing one from an empty plan would
create a gate that fails forever or, worse, one recorded from a single run and
mistaken for a measured minimum.

## The limitation worth stating out loud

This gate is only as good as the freshness of the Kiwi data, and Kiwi is
populated by whoever runs `sync.py --results`. A stand that has not been synced
does not fail here — it reports stale data via `--max-age`. Until `kresults`
runs in CI (see the "not a gate yet" section in `docs/decisions/deferred-backlog.md`),
**this gate does not protect `main`**. It is a local instrument, and it is
called that so nobody treats a green run here as evidence about a merge.

## Usage

    scripts/check-kiwi-gaps.py                      # compare against the floor
    scripts/check-kiwi-gaps.py --if-present         # skip when the stand is down
    scripts/check-kiwi-gaps.py --update-baseline    # after adding tests
    scripts/check-kiwi-gaps.py --max-age HOURS      # fail on stale Kiwi data
"""

from __future__ import annotations

import argparse
import pathlib
import sys

REPO_ROOT = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT / "infra"))

BASELINE = REPO_ROOT / "config" / "docs" / "kiwi-gaps-baseline.txt"
PRODUCT = "Singularity Todo"

# Kiwi writes a run's summary as "gradle <sha> @ <UTC timestamp>"; gaps.py does
# not record when a case was last seen, so freshness is read off the newest run.
FRESH_MARKER = "gradle "


def _load_client():
    from infra.kiwi.kiwi_client import KiwiError, KiwiClient

    return KiwiClient(), KiwiError


def collect(client) -> tuple[dict[str, int], dict[str, int], str]:
    """{plan: never_run_count}, {plan: total_cases}, 'newest run summary'.

    "Never run" means **never executed in the life of the stand**, read from the
    `ever_run` property on the case — not "has no execution right now".

    The distinction is load-bearing. `TestRun.remove` cascades to the run's
    executions, so any measure based on live executions reports a case that ran
    six weeks ago as untested the moment a rotation lands. Measured on the
    stand: after `prune --keep 1` the never-run count went 47 → 237 with no
    test changing, because two old runs carrying 212 executions were deleted.
    A floor that a routine maintenance job trips is a floor that gets ignored.
    The property lives on the TestCase, which rotation does not touch.
    """
    from gaps import collect_case_inventory  # noqa: PLC0415

    product = client.get_product(PRODUCT)
    if product is None:
        raise KeyError(PRODUCT)

    inventory = collect_case_inventory(client, PRODUCT)
    ran = {
        case_id
        for case_id, case in inventory["cases"].items()
        if case["properties"].get("ever_run")
    }

    per_plan_never: dict[str, int] = {}
    per_plan_total: dict[str, int] = {}
    for case_id, case in inventory["cases"].items():
        plan = case["plan"]
        per_plan_total[plan] = per_plan_total.get(plan, 0) + 1
        if case_id not in ran:
            per_plan_never[plan] = per_plan_never.get(plan, 0) + 1

    newest = ""
    for plan in client.call("TestPlan.filter", {"product__id": product["id"]}) or []:
        for run in client.get_runs(plan["id"]):
            summary = run.get("summary", "")
            if summary > newest:
                newest = summary
    return per_plan_never, per_plan_total, newest


def parse_baseline() -> dict[str, int]:
    floors: dict[str, int] = {}
    if not BASELINE.exists():
        return floors
    for line in BASELINE.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        plan, _, value = line.rpartition(" ")
        if not plan:
            continue
        try:
            floors[plan] = int(value)
        except ValueError:
            continue
    return floors


def format_baseline(
    never: dict[str, int], total: dict[str, int]
) -> str:
    lines = [
        "# Never-run test cases per Kiwi plan, used as a ceiling by",
        "# scripts/check-kiwi-gaps.py. Format: <plan> <max-never-run>.",
        "#",
        "# Record the HIGHEST count any legitimate state produces, exactly as",
        "# test-runs-baseline.txt records the smallest executed count: a floor",
        "# regenerated on every failure is not a gate.",
        "#",
        "# A RISE in never-run cases is a tooling fault until shown otherwise —",
        "# stale build/test-results, or a test task that was UP-TO-DATE and did",
        "# not rewrite its XML. Investigate before lowering the number.",
        "#",
        "# A plan with no line here is skipped, not failed: it has no floor",
        "# because it has no legitimate run to record one from.",
        "",
    ]
    for plan in sorted(never):
        lines.append(f"{plan} {never[plan]}")
    return "\n".join(lines) + "\n"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--if-present", action="store_true",
                    help="exit 0 when the Kiwi stand is unreachable")
    ap.add_argument("--update-baseline", action="store_true",
                    help="rewrite the baseline from the current numbers")
    ap.add_argument("--max-age", type=float, default=None, metavar="HOURS",
                    help="fail if the newest Kiwi run is older than this")
    args = ap.parse_args(argv)

    client, KiwiError = _load_client()
    try:
        client.check_alive()
        never, total, newest = collect(client)
    except (KiwiError, KeyError, OSError) as exc:
        if args.if_present:
            print(f"check-kiwi-gaps: skip — stand unavailable ({exc})")
            return 0
        print(f"check-kiwi-gaps: FAIL — {exc}", file=sys.stderr)
        print("  start it with: just kiwi-start && just kiwi-wait", file=sys.stderr)
        return 1

    if not never:
        if args.if_present:
            print("check-kiwi-gaps: skip — no cases in Kiwi")
            return 0
        print("check-kiwi-gaps: FAIL — Kiwi has no cases; run sync.py --plan",
              file=sys.stderr)
        return 1

    if args.update_baseline:
        BASELINE.parent.mkdir(parents=True, exist_ok=True)
        BASELINE.write_text(format_baseline(never, total), encoding="utf-8")
        print(f"check-kiwi-gaps: baseline updated ({len(never)} plans)")
        for plan in sorted(never):
            print(f"  {plan} {never[plan]}")
        return 0

    # Freshness: a floor satisfied by a stand nobody has synced is the same
    # defect as a floor satisfied by a report nobody regenerated.
    if args.max_age is not None and FRESH_MARKER in newest:
        import datetime as dt
        import re

        m = re.search(r"(\d{4}-\d{2}-\d{2} \d{2}:\d{2}) UTC", newest)
        if m:
            stamp = dt.datetime.strptime(m.group(1), "%Y-%m-%d %H:%M").replace(
                tzinfo=dt.timezone.utc
            )
            age_h = (dt.datetime.now(dt.timezone.utc) - stamp).total_seconds() / 3600
            if age_h > args.max_age:
                print(
                    f"check-kiwi-gaps: FAIL — newest Kiwi run is {age_h:.0f}h old "
                    f"(limit {args.max_age:.0f}h): '{newest}'",
                    file=sys.stderr,
                )
                print("  run: just kresults", file=sys.stderr)
                return 1

    floors = parse_baseline()
    if not floors:
        print("check-kiwi-gaps: FAIL — no baseline. Run --update-baseline once "
              "against a legitimate, freshly synced stand.", file=sys.stderr)
        return 1

    regressions: list[str] = []
    notes: list[str] = []
    for plan in sorted(never):
        count = never[plan]
        if plan not in floors:
            notes.append(f"  {plan}: {count} never run — no floor, skipped")
            continue
        floor = floors[plan]
        if count > floor:
            regressions.append(
                f"  {plan}: {count} never run (floor {floor}, +{count - floor})"
            )
        elif count < floor:
            notes.append(f"  {plan}: {count} never run (floor {floor}, improved)")

    print(f"check-kiwi-gaps: {len(never)} plans, "
          f"{sum(never.values())} never-run cases of {sum(total.values())}")
    for note in notes:
        print(note)

    if regressions:
        print("check-kiwi-gaps: FAIL — more never-run cases than the floor:",
              file=sys.stderr)
        for line in regressions:
            print(line, file=sys.stderr)
        print("  A rise means results stopped arriving, not that coverage grew.",
              file=sys.stderr)
        print("  Check: is build/test-results current? did a test task go "
              "UP-TO-DATE? then: just kresults", file=sys.stderr)
        return 1

    print("check-kiwi-gaps: OK — no plan above its floor")
    return 0


if __name__ == "__main__":
    sys.exit(main())
