"""sgate.commands.run_all — sgate run-all: run all gates in parallel.

Reads from static-gates.sh registry (same source as ci.yml static job).
Supports: sgate <name> calls, python3 scripts, bash functions, and direct commands.
"""
from __future__ import annotations

import concurrent.futures
import json
import re
import shlex
import subprocess
import sys
import threading
import time
from dataclasses import dataclass
from pathlib import Path

import click
from rich.console import Console
from rich.progress import BarColumn, Progress, SpinnerColumn, TaskProgressColumn, TextColumn

import sgate
from sgate.commands.base import CLEAN, VALIDATION_FAILURE, register
from sgate.log import logger
from sgate.models import GatePayload


def _repo_env() -> dict[str, str]:
    """The environment a gate subprocess runs in.

    ``PYTHONPATH`` points at *this* checkout's ``scripts/src``, so a gate that
    imports ``sgate`` — or any other module of this repository — runs the code
    on disk here. Without it, an editable install of another checkout wins the
    import, and the gate suite reports another checkout's verdict as this
    one's (ADR `2026-10-21-one-owner-of-stdout.md`).

    The path comes from ``sgate.repo`` rather than being written out here: a
    path written twice is a path that drifts.
    """
    import os

    env = dict(os.environ)
    src = str(sgate.repo.SRC)
    existing = env.get("PYTHONPATH", "")
    env["PYTHONPATH"] = f"{src}{os.pathsep}{existing}" if existing else src
    return env


@dataclass
class GateResult:
    """Result of a single gate run."""
    name: str
    mode: str  # "blocking" | "advisory"
    passed: bool
    exit_code: int
    duration_ms: int = 0
    error: str = ""
    #: Captured output, kept so a failing gate can say WHY. A table row reading
    #: "exit=1" is not a diagnosis; see `OUTPUT_TAIL_LINES` at the print site.
    stdout: str = ""
    stderr: str = ""
    #: The verdict the gate's own payload carried, when it emitted one. None
    #: means the gate printed no contract and the exit code is all there was.
    payload_passed: bool | None = None


# Pattern to parse gate lines from static-gates.sh:
#   gate blocking|advisory "name" command...
_GATE_LINE = re.compile(r'^gate\s+(blocking|advisory)\s+"([^"]+)"\s+(.+)$')

#: How much of a failing gate's output to print. Bounded because `declared
#: dependencies are used` alone emits tens of thousands of lines on a real
#: failure, and a log nobody can scroll is the same as no log.
OUTPUT_TAIL_LINES = 40

#: The budget for one gate, in seconds.
#:
#: Five minutes is enough for a gate that reads files and asks a question.
#: It is not enough for a gate that resolves a build: see `GATE_TIMEOUTS`.
DEFAULT_GATE_TIMEOUT = 300

#: The budget for the control phase. It runs a gate twice per control — once
#: on the clean tree, once on the sabotaged one — so it needs more room than a
#: single gate.
CONTROLS_PHASE_TIMEOUT = 1800

#: Gates whose own work needs more than the default budget, and why.
#:
#: `declared dependencies are used` asks Gradle which files each configuration
#: resolved and reads the jars to learn the package roots they contribute. On a
#: runner with a cold Gradle home that is a download of the distribution plus a
#: resolution of every configuration — minutes, not seconds, and how many
#: minutes depends on the network.
#:
#: It failed on that budget rather than on a finding: the run that prompted
#: this table measured 113 s on a good day and was killed at 300 s on a bad one,
#: with the same tree and the same code. A gate that needs the build does not
#: fit in a budget that was chosen for gates that do not.
#:
#: The budget stays under the job's own ten-minute cap, so a gate that hangs is
#: killed by the runner rather than by the job. The static job now restores the
#: Gradle cache, which is what actually makes this gate fast; this number is the
#: floor under a cold cache, not the plan.
#:
#: The gate still fails if it finds anything. The budget buys the gate time to
#: finish; it does not buy it a pass.
GATE_TIMEOUTS = {
    "declared dependencies are used": 540,
}


def _timeout_for(display_name: str) -> int:
    """The budget for one gate, in seconds."""
    return GATE_TIMEOUTS.get(display_name, DEFAULT_GATE_TIMEOUT)


def _read_gate_payload(stdout: str, gate_name: str) -> GatePayload | None:
    """The verdict a gate's JSON payload carries, or None when it has none.

    A gate dispatched through `sgate` is asked for `--json`, so its stdout is
    the contract: one JSON object with a boolean `passed`. A gate run as a
    plain script prints human text, and there is nothing to read — the caller
    falls back to the exit code.

    Only the verdict is taken from the document. The rest of the payload is
    the gate's business, and a document whose extra fields do not fit the
    payload is still a document that said `passed`.
    """
    text = stdout.strip()
    if not text.startswith("{"):
        return None
    try:
        document = json.loads(text)
    except json.JSONDecodeError:
        return None
    if not isinstance(document, dict):
        return None
    verdict = document.get("passed")
    if not isinstance(verdict, bool):
        return None
    return GatePayload(gate=gate_name, passed=verdict)

#: Lines that mark a failure wherever they appear in a gate's output. The tail
#: alone is not enough: every delegated `sgate` gate ends its stderr with a burst
#: of loguru DEBUG lines, so the last 40 lines of a real failure can contain none
#: of the failure. These are kept even when they are far above the tail.
FAILURE_LINE = re.compile(
    r'(^|\s)(FAIL|ERROR)(:|\s)|Traceback|AssertionError|^FAILED|^Ran \d+ tests|'
    r'::error|violation'
)


def _load_static_gates() -> list[tuple[str, str, str]]:
    """Load gate registry from static-gates.sh.

    Returns list of (mode, display_name, command).
    Multi-command bash functions are stored as their function name.
    """
    static_sh = sgate.repo.STATIC_GATES_SH
    content = static_sh.read_text(encoding="utf-8")
    gates: list[tuple[str, str, str]] = []

    for line in content.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#") or "gate()" in stripped:
            continue
        m = _GATE_LINE.match(line)
        if not m:
            continue
        mode, name, cmd = m.group(1), m.group(2), m.group(3).rstrip()
        # Strip inline comment
        if " # " in cmd:
            cmd = cmd.split(" # ", 1)[0].rstrip()
        gates.append((mode, name, cmd))
    return gates


def _static_gate_functions(static_sh: str) -> set[str]:
    """The bash functions static-gates.sh defines, by name.

    A gate line whose command is one of these names a function, not a binary:
    the runner sources the registry and calls the function. Reading the names
    from the file keeps the two in step — a function defined here and routed
    there cannot drift apart, and a name that is routed but not defined fails
    loudly instead of silently doing nothing.
    """
    try:
        content = Path(static_sh).read_text(encoding="utf-8")
    except OSError:
        return set()
    return {
        match.group(1)
        for match in re.finditer(r"^([a-z_][a-z0-9_]*)\s*\(\)\s*\{", content, re.MULTILINE)
    }


def _slug(name: str) -> str:
    """Convert display name to slug (matches static-gates.sh convention)."""
    slug = name.lower()
    slug = re.sub(r"[^a-z0-9]+", "_", slug)
    slug = slug.strip("_")
    return slug


def _decode(raw: bytes | str | None) -> str:
    """Gate output as text. Gate scripts print in UTF-8; a stray byte must not
    turn a readable failure into a UnicodeDecodeError raised from the reporter."""
    if raw is None:
        return ""
    if isinstance(raw, str):
        return raw
    return raw.decode("utf-8", errors="replace")


def _run_gate_subprocess(mode: str, display_name: str, command: str) -> GateResult:
    """Run a single gate command and return structured result.

    Commands are tokenized with shlex so quoted arguments survive:
      sgate <name> [flags]   → sgate binary + name + flags + --json
      python3 <script> ...   → venv python + script path
      ./<script> ...         → bash + script path
      bash -c '<snippet>'    → bash -c with the snippet as ONE argument
      bare function name     → sourced from static-gates.sh
    """
    start = time.monotonic()
    repo_root = str(sgate.repo.ROOT)
    static_sh = str(sgate.repo.STATIC_GATES_SH)

    timeout = _timeout_for(display_name)

    try:
        tokens = shlex.split(command)
        if not tokens:
            return GateResult(
                name=display_name, mode=mode, passed=False, exit_code=127,
                duration_ms=0, error="empty command",
            )
        head, *args = tokens

        if head == "sgate":
            # Delegated to the sgate CLI — but to *this* checkout's sgate, not
            # to the `sgate` on PATH.
            #
            # On a machine with several checkouts of this repository, the binary
            # on PATH is an editable install pointing at one of them, not
            # necessarily this one, and the gate suite would then certify
            # somebody else's code while reporting it as this checkout's
            # result (ADR `2026-10-21-one-owner-of-stdout.md`).
            #
            # The module is run with this checkout's `scripts/src` on the import
            # path, which is the same arrangement the JSON-contract probe uses
            # (`scripts/check-gate-json-contract.py`).
            cmd = [sys.executable, "-m", "sgate", *args, "--json"]
            result = subprocess.run(cmd, capture_output=True, timeout=timeout,
                                   cwd=repo_root, env=_repo_env())

        elif head == "python3":
            # Direct python3 script — use the sgate venv Python (has all dependencies)
            if not args:
                raise FileNotFoundError("python3 gate has no script argument")
            script_path = repo_root + "/" + args[0]
            # Discover sgate Python: same Python that runs this script
            venv_python = Path(sys.executable)
            py_bin = str(venv_python.parent / "python")
            cmd = [py_bin, script_path] + args[1:]
            result = subprocess.run(cmd, capture_output=True, timeout=timeout,
                                   cwd=repo_root, env=_repo_env())

        elif head.startswith("./"):
            # Direct shell script
            script_path = repo_root + "/" + head[2:]
            cmd = ["bash", script_path] + args
            result = subprocess.run(cmd, capture_output=True, timeout=timeout,
                                   cwd=repo_root)

        elif head in _static_gate_functions(static_sh):
            # A bash function defined in static-gates.sh.
            #
            # The list is read from the file rather than hardcoded here. The
            # hardcoded tuple ("traceability", "digest_and_sizes") carried a
            # name that is defined nowhere — a gate routed to it would have
            # failed with "command not found", and the comment said so. Two
            # gates now need the same treatment (the actionlint and the
            # yamllint gate), and a list that has to be edited in two places
            # is a list that will be edited in one.
            #
            # Source the file (with STATIC_GATES_SOURCED=1 so main() is skipped),
            # then call only the requested function. shlex already stripped the
            # registry's quoting, so re-quote for the -c string.
            func_call = shlex.join([head, *args])
            bash_cmd = f"STATIC_GATES_SOURCED=1 . {shlex.quote(static_sh)} && {func_call}"
            cmd = ["bash", "-c", bash_cmd]
            result = subprocess.run(cmd, capture_output=True, timeout=timeout,
                                   cwd=repo_root)

        else:
            # Anything else — including `bash -c '<snippet>'`, where shlex has
            # already reduced the quoted snippet to a single argv element.
            cmd = tokens
            result = subprocess.run(cmd, capture_output=True, timeout=timeout,
                                   cwd=repo_root)

        duration_ms = int((time.monotonic() - start) * 1000)
        rc = result.returncode
        # A skip is not a pass. `rc in (CLEAN, 4)` used to treat exit 4 — the
        # "kiwi unavailable, skipping" code — as green, which is how a gate
        # that could not run at all reported success. No gate declares a
        # Kiwi dependency any more, so nothing emits 4; if something did, it
        # would now be red, which is the honest reading.
        #
        # The verdict is the gate's own, not the runner's: a gate dispatched
        # through `sgate` was asked for `--json`, so its stdout is the contract
        # and it carries a verdict of its own. The runner reads it and ANDs it
        # with the exit code — the rule `GatePayload.honest_pass` already
        # states. A gate that reports `passed: false` and exits 0 is red; a
        # gate that reports `passed: true` and exits non-zero is red too.
        #
        # A gate run as a plain script prints human text, so there is no
        # payload to read and the exit code is the only verdict there is.
        payload = _read_gate_payload(_decode(result.stdout), display_name)
        passed = payload.honest_pass(rc) if payload is not None else rc == CLEAN
        error = ""
        if payload is not None and not payload.passed and rc == CLEAN:
            # The gate said it failed and still exited 0. That is the case the
            # verdict exists for, and the report has to name it: a row reading
            # "exit=0" on a failing gate reads like a bug in the table.
            error = "the gate's own payload says it failed, and it exited 0"

        return GateResult(
            name=display_name,
            mode=mode,
            passed=passed,
            exit_code=rc,
            duration_ms=duration_ms,
            error=error,
            stdout=_decode(result.stdout),
            stderr=_decode(result.stderr),
            payload_passed=payload.passed if payload is not None else None,
        )

    except subprocess.TimeoutExpired:
        return GateResult(
            name=display_name, mode=mode, passed=False, exit_code=124,
            duration_ms=int((time.monotonic() - start) * 1000),
            error=f"timeout (>{timeout}s)",
        )
    except FileNotFoundError:
        return GateResult(
            name=display_name, mode=mode, passed=False, exit_code=127,
            duration_ms=int((time.monotonic() - start) * 1000),
            error="command not found",
        )
    except Exception as e:
        return GateResult(
            name=display_name, mode=mode, passed=False, exit_code=128,
            duration_ms=int((time.monotonic() - start) * 1000),
            error=str(e),
        )


def _failure_line(result: GateResult) -> str:
    """The one line of a failing gate's output that names the failure.

    A gate that failed says why in one of three places: its own error (the
    verdict contradicts its exit code), a line that matches `FAILURE_LINE`, or
    the tail of its output. The first is the most precise, the second is what
    almost every gate uses, and the third is the fallback for a gate that
    failed without saying so — in which case the exit code is the only thing
    there is, and the line says that rather than inventing a reason.
    """
    if result.error:
        return result.error
    for text in (result.stdout, result.stderr):
        for line in text.splitlines():
            stripped = line.strip()
            if stripped and FAILURE_LINE.search(stripped):
                return stripped
    if result.exit_code == 127:
        return "the command was not found (exit 127)"
    if result.exit_code == 124:
        return "the gate timed out (exit 124)"
    return f"the gate exited {result.exit_code} without saying why"


def _write_gate_run(results: list[GateResult]) -> None:
    """Record how long each gate took.

    The run happened anyway, so writing down what it cost is free. The file is
    untracked and belongs to this machine and this moment; `sgate gate-costs` reads
    it, and the committed baseline is what survives it.

    A failure to write is not a failure of the run: the gates ran, and their verdicts
    do not depend on whether anybody wrote down how long they took.
    """
    document = {
        "schemaVersion": 1,
        "recordedAt": int(time.time() * 1000),
        "gates": {result.name: result.duration_ms for result in results},
    }
    try:
        path = sgate.repo.GATE_RUN_JSON
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(document, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    except OSError as exc:
        logger.warning(f"could not record how long the gates took: {exc}")


#: The name the control phase is reported under. It is a gate of the suite in
#: every sense but one: it runs alone, after the others.
CONTROLS_PHASE_NAME = "the fast gates can fail"

#: The command of the control phase: the sabotage harness, without the
#: Gradle-backed controls (those need a daemon, and this phase runs in the
#: fast loop).
CONTROLS_PHASE_COMMAND = "python3 scripts/check-gate-wiring.py --can-fail"


def _run_controls_phase(repo_root: str) -> GateResult:
    """Prove that the gates can fail — the phase that edits the tree.

    Every gate has a control: a change that must make it fail. The harness
    applies the change, runs the gate, and puts the file back. A gate whose
    control does not make it fail is a gate that is green forever — it was
    found that way: a link-grammar gate whose pattern matched nothing, in a
    suite that reported every gate as passed.

    The phase runs *after* the gates, alone: the harness edits files in the
    working tree, and a gate reading the tree while it is edited reads a tree
    that is not there.
    """
    start = time.monotonic()
    command = [sys.executable, "scripts/check-gate-wiring.py", "--can-fail"]
    try:
        completed = subprocess.run(
            command,
            cwd=repo_root,
            capture_output=True,
            text=True,
            timeout=CONTROLS_PHASE_TIMEOUT,
        )
    except subprocess.TimeoutExpired:
        return GateResult(
            name=CONTROLS_PHASE_NAME,
            mode="blocking",
            passed=False,
            exit_code=124,
            duration_ms=int((time.monotonic() - start) * 1000),
            error=f"the control phase did not finish in {CONTROLS_PHASE_TIMEOUT}s",
        )
    duration = int((time.monotonic() - start) * 1000)
    return GateResult(
        name=CONTROLS_PHASE_NAME,
        mode="blocking",
        passed=completed.returncode == 0,
        exit_code=completed.returncode,
        duration_ms=duration,
        error="" if completed.returncode == 0 else (completed.stdout + completed.stderr)[-4000:],
        stdout=completed.stdout,
        stderr=completed.stderr,
    )


@click.command("run-all")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
@click.option("--summary", "summary_only", is_flag=True,
              help="Print one line per failing gate instead of the full report")
@click.option("--concurrency", "-j", default=8, show_default=True, help="Number of parallel workers")
@click.option("--gate", "gate_filters", multiple=True,
              help="Run only gates whose name contains any of these substrings")
@click.option("--with-controls", "with_controls", is_flag=True,
              help="Also prove that the gates can fail (runs after the gates, alone)")
def run_all(emit_json: bool, summary_only: bool, concurrency: int,
            gate_filters: tuple[str, ...], with_controls: bool) -> None:
    """Run all gates from static-gates.sh registry in parallel.

    With `--with-controls` the suite also proves that the gates *can* fail: the
    sabotage harness runs after the parallel pass, because it edits the tree
    the gates read.

    Exit codes:
      0 = all blocking gates passed
      1 = one or more blocking gates failed
    """
    gates = _load_static_gates()

    if gate_filters:
        gates = [
            (mode, name, cmd)
            for mode, name, cmd in gates
            if any(f.lower() in name.lower() for f in gate_filters)
        ]

    if not gates:
        # Exiting 0 here reported success for a run that executed zero gates, and
        # verified it: blanking scripts/ci/static-gates.sh made `sgate run-all` print
        # "No gates found." in yellow and exit 0 — a green `static` job that had
        # checked nothing. An empty registry is not a suite that passed; it is a
        # suite that is not there, which is the one fact a caller cannot infer from
        # a zero. `import sgate` does not catch this either: load_gates() reads
        # _registry.GATES, a separate Python list, while this branch reads
        # static-gates.sh — emptying either one leaves the other intact.
        if emit_json:
            click.echo(
                json.dumps(
                    {
                        "error": "no gates found — the registry at "
                        f"{sgate.repo.STATIC_GATES_SH} declared none, so nothing was "
                        "evaluated. This is not a pass.",
                        "gates": [],
                    }
                )
            )
        else:
            click.secho(
                f"No gates found in {sgate.repo.STATIC_GATES_SH}: zero gates were "
                "evaluated. Treating this as a failure, because a suite that ran "
                "nothing and a suite that passed are different facts and a zero "
                "cannot say which.",
                fg="red",
                err=True,
            )
        raise SystemExit(VALIDATION_FAILURE)

    results: list[GateResult] = []
    results_lock = threading.Lock()

    def worker(mode: str, name: str, cmd: str) -> GateResult:
        r = _run_gate_subprocess(mode, name, cmd)
        with results_lock:
            results.append(r)
        return r

    console = Console()

    with Progress(
        SpinnerColumn(),
        TextColumn("[progress.description]{task.description}"),
        BarColumn(),
        TaskProgressColumn(),
        console=console,
        transient=True,
    ) as progress:
        task = progress.add_task(f"[cyan]Running {len(gates)} gates...", total=len(gates))

        with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
            futures = {
                executor.submit(worker, mode, name, cmd): (name, cmd)
                for mode, name, cmd in gates
            }
            for future in concurrent.futures.as_completed(futures):
                _ = future.result()
                progress.advance(task)

    if with_controls:
        # After the parallel pass, and alone: the harness edits the tree.
        results.append(_run_controls_phase(str(sgate.repo.ROOT)))

    # Sort: blocking first, then advisory; alphabetically within group
    order = {"blocking": 0, "advisory": 1}
    results.sort(key=lambda r: (order.get(r.mode, 2), r.name))

    # What this run cost. The run happened anyway; writing down what it took is free,
    # and `sgate gate-costs` is what reads it.
    _write_gate_run(results)

    blocking = [r for r in results if r.mode == "blocking"]
    advisory = [r for r in results if r.mode == "advisory"]
    blocking_failed = [r for r in blocking if not r.passed]
    overall_ok = all(r.passed for r in blocking)

    if emit_json:
        out = {
            "total": len(results),
            "blocking": {"total": len(blocking), "passed": sum(1 for r in blocking if r.passed)},
            "advisory": {"total": len(advisory), "passed": sum(1 for r in advisory if r.passed)},
            "blocking_failed": [r.name for r in blocking_failed],
            "advisory_failed": [r.name for r in advisory if not r.passed],
            "passed": overall_ok,
            "gates": [
                {
                    "name": r.name,
                    "mode": r.mode,
                    "passed": r.passed,
                    "exit_code": r.exit_code,
                    "duration_ms": r.duration_ms,
                    "error": r.error,
                }
                for r in results
            ],
        }
        click.echo(json.dumps(out, indent=2))
        raise SystemExit(0 if overall_ok else 1)

    # Human-readable
    from rich.panel import Panel
    from rich.table import Table

    if summary_only:
        # One line per failing gate, and nothing else.
        #
        # The full report prints a table of every gate and a panel of every failing
        # gate's output — which is right for a human reading a terminal and wrong
        # for an agent that has to decide what to do next: the table is forty rows
        # of "pass", and the panels repeat what the gate already said. What a
        # reader needs from a failing run is the list of failures and, for each,
        # the line that names it.
        for r in results:
            if r.passed:
                continue
            reason = _failure_line(r)
            click.echo(f"FAIL  {r.name}: {reason}")
        advisory_failed = [r for r in advisory if not r.passed]
        if advisory_failed:
            # An advisory failure does not fail the build, so it does not belong
            # in the exit code — but it is a failure, and a summary that printed
            # "FAIL <name>" and then "OK" would read as a contradiction.
            click.echo(
                f"WARN  {len(advisory_failed)} advisory gate(s) failed (they do not block): "
                f"{', '.join(r.name for r in advisory_failed)}"
            )
        if overall_ok:
            click.echo(f"OK    {len(blocking)} blocking gate(s) passed")
        else:
            click.echo(
                f"FAIL  {len(blocking_failed)}/{len(blocking)} blocking gate(s) failed: "
                f"{', '.join(r.name for r in blocking_failed)}"
            )
        raise SystemExit(0 if overall_ok else 1)

    if overall_ok:
        summary = f"[green]✓ All {len(blocking)} blocking gates passed[/green]"
    else:
        names = ", ".join(r.name for r in blocking_failed)
        summary = f"[red]✗ {len(blocking_failed)}/{len(blocking)} blocking failed: {names}[/red]"
    console.print(Panel(summary, title="[bold]sgate run-all[/bold]"))

    def make_table(title: str, rows: list[GateResult], color: str) -> Table:
        t = Table(title=title, show_header=True)
        t.add_column("Gate", style=color)
        t.add_column("Status", width=14)
        t.add_column("Duration", justify="right", style="dim")
        for r in rows:
            if r.passed:
                status = f"[green]✓ pass[/green]"
            elif r.exit_code == 127:
                status = "[red]✗ miss[/red]"
            elif r.exit_code == 124:
                status = "[red]✗ timeout[/red]"
            elif r.payload_passed is False:
                status = "[red]✗ payload[/red]"
            else:
                status = f"[red]✗ exit={r.exit_code}[/red]"
            t.add_row(r.name, status, f"{r.duration_ms}ms")
        return t

    if blocking:
        console.print(make_table(
            f"Blocking Gates ({sum(1 for r in blocking if r.passed)}/{len(blocking)} passed)",
            blocking, "cyan",
        ))
    if advisory:
        console.print(make_table(
            f"Advisory Gates ({sum(1 for r in advisory if r.passed)}/{len(advisory)} passed)",
            advisory, "yellow",
        ))

    # Print what each failing gate said. Every gate here runs with
    # `capture_output=True`, so without this the whole table is the entire
    # report: a reader learns that "gate scripts' own unit tests" exited 1 and
    # nothing else, in a job whose log has no other copy of the output. That is
    # the failure mode this repo treats as loud — a gate that cannot be read
    # cannot be fixed from its log.
    for r in results:
        if r.passed:
            continue
        body = r.error or ""
        for stream_name, text in (("stdout", r.stdout), ("stderr", r.stderr)):
            if not text.strip():
                continue
            lines = text.splitlines()
            tail = lines[-OUTPUT_TAIL_LINES:]
            marks = [line for line in lines[:-OUTPUT_TAIL_LINES] if FAILURE_LINE.search(line)]
            body += f"\n{stream_name} ({len(lines)} lines):\n"
            if marks:
                body += (
                    f"[the {len(marks)} line(s) above the tail that name a failure]\n"
                )
                body += "\n".join(marks[:OUTPUT_TAIL_LINES])
                body += "\n[...tail...]\n"
            body += "\n".join(tail)
        if not body:
            body = "(the gate produced no output at all)"
        console.print(Panel(body, title=f"[bold red]{r.name}[/bold red]", border_style="red"))

    raise SystemExit(0 if overall_ok else 1)


register(run_all)
