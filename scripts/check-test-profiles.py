#!/usr/bin/env python3
"""check-test-profiles.py — the profiles are declared, and the declaration is true.

## Why this exists

`config/test-profiles.properties` answers one question: **which tests does this
run?** Before the file existed, the repository had two names for that answer and
no name for the third:

* a *population* (`fast`, `full`) — a set of JUnit tags, read by the build, the
  workflows and the gates;
* a *lane* (`fast`, `affected`, `full`) — the local `hk` profile, which
  decides which steps run;
* and the third question nobody had a word for: **which tasks ran at all**.

The third one is what a failure investigation needs. "The fast population
failed" does not say whether `shared:archTest` ran, and "the local lane is
red" does not say which of the four workflows produced the evidence. The
profiles file names the answer, and this gate is what keeps the name honest.

## The rules

1. **The vocabulary is exactly the declared one.** `fast`, `full`, `device`,
   `e2e`, `all` — no more and no fewer. A profile nobody can ask for is a
   paragraph; a profile the vocabulary promises and the file does not keep is
   a promise.
2. **Every task is `module:task`, and the module exists.** Either a project in
   `settings.gradle.kts` or one of the declared pseudo-modules (`maestro` — a
   flow is not a Gradle task, and the pseudo-module says so instead of leaving
   the reader to guess why the project list has a hole in it).
3. **`all` is the union of the other four.** A declared set, deliberately: the
   evidence reporter reads this file when it is asked for the `all` profile and
   expects exactly these tasks. A wildcard sweep over `build/` would count the
   reports of a task that nobody ran in this run — the reports of an unrelated
   task are evidence about a different execution.
4. **`fast` and `full` differ by their population and by nothing else.** The
   difference between them is the tag selection, and the whole point of the
   populations file is that the selection is named in ONE place. A difference
   in the task list would mean the two profiles measure different things, and
   the fast coverage floor — which is recorded against `fast` — would be
   recorded against a task list nobody runs.
5. **A tier that is not tag-selected declares no population.** The instrumented
   tests carry no `@Tag` at all, and a Maestro flow's tag (`smoke`,
   `regression`, a feature name) is not a JUnit tag. A population for those
   tiers would be a name for a selection nothing implements.
6. **Every test task a surface runs is declared in `all`.** A surface is a
   workflow or `hk.pkl` — the places that actually run tests. A task that runs
   and is declared in no profile is a task whose evidence no profile expects,
   and the evidence reporter would call the run complete while ignoring it.
7. **Every declared task is run by some surface.** The other direction: a
   profile that declares a task nobody runs is a profile that promises a
   report that will never arrive, and the reporter would report the run as
   incomplete forever.
8. **Every task of a profile is in exactly one of three lists.** `reported`
   (the task wrote a report by the time the index is built), `deferred` (the
   report arrives later) and `conditional` (the task ran and wrote nothing,
   because the population selected no test of it). Three lists because there
   are three honest answers, and one list because the reporter has to be able
   to tell "did not run" from "ran and wrote nothing".
9. **The tier is one of the known tiers.** `jvm`, `android`, `maestro`, `all`.
   A tier nobody validated is a word in a file.
10. **Every profile says where its reports live.** The three tiers write their
    reports in three different places, and a reporter asked for the `device`
    profile has to look in the second one.
11. **A workflow carries no list of expected tasks of its own.** The
    expectations come from the profile registry, and a second list in a
    workflow is a definition that drifts away from the declaration it claims
    to follow.

## What it does not do

It does not run Gradle, and it does not read a JUnit report. It compares
*declared intent* against the repository's own surfaces — which is the part a
diff can change silently and a reviewer cannot see.

The one judgement it makes about a source set is a small table: a Gradle task
draws its testcases from a named source set, and a task whose module has no
such source set cannot produce testcases at all. That is how
`:androidApp:test` — which is NO-SOURCE, and runs zero tests — stays out of
the expectations without a special case in the rule.

Usage:
  python3 scripts/check-test-profiles.py [--verbose]

Exit codes:
  0 — the profiles agree with each other and with the repository
  1 — a profile drifted, or a task runs that no profile declares
  2 — I/O or parse error
"""

from __future__ import annotations

import io
import json
import pathlib
import re
import sys
import tokenize

import click

# `scripts/src` on sys.path, and it has to be there *before* the first import
# that uses it: this gate must resolve `workflow_gradle` and the shared runner
# against *this* checkout, not against whatever happens to be installed. When
# this file is loaded by a test harness via importlib, sys.path[0] is
# `scripts/tests` and the package is invisible; this file's own location is
# authoritative.
_scripts_src = str(pathlib.Path(__file__).resolve().parent / "src")
_added = _scripts_src not in sys.path
if _added:
    sys.path.insert(0, _scripts_src)

import workflow_gradle as wg  # noqa: E402
from sgate.commands.base import run  # noqa: E402

if _added:
    sys.path.remove(_scripts_src)

import yaml  # noqa: E402

ROOT = pathlib.Path(__file__).resolve().parent.parent
WORKFLOWS = ROOT / ".github" / "workflows"
LOCAL_SURFACE = ROOT / "hk.pkl"
SETTINGS = ROOT / "settings.gradle.kts"

#: The definitions this gate reads. Module-level on purpose: a test that
#: materialises a fake repository points these at the fake and the gate reads
#: the fake. A gate whose paths are buried in a function is a gate that can
#: only be tested against the real tree — and a gate tested only against the
#: real tree proves nothing when the real tree is what broke.
PROFILES_FILE = wg.PROFILES_FILE
POPULATIONS_FILE = wg.POPULATIONS_FILE
PROFILE_NAMES = wg.PROFILE_NAMES

#: `include(":tools:unwritten-properties")` — a Gradle project.
INCLUDE = re.compile(r'include\("(:[^"]+)"\)')

#: The source sets a test task draws its testcases from.
#:
#: A task whose module has no such source set cannot produce testcases, and
#: that is the whole reason `:androidApp:test` — NO-SOURCE, zero tests — does
#: not have to be declared by any profile. The table is small on purpose: it
#: names the source sets this repository actually has.
TASK_SOURCE_SETS: dict[str, tuple[str, ...]] = {
    "test": ("test", "jvmTest", "commonTest"),
    "jvmTest": ("jvmTest", "commonTest"),
    "archTest": ("jvmTest",),
    "testAndroidHostTest": ("androidHostTest",),
    "connectedDebugAndroidTest": ("androidTest",),
}

#: The source sets that count as "this module has tests at all".
TEST_SOURCE_SETS = ("test", "jvmTest", "commonTest", "androidTest", "androidHostTest")

#: The tiers a profile may declare.
TIERS = ("jvm", "android", "maestro", "all")

#: `--expect module:task` in a workflow's own command line.
EXPECT_ARG = re.compile(r"--expect[= ]+[\"']?([\w:.-]+)")

#: The artifact names that carry the evidence of a run. A step that writes or
#: uploads one of them has to run even when the job was cancelled: a
#: cancelled run is still a run, and its account of how far it got is worth
#: more than no account.
EVIDENCE_ARTIFACT = re.compile(
    r"(evidence|junit|allure|traceability|html-reports)", re.IGNORECASE
)


def _gradle_projects() -> set[str]:
    """The project paths `settings.gradle.kts` includes, without the colon."""
    try:
        text = SETTINGS.read_text(encoding="utf-8")
    except OSError as exc:
        raise RuntimeError(f"the Gradle settings are unreadable: {SETTINGS} ({exc})") from exc
    return {path.lstrip(":") for path in INCLUDE.findall(text)}


def _module_dir(module: str) -> pathlib.Path:
    """The directory of a Gradle project, from its `a:b` path."""
    return ROOT.joinpath(*module.split(":"))


def _has_tests(module: str, task: str) -> bool:
    """Can this task draw testcases from this module at all?

    The question is asked of the SOURCE SET, not of the module: a module with
    an `androidTest` source set can run the instrumented tier and cannot run a
    JVM unit test, and a task that cannot draw testcases produces no report
    for anybody to expect.
    """
    wanted = TASK_SOURCE_SETS.get(task)
    base = _module_dir(module)
    if wanted is None:
        return any((base / "src" / name).is_dir() for name in TEST_SOURCE_SETS)
    return any((base / "src" / name).is_dir() for name in wanted)


def _task_module(token: str) -> tuple[str, str] | None:
    """`(module, task)` for a Gradle task token, or `None`.

    A root task (`:koverVerify`) names no module, and a task of a project this
    repository does not include names one that is not here. Neither is a
    finding about the profiles: they are not test tasks of this build.
    """
    if not token.startswith(":"):
        return None
    parts = token.lstrip(":").split(":")
    if len(parts) < 2:
        return None
    return ":".join(parts[:-1]), parts[-1]


def _code_without_comments(text: str) -> str:
    """The source without its comments.

    A comment says what somebody wished the repository did; the code says
    what it does. A gate that reads both cannot tell the difference.
    """
    try:
        tokens = tokenize.generate_tokens(io.StringIO(text).readline)
        return " ".join(tok.string for tok in tokens if tok.type != tokenize.COMMENT)
    except (tokenize.TokenError, IndentationError, SyntaxError):
        return text


def _script_mentions(task: str) -> bool:
    """Does any runner script name this Gradle task?

    The device tier is not a Gradle invocation in a workflow: it is driven by
    `scripts/run-android-ui-tests.py`, which names the task it runs. A surface
    scan alone would therefore report the instrumented task as "run by
    nobody", and a declared task that nobody runs is a finding. The search is
    a text search, and it is deliberately weak: it answers "is this task
    spelled anywhere in the runner scripts", not "does the script run it" —
    the rule it feeds is about a typo in a profile, and a typo is a string
    that appears nowhere.

    Two things do not count as a mention. A TEST of the gate says what the
    gate should find, not what the repository runs; and a COMMENT says what
    somebody wrote down. Counting either would let the gate pass on the
    strength of a word rather than of a run.
    """
    scripts = ROOT / "scripts"
    for script in sorted(scripts.rglob("*.py")):
        if script.relative_to(scripts).parts[:1] == ("tests",):
            continue
        try:
            text = script.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        if task in _code_without_comments(text):
            return True
    return False


def _surfaces() -> list[tuple[str, str]]:
    """Every place that runs Gradle: the workflows and the local lane file."""
    found: list[tuple[str, str]] = []
    for workflow in sorted(WORKFLOWS.glob("*.y*ml")):
        try:
            data = yaml.safe_load(workflow.read_text(encoding="utf-8")) or {}
        except (OSError, yaml.YAMLError) as exc:
            raise RuntimeError(f"{workflow}: {exc}") from exc
        for job_name, job in (data.get("jobs") or {}).items():
            if not isinstance(job, dict):
                continue
            texts = [
                step["run"]
                for step in job.get("steps") or []
                if isinstance(step, dict) and isinstance(step.get("run"), str)
            ]
            if texts:
                found.append((f"{workflow.name}::{job_name}", "\n".join(texts)))
    if LOCAL_SURFACE.exists():
        found.append((LOCAL_SURFACE.name, LOCAL_SURFACE.read_text(encoding="utf-8")))
    return found


def _evidence_steps() -> list[tuple[str, dict]]:
    """The workflow steps that write or upload the evidence of a run.

    Two kinds of step: the one that indexes the reports, and the ones that
    upload them. Both have to survive a cancelled job — see rule 12.
    """
    found: list[tuple[str, dict]] = []
    for workflow in sorted(WORKFLOWS.glob("*.y*ml")):
        try:
            data = yaml.safe_load(workflow.read_text(encoding="utf-8")) or {}
        except (OSError, yaml.YAMLError) as exc:
            raise RuntimeError(f"{workflow}: {exc}") from exc
        for job_name, job in (data.get("jobs") or {}).items():
            if not isinstance(job, dict):
                continue
            for step in job.get("steps") or []:
                if not isinstance(step, dict):
                    continue
                run_text = step.get("run") if isinstance(step.get("run"), str) else ""
                name = str(step.get("name") or "")
                uses = str(step.get("uses") or "")
                with_block = step.get("with") if isinstance(step.get("with"), dict) else {}
                artifact = str(with_block.get("name") or "")
                if "test-evidence.py" in run_text or (
                    "upload-artifact" in uses
                    and EVIDENCE_ARTIFACT.search(f"{name} {artifact}")
                ):
                    found.append((f"{workflow.name}::{job_name}::{name}", step))
    return found


def evaluate(verbose: bool = False) -> tuple[list[str], list[str], list[str]]:
    """Return `(violations, notes, listing)` for the profiles."""
    violations: list[str] = []
    notes: list[str] = []
    listing: list[str] = []

    try:
        profiles = wg.load_profiles(PROFILES_FILE)
        populations = wg.load_populations(POPULATIONS_FILE)
    except RuntimeError as exc:
        raise RuntimeError(str(exc)) from exc

    declared = set(profiles)
    expected = set(PROFILE_NAMES)
    for missing in sorted(expected - declared):
        violations.append(
            f"the profile vocabulary promises {missing!r}, and the profiles file does not "
            f"declare it ({wg.PROFILES_FILE.name})"
        )
    for extra in sorted(declared - expected):
        violations.append(
            f"the profiles file declares {extra!r}, which is not one of the declared "
            f"profiles ({', '.join(sorted(expected))})"
        )

    projects = _gradle_projects()

    # Rule 2: every task is `module:task`, and the module exists.
    for name in sorted(declared):
        tasks = wg.profile_tasks(name, PROFILES_FILE)
        if not tasks:
            violations.append(
                f"the profile {name!r} declares properties but no tasks — a profile "
                f"with no task is not a profile, and the properties that name it "
                f"belong to nothing"
            )
        for task in tasks:
            if ":" not in task:
                violations.append(
                    f"the profile {name!r} declares the task {task!r}, which is not "
                    f"'module:task'"
                )
                continue
            module = task.rsplit(":", 1)[0]
            if module not in projects and module not in wg.PSEUDO_MODULES:
                violations.append(
                    f"the profile {name!r} declares the task {task!r}, and the module "
                    f"{module!r} is neither a project in settings.gradle.kts nor a "
                    f"declared pseudo-module"
                )

    # Rule 3: `all` is the union of the others.
    if "all" in declared:
        union: set[str] = set()
        for name in sorted(declared - {"all"}):
            union.update(wg.profile_tasks(name, PROFILES_FILE))
        all_tasks = set(wg.profile_tasks("all", PROFILES_FILE))
        for missing in sorted(union - all_tasks):
            violations.append(
                f"the profile 'all' does not declare the task {missing!r}, which another "
                f"profile declares"
            )
        for extra in sorted(all_tasks - union):
            violations.append(
                f"the profile 'all' declares the task {extra!r}, which no other profile "
                f"declares"
            )

    # Rules 4 and 5: the population is what differs, and only where there is one.
    if "fast" in declared and "full" in declared:
        fast_tasks = set(wg.profile_tasks("fast", PROFILES_FILE))
        full_tasks = set(wg.profile_tasks("full", PROFILES_FILE))
        if fast_tasks != full_tasks:
            only_fast = ", ".join(sorted(fast_tasks - full_tasks)) or "none"
            only_full = ", ".join(sorted(full_tasks - fast_tasks)) or "none"
            violations.append(
                f"the profiles 'fast' and 'full' declare different task lists — only "
                f"'fast': {only_fast}; only 'full': {only_full}. The difference between "
                f"them is the population, and nothing else"
            )
        fast_pop = wg.profile_population("fast", PROFILES_FILE)
        full_pop = wg.profile_population("full", PROFILES_FILE)
        if fast_pop == full_pop:
            violations.append(
                "the profiles 'fast' and 'full' declare the same population — they would "
                "run the same tests"
            )
        for profile_name, population in (("fast", fast_pop), ("full", full_pop)):
            if population and population not in populations:
                violations.append(
                    f"the profile {profile_name!r} declares the population {population!r}, "
                    f"and the populations file does not declare it"
                )
    for name in sorted(declared - {"fast", "full", "all"}):
        if wg.profile_population(name, PROFILES_FILE):
            violations.append(
                f"the profile {name!r} declares a population, and its tier is not "
                f"tag-selected — the instrumented tests carry no @Tag and a Maestro "
                f"flow's tag is not a JUnit tag"
            )
    if "all" in declared and wg.profile_population("all", PROFILES_FILE):
        violations.append(
            "the profile 'all' declares a population — it is the union of the tiers, "
            "and the tiers do not share one selection"
        )

    # Rules 8, 9 and 10: the partition of the tasks, the tier, the globs.
    for name in sorted(declared):
        tasks = set(wg.profile_tasks(name, PROFILES_FILE))
        reported = set(wg.profile_reported(name, PROFILES_FILE))
        deferred = set(wg.profile_deferred(name, PROFILES_FILE))
        conditional = set(wg.profile_conditional(name, PROFILES_FILE))
        for label, listed in (
            ("reported", reported),
            ("deferred", deferred),
            ("conditional", conditional),
        ):
            for task in sorted(listed - tasks):
                violations.append(
                    f"the profile {name!r} lists the task {task!r} as {label}, and the "
                    f"profile does not declare it"
                )
        for task in sorted((reported & deferred) | (reported & conditional) | (deferred & conditional)):
            violations.append(
                f"the profile {name!r} lists the task {task!r} in more than one of "
                f"reported, deferred and conditional — a task is in one of them or in "
                f"none, and the reporter has to be able to tell them apart"
            )
        for task in sorted(tasks - reported - deferred - conditional):
            violations.append(
                f"the profile {name!r} declares the task {task!r}, and the task is in "
                f"none of reported, deferred and conditional — the reporter would have "
                f"no way to say whether the task ran"
            )
        tier = wg.profile_tier(name, PROFILES_FILE)
        if tier not in TIERS:
            violations.append(
                f"the profile {name!r} declares the tier {tier!r}, and the known tiers "
                f"are: {', '.join(TIERS)}"
            )
        if not wg.profile_globs(name, PROFILES_FILE):
            violations.append(
                f"the profile {name!r} declares no report globs — a reporter asked for "
                f"this profile would not know where its reports live"
            )

    # Rules 6 and 7: the surfaces and the profiles must see the same tasks.
    declared_all = set(wg.profile_tasks("all", PROFILES_FILE)) if "all" in declared else set()
    run_tasks: dict[str, list[str]] = {}
    for location, text in _surfaces():
        # Rule 11: the expectations come from the profile registry.
        for task in EXPECT_ARG.findall(text):
            violations.append(
                f"{location}: the workflow lists the expected task {task!r} of its own "
                f"— the expectations come from the profile registry, and a second list "
                f"is a definition that drifts away from it"
            )
        for invocation in wg.iter_invocations(text, location):
            if not invocation.test_capable:
                continue
            for token in invocation.tasks:
                if not wg.is_report_task(token):
                    continue
                parsed = _task_module(token)
                if parsed is None:
                    continue
                module, task = parsed
                if module not in projects:
                    continue
                if not _has_tests(module, task):
                    continue
                run_tasks.setdefault(token.lstrip(":"), []).append(location)
                if token.lstrip(":") not in declared_all:
                    violations.append(
                        f"{location}: the test task {token!r} runs, and no profile "
                        f"declares it — the 'all' profile would expect a report nobody "
                        f"promised"
                    )
    for task in sorted(declared_all):
        module = task.rsplit(":", 1)[0]
        if module in wg.PSEUDO_MODULES:
            continue
        if task not in run_tasks and not _script_mentions(task):
            violations.append(
                f"the profile 'all' declares the task {task!r}, and no workflow, no "
                f"hk.pkl and no runner script runs it — a profile that promises a "
                f"report that never arrives is a profile that reports every run as "
                f"incomplete"
            )

    # Rule 12: the evidence of a run survives the cancellation of the job.
    for location, step in _evidence_steps():
        condition = str(step.get("if") or "")
        if "!cancelled()" in condition:
            violations.append(
                f"{location}: the step that carries the evidence of the run is guarded by "
                f"!cancelled() — a cancelled job is still a run, and throwing its evidence "
                f"away leaves the reader with nothing about how far it got"
            )
        elif "always()" not in condition:
            violations.append(
                f"{location}: the step that carries the evidence of the run does not run on "
                f"always() — the evidence of a run that failed, was skipped or was cancelled "
                f"is exactly the evidence a reader needs"
            )

    if verbose:
        for name in sorted(declared):
            population = wg.profile_population(name, PROFILES_FILE)
            suffix = f", population {population}" if population else ""
            listing.append(
                f"  {name}: {len(wg.profile_tasks(name, PROFILES_FILE))} task(s){suffix} — "
                f"{', '.join(wg.profile_tasks(name, PROFILES_FILE))}"
            )
        for task in sorted(run_tasks):
            listing.append(f"  runs: {task} ({len(run_tasks[task])} surface(s))")

    return violations, notes, listing


@click.command()
@click.option("--verbose", is_flag=True, help="List every profile and every task it found.")
@click.option("--check", is_flag=True, help="Dry-run: report without a non-zero exit.")
@click.option("--json", "emit_json", is_flag=True, help="Emit the verdict as JSON.")
@click.pass_context
def cli(ctx: click.Context, verbose: bool, check: bool, emit_json: bool) -> None:
    """The profiles agree with each other and with the repository."""
    try:
        violations, notes, listing = evaluate(verbose=verbose)
    except (OSError, RuntimeError) as exc:
        if emit_json:
            click.echo(json.dumps({"passed": False, "blocking_errors": [str(exc)],
                                   "warnings": []}, ensure_ascii=False))
        else:
            click.echo(f"check_test_profiles: ERROR — {exc}", err=True)
        raise ctx.exit(2)

    if emit_json:
        click.echo(json.dumps(
            {
                "passed": not violations,
                "blocking_errors": violations,
                "warnings": notes,
            },
            ensure_ascii=False,
        ))
        raise ctx.exit(0 if (not violations or check) else 1)

    for line in listing:
        click.echo(line)
    for note in notes:
        click.echo(f"check_test_profiles: note: {note}")

    if violations:
        for violation in violations:
            click.echo(f"check_test_profiles: {violation}", err=True)
        click.echo(
            "check_test_profiles: FAIL — the profiles are the vocabulary every run "
            "reports itself in, and a drifted profile makes the report describe a run "
            "that never happened",
            err=True,
        )
        raise ctx.exit(0 if check else 1)

    click.echo("check_test_profiles: OK — the profiles agree with each other and with the repository")
    raise ctx.exit(0)


def main(argv: list[str] | None = None) -> int:
    """The exit code every gate shares: `run` is the one reader of it."""
    return run(cli.main, argv)


if __name__ == "__main__":
    sys.exit(main())
