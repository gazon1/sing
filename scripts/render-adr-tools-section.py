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
CATALOG_KT = ROOT / ("shared/src/commonMain/kotlin/com/singularity/todo/feature/ai"
                     "/McpToolCatalog.kt")
# The tool classes own their descriptions. The catalogue used to carry a second copy of
# every description and the two drifted apart in 32 of 39 tools -- including five where
# the catalogue described a different capability than the tool implements. The server
# builds its schema from the class constant, so the class is the fact; the docs read it
# from there too, and the duplication is gone rather than policed.
TOOLS_DIR = ROOT / ("shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/tools")

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
    r'^\s*(?P<ctor>AdrCommandSpec|AdrFlagSpec|AdrMcpToolSpec)\(\s*'
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


STRING_LITERAL_RE = re.compile(r'"((?:[^"\\]|\\.)*)"')
NAME_CONST_RE = re.compile(r'const val NAME\s*=\s*"([^"]+)"')
DESCRIPTION_CONST_RE = re.compile(r'const val DESCRIPTION\s*=')


def _description_after(text: str, pos: int, path: pathlib.Path) -> str:
    """The DESCRIPTION constant that follows offset `pos` in `text`.

    Kotlin string concatenation is one sentence to a reader, so the expression is
    collected across lines: a line contributes its string literals and the expression
    continues while the line ends with `+`. The first line after `=` may be empty --
    `const val DESCRIPTION =` is often followed by the literal on the next row -- so
    blank lines are skipped before collection starts.
    """
    m = DESCRIPTION_CONST_RE.search(text, pos)
    if not m:
        raise ParseError(f'{path.name}: a tool NAME constant has no DESCRIPTION constant after it')
    lines = text[m.end():].split('\n')
    idx = 0
    while idx < len(lines) and not lines[idx].strip():
        idx += 1
    literals: list[str] = []
    while idx < len(lines):
        line = lines[idx]
        stripped = line.strip()
        if stripped.startswith('//') or stripped.startswith('*') or stripped.startswith('/*'):
            break
        found = STRING_LITERAL_RE.findall(line)
        if not found:
            break
        literals.extend(found)
        if not line.rstrip().endswith('+'):
            break
        idx += 1
    if not literals:
        raise ParseError(f'{path.name}: DESCRIPTION constant at offset {m.end()} has no string literal')
    return ''.join(literals).replace('\\"', '"')


def tool_descriptions() -> dict[str, str]:
    """`name` -> description for every Koog tool class in [TOOLS_DIR].

    Read from the same constants the MCP server puts into its tool schema, so a
    description in a document cannot disagree with what an agent is told when it
    calls the tool.
    """
    return {name: desc for name, desc in tool_classes().values()}


CLASS_RE = re.compile(r'^\s*class\s+(\w+)\s*[:(]', re.MULTILINE)


def tool_classes() -> dict[str, tuple[str, str]]:
    """`class name` -> (tool NAME constant, description) for every tool class.

    A file may hold several tool classes -- `AdrTools.kt` holds five -- so the owner
    of a NAME constant is the last class declaration before it, not the file name.
    """
    out: dict[str, tuple[str, str]] = {}
    for kt in sorted(TOOLS_DIR.glob('*.kt')):
        text = kt.read_text(encoding='utf-8')
        classes = [(m.start(), m.group(1)) for m in CLASS_RE.finditer(text)]
        for m in NAME_CONST_RE.finditer(text):
            owner = None
            for pos, class_name in classes:
                if pos >= m.start():
                    break
                owner = class_name
            if owner is None:
                raise ParseError(f'{kt.name}: a NAME constant is declared outside any class')
            entry = (m.group(1), _description_after(text, m.end(), kt))
            if owner in out and out[owner] != entry:
                raise ParseError(
                    f'{kt.name}: class {owner} declares two different tools -- the toolbox has a '
                    'name collision'
                )
            out[owner] = entry
    if not out:
        raise ParseError(f'{TOOLS_DIR}: no tool classes found -- the source shape changed')
    return out


def catalog_entries() -> list[tuple[str, str, str]]:
    """(name, toolClass, domain) for every entry in the MCP catalogue."""
    text = CATALOG_KT.read_text(encoding='utf-8')
    start = text.index('val catalog: List<ToolEntry>')
    block = text[start:]
    entries: list[tuple[str, str, str]] = []
    for chunk in block.split('ToolEntry(')[1:]:
        found = []
        for field in ('name', 'domain'):
            m = re.search(rf'^\s*{field}\s*=\s*"([^"]+)"', chunk, re.MULTILINE)
            if not m:
                raise ParseError(f'{CATALOG_KT.name}: a ToolEntry has no {field}')
            found.append(m.group(1))
        # The class is a class reference, not a name in a string: the catalogue holds the
        # class itself, so there is no second copy of the name to drift.
        m = re.search(r'^\s*toolClass\s*=\s*(\w+)::class', chunk, re.MULTILINE)
        if not m:
            raise ParseError(f'{CATALOG_KT.name}: a ToolEntry has no toolClass')
        entries.append((found[0], m.group(1), found[1]))
    if not entries:
        raise ParseError(f'{CATALOG_KT.name}: no ToolEntry declarations found -- the catalog shape '
                         'changed and this renderer has to follow it')
    return entries


def mcp_tool_mapping() -> list[tuple[str, str]]:
    """(CLI command, MCP tool) for every ADR command that has an MCP face."""
    text = SURFACE_KT.read_text(encoding='utf-8')
    start = text.index('val mcpTools: List<AdrMcpToolSpec>')
    end = text.index('fun usageLines()', start)
    rows = _parse_block(text[start:end].splitlines(), 'AdrMcpToolSpec', SURFACE_KT)
    return [(command, tool) for command, tool, _ in rows]


def mcp_adr_tools() -> list[tuple[str, str]]:
    """(name, description) for every MCP tool in the `adr` domain.

    The names come from the catalogue the MCP server serves; the descriptions come
    from the tool classes themselves (see [tool_descriptions]).

    The domain is read from the entries rather than from a slice of the file between two
    section markers: a marker-based slice silently swallowed the tools of whatever
    section happened to be declared next, and the "ADR tools" block then listed a tool
    that was not in the ADR domain at all.
    """
    names = [name for name, _, domain in catalog_entries() if domain == 'adr']
    if not names:
        raise ParseError(
            f'{CATALOG_KT.name}: no `adr`-domain ToolEntry declarations found -- the catalog '
            'shape changed and this renderer has to follow it'
        )

    descriptions = tool_descriptions()
    tools: list[tuple[str, str]] = []
    for name in names:
        if name not in descriptions:
            raise ParseError(
                f'{CATALOG_KT.name}: tool {name!r} is declared in the catalogue but no tool '
                'class declares that NAME -- the catalogue and the toolbox have drifted'
            )
        tools.append((name, descriptions[name]))
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