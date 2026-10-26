#!/usr/bin/env python3
"""check-mobile-isolation.py — the mobile build carries no external-agent code.

SC-011 of the external-agent feature says:

    The mobile build contains no external-agent or process-spawning code.

That sentence was a promise with nothing behind it. The feature's own artifacts
claimed an automated check for it, and no such check existed — the guarantee held
only by the accident that nobody had yet moved a file. This gate is the check.

What is forbidden, and why each one is forbidden
-------------------------------------------------

* The **external-agent packages** (`feature.ai.acp`, `feature.ai.external`). They
  are the feature's own code, and they are JVM-only because the protocol library
  they drive needs JDK 21 while this project keeps the Android target at
  JVM_11 bytecode. A commonMain file that reached into them would break the
  Android build — at compile time, loudly. The gate exists so that the break
  arrives with an explanation attached instead of as a compiler error about a
  classpath nobody remembers.

* The **protocol library** (`com.agentclientprotocol`). The same reason, one
  layer down: the library has no Android artifact, so an import of it in a
  mobile source set is a build that cannot be produced.

* The **process-spawning package** (`core.process`). The external agent is a
  third-party process the application starts, and the process tree it starts is
  the application's to end. On Android the application has no such business:
  there is no packaged external agent to supervise, and the code that manages a
  process tree has no reason to exist on a phone.

What the gate does NOT check, and why
-------------------------------------

The **dependency declaration**. `shared/build.gradle.kts` declares the protocol
library inside `jvmMain.dependencies`, and it is the *source* that decides what
the mobile build compiles: a dependency no mobile source set imports cannot
reach the mobile classpath, and moving it into `commonMain` without an import
would be a declaration of nothing. If someone does move it, the Android build
fails on the missing artifact — louder than this gate, and with the artifact's
name in the message.

The gate reads the **source trees**, not the compiled artefacts: the promise is
about what the mobile build contains, and a promise about a source tree is one
that can be checked on every change rather than only on a release build.

Usage:
    python3 scripts/check-mobile-isolation.py
"""

from __future__ import annotations

import json
import pathlib
import re
import sys

# `scripts/src` on sys.path, and it has to be there *before* the first import
# that uses it: this gate must resolve `sgate` against *this* checkout, not
# against whatever `sgate` happens to be installed.
_scripts_src = str(pathlib.Path(__file__).resolve().parent / "src")
_added = _scripts_src not in sys.path
if _added:
    sys.path.insert(0, _scripts_src)

import click
from sgate.commands.base import run

CLEAN = 0
VALIDATION_FAILURE = 1

ROOT = pathlib.Path(__file__).resolve().parent.parent

# What must not appear in them, and what each one is.
FORBIDDEN = (
    (
        re.compile(r"\bcom\.singularity\.todo\.feature\.ai\.acp\b"),
        "the external-agent protocol client",
    ),
    (
        re.compile(r"\bcom\.singularity\.todo\.feature\.ai\.external\b"),
        "the external-agent screen",
    ),
    (
        re.compile(r"\bcom\.agentclientprotocol\b"),
        "the Agent Client Protocol library",
    ),
    (
        re.compile(r"\bcom\.singularity\.todo\.core\.process\b"),
        "the process-spawning package",
    ),
)


def mobile_files(tree: pathlib.Path) -> list[pathlib.Path]:
    """Every source file the mobile build is made of."""
    files: list[pathlib.Path] = []
    for source_set in (
        tree / "shared" / "src" / "commonMain",
        tree / "shared" / "src" / "androidMain",
        tree / "androidApp" / "src",
    ):
        if not source_set.exists():
            continue
        for path in source_set.rglob("*.kt"):
            if any(part in {"build", ".gradle"} for part in path.parts):
                continue
            files.append(path)
    return files


def offenders(files: list[pathlib.Path], tree: pathlib.Path) -> list[str]:
    """The mobile files that reach for something the mobile build may not have."""
    findings: list[str] = []
    for path in files:
        text = path.read_text(encoding="utf-8", errors="replace")
        try:
            rel = path.relative_to(tree).as_posix()
        except ValueError:
            rel = path.as_posix()
        for pattern, what in FORBIDDEN:
            for match in pattern.finditer(text):
                line = text.count("\n", 0, match.start()) + 1
                findings.append(f"{rel}:{line}: {what}")
    return findings


@click.command()
@click.option(
    "--check",
    is_flag=True,
    help="Check the tree and exit non-zero on a finding (the default; the flag is what the gate runner passes)",
)
@click.option("--json", "emit_json", is_flag=True, help="Emit the verdict as JSON")
@click.option(
    "--root",
    default=None,
    help="the tree to check (default: the repository this script lives in)",
)
@click.pass_context
def cli(ctx: click.Context, check: bool, emit_json: bool, root: str | None) -> None:
    tree = pathlib.Path(root).resolve() if root else ROOT
    files = mobile_files(tree)
    if not files:
        click.echo(
            "ERROR: no mobile source files found — the source sets moved, and this "
            "gate is now checking nothing",
        )
        raise ctx.exit(VALIDATION_FAILURE)

    findings = offenders(files, tree)
    if findings:
        if emit_json:
            click.echo(
                json.dumps(
                    {
                        "gate": "mobile-isolation",
                        "verdict": "fail",
                        "filesChecked": len(files),
                        "findings": findings,
                    },
                ),
            )
        else:
            click.echo("The mobile build must carry no external-agent code:")
            for finding in findings:
                click.echo(f"  - {finding}")
            click.echo("")
            click.echo(
                "The external-agent feature is desktop-only: the protocol library needs "
                "JDK 21, this project keeps the Android target at JVM_11 bytecode, and "
                "there is no packaged agent to supervise on a phone.",
            )
        raise ctx.exit(VALIDATION_FAILURE)

    if emit_json:
        click.echo(
            json.dumps(
                {
                    "gate": "mobile-isolation",
                    "verdict": "pass",
                    "filesChecked": len(files),
                    "findings": [],
                },
            ),
        )
    else:
        click.echo(
            f"check-mobile-isolation: OK — {len(files)} mobile source files carry no "
            f"external-agent code",
        )
    raise ctx.exit(CLEAN)


if __name__ == "__main__":
    raise SystemExit(run(cli))
