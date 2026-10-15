#!/usr/bin/env python3
"""Prove the lint gate is actually measuring, by planting a violation and reading the report.

## Why this exists

Three defects in one session, all the same shape, all found by hand rather than by a gate:

- **#136** — a stale `:detekt-rules` classpath made `:shared:detekt` report **green with no custom
  rules running**. Observed three times, twice as a false pass.
- **#137** — `:shared:detektBaseline` runs without the custom rule set, so regenerating the
  baseline deleted every custom-rule entry (357 → 338) and the next `detekt` run then reported a
  clean tree *because nothing was running*.
- **#138** — a coverage number that may not describe any execution at all.

The common failure is not "the check is wrong". It is **a measurement that cannot be
distinguished from its own failure.** A rule that stopped running and a codebase that stopped
violating produce the same green; a baseline that lost its entries and a baseline that never had
any produce the same diff.

The cure is the same in all three cases and it is cheap: **plant a violation, assert it is
reported, and run that before trusting a green.**

`RuleFiresSmokeTest` is the existing rule-level version of this. It is not enough on its own,
because it constructs the rule directly — which is precisely what keeps passing when the *rule
classloader* is the broken part. This script goes through the real `detekt` task, the real
plugin classpath, and the real report file.

## What it does, and what it refuses to do

For each probe: `git worktree add --detach` creates a disposable worktree in a temp
directory, the probe file is planted inside it, `:shared:detekt` is run there (with
`./gw` for GRADLE_USER_HOME isolation), and the worktree is removed when done.

The live tree is never touched. Git identity is isolated per-probe via `GIT_CONFIG_*`
parameters, so a global `core.hooksPath` does not fire the repository's hooks inside
the worktree. A probe that does not fire is a failure — not a pass — because a probe
that cannot fail is the thing this script exists to catch.

## Why it is slow, and why that is fine

Each probe is a full `:shared:detekt` run (~1.5 min). Running all of them costs minutes, which is
why this is a `just` recipe and not part of `check.sh`: the person who needs it is the one about
to regenerate a baseline or trust a green lint run, and they need it *then*, not on every commit.

The probes are additive, so `--only NAME` runs one.
"""

from __future__ import annotations

import argparse
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = pathlib.Path(__file__).resolve().parent.parent
GRADLEW = ROOT / "gw"

# A file under commonMain that no rule inspects structurally, so adding a probe to it cannot
# change any other rule's verdict. The package is real so the code would compile if it stayed.
PROBE_DIR = ROOT / "shared/src/commonMain/kotlin/com/singularity/todo/core/observability"


class Probe:
    """One planted violation, and what the report must then say about it."""

    def __init__(self, name: str, rule_id: str, body: str, why: str) -> None:
        self.name = name
        self.rule_id = rule_id
        self.body = body
        self.why = why

    @property
    def path(self) -> pathlib.Path:
        return PROBE_DIR / f"GateHonestyProbe.{self.name}.kt"


PROBES = [
    Probe(
        name="appErrorCode",
        rule_id="AppErrorCode",
        body=(
            "package com.singularity.todo.core.observability\n"
            "\n"
            "import com.singularity.todo.core.error.AppError\n"
            "\n"
            "/** TEMPORARY probe planted by scripts/check-gate-honesty.py. */\n"
            "fun gateHonestyProbe() {\n"
            '    throw AppError.Validation("planted by the gate-honesty check")\n'
            "}\n"
        ),
        why=(
            "a custom rule that stopped loading. The whole point of this probe is that it is a "
            "CUSTOM rule: it fails if the plugin classpath is stale or missing, which is #136's "
            "exact failure mode and the one that produces a silent green."
        ),
    ),
    Probe(
        name="unwiredReporter",
        rule_id="NoUnwiredReporterInBinding",
        # The probe declares its OWN ViewModel-shaped class rather than naming a real one.
        # The first version pointed at `TaskTimeSlot`, which is not ViewModel-shaped, so the
        # rule correctly stayed quiet and the probe reported DID NOT FIRE — the script working
        # as designed, and a reminder that a probe pointing at the real codebase rots the day
        # someone renames a class. A probe must fail only for the reason it exists.
        body=(
            "package com.singularity.todo.core.observability\n"
            "\n"
            "import org.koin.core.module.dsl.viewModel\n"
            "\n"
            "/** TEMPORARY probe planted by scripts/check-gate-honesty.py. */\n"
            "class GateHonestyProbeViewModel(val id: Int)\n"
            "\n"
            "fun gateHonestyProbe() {\n"
            "    viewModel { GateHonestyProbeViewModel(get()) }\n"
            "}\n"
        ),
        why=(
            "the second half of the crash-reporting invariant, added after two production "
            "bindings shipped a no-op reporter. A gate that only proves the *first* rule in a "
            "set loads would not notice a rule added alongside it."
        ),
    ),
]

REPORT = ROOT / "shared/build/reports/detekt/detekt.md"


def _run_detekt_in(worktree: pathlib.Path) -> tuple[int, str]:
    """Run :shared:detekt inside a worktree. Returns (exit code, output tail)."""
    # Use ./gw to get GRADLE_USER_HOME isolation (per ADR 2026-10-04).
    # Detekt writes shared/build/reports/detekt/detekt.md inside the worktree,
    # so each probe gets its own report file and they never contend.
    proc = subprocess.run(
        [str(worktree / "gw"), ":shared:detekt", "--console=plain"],
        cwd=worktree,
        capture_output=True,
        text=True,
    )
    return proc.returncode, (proc.stdout + proc.stderr)[-4000:]


def probe_once(probe: Probe) -> tuple[bool, str]:
    """Run one probe inside a disposable worktree.

    The probe file is planted in an isolated git worktree, detekt is run there,
    and the worktree is removed when done. The live tree is never touched.
    GIT_CONFIG_* parameters ensure the worktree's git identity is isolated from
    any global config (e.g. a shared hooksPath).
    """
    wt_dir = pathlib.Path(tempfile.mkdtemp(prefix="gate-honesty-"))
    worktree = wt_dir / "probe-tree"

    # git config for this invocation only — does not pollute ~/.gitconfig or
    # the repository's per-worktree config. The two settings are the minimum
    # needed to avoid git warnings about identity; the value is otherwise
    # irrelevant since no commit is made.
    git_env = {
        **os.environ,
        "GIT_CONFIG_COUNT": "2",
        "GIT_CONFIG_KEY_0": "user.email",
        "GIT_CONFIG_VALUE_0": "gate-honesty@local",
        "GIT_CONFIG_KEY_1": "user.name",
        "GIT_CONFIG_VALUE_1": "Gate Honesty",
    }

    try:
        # Create disposable worktree. --detach = detached HEAD, no branch needed.
        # --reason is stored by git worktree prune; it appears in `git worktree list`.
        wt_result = subprocess.run(
            [
                "git", "-C", str(ROOT),
                "worktree", "add",
                "--detach",
                "--reason", f"gate-honesty probe {probe.name}",
                str(worktree),
            ],
            capture_output=True, text=True, env=git_env,
        )
        if wt_result.returncode != 0:
            return False, f"git worktree add failed: {wt_result.stderr}"

        # Plant the probe file inside the worktree.
        wt_probe = worktree / probe.path.relative_to(ROOT)
        wt_probe.parent.mkdir(parents=True, exist_ok=True)
        wt_probe.write_text(probe.body, encoding="utf-8")

        # The worktree's report is isolated from the live tree's.
        wt_report = worktree / "shared" / "build" / "reports" / "detekt" / "detekt.md"

        code, output = _run_detekt_in(worktree)

        if not wt_report.exists():
            return False, "detekt wrote no report at all"
        report = wt_report.read_text(encoding="utf-8", errors="replace")
        if probe.rule_id not in report:
            return False, (
                f"the report does not mention {probe.rule_id}. "
                f"detekt exit={code}. This is the failure the check exists for: the rule is not "
                f"running, so a green result means nothing. "
                f"If you just changed detekt-rules/, try `./gw --stop` first — see #136."
            )
        return True, f"{probe.rule_id} reported the planted violation"

    finally:
        # Remove the worktree. --force needed if the process was interrupted;
        # --reason persists in the worktree list entry.
        subprocess.run(
            ["git", "-C", str(ROOT), "worktree", "remove", "--force", str(worktree)],
            capture_output=True,
        )
        # Also remove the temp directory (worktree remove can leave it behind on crash).
        shutil.rmtree(wt_dir, ignore_errors=True)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument(
        "--only",
        action="append",
        help="run one probe by name (repeatable); default runs all",
    )
    ap.add_argument("--list", action="store_true", help="list probe names and exit")
    args = ap.parse_args()

    if args.list:
        for p in PROBES:
            print(f"{p.name}\t{p.rule_id}")
        return 0

    selected = [p for p in PROBES if not args.only or p.name in args.only]
    if not selected:
        print(f"no probe matches {args.only}", file=sys.stderr)
        return 2

    print("=" * 72)
    print("GATE HONESTY — can the lint gate tell a clean tree from a broken one?")
    print("=" * 72)

    failures = 0
    for probe in selected:
        print(f"\n→ {probe.name}  (expects {probe.rule_id})")
        print(f"  {probe.why}")
        started = time.monotonic()
        fired, detail = probe_once(probe)
        took = time.monotonic() - started
        if fired:
            print(f"  FIRED — {detail}  ({took:.0f}s)")
        else:
            failures += 1
            print(f"  DID NOT FIRE — {detail}  ({took:.0f}s)")

    print("\n" + "=" * 72)
    if failures:
        print(f"FAILED: {failures}/{len(selected)} probe(s) did not fire.")
        print("A probe that cannot fire is the defect this script exists to catch, so this is a")
        print("failure of the *check*, not a pass with nothing to report.")
        return 1
    print(f"OK: {len(selected)}/{len(selected)} probes fired. The gate is measuring.")
    print("A green `just lint` from here is evidence rather than an absence of evidence.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
