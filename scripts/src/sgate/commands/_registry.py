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
from sgate.log import logger
from sgate.output import gate_fail

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
        scope="ci",
        why=(
            "the Android host source set is built only in its own CI job; see "
            "openspec/changes/ci-checks-parallel-split for why it was split out"
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
        name="gradle invocations keep one test population",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-gradle-test-population.py"),
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
        name="settings read by a feature",
        mode=GateMode.BLOCKING,
        cmd_type=CmdType.PYTHON3,
        script=Path("scripts/check-dead-settings.py"),
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
    # ── Docs / agent-facing text ───────────────────────────────────────────────────
    # ── ADR governance ─────────────────────────────────────────────────────────────
    # ── spec-kit ──────────────────────────────────────────────────────────────────
    # ── AppTracer ─────────────────────────────────────────────────────────────────
    # ── Backlog ───────────────────────────────────────────────────────────────────
)


# ── Positive controls ────────────────────────────────────────────────────────

# ── Positive controls ──────────────────────────────────────────
#
# Keyed by the control's own slug, the name the harness has always used
# for it. `proves` joins each control to the registry gate it covers —
# by script path, because display names are prose and one script can
# back two gates with two different controls.
GATE_CONTROLS: dict[str, Control] = {
    'release-tags': Control(kind='sabotage', proves='scripts/check-release-tags.py', argv=('{python}', 'scripts/check-release-tags.py'), path='config/docs/releases-snapshot.json', edit=Edit(kind='replace', old='    "v0.2.1-rc.1"\n  ],', new='    "v0.2.1-rc.1",\n    "v9.9.9-probe"\n  ],', count=1), why='a tag that produced no release is the failure this gate exists for: `meta` proved the tag was on main with a green CI run, `publish` still did not run, and nothing said so. The probe appends exactly such a tag — recorded as neither released nor excused.'),
    'detekt-registrations': Control(kind='sabotage', proves='scripts/check-detekt-registrations.sh', argv=('./scripts/check-detekt-registrations.sh',), path='config/detekt/detekt.yml', edit=Edit(kind='replace', old='no-runblocking:\n  NoRunBlocking:\n    active: true\n', new='', count=-1), why='a RuleSetId with no detekt.yml block never loads, so the rule never runs'),
    'baseline-ratchet': Control(kind='sabotage', proves='scripts/check-baseline-ratchet.py', argv=('{python}', 'scripts/check-baseline-ratchet.py'), path='config/detekt/baseline-shared.xml', edit=Edit(kind='replace', old='</CurrentIssues>', new='    <ID>Sabotage:Probe.kt:Probe</ID>\n  </CurrentIssues>', count=-1), why='a baseline that can grow silently stops being a ratchet and becomes a sink'),
    'gate-liveness': Control(kind='sabotage', proves='scripts/check-gate-liveness.py', argv=('{python}', 'scripts/check-gate-liveness.py'), path='scripts/check-backlog-issue-refs.py', edit=Edit(kind='replace', old='import sys', new='import sys\nraise AttributeError("\'NoneType\' object has no attribute \'loader\'")', count=1), why='a gate that raises instead of reporting has no opinion at all, and an advisory gate whose output nobody reads will stay that way indefinitely'),
    'hooks-path': Control(kind='sabotage', proves='scripts/check-hooks-path.py', argv=('{python}', 'scripts/check-hooks-path.py', '--quiet'), path='.githooks/pre-push', edit=Edit(kind='hooks_path'), why='hooks travel with the repository; local git config does not. A configured hooks path with nothing tracked under it is a gate that reports success because nothing ever asked it a question'),
    'skill-drift': Control(kind='sabotage', proves='scripts/check-skill-drift.py', argv=('{python}', 'scripts/check-skill-drift.py'), path='.agents/skills/singularity-todo-decisions-workflow/SKILL.md', edit=Edit(kind='replace', old='`singularity-adr validate`', new='`singularity-adr verifai`', count=1), why='a skill that lists commands the tool does not have sends an agent to run something that does not exist'),
    'dead-settings': Control(kind='sabotage', proves='scripts/check-dead-settings.py', argv=('{python}', 'scripts/check-dead-settings.py', '--quiet'), path='scripts/check-dead-settings-baseline.txt', edit=Edit(kind='replace', old='Notifications.reminderDefault', new='Notifications.no_such_field', count=-1), why='a persisted setting rendered in Settings and read by no feature is a control that looks like it works; every baseline line is a claim that needs its own justification'),
    'doc-sizes': Control(kind='sabotage', proves='scripts/check-doc-sizes.py', argv=('{python}', 'scripts/check-doc-sizes.py'), path='AGENTS.md', edit=Edit(kind='append', new='\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n\n'), why='the budget must be able to fail; `--warn-only || status=1` could not'),
    'sync-allowlist-regenerated': Control(kind='sabotage', proves='scripts/check-sync-allowlist-regenerated.py', argv=('{python}', 'scripts/check-sync-allowlist-regenerated.py'), path='shared/src/commonMain/kotlin/com/singularity/todo/core/sync/SyncContract.kt', edit=Edit(kind='replace', old='"aiSuppressedTagIds",', new='"aiSuppressedTagIds",\n        "sabotage_probe_field",', count=-1), why='if the committed seed differs from the generator output, the gate catches it — and the seed is the one artifact that cannot be regenerated from within the check'),
    'doc-dead-refs': Control(kind='sabotage', proves='scripts/check-doc-dead-refs.py', argv=('{python}', 'scripts/check-doc-dead-refs.py'), path='README.md', edit=Edit(kind='append', new='\nSee `docs/decisions/DefinitelyNotAFile.md` for details.\n'), why='a new dead reference must fail until baselined'),
    'provenance': Control(kind='sabotage', proves='scripts/check-provenance.py', argv=('{python}', 'scripts/check-provenance.py'), path='config/legal/provenance-registry.tsv', edit=Edit(kind='drop_lines', old='QueryTokenizer'), why='a GPL upstream named in production code with no registry row is a licence claim nobody is tracking'),
    'pro-licence-boundary': Control(kind='sabotage', proves='scripts/check-pro-licence-boundary.py', argv=('{python}', 'scripts/check-pro-licence-boundary.py'), path='shared/build.gradle.kts', edit=Edit(kind='append', new='\ndependencies { implementation("ru.ok.tracer:tracer-crash-report:1.4.0") }\n'), why='proprietary code in an Apache-2.0 module makes the published licence a claim the project cannot honour'),
    'traceability-ratchet': Control(kind='sabotage', proves='scripts/check-traceability-ratchet.py', argv=('{python}', 'scripts/check-traceability-ratchet.py'), path='scripts/src/traceability/scenarios/tasks/checklist/TASK-CHECK-01.yaml', edit=Edit(kind='regex', pattern='^targets: \\[android\\]$', repl='targets: [android, desktop]', flags='MULTILINE'), why='a scenario claiming a target nothing verifies is the one hole count that grows without anyone reading the diff'),
    'room-schema-integrity': Control(kind='sabotage', proves='scripts/check-room-schema-integrity.py', argv=('{python}', 'scripts/check-room-schema-integrity.py'), path='shared/src/commonMain/kotlin/com/singularity/todo/core/database/AppDatabase.kt', edit=Edit(kind='regex', pattern='const val SCHEMA_VERSION = \\d+', repl='const val SCHEMA_VERSION = 1'), why='an entity change without a version bump passes every test and crashes every existing install on upgrade'),
    'dependency-usage': Control(kind='sabotage', proves='scripts/check-dependency-usage.py', argv=('{python}', 'scripts/check-dependency-usage.py', '--quiet'), path='scripts/dependency-usage-allowlist.txt', edit=Edit(kind='drop_lines', old='kermit-koin'), why="a dependency declared, resolved and imported by nothing costs a full release's build time and ships undetected — material-kolor sat on the classpath for one"),
    'supabase-schema-integrity': Control(kind='sabotage', proves='scripts/check-supabase-schema-integrity.py', argv=('{python}', 'scripts/check-supabase-schema-integrity.py'), path='supabase/migrations/2026-10-07-sync_schema.sql', edit=Edit(kind='append', new='\ncreate or replace function public.sync_control_probe(p_type text)\nreturns text as $$ select p_type $$ language sql;\n'), why='a function added without a fingerprint makes the header understate the schema, and the header is the part a reader trusts; the live half of #221 needs credentials and stays manual, so this is the half that can be a gate'),
    'rebase-compiles': Control(kind='sabotage', proves='scripts/check-rebase-compiles.py', argv=('{python}', 'scripts/check-rebase-compiles.py'), path='shared/src/commonMain/kotlin/com/singularity/todo/core/di/CoreDiModule.kt', edit=Edit(kind='replace', old='    single<DeviceIdProvider> { get<DataStoreSessionStore>() }', new='    // single<DeviceIdProvider> { get<DataStoreSessionStore>() }', count=-1), why="an import removed from a DI module silently breaks every consumer; the compile gate detects the resulting 'unresolved reference' before jvmTest does"),
    'req-id-uniqueness': Control(kind='fixture', proves='scripts/check-req-id-uniqueness.py', argv=('{python}', 'scripts/check-req-id-uniqueness.py', '--root', '{tmp}/repo'), fixture='setup_req_id_uniqueness', why='the collision this gate exists for, in the exact shape the tree had: a change that ADDs an identifier the spec already defines. The clean corpus would pass, so needs_clean_run is False — on this repository the corpus is still red (log-export-surface vs add-log-export), which is why the gate is registered advisory.', needs_clean_run=False),
    'flaky-tests': Control(kind='fixture', proves='scripts/check-flaky-tests.py', argv=('{python}', 'scripts/check-flaky-tests.py', '--current', '{tmp}/cur', '--previous', '{tmp}/prev'), fixture='setup_flaky_tests', why='a test that passed yesterday and fails today must be reported until it is acknowledged', needs_clean_run=False),
    'measure-flake-rate': Control(kind='fixture', proves='scripts/measure-flake-rate.py', argv=('{python}', 'scripts/measure-flake-rate.py', '--current', '{tmp}/run-01', '{tmp}/run-02', '--runs', '2', '--baseline', '{tmp}/empty-baseline.txt', '--max-flaky-rate', '0.0'), fixture='setup_measure_flake_rate', why="a status flip between two runs must be reported as an unacknowledged flake until it is baselined — the same property check-flaky-tests.py is probed for, but here it is the N-run aggregator's own gate logic (rate ceiling + baseline acknowledgement) that must not be swallows", needs_clean_run=False),
    'test-runs': Control(kind='sabotage', proves='scripts/check-test-runs.py', argv=('{python}', 'scripts/check-test-runs.py', '--require', 'shared:jvmTest,desktopApp:test', '--max-age', '86400'), path='config/docs/test-runs-baseline.txt', edit=Edit(kind='regex', pattern='^(shared:jvmTest )\\d+( \\d+)$', repl='\\g<1>99999\\g<2>', flags='MULTILINE'), why='a test source set that ran fewer tests than its recorded floor means coverage was lost'),
    'adr-references': Control(kind='sabotage', proves='scripts/check-adr-references.py', argv=('{python}', 'scripts/check-adr-references.py'), path='README.md', edit=Edit(kind='append', new='\nSee `docs/decisions/2026-10-05-nonexistent-adr.md` for details.\n'), why='a prose reference to a dated-ADR slug carries no path, so a moved or deleted ADR leaves a dangling claim nothing else sees'),
    'openspec-stale': Control(kind='sabotage', proves='scripts/check-openspec-stale.py', argv=('{python}', 'scripts/check-openspec-stale.py'), path='openspec/changes/detekt-rule-has-positive-control', edit=Edit(kind='age_dir', days=30), why='an active change nobody has returned to in three weeks is a decision deferred past the point of usefulness'),
    'rule-intent': Control(kind='sabotage', proves='scripts/check-rule-intent.py', argv=('{python}', 'scripts/check-rule-intent.py'), path='config/detekt/detekt.yml', edit=Edit(kind='replace', old='style:\n    MagicNumber:', new='style:\n    MagicNumber:\n      active: false', count=1), why='a rule that reports findings must be one the author chose; a detekt rule absent from detekt.yml runs on detekt built-in defaults, and 18 such rules were producing 247 of 428 baseline entries with nobody deciding'),
    'suppression-intent': Control(kind='sabotage', proves='scripts/check-suppression-intent.py', argv=('{python}', 'scripts/check-suppression-intent.py'), path='shared/src/jvmMain/kotlin/com/singularity/todo/feature/pomodoro/JvmPomodoroTimer.kt', edit=Edit(kind='strip_suppression_reason'), why='a file-level suppression with no recorded reason switches a rule off for every future call in that file'),
    'gradle-test-population': Control(kind='sabotage', proves='scripts/check-gradle-test-population.py', argv=('{python}', 'scripts/check-gradle-test-population.py'), path='.github/workflows/ci.yml', edit=Edit(kind='replace', old='run: ./gradlew :koverVerify -Ptest.tags=fast,slow', new='run: ./gradlew :koverVerify', count=1), why="a Gradle invocation that follows the test run without -Ptest.tags measures Gradle's default, excludeTags('slow'): the slow scenario classes are skipped rather than executed, the suite stays green, and every gate that reads build/test-results/ afterwards is measuring a population nobody ran (run 38933517902)", tracked_by='.github/workflows/ci.yml#Normalise desktop results into the scenario matrix'),
    'test-task-inputs': Control(kind='sabotage', proves='scripts/check-test-task-inputs.py', argv=('{python}', 'scripts/check-test-task-inputs.py'), path='shared/build.gradle.kts', edit=Edit(kind='replace', old='    inputs.dir(layout.projectDirectory.dir("../Maestro"))\n        .withPropertyName("maestroFlows")\n        .withPathSensitivity(PathSensitivity.RELATIVE)\n', new='', count=1), why='a test task reading a tree outside its module reports a stale verdict when that tree is not an input'),
    'detekt-rule-coverage-floor': Control(kind='sabotage', proves='gw', argv=('./gw', '-q', ':detekt-rules:koverVerify'), path='detekt-rules/build.gradle.kts', edit=Edit(kind='regex', pattern='^(val DETEKT_RULE_BRANCH_FLOOR = )\\d+$', repl='\\g<1>100', flags='MULTILINE'), why='branch coverage below the declared floor — the one thing the floor exists to catch, and the thing an unbound or zero bound lets through', needs_clean_run=False, tracked_by='.github/workflows/ci.yml#Kover verify (detekt-rules)'),
}


# Gates invoked somewhere but deliberately without a positive control here.
# Each names what does the proving instead: "no control" is only acceptable when
# something else is, and the reader has to be told what.
GATE_EXEMPTIONS: dict[str, Control] = {
    'scripts/check-gate-wiring.py': Control(kind='exempt', proves='scripts/check-gate-wiring.py', reason='this file. Its controls are its own Parts, and its self-tests (scripts/tests/test_check_gate_wiring.py) pin each part; a registry that had to sabotage itself to prove itself would be circular.'),
    'scripts/check-gate-honesty.py': Control(kind='exempt', proves='scripts/check-gate-honesty.py', reason="this file's own premise, one level down: it plants a real detekt violation and asserts the report names it, so it is a positive control rather than a gate needing one. Its control is scripts/tests/test_check_gate_honesty.py, including `test_a_probe_that_does_not_fire_is_a_failure_not_a_pass`."),
    'scripts/check-script-conventions.py': Control(kind='exempt', proves='scripts/check-script-conventions.py', reason='advisory by registration (gate "script conventions" in the shared registry) and already red on a clean tree: every legacy check-*.py still uses argparse (R2.1), so a sabotage control could not distinguish \'caught the mutation\' from \'still failing the 35 known violations\'. The debt is tracked by the advisory gate itself; when the Click migration sweep lands and the gate goes green, add a sabotage control (strip a docstring from any gate script) and drop this exemption.'),
    'scripts/check-doc-sizes.py': Control(kind='exempt', proves='scripts/check-doc-sizes.py', reason='local doc-size budgets (AGENTS.md <= 240 lines, SKILL.md <= 500 lines) are a house-keeping invariant; not run in CI, must not block a PR'),
    'scripts/check-provenance.py': Control(kind='exempt', proves='scripts/check-provenance.py', reason='provenance audit for Apache-2.0 / FSL-1.1 publication; runs locally before publication commits, not as a CI gate; the sabotage control is the provenance-registry.tsv itself'),
    'scripts/check-pro-licence-boundary.py': Control(kind='exempt', proves='scripts/check-pro-licence-boundary.py', reason='Apache-2.0 / pro licence boundary check; runs locally before publication commits, not as a CI gate; the sabotage control is the direction check itself'),
    'scripts/check-rebase-compiles.py': Control(kind='exempt', proves='scripts/check-rebase-compiles.py', reason='GATE_PARITY: needs a Gradle compile run with rebased DI files, so lives in the CI tests job after the rebase step and is not in the static-gates.sh registry; sabotage control is the rebase step'),
    'scripts/build-version-catalog-gate.py': Control(kind='exempt', proves='scripts/build-version-catalog-gate.py', reason='auto-generator: the gate proves version catalog is clean by checking for undeclared literals; the sabotage is removing the generated catalog file and asserting the gate fails — but the generator is the gate, so `test_version_catalog_gate_self_repairs` is the real control'),
    'scripts/tests': Control(kind='exempt', proves='scripts/tests', reason='meta-gate: `python3 -m unittest discover` is the gate; corrupting a test file would be the sabotage, but the unittest runner is the proving mechanism and its own test suite (scripts/tests/test_sgate_gates.py) is what makes it trustworthy'),
    'scripts/find-unwired-surfaces.py': Control(kind='exempt', proves='scripts/find-unwired-surfaces.py', reason="Part J cross-check itself is the control: the --registry-cross flag verifies this gate has no sabotage control and documents the exemption; the script's own test is scripts/tests/test_find_unwired_surfaces.py"),
    'scripts/check-gate-liveness.py': Control(
        kind='exempt',
        proves='scripts/tests/test_check_gate_liveness.py',
        reason=(
            'the gate *executes* other gates, so a sabotage control has to corrupt a script a '
            'registered gate actually runs — and three successive targets did not. '
            'check-kiwi-inventory-ratchet.py went with the retired Kiwi stand; '
            'check-backlog-issue-refs.py went with the removed backlog-issue-refs gate; '
            'check-skill-drift.py is not the script `sgate skills_describe_the_tools_that_exist` '
            'executes, so the injected raise never ran. All three passed a deliberately sabotaged '
            'input, i.e. they certified nothing. The proving is '
            'scripts/tests/test_check_gate_liveness.py (registry parsing, the heavyweight filter, '
            'the self-match skip) plus `check.sh`, which runs this gate for real on every '
            'invocation. A working control is still available if wanted: synthesise a two-gate '
            'registry fixture and assert the probe reports the dead entry.'
        ),
    ),
    'scripts/gen-detekt-rules-config.py': Control(kind='exempt', proves='scripts/gen-detekt-rules-config.py', reason='auto-generator: sabotage is deleting the generated config and asserting the gate fails; equivalent to `rm config/detekt/detekt-rules.yml && gate detekt-rules-config` — the output-diff is the control'),
    'scripts/gen-detekt-rule-table.py': Control(kind='exempt', proves='scripts/gen-detekt-rule-table.py', reason='auto-generator: sabotage is deleting the generated table and asserting the gate fails; equivalent to `rm docs/detekt/rule_table.md && gate detekt-rule-inventory` — the output-diff is the control'),
    'scripts/check-detekt-registrations.sh': Control(kind='exempt', proves='scripts/check-detekt-registrations.sh', reason='shell-gate: the sabotage is removing a rule from the registrations file and asserting the gate fails; equivalent to `rm config/detekt/rule_registrations.toml && gate detekt-rule-registry` — but Part J cross-check itself is what catches it when the control goes missing'),
    'scripts/ci/validate-maestro-selectors.py': Control(kind='exempt', proves='scripts/ci/validate-maestro-selectors.py', reason='CI-only gate: runs in the Maestro flow job, not locally; sabotage would be corrupting the selector files and asserting the gate fails; Part J cross-check itself is the backstop when the control goes missing'),
    'scripts/regen-skills-catalog.sh': Control(kind='exempt', proves='scripts/regen-skills-catalog.sh', reason='shell-gate auto-generator: sabotage is removing the generated catalog and asserting the gate fails; equivalent to `rm docs/SKILLS-CATALOG.md && gate skills-catalog` — the output-diff is the control'),
    'traceability': Control(
        kind='exempt',
        proves='scripts/tests/test_traceability.py',
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
