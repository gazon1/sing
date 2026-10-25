#!/usr/bin/env python3
"""check_ci_workflow_shape.py — the shape of the CI workflow's Gradle block.

Three checks read the same file — `.github/workflows/ci.yml` — and each of them
was a separate gate:

  - ``check-test-task-inputs.py`` — a test task whose inputs are declared;
  - ``check-gradle-test-population.py`` — one test population per job;
  - ``check-build-output-readers.py`` — a reader of build output runs after the
    invocation that rewrites it.

They were three CI rows, three registry entries and three places to look when
one of them failed, for three properties of one file. They are one gate now:
the wrapper runs all three, prints every failure rather than the first, and
exits non-zero if any of them failed.

The three checks are still three checks. Each keeps its own script, its own
unit tests and its own positive control — a control proves that a check can
fail, and a cluster with one control proves only that *something* in it can
fail. `--only <name>` runs one member, which is how the control for each
member drives it.

See docs/decisions/2026-10-20-gate-clusters-and-the-two-registries.md.
"""
from __future__ import annotations

import pathlib
import shlex
import subprocess
import sys

import click


# This script must resolve `sgate` against *this* checkout: an editable
# install of a sibling worktree is a different tree under the same name.
_SCRIPTS_SRC = str(pathlib.Path(__file__).resolve().parent / "src")
if _SCRIPTS_SRC not in sys.path:
    sys.path.insert(0, _SCRIPTS_SRC)

from sgate.commands.base import CLEAN, IO_ERROR, VALIDATION_FAILURE, run

ROOT = pathlib.Path(__file__).resolve().parent.parent


class Member:
    """One check in the cluster: its display name and how to run it."""

    def __init__(self, name: str, command: str) -> None:
        self.name = name
        self.command = command

    def run(self) -> tuple[int, str]:
        # The command is spelled out in full — `python3 scripts/check-x.py` —
        # so the wiring gate's delegation scan can see that this gate runs that
        # script. A member list written as bare paths would hide the calls from
        # the very scan that exists to prove a gate is reachable.
        argv = shlex.split(self.command)
        if argv and argv[0] == "python3":
            argv[0] = sys.executable
        result = subprocess.run(
            argv,
            capture_output=True,
            text=True,
            cwd=ROOT,
            timeout=300,
        )
        output = (result.stdout or "") + (result.stderr or "")
        return result.returncode, output.strip()


MEMBERS: list[Member] = [
    Member(
        "test-task external inputs declared",
        "python3 scripts/check-test-task-inputs.py",
    ),
    Member(
        "gradle invocations keep one test population",
        "python3 scripts/check-gradle-test-population.py",
    ),
    Member(
        "build-output readers precede the invocations that rewrite them",
        "python3 scripts/check-build-output-readers.py",
    ),
]


def _slug(name: str) -> str:
    out = []
    for char in name.lower():
        out.append(char if char.isalnum() else "_")
    return "_".join(part for part in "".join(out).split("_") if part)


def _selected(only: str | None) -> list[Member]:
    if only is None:
        return MEMBERS
    for member in MEMBERS:
        if _slug(member.name) == only or member.name == only:
            return [member]
    raise click.ClickException(
        f"unknown member {only!r}. Known members: "
        + ", ".join(_slug(m.name) for m in MEMBERS)
    )


@click.command()
@click.option(
    "--only",
    default=None,
    help="Run one member of the cluster instead of all of them.",
)
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
@click.pass_context
def cli(ctx: click.Context, only: str | None, emit_json: bool) -> None:
    """Check the shape of the CI workflow's Gradle block."""
    members = _selected(only)

    results: list[dict[str, object]] = []
    failed = 0
    for member in members:
        try:
            code, output = member.run()
        except (OSError, subprocess.SubprocessError) as exc:
            code, output = IO_ERROR, f"could not run the check: {exc}"
        if code != 0:
            failed += 1
        results.append(
            {
                "name": member.name,
                "exit_code": code,
                "passed": code == 0,
                "output": output,
            }
        )

    if emit_json:
        import json

        click.echo(
            json.dumps(
                {"passed": failed == 0, "checks": results},
                indent=2,
            )
        )
        raise ctx.exit(VALIDATION_FAILURE if failed else CLEAN)

    for result in results:
        name = result["name"]
        if result["passed"]:
            click.echo(f"  ok  {name}")
            continue
        click.echo(f"  FAIL  {name} (exit {result['exit_code']})")
        output = str(result["output"])
        if output:
            for line in output.splitlines():
                click.echo(f"      {line}")

    if failed:
        click.echo(
            f"check_ci_workflow_shape.py: {failed} of {len(members)} check(s) failed"
        )
        raise ctx.exit(VALIDATION_FAILURE)

    click.echo(
        f"check_ci_workflow_shape.py: OK — {len(members)} check(s) on the CI workflow"
    )
    raise ctx.exit(CLEAN)


if __name__ == "__main__":
    raise SystemExit(run(cli))
