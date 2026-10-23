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

Exit codes:
  0 = the host was measured (busy or idle — this is a report, not a gate)
  2 = the load could not be read (a platform without it)
"""
from __future__ import annotations

import json as _json
import os

import click

from sgate.commands.base import CLEAN, IO_ERROR, register
from sgate.log import logger

#: The load per core at which the host counts as busy.
#:
#: Measured on a 16-core host: load 16 (1.0 per core) ran `:shared:jvmTest` in
#: 8 minutes; load 42–52 (2.6–3.2 per core) ran the same command in 24. The
#: line is drawn at 2.0 per core — above it, the daemons queue for cores.
BUSY_LOAD_PER_CORE = 2.0


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


register(host_load)
