"""Generate all 45 gate shim modules from the static-gates.sh registry.

Run once: python3 _generate_shims.py
"""
from __future__ import annotations

import re
from pathlib import Path

SRC = Path(__file__).parent

# ── helpers ────────────────────────────────────────────────────────────────────

def module_name(display_name: str) -> str:
    """Convert gate display name to Python module filename."""
    s = re.sub(r"[^a-z0-9]+", "_", display_name.lower())
    return re.sub(r"_+", "_", s).strip("_")


# ── Gate registry ─────────────────────────────────────────────────────────────
# (display_name, mode, command_parts, *extra)
# extra can include:
#   {"kiwi": True}     — KIWI_REQUIRED gate
#   {"no_shim": True}  — skip generation (runs as plain script in static-gates.sh)
#   {"custom": True}   — skip generation (hand-written native Click implementation)
# ──────────────────────────────────────────────────────────────────────────────

GATES = [
    # Build hygiene
    ("version catalog has no literals", "blocking",
     ["python3", "scripts/build-version-catalog-gate.py", "--quiet", "."]),
    ("gate scripts' own unit tests", "blocking",
     ["python3", "-m", "unittest", "discover", "-s", "scripts/tests"], {"no_shim": True}),
    ("test-task external inputs declared", "blocking",
     ["python3", "scripts/check-test-task-inputs.py"], {"custom": True}),
    ("declared dependencies are used", "blocking",
     ["python3", "scripts/check-dependency-usage.py"]),
    ("room schema integrity", "blocking",
     ["python3", "scripts/check-room-schema-integrity.py"], {"custom": True}),
    ("gates are alive", "blocking",
     ["python3", "scripts/check-gate-liveness.py"]),
    ("closed ADRs keep their body", "blocking",
     ["python3", "scripts/check-adr-immutable.py"], {"custom": True}),
    ("skills describe the tools that exist", "blocking",
     ["python3", "scripts/check-skill-drift.py"], {"custom": True}),
    ("git hooks path is per-worktree", "advisory",
     ["python3", "scripts/check-hooks-path.py", "--quiet"]),
    ("unwired surfaces", "advisory",
     ["python3", "scripts/find-unwired-surfaces.py", "--quiet"]),
    ("unwired backlog refs", "blocking",
     ["python3", "scripts/check-unwired-backlog-refs.py"], {"custom": True}),
    ("settings read by a feature", "blocking",
     ["python3", "scripts/check-dead-settings.py", "--quiet"], {"custom": True}),
    ("YAML has no duplicate keys", "blocking",
     ["python3", "scripts/check-yaml-duplicate-keys.py", "--quiet"], {"custom": True}),
    # Lint-rule governance
    ("detekt rules config is current", "blocking",
     ["python3", "scripts/gen-detekt-rules-config.py", "--check"]),
    ("detekt rule inventory is current", "blocking",
     ["python3", "scripts/gen-detekt-rule-table.py", "--check"]),
    ("detekt rule registry", "blocking",
     ["./scripts/check-detekt-registrations.sh"]),
    ("detekt baseline ratchet", "blocking",
     ["python3", "scripts/check-baseline-ratchet.py"]),
    ("lint rules are declared decisions", "blocking",
     ["python3", "scripts/check-rule-intent.py"]),
    ("file-level suppressions explain themselves", "blocking",
     ["python3", "scripts/check-suppression-intent.py"]),
    # Scenario traceability (Kiwi)
    ("scenario specs valid", "blocking",
     ["traceability", "validate"], {"kiwi": True}),
    ("coverage matrix current", "blocking",
     ["traceability", "coverage", "--check"], {"kiwi": True}),
    ("coverage holes did not grow", "blocking",
     ["python3", "scripts/check-traceability-ratchet.py"], {"custom": True}),
    ("kiwi inventory did not regress", "advisory",
     ["python3", "scripts/check-kiwi-inventory-ratchet.py"], {"kiwi": True, "custom": True}),
    # Server schema
    ("supabase schema is self-consistent", "blocking",
     ["python3", "scripts/check-supabase-schema-integrity.py"]),
    ("sync allowlist seed matches contract", "blocking",
     ["python3", "scripts/check-sync-allowlist-regenerated.py"]),
    # Maestro
    ("maestro selectors valid", "blocking",
     ["python3", "scripts/ci/validate-maestro-selectors.py"]),
    # Docs / agent-facing text
    ("skills catalog current", "advisory",
     ["./scripts/regen-skills-catalog.sh", "--check"]),
    ("skill frontmatter", "blocking",
     ["./scripts/check-skill-frontmatter.sh"], {"custom": True}),
    ("decisions digest within budget", "blocking",
     ["bash", "-c", "digest_and_sizes"], {"custom": True}),
    ("digest has no double suffix", "blocking",
     ["bash", "-c", "digest_no_double_suffix"]),
    ("no dead doc refs", "advisory",
     ["python3", "scripts/check-doc-dead-refs.py"]),
    ("no dead skill symbols", "blocking",
     ["python3", "scripts/check-doc-dead-refs.py", "--skill-symbols"]),
    ("publication hygiene", "advisory",
     ["python3", "scripts/check-publication-hygiene.py"]),
    ("publication hygiene self-test", "blocking",
     ["python3", "scripts/check-publication-hygiene.py", "--self-test"]),
    ("README claims", "blocking",
     ["python3", "scripts/check-readme-claims.py"]),
    ("README claims self-test", "blocking",
     ["python3", "scripts/check-readme-claims.py", "--self-test"]),
    # ADR governance
    ("ADR references are valid", "blocking",
     ["python3", "scripts/check-adr-references.py"], {"custom": True}),
    ("ADR status policy", "blocking",
     ["singularity-adr", "validate"]),
    ("ADR corpus rules are shared", "blocking",
     ["python3", "scripts/check-adr-config-sync.py"]),
    ("light ADR modules stay light", "blocking",
     ["python3", "scripts/check-adr-module-boundary.py", "--quiet"], {"custom": True}),
    ("ADR frontmatter drift", "advisory",
     ["bash", "-c", "adr_dry_run"], {"custom": True}),
    # spec-kit
    ("spec-kit issue registry current", "advisory",
     ["python3", "scripts/check-speckit-issue-registry.py"]),
    ("requirement identifiers are unique", "advisory",
     ["python3", "scripts/check-req-id-uniqueness.py"]),
    # AppTracer
    ("apptracer upload policy encoded", "blocking",
     ["python3", "scripts/check-apptracer-policy.py"]),
    # Backlog
    ("backlog-issue-refs", "advisory",
     ["python3", "scripts/check-backlog-issue-refs.py"], {"custom": True}),
]

INDENT = "    "  # 4 spaces


def generate_shim(display_name: str, mode: str, cmd: list[str],
                  kiwi_required: bool = False) -> str:
    """Generate a complete Python shim module for a single gate."""
    name = module_name(display_name)
    kiwi_bool = "True" if kiwi_required else "False"
    doc = f'Run the "{display_name}" gate.'
    first = cmd[0]

    lines: list[str] = []

    def add(content: str = "") -> None:
        lines.append(content)

    # ── header ───────────────────────────────────────────────────────────────
    add(f'"""sgate.commands.{name} — shim for: {display_name}."""')
    add("from __future__ import annotations")
    add("")
    add("import json")
    add("from typing import ClassVar")
    add("")
    add("import click")
    add("from sgate.log import logger")
    add("")
    add("import sgate")
    add("from sgate.output import gate_fail")
    add("from sgate.commands._runner import run, run_capture")
    add("from sgate.commands.base import IO_ERROR, INTERNAL_ERROR, register")
    add("")
    add(f"# Kiwi-dependent gate (skips if Kiwi TCMS is unavailable)")
    add(f"KIWI_REQUIRED: ClassVar[bool] = {kiwi_bool}")
    add("")
    add("")
    add(f'@click.command("{name}")')
    add('@click.option("--check", is_flag=True, help="Dry-run: print result without exiting non-zero")')
    add('@click.option("--json", "emit_json", is_flag=True, help="Emit JSON output to stdout")')
    add(f"def {name}(check: bool, emit_json: bool) -> None:")
    add(f'    """{doc}')
    add("")
    add(f"    Mode: {mode}")
    add("")
    add("    Exit codes:")
    add("      0 = clean (gate passed)")
    add("      1 = validation failure (gate detected an issue)")
    add("      2 = I/O error (script not found)")
    add("      3 = config error")
    add("      4 = kiwi unavailable (KIWI_REQUIRED gate; skip is expected)")
    add("      5 = internal error (unexpected exception)")
    add("")
    add(f"    KIWI_REQUIRED: {kiwi_required}")
    add('    """')
    add("    repo_root = sgate.repo.ROOT")
    add(f"    gate_name = '{name}'")
    add("")

    # ── python3 script.py ... ────────────────────────────────────────────────
    if first == "python3":
        script_rel = cmd[1]
        script_args = cmd[2:]
        add(f"{INDENT}script = repo_root / {script_rel!r}")
        add(f"{INDENT}if not script.exists():")
        add(f"{INDENT}    logger.error(f\"{{script}} not found\")")
        add(f"{INDENT}    gate_fail(gate_name, None, None, None, None, \"script not found\")")
        add(f"{INDENT}    raise SystemExit(IO_ERROR)")
        add("")
        add(f"{INDENT}import sys")
        add(f"{INDENT}py_cmd = [sys.executable, str(script)]")
        if script_args:
            args_repr = ", ".join(repr(a) for a in script_args)
            add(f"{INDENT}py_cmd.extend([{args_repr}])")
        add(f"{INDENT}try:")
        add(f"{INDENT}    if emit_json:")
        add(f"{INDENT}        result = run_capture(py_cmd, gate_name)")
        add(f"{INDENT}        out = result.stdout.decode(\"utf-8\", errors=\"replace\") if result.stdout else \"\"")
        add(f"{INDENT}        data = {{\"gate\": gate_name, \"passed\": result.returncode == 0, \"exit_code\": result.returncode, \"output\": out}}")
        add(f"{INDENT}        if result.returncode != 0:")
        add(f"{INDENT}            gate_fail(gate_name, None, None, None, None, f\"exit {{result.returncode}}\")")
        add(f"{INDENT}        print(json.dumps(data))")
        add(f"{INDENT}        raise SystemExit(result.returncode)")
        add(f"{INDENT}    run(py_cmd, gate_name)")
        add(f"{INDENT}except Exception:")
        add(f"{INDENT}    logger.exception(\"unexpected error\")")
        add(f"{INDENT}    gate_fail(gate_name, None, None, None, None, \"unexpected error\")")
        add(f"{INDENT}    raise SystemExit(INTERNAL_ERROR)")

    # ── ./scripts/foo.sh ... ─────────────────────────────────────────────────
    elif first == "./":
        script_rel = cmd[1]
        script_args = cmd[2:]
        add(f"{INDENT}script = repo_root / {script_rel!r}")
        add(f"{INDENT}if not script.exists():")
        add(f"{INDENT}    logger.error(f\"{{script}} not found\")")
        add(f"{INDENT}    gate_fail(gate_name, None, None, None, None, \"script not found\")")
        add(f"{INDENT}    raise SystemExit(IO_ERROR)")
        add("")
        add(f"{INDENT}sh_cmd = ['bash', str(script)]")
        if script_args:
            args_repr = ", ".join(repr(a) for a in script_args)
            add(f"{INDENT}sh_cmd.extend([{args_repr}])")
        add(f"{INDENT}try:")
        add(f"{INDENT}    if emit_json:")
        add(f"{INDENT}        result = run_capture(sh_cmd, gate_name)")
        add(f"{INDENT}        out = result.stdout.decode(\"utf-8\", errors=\"replace\") if result.stdout else \"\"")
        add(f"{INDENT}        data = {{\"gate\": gate_name, \"passed\": result.returncode == 0, \"exit_code\": result.returncode, \"output\": out}}")
        add(f"{INDENT}        if result.returncode != 0:")
        add(f"{INDENT}            gate_fail(gate_name, None, None, None, None, f\"exit {{result.returncode}}\")")
        add(f"{INDENT}        print(json.dumps(data))")
        add(f"{INDENT}        raise SystemExit(result.returncode)")
        add(f"{INDENT}    run(sh_cmd, gate_name)")
        add(f"{INDENT}except Exception:")
        add(f"{INDENT}    logger.exception(\"unexpected error\")")
        add(f"{INDENT}    gate_fail(gate_name, None, None, None, None, \"unexpected error\")")
        add(f"{INDENT}    raise SystemExit(INTERNAL_ERROR)")

    # ── bash -c ". static-gates.sh && funcname" ─────────────────────────────
    elif first == "bash":
        func_name = cmd[2]
        add(f"{INDENT}static_sh = repo_root / 'scripts' / 'ci' / 'static-gates.sh'")
        add(f"{INDENT}if not static_sh.exists():")
        add(f"{INDENT}    logger.error(f\"{{static_sh}} not found\")")
        add(f"{INDENT}    gate_fail(gate_name, None, None, None, None, \"static-gates.sh not found\")")
        add(f"{INDENT}    raise SystemExit(IO_ERROR)")
        add("")
        add(f"{INDENT}bash_cmd = ['bash', '-c', f'. {{static_sh}} && {func_name}']")
        add(f"{INDENT}try:")
        add(f"{INDENT}    if emit_json:")
        add(f"{INDENT}        result = run_capture(bash_cmd, gate_name)")
        add(f"{INDENT}        out = result.stdout.decode(\"utf-8\", errors=\"replace\") if result.stdout else \"\"")
        add(f"{INDENT}        data = {{\"gate\": gate_name, \"passed\": result.returncode == 0, \"exit_code\": result.returncode, \"output\": out}}")
        add(f"{INDENT}        if result.returncode != 0:")
        add(f"{INDENT}            gate_fail(gate_name, None, None, None, None, f\"exit {{result.returncode}}\")")
        add(f"{INDENT}        print(json.dumps(data))")
        add(f"{INDENT}        raise SystemExit(result.returncode)")
        add(f"{INDENT}    run(bash_cmd, gate_name)")
        add(f"{INDENT}except Exception:")
        add(f"{INDENT}    logger.exception(\"unexpected error\")")
        add(f"{INDENT}    gate_fail(gate_name, None, None, None, None, \"unexpected error\")")
        add(f"{INDENT}    raise SystemExit(INTERNAL_ERROR)")

    # ── PATH command (singularity-adr, etc.) ──────────────────────────────────
    else:
        cmd_repr = repr(cmd)
        add(f"{INDENT}try:")
        add(f"{INDENT}    if emit_json:")
        add(f"{INDENT}        result = run_capture({cmd_repr}, gate_name)")
        add(f"{INDENT}        out = result.stdout.decode(\"utf-8\", errors=\"replace\") if result.stdout else \"\"")
        add(f"{INDENT}        data = {{\"gate\": gate_name, \"passed\": result.returncode == 0, \"exit_code\": result.returncode, \"output\": out}}")
        add(f"{INDENT}        if result.returncode != 0:")
        add(f"{INDENT}            gate_fail(gate_name, None, None, None, None, f\"exit {{result.returncode}}\")")
        add(f"{INDENT}        print(json.dumps(data))")
        add(f"{INDENT}        raise SystemExit(result.returncode)")
        add(f"{INDENT}    run({cmd_repr}, gate_name)")
        add(f"{INDENT}except Exception:")
        add(f"{INDENT}    logger.exception(\"unexpected error\")")
        add(f"{INDENT}    gate_fail(gate_name, None, None, None, None, \"unexpected error\")")
        add(f"{INDENT}    raise SystemExit(INTERNAL_ERROR)")
    add("")
    add(f"register({name})")
    add("")

    return "\n".join(lines) + "\n"


def main() -> None:
    count = 0
    for entry in GATES:
        display_name = entry[0]
        mode = entry[1]
        cmd = entry[2]
        kiwi_required = False
        opts = {}
        if len(entry) > 3:
            opts = entry[3]
            kiwi_required = opts.get("kiwi", False)

        # Gates with no_shim=True or custom=True are not generated by this script.
        # no_shim: runs as a plain script in static-gates.sh (e.g. unittest).
        # custom: has a hand-written native Click implementation (--json, etc.).
        if opts.get("no_shim") or opts.get("custom"):
            continue

        name = module_name(display_name)
        content = generate_shim(display_name, mode, cmd, kiwi_required)
        out_path = SRC / f"{name}.py"
        out_path.write_text(content)
        print(f"  {name}.py")
        count += 1

    print(f"\nGenerated {count} shim modules.")


if __name__ == "__main__":
    main()
