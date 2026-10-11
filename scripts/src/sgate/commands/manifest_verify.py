"""sgate.commands.manifest_verify — detect orphan shims and missing entries.

Verifies consistency between the gate registry (static-gates.sh) and the
actual shim files in commands/. Detects:
  - Orphan shim files: shim files with no corresponding GATES entry
  - Missing shims: GATES entries with no shim file (when one is expected)
  - Mismatched commands: GATES entries whose sgate command doesn't match
    the actual shim file name
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import click

import sgate
from sgate.commands.base import register


# Module-level so it can be tested without the full CLI
def _module_name(display_name: str) -> str:
    """Convert gate display name to the expected shim module filename."""
    s = re.sub(r"[^a-z0-9]+", "_", display_name.lower())
    return re.sub(r"_+", "_", s).strip("_")


def _shim_file_path(module_name: str) -> Path:
    """Path to the shim file for a given module name."""
    return sgate.repo.ROOT / "scripts" / "src" / "sgate" / "commands" / f"{module_name}.py"


def check_manifest() -> tuple[list[str], list[str], list[str]]:
    """Check consistency between GATES registry and shim files.

    Returns:
        (orphan_shims, missing_shims, command_mismatches)
        - orphan_shims: shim files in commands/ with no GATES entry
        - missing_shims: GATES entries that should have a shim but don't
        - command_mismatches: GATES entries whose sgate command doesn't match
          the expected shim name
    """
    import sgate.gates

    gates = sgate.gates.load_gates()
    gate_names = {g.name for g in gates}

    # Find all shim files in commands/
    commands_dir = sgate.repo.ROOT / "scripts" / "src" / "sgate" / "commands"
    all_shims = {p.stem for p in commands_dir.glob("*.py") if p.stem not in ("__init__", "base", "cli", "run_all", "manifest_verify")}

    # Find all GATES that have a corresponding shim (custom=False, no_shim=False)
    custom_gates = set()
    no_shim_gates = set()
    gates_with_shim = set()
    command_mismatches = []

    # We need the full GATES metadata. Re-parse from the generator GATES list.
    # Use the static-gates.sh to determine which gates have sgate commands.
    for gate in gates:
        cmd = gate.command.strip()
        module_name = _module_name(gate.name)

        if cmd.startswith("sgate "):
            # The subcommand after "sgate "
            sub_cmd = cmd[6:].strip()
            # sub_cmd is the command name used to invoke this gate via sgate
            expected_module = _module_name(sub_cmd.replace("_", " ").lower())
            actual_module = _module_name(gate.name)

            # Check if the sgate subcommand maps to the right shim
            if module_name != expected_module:
                command_mismatches.append(
                    f"  GATE: {gate.name!r}  command: {cmd!r}  "
                    f"expected shim: {expected_module}.py  actual shim: {module_name}.py"
                )

        # Determine if this gate should have a shim based on the command type
        # sgate <name> gates → custom or generated shim
        # python3 scripts/... → generated shim (or no_shim if marked)
        # The generator GATES list tells us which are custom/no_shim

        # We check against the actual shim files to see what's there
        if module_name in all_shims:
            gates_with_shim.add(gate.name)

    # Also check: which shims have no corresponding GATES entry?
    # A shim is "covered" if:
    #   1. Its stem matches a gate's derived module name (display_name -> module)
    #   2. OR it appears as an sgate subcommand (command = "sgate <sub>")
    no_shim_gates = {
        "gate scripts' own unit tests",  # no_shim=True in generator
    }
    # Known orphans that are intentionally not in static-gates.sh:
    #   _*               : internal utilities (not gate commands)
    #   info             : sgate info command (not a gate itself)
    #   backlog_status   : called from check.sh step 2, not in static-gates.sh
    #   wiring           : called from check.sh step 8, not in static-gates.sh
    #   suite_docs/lint  : supplementary CI tools, not formal gates
    #   render_registry  : regen tool, not a gate
    #   conventions/kiwi_availability/doc_sizes/check_benchmarks : check.sh helpers
    expected_orphans = {
        "_generate_shims", "_runner",
        "info",
        "backlog_status", "wiring",
        "suite_docs", "suite_lint",
        "render_registry",
        "conventions", "kiwi_availability", "doc_sizes", "check_benchmarks",
    }
    orphan_shims = []
    for shim_name in all_shims:
        # Skip expected orphans
        if shim_name in expected_orphans:
            continue
        # Find if any GATE maps to this shim
        found = False
        for gate in gates:
            if gate.name in no_shim_gates:
                continue
            module = _module_name(gate.name)
            if module == shim_name:
                found = True
                break
            # Also check sgate subcommands: "sgate <sub>" -> derive module from sub
            if gate.command.startswith("sgate "):
                sub = gate.command[6:].strip()
                sub_module = _module_name(sub.replace("_", " ").lower())
                if sub_module == shim_name:
                    found = True
                    break
        if not found:
            orphan_shims.append(shim_name)

    # Which GATES entries that should have shims are missing them?
    # A gate "should have a shim" if it would generate one based on command type.
    # Gates with no_shim=True in the generator are expected to lack shims.
    no_shim_gates = {
        "gate scripts' own unit tests",  # no_shim=True in generator
    }
    missing_shims = []
    for gate in gates:
        if gate.name in no_shim_gates:
            continue
        module_name = _module_name(gate.name)
        shim_path = _shim_file_path(module_name)
        if not shim_path.exists():
            missing_shims.append(
                f"  GATE: {gate.name!r}  module: {module_name}.py  command: {gate.command!r}"
            )

    return sorted(orphan_shims), sorted(missing_shims), sorted(command_mismatches)


@click.command("manifest-verify")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
def manifest_verify(emit_json: bool) -> None:
    """Verify consistency between the gate registry and shim files.

    Checks:
      - Orphan shim files: .py files in commands/ with no GATES entry
      - Missing shims: GATES entries with no corresponding .py shim
      - Command mismatches: GATES entries whose sgate command doesn't
        resolve to the expected shim filename

    Exit codes:
      0 = manifest is consistent
      1 = inconsistencies found
    """
    orphan_shims, missing_shims, command_mismatches = check_manifest()

    has_issues = bool(orphan_shims or missing_shims or command_mismatches)

    if emit_json:
        import json
        data = {
            "passed": not has_issues,
            "orphan_shims": orphan_shims,
            "missing_shims": missing_shims,
            "command_mismatches": command_mismatches,
        }
        print(json.dumps(data, indent=2))
        raise SystemExit(1 if has_issues else 0)

    # Human-readable output
    if not has_issues:
        print("manifest-verify: OK — registry and shims are consistent")
        return

    if orphan_shims:
        print("ORPHAN SHIM FILES (no GATES entry):")
        for s in orphan_shims:
            print(f"  {s}.py")
        print()

    if missing_shims:
        print("MISSING SHIMS (GATES entry has no .py shim):")
        for m in missing_shims:
            print(m)
        print()

    if command_mismatches:
        print("COMMAND MISMATCHES:")
        for c in command_mismatches:
            print(c)
        print()

    raise SystemExit(1)


register(manifest_verify)
