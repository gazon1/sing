"""sgate.commands._registry — single source of truth for gate metadata.

The GATES list is the canonical registry. All gate commands are produced by
``make_command()`` — there are no hand-written shims for generated gates.

The SCRIPTS dict maps gate names to their underlying script paths and is used
by check-gate-wiring.py for its sabotage/injection mechanism. It must have
the same structure the sabotage system expects.

Design
------
A GateSpec describes what a gate runs. ``make_command()`` returns a
Click Command that runs the gate. ``register_all()`` registers every command
with the CLI.

Command types
------------
python3 <path>  — run a Python script via sys.executable
./<path>        — run a shell script via bash
bash -c <func>  — source static-gates.sh and call a named function
PATH command    — run something on PATH (e.g. traceability)

Custom gates (hand-written Click commands) are NOT in GATES; they live in
their own modules and import themselves via the normal Click discovery.
"""
from __future__ import annotations

import re
import sys
from dataclasses import dataclass, field

import click

from sgate.log import logger
from sgate.output import gate_fail
from sgate.commands._runner import run, run_capture, Result, handle_result
from sgate.commands.base import IO_ERROR, INTERNAL_ERROR, register

# Re-export for convenience
GateCommand = click.Command


# ── GateSpec ────────────────────────────────────────────────────────────────────


@dataclass(frozen=True, slots=True)
class GateSpec:
    """One gate's metadata, sufficient to produce a Click command."""

    #: Display name (used in --json output, gate_fail annotations, etc.)
    name: str
    #: blocking | advisory
    mode: str
    #: The command type determines how the gate is invoked.
    #: Each variant carries the parts needed to reconstruct the command.
    cmd_type: str  # "python3" | "shell" | "bash_c" | "path"
    #: Script path for python3/shell commands, relative to repo root.
    script: str = ""
    #: Extra arguments appended to the script command.
    script_args: tuple[str, ...] = field(default_factory=tuple)
    #: Function name for bash_c commands.
    bash_func: str = ""
    #: Full command list for PATH commands.
    path_cmd: tuple[str, ...] = field(default_factory=tuple)
    #: KIWI_REQUIRED gates skip (exit 4) when the command is not found.
    kiwi_required: bool = False
    #: Extra boolean flags this gate accepts (param name → flag details).
    extra_flags: tuple[dict[str, str], ...] = field(default_factory=tuple)


def _module_name(display_name: str) -> str:
    """Convert gate display name to Python module filename."""
    s = re.sub(r"[^a-z0-9]+", "_", display_name.lower())
    return re.sub(r"_+", "_", s).strip("_")


# ── GATES registry ─────────────────────────────────────────────────────────────

GATES: tuple[GateSpec, ...] = (
    # ── Build hygiene ───────────────────────────────────────────────────────────────
    GateSpec(
        name="version catalog has no literals",
        mode="blocking",
        cmd_type="python3",
        script="scripts/build-version-catalog-gate.py",
        script_args=("--quiet", "."),
    ),
    GateSpec(
        name="gate scripts' own unit tests",
        mode="blocking",
        cmd_type="python3_m",
        script="unittest",
        script_args=("discover", "-s", "scripts/tests"),
    ),
    GateSpec(
        name="test-task external inputs declared",
        mode="blocking",
        cmd_type="python3",
        script="scripts/check-test-task-inputs.py",
    ),
    GateSpec(
        name="declared dependencies are used",
        mode="blocking",
        cmd_type="python3",
        script="scripts/check-dependency-usage.py",
    ),
    GateSpec(
        name="room schema integrity",
        mode="blocking",
        cmd_type="python3",
        script="scripts/check-room-schema-integrity.py",
    ),
    GateSpec(
        name="gates are alive",
        mode="blocking",
        cmd_type="python3",
        script="scripts/check-gate-liveness.py",
    ),
    GateSpec(
        name="closed ADRs keep their body",
        mode="blocking",
        cmd_type="python3",
        script="scripts/check-adr-immutable.py",
    ),
    GateSpec(
        name="skills describe the tools that exist",
        mode="blocking",
        cmd_type="python3",
        script="scripts/check-skill-drift.py",
    ),
    GateSpec(
        name="git hooks path is per-worktree",
        mode="advisory",
        cmd_type="python3",
        script="scripts/check-hooks-path.py",
        script_args=("--quiet",),
    ),
    GateSpec(
        name="unwired surfaces",
        mode="advisory",
        cmd_type="python3",
        script="scripts/find-unwired-surfaces.py",
        script_args=("--quiet",),
    ),
    GateSpec(
        name="unwired backlog refs",
        mode="blocking",
        cmd_type="python3",
        script="scripts/check-unwired-backlog-refs.py",
    ),
    GateSpec(
        name="settings read by a feature",
        mode="blocking",
        cmd_type="python3",
        script="scripts/check-dead-settings.py",
        script_args=("--quiet",),
    ),
    GateSpec(
        name="YAML has no duplicate keys",
        mode="blocking",
        cmd_type="python3",
        script="scripts/check-yaml-duplicate-keys.py",
        script_args=("--quiet",),
    ),
    # ── Lint-rule governance ──────────────────────────────────────────────────────
    GateSpec(
        name="detekt rules config is current",
        mode="blocking",
        cmd_type="python3",
        script="scripts/gen-detekt-rules-config.py",
        script_args=("--check",),
    ),
    GateSpec(
        name="detekt rule inventory is current",
        mode="blocking",
        cmd_type="python3",
        script="scripts/gen-detekt-rule-table.py",
        script_args=("--check",),
    ),
    GateSpec(
        name="detekt rule registry",
        mode="blocking",
        cmd_type="shell",
        script="scripts/check-detekt-registrations.sh",
    ),
    GateSpec(
        name="detekt baseline ratchet",
        mode="blocking",
        cmd_type="python3",
        script="scripts/check-baseline-ratchet.py",
    ),
    GateSpec(
        name="lint rules are declared decisions",
        mode="blocking",
        cmd_type="python3",
        script="scripts/check-rule-intent.py",
    ),
    GateSpec(
        name="file-level suppressions explain themselves",
        mode="blocking",
        cmd_type="python3",
        script="scripts/check-suppression-intent.py",
    ),
    # ── Scenario traceability ──────────────────────────────────────────────────────
    GateSpec(
        name="scenario specs valid",
        mode="blocking",
        cmd_type="python3_m",
        script="traceability",
        script_args=("validate",),
    ),
    GateSpec(
        name="coverage matrix current",
        mode="blocking",
        cmd_type="python3_m",
        script="traceability",
        script_args=("coverage", "--check"),
    ),
    GateSpec(
        name="coverage holes did not grow",
        mode="blocking",
        cmd_type="python3",
        script="scripts/check-traceability-ratchet.py",
    ),
    GateSpec(
        name="speckit scenarios current",
        mode="blocking",
        cmd_type="python3_m",
        script="traceability",
        script_args=("coverage", "--check"),
    ),
    GateSpec(
        name="speckit stale",
        mode="advisory",
        cmd_type="python3_m",
        script="traceability",
        script_args=("validate",),
    ),
    # ── Server schema ─────────────────────────────────────────────────────────────
    GateSpec(
        name="supabase schema is self-consistent",
        mode="blocking",
        cmd_type="python3",
        script="scripts/check-supabase-schema-integrity.py",
    ),
    GateSpec(
        name="sync allowlist seed matches contract",
        mode="blocking",
        cmd_type="python3",
        script="scripts/check-sync-allowlist-regenerated.py",
    ),
    # ── Maestro ───────────────────────────────────────────────────────────────────
    GateSpec(
        name="maestro selectors valid",
        mode="blocking",
        cmd_type="python3",
        script="scripts/ci/validate-maestro-selectors.py",
    ),
    # ── Docs / agent-facing text ───────────────────────────────────────────────────
    GateSpec(
        name="skills catalog current",
        mode="advisory",
        cmd_type="shell",
        script="scripts/regen-skills-catalog.sh",
        script_args=("--check",),
    ),
    GateSpec(
        name="skill frontmatter",
        mode="blocking",
        cmd_type="shell",
        script="scripts/check-skill-frontmatter.sh",
    ),
    GateSpec(
        name="decisions digest within budget",
        mode="blocking",
        cmd_type="bash_c",
        bash_func="digest_and_sizes",
    ),
    GateSpec(
        name="digest has no double suffix",
        mode="blocking",
        cmd_type="bash_c",
        bash_func="digest_no_double_suffix",
    ),
    GateSpec(
        name="no dead doc refs",
        mode="advisory",
        cmd_type="python3",
        script="scripts/check-doc-dead-refs.py",
        extra_flags=({
            "param": "skill_symbols",
            "flag": "--skill-symbols",
            "help": "Check Kotlin symbol references in skills",
            "script_arg": "--skill-symbols",
        },),
    ),
    GateSpec(
        name="no dead skill symbols",
        mode="blocking",
        cmd_type="python3",
        script="scripts/check-doc-dead-refs.py",
        script_args=("--skill-symbols",),
    ),
    GateSpec(
        name="publication hygiene",
        mode="advisory",
        cmd_type="python3",
        script="scripts/check-publication-hygiene.py",
        extra_flags=({
            "param": "self_test",
            "flag": "--self-test",
            "help": "Prove the rules fire on a known-bad corpus",
            "script_arg": "--self-test",
        },),
    ),
    GateSpec(
        name="publication hygiene self-test",
        mode="blocking",
        cmd_type="python3",
        script="scripts/check-publication-hygiene.py",
        script_args=("--self-test",),
    ),
    GateSpec(
        name="README claims",
        mode="blocking",
        cmd_type="python3",
        script="scripts/check-readme-claims.py",
        extra_flags=({
            "param": "self_test",
            "flag": "--self-test",
            "help": "Prove the rules fire on a known-bad corpus",
            "script_arg": "--self-test",
        },),
    ),
    GateSpec(
        name="README claims self-test",
        mode="blocking",
        cmd_type="python3",
        script="scripts/check-readme-claims.py",
        script_args=("--self-test",),
    ),
    # ── ADR governance ─────────────────────────────────────────────────────────────
    GateSpec(
        name="ADR references are valid",
        mode="blocking",
        cmd_type="python3",
        script="scripts/check-adr-references.py",
    ),
    GateSpec(
        name="ADR status policy",
        mode="blocking",
        cmd_type="python3",
        script="scripts/check_adr_status.py",
    ),
    GateSpec(
        name="ADR corpus rules are shared",
        mode="blocking",
        cmd_type="python3",
        script="scripts/check-adr-config-sync.py",
    ),
    GateSpec(
        name="light ADR modules stay light",
        mode="blocking",
        cmd_type="python3",
        script="scripts/check-adr-module-boundary.py",
        script_args=("--quiet",),
    ),
    GateSpec(
        name="ADR frontmatter drift",
        mode="advisory",
        cmd_type="bash_c",
        bash_func="adr_dry_run",
    ),
    # ── spec-kit ──────────────────────────────────────────────────────────────────
    GateSpec(
        name="spec-kit issue registry current",
        mode="advisory",
        cmd_type="python3",
        script="scripts/check-speckit-issue-registry.py",
    ),
    GateSpec(
        name="requirement identifiers are unique",
        mode="advisory",
        cmd_type="python3",
        script="scripts/check-req-id-uniqueness.py",
    ),
    # ── AppTracer ─────────────────────────────────────────────────────────────────
    GateSpec(
        name="apptracer upload policy encoded",
        mode="blocking",
        cmd_type="python3",
        script="scripts/check-apptracer-policy.py",
    ),
    # ── Backlog ───────────────────────────────────────────────────────────────────
    GateSpec(
        name="backlog-issue-refs",
        mode="advisory",
        cmd_type="python3",
        script="scripts/check-backlog-issue-refs.py",
    ),
)


# ── make_command factory ────────────────────────────────────────────────────────


def make_command(spec: GateSpec) -> click.Command:
    """Build a Click command from a GateSpec."""
    module_name = _module_name(spec.name)
    kiwi_required = spec.kiwi_required

    # Build the Click options
    params = [
        click.Option(["--check"], is_flag=True, help="Dry-run: print result without exiting non-zero"),
        click.Option(["--json", "emit_json"], is_flag=True, help="Emit JSON output to stdout"),
    ]
    for flag in spec.extra_flags:
        params.append(
            click.Option(
                [flag["flag"]],
                is_flag=True,
                help=flag["help"],
            )
        )

    def build_cmd(
        check: bool,
        emit_json: bool,
        **extra: bool,
    ) -> None:
        import sgate
        repo_root = sgate.repo.ROOT
        gate_name = module_name

        # Build the command list based on cmd_type
        if spec.cmd_type == "python3":
            script = repo_root / spec.script
            if not script.exists():
                logger.error(f"{script} not found")
                gate_fail(gate_name, None, None, None, None, "script not found")
                raise SystemExit(IO_ERROR)
            cmd = [sys.executable, str(script)]
            cmd.extend(spec.script_args)
            for flag in spec.extra_flags:
                if extra.get(flag["param"]):
                    cmd.append(flag["script_arg"])

        elif spec.cmd_type == "python3_m":
            # python3 -m <module> [args...]  — for unittest discover etc.
            cmd = [sys.executable, "-m", spec.script]
            cmd.extend(spec.script_args)
            for flag in spec.extra_flags:
                if extra.get(flag["param"]):
                    cmd.append(flag["script_arg"])

        elif spec.cmd_type == "shell":
            script = repo_root / spec.script
            if not script.exists():
                logger.error(f"{script} not found")
                gate_fail(gate_name, None, None, None, None, "script not found")
                raise SystemExit(IO_ERROR)
            cmd = ["bash", str(script)]
            cmd.extend(spec.script_args)
            for flag in spec.extra_flags:
                if extra.get(flag["param"]):
                    cmd.append(flag["script_arg"])

        elif spec.cmd_type == "bash_c":
            static_sh = sgate.repo.ROOT / "scripts" / "ci" / "static-gates.sh"
            if not static_sh.exists():
                logger.error(f"{static_sh} not found")
                gate_fail(gate_name, None, None, None, None, "static-gates.sh not found")
                raise SystemExit(IO_ERROR)
            cmd = ["bash", "-c", f". {static_sh} && {spec.bash_func}"]

        elif spec.cmd_type == "path":
            cmd = list(spec.path_cmd)
        else:
            logger.error(f"unknown cmd_type: {spec.cmd_type}")
            gate_fail(gate_name, None, None, None, None, "internal error")
            raise SystemExit(INTERNAL_ERROR)

        try:
            if emit_json:
                result = run_capture(cmd, gate_name)
                handle_result(result, gate_name)
            run(cmd, gate_name)
        except SystemExit:
            raise
        except Exception:
            logger.exception("unexpected error")
            gate_fail(gate_name, None, None, None, None, "unexpected error")
            raise SystemExit(INTERNAL_ERROR)

    cmd = click.Command(
        name=module_name,
        params=params,
        callback=build_cmd,
    )
    cmd.KIWI_REQUIRED = kiwi_required  # type: ignore[attr-defined]
    return cmd


# ── register_all ────────────────────────────────────────────────────────────────


def register_all() -> None:
    """Register every gate in GATES with the sgate CLI."""
    for spec in GATES:
        cmd = make_command(spec)
        register(cmd)
