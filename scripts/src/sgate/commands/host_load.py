"""sgate.commands.host_load — is this host busy enough to matter?

Why this exists
---------------

The cost of a Gradle run on this repository is not a property of the run. The
same `:shared:jvmTest` took **8 minutes** on a host sitting at load 16 and
**24 minutes** on a host at load 42–52 — the same command, the same tree, the
same cache, three times the wall clock. The difference was not the work; it was
the other eight Gradle daemons that were already running.

That knowledge existed, and it was useless: it was a sentence in a guidance
file, and the sentence could not be consulted by anything. A rule that no tool
can answer is a rule that is remembered, and remembering it is exactly what
fails at the moment it matters — when the host is busy and the run has already
been started.

So the question gets an answer that a machine can give:

    sgate host-load            # "busy — run the targeted set, or wait"
    sgate host-load --json     # {"state": "busy", "loadPerCore": 2.6, ...}

The threshold is measured, not guessed
--------------------------------------

Two measurements on a 16-core host, same command, same tree:

| load (1 min) | per core | `:shared:jvmTest` |
|---------------|----------|-------------------|
| 16            | 1.0      | 8 min             |
| 42–52         | 2.6–3.2  | 24 min            |

The knee lies between 1.0 and 2.6 per core. The line is drawn at **2.0 per
core**: at that point the host is running more runnable work than it has cores
to run it on, and every additional Gradle daemon is waiting for a core that
another daemon already holds. Below it, the run costs what the run costs.

The threshold is a module constant, so the next measurement can move it — and
the measurement that moves it has to be written down here, the way these two
are.

What busy means for the caller
-------------------------------

Nothing is blocked: this command reports, it does not refuse. The advice it
prints is the rule that was measured — on a busy host, run the targeted set
(`--tests`) rather than the whole suite, or wait for the host to settle.

This module holds two commands
------------------------------

* ``host-load``  — the CPU load of the host (the measurement above);
* ``host-check`` — the inode pressure of the host, and the dead drafts that
  previous sessions left in the scratch directory.

Why the second one exists
--------------------------

A Gradle build died in the middle of a compile with "No space left on device",
and the space was not the disk: the scratch directory is a tmpfs with 1,048,576
inodes, and the drafts of previous sessions had taken 700,450 of them (67 %).
``host-load`` did not see that — it measures the CPU, and the CPU was idle.
The diagnosis cost six calls and a restart of a 35-minute run.

So the second question gets an answer a machine can give:

    sgate host-check            # "tight — a third of the inodes are gone; prune these"
    sgate host-check --json     # {"state": "tight", "freeInodes": 347668, ...}

The thresholds are measured, not guessed
-----------------------------------------

The scratch filesystem here holds 1,048,576 inodes, and one session's draft
costs 54,912 of them (measured: a Gradle worktree under ``/tmp``, counted with
``find -xdev | wc -l``). 20 % of the filesystem is 209,715 inodes, which is
fewer than four drafts (4 × 54,912 = 219,648). So the line is drawn at **80 %**:
past it, the host cannot host four more sessions, and the run that dies is a
build in the middle of a compile.

The measurements live in ``docs/testing/host-pressure.md``.

Exit codes:
  0 = the host was measured (busy or idle — this is a report, not a gate)
  2 = the load could not be read (a platform without it)
"""
from __future__ import annotations

import json as _json
import os
import shlex
import subprocess
import tempfile
import time

import click

from sgate.commands.base import CLEAN, IO_ERROR, VALIDATION_FAILURE, register
from sgate.log import logger
from sgate.repo import ROOT

#: The load per core at which the host counts as busy.
#:
#: Measured on a 16-core host: load 16 (1.0 per core) ran `:shared:jvmTest` in
#: 8 minutes; load 42–52 (2.6–3.2 per core) ran the same command in 24. The
#: line is drawn at 2.0 per core — above it, the daemons queue for cores.
BUSY_LOAD_PER_CORE = 2.0

#: The inode use at which the scratch filesystem counts as tight.
#:
#: Measured 2026-10-23 on this host: the scratch directory is a tmpfs with
#: 1,048,576 inodes, of which 700,450 were in use — the drafts of previous
#: sessions. One session's draft costs 54,912 inodes (a Gradle worktree with a
#: build, counted with `find -xdev | wc -l`), so 20 % of the filesystem
#: (209,715 inodes) is fewer than four more drafts (4 × 54,912 = 219,648).
#: Past 80 % the host cannot host four more sessions, and the run that dies is
#: a build in the middle of a compile.
TIGHT_INODE_PERCENT = 80

#: The inodes one session's draft costs.
#:
#: Measured 2026-10-23 on this host: 54,912 inodes for a Gradle worktree with
#: a build under the scratch directory. It is the divisor that turns free
#: inodes into "how many more sessions fit".
DRAFT_INODE_COST = 54_912

#: How old a scratch directory must be before it counts as a dead draft.
#:
#: The longest single command this repository measures takes 37 min 25 s
#: (`docs/testing/gate-costs.md`). A scratch directory that nothing has touched
#: for 24 hours — 39 times that longest run — cannot belong to a running
#: command; it belongs to a session that ended.
STALE_DRAFT_HOURS = 24


def measure() -> dict[str, object]:
    """What the host is doing right now.

    The load average is read the way the kernel reports it — the number of
    runnable and uninterruptible tasks averaged over the last minute — and it
    is divided by the number of cores, because a load of 32 means something
    different on a 16-core host than on a 64-core one.
    """
    try:
        one, five, fifteen = os.getloadavg()
    except (OSError, AttributeError) as exc:  # not every platform has it
        logger.error(f"the load average could not be read: {exc}")
        raise SystemExit(IO_ERROR) from exc

    cores = os.cpu_count() or 0
    per_core = round(one / cores, 2) if cores else None
    busy = per_core is not None and per_core >= BUSY_LOAD_PER_CORE
    return {
        "load1m": round(one, 1),
        "load5m": round(five, 1),
        "load15m": round(fifteen, 1),
        "cores": cores,
        "loadPerCore": per_core,
        "thresholdPerCore": BUSY_LOAD_PER_CORE,
        "state": "busy" if busy else "idle",
    }


def advise(state: str) -> str:
    """What the measurement says the caller should do."""
    if state == "busy":
        return (
            "the host is busy — run the targeted set (--tests) rather than the whole "
            "suite, or wait for the load to fall; a full run costs about three times "
            "what it costs on an idle host"
        )
    return "the host is idle — a full run costs what it costs"


@click.command("host-load")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
def host_load(emit_json: bool) -> None:
    """Say whether this host is busy, and what that means for a Gradle run."""
    reading = measure()
    if emit_json:
        click.echo(_json.dumps(reading))
        return
    click.echo(
        f"host load {reading['load1m']} on {reading['cores']} cores "
        f"({reading['loadPerCore']} per core) — {reading['state']}",
    )
    click.echo(advise(str(reading["state"])))


# ── host-check: the inode pressure and the dead drafts ────────────────────────

#: How long a walk of the scratch directory may take, in seconds.
#:
#: The walk is the expensive part of the command — it reads every inode of the
#: scratch tree — so it gets a bound: a scratch directory that cannot be walked
#: in two minutes is not a scratch directory anybody should be pruning.
SCAN_TIMEOUT_SECONDS = 120


def _mount_of(path: str) -> tuple[str, str]:
    """The mount point and the filesystem type that carry a path.

    Read from `/proc/mounts`, because a path does not say which filesystem it
    lives on: the scratch directory is a tmpfs and the checkout is on btrfs,
    and only the first of the two has an inode limit at all.
    """
    target = os.path.abspath(path)
    best_point = ""
    best_type = ""
    try:
        with open("/proc/mounts", encoding="utf-8") as mounts:
            for line in mounts:
                parts = line.split()
                if len(parts) < 3:
                    continue
                point = parts[1]
                if target != point and not target.startswith(point.rstrip("/") + "/"):
                    continue
                if len(point) >= len(best_point):
                    best_point, best_type = point, parts[2]
    except OSError as exc:
        logger.error(f"the mounts could not be read: {exc}")
    return best_point, best_type


def _inode_state(percent: float | None) -> str:
    """The verdict for one filesystem's inode use."""
    if percent is None:
        return "no-inode-limit"
    return "tight" if percent >= TIGHT_INODE_PERCENT else "ok"


def measure_filesystem(path: str) -> dict[str, object]:
    """The inode and byte pressure of the filesystem that carries a path.

    The inode numbers are the ones `df -i` prints: `os.statvfs` is what `df`
    calls, and it says how many inodes the filesystem has (`f_files`), how many
    are free (`f_ffree`) and how many blocks are left. A filesystem that
    allocates its inodes dynamically — btrfs here — reports zero inodes, and
    zero is not "no inodes": it is "no limit".
    """
    try:
        stats = os.statvfs(path)
    except OSError as exc:
        logger.error(f"the filesystem of '{path}' could not be read: {exc}")
        raise SystemExit(IO_ERROR) from exc

    total_inodes = stats.f_files
    free_inodes = stats.f_ffree
    used_inodes = total_inodes - free_inodes
    percent = round(used_inodes * 100 / total_inodes, 1) if total_inodes else None
    mount, fs_type = _mount_of(path)
    return {
        "path": os.path.abspath(path),
        "mount": mount,
        "fsType": fs_type,
        "totalInodes": total_inodes,
        "usedInodes": used_inodes,
        "freeInodes": free_inodes,
        "usedPercent": percent,
        "totalBytes": stats.f_blocks * stats.f_frsize,
        "freeBytes": stats.f_bavail * stats.f_frsize,
        "state": _inode_state(percent),
    }


def _child_inode_counts(scratch: str) -> dict[str, int]:
    """How many inodes each direct child of the scratch directory holds.

    One walk of the whole tree, counted per top-level entry. The ranking is by
    inodes and not by bytes, because the filesystem runs out of inodes long
    before it runs out of bytes: a tmpfs here holds 32 GiB and 1,048,576
    inodes, and the drafts fill the second one first.

    `find` reports the directories it may not read and still counts the rest,
    so a non-zero exit is not a failure here — only an empty answer is.
    """
    counts: dict[str, int] = {}
    try:
        completed = subprocess.run(
            ["find", scratch, "-xdev", "-mindepth", "1", "-printf", "%P\n"],
            capture_output=True,
            text=True,
            check=False,
            timeout=SCAN_TIMEOUT_SECONDS,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        logger.error(f"the scratch directory could not be walked: {exc}")
        return counts
    for line in completed.stdout.splitlines():
        name = line.split("/", 1)[0]
        if name:
            counts[name] = counts.get(name, 0) + 1
    if not counts and completed.returncode != 0:
        first = completed.stderr.strip().splitlines()[:1]
        logger.error(f"the scratch directory could not be walked: {first}")
    return counts


def _touched_recently(path: str, cutoff: str) -> bool:
    """Whether anything inside the directory was modified after the cutoff.

    The probe stops at the first hit, so a directory that is being written to
    answers at once and a directory that is dead costs one walk. A probe that
    cannot answer says "yes, it is live": a directory whose state is unknown is
    never proposed for deletion.
    """
    try:
        completed = subprocess.run(
            ["find", path, "-xdev", "-newermt", cutoff, "-print", "-quit"],
            capture_output=True,
            text=True,
            check=False,
            timeout=SCAN_TIMEOUT_SECONDS,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        logger.error(f"'{path}' could not be probed: {exc}")
        return True
    if completed.returncode != 0:
        logger.error(f"'{path}' could not be probed: {completed.stderr.strip()}")
        return True
    return bool(completed.stdout.strip())


def _size_of(path: str) -> int:
    """The size of a directory in bytes, as `du` counts it."""
    try:
        completed = subprocess.run(
            ["du", "-x", "-sk", path],
            capture_output=True,
            text=True,
            check=False,
            timeout=SCAN_TIMEOUT_SECONDS,
        )
    except (OSError, subprocess.SubprocessError) as exc:
        logger.error(f"'{path}' could not be measured: {exc}")
        return 0
    if completed.returncode != 0:
        return 0
    try:
        return int(completed.stdout.split("\t", 1)[0].strip()) * 1024
    except ValueError:
        return 0


def _cutoff(stale_hours: int) -> str:
    """The timestamp a draft has to be older than to count as dead."""
    moment = time.time() - stale_hours * 3600
    return time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(moment))


def scan_drafts(
    scratch: str,
    limit: int = 5,
    stale_hours: int = STALE_DRAFT_HOURS,
) -> list[dict[str, object]]:
    """The biggest drafts in the scratch directory, and which of them are dead.

    A draft is a directory a previous session left behind. Three rules keep
    the answer safe, and none of them is a guess about names:

    * hidden entries are skipped — on this host the scratch directory holds a
      live AppImage mount under a dot-name, and a dot-name is never a draft;
    * mount points are skipped — a mounted filesystem is not a draft;
    * a directory that was touched inside the staleness window is *live* — that
      is a session at work, not the leftovers of one, and the prune command
      never names it.

    The report names the big ones whether they are dead or not: the question
    "where did the inodes go" has an answer even when every draft is still
    being written to.
    """
    counts = _child_inode_counts(scratch)
    if not counts:
        return []

    cutoff = _cutoff(stale_hours)
    drafts: list[dict[str, object]] = []
    ranked = sorted(counts.items(), key=lambda item: item[1], reverse=True)
    for name, inodes in ranked:
        if len(drafts) >= limit:
            break
        if name.startswith("."):
            continue
        path = os.path.join(scratch, name)
        if not os.path.isdir(path) or os.path.ismount(path):
            continue
        drafts.append(
            {
                "path": path,
                "inodes": inodes,
                "bytes": _size_of(path),
                "dead": not _touched_recently(path, cutoff),
                "staleHours": stale_hours,
            },
        )
    return drafts


def prune_command(drafts: list[dict[str, object]]) -> str:
    """The command that removes the dead drafts — and nothing else.

    The command is printed, never run: this command reports the state of the
    host, and the decision to delete a directory is the caller's.
    """
    dead = [str(draft["path"]) for draft in drafts if draft.get("dead")]
    if not dead:
        return ""
    return "rm -rf " + " ".join(shlex.quote(path) for path in dead)


def _human_bytes(value: int) -> str:
    """A size a person reads at a glance."""
    size = float(value)
    for unit in ("B", "KiB", "MiB", "GiB", "TiB"):
        if size < 1024 or unit == "TiB":
            return f"{size:.0f} {unit}" if unit == "B" else f"{size:.1f} {unit}"
        size /= 1024
    return f"{size:.1f} TiB"


@click.command("host-check")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
@click.option("--quiet", is_flag=True, help="Print the verdict and the prune command only")
@click.option("--top", "top", type=int, default=5, help="How many dead drafts to name")
@click.option(
    "--scratch",
    "scratch",
    default=None,
    help="The scratch directory to scan (default: the temporary directory)",
)
@click.option(
    "--fail-when-tight",
    is_flag=True,
    help="Exit 1 when a filesystem is tight (for a gate that must not pass)",
)
@click.pass_context
def host_check(
    ctx: click.Context,
    emit_json: bool,
    quiet: bool,
    top: int,
    scratch: str | None,
    fail_when_tight: bool,
) -> None:
    """Say whether the host has room for another run, and what is in the way.

    Reports the inode pressure of the scratch filesystem and of the checkout
    (the two places a run writes), the dead drafts that previous sessions left
    in the scratch directory, and the command that removes them. Nothing is
    deleted: the command is a suggestion, printed for the caller to run.

    Exit codes:
      0 = the host was measured (tight or not — this is a report, not a gate)
      1 = the host is tight and `--fail-when-tight` was asked for
      2 = the state of the host could not be read
    """
    scratch_dir = os.path.abspath(scratch or tempfile.gettempdir())
    filesystems = [measure_filesystem(scratch_dir), measure_filesystem(str(ROOT))]
    drafts = scan_drafts(scratch_dir, limit=max(top, 0))
    scratch_state = str(filesystems[0]["state"])
    state = "tight" if any(str(entry["state"]) == "tight" for entry in filesystems) else "ok"
    free_inodes = int(filesystems[0]["freeInodes"])
    payload = {
        "state": state,
        "scratch": scratch_dir,
        "scratchState": scratch_state,
        "filesystems": filesystems,
        "drafts": drafts,
        "deadDrafts": [draft for draft in drafts if draft.get("dead")],
        "pruneCommand": prune_command(drafts),
        "draftsThatFit": free_inodes // DRAFT_INODE_COST if DRAFT_INODE_COST else None,
        "thresholds": {
            "tightInodePercent": TIGHT_INODE_PERCENT,
            "staleHours": STALE_DRAFT_HOURS,
            "draftInodeCost": DRAFT_INODE_COST,
        },
    }

    if emit_json:
        click.echo(_json.dumps(payload))
    elif quiet:
        _print_verdict(filesystems[0], payload)
        if payload["pruneCommand"]:
            click.echo(f"  prune: {payload['pruneCommand']}")
    else:
        _print_report(filesystems, payload)

    if fail_when_tight and state == "tight":
        raise ctx.exit(VALIDATION_FAILURE)
    raise ctx.exit(CLEAN)


def _print_verdict(scratch: dict[str, object], payload: dict[str, object]) -> None:
    """One line: the state of the scratch filesystem."""
    percent = scratch["usedPercent"]
    used = f"{percent} % of the inodes in use" if percent is not None else "no inode limit"
    click.echo(
        f"host check — {scratch['path']}: {used} — {payload['state']}",
    )


def _print_report(filesystems: list[dict[str, object]], payload: dict[str, object]) -> None:
    """The summary: two filesystems, the biggest drafts, and the prune command."""
    for entry in filesystems:
        percent = entry["usedPercent"]
        if percent is None:
            click.echo(
                f"{entry['path']} ({entry['fsType']}): no inode limit — "
                "this filesystem cannot run out of inodes",
            )
            continue
        click.echo(
            f"{entry['path']} ({entry['fsType']}): {percent} % of the inodes in use "
            f"— {entry['state']}, {entry['freeInodes']} free",
        )
    scratch = filesystems[0]
    if scratch["totalInodes"]:
        click.echo(
            f"  room for {payload['draftsThatFit']} more session drafts "
            f"({DRAFT_INODE_COST} inodes each)",
        )
    drafts = payload["drafts"]
    if drafts:
        click.echo(f"  biggest drafts in {payload['scratch']} (by inodes):")
        for draft in drafts:
            click.echo(
                f"    {draft['path']}  {draft['inodes']} inodes  "
                f"{_human_bytes(int(draft['bytes']))}  "
                f"{'dead' if draft.get('dead') else 'live'}",
            )
    else:
        click.echo(f"  no drafts in {payload['scratch']}")
    if payload["pruneCommand"]:
        click.echo(f"  prune (this command deletes nothing): {payload['pruneCommand']}")
    else:
        click.echo(f"  nothing to prune (no draft untouched over {STALE_DRAFT_HOURS} h)")


register(host_load)
register(host_check)
