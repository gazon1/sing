"""sgate.commands.manifest_verify — verify gate registry integrity.

DEPRECATED: This command was originally used to detect orphan shim files and
missing shims. Since the registry (GATES in _registry.py) is now the single
source of truth and generated shims no longer exist as separate files, this
check verifies that every gate in static-gates.sh has a corresponding entry
in the registry with a valid command type.

Checks:
  - All gates in static-gates.sh are present in the registry
  - All registry entries have a valid cmd_type
  - No duplicate gate names in the registry
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

import click

import sgate
from sgate.commands.base import register


def _module_name(display_name: str) -> str:
    """Convert gate display name to the expected shim module filename."""
    s = re.sub(r"[^a-z0-9]+", "_", display_name.lower())
    return re.sub(r"_+", "_", s).strip("_")


# Gates that are intentionally NOT in static-gates.sh (utility commands,
# custom gates, or supplementary CI helpers).
# Update this set when adding a new non-gate command module.
KNOWN_NON_GATES: set[str] = {
    "__init__",
    "base",
    "cli",
    "run_all",
    "manifest_verify",  # this command itself
    "info",
    "wiring",
    "suite_docs",
    "suite_lint",
    "render_registry",
    "conventions",
    "check_benchmarks",
    "doc_sizes",
    "_parsers",
    "_registry",
    "_runner",
    "_gate_runner",
    "yaml_has_no_duplicate_keys",
    "unwired_backlog_refs",
    "test_task_external_inputs_declared",
    "room_schema_integrity",
    "decisions_digest_within_budget",
    "adr_references_are_valid",
    "skills_describe_the_tools_that_exist",
    "skill_frontmatter",
    "settings_read_by_a_feature",
    "light_adr_modules_stay_light",
    "adr_status_policy",
    "coverage_matrix_current",  # was a generated shim, now removed
}


# Registry gates deliberately kept out of `static-gates.sh`.
#
# Each is registered here, with a positive control, and exits non-zero on a clean
# tree — the repo's knowingly-red corpus, the same shape as the advisory `script
# conventions` entry in the registry file. Registering them would turn CI red for a
# reason unrelated to any change under test; deleting them would delete a gate that
# a maintainer still runs by hand.
#
# Each carries `needs_clean_run=False` or a documented exemption in `GATE_CONTROLS`.
# Adding a name here is a claim that a human checked it still fails on a clean tree;
# remove the name when it passes, and the check below will say so.
NOT_RUN_IN_STATIC: frozenset[str] = frozenset({
    "backlog issue refs are resolved",
    "doc dead refs",
    "doc sizes are within budget",
    "flaky tests are acknowledged",
    "rebase compiles",
    "req id uniqueness",
    "test runs",
    # Green on a clean tree, and excluded because it cannot finish in CI: each
    # probe is a full `:shared:detekt` run (~1.5 min) in a throwaway worktree, so
    # the script costs minutes against the 300s budget every gate gets. Its own
    # module docstring says it is a `just` recipe and not part of `check.sh` — the
    # person who needs it is the one about to regenerate a baseline or trust a
    # green lint run, and they need it then, not on every commit.
    "gate honesty",
})


def check_manifest() -> tuple[list[str], list[str], list[str]]:
    """Check consistency between static-gates.sh and the registry.

    Returns:
        (missing_from_registry, registry_duplicates, invalid_cmd_types,
         not_registered_in_static)
        - missing_from_registry: gates in static-gates.sh with no registry entry
        - registry_duplicates: duplicate gate names in the registry
        - invalid_cmd_types: registry entries with unknown cmd_type
        - not_registered_in_static: registry gates with no gate line, i.e. gates
          `sgate run-all` never dispatches (see NOT_RUN_IN_STATIC)
    """
    # Load gates from static-gates.sh
    import sgate.gates
    from sgate.commands import _registry

    shell_gates = sgate.gates.load_gates()
    shell_gate_names = {g.name for g in shell_gates}

    # Registry gate names
    registry_names: list[str] = [spec.name for spec in _registry.GATES]
    registry_name_set = set(registry_names)

    # Missing from registry: gates in static-gates.sh that have no registry entry
    missing_from_registry = sorted(
        name for name in shell_gate_names if name not in registry_name_set
    )

    # The other direction, which is the one that actually bit: a registry gate with
    # no line in static-gates.sh is a gate `sgate run-all` never dispatches, because
    # run-all reads that file and not this registry. Eleven of them were registered
    # here, each with a positive control, and none of them ran — including two whose
    # controls were proving gates CI never executed. A control for a gate that never
    # runs is a control that measures nothing.
    #
    # Checking only the direction above let that happen silently: the two lists
    # drifted by eleven rows and every check agreed.
    not_registered_in_static = sorted(
        name for name in registry_name_set
        if name not in shell_gate_names and name not in NOT_RUN_IN_STATIC
    )

    # Duplicate names in registry
    seen: set[str] = set()
    registry_duplicates: list[str] = []
    for name in registry_names:
        if name in seen:
            registry_duplicates.append(name)
        seen.add(name)
    registry_duplicates = sorted(registry_duplicates)

    # Invalid cmd_types
    from sgate.commands._registry import CmdType
    invalid_cmd_types: list[str] = []
    for spec in _registry.GATES:
        if spec.cmd_type not in CmdType:
            invalid_cmd_types.append(
                f"  {spec.name!r}: unknown cmd_type {spec.cmd_type!r}"
            )
    invalid_cmd_types = sorted(invalid_cmd_types)

    return (
        missing_from_registry,
        registry_duplicates,
        invalid_cmd_types,
        not_registered_in_static,
    )


@click.command("manifest-verify")
@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")
def manifest_verify(emit_json: bool) -> None:
    """Verify consistency between static-gates.sh and the gate registry.

    DEPRECATED: This check is informational only. The registry (_registry.py)
    is the authoritative source of truth for gate metadata.

    Exit codes:
      0 = manifest is consistent
      1 = inconsistencies found
    """
    import json

    missing, duplicates, invalid_types, not_run = check_manifest()
    has_issues = bool(missing or duplicates or invalid_types or not_run)

    if emit_json:
        data = {
            "passed": not has_issues,
            "missing_from_registry": missing,
            "registry_duplicates": duplicates,
            "invalid_cmd_types": invalid_types,
            "not_registered_in_static": not_run,
        }
        print(json.dumps(data, indent=2))
        raise SystemExit(1 if has_issues else 0)

    if not has_issues:
        print("manifest-verify: OK — registry is consistent")
        return

    if missing:
        print("GATES IN static-gates.sh WITH NO REGISTRY ENTRY:")
        for name in missing:
            print(f"  {name}")
        print()

    if not_run:
        print("REGISTRY GATES WITH NO LINE IN static-gates.sh — `sgate run-all` never runs them:")
        for name in not_run:
            print(f"  {name}")
        print("  Add a gate line, or record it in NOT_RUN_IN_STATIC with why the corpus is red.")
        print()

    if duplicates:
        print("DUPLICATE GATE NAMES IN REGISTRY:")
        for name in duplicates:
            print(f"  {name}")
        print()

    if invalid_types:
        print("INVALID CMD_TYPES IN REGISTRY:")
        for msg in invalid_types:
            print(msg)
        print()

    raise SystemExit(1)


register(manifest_verify)
