#!/usr/bin/env python3
"""Prove the lint gate is actually measuring, by planting a violation and reading the report.

## Migration note

Migrated 2026-10-17 from argparse to Click, following the established sgate pattern:
- `@click.command()` + `@click.option()` + `@click.argument()` + `@click.pass_context`
- `click.echo()` replaces `print()`; `click.echo(..., err=True)` replaces `print(..., file=sys.stderr)`
- Exit via `ctx.exit(code)` / `raise ctx.exit(code)` using constants from sgate.commands.base
- `main()` wrapper calls `run(cli)` from sgate.commands.base

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

import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import time
from typing import Annotated

import click
from sgate.commands.base import run

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

#: Must match the `mkdtemp` prefix below: `_sweep_stale_probe_worktrees` recognises
#: this script's leftovers by it, and a sweep that misses its own prefix is a sweep
#: that reaps nothing while looking like it works.
PROBE_PREFIX = "gate-honesty-"

#: Per-probe deadline for the detekt run. Generous enough for a cold Gradle start
#: on a fresh worktree, short enough that a wedged build cannot hold a worktree
#: for the rest of the day.
DETEKT_TIMEOUT_SECONDS = 900


def _run_detekt_in(worktree: pathlib.Path) -> tuple[int, str]:
    """Run :shared:detekt inside a worktree. Returns (exit code, output tail)."""
    # Use ./gw to get GRADLE_USER_HOME isolation (per ADR 2026-10-04).
    # Detekt writes shared/build/reports/detekt/detekt.md inside the worktree,
    # so each probe gets its own report file and they never contend.
    #
    # Bounded. Gradle can sit on a lock or wait on a daemon forever, and a probe
    # with no deadline holds its worktree for as long as it hangs — 1305 of them
    # did, until the machine rebooted and cleared /tmp out from under a registry
    # that had already forgotten it was allowed to forget anything.
    try:
        proc = subprocess.run(
            [str(worktree / "gw"), ":shared:detekt", "--console=plain"],
            cwd=worktree,
            capture_output=True,
            text=True,
            timeout=DETEKT_TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired:
        return (
            124,
            f"detekt did not finish within {DETEKT_TIMEOUT_SECONDS}s — a probe with "
            "no deadline is a probe that never cleans up",
        )
    return proc.returncode, (proc.stdout + proc.stderr)[-4000:]


def _sweep_stale_probe_worktrees() -> int:
    """Reap probe worktrees this script orphaned on an earlier run.

    `--lock` at creation time (see `probe_once`) made every abnormal exit
    permanent: a killed run left a locked entry, `git worktree prune` refuses
    locked entries, and the entry outlived the directory it named — /tmp is a
    tmpfs, so the directory disappeared on the next reboot while the metadata sat
    in `.git/worktrees/` for ever. `git worktree list` grew past 1300 rows.

    Scoped to this script's own prefix, and only where the directory is already
    gone. A blanket `prune --expire=now` would also reap the maintainer's own
    stale worktrees, which is not this gate's to decide.
    """
    listed = subprocess.run(
        ["git", "-C", str(ROOT), "worktree", "list", "--porcelain"],
        capture_output=True,
        text=True,
    )
    if listed.returncode != 0:
        return 0

    reaped = 0
    path: str | None = None
    stale: list[str] = []
    for line in [*listed.stdout.splitlines(), ""]:
        if line.startswith("worktree "):
            path = line.removeprefix("worktree ")
        elif not line and path is not None:
            if PROBE_PREFIX in path and not pathlib.Path(path).exists():
                stale.append(path)
            path = None

    for target in stale:
        # Unlock first: prune declines locked entries by design, and the lock is
        # what stranded them in the first place.
        subprocess.run(
            ["git", "-C", str(ROOT), "worktree", "unlock", target],
            capture_output=True,
        )
        subprocess.run(
            ["git", "-C", str(ROOT), "worktree", "remove", "--force", target],
            capture_output=True,
        )
        reaped += 1
    return reaped


def probe_once(probe: Probe) -> tuple[bool, str]:
    """Run one probe inside a disposable worktree.

    The probe file is planted in an isolated git worktree, detekt is run there,
    and the worktree is removed when done. The live tree is never touched.
    GIT_CONFIG_* parameters ensure the worktree's git identity is isolated from
    any global config (e.g. a shared hooksPath).
    """
    wt_dir = pathlib.Path(tempfile.mkdtemp(prefix=PROBE_PREFIX))
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
        #
        # Not `--lock`. It was added with the reasoning that anything holding the
        # worktree open should be surfaced rather than unlocked silently — but the
        # lock only ever applies to the abnormal path, because `finally` removes the
        # worktree with `--force` on the normal one. So it bought nothing and cost
        # everything: `git worktree prune` refuses locked entries, so a run killed
        # mid-probe left a registry row that no later run and no reboot could remove.
        # A directory on /tmp is a tmpfs; it vanishes on the next power cycle while
        # the metadata naming it sat in `.git/worktrees/` indefinitely. That is how
        # `git worktree list` came to hold 1305 rows for trees that no longer exist.
        wt_result = subprocess.run(
            [
                "git", "-C", str(ROOT),
                "worktree", "add",
                "--detach",
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


# ── CLI ─────────────────────────────────────────────────────────────────────


@click.command("check-gate-honesty")
@click.option(
    "--only",
    type=str,
    multiple=True,
    help="run one probe by name (repeatable); default runs all",
)
@click.option("--list", is_flag=True, help="list probe names and exit")
@click.argument("PROBE", nargs=-1, type=str)
@click.pass_context
def cli(
    ctx: click.Context,
    only: tuple[str, ...],
    list: bool,
    probe: tuple[str, ...],
) -> None:
    """Prove the lint gate is actually measuring, by planting a violation and reading the report.

    PROBE is an optional positional probe name for backward compatibility with --only.
    Exit codes:
      0 = all probes fired (gate is measuring)
      1 = one or more probes did not fire
      2 = no probe matches the given name
    """
    # Support both positional PROBE and --only for backward compatibility.
    selected_names = set(only) | set(probe)

    if list:
        for p in PROBES:
            click.echo(f"{p.name}\t{p.rule_id}")
        raise ctx.exit(0)

    selected = [p for p in PROBES if not selected_names or p.name in selected_names]
    if not selected:
        click.echo(f"no probe matches {selected_names!r}", err=True)
        raise ctx.exit(2)

    click.echo("=" * 72)
    click.echo("GATE HONESTY — can the lint gate tell a clean tree from a broken one?")
    click.echo("=" * 72)

    # Reap what an interrupted earlier run left behind, before adding to it. Doing
    # it here rather than in a hook is deliberate: `git worktree prune` alone would
    # not help (its default expiry is three months, so fresh orphans are exempt) and
    # a blanket prune would take the maintainer's own worktrees with it.
    reaped = _sweep_stale_probe_worktrees()
    if reaped:
        click.echo(f"reaped {reaped} orphaned probe worktree(s) from an earlier run")

    failures = 0
    for p in selected:
        click.echo(f"\n→ {p.name}  (expects {p.rule_id})")
        click.echo(f"  {p.why}")
        started = time.monotonic()
        fired, detail = probe_once(p)
        took = time.monotonic() - started
        if fired:
            click.echo(f"  FIRED — {detail}  ({took:.0f}s)")
        else:
            failures += 1
            click.echo(f"  DID NOT FIRE — {detail}  ({took:.0f}s)")

    click.echo("\n" + "=" * 72)
    if failures:
        click.echo(f"FAILED: {failures}/{len(selected)} probe(s) did not fire.")
        click.echo("A probe that cannot fire is the defect this script exists to catch, so this is a")
        click.echo("failure of the *check*, not a pass with nothing to report.")
        raise ctx.exit(1)
    click.echo(f"OK: {len(selected)}/{len(selected)} probes fired. The gate is measuring.")
    click.echo("A green `just lint` from here is evidence rather than an absence of evidence.")
    raise ctx.exit(0)




if __name__ == "__main__":
    raise SystemExit(run(cli))
