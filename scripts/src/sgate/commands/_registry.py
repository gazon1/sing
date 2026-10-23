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
from dataclasses import dataclass, field, replace
from enum import StrEnum
from pathlib import Path

import click

from sgate.commands._runner import Result, handle_result, run, run_capture
from sgate.commands.base import INTERNAL_ERROR, IO_ERROR, register
from sgate.controls import Control, Edit, GradleTaskParity, Parity
from sgate.gate_manifest import CLUSTERS, NOT_RUN_IN_STATIC, GateMode, Placement
from sgate.log import logger
from sgate.output import gate_fail

# Re-export for convenience
GateCommand = click.Command


# ── Enums ───────────────────────────────────────────────────────────────────────


class CmdType(StrEnum):
    """How the gate is invoked — determines the command construction."""
    PYTHON3 = "python3"          # python3 <script> [args...]
    PYTHON3_M = "python3_m"      # python3 -m <module> [args...]
    SHELL = "shell"              # bash <script> [args...]
    BASH_C = "bash_c"            # bash -c ". {static_gates.sh} && {func}"
    PATH = "path"                # run something on PATH


#: What each module under ``scripts/src/sgate/commands/`` is.
#:
#: A reader of ``sgate --help`` cannot tell a gate from a utility: the CLI
#: lists them side by side, and a command that exists to run another gate's
#: script looks like a gate of its own. One of them was in fact dead —
#: ``unwired_backlog_refs`` was a shim for a script that went with its gate in
#: 2026-10-19, so the command could only fail.
#:
#: The kinds:
#:   gate            — a gate: a line in static-gates.sh names it
#:   utility         — a command that is not a gate (info, the registry doc)
#:   suite           — a group of gates run together
#:   driver          — the runner itself (``run-all``)
#:   infrastructure  — the machinery the others are built from
#:
#: ``scripts/check-gate-registries.py`` checks this list against the files on
#: disk in both directions: a module nobody classified is a decision nobody
#: made, and a classified module that is gone is a claim about a file that no
#: longer exists.
#:
#: Two commands here were shims for a gate that no longer existed, and could
#: only fail. ``unwired_backlog_refs`` was a shim for a script that went with
#: its gate in 2026-10-19; ``light_adr_modules_stay_light`` was a shim for
#: ``scripts/check-adr-module-boundary.py``, deleted on 2026-10-15 when the
#: owner retired that gate. Both are gone — a command that cannot pass is not
#: a gate, it is a way to run a stack trace.
#: The tree rules the pre-push hook runs against the tree being pushed.
#:
#: The hook carried the list in its own loop, and the test that drives the hook
#: carried a second list — the scripts it stubs. A gate added to the hook and
#: not to the fixture was a failing test, and the failure was found by the full
#: run rather than by the edit that caused it. One declaration now serves both
#: sides: the hook reads the rules, the test reads the scripts.
PRE_PUSH_TREE_RULES: tuple[str, ...] = (
    "check-agents-pointers",
    "check-issue-registry",
    "check-review-verdicts",
)

#: Every script the pre-push hook runs.
#:
#: The hook skips a rule the pushed tree does not have, so the list is what the
#: hook *may* run; the tree decides what it does. The test stubs all of them,
#: because a hook that calls a script nobody stubbed fails for a reason that
#: has nothing to do with what the test is about.
PRE_PUSH_SCRIPTS: tuple[str, ...] = (
    "scripts/build-version-catalog-gate.py",
    "scripts/check-ui-wait-budgets.py",
    "scripts/ci/validate-maestro-selectors.py",
    "scripts/check-di-fixture-sync.py",
    "scripts/check-navigation-doc-sync.py",
    # The hook calls these two as well, and the declaration did not name them:
    # a script the hook runs and the declaration does not list is a gate the
    # test fixture does not stub — a failing test that only the full run
    # finds. scripts/check-hook-gates.py is what found it.
    "scripts/check-maestro-flow-catalogue.py",
    "scripts/check-test-tags-catalog.py",
) + tuple(f"scripts/{rule}.py" for rule in PRE_PUSH_TREE_RULES)

COMMAND_MODULES: dict[str, str] = {
    "adr_references_are_valid": "gate",
    "adr_status_policy": "gate",
    "build_output_readers": "gate",
    "conventions": "gate",
    "decisions_digest_within_budget": "gate",
    "gradle_test_population": "gate",
    "room_schema_integrity": "gate",
    "settings_read_by_a_feature": "gate",
    "test_task_external_inputs_declared": "gate",
    "gate_costs": "gate",
    "host_load": "utility",
    "info": "utility",
    "render_registry": "utility",
    "render_static_gates": "utility",
    "whereami": "utility",
    "wiring": "utility",
    "run_all": "driver",
    "suite_docs": "suite",
    "suite_lint": "suite",
    "base": "infrastructure",
    "_gate_runner": "infrastructure",
    "_parsers": "infrastructure",
    "_registry": "infrastructure",
    "_runner": "infrastructure",
}


# ── Gradle-derived data ─────────────────────────────────────────────────────────
#
# Data derived from the Gradle build — file-content analysis that must stay
# in sync with the build file contents. These live here (not in check-gate-wiring)
# so they can be read by any consumer, and so the reader in check-gate-wiring
# flips rather than multiplying the source of truth.
#
# Wave (б): Parts A/C/I of check-gate-wiring.py.

#: Minimum kover verify bound that counts as a usable floor, expressed as a
#: percentage integer (0–100). The strongest bound in any module must meet
#: this floor, because one rule that can fail is enough to make the task fail.
_KOVER_MIN_FLOOR: int = 60

#: Declared CI/local asymmetries for individual Gradle tasks.
#: Each entry maps a task name (`:module:task`) to its declared scope and reason.
#: A `covered` task is one CI reaches without spelling it (e.g. via `koverReport`);
#: check.sh must still name it for the rehearsal to be valid.
GRADLE_TASK_PARITY: dict[str, GradleTaskParity] = {
    ":shared:jvmTest": GradleTaskParity(
        scope="covered",
        why=(
            "CI reaches it through `koverReport`, which aggregates the test task and "
            "instruments it. `ci.yml` never spells the task; adding a second literal "
            "invocation would be noise, not coverage."
        ),
    ),
    ":desktopApp:test": GradleTaskParity(
        scope="covered",
        why="same route as :shared:jvmTest — `koverReport` in ci.yml's tests job.",
    ),
    ":androidApp:connectedDebugAndroidTest": GradleTaskParity(
        scope="covered",
        why=(
            "CI runs it in android-device-tests.yml, not ci.yml. It needs an "
            "emulator, so it cannot join a PR's cheap surfaces; see "
            "docs/decisions/2026-10-07-the-instrumentation-tier-ran-in-no-ci-job.md"
        ),
    ),
    ":mcp-server:compileKotlin": GradleTaskParity(
        scope="local",
        why=(
            "CI compiles this module through :mcp-server:jar, which is a strict "
            "superset — the DI-graph check runs either way. Naming it locally is "
            "what makes the validation visible on the surface a developer reads."
        ),
    ),
    ":mcp-server:jar": GradleTaskParity(
        scope="ci",
        why=(
            "McpServerEndToEndTest resolves build/libs/mcp-server.jar and skips "
            "itself when the jar is absent. Building it is a CI concern; the "
            "local loop validates compilation, not the packaged artifact."
        ),
    ),
    ":pro:detekt": GradleTaskParity(
        scope="ci",
        why=(
            ":pro is included in settings.gradle.kts only under "
            "-PwithPro=true, so the task does not exist in a plain local build. "
            "CI's `pro` matrix leg passes the flag."
        ),
    ),
    ":pro:testDebugUnitTest": GradleTaskParity(
        scope="ci",
        why=(
            "same reason as :pro:detekt — the module is only in the graph when "
            "-PwithPro=true is passed, which is the `pro` matrix leg's job"
        ),
    ),
    ":shared:testAndroidHostTest": GradleTaskParity(
        scope="covered",
        why=(
            "CI reaches it through `koverReport`, which aggregates the test task and "
            "instruments it; `ci.yml` never spells the task, and the Android host "
            "source set was CI-only for a while — the local loop did not compile it "
            "at all. That was a hole with a price: `LocalDate.minusDays` — an API "
            "`kotlinx-datetime 0.8` does not have — passed a green detekt and 3266 "
            "green JVM tests, because detekt runs without type resolution and the JVM "
            "tests do not compile `src/androidMain`. Only the CI job said so. The "
            "host test is a Robolectric test, so naming it here costs a minute and "
            "no emulator."
        ),
    ),
    ":shared:verifyToolSurface": GradleTaskParity(
        scope="covered",
        why=(
            "CI reaches it through `ToolSurfaceExportTest` in `:shared:jvmTest`, "
            "which compares the committed `config/docs/tool-surface.json` with the "
            "surface the code declares. The Gradle task is the fast rehearsal of "
            "that check for the local loop: it exports to a scratch file and "
            "compares, without waiting for the twenty-minute suite. Naming it "
            "in `ci.yml` as well would buy a second run of a check CI already "
            "performs — and a second Gradle invocation on every PR."
        ),
    ),
    ":adr-cli:installDist": GradleTaskParity(
        scope="ci",
        why=(
            "ci.yml's adr-gate job builds the ADR CLI distribution to run it "
            "against the repository; the local loop reaches the same invariants "
            "through the check_adr_*.py gates in the shared registry, and reaches "
            ":adr-cli:run only interactively (ADR authoring), so a packaged "
            "installDist is a CI-only artifact"
        ),
    ),
    ":shared:compileKotlinJvm": GradleTaskParity(
        scope="ci",
        why=(
            "the CodeQL analysis job (codeql.yml) compiles the JVM target so the "
            "extractor can map bytecode back to sources. It is a compile, not a "
            "test population: the local loop reaches the same compilation through "
            ":shared:jvmTest and :shared:detekt, and mirroring a compile-only task "
            "in the developer loop would buy nothing the rehearsal does not "
            "already cover. See "
            "docs/decisions/2026-10-19-security-workflows-from-upstream-templates.md"
        ),
    ),
    ":core-base:compileKotlinJvm": GradleTaskParity(
        scope="ci",
        why=(
            "same reason as :shared:compileKotlinJvm — the CodeQL analysis job "
            "compiles it so the scanner sees the module's sources. The local loop "
            "compiles :core-base through the tasks that depend on it"
        ),
    ),
}

#: Modules that have a `detekt { }` block in their build file but are not wired
#: to any gate. Each entry is the module name (no leading `:`). An exemption
#: declares that the configured task is deliberately not invoked by any gate, and
#: carries the reason so a reader can verify the asymmetry is intentional.
#: Format: {module_name: reason_string}
#: Populated in check-gate-wiring.py by _populate_derived_tables().
DETEKT_TASK_EXEMPTIONS: dict[str, str] = {}


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
    #: The input change that must make this gate fail, and why it is that change.
    #: Data, not code: this module is imported by every gate, so nothing
    #: executable may live on the registry path. Appliers live in
    #: scripts/check-gate-wiring.py; the types are sgate.controls.
    control: Control | None = None
    #: A declared CI/local asymmetry, when the default "both" is wrong.
    parity: Parity | None = None


def _module_name(display_name: str) -> str:
    """Convert gate display name to Python module filename."""
    s = re.sub(r"[^a-z0-9]+", "_", display_name.lower())
    return re.sub(r"_+", "_", s).strip("_")


# ── GATES registry ─────────────────────────────────────────────────────────────

_REGISTERED: tuple[GateSpec, ...] = (
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
        name="build-output readers precede the invocations that rewrite them",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-build-output-readers.py"),
    ),
    GateSpec(
        name="gradle invocations keep one test population",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-gradle-test-population.py"),
    ),
    GateSpec(
        name="published measurements match the record",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-published-measurements.py"),
    ),
    GateSpec(
        name="waiver references resolve",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-waiver-references.py"),
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
        name="the toolbox surface is declared once",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-skill-drift.py"),
    ),
    GateSpec(
        name="workflow env context resolves",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-workflow-env-context.py"),
    ),
    GateSpec(
        name="maestro flow catalogue is current",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-maestro-flow-catalogue.py"),
    ),
    GateSpec(
        name="ui wait budgets are consistent",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-ui-wait-budgets.py"),
    ),
    GateSpec(
        name="git hooks path is per-worktree",
        mode=GateMode.ADVISORY,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-hooks-path.py"),
        script_args=("--quiet",),
    ),
    GateSpec(
        name="hook gates are declared once",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-hook-gates.py"),
    ),
    GateSpec(
        name="unwired surfaces",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/find-unwired-surfaces.py"),
        script_args=("--quiet",),
    ),
    GateSpec(
        name="settings read by a feature",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-dead-settings.py"),
        script_args=("--quiet",),
    ),
    GateSpec(
        name="coverage task list",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-coverage-task-list.py"),
        script_args=("--quiet",),
    ),
    GateSpec(
        name="coverage floor declaration",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-coverage-floor-declaration.py"),
        script_args=("--quiet",),
    ),
    GateSpec(
        name="coverage timeout",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-coverage-timeout.py"),
        script_args=("--quiet",),
    ),
    GateSpec(
        name="selectable text coverage",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-selectable-text-coverage.py"),
        script_args=("--quiet", "--check"),
    ),
    GateSpec(
        name="issue registry covers every change",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-issue-registry.py"),
        script_args=("--quiet",),
    ),
    GateSpec(
        name="AGENTS.md pointers resolve",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-agents-pointers.py"),
        script_args=("--quiet",),
    ),
    GateSpec(
        name="review verdicts cover every change",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-review-verdicts.py"),
        script_args=("--quiet",),
    ),
    GateSpec(
        name="the tag catalogue matches the tags",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-test-tags-catalog.py"),
        script_args=("--quiet", "--check"),
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
    # These two used to be `cmd_type=CmdType.PATH, path_cmd=("traceability", …)`.
    # There is no `traceability` executable: `[project.scripts]` in
    # scripts/src/pyproject.toml declares only sgate, ensure-emulator and
    # run-maestro, and `subprocess.run(["traceability", …])` does not resolve the
    # bash function `static-gates.sh` exports under that name — an exported
    # function is not on PATH. Both gates therefore died on FileNotFoundError
    # and reported "traceability not found" instead of their verdict, on `main`
    # as well: introduced when the shim modules were replaced by the registry in
    # e05a68ad. A gate that cannot run is the defect class this repo treats as
    # loud, and it failed quietly because the failure path looked like a
    # legitimate red.
    GateSpec(
        name="scenario specs valid",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3_M,
        script=Path("traceability"),
        script_args=("validate",),
    ),
    GateSpec(
        name="coverage matrix current",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3_M,
        script=Path("traceability"),
        script_args=("coverage", "--check"),
    ),
    GateSpec(
        name="coverage holes did not grow",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-traceability-ratchet.py"),
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
    # ── Workflow semantics ──────────────────────────────────────────────────────────
    GateSpec(
        name="workflow steps keep their exit status",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-workflow-exit-status.py"),
    ),
    # ── Releases ───────────────────────────────────────────────────────────────────
    GateSpec(
        name="releases describe themselves",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-release-tags.py"),
    ),
    # ── Maestro ───────────────────────────────────────────────────────────────────
    GateSpec(
        name="maestro selectors valid",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/ci/validate-maestro-selectors.py"),
    ),
    GateSpec(
        name="maestro keyboard commands have something to hide",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-maestro-keyboard-commands.py"),
    ),
    GateSpec(
        name="maestro flows do not name a pixel",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-maestro-screen-geometry.py"),
    ),
    GateSpec(
        name="maestro results are within the floor",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/ci/check-maestro-results.py"),
        # The run's verdict was a number in a CI log that nobody read. This
        # gate turns it into a number in a file — config/docs/maestro-failure-floor.txt
        # — and fails when the debt grows. It runs where the reports are:
        # the `maestro-results` job of e2e.yml, after the shards.
    ),
    GateSpec(
        name="profile reset inventory is true",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/ci/check-profile-reset-inventory.py"),
        # The E2E suite isolates one flow from the next by wiping the app's
        # content between flows. The list of tables that wipe reaches is a list
        # of exceptions, and a list of exceptions rots silently: a table added
        # to the Room schema is not a row in a document, so the document keeps
        # claiming the wipe reaches the whole profile while the code has
        # stopped reaching it. This gate holds the schema, the wipe's own list
        # and the inventory document to each other.
    ),
    GateSpec(
        name="the text-field transformation table is true",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-text-field-transformations-table.py"),
        # A skill that lists the text-field transformations is a promise that the
        # list is the code's list. Nothing held it: a transformation could be
        # added, renamed, or have its rule changed, and the table kept claiming
        # the old thing — and a reader has no way to know which half of the
        # table is true. This gate reads the table and compares it with the
        # transformations the code declares.
    ),
    GateSpec(
        name="the platform reference ports are true",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-platform-reference-ports.py"),
        # The platform reference is the inventory of the seam: which port is
        # bound to which implementation on which platform. It said so itself —
        # "no gate will notice" — and it had rotted: it still named a port that
        # was deleted and did not name the twenty ports the seam has. This gate
        # reads the reference and compares it with the code.
    ),
    # ── Docs / agent-facing text ───────────────────────────────────────────────────
    GateSpec(
        name="doc dead refs",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-doc-dead-refs.py"),
        # `--strict` makes path drift a failure, not a warning.
        #
        # Drift is what this gate reports when a referenced path does not resolve
        # from the repository root but a file of the same basename exists
        # somewhere else — the leniency that let fifteen broken Markdown links
        # (and the drift behind them) survive to 2026-10-19. A warning does not
        # stop anything: the references it names keep pointing at the wrong
        # place until a reader follows one and finds nothing.
        script_args=("--strict",),
    ),
    # doc links: the lychee gate. A shell gate, like `detekt registrations`
    # above — lychee is a Rust binary, not a Python script, and the wrapper
    # carries the flags and the "is it on PATH" check.
    #
    # It does not overlap `doc dead refs`. That gate's PATH_RE matches a path
    # inside backticks and its ADR_SLUG_RE a dated slug; a Markdown link was
    # never in either scope. See the script's header for why the two resolve
    # differently and why that difference mattered.
    GateSpec(
        name="doc links",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.SHELL,
        script=Path("scripts/check-doc-links.sh"),
    ),
    # The navigation doc says what opens what. `doc links` above checks that the
    # links in it resolve; it cannot see that a *claim* about behaviour has
    # stopped being true, because the link is fine and the sentence is wrong.
    #
    # Two rows of `docs/navigation.md` went false on 2026-10-22 and nothing
    # noticed: the deep-link row for `singularity://view/{viewId}` still named
    # `SavedAgendaEdit` after the code moved to `SavedAgendaResults`, and the
    # platform row still said the desktop tasks stack pushes the `Detail` start
    # onto the stack after the code changed to seed the stack with the
    # requested route. The doc is pointed at from the nav3 skill, so an agent
    # that follows the pointer reads both.
    GateSpec(
        name="navigation doc matches the code",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-navigation-doc-sync.py"),
        script_args=("--check",),
    ),
    # The DI fixture table says what to hand a Koin definition that takes its
    # arguments at runtime. The definition lives in `shared/.../core/di/`, the
    # fixture in `desktopApp/.../jvmTest/`, and nothing kept them in step: on
    # 2026-10-22 `TaskDetailCoordinator` was given a route instead of a
    # `TaskId`, the definition was updated, the fixture was not, and the graph
    # could not be created. The test that catches it lives in the full desktop
    # suite — an hour away on a loaded host, at the very end of the work.
    #
    # This gate reads the same two facts from the sources and compares them
    # without a JVM, so it runs in the static gates and in the pre-push hook.
    GateSpec(
        name="DI fixture matches the definitions",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-di-fixture-sync.py"),
        script_args=("--check",),
    ),
    # The two linters that are not Python scripts. They are named functions in
    # static-gates.sh, because a gate the CLI cannot dispatch is a gate only one
    # surface can run — and the two registries then disagree about what exists.
    GateSpec(
        name="GitHub workflows are valid",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.BASH_C,
        bash_func="github_workflows_valid",
    ),
    GateSpec(
        name="YAML is structurally valid",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.BASH_C,
        bash_func="yaml_is_structurally_valid",
    ),
    # The gate that checks the two registries against each other. It is a script
    # gate, so the registry entry names the script; the comparison itself reads
    # both registries, and its verdict is what keeps the gap from being invisible.
    GateSpec(
        name="gate registries agree",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-gate-registries.py"),
    ),
    # The generated catalogue of this registry: which gates exist, and whether
    # each one blocks. `docs/scripts/gate-registry.md` is derived from GATES, so
    # a gate added, removed or reclassified changes the table — and this gate is
    # what says the committed table still matches.
    #
    # Until 2026-10-20 nothing checked that. The table had drifted: the
    # generator read the command-modules directory instead of the registry, so it
    # listed private helpers as gates and reported `mode=unknown` for most rows.
    GateSpec(
        name="gate registry is current",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3_M,
        script=Path("sgate"),
        script_args=("render-registry", "--check"),
    ),
    # The shell registry is a generated artifact: `scripts/ci/static-gates.sh`
    # holds a generated region, and this gate says the file is what the
    # manifest renders today. Without it the two could drift again — the drift
    # the previous registry check existed to catch, in the shape the check
    # could no longer see, because the shell file had become a copy nobody
    # generated.
    GateSpec(
        name="the shell registry is current",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3_M,
        script=Path("sgate"),
        script_args=("render-static-gates", "--check"),
    ),
    # What the verification loop costs, published.
    #
    # The local loop grew by minutes at a time — a wave added an Android host test,
    # another added a module to the detekt sweep — and nobody could say so, because
    # nothing measured it. The measurement now exists (`sgate run-all` times every
    # gate, `check.sh` times every Gradle step), and this gate keeps the published
    # page and the recorded baseline in step.
    #
    # It compares two committed files, so it is a question a CI runner can answer
    # honestly: the *comparison* with a local measurement is not one CI can answer,
    # and it is not asked here.
    GateSpec(
        name="the published gate costs are current",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3_M,
        script=Path("sgate"),
        script_args=("gate-costs", "--check"),
    ),
    # The JSON contract every gate dispatched through `sgate` is called with.
    #
    # `sgate run-all` calls each gate with `--json`, and the document that
    # lands on stdout is what a consumer reads. Nothing checked that the
    # document was a document: the GitHub Actions annotation (`::error::` on
    # stdout, because that is where Actions reads workflow commands from) and
    # four adapters that rebuilt a verdict from prose were both found by
    # reading, and both were found late.
    GateSpec(
        name="gates speak the JSON contract",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-gate-json-contract.py"),
    ),
    # The two clusters.
    #
    # Each of these is one gate that runs several checks over one subject: the
    # CI workflow's Gradle block, and the governance around the detekt rules.
    # The members keep their own registry entries, their own scripts, their own
    # unit tests and their own positive controls — a control proves that a
    # check can fail, and one control for a cluster would prove only that
    # something in it can fail. What the cluster changes is the surface: one
    # CI row instead of nine, and one place to look when one of them fails.
    #
    # The members are run by the wrapper, so the shell registry names the
    # wrapper and the registry checker resolves the rest by delegation.
    GateSpec(
        name="ci workflow shape is declared",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-ci-workflow-shape.py"),
    ),
    GateSpec(
        name="detekt governance is consistent",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-detekt-governance.py"),
    ),
    GateSpec(
        name="pro licence boundary",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-pro-licence-boundary.py"),
    ),
    GateSpec(
        name="provenance",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-provenance.py"),
    ),
    # ── ADR governance ─────────────────────────────────────────────────────────────
    GateSpec(
        name="adr references are valid",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-adr-references.py"),
    ),
    # ── spec-kit ──────────────────────────────────────────────────────────────────
    GateSpec(
        name="flaky tests are acknowledged",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-flaky-tests.py"),
    ),
    # ── Backlog ───────────────────────────────────────────────────────────────────
    GateSpec(
        name="rebase compiles",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-rebase-compiles.py"),
    ),
    GateSpec(
        name="test runs",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-test-runs.py"),
        script_args=("--require", "shared:jvmTest,desktopApp:test"),
    ),
    GateSpec(
        name="instrumented test floor",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-instrumented-test-floor.py"),
        parity=Parity(
            scope="local",
            tracked_by='.github/workflows/android-device-tests.yml#Check the instrumented suite\'s floor',
            why=(
                "the gate reads the instrumented run's report, and the report exists "
                "only where the emulator ran: in CI that is the device-tests workflow, "
                "and locally it is the optional adb step of check.sh. It cannot live "
                "in the shared registry — the static CI job has no build output at all, "
                "so a gate there would fail on every run for want of a report. The CI "
                "invocation is therefore not visible to the parity check, which reads "
                "ci.yml and the shared registry only, and the scope says local because "
                "local is the only surface the check can see."
            ),
        ),
    ),
    GateSpec(
        name="script conventions",
        # Blocking: the corpus is green now that the rule reads the AST, so the
        # gate can stop a change instead of only reporting it.
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-script-conventions.py"),
    ),
        # The grammar of a link has a home: TaskLinkSpec for a deep link, core/links/InternalLinks.kt for an internal one. A second copy of it is a link that opens nothing.
        GateSpec(
            name="the link grammar has one home",
            mode=GateMode.BLOCKING,
            cmd_type=CmdType.PYTHON3,
            script=Path("scripts/check-link-grammar.py"),
            script_args=("--check",),
        ),
        # The catalogue of the gate scripts, generated from their own docstrings. A catalogue that lost a tool is a catalogue that sends a reader to a tool that is not there.
        GateSpec(
            name="the tool catalogue is current",
            mode=GateMode.BLOCKING,
            cmd_type=CmdType.PYTHON3,
            script=Path("scripts/check-tools-catalog.py"),
            script_args=("--check",),
        ),
)


# ── Positive controls ────────────────────────────────────────────────────────

# ── Positive controls ──────────────────────────────────────────
#
# Keyed by the control's own slug, the name the harness has always used
# for it. `proves` joins each control to the registry gate it covers —
# by script path, because display names are prose and one script can
# back two gates with two different controls.
GATE_CONTROLS: dict[str, Control] = {
    # the tag catalogue: the promise about which ids a flow may address. A tag
    # whose value was renamed in the code leaves the promise describing the old
    # world — the flow keeps using an id no screen answers to, and the failure
    # lands on a device rather than in a gate. The JVM test that owns the same
    # comparison lives in a forty-minute suite, so the drift is invisible for the
    # rest of the session. The probe renames one value to prove the gate can
    # still fail.
    'test-tags-catalogue': Control(
        kind='sabotage',
        proves='scripts/check-test-tags-catalog.py',
        argv=('{python}', 'scripts/check-test-tags-catalog.py', '--check'),
        path='shared/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt',
        edit=Edit(
            kind='replace',
            old='const val LOADING_INDICATOR = "loading_indicator"',
            new='const val LOADING_INDICATOR = "loading_indicator_probe"',
            count=1,
        ),
        why=(
            'the catalogue is the promise about which ids a flow may address, and a tag '
            'whose value was renamed in the code leaves the promise describing the old '
            'world: the flow keeps using an id that no screen answers to, and the '
            'failure lands on a device rather than in a gate. The JVM test that owns '
            'the same comparison lives in a forty-minute suite, so the drift is '
            'invisible for the rest of the session — this gate is the same '
            'comparison in a second, and the probe renames one value to prove it can '
            'still fail'
        ),
    ),
    # text-field-transformations-table: the table claims a limit the code does
    # not enforce. The probe changes the digit count of the recurrence rule
    # from two to three — the smallest change that a reader could not notice
    # in the prose and that the code would never allow.
    'text-field-transformations-table': Control(
        kind='sabotage',
        proves='scripts/check-text-field-transformations-table.py',
        argv=('{python}', 'scripts/check-text-field-transformations-table.py'),
        path='.agents/skills/singularity-todo-text-field-states/SKILL.md',
        edit=Edit(
            kind='replace',
            old='`drops=non-digit; limit=2; rewrite; rejects=edit; min=1`',
            new='`drops=non-digit; limit=3; rewrite; rejects=edit; min=1`',
            count=1,
        ),
        why=(
            "a transformation's rule is a promise about what the field accepts, "
            "and a table that is wrong about it sends an agent to write a field "
            "that takes three digits where the code takes two — the drift this "
            "gate exists to report, in the shape it happens: a limit changed in "
            "the code and not in the table, or in the table and not in the code"
        ),
    ),
    # platform-reference-ports: the reference names a port the code does not
    # have. The probe renames `Notifier` back to `NotificationPort`, the name
    # the reference carried before the port was replaced — the exact rot the
    # document had already suffered once.
    'platform-reference-ports': Control(
        kind='sabotage',
        proves='scripts/check-platform-reference-ports.py',
        argv=('{python}', 'scripts/check-platform-reference-ports.py'),
        path='docs/PLATFORM-REFERENCE.md',
        edit=Edit(
            kind='replace',
            old='| `Notifier` | `JvmNotifier` | `AndroidNotifier` | checked |',
            new='| `NotificationPort` | `JvmNotifier` | `AndroidNotifier` | checked |',
            count=1,
        ),
        why=(
            "the reference is the map of the seam, and a map that names a port "
            "the code deleted sends a reader to a type that is not there — while "
            "the port the code does have is missing from the map, so an agent "
            "adding a binding to it cannot see that it is crossing the seam"
        ),
    ),
    'profile-reset-inventory': Control(kind='sabotage', proves='scripts/ci/check-profile-reset-inventory.py', argv=('{python}', 'scripts/ci/check-profile-reset-inventory.py'), path='docs/architecture/wipe-inventory.md', edit=Edit(kind='replace', old='| `sync_state` | not profile content |', new='| `sync_state` | wiped |', count=1), why='the document claiming more than the code does is the failure this gate exists for: the ADR that describes the profile reset says it reaches the whole profile, and the code reached five things. The probe makes the inventory call the sync cursor profile content — a table whose deletion would make the next pull restore everything the reset just removed. The code does not wipe it, so the document and the code now disagree, and the gate must say so.'),
    'release-tags': Control(kind='sabotage', proves='scripts/check-release-tags.py', argv=('{python}', 'scripts/check-release-tags.py'), path='config/docs/releases-snapshot.json', edit=Edit(kind='replace', old='    "v0.2.1-rc.1"\n  ],', new='    "v0.2.1-rc.1",\n    "v9.9.9-probe"\n  ],', count=1), why='a tag that produced no release is the failure this gate exists for: `meta` proved the tag was on main with a green CI run, `publish` still did not run, and nothing said so. The probe appends exactly such a tag — recorded as neither released nor excused.'),
    'detekt-registrations': Control(kind='sabotage', proves='scripts/check-detekt-registrations.sh', argv=('./scripts/check-detekt-registrations.sh',), path='config/detekt/detekt.yml', edit=Edit(kind='replace', old='no-runblocking:\n  NoRunBlocking:\n    active: true\n', new='', count=-1), why='a RuleSetId with no detekt.yml block never loads, so the rule never runs'),
    'baseline-ratchet': Control(kind='sabotage', proves='scripts/check-baseline-ratchet.py', argv=('{python}', 'scripts/check-baseline-ratchet.py'), path='config/detekt/baseline-shared.xml', edit=Edit(kind='replace', old='</CurrentIssues>', new='    <ID>Sabotage:Probe.kt:Probe</ID>\n  </CurrentIssues>', count=-1), why='a baseline that can grow silently stops being a ratchet and becomes a sink'),
    'hooks-path': Control(kind='sabotage', proves='scripts/check-hooks-path.py', argv=('{python}', 'scripts/check-hooks-path.py', '--quiet'), path='.githooks/pre-push', edit=Edit(kind='hooks_path'), why='hooks travel with the repository; local git config does not. A configured hooks path with nothing tracked under it is a gate that reports success because nothing ever asked it a question'),
    # hook-gates: the record of what the hook judges drifts away from the hook
    # and from the CI. The probe removes one detekt module from the record —
    # exactly the state a new CI module leaves behind: CI judges the module, the
    # record does not mention it, and nothing says so. A reader of the hook
    # would read "detekt" as "the whole tree" and be wrong.
    'hook-gates': Control(
        kind='sabotage',
        proves='scripts/check-hook-gates.py',
        argv=('{python}', 'scripts/check-hook-gates.py'),
        path='config/hooks/pre-push.json',
        edit=Edit(
            kind='replace',
            old='      ":mcp-server:detekt",\n',
            new='',
            count=1,
        ),
        why='the hook judges three gates of forty-one and the modules a change reaches; the CI judges '
            'every module. That difference is written down in config/hooks/pre-push.json, and the '
            'record is the only place it can be read. A module the CI judges and the record does not '
            'mention is a difference nobody can see — which is the state this gate exists for',
    ),
    'skill-drift': Control(kind='sabotage', proves='scripts/check-skill-drift.py', argv=('{python}', 'scripts/check-skill-drift.py'), path='.agents/skills/singularity-todo-decisions-workflow/SKILL.md', edit=Edit(kind='replace', old='`singularity-adr validate`', new='`singularity-adr verifai`', count=1), why='a skill that lists commands the tool does not have sends an agent to run something that does not exist'),
    'workflow-env-context': Control(kind='sabotage', proves='scripts/check-workflow-env-context.py', argv=('{python}', 'scripts/check-workflow-env-context.py'), path='.github/workflows/e2e.yml', edit=Edit(kind='replace', old="  EMULATOR_GPU_MODE: ${{ vars.EMULATOR_GPU_MODE || 'off' }}", new="  EMULATOR_GPU_MODE_TYPO: ${{ vars.EMULATOR_GPU_MODE || 'off' }}", count=1), why='renaming the definition while the reference keeps the old name is the smallest possible version of the failure this gate exists for: the `env` context does not hold the runner\'s environment, so `${{ env.X }}` for an undefined X expands to the empty string and the path silently becomes a path to nothing. That is how the Maestro shards spent a release cycle failing with "emulator binary not found in PATH" while the cache carried an empty directory'),
    'maestro-flow-catalogue': Control(kind='sabotage', proves='scripts/check-maestro-flow-catalogue.py', argv=('{python}', 'scripts/check-maestro-flow-catalogue.py'), path='config/docs/maestro-flows.txt', edit=Edit(kind='regex', pattern=r'^Maestro/flows/smoke/01-launch-today\.yaml\n', repl='', flags='MULTILINE'), why='a flow deleted from the tree without a word leaves the suite smaller and the run green: the suite reports the flows that are left, and the one that is gone is a line in a diff nobody reads. Removing a line from the catalogue puts the flow back into the tree without putting it back into the promise, which is the state this gate exists to refuse'),
    'maestro-keyboard-commands': Control(kind='sabotage', proves='scripts/check-maestro-keyboard-commands.py', argv=('{python}', 'scripts/check-maestro-keyboard-commands.py'), path='Maestro/flows/smoke/01-launch-today.yaml', edit=Edit(kind='replace', old='- assertVisible:\n    id: tasks_fab', new='- hideKeyboard\n- assertVisible:\n    id: tasks_fab', count=1), why='a `hideKeyboard` that follows nothing but assertions is a BACK keypress in disguise: the editors are dialogs, so BACK dismisses them, and the flow then fails on an element the editor was holding. The probe adds exactly that command to a flow that types nothing, and the gate must say so instead of reading the flow as well-formed'),
    'maestro-screen-geometry': Control(kind='sabotage', proves='scripts/check-maestro-screen-geometry.py', argv=('{python}', 'scripts/check-maestro-screen-geometry.py'), path='Maestro/flows/smoke/01-launch-today.yaml', edit=Edit(kind='replace', old='- assertVisible:\n    id: tasks_fab', new='- swipe:\n    start: 105, 2000\n    end: 105, 1600\n- assertVisible:\n    id: tasks_fab', count=1), why='a flow that swipes to a point in pixels only works on the display it was measured on: on a 720x1600 phone the start point is off-screen, the swipe never happens, and the flow fails on a screen it never claimed to know about. The probe adds exactly that swipe to a flow that needs none and is not on the exceptions registry, and the gate must say so instead of reading the flow as portable'),
    'ui-wait-budgets': Control(kind='sabotage', proves='scripts/check-ui-wait-budgets.py', argv=('{python}', 'scripts/check-ui-wait-budgets.py'), path='config/docs/ui-wait-budgets.txt', edit=Edit(kind='replace', old='shell 30000', new='shell 12000', count=1), why='the two UI layers wait for the same screen from two runners that cannot share a constant, and the only thing that keeps them honest is one written budget. Moving the shell budget in the catalogue while the Kotlin constant and the Maestro flows keep the old number is exactly the drift this gate exists for: the instrumented suite waited 8 s while the Maestro helper had already learned that 8 s is not enough on a loaded CI runner, and `android-device-tests` was red on every run of `main` for days'),
    'dead-settings': Control(kind='sabotage', proves='scripts/check-dead-settings.py', argv=('{python}', 'scripts/check-dead-settings.py', '--quiet'), path='shared/src/commonMain/kotlin/com/singularity/todo/core/settings/SettingsBundle.kt', edit=Edit(kind='replace', old='        val workingDays: Set<DayOfWeek> = WorkingDays.DEFAULT,', new='        val workingDays: Set<DayOfWeek> = WorkingDays.DEFAULT,\n        val deadSettingProbe: Boolean = false,', count=1), why='a persisted setting rendered in Settings and read by no feature is a control that looks like it works; every baseline line is a claim that needs its own justification'),
    'sync-allowlist-regenerated': Control(kind='sabotage', proves='scripts/check-sync-allowlist-regenerated.py', argv=('{python}', 'scripts/check-sync-allowlist-regenerated.py'), path='shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncContract.kt', edit=Edit(kind='replace', old='"aiSuppressedTagIds",', new='"aiSuppressedTagIds",\n        "sabotage_probe_field",', count=-1), why='if the committed seed differs from the generator output, the gate catches it — and the seed is the one artifact that cannot be regenerated from within the check'),
    'doc-dead-refs': Control(kind='sabotage', proves='scripts/check-doc-dead-refs.py', argv=('{python}', 'scripts/check-doc-dead-refs.py', '--strict'), path='README.md', edit=Edit(kind='append', new='\nSee `docs/decisions/DefinitelyNotAFile.md` for details.\n'), why='a new dead reference must fail until baselined'),
    'issue-registry': Control(kind='sabotage', proves='scripts/check-issue-registry.py', argv=('{python}', 'scripts/check-issue-registry.py', '--quiet'), path='.specify/issues/close-rule-consolidation.yaml', edit=Edit(kind='replace', old='openspec_path: openspec/changes/close-rule-consolidation', new='openspec_path: openspec/changes/some-other-change', count=1), why='a registry entry that points at another change is a record of something else, and it looks like coverage: the next session reads the record, believes the change is known, and never learns what the change actually was. Four changes reached main with no entry at all, which is the same defect one step further from the surface'),
    'agents-pointers': Control(kind='sabotage', proves='scripts/check-agents-pointers.py', argv=('{python}', 'scripts/check-agents-pointers.py', '--quiet'), path='AGENTS.md', edit=Edit(kind='append', new='\nSee `docs/decisions/definitely-not-a-file.md` for the reasoning.\n'), why='a pointer that names a file that is not there sends the next session to look for it: the note that was written to remove the stop becomes the stop'),
    'review-verdicts': Control(kind='sabotage', proves='scripts/check-review-verdicts.py', argv=('{python}', 'scripts/check-review-verdicts.py', '--quiet'), path='config/docs/review-verdicts-baseline.txt', edit=Edit(kind='replace', old='a-closed-project-is-reachable\n', new='', count=1), why='a change that leaves the baseline stops carrying a verdict, and a review that leaves no trace is a promise with nothing behind it: the next session cannot tell whether the change was reviewed, what was found, or whether the findings were fixed'),
    # gate-registries: the gate that compares the two registries. Its input is
    # the shell registry, so the sabotage adds a gate line the Python registry
    # does not know — the exact shape of drift the gate exists to report. The
    # line goes inside main(), so it is a gate line rather than a stray command,
    # and the file is restored right after.
    'gate-registries': Control(kind='sabotage', proves='scripts/check-gate-registries.py', argv=('{python}', 'scripts/check-gate-registries.py', '--strict'), path='scripts/ci/static-gates.sh', edit=Edit(kind='replace', old='main() {\n', new='main() {\ngate blocking "phantom gate the registry does not know" python3 scripts/check-nothing.py\n', count=1), why='a gate line the Python registry does not know is drift: `sgate run-all` would run it and `sgate <name>` could not dispatch it, and the two registries are what decide which gates run'),
    # The generated catalogue's freshness. The probe edits the generated table
    # itself, because that file is the only thing the gate reads: a comparison
    # that could not tell a stale table from a current one would prove nothing.
    #
    # The edit changes a mode, which is the field the table exists to carry: a
    # catalogue that says a blocking gate is advisory is a catalogue that
    # understates what stops the build.
    'gate-registry-is-current': Control(
        kind='sabotage',
        proves='sgate',
        argv=('{python}', '-m', 'sgate', 'render-registry', '--check'),
        path='docs/scripts/gate-registry.md',
        edit=Edit(
            kind='replace',
            old='| version catalog has no literals | blocking |',
            new='| version catalog has no literals | advisory |',
            count=1,
        ),
        why=(
            "a catalogue of the gates that no longer matches the registry is a "
            "catalogue that answers questions about a gate set nobody runs — and a "
            "reader has no way to tell, because the file looks exactly as "
            "authoritative as the registry it drifted from"
        ),
    ),
    # The published cost of the loop. The probe edits the generated block's marker, and
    # the gate must notice that the page no longer says what the recorded baseline says.
    #
    # The marker, not a row of the table: the table has rows only once a baseline has
    # been recorded, and a control that cannot be applied is a control that proves
    # nothing.
    'published-gate-costs': Control(
        kind='sabotage',
        proves='sgate',
        argv=('{python}', '-m', 'sgate', 'gate-costs', '--check'),
        path='docs/testing/gate-costs.md',
        edit=Edit(
            kind='replace',
            old='<!-- BEGIN GENERATED: sgate gate-costs -->',
            new='<!-- BEGIN GENERATED: sgate gate-costs (sabotage probe) -->',
            count=1,
        ),
        why=(
            "a published cost that no longer matches the recorded baseline is a "
            "number nobody can trace: the page is what a reader believes, and the "
            "baseline is what the next comparison is made against — so a page "
            "that drifted from it makes the loop's cost unknowable while looking "
            "exactly as authoritative as the one that is right"
        ),
    ),
    # The JSON contract. The probe makes one gate's stdout stop being one JSON
    # document, and the gate must notice.
    #
    # The edit is in the shared runner rather than in one adapter, because the
    # defect it reproduces is not an adapter's: it is a second writer on the
    # contract channel. The runner is where every registry-generated gate gets
    # its document, so a stray line there is exactly what a consumer would meet.
    'gate-json-contract': Control(
        kind='sabotage',
        proves='scripts/check-gate-json-contract.py',
        argv=('{python}', 'scripts/check-gate-json-contract.py'),
        path='scripts/src/sgate/commands/_runner.py',
        edit=Edit(
            kind='replace',
            old='        print(payload.model_dump_json(indent=2))',
            new='        print("gate chatter that is not JSON")\n        print(payload.model_dump_json(indent=2))',
            count=1,
        ),
        why=(
            "a gate whose stdout carries prose in front of the document is a gate "
            "a consumer cannot read: the verdict is there, and the parser that "
            "wanted it gets a stack trace instead. Nothing caught it, because "
            "nothing read the document"
        ),
    ),
    # The two cluster gates. Each control breaks one member's input, and the
    # cluster must report it — the aggregate is a gate, so it needs a control of
    # its own, not only the members' six.
    #
    # A different file from the member controls where one was available: two
    # controls that sabotage the same path can mask each other if one of them
    # fails to restore.
    'ci-workflow-shape': Control(kind='sabotage', proves='scripts/check-ci-workflow-shape.py', argv=('{python}', 'scripts/check-ci-workflow-shape.py'), path='shared/build.gradle.kts', edit=Edit(kind='replace', old='    inputs.dir(layout.projectDirectory.dir("../Maestro"))\n        .withPropertyName("maestroFlows")\n        .withPathSensitivity(PathSensitivity.RELATIVE)\n', new='', count=1), why='the cluster reports every member that fails, so breaking one member must make the cluster fail — a cluster that swallowed a member failure would be one green row over three red checks'),
    'detekt-governance': Control(kind='sabotage', proves='scripts/check-detekt-governance.py', argv=('{python}', 'scripts/check-detekt-governance.py'), path='config/detekt/baseline-shared.xml', edit=Edit(kind='replace', old='</CurrentIssues>', new='    <ID>TotallyMadeUpRule:Probe.kt:1</ID>\n  </CurrentIssues>', count=1), why='the cluster reports every member that fails, so breaking one member must make the cluster fail — a cluster that swallowed a member failure would be one green row over six red checks'),
    # doc-links: the lychee gate. The sabotage appends a *Markdown link* to a
    # file that does not exist — the shape this gate checks and the other one
    # does not. `check-doc-dead-refs.py` matches a path inside backticks, so the
    # same text would pass it, and a control that only one of the two can fail
    # proves nothing about the other.
    #
    # A different file from the doc-dead-refs control on purpose: two controls
    # that sabotage the same path can mask each other if one of them fails to
    # restore.
    'doc-links': Control(kind='sabotage', proves='scripts/check-doc-links.sh', argv=('./scripts/check-doc-links.sh',), path='docs/agents/domain.md', edit=Edit(kind='append', new='\nSee [DefinitelyNotAFile](docs/decisions/DefinitelyNotAFile.md) for details.\n'), why='a documentation link that points at a file which is not there is a dead reference a reader follows and finds nothing'),
    'provenance': Control(kind='sabotage', proves='scripts/check-provenance.py', argv=('{python}', 'scripts/check-provenance.py'), path='config/legal/provenance-registry.tsv', edit=Edit(kind='drop_lines', old='QueryTokenizer'), why='a GPL upstream named in production code with no registry row is a licence claim nobody is tracking'),
    'pro-licence-boundary': Control(kind='sabotage', proves='scripts/check-pro-licence-boundary.py', argv=('{python}', 'scripts/check-pro-licence-boundary.py'), path='shared/build.gradle.kts', edit=Edit(kind='append', new='\ndependencies { implementation("ru.ok.tracer:tracer-crash-report:1.4.0") }\n'), why='proprietary code in an Apache-2.0 module makes the published licence a claim the project cannot honour'),
    'traceability-ratchet': Control(kind='sabotage', proves='scripts/check-traceability-ratchet.py', argv=('{python}', 'scripts/check-traceability-ratchet.py'), path='scripts/src/traceability/scenarios/tasks/checklist/TASK-CHECK-01.yaml', edit=Edit(kind='regex', pattern='^targets: \\[android\\]$', repl='targets: [android, desktop]', flags='MULTILINE'), why='a scenario claiming a target nothing verifies is the one hole count that grows without anyone reading the diff'),
    'room-schema-integrity': Control(kind='sabotage', proves='scripts/check-room-schema-integrity.py', argv=('{python}', 'scripts/check-room-schema-integrity.py'), path='shared/src/commonMain/kotlin/com/singularity/todo/core/database/AppDatabase.kt', edit=Edit(kind='regex', pattern='const val SCHEMA_VERSION = \\d+', repl='const val SCHEMA_VERSION = 1'), why='an entity change without a version bump passes every test and crashes every existing install on upgrade'),
    'dependency-usage': Control(kind='sabotage', proves='scripts/check-dependency-usage.py', argv=('{python}', 'scripts/check-dependency-usage.py', '--quiet'), path='scripts/dependency-usage-allowlist.txt', edit=Edit(kind='drop_lines', old='kermit-koin'), why="a dependency declared, resolved and imported by nothing costs a full release's build time and ships undetected — material-kolor sat on the classpath for one"),
    'supabase-schema-integrity': Control(kind='sabotage', proves='scripts/check-supabase-schema-integrity.py', argv=('{python}', 'scripts/check-supabase-schema-integrity.py'), path='supabase/migrations/2026-10-07-sync_schema.sql', edit=Edit(kind='append', new='\ncreate or replace function public.sync_control_probe(p_type text)\nreturns text as $$ select p_type $$ language sql;\n'), why='a function added without a fingerprint makes the header understate the schema, and the header is the part a reader trusts; the live half of #221 needs credentials and stays manual, so this is the half that can be a gate'),
    'rebase-compiles': Control(kind='sabotage', proves='scripts/check-rebase-compiles.py', argv=('{python}', 'scripts/check-rebase-compiles.py'), path='shared/src/commonMain/kotlin/com/singularity/todo/core/di/CoreDiModule.kt', edit=Edit(kind='replace', old='    single<DeviceIdProvider> { get<DataStoreSessionStore>() }', new='    // single<DeviceIdProvider> { get<DataStoreSessionStore>() }', count=-1), why="an import removed from a DI module silently breaks every consumer; the compile gate detects the resulting 'unresolved reference' before jvmTest does"),
    'slow-tests-ran': Control(kind='fixture', proves='scripts/check-slow-tests-ran.py', argv=('{python}', 'scripts/check-slow-tests-ran.py', '--root', '{tmp}'), fixture='setup_slow_tests_ran', why="a test the local loop never runs is a test whose breakage nobody local ever sees: McpServerEndToEndTest was tagged slow, sat broken on main since the read surface changed the shape of its answer, and was found by CI rather than by anyone holding the tree", needs_clean_run=False, tracked_by='.github/workflows/ci.yml#Slow tests ran'),
    'flaky-tests': Control(kind='fixture', proves='scripts/check-flaky-tests.py', argv=('{python}', 'scripts/check-flaky-tests.py', '--current', '{tmp}/cur', '--previous', '{tmp}/prev'), fixture='setup_flaky_tests', why='a test that passed yesterday and fails today must be reported until it is acknowledged', needs_clean_run=False),
    'measure-flake-rate': Control(kind='fixture', proves='scripts/measure-flake-rate.py', argv=('{python}', 'scripts/measure-flake-rate.py', '--current', '{tmp}/run-01', '{tmp}/run-02', '--runs', '2', '--baseline', '{tmp}/empty-baseline.txt', '--max-flaky-rate', '0.0'), fixture='setup_measure_flake_rate', why="a status flip between two runs must be reported as an unacknowledged flake until it is baselined — the same property check-flaky-tests.py is probed for, but here it is the N-run aggregator's own gate logic (rate ceiling + baseline acknowledgement) that must not be swallows", needs_clean_run=False),
    'maestro-results': Control(kind='fixture', proves='scripts/ci/check-maestro-results.py', argv=('{python}', 'scripts/ci/check-maestro-results.py', '--scope', 'full', '--results-dir', '{tmp}/results', '--catalogue', '{tmp}/catalogue.txt', '--floor', '{tmp}/floor.txt', '--debt', '{tmp}/debt.yaml', '--environment', '{tmp}/results/environment.json'), fixture='setup_maestro_results', why="a Maestro run that failed a flow the floor does not allow, and a flow the run produced no report for — the two ways a run quietly stops being the run the repository promised. The input is a run on an emulator, so it cannot exist in a clean checkout: the control synthesises it", needs_clean_run=False),
    'gate-tooling-origin': Control(kind='fixture', proves='scripts/check-gate-tooling-origin.py', argv=('{python}', '{tmp}/foreign/check-gate-tooling-origin.py'), fixture='setup_gate_tooling_origin', why="the gate CLI is installed editable, so the venv on PATH decides which checkout's scripts/src/sgate the gate CLI is — and a foreign CLI reads a foreign registry and reports about a foreign tree. The run looks green and says nothing about the tree in front of it. The input is the interpreter's import path, not a committed file, so the control synthesises it: a copy of the gate standing next to a package named sgate, which the copy must answer FOREIGN about", needs_clean_run=False),
    'test-runs': Control(kind='sabotage', proves='scripts/check-test-runs.py', argv=('{python}', 'scripts/check-test-runs.py', '--require', 'shared:jvmTest,desktopApp:test', '--max-age', '86400'), path='config/docs/test-runs-baseline.txt', edit=Edit(kind='regex', pattern='^(shared:jvmTest )\\d+( \\d+)$', repl='\\g<1>99999\\g<2>', flags='MULTILINE'), why='a test source set that ran fewer tests than its recorded floor means coverage was lost', needs_clean_run=False, until='2026-12-31', tracked_by='.github/workflows/ci.yml#Executed test counts'),
    'instrumented-test-floor': Control(kind='sabotage', proves='scripts/check-instrumented-test-floor.py', argv=('{python}', 'scripts/check-instrumented-test-floor.py', '--report-dir', 'scripts/tests/fixtures/instrumented-report'), path='config/docs/instrumented-test-floor.txt', edit=Edit(kind='regex', pattern='^tests \\d+$', repl='tests 99999', flags='MULTILINE'), why='the instrumented tier runs on a schedule, on pushes to main and on demand — and a suite that runs on a schedule is a suite whose size nobody watches. A deleted test class leaves the run green, because the report lists the tests that are left. The floor is the record of what the tier used to be, and the control moves it to a number no run can reach'),
    'adr-references': Control(kind='sabotage', proves='scripts/check-adr-references.py', argv=('{python}', 'scripts/check-adr-references.py'), path='README.md', edit=Edit(kind='append', new='\nSee `docs/decisions/2026-10-05-nonexistent-adr.md` for details.\n'), why='a prose reference to a dated-ADR slug carries no path, so a moved or deleted ADR leaves a dangling claim nothing else sees'),
    'openspec-stale': Control(kind='sabotage', proves='scripts/check-openspec-stale.py', argv=('{python}', 'scripts/check-openspec-stale.py'), path='openspec/changes/detekt-rule-has-positive-control', edit=Edit(kind='age_dir', days=30), why='an active change nobody has returned to in three weeks is a decision deferred past the point of usefulness'),
    'rule-intent': Control(kind='sabotage', proves='scripts/check-rule-intent.py', argv=('{python}', 'scripts/check-rule-intent.py'), path='config/detekt/baseline-shared.xml', edit=Edit(kind='replace', old='</CurrentIssues>', new='    <ID>TotallyMadeUpRule:Probe.kt:1</ID>\n  </CurrentIssues>', count=1), why='a rule producing findings that nobody declared means it is running on detekt\'s defaults, unchosen. The edit is the one the twin in check-gate-wiring.py applies: the two copies of a control are the same control until one of them is edited, and the copy that rotted here named a detekt.yml pattern that has not been in the file since the rules were reorganised — so the registry described a sabotage that would have raised "matched nothing" if it had ever been the copy that ran'),
    'suppression-intent': Control(kind='sabotage', proves='scripts/check-suppression-intent.py', argv=('{python}', 'scripts/check-suppression-intent.py'), path='shared/src/jvmMain/kotlin/com/singularity/todo/feature/pomodoro/JvmPomodoroTimer.kt', edit=Edit(kind='strip_suppression_reason'), why='a file-level suppression with no recorded reason switches a rule off for every future call in that file'),
    'workflow-exit-status': Control(
        kind='sabotage',
        proves='scripts/check-workflow-exit-status.py',
        argv=('{python}', 'scripts/check-workflow-exit-status.py'),
        path='.github/workflows/ci.yml',
        edit=Edit(
            kind='replace',
            old='          set -o pipefail\n          ./gradlew --continue \\\n',
            new='          ./gradlew --continue \\\n',
            count=1,
        ),
        why=(
            "a step whose status is the pipeline's last command cannot fail on its producer; "
            "the JVM job stayed green through 109 detekt findings for that reason "
            "(measured on run 38902346697, green, containing ':shared:detekt FAILED')"
        ),
        tracked_by='.github/workflows/ci.yml#Gradle (tests + coverage + detekt)',
    ),
    'build-output-readers': Control(kind='sabotage', proves='scripts/check-build-output-readers.py', argv=('{python}', 'scripts/check-build-output-readers.py'), path='.github/workflows/ci.yml', edit=Edit(kind='replace', old='      - name: Coverage floors\n        run: ./gradlew :koverVerify -Ptest.population="$TEST_POPULATION"\n', new='      - name: Coverage floors\n        run: ./gradlew :koverVerify -Ptest.population="$TEST_POPULATION"\n      - name: Late flake pass\n        run: |\n          python3 scripts/check-flaky-tests.py \\\n            --current shared/build/test-results/jvmTest \\\n            --previous /tmp/prev\n', count=1), why="a step that reads build output and runs after a second Gradle invocation may be reading a directory that invocation re-ran: the traceability step failed this way on run 38933517902, and the flake step would have done it silently. The probe reproduces exactly that ordering — a build/test-results reader placed after the job's second test-capable invocation — and nothing else. The previous probe appended a bare `./gradlew :koverVerify` to the end of the file, which landed in `nightly-alert`, a job with one test-capable invocation and no readers: the gate ignores such a job by design, so the probe passed a sabotaged input and certified nothing. Both halves of that probe need the parser to read a backslash-continued command as one command — the producing invocation's tasks and the reader's path each sit on continuation lines — so the probe also pins the parser fix.", tracked_by='.github/workflows/ci.yml#Normalise desktop results into the scenario matrix'),
    'gradle-test-population': Control(kind='sabotage', proves='scripts/check-gradle-test-population.py', argv=('{python}', 'scripts/check-gradle-test-population.py'), path='.github/workflows/ci.yml', edit=Edit(kind='replace', old='run: ./gradlew :koverVerify -Ptest.population="$TEST_POPULATION"', new='run: ./gradlew :koverVerify', count=1), why="a Gradle invocation that follows the test run without -Ptest.tags measures Gradle's default, excludeTags('slow'): the slow scenario classes are skipped rather than executed, the suite stays green, and every gate that reads build/test-results/ afterwards is measuring a population nobody ran (run 38933517902)", tracked_by='.github/workflows/ci.yml#Normalise desktop results into the scenario matrix'),
    'test-task-inputs': Control(kind='sabotage', proves='scripts/check-test-task-inputs.py', argv=('{python}', 'scripts/check-test-task-inputs.py'), path='shared/build.gradle.kts', edit=Edit(kind='replace', old='    inputs.dir(layout.projectDirectory.dir("../Maestro"))\n        .withPropertyName("maestroFlows")\n        .withPathSensitivity(PathSensitivity.RELATIVE)\n', new='', count=1), why='a test task reading a tree outside its module reports a stale verdict when that tree is not an input'),
    # The app coverage floor, declared where Kover reads it.
    #
    # The floors were declared in the root build script for months, in a
    # `plugins.withId("org.jetbrains.kotlinx.kover")` block that never executed
    # — probed with a `println`, the line never appeared. The root project has
    # no Kover extension of its own to configure, so the floors it declared
    # were evaluated by no task: the CI step that reads them was green no
    # matter what the coverage was, and the ADR that recorded them said, in so
    # many words, that "nothing Python-side could have proved it" — which was
    # true only because of WHERE the declaration was.
    #
    # The probe puts the declaration back where it was: a Kover verify block
    # with an app rule in the root build script. The gate must say so.
    'coverage-floor-declaration': Control(kind='sabotage', proves='scripts/check-coverage-floor-declaration.py', argv=('{python}', 'scripts/check-coverage-floor-declaration.py', '--quiet'), path='build.gradle.kts', edit=Edit(kind='append', new='\nkover {\n    reports {\n        verify {\n            rule("app instruction coverage") {\n                bound { minValue = 99 }\n            }\n        }\n    }\n}\n'), why='the app coverage floor is the aggregate one, and only the settings-level block can measure it: a verify block in the root build script is read by no task at all, and one in a module measures that module under a name the reader will take for the aggregate. That is how a declared floor went unenforced for months while the step that reads it stayed green'),
    # The CI timeout covers the instrumented run, and the instrumented run is
    # measured.
    #
    # The coverage job's timeout was a literal — 75 minutes — and the cost of
    # the instrumented run was priced nowhere, so the number nobody could check
    # was the number that decided whether a healthy run was killed. The gate
    # reads the measured cost and the timeout and says so when the timeout
    # cannot cover the measurement.
    #
    # The probe shrinks the timeout to 30 minutes: the measured fast run takes
    # 37 min 25 s, and the runner is slower than the measurement host. The gate
    # must fail and name both numbers.
    'published-measurements': Control(kind='sabotage', proves='scripts/check-published-measurements.py', argv=('{python}', 'scripts/check-published-measurements.py'), path='docs/decisions/2026-10-22-the-app-coverage-floor-lives-in-the-settings.md', edit=Edit(kind='replace', old='over 4735 classes and 579,002', new='over 4736 classes and 579,002', count=1), why='a published number is a claim about a measurement, and the claim outlives the measurement it was read from: "2.82M instructions" survived a real measurement because the percentages are a ratio and a ratio is the same at every level of the report. The probe moves the class count of the fast measurement by one, and the gate must name the document, the line and both numbers instead of reading the document as a source of truth'),
    'coverage-timeout': Control(kind='sabotage', proves='scripts/check-coverage-timeout.py', argv=('{python}', 'scripts/check-coverage-timeout.py', '--quiet'), path='.github/workflows/ci.yml', edit=Edit(kind='replace', old='    timeout-minutes: 75', new='    timeout-minutes: 30', count=1), why='a timeout that fires on a healthy instrumented run reports a failure that says nothing about the code: the coverage job is killed at the moment it was about to report, and the report is the thing the job exists for'),
    # The coverage report measures every module that is in it.
    #
    # The list of test tasks that feed the aggregated report is written by
    # hand, in the root build script. A module added to the build is not added
    # to that list, so its tests never run in the coverage job — and the
    # report says nothing about it while the reader assumes it does. The
    # worst case is not "a number is missing": it is a module whose classes
    # ARE in the denominator and whose tests never ran, which the report
    # scores as 0% covered — a number that reads like a coverage problem and
    # is a measurement one.
    #
    # The probe removes one entry from the list. `:adr-core` holds the app's
    # ADR rules, so its classes are in the denominator: with its test task
    # gone from the list, the gate must say so.
    'coverage-task-list': Control(kind='sabotage', proves='scripts/check-coverage-task-list.py', argv=('{python}', 'scripts/check-coverage-task-list.py', '--quiet'), path='build.gradle.kts', edit=Edit(kind='replace', old='        ":adr-core:jvmTest",', new='', count=1), why='a module whose classes are in the coverage denominator but whose tests never run is scored as 0% covered: the number reads like a coverage problem and is a measurement one, and nothing else in the build notices it'),
    'detekt-rule-coverage-floor': Control(kind='sabotage', proves='gw', argv=('./gw', '-q', ':detekt-rules:koverVerify'), path='detekt-rules/build.gradle.kts', edit=Edit(kind='regex', pattern='^(val DETEKT_RULE_BRANCH_FLOOR = )\\d+$', repl='\\g<1>100', flags='MULTILINE'), why='branch coverage below the declared floor — the one thing the floor exists to catch, and the thing an unbound or zero bound lets through', needs_clean_run=False, tracked_by='.github/workflows/ci.yml#Kover verify (detekt-rules)'),
    # The waiver-premise control. It renames the CI step the one waived
    # control's `tracked_by` points at, so the anchor names a step that no
    # longer exists — the exact shape of the failure the gate exists for: the
    # waiver keeps waiving while the compensating pass it was granted for has
    # been renamed away.
    #
    # The edit is on the workflow rather than on the registry because that is
    # the thing that rots: the registry's promise is a pointer, and a pointer
    # whose target has been renamed is exactly what nothing used to notice.
    'waiver-references': Control(kind='sabotage', proves='scripts/check-waiver-references.py', argv=('{python}', 'scripts/check-waiver-references.py'), path='.github/workflows/ci.yml', edit=Edit(kind='replace', old='      - name: Kover verify (detekt-rules)\n        run: ./gradlew :detekt-rules:koverVerify -Ptest.population="$TEST_POPULATION"\n', new='      - name: Kover verify (detekt-rules) [renamed]\n        run: ./gradlew :detekt-rules:koverVerify -Ptest.population="$TEST_POPULATION"\n', count=1), why='a waiver whose reference no longer resolves waives nothing: the compensating pass it was granted for can be gone and the waiver keeps waiving, which is how a permanently-red gate becomes noise nobody reads'),
    # script-conventions: the rule that gate scripts use Click. The probe adds
    # the import the rule looks for, to a script no other control touches.
    #
    # The rule was a text search for "import argparse", and the checker that
    # held the rule contained the string — so the gate reported itself as a
    # violation on every run and could only ever fail that way. The rule reads
    # the AST now, and this control proves the new rule can fail.
    #
    # This control was declared **twice** in this dictionary, with two different
    # probes. A dict literal drops the earlier one without a word, so the first
    # declaration was dead code — and the probe that actually ran was the one
    # nobody was reading. `TestGateControlsTest` now says a key may not be
    # written twice.
    'script-conventions': Control(kind='sabotage', proves='scripts/check-script-conventions.py', argv=('{python}', 'scripts/check-script-conventions.py'), path='scripts/check-gate-registries.py', edit=Edit(kind='append', new='\nimport argparse\n'), why='a gate script that imports argparse breaks the CLI contract every gate script is written against. The detector that used to find it was a substring search, so it reported the file that *documented* the rule instead of the file that broke it, and the rule was never enforced: the gate was red on a clean tree for a reason nobody could act on, and the exemption that said so was the reason it stayed red.'),
    'unwired-surfaces': Control(
        kind='sabotage',
        proves='scripts/find-unwired-surfaces.py',
        argv=('{python}', 'scripts/find-unwired-surfaces.py', '--quiet'),
        path='shared/src/commonMain/kotlin/com/singularity/todo/core/di/CoreDiModule.kt',
        edit=Edit(
            kind='append',
            new='\n// SABOTAGE PROBE — a binding nothing injects.\nsingle<SabotageProbePort> { SabotageProbePort() }\n',
        ),
        why=(
            "a binding nothing injects is a no-op at runtime, and the gate is the only "
            "thing that notices: the binding compiles, the graph verifies, and the "
            "feature is never reached. The probe adds exactly that binding to a real "
            "DI module, so the report has to name it"
        ),
    ),
    # The fixture table handing a definition the wrong type is the drift this
    # gate exists for, and the probe is the drift itself: the row that says
    # `TaskId` where the definition says `TasksRoute.Detail` is exactly what
    # was committed on 2026-10-22, and the graph could not be created.
    'di-fixture-sync': Control(
        kind='sabotage',
        proves='scripts/check-di-fixture-sync.py',
        argv=('{python}', 'scripts/check-di-fixture-sync.py', '--check'),
        path='desktopApp/src/jvmTest/kotlin/com/singularity/todo/DomainGraphResolutionTest.kt',
        edit=Edit(
            kind='replace',
            old='TaskDetailCoordinator::class to listOf(TasksRoute.Detail(TaskId("task-1")))',
            new='TaskDetailCoordinator::class to listOf(TaskId("task-1"))',
            count=1,
        ),
        why=(
            "a ViewModel that takes a route and a fixture that hands it a task id is a "
            "graph that cannot be built, and the only test that says so lives in the "
            "full desktop suite — an hour away on a loaded host. The probe puts the "
            "wrong type back into the table, so the gate has to name the mismatch"
        ),
    ),
    # A navigation row that names a type the matcher does not name sends every
    # agent that follows the pointer the wrong way. The probe puts back the row
    # that was true before 2026-10-22, so the gate has to see the difference.
    'navigation-doc-sync': Control(
        kind='sabotage',
        proves='scripts/check-navigation-doc-sync.py',
        argv=('{python}', 'scripts/check-navigation-doc-sync.py', '--check'),
        path='docs/navigation.md',
        edit=Edit(
            kind='replace',
            old='`AgendaGraph(SavedAgendaResults(viewId))`',
            new='`AgendaGraph(SavedAgendaEdit(viewId))`',
            count=1,
        ),
        why=(
            "the doc is a pointer: it is what an agent reads to learn what opens what, "
            "and it is pointed at from the nav3 skill. The probe restores the row that "
            "went false on 2026-10-22 — the deep link naming an editor the matcher "
            "never opens — so the gate has to catch a claim the code does not support"
        ),
    ),
        # a link format that lives in two places is a link format that drifts: the producer writes the old format and the matcher reads the new one
        'link-grammar': Control(
            kind='sabotage',
            proves='scripts/check-link-grammar.py',
            argv=('{python}', 'scripts/check-link-grammar.py', '--check',),
            path='shared/src/commonMain/kotlin/com/singularity/todo/feature/calendar_sync/domain/model/CalendarSyncEvent.kt',
            edit=Edit(
                kind='replace',
                old='package com.singularity.todo.feature.calendar_sync.domain.model',
                new='package com.singularity.todo.feature.calendar_sync.domain.model\n\nprivate val handWrittenLink = "singularity://task/abc"',
                count=1,
            ),
            why=(
                'a link format that lives in two places is a link format that drifts: the producer writes the old format and the matcher reads the new one'
            ),
        ),
        # a catalogue that lost a tool is a catalogue that sends a reader to a tool that is not there
        'tools-catalog': Control(
            kind='sabotage',
            proves='scripts/check-tools-catalog.py',
            argv=('{python}', 'scripts/check-tools-catalog.py', '--check',),
            # The catalogue lists the scripts the registry does not name, so the
            # sabotage changes one of those: a script that is a gate is
            # described in the registry document, and changing its summary would
            # not make the catalogue stale.
            path='scripts/check-openspec-stale.py',
            edit=Edit(
                kind='replace',
                old='Warn when an active OpenSpec change is older than 14 days.',
                new='Warn when an active OpenSpec change is older than a fortnight.',
                count=1,
            ),
            why=(
                'a catalogue that lost a tool is a catalogue that sends a reader to a tool that is not there'
            ),
        ),
}


# Gates invoked somewhere but deliberately without a positive control here.
# Each names what does the proving instead: "no control" is only acceptable when
# something else is, and the reader has to be told what.
GATE_EXEMPTIONS: dict[str, Control] = {
    'scripts/check-gate-tooling-origin.py': Control(
        kind='exempt',
        proves='scripts/check-gate-tooling-origin.py',
        tracked_by='scripts/tests/test_check_gate_tooling_origin.py',
        reason=(
            'its subject is the installation, not the tree: the answer is where the '
            'importable `sgate` package lives, and no committed file decides that — so a '
            'sabotage of the tree cannot make it fail. The control is the test that '
            'synthesises the state the gate exists to report: a foreign package on the '
            'path, which the gate must catch as FOREIGN.'
        ),
    ),
    'scripts/check-selectable-text-coverage.py': Control(
        kind='exempt',
        proves='scripts/check-selectable-text-coverage.py',
        tracked_by='shared/src/jvmTest/kotlin/com/singularity/todo/arch/SelectableTextConventionTest.kt',
        reason=(
            'an advisory gate has no failing state to prove: it reports the prose that no '
            'registry row covers and passes, so a sabotage control could only assert that '
            'it still passes. The proving is the blocking reader of the same registry — '
            'SelectableTextConventionTest — which fails when a file uses a text widget and '
            'is not in the inventory table, and when a row does not match the code.'
        ),
    ),
    'scripts/check-gate-wiring.py': Control(kind='exempt', proves='scripts/check-gate-wiring.py', tracked_by='scripts/tests/test_check_gate_wiring.py', reason='this file. Its controls are its own Parts, and its self-tests (scripts/tests/test_check_gate_wiring.py) pin each part; a registry that had to sabotage itself to prove itself would be circular.'),
    'scripts/check-provenance.py': Control(kind='exempt', proves='scripts/check-provenance.py', tracked_by='scripts/tests/test_check_provenance.py', reason='provenance audit for Apache-2.0 / FSL-1.1 publication; runs locally before publication commits, not as a CI gate; the sabotage control is the provenance-registry.tsv itself'),
    'scripts/check-pro-licence-boundary.py': Control(kind='exempt', proves='scripts/check-pro-licence-boundary.py', tracked_by='scripts/tests/test_check_pro_licence_boundary.py', reason='Apache-2.0 / pro licence boundary check; runs locally before publication commits, not as a CI gate; the sabotage control is the direction check itself'),
    'scripts/check-rebase-compiles.py': Control(kind='exempt', proves='scripts/check-rebase-compiles.py', tracked_by='.github/workflows/ci.yml#Post-rebase DI compile', reason='GATE_PARITY: needs a Gradle compile run with rebased DI files, so lives in the CI tests job after the rebase step and is not in the static-gates.sh registry; sabotage control is the rebase step'),
    'scripts/build-version-catalog-gate.py': Control(kind='exempt', proves='scripts/build-version-catalog-gate.py', tracked_by='scripts/tests/test_sgate_gates.py', reason='auto-generator: the gate proves version catalog is clean by checking for undeclared literals; the sabotage is removing the generated catalog file and asserting the gate fails — but the generator is the gate, so `test_version_catalog_gate_self_repairs` is the real control'),
    'scripts/tests': Control(kind='exempt', proves='scripts/tests', tracked_by='scripts/tests/test_sgate_gates.py', reason='meta-gate: `python3 -m unittest discover` is the gate; corrupting a test file would be the sabotage, but the unittest runner is the proving mechanism and its own test suite (scripts/tests/test_sgate_gates.py) is what makes it trustworthy'),
    'scripts/check-gate-liveness.py': Control(
        kind='exempt',
        proves='scripts/tests/test_check_gate_liveness.py',
        tracked_by='scripts/tests/test_check_gate_liveness.py',
        reason=(
            'the gate *executes* other gates, so a sabotage control has to corrupt a script a '
            'registered gate actually runs — and three successive targets did not. '
            'check-kiwi-inventory-ratchet.py went with the retired Kiwi stand; '
            'check-backlog-issue-refs.py went with the removed backlog-issue-refs gate; '
            'check-skill-drift.py is not the script `sgate the_toolbox_surface_is_declared_once` '
            'executes, so the injected raise never ran. All three passed a deliberately sabotaged '
            'input, i.e. they certified nothing. Measured 2026-10-19: corrupting '
            'check-backlog-issue-refs.py left the liveness gate green '
            '("30 gate(s) probed, 0 died"), so the control was a no-op that the harness '
            'reported as a passing one. The registry control was therefore removed rather '
            'than re-pointed: a control that corrupts a file nothing probes cannot be made '
            'to fail by choosing a different file, because the property that made it '
            'inert was the property of the harness, not of the target. The proving is '
            'scripts/tests/test_check_gate_liveness.py (registry parsing, the heavyweight filter, '
            'the self-match skip) plus `check.sh`, which runs this gate for real on every '
            'invocation. A working control is still available if wanted: synthesise a two-gate '
            'registry fixture and assert the probe reports the dead entry.'
        ),
    ),
    'scripts/gen-detekt-rules-config.py': Control(kind='exempt', proves='scripts/gen-detekt-rules-config.py', tracked_by='config/detekt/detekt-rules-module.yml', reason='auto-generator: sabotage is deleting the generated config and asserting the gate fails; equivalent to `rm config/detekt/detekt-rules.yml && gate detekt-rules-config` — the output-diff is the control'),
    'scripts/gen-detekt-rule-table.py': Control(kind='exempt', proves='scripts/gen-detekt-rule-table.py', tracked_by='.agents/skills/singularity-todo-detekt-rules-authoring/RULES.md', reason='auto-generator: sabotage is deleting the generated table and asserting the gate fails; equivalent to `rm docs/detekt/rule_table.md && gate detekt-rule-inventory` — the output-diff is the control'),
    'scripts/check-detekt-registrations.sh': Control(kind='exempt', proves='scripts/check-detekt-registrations.sh', tracked_by='config/detekt/detekt.yml', reason='shell-gate: the sabotage is removing a rule from the registrations file and asserting the gate fails; equivalent to `rm config/detekt/rule_registrations.toml && gate detekt-rule-registry` — but Part J cross-check itself is what catches it when the control goes missing'),
    'scripts/ci/validate-maestro-selectors.py': Control(kind='exempt', proves='scripts/ci/validate-maestro-selectors.py', tracked_by='shared/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt', reason='CI-only gate: runs in the Maestro flow job, not locally; sabotage would be corrupting the selector files and asserting the gate fails; Part J cross-check itself is the backstop when the control goes missing'),
    'scripts/regen-skills-catalog.sh': Control(kind='exempt', proves='scripts/regen-skills-catalog.sh', tracked_by='docs/SKILLS-CATALOG.md', reason='shell-gate auto-generator: sabotage is removing the generated catalog and asserting the gate fails; equivalent to `rm docs/SKILLS-CATALOG.md && gate skills-catalog` — the output-diff is the control'),
    'traceability': Control(
        kind='exempt',
        proves='scripts/tests/test_traceability.py',
        tracked_by='scripts/tests/test_traceability.py',
        reason=(
            'corpus-scanning gates ("scenario specs valid", "coverage matrix current"), invoked '
            'as `python3 -m traceability` rather than by script path — so the registry records '
            'the module name, not a file, and no single committed file is their input. The '
            'proving is scripts/tests/test_traceability.py, which asserts the rejections '
            'directly: `test_p0_is_rejected`, `test_declared_area_must_match_the_path`, '
            '`test_matching_declared_area_is_accepted`. Adding a sabotage control here would '
            'mean corrupting a real scenario spec and re-running a full corpus scan on every '
            'gate-wiring check, to re-prove what those unit tests already pin. If a control is '
            'wanted later, the cheap honest form is a FIXTURE: synthesise a two-spec corpus and '
            'assert `traceability validate` rejects it.'
        ),
    ),
}

# Populate `GateSpec.control` from the declarations above.
#
# The join key is a control's `proves` — a repo-relative script path — and not the
# gate's display name. Display names are prose ("ADR status policy") and would
# need a lookup table, while one script can back two gates that carry two
# different controls (no dead doc refs / no dead skill symbols share
# scripts/check-doc-dead-refs.py). The script path is the only key that survives
# both facts, and it is what the cross-check has always keyed on.
_CONTROL_BY_SCRIPT: dict[str, Control] = {}
for _slug, _control in GATE_CONTROLS.items():
    _CONTROL_BY_SCRIPT.setdefault(_control.proves, _control)

GATES: tuple[GateSpec, ...] = tuple(
    replace(g, control=_CONTROL_BY_SCRIPT.get(str(g.script))) for g in _REGISTERED
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
            # STATIC_GATES_SOURCED=1 loads the file's function definitions without
            # running its main() — which is the whole gate suite. Without it,
            # `bash -c ". static-gates.sh && some_func"` would run every gate and
            # then the function, so the gate would cost the suite and report the
            # suite's verdict.
            cmd = [
                "bash",
                "-c",
                f"STATIC_GATES_SOURCED=1 . {static_sh} && {spec.bash_func}",
            ]

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
