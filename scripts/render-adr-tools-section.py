#!/usr/bin/env python3
"""render-adr-tools-section.py — write the "ADR tools" block into the agent skills.

Why a generator (2026-10-11, #526)
----------------------------------
A skill is a hand-written description of code that nobody diffs against it. In one
session the drift was found five times over:

| Claim in a skill                              | Reality                        |
|-----------------------------------------------|--------------------------------|
| `write_adr` "Create **or update**"             | it only creates; update did not exist |
| "Add entries to `docs/decisions/`"             | invites writing `.md` by hand |
| no mention of `adr validate`                   | it existed and was the gate   |
| `DIGEST.md` is the "single source of truth"   | it is in `.gitignore`         |
| MCP tools listed                              | did not match the catalog     |

None of these are lies. Each was true when written and left behind by the thing it
described — the README numbers this repository already guards with
`check-readme-claims.py`, applied to the skill surface instead.

    python3 scripts/render-adr-tools-section.py            # rewrite the blocks
    python3 scripts/render-adr-tools-section.py --check    # report drift, write nothing

`scripts/check-skill-drift.py` is the gate; it calls `--check` and prints the diff.

Where the facts come from
-------------------------
* Command names, usage and summaries — `AdrSurface.kt`, the same list
  `singularity-adr --help` prints.
* MCP tool names and descriptions — the `adr` domain entries of `McpToolCatalog.kt`,
  which is the single registry the MCP server itself serves from.

Both are *parsed*, not executed. `scripts/ci/static-gates.sh` is specified to finish
without a JVM, and shelling out to a built CLI would make the cheapest gate in the
repository the slowest — a gate that is usually skipped is a gate that is usually
lying. The cost of parsing is that a declaration it cannot read must be an error, not
a silent omission: an unread entry would mean the skill quietly drops a command while
still looking complete. Every parse here fails loudly on anything it does not
understand.

What is never touched
---------------------
Text outside the markers. The generated block is a dashboard bolted to a hand-written
document; the document is the author's, and the generator has no opinion about it.
"""

from __future__ import annotations

import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

SURFACE_KT = ROOT / ("adr-cli/src/main/kotlin/com/singularity/todo/adr/cli"
                     "/AdrSurface.kt")
CATALOG_KT = ROOT / ("shared/src/commonMain/kotlin/com/singularity/todo/mcp"
                     "/McpToolCatalog.kt")

BEGIN = "<!-- BEGIN GENERATED: adr-tools -->"
END = "<!-- END GENERATED: adr-tools -->"

# The documents whose command reference is generated. Both describe the ADR tools, and
# both had drifted; the rest of each file is prose and stays hand-written.
#
# One of the two is no longer a skill. `singularity-todo-mcp-server` moved to
# `docs/architecture/mcp-server.md` on 2026-10-15 because it described the state of the
# code rather than a procedure, and this tuple points at wherever it now lives. That is
# the seam that matters: `check-skill-drift.py` iterates THIS tuple, so a path that is
# wrong here is invisible there. Deleting an entry would be worse than leaving it stale —
# the gate would keep passing while checking one document instead of two, and report
# "OK — 1 document(s)" without anyone reading the number. The count is asserted by
# `test_render_targets_two_documents_and_both_exist`.
DOCUMENTS = (
    ROOT / ".agents/skills/singularity-todo-decisions-workflow/SKILL.md",
    ROOT / "docs/architecture/mcp-server.md",
)

# Retained under the historical name: it is imported as `renderer.SKILLS` by
# check-skill-drift.py and by its own tests. Renaming it here would break those callers
# to make a name accurate, which is the wrong trade.
SKILLS = DOCUMENTS

# Parsing rules for `AdrSurface.kt` declarations:
#
# Rule 1: `ctor` (AdrCommandSpec or AdrFlagSpec) starts at column 0 of a line.
#   Rationale: multiline declarations are rejected, not guessed at. If a developer
#   reformats a long call across lines, the gate fires with a diagnostic rather than
#   silently dropping the entry. See test_an_unreadable_declaration_is_an_error_not_a_skip.
#
# Rule 2: arguments are double-quoted strings only. Single quotes are not accepted.
#   Rationale: Kotlin allows both, but the codebase uses double quotes consistently.
#
# Rule 3: the third argument (summary) is optional and captured via `(?:...)?`.
#   Rationale: AdrFlagSpec has two args, AdrCommandSpec has three. The regex must
#   not fail when the optional third arg is absent.
#
# Rule 4: a trailing comma before `)` is accepted and stripped by `\s*,?\s*`.
#   Rationale: Kotlin trailing commas are legal and common in the codebase.
CALL_RE = re.compile(
    r'^\s*(?P<ctor>AdrCommandSpec|AdrFlagSpec)\(\s*'
    r'"(?P<a>[^"]*)"\s*,\s*'
    r'"(?P<b>[^"]*)"\s*'
    r'(?:,\s*"(?P<c>[^"]*)"\s*)?\)\s*,?\s*$'
)


class ParseError(RuntimeError):
    """A declaration the renderer could not read. Never skipped, never guessed."""


def commands() -> list[tuple[str, str, str]]:
    """(name, usage, summary) for every CLI subcommand."""
    text = SURFACE_KT.read_text(encoding='utf-8')
    start = text.index('val commands: List<AdrCommandSpec>')
    end = text.index('val globalFlags:', start)
    return _parse_block(text[start:end].splitlines(), 'AdrCommandSpec', SURFACE_KT)


def global_flags() -> list[tuple[str, str]]:
    """(flag, summary) for every global flag.

    `AdrFlagSpec` takes two arguments and `AdrCommandSpec` three, so the third slot
    is None for flags and the summary is the second argument in that case. Getting
    this wrong renders "--json — None" into a skill, which is precisely the kind of
    confident nonsense a generated section can produce if nobody runs it.
    """
    text = SURFACE_KT.read_text(encoding='utf-8')
    start = text.index('val globalFlags: List<AdrFlagSpec>')
    end = text.index('val jsonCommands:', start)
    return [(a, c if c is not None else b)
            for a, b, c in _parse_block(text[start:end].splitlines(), 'AdrFlagSpec', SURFACE_KT)]


def _parse_block(lines: list[str], ctor: str, path: pathlib.Path) -> list[tuple[str, ...]]:
    out: list[tuple[str, ...]] = []
    in_doc = False
    for lineno, line in enumerate(lines, 1):
        stripped = line.strip()
        # KDoc blocks (`/** ... */`) and line comments are prose about the
        # declarations, not declarations. Skipping them is reading, not guessing:
        # anything else that is not a `ctor(...)` call is an error below.
        if in_doc:
            if stripped.endswith('*/'):
                in_doc = False
            continue
        if stripped.startswith('/**'):
            in_doc = not stripped.endswith('*/')
            continue
        if stripped.startswith('//') or stripped.startswith('*'):
            continue
        if not stripped or stripped in ('listOf(', ')'):
            continue
        if not stripped.startswith(ctor + '('):
            if stripped.startswith('val '):
                continue
            raise ParseError(f'{path.name}:{lineno}: unparseable line in {ctor} block: {stripped!r}')
        m = CALL_RE.match(line)
        if not m:
            raise ParseError(
                f'{path.name}:{lineno}: {ctor} declaration spans several lines or has a shape '
                f'this renderer does not know: {stripped!r}'
            )
        out.append((m.group('a'), m.group('b'), m.group('c')))
    if not out:
        raise ParseError(f'{path.name}: {ctor} block is empty — nothing to generate')
    return out


def mcp_adr_tools() -> list[tuple[str, str]]:
    """(name, description) for every MCP tool in the `adr` domain.

    Read from the catalog the MCP server itself serves, so a tool's description in a
    skill cannot disagree with what an agent is actually told when it calls it.

    Parsed by splitting on `ToolEntry(` and reading one chunk at a time, not by
    matching `name = ... .*? description = ...` across the file. That single regex
    was tried and it is wrong in a way that does not announce itself: a description
    built by string concatenation pushes the `annotations` line onto another row, the
    lazy match cannot close there, and the pattern walks on to the *next* entry's
    description. The result rendered `write_adr` with `list_adrs`'s description —
    plausible, confident, and off by one tool.
    """
    text = CATALOG_KT.read_text(encoding='utf-8')
    start = text.index('// ── ADR ─')
    end = text.index('// ── AI Write Tools ─', start)
    block = text[start:end]

    tools: list[tuple[str, str]] = []
    for chunk in block.split('ToolEntry(')[1:]:
        name = re.search(r'^\s*name\s*=\s*"([^"]+)"', chunk, re.MULTILINE)
        if not name:
            raise ParseError(f'{CATALOG_KT.name}: ToolEntry without a name')
        desc = re.search(
            r'^\s*description\s*=\s*(?P<body>.*?)(?=^\s*annotations\s*=)',
            chunk,
            re.MULTILINE | re.DOTALL,
        )
        if not desc:
            raise ParseError(f'{CATALOG_KT.name}: tool {name.group(1)!r} has no description')
        # Kotlin string concatenation across lines is one sentence to a reader.
        literals = re.findall(r'"((?:[^"\\]|\\.)*)"', desc.group('body'))
        tools.append((name.group(1), ''.join(literals).replace('\\"', '"')))
    if not tools:
        raise ParseError(
            f'{CATALOG_KT.name}: no `adr`-domain ToolEntry declarations found — the catalog '
            'shape changed and this renderer has to follow it'
        )
    return tools


def render() -> str:
    """The block that goes between the markers. Deterministic, or it cannot be gated."""
    lines: list[str] = [BEGIN, '']
    lines += ['#### `singularity-adr` — the ADR command line', '']
    lines.append('Run it from the repository root. Every command takes `--json` for')
    lines.append('machine-readable output; exit code `2` means the command line was wrong,')
    lines.append('`1` means the operation failed.')
    lines.append('')
    lines.append('| Command | What it does |')
    lines.append('|---|---|')
    for name, usage, summary in commands():
        del name
        lines.append(f'| `singularity-adr {usage}` | {summary} |')
    lines.append('')
    lines.append('Global flags:')
    lines.append('')
    for flag, summary in global_flags():
        lines.append(f'- `{flag}` — {summary}')
    lines.append('')
    lines += ['#### MCP tools — `adr` domain', '']
    lines.append('These are the tools an agent calls over the MCP server. The descriptions')
    lines.append('below are the same strings the server sends, so they cannot drift.')
    lines.append('')
    lines.append('| Tool | What it does |')
    lines.append('|---|---|')
    for name, desc in mcp_adr_tools():
        lines.append(f'| `{name}` | {desc} |')
    lines.append('')
    lines += [
        '#### Before you push', '',
        '```bash',
        'singularity-adr validate   # what check_adr_status.py will enforce',
        'singularity-adr stale      # decisions the gate will reject next push',
        '```', '',
        'Both wrap the repository\'s own Python gates rather than reimplementing them, so a',
        'preview and the gate cannot disagree.',
    ]
    lines += ['', END]
    return '\n'.join(lines)


def current_block(path: pathlib.Path) -> str | None:
    """The block currently committed in [path], or None if there are no markers."""
    text = path.read_text(encoding='utf-8')
    if BEGIN not in text or END not in text:
        return None
    start = text.index(BEGIN)
    return text[start:text.index(END, start) + len(END)]


def splice(text: str, block: str) -> str:
    """Replace the generated block, or append it. Everything else is untouched."""
    if BEGIN in text and END in text:
        start = text.index(BEGIN)
        end = text.index(END, start) + len(END)
        return text[:start] + block + text[end:]
    return text.rstrip('\n') + '\n\n' + block + '\n'


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--check', action='store_true',
                        help='report drift without writing')
    args = parser.parse_args()

    try:
        block = render()
    except (ParseError, ValueError) as exc:
        print(f'render-adr-tools-section: {exc}', file=sys.stderr)
        return 1

    drifted: list[pathlib.Path] = []
    missing: list[pathlib.Path] = []
    for skill in SKILLS:
        if not skill.is_file():
            missing.append(skill)
            continue
        existing = current_block(skill)
        if existing == block:
            continue
        drifted.append(skill)
        if not args.check:
            skill.write_text(splice(skill.read_text(encoding='utf-8'), block),
                             encoding='utf-8')

    for path in missing:
        print(f'render-adr-tools-section: {path} does not exist')

    if args.check:
        for path in drifted:
            print(f'DRIFT: {path.relative_to(ROOT)} — the "ADR tools" section no longer '
                  f'matches the code')
        if drifted or missing:
            print('')
            print('regenerate with: python3 scripts/render-adr-tools-section.py')
            return 1
        print(f'render-adr-tools-section: OK — {len(SKILLS)} skill(s) match the tool surface')
        return 0

    for path in drifted:
        print(f'updated: {path.relative_to(ROOT)}')
    if not drifted and not missing:
        print('already up to date')
    return 1 if missing else 0


if __name__ == '__main__':
    sys.exit(main())