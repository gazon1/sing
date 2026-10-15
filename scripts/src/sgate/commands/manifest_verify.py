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


def check_manifest() -> tuple[list[str], list[str], list[str]]:
    """Check consistency between static-gates.sh and the registry.

    Returns:
        (missing_from_registry, registry_duplicates, invalid_cmd_types)
        - missing_from_registry: gates in static-gates.sh with no registry entry
        - registry_duplicates: duplicate gate names in the registry
        - invalid_cmd_types: registry entries with unknown cmd_type
    """
    from sgate.commands import _registry

    # Load gates from static-gates.sh
    import sgate.gates

    shell_gates = sgate.gates.load_gates()
    shell_gate_names = {g.name for g in shell_gates}

    # Registry gate names
    registry_names: list[str] = [spec.name for spec in _registry.GATES]
    registry_name_set = set(registry_names)

    # Missing from registry: gates in static-gates.sh that have no registry entry
    missing_from_registry = sorted(
        name for name in shell_gate_names if name not in registry_name_set
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
    VALID_CMD_TYPES = {"python3", "python3_m", "shell", "bash_c", "path"}
    invalid_cmd_types: list[str] = []
    for spec in _registry.GATES:
        if spec.cmd_type not in VALID_CMD_TYPES:
            invalid_cmd_types.append(
                f"  {spec.name!r}: unknown cmd_type {spec.cmd_type!r}"
            )
    invalid_cmd_types = sorted(invalid_cmd_types)

    return missing_from_registry, registry_duplicates, invalid_cmd_types


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

    missing, duplicates, invalid_types = check_manifest()
    has_issues = bool(missing or duplicates or invalid_types)

    if emit_json:
        data = {
            "passed": not has_issues,
            "missing_from_registry": missing,
            "registry_duplicates": duplicates,
            "invalid_cmd_types": invalid_types,
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
