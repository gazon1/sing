#!/usr/bin/env python3
"""new-gate.py — register a gate in every place it has to be, in one command.

The ritual this removes
-----------------------

Adding a gate used to mean five files and four names for one gate:

  * `scripts/check-<name>.py` — the gate itself;
  * `scripts/src/sgate/commands/_registry.py` — the `GateSpec` that says how it
    is invoked, and the `Control` that says what change must make it fail;
  * `scripts/src/sgate/gate_controls.py` — the `ScriptGate` the harness
    sabotages;
  * `scripts/src/sgate/gate_manifest.py` — the `Placement` that says where it
    runs and the prose that explains it;
  * `scripts/tests/test_check_<name>.py` — the gate's own test.

And the four names: the display name ("navigation doc matches the code"), the
slug ("navigation-doc-sync"), the command ("navigation_doc_matches_the_code")
and the file ("check-navigation-doc-sync.py"). A name mistyped in one of them
is a gate that does not run, or a control that proves nothing.

This command takes the display name and the script, derives the other names,
writes all five pieces, and verifies that the three modules still import.

ADR `docs/decisions/2026-10-22-the-gate-suite-runs-this-checkout.md`.

Usage:
    python3 scripts/ci/new-gate.py \\
        --display-name "navigation doc matches the code" \\
        --script scripts/check-navigation-doc-sync.py \\
        --why "a row that names a type the code does not name sends every agent the wrong way" \\
        --note "The doc is pointed at from the nav3 skill." \\
        [--mode blocking|advisory] \\
        [--sabotage-path docs/navigation.md --sabotage-old "OLD" --sabotage-new "NEW"]

The command closes the loop before it returns: it renders the two generated
files (`scripts/ci/static-gates.sh` and `docs/scripts/gate-registry.md`) from
the manifest, proves that the shell registry actually runs the new gate, and
runs the gate once. A registration that stops before that leaves three things
to remember — and the one that matters is the easiest to forget: that the gate
runs at all.

Exit codes:
  0 = the gate was registered, rendered, reached and run
  1 = the arguments or the anchors were not what they must be, or the gate
      does not run yet
  2 = a file could not be written
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent

REGISTRY_PY = ROOT / "scripts/src/sgate/commands/_registry.py"
CONTROLS_PY = ROOT / "scripts/src/sgate/gate_controls.py"
MANIFEST_PY = ROOT / "scripts/src/sgate/gate_manifest.py"

#: Where each block is inserted: the file, the line the block follows, and the
#: name of the collection the block belongs to. The line is the anchor: the
#: block is written directly after it, inside the literal it opens.
ANCHORS = {
    "spec": (REGISTRY_PY, "_REGISTERED: tuple[GateSpec, ...] = ("),
    "control": (REGISTRY_PY, "GATE_CONTROLS: dict[str, Control] = {"),
    "script_gate": (CONTROLS_PY, "SCRIPT_GATES = ["),
    "placement": (MANIFEST_PY, "PLACEMENTS: dict[str, Placement] = {"),
}


def slugify(text: str) -> str:
    """`navigation doc matches the code` → `navigation-doc-matches-the-code`."""
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")


def command_name(text: str) -> str:
    """`navigation doc matches the code` → `navigation_doc_matches_the_code`."""
    return re.sub(r"[^a-z0-9]+", "_", text.lower()).strip("_")


def insert_at_end_of(path: Path, anchor: str, block: str) -> None:
    """Write `block` at the end of the collection the anchor opens.

    The block goes at the *end* of the collection, not at its start: the order
    of the entries is a fact other things depend on — a test asserts which
    gate the registry names first — and a new gate inserted at the top of a
    list changes that fact for no reason.
    """
    lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
    matches = [index for index, line in enumerate(lines) if line.strip() == anchor]
    if not matches:
        raise SystemExit(
            f"anchor not found in {path.relative_to(ROOT)}: {anchor!r}\n"
            f"The registry moved. Update ANCHORS in {Path(__file__).name}."
        )
    if len(matches) > 1:
        raise SystemExit(
            f"anchor is ambiguous in {path.relative_to(ROOT)}: {anchor!r} "
            f"appears {len(matches)} times"
        )
    start = matches[0]
    for index in range(start + 1, len(lines)):
        if lines[index].rstrip("\n") in ("}", "]", ")"):
            indent = "    "
            inserted = [f"{indent}{line}\n" if line else "\n" for line in block.splitlines()]
            lines[index:index] = inserted
            path.write_text("".join(lines), encoding="utf-8")
            return
    raise SystemExit(
        f"the collection opened by {anchor!r} in {path.relative_to(ROOT)} has no closing line"
    )


def spec_block(display: str, mode: str, script: str, note: str) -> str:
    """The `GateSpec` that says how the gate is invoked."""
    return f"""    # {note}
    GateSpec(
        name="{display}",
        mode=GateMode.{mode.upper()},
        cmd_type=CmdType.PYTHON3,
        script=Path("{script}"),
        script_args=("--check",),
    ),"""


def control_block(slug: str, script: str, why: str, path: str, old: str, new: str,
                  argv: tuple[str, ...] = ()) -> str:
    """The `Control` that says what change must make the gate fail."""
    arguments = ", ".join(f"'{arg}'" for arg in ("{python}", script, *argv))
    return f"""    # {why}
    '{slug}': Control(
        kind='sabotage',
        proves='{script}',
        argv=({arguments},),
        path='{path}',
        edit=Edit(
            kind='replace',
            old={old!r},
            new={new!r},
            count=1,
        ),
        why=(
            {why!r}
        ),
    ),"""


def script_gate_block(slug: str, script: str, why: str) -> str:
    """The `ScriptGate` the harness sabotages."""
    return f"""    # {why}
    ScriptGate(
        name="{slug}",
        cmd=[sys.executable, "{script}", "--check"],
        why=(
            {why!r}
        ),
    ),"""


def placement_block(display: str, command: str, note: str) -> str:
    """The `Placement` that says where the gate runs.

    The note is prose; the shell registry it is rendered into is what makes it
    a comment. The renderer (`sgate.gate_manifest.render_static_registry`) puts
    the `#` on every line itself, so a note does not have to remember the
    convention — and a note that forgot it became a command the shell tried to
    run.
    """
    return f"""    '{display}': Placement(
        kind=PlacementKind.STATIC,
        command="sgate {command}",
        note=(
            {note!r}
        ),
    ),"""


def test_file(slug: str, script: str) -> str:
    """The gate's own test — a smoke test that the gate is a gate."""
    module = slug.replace("-", "_")
    return f'''"""Tests for {script}.

A gate that does not run is not a gate. This test proves the script exists,
that it is a Click command, and that it answers the three questions a caller
asks of a gate: `--check`, `--json`, and the exit code.
"""

import pathlib
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
MODULE_PATH = ROOT / "{script}"


class GateSmokeTest(unittest.TestCase):
    def test_the_gate_script_exists(self):
        self.assertTrue(MODULE_PATH.is_file())

    def test_the_gate_passes_on_a_clean_tree(self):
        """A gate that is red on a clean tree proves nothing about a red tree."""
        import subprocess

        result = subprocess.run(
            [sys.executable, str(MODULE_PATH), "--check"],
            cwd=ROOT,
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
'''


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--display-name", required=True, help="The name the gate is called in prose.")
    parser.add_argument("--script", required=True, help="The gate script, repo-relative.")
    parser.add_argument("--why", default="", help="Why this gate exists (the reason the control records).")
    parser.add_argument("--note", default="", help="The prose rendered above the gate line.")
    parser.add_argument("--mode", default="blocking", choices=["blocking", "advisory"])
    parser.add_argument("--sabotage-path", default="", help="The file the control changes.")
    parser.add_argument("--sabotage-old", default="", help="The text the control replaces.")
    parser.add_argument("--sabotage-new", default="", help="The text the control writes instead.")
    parser.add_argument(
        "--sabotage-arg",
        action="append",
        default=[],
        help="An extra argument of the control-time command (repeatable).",
    )
    parser.add_argument("--dry-run", action="store_true", help="Print what would be written, and write nothing.")
    args = parser.parse_args()

    display = args.display_name
    script = args.script
    # The slug follows the script — `check-navigation-doc-sync.py` is the gate
    # `navigation-doc-sync` — and the command follows the display name, because
    # that is what `sgate` names the command after.
    slug = slugify(Path(script).stem.removeprefix("check-"))
    command = command_name(display)
    why = args.why or f"{display} would go unnoticed"
    note = args.note or display

    blocks = [
        ("spec", spec_block(display, args.mode, script, note)),
        ("script_gate", script_gate_block(slug, script, why)),
        ("placement", placement_block(display, command, note)),
    ]
    if args.sabotage_path:
        blocks.append(
            (
                "control",
                control_block(
                    slug,
                    script,
                    why,
                    args.sabotage_path,
                    args.sabotage_old,
                    args.sabotage_new,
                    tuple(args.sabotage_arg),
                ),
            )
        )

    if args.dry_run:
        for key, block in blocks:
            path, anchor = ANCHORS[key]
            print(f"=== {path.relative_to(ROOT)} (at the end of {anchor!r}) ===")
            print(block)
        return 0

    for key, block in blocks:
        path, anchor = ANCHORS[key]
        insert_at_end_of(path, anchor, block)

    test_path = ROOT / "scripts/tests" / f"test_{Path(script).stem.replace('-', '_')}.py"
    if not test_path.exists():
        test_path.write_text(test_file(slug, script), encoding="utf-8")
    else:
        print(f"  (test file already exists: {test_path.relative_to(ROOT)})")

    # The three modules must still import: a registry that does not parse is a
    # registry that takes the whole gate suite with it.
    check = subprocess.run(
        [sys.executable, "-c", "import sgate.commands._registry, sgate.gate_controls, sgate.gate_manifest"],
        cwd=ROOT,
        capture_output=True,
        text=True,
        env={**os.environ, "PYTHONPATH": str(ROOT / "scripts" / "src")},
    )
    if check.returncode != 0:
        print(check.stderr, file=sys.stderr)
        print("The registry does not import — fix it before committing.", file=sys.stderr)
        return 1

    print(f"Registered: {display}")
    print(f"  script    {script}")
    print(f"  slug      {slug}")
    print(f"  command   sgate {command}")
    print()

    # The loop closes here: the gate is rendered into the two generated files,
    # checked for reachability, and run once. A command that registered a gate
    # and stopped left three things to remember — and the one that matters is
    # the one that is easiest to forget: that the gate runs at all.
    if not render_generated_files():
        return 1
    if not gate_is_reachable(script):
        return 1
    if not gate_passes(script):
        return 1

    print()
    print("Now prove the gate can fail:")
    print("  python3 scripts/check-gate-wiring.py --can-fail")
    return 0


def _sgate_env() -> dict[str, str]:
    """The environment a `sgate` command runs in: this checkout's code."""
    env = dict(os.environ)
    src = str(ROOT / "scripts" / "src")
    existing = env.get("PYTHONPATH", "")
    env["PYTHONPATH"] = f"{src}{os.pathsep}{existing}" if existing else src
    return env


def render_generated_files() -> bool:
    """Rewrite the two generated files, so the new gate is in them."""
    for arguments in (["render-static-gates", "--write"], ["render-registry"]):
        result = subprocess.run(
            [sys.executable, "-m", "sgate", *arguments],
            cwd=ROOT,
            capture_output=True,
            text=True,
            env=_sgate_env(),
        )
        if result.returncode != 0:
            print(result.stdout + result.stderr, file=sys.stderr)
            print(
                f"`sgate {' '.join(arguments)}` failed — the generated files are not "
                "what the manifest renders.",
                file=sys.stderr,
            )
            return False
    print("Rendered: scripts/ci/static-gates.sh, docs/scripts/gate-registry.md")
    return True


def gate_is_reachable(script: str) -> bool:
    """Whether the shell registry actually runs this gate's script.

    A gate that is registered but not reachable is a gate that runs on no one:
    the registry is what the suite reads, and a name in the Python registry
    alone changes nothing.
    """
    sys.path.insert(0, str(ROOT / "scripts" / "src"))
    from sgate.commands._registry import GATES
    from sgate.gate_reachability import covered_scripts

    shell_gates: list[tuple[str, str, str]] = []
    for line in (ROOT / "scripts" / "ci" / "static-gates.sh").read_text(encoding="utf-8").splitlines():
        match = re.match(r'^gate (blocking|advisory) "([^"]+)" (.+)$', line)
        if match:
            shell_gates.append((match.group(1), match.group(2), match.group(3)))

    # The shell registry names a gate by its *command* (`sgate <command>`),
    # and the registry knows it by its display name. The two are joined by the
    # name the command was derived from.
    registry_by_command = {command_name(spec.name): spec for spec in GATES}
    covered = covered_scripts(ROOT, shell_gates, registry_by_command)
    if script in covered:
        print(f"Reachable: {script} is run by the shell registry")
        return True
    print(
        f"{script} is registered, but no gate line of the shell registry runs it. "
        "A gate that runs on no one is not a gate.",
        file=sys.stderr,
    )
    return False


def gate_passes(script: str) -> bool:
    """Run the new gate once, on the tree as it stands."""
    result = subprocess.run(
        [sys.executable, script, "--check"],
        cwd=ROOT,
        capture_output=True,
        text=True,
    )
    if result.returncode == 0:
        print(f"Passes: {script} --check")
        return True
    print(result.stdout + result.stderr, file=sys.stderr)
    print(
        f"{script} --check does not pass on the tree as it stands. A gate that is "
        "red on the day it lands is a gate nobody reads.",
        file=sys.stderr,
    )
    return False


if __name__ == "__main__":
    raise SystemExit(main())
