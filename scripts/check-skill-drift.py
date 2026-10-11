#!/usr/bin/env python3
"""check-skill-drift.py — a skill must not describe tools it no longer has (#526).

Why
---
The class of defect this closes is not "a wrong sentence in a doc". It is: **a skill
is a hand-written description of code that nobody diffs against it.** The skill is
edited rarely, the code is edited daily, so the document is always the stale one, and
it is always plausible — it reads like documentation, not like a bug.

Found in a single session:

| Claim in a skill                    | Reality                                   |
|-------------------------------------|-------------------------------------------|
| `write_adr` "create **or update**"  | it only creates; the update half was gone |
| ADR tools are a `desktopApp` subcommand | a separate `:adr-cli` module since #522 |
| "all four tools"                    | five, since `update_adr` landed in #511   |
| no mention of `adr validate`        | it existed, and it *was* the gate          |

Each one is fixed by hand and true again for a while. That is the wrong fix: the next
subcommand lands and the document is wrong again, and nobody notices for months.

What this gate does
-------------------
The "ADR tools" section of the two ADR skills is **generated** from the tool surface
(`AdrSurface.kt` for the CLI, the `adr` domain of `McpToolCatalog.kt` for MCP) and
this gate re-renders it and fails on any difference. Hand-written prose outside the
markers is never touched — that is the author's, not the generator's.

    python3 scripts/check-skill-drift.py           # check
    python3 scripts/render-adr-tools-section.py    # regenerate

The renderer is a separate script rather than a function here on purpose: writing the
skills and checking them must be the *same* code path, or the second one becomes a
second implementation of the first — which is the failure this whole toolchain exists
to prevent (see `2026-10-10-adr-file-local-rules-live-in-the-writer`).

The positive control
--------------------
`sabotage` is the generated block with one entry removed; the gate must report drift
when the skill holds it. Proved by `scripts/tests/test_render_adr_tools_section.py`,
which asserts that a hand-edited skill fails and a regenerated one passes.
"""

from __future__ import annotations

import importlib.util
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent

# The renderer is loaded by path, not by name: its filename carries hyphens to match
# every other script in `scripts/`, which `import` will not accept. The module *is*
# still the single implementation — `importlib` loads the same file `python3
# scripts/render-adr-tools-section.py` executes.
_RENDERER_PATH = pathlib.Path(__file__).resolve().parent / 'render-adr-tools-section.py'
_spec = importlib.util.spec_from_file_location('render_adr_tools_section', _RENDERER_PATH)
assert _spec and _spec.loader
renderer = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(renderer)


def main() -> int:
    try:
        expected = renderer.render()
    except renderer.ParseError as exc:
        print(f'check-skill-drift: {exc}', file=sys.stderr)
        print('check-skill-drift: the renderer could not read the tool surface, so nothing '
              'can be compared. A gate that cannot read its own input is not a green gate.',
              file=sys.stderr)
        return 1

    drifted: list[pathlib.Path] = []
    missing: list[pathlib.Path] = []

    for skill in renderer.SKILLS:
        if not skill.is_file():
            missing.append(skill)
            continue
        current = renderer.current_block(skill)
        if current != expected:
            drifted.append(skill)

    for path in missing:
        print(f'ERROR: {path.relative_to(ROOT)} does not exist — the generated section has '
              f'nowhere to live')

    for path in drifted:
        rel = path.relative_to(ROOT)
        current = renderer.current_block(path)
        if current is None:
            print(f'ERROR: {rel} has no "{renderer.BEGIN}" block. The ADR tool surface is '
                  f'generated, not maintained by hand; restore it with:')
        else:
            print(f'ERROR: {rel} — the generated "ADR tools" section does not match the code.')
            print(f'  the skill says: {_first_difference(current, expected)}')
        print(f'  fix with: python3 scripts/render-adr-tools-section.py')

    if drifted or missing:
        print('')
        print('A skill that lists tools the code no longer has is worse than no skill: an')
        print('agent reads it and calls something that does not exist, or trusts a promise')
        print('about a tool whose behaviour has changed.')
        return 1

    print(f'check-skill-drift: OK — {len(renderer.SKILLS)} skill(s) match the ADR tool surface '
          f'({len(renderer.commands())} command(s), {len(renderer.mcp_adr_tools())} MCP tool(s))')
    return 0


def _first_difference(actual: str, expected: str) -> str:
    """The first line where the skill and the code disagree, for the error message."""
    actual_lines = actual.splitlines()
    expected_lines = expected.splitlines()
    for i, (a, e) in enumerate(zip(actual_lines, expected_lines), 1):
        if a != e:
            return f'line {i}: skill has {a.strip()!r}, code says {e.strip()!r}'
    if len(actual_lines) != len(expected_lines):
        return (f'the skill has {len(actual_lines)} line(s) in the block, the code produces '
                f'{len(expected_lines)}')
    return 'identical text but byte-different'


if __name__ == '__main__':
    sys.exit(main())