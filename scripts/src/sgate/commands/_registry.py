"""sgate.commands._registry — single source of truth for gate metadata.

The GATES list is the canonical registry. All gate commands are produced by
``make_command()`` — there are no hand-written shims for generated gates.

Design
------
A GateSpec describes what a gate runs. ``make_command()`` returns a
Click Command that runs the gate. ``register_all()`` registers every command
with the CLI.

Command types
------------
python3 <path>  — run a Python script via sys.executable
bash <path>      — run a shell script via bash
bash -c <func>   — source static-gates.sh and call a named function
PATH command     — run something on PATH (e.g. traceability)
python3 -m <mod> — run a Python module as a script (e.g. unittest discover)

Custom gates (hand-written Click commands) are NOT in GATES; they live in
their own modules and import themselves via the normal Click discovery.
"""
from __future__ import annotations

import re
import sys
from dataclasses import dataclass, field
from enum import StrEnum
from pathlib import Path

import click

from sgate.log import logger
from sgate.output import gate_fail
from sgate.commands._runner import run, run_capture, Result, handle_result
from sgate.commands.base import IO_ERROR, INTERNAL_ERROR, register

# Re-export for convenience
GateCommand = click.Command


# ── Enums ───────────────────────────────────────────────────────────────────────


class GateMode(StrEnum):
    """Whether a gate failure blocks the pipeline or is only advisory."""
    BLOCKING = "blocking"
    ADVISORY = "advisory"


class CmdType(StrEnum):
    """How the gate is invoked — determines the command construction."""
    PYTHON3 = "python3"          # python3 <script> [args...]
    PYTHON3_M = "python3_m"      # python3 -m <module> [args...]
    SHELL = "shell"              # bash <script> [args...]
    BASH_C = "bash_c"            # bash -c ". {static_gates.sh} && {func}"
    PATH = "path"                # run something on PATH


# ── GateSpec ────────────────────────────────────────────────────────────────────


@dataclass(frozen=True, slots=True)
class ExtraFlag:
    """A boolean flag accepted by a gate command, forwarded as a script argument."""
    param: str          # Click parameter name (without --)
    flag: str           # Click option string, e.g. --self-test
    help: str           # Help text shown in --help
    script_arg: str     # Argument appended to the script when the flag is set


@dataclass(frozen=True, slots=True)
class GateSpec:
    """One gate's metadata, sufficient to produce a Click command."""

    #: Display name (used in --json output, gate_fail annotations, etc.)
    name: str
    #: blocking | advisory
    mode: GateMode
    #: How the gate is invoked.
    cmd_type: CmdType
    #: Script path for python3/shell commands, relative to repo root.
    script: Path = Path()
    #: Extra arguments appended to the script command.
    script_args: tuple[str, ...] = field(default_factory=tuple)
    #: Function name for bash_c commands.
    bash_func: str = ""
    #: Full command list for PATH commands.
    path_cmd: tuple[str, ...] = field(default_factory=tuple)
    #: KIWI_REQUIRED gates skip (exit 4) when the command is not found.
    kiwi_required: bool = False
    #: Extra boolean flags this gate accepts (forwarded as script arguments).
    extra_flags: tuple[ExtraFlag, ...] = field(default_factory=tuple)


def _module_name(display_name: str) -> str:
    """Convert gate display name to Python module filename."""
    s = re.sub(r"[^a-z0-9]+", "_", display_name.lower())
    return re.sub(r"_+", "_", s).strip("_")


# ── GATES registry ─────────────────────────────────────────────────────────────

GATES: tuple[GateSpec, ...] = (
    # ── Build hygiene ───────────────────────────────────────────────────────────────
    GateSpec(
        name="version catalog has no literals",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/build-version-catalog-gate.py"),
        script_args=("--quiet", "."),
    ),
    GateSpec(
        name="gate scripts' own unit tests",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/tests"),
        script_args=("scripts/tests",),
    ),
    GateSpec(
        name="test-task external inputs declared",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-test-task-inputs.py"),
    ),
    GateSpec(
        name="declared dependencies are used",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-dependency-usage.py"),
    ),
    GateSpec(
        name="room schema integrity",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-room-schema-integrity.py"),
    ),
    GateSpec(
        name="gates are alive",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-gate-liveness.py"),
    ),
    GateSpec(
        name="closed ADRs keep their body",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-adr-immutable.py"),
    ),
    GateSpec(
        name="skills describe the tools that exist",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-skill-drift.py"),
    ),
    GateSpec(
        name="git hooks path is per-worktree",
        mode=GateMode.ADVISORY,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-hooks-path.py"),
        script_args=("--quiet",),
    ),
    GateSpec(
        name="unwired surfaces",
        mode=GateMode.ADVISORY,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/find-unwired-surfaces.py"),
        script_args=("--quiet",),
    ),
    GateSpec(
        name="unwired backlog refs",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-unwired-backlog-refs.py"),
    ),
    GateSpec(
        name="settings read by a feature",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-dead-settings.py"),
        script_args=("--quiet",),
    ),
    GateSpec(
        name="YAML has no duplicate keys",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-yaml-duplicate-keys.py"),
        script_args=("--quiet",),
    ),
    # ── Lint-rule governance ──────────────────────────────────────────────────────
    GateSpec(
        name="detekt rules config is current",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/gen-detekt-rules-config.py"),
        script_args=("--check",),
    ),
    GateSpec(
        name="detekt rule inventory is current",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/gen-detekt-rule-table.py"),
        script_args=("--check",),
    ),
    GateSpec(
        name="detekt rule registry",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.SHELL,
        script=Path("scripts/check-detekt-registrations.sh"),
    ),
    GateSpec(
        name="detekt baseline ratchet",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-baseline-ratchet.py"),
    ),
    GateSpec(
        name="lint rules are declared decisions",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-rule-intent.py"),
    ),
    GateSpec(
        name="file-level suppressions explain themselves",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-suppression-intent.py"),
    ),
    # ── Scenario traceability ──────────────────────────────────────────────────────
    GateSpec(
        name="scenario specs valid",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PATH,
        path_cmd=("traceability", "validate"),
    ),
    GateSpec(
        name="coverage matrix current",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PATH,
        path_cmd=("traceability", "coverage", "--check"),
    ),
    GateSpec(
        name="coverage holes did not grow",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-traceability-ratchet.py"),
    ),
    GateSpec(
        name="speckit scenarios current",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PATH,
        path_cmd=("traceability", "coverage", "--check"),
    ),
    GateSpec(
        name="speckit stale",
        mode=GateMode.ADVISORY,
        cmd_type=CmdType.PATH,
        path_cmd=("traceability", "validate"),
    ),
    # ── Server schema ─────────────────────────────────────────────────────────────
    GateSpec(
        name="supabase schema is self-consistent",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-supabase-schema-integrity.py"),
    ),
    GateSpec(
        name="sync allowlist seed matches contract",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-sync-allowlist-regenerated.py"),
    ),
    # ── Maestro ───────────────────────────────────────────────────────────────────
    GateSpec(
        name="maestro selectors valid",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/ci/validate-maestro-selectors.py"),
    ),
    # ── Docs / agent-facing text ───────────────────────────────────────────────────
    GateSpec(
        name="skills catalog current",
        mode=GateMode.ADVISORY,
        cmd_type=CmdType.SHELL,
        script=Path("scripts/regen-skills-catalog.sh"),
        script_args=("--check",),
    ),
    GateSpec(
        name="skill frontmatter",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.SHELL,
        script=Path("scripts/check-skill-frontmatter.sh"),
    ),
    GateSpec(
        name="decisions digest within budget",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.BASH_C,
        bash_func="digest_and_sizes",
    ),
    GateSpec(
        name="digest has no double suffix",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.BASH_C,
        bash_func="digest_no_double_suffix",
    ),
    GateSpec(
        name="no dead doc refs",
        mode=GateMode.ADVISORY,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-doc-dead-refs.py"),
        extra_flags=(
            ExtraFlag(
                param="skill_symbols",
                flag="--skill-symbols",
                help="Check Kotlin symbol references in skills",
                script_arg="--skill-symbols",
            ),
        ),
    ),
    GateSpec(
        name="no dead skill symbols",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-doc-dead-refs.py"),
        script_args=("--skill-symbols",),
    ),
    GateSpec(
        name="publication hygiene",
        mode=GateMode.ADVISORY,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-publication-hygiene.py"),
        extra_flags=(
            ExtraFlag(
                param="self_test",
                flag="--self-test",
                help="Prove the rules fire on a known-bad corpus",
                script_arg="--self-test",
            ),
        ),
    ),
    GateSpec(
        name="publication hygiene self-test",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-publication-hygiene.py"),
        script_args=("--self-test",),
    ),
    GateSpec(
        name="README claims",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-readme-claims.py"),
        extra_flags=(
            ExtraFlag(
                param="self_test",
                flag="--self-test",
                help="Prove the rules fire on a known-bad corpus",
                script_arg="--self-test",
            ),
        ),
    ),
    GateSpec(
        name="README claims self-test",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-readme-claims.py"),
        script_args=("--self-test",),
    ),
    # ── ADR governance ─────────────────────────────────────────────────────────────
    GateSpec(
        name="ADR references are valid",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-adr-references.py"),
    ),
    GateSpec(
        name="ADR status policy",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check_adr_status.py"),
    ),
    GateSpec(
        name="ADR corpus rules are shared",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-adr-config-sync.py"),
    ),
    GateSpec(
        name="light ADR modules stay light",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-adr-module-boundary.py"),
        script_args=("--quiet",),
    ),
    GateSpec(
        name="ADR frontmatter drift",
        mode=GateMode.ADVISORY,
        cmd_type=CmdType.BASH_C,
        bash_func="adr_dry_run",
    ),
    # ── spec-kit ──────────────────────────────────────────────────────────────────
    GateSpec(
        name="spec-kit issue registry current",
        mode=GateMode.ADVISORY,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-speckit-issue-registry.py"),
    ),
    GateSpec(
        name="requirement identifiers are unique",
        mode=GateMode.ADVISORY,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-req-id-uniqueness.py"),
    ),
    # ── AppTracer ─────────────────────────────────────────────────────────────────
    GateSpec(
        name="apptracer upload policy encoded",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-apptracer-policy.py"),
    ),
    # ── Backlog ───────────────────────────────────────────────────────────────────
    GateSpec(
        name="backlog-issue-refs",
        mode=GateMode.ADVISORY,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-backlog-issue-refs.py"),
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
                [flag.flag],
                is_flag=True,
                help=flag.help,
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
        if spec.cmd_type == CmdType.PYTHON3:
            script = repo_root / spec.script
            if not script.exists():
                logger.error(f"{script} not found")
                gate_fail(gate_name, None, None, None, None, "script not found")
                raise SystemExit(IO_ERROR)
            cmd = [sys.executable, str(script)]
            cmd.extend(spec.script_args)
            for flag in spec.extra_flags:
                if extra.get(flag.param):
                    cmd.append(flag.script_arg)

        elif spec.cmd_type == CmdType.PYTHON3_M:
            # python3 -m <module> [args...]  — for unittest discover etc.
            cmd = [sys.executable, "-m", str(spec.script)]
            cmd.extend(spec.script_args)
            for flag in spec.extra_flags:
                if extra.get(flag.param):
                    cmd.append(flag.script_arg)

        elif spec.cmd_type == CmdType.SHELL:
            script = repo_root / spec.script
            if not script.exists():
                logger.error(f"{script} not found")
                gate_fail(gate_name, None, None, None, None, "script not found")
                raise SystemExit(IO_ERROR)
            cmd = ["bash", str(script)]
            cmd.extend(spec.script_args)
            for flag in spec.extra_flags:
                if extra.get(flag.param):
                    cmd.append(flag.script_arg)

        elif spec.cmd_type == CmdType.BASH_C:
            static_sh = sgate.repo.ROOT / "scripts" / "ci" / "static-gates.sh"
            if not static_sh.exists():
                logger.error(f"{static_sh} not found")
                gate_fail(gate_name, None, None, None, None, "static-gates.sh not found")
                raise SystemExit(IO_ERROR)
            cmd = ["bash", "-c", f". {static_sh} && {spec.bash_func}"]

        elif spec.cmd_type == CmdType.PATH:
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
