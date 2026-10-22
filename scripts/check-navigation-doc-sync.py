#!/usr/bin/env python3
"""check-navigation-doc-sync.py — the navigation doc must say what the code does.

The defect this catches
-----------------------
`docs/navigation.md` is a pointer: an agent that reads it learns which route
opens which screen, which deep link opens what, and how the nested graphs are
seeded. It is pointed at from the nav3 skill, so a false row in it is worse
than no row — the agent builds on it.

Two rows of that table went false on 2026-10-22 and nothing noticed. The
deep-link row for `singularity://view/{viewId}` still named `SavedAgendaEdit`
after the code moved to `SavedAgendaResults`, and the platform-differences row
still said the desktop tasks stack pushes the `Detail` start onto the stack
after the code changed to seed the stack with the requested route itself.

Why the existing gates did not catch it
---------------------------------------
`doc links` checks that the links in the docs resolve. It cannot see that a
*claim* about behaviour has stopped being true — the link is fine, the sentence
is wrong. This gate reads the claim and the code and compares them.

What it compares
----------------
  * **Deep links.** Every `singularity://…` row of the deep-link table names
    the types it opens. Those types must appear in the matcher that implements
    the link, and every link the code declares must have a row.
  * **The tasks stack.** A row or a paragraph that says the tasks stack is
    pushed onto must agree with the code: the platform assemblers seed the
    stack with the requested route, they do not push a second route onto it.

Both are text comparisons, so they are a floor and not a proof: they catch a
claim that names a type the code does not name, and a claim of a push the code
does not make. The runtime tests are what prove the behaviour.

Usage:
    scripts/check-navigation-doc-sync.py            # human-readable report
    scripts/check-navigation-doc-sync.py --quiet    # findings only, for CI
    scripts/check-navigation-doc-sync.py --check    # fail when anything is found
    scripts/check-navigation-doc-sync.py --json     # JSON instead of text

Exit code is 0 by default and 1 under `--check` when anything is found.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

import click

from sgate.commands.base import run

CLEAN = 0
VALIDATION_FAILURE = 1

#: The tree to check. The pre-push hook runs this script against a throwaway
#: checkout of the tree being pushed, while the script itself comes from the
#: repository the hook runs in, so the root is an argument and not a derivation.
DEFAULT_ROOT = Path(__file__).resolve().parent.parent

#: The navigation doc, relative to the root.
DOC_RELATIVE = Path("docs/navigation.md")

#: The sources that declare a deep link, and the sources that match one.
SOURCES_RELATIVE = Path("shared/src/commonMain")

#: A row of the deep-link table: `| `link` | owner — `Spec` | tab | screen |`.
LINK_ROW = re.compile(
    r"^\|\s*`(?P<link>[^`]+)`\s*\|\s*(?P<owner>[^|]*?)\s*\|\s*(?P<tab>[^|]*?)\s*\|\s*(?P<screen>[^|]*?)\s*\|\s*$"
)

#: The type names a cell names: `AgendaGraph(Today)` → `AgendaGraph`, `Today`.
TYPE_NAME = re.compile(r"\b([A-Z][A-Za-z0-9_]*)\b")

#: `object TaskLinkSpec {` — a link the code declares.
LINK_SPEC = re.compile(r"^object\s+([A-Za-z_][A-Za-z0-9_]*LinkSpec)\b", re.MULTILINE)

#: `class TaskDeepLink : AppDeepLink {` — the matcher that implements a link.
LINK_MATCHER = re.compile(r"^class\s+([A-Za-z_][A-Za-z0-9_]*DeepLink)\b", re.MULTILINE)

#: Where the tasks stack is created — the platform assemblers own the seed.
TASKS_ENTRY_MARKER = "entry<AppDestination.TasksGraph>"

#: What a push onto a nav3 stack looks like.
STACK_PUSH = re.compile(r"\.add\(|\.push\(|\.plus\(")


def doc_text(root: Path) -> str:
    doc = root / DOC_RELATIVE
    return doc.read_text(encoding="utf-8") if doc.exists() else ""


def link_rows(text: str) -> list[dict[str, str]]:
    """The deep-link rows of the navigation doc."""
    rows: list[dict[str, str]] = []
    for line in text.splitlines():
        if not line.lstrip().startswith("|"):
            continue
        match = LINK_ROW.match(line)
        if not match:
            continue
        link = match.group("link")
        if not link.startswith("singularity://"):
            continue
        rows.append(match.groupdict())
    return rows


def matcher_sources(root: Path) -> dict[str, Path]:
    """Every deep-link matcher in the sources — class name → file."""
    found: dict[str, Path] = {}
    for path in sorted((root / SOURCES_RELATIVE).rglob("*.kt")):
        source = path.read_text(encoding="utf-8")
        for name in LINK_MATCHER.findall(source):
            found[name] = path
    return found


def declared_specs(root: Path) -> set[str]:
    """Every link the code declares — `TaskLinkSpec` and the rest."""
    names: set[str] = set()
    for path in sorted((root / SOURCES_RELATIVE).rglob("*.kt")):
        names.update(LINK_SPEC.findall(path.read_text(encoding="utf-8")))
    return names


def tasks_stack_is_pushed(root: Path) -> bool:
    """Whether the code pushes a route onto the tasks stack instead of seeding it."""
    assemblers = [
        root / "shared/src/jvmMain/kotlin/com/singularity/todo/feature/nav/JvmNavEntries.kt",
        root / "shared/src/androidMain/kotlin/com/singularity/todo/feature/nav/AndroidNavEntries.kt",
    ]
    for path in assemblers:
        if not path.exists():
            continue
        source = path.read_text(encoding="utf-8")
        marker = source.find(TASKS_ENTRY_MARKER)
        if marker < 0:
            continue
        following = source.find("\n    entry<", marker)
        block = source[marker : following if following > 0 else len(source)]
        if STACK_PUSH.search(block):
            return True
    return False


def doc_blocks(text: str) -> list[str]:
    """The doc's blocks: every table row on its own, every paragraph as one."""
    blocks: list[str] = []
    paragraph: list[str] = []
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped:
            if paragraph:
                blocks.append("\n".join(paragraph))
                paragraph = []
            continue
        if stripped.startswith("|"):
            if paragraph:
                blocks.append("\n".join(paragraph))
                paragraph = []
            blocks.append(stripped)
            continue
        paragraph.append(stripped)
    if paragraph:
        blocks.append("\n".join(paragraph))
    return blocks


def mismatches(root: Path) -> list[str]:
    """The claims the navigation doc makes that the code does not support."""
    findings: list[str] = []
    text = doc_text(root)
    matchers = matcher_sources(root)
    specs = declared_specs(root)
    doc_relative = DOC_RELATIVE.as_posix()

    documented: set[str] = set()
    for row in link_rows(text):
        owner = row["owner"]
        spec_match = re.search(r"`([A-Za-z_][A-Za-z0-9_]*LinkSpec)`", owner)
        if not spec_match:
            findings.append(
                f"{doc_relative}: the row for `{row['link']}` names no link spec",
            )
            continue
        spec = spec_match.group(1)
        documented.add(spec)
        matcher_name = spec.replace("LinkSpec", "") + "DeepLink"
        matcher_path = matchers.get(matcher_name)
        if matcher_path is None:
            findings.append(
                f"{doc_relative}: the row for `{row['link']}` names "
                f"`{spec}`, but no matcher `{matcher_name}` exists in the sources",
            )
            continue
        source = matcher_path.read_text(encoding="utf-8")
        matcher_relative = matcher_path.relative_to(root).as_posix()
        for cell in ("tab", "screen"):
            for type_name in TYPE_NAME.findall(row[cell]):
                if type_name not in source:
                    findings.append(
                        f"{doc_relative}: the row for `{row['link']}` says the "
                        f"{cell} is `{type_name}`, but `{matcher_relative}` "
                        f"never names it",
                    )

    for spec in sorted(specs - documented):
        findings.append(
            f"{doc_relative}: the code declares `{spec}`, and no deep-link row "
            f"in the doc names it",
        )

    pushed = tasks_stack_is_pushed(root)
    for block in doc_blocks(text):
        lowered = block.lower()
        if "task" not in lowered or "push" not in lowered:
            continue
        claims_push = True
        if claims_push != pushed:
            state = "pushes a route onto the tasks stack" if pushed else "seeds the tasks stack"
            findings.append(
                f"{doc_relative}: the doc says the tasks stack is pushed onto, "
                f"and the code {state}: {block.splitlines()[0][:120]}",
            )

    return findings


@click.command()
@click.option("--quiet", is_flag=True, help="Findings only, for CI.")
@click.option("--check", is_flag=True, help="Fail when anything is found.")
@click.option("--json", "as_json", is_flag=True, help="Emit JSON instead of text.")
@click.option(
    "--root",
    type=click.Path(file_okay=False, path_type=Path),
    default=DEFAULT_ROOT,
    help="The tree to check. Defaults to the repository the script lives in.",
)
@click.pass_context
def cli(ctx: click.Context, quiet: bool, check: bool, as_json: bool, root: Path) -> None:
    """Report the claims the navigation doc makes that the code does not support."""
    findings = mismatches(root)
    if as_json:
        click.echo(json.dumps({"findings": findings, "count": len(findings)}, indent=2))
    else:
        if not quiet:
            click.echo("check-navigation-doc-sync: the navigation doc against the code")
        for finding in findings:
            click.echo(f"  [doc-drift] {finding}")
        if not quiet:
            click.echo()
            click.echo(f"{len(findings)} claim(s) the doc makes that the code does not support.")
            click.echo(
                f"{DOC_RELATIVE.as_posix()} is a pointer: an agent reads it to learn what "
                "opens what. A row that names a type the code does not name sends it the wrong way.",
            )
    if findings and check:
        raise ctx.exit(VALIDATION_FAILURE)
    raise ctx.exit(CLEAN)


if __name__ == "__main__":
    raise SystemExit(run(cli))
