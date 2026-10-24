#!/usr/bin/env python3
"""check-workflow-exit-status.py — a `run:` block must not swallow its exit status.

## The defect

`.github/workflows/ci.yml` ended its Gradle step like this:

```yaml
run: >
  ./gradlew --continue --no-build-cache … :shared:detekt …
  2>&1 | tee build.log
```

In POSIX a pipeline's status is the status of its **last** command. GitHub's default
shell for `run:` is `bash -e {0}` — no `pipefail` — so the step's status was `tee`'s,
which is 0. Every Gradle failure inside it was discarded: failing tests, failing
detekt, a failed `koverReport`.

Measured on a pristine `origin/main` on 2026-10-16:

```
$ ./gradlew :shared:detekt
Analysis failed with 109 issues.
BUILD FAILED
$ gh run list --workflow ci.yml --branch main --limit 1
success  adaf9afd …
```

Same commit. The job that runs those tasks was green. The 109 findings had been
invisible not because nobody looked, but because nothing that read them *could* fail.

Two things made it look otherwise:

* the step's name is `Gradle (tests)`, and its comment block
  reasons at length about how each Gradle flag affects the verdicts;
* `check-test-runs.py --gradle-log build.log` reads the log on the very next steps, so
  the step reads as instrumented. It reads the log to exempt `UP-TO-DATE` tasks from
  its freshness floor; a **failed** task has run and written its XML exactly as a
  passing one has, so it read nothing that could have contradicted the green checkmark.

## What this gate checks

For every `run:` block in `.github/workflows/*.yml` whose body contains a pipeline
(`| tee`, `| grep`, `| tee x || true`, …), the block must opt into `pipefail` — either
`set -o pipefail` / `set -euo pipefail`, or an explicit status capture such as
`${PIPESTATUS[0]}` or `|| exit`.

A block that pipes *without* either is reported, because its status is the last
command's and the rest of the line cannot fail the step.

## What it does not check

Whether the pipeline is the right construct. `cmd | tee file` is the honest way to
keep a log; this gate is not arguing for removing it, only for keeping the status.

Usage:
    python3 scripts/check-workflow-exit-status.py
    python3 scripts/check-workflow-exit-status.py --quiet
"""

from __future__ import annotations

import pathlib
import re
import sys

import click
from sgate.commands.base import run

CLEAN = 0
VALIDATION_FAILURE = 1
IO_ERROR = 2

ROOT = pathlib.Path(__file__).resolve().parent.parent
WORKFLOWS = ROOT / ".github" / "workflows"

#: How a step opts out of the pipeline's status-swallowing.
PIPEFAIL_DECLARATIONS = (
    re.compile(r"^\s*set\s+-[a-zA-Z]*o\s+pipefail", re.M),
    re.compile(r"^\s*set\s+-[a-zA-Z]+[a-zA-Z]*pipefail", re.M),
    re.compile(r"\$\{PIPESTATUS\[\d+\]}"),
    re.compile(r"\$\{pipestatus\[\d+\]}"),
    # `cmd | tee f || exit 1` and `cmd | tee f || { …; }` — the pipeline is allowed to
    # fail as long as the step does not. Blunt (it catches the last command's status,
    # not the producer's), but it is a declared intent rather than a silent swallow,
    # which is what this gate is about.
    re.compile(r"\|\s*\S.*\|\|\s*(\{|exit|fail|false)", re.S),
)

#: `a | b` where both sides are commands, as opposed to `|` inside a string or a comment.
PIPELINE = re.compile(r"\S\s*\|\s*(?:tee|cat|grep|sed|awk|head|tail|sort|xargs|nc|jq)\b")


def _blocks(text: str) -> list[tuple[int, str]]:
    """Return (line number, body) for every `run:` value in a workflow.

    Composed with `yaml.compose` rather than a hand-rolled line scanner: a folded
    (`>`) and a literal (`|`) block both continue across indented lines, and a scanner
    that guesses where they end will eventually swallow the rest of the file and
    report against a block nobody wrote. The first version of this did exactly that,
    on `e2e.yml` and `release.yml`.

    A bare `run:` with no block scalar — a one-line command — is reported as itself.
    """
    import yaml

    blocks: list[tuple[int, str]] = []
    _walk_runs(yaml.compose(text), blocks)
    return blocks


def _walk_runs(node, out: list[tuple[int, str]]) -> None:
    """Collect every `run:` scalar anywhere in the document, at any depth."""
    if node is None or not hasattr(node, "value"):
        return
    if isinstance(node.value, list):
        for entry in node.value:
            # PyYAML yields (key, value) as a **tuple**, not a list. Checking for
            # `list` only made this walker return nothing at all, and the gate it was
            # written for passed on a repository containing the exact defect — a
            # scanner that finds no steps certifies nothing.
            if isinstance(entry, (list, tuple)) and len(entry) == 2:
                key, value = entry
                if key.value == "run" and hasattr(value, "value") and isinstance(value.value, str):
                    out.append((key.start_mark.line + 1, value.value))
                else:
                    _walk_runs(value, out)
            else:
                _walk_runs(entry, out)


def _strip_noise(body: str) -> str:
    """Drop comment lines, quoted strings and command substitutions.

    Three things are not pipelines, and each one has bitten this gate:

    * `# a | b` — a comment;
    * `'a|b'` — a pipe character inside a string is data;
    * `x=$(cmd | head -1)` — a **value capture**. Under the default `bash -e` an
      assignment whose command substitution fails fails the step, so these were never
      at risk; the step then checks `[ -z "$x" ]` and exits 1 itself. Flagging them
      would train a reader to ignore the gate, and it is `ci.yml`'s android step that
      uses the pattern most.
    * `mapfile -t X < <(find … | sort)` — a **process substitution**. `mapfile`'s own
      status is the block's; the substitution's is discarded by the shell, not by a
      pipeline, and there is nothing to recover it from.
    """
    kept = []
    for line in body.splitlines():
        stripped = line.strip()
        if stripped.startswith("#"):
            continue
        line = re.sub(r"'[^']*'|\"[^\"]*\"", "''", line)
        kept.append(_mask_substitutions(_mask_process_substitution(line)))
    return "\n".join(kept)


def _mask_process_substitution(line: str) -> str:
    """Blank out `<( … )` spans, the same way command substitutions are blanked."""
    out: list[str] = []
    depth = 0
    i = 0
    while i < len(line):
        if line.startswith("<(", i):
            depth += 1
            out.append("  ")
            i += 2
            continue
        if line[i] == ")" and depth > 0:
            depth -= 1
            out.append(" ")
            i += 1
            continue
        out.append(" " if depth > 0 else line[i])
        i += 1
    return "".join(out)


def _mask_substitutions(line: str) -> str:
    """Blank out `$( ... )` spans, tracking nesting so an inner `)` does not end it."""
    out: list[str] = []
    depth = 0
    i = 0
    while i < len(line):
        if line.startswith("$(", i):
            depth += 1
            out.append("  ")
            i += 2
            continue
        if line[i] == ")" and depth > 0:
            depth -= 1
            out.append(" ")
            i += 1
            continue
        out.append(" " if depth > 0 else line[i])
        i += 1
    return "".join(out)


def _display(path: pathlib.Path) -> str:
    """Repo-relative when it can be, absolute otherwise — tests pass temporary trees."""
    try:
        return str(path.relative_to(ROOT))
    except ValueError:
        return str(path)


def check_workflow(path: pathlib.Path) -> list[str]:
    text = path.read_text(encoding="utf-8")
    findings: list[str] = []
    for line_no, body in _blocks(text):
        scan = _strip_noise(body)
        # Line by line, not over the whole block: `\s` in the pattern matches a
        # newline, so a masked-out `$( … )` on one line and a leading `| sed …` on the
        # next read as one pipeline that does not exist. A cross-line match is
        # indistinguishable, in the report, from a real one.
        if not any(PIPELINE.search(stripped) for stripped in (l.strip() for l in scan.splitlines())):
            continue
        if any(pattern.search(scan) for pattern in PIPEFAIL_DECLARATIONS):
            continue
        findings.append(
            f"{_display(path)}:{line_no}: a `run:` block pipes but never declares "
            f"pipefail or captures PIPESTATUS — the step's status is the last command's, "
            f"so the producer cannot fail it"
        )
    return findings


@click.command()
@click.option("--quiet", is_flag=True, help="print findings only")
@click.pass_context
def cli(ctx: click.Context, quiet: bool) -> None:
    workflows = sorted(WORKFLOWS.glob("*.yml")) + sorted(WORKFLOWS.glob("*.yaml"))
    if not workflows:
        click.echo(f"::error::no workflows found under {WORKFLOWS}", err=True)
        raise ctx.exit(IO_ERROR)

    findings: list[str] = []
    for path in workflows:
        findings.extend(check_workflow(path))

    for finding in findings:
        click.echo(finding)

    if findings:
        click.echo(
            f"\n{len(findings)} step(s) whose exit status is the pipeline's last command "
            f"rather than its producer's.",
            err=True,
        )
        raise ctx.exit(VALIDATION_FAILURE)

    if not quiet:
        click.echo(f"OK — {len(workflows)} workflow(s), every piped `run:` block keeps its status")
    raise ctx.exit(CLEAN)




if __name__ == "__main__":
    raise SystemExit(run(cli))
