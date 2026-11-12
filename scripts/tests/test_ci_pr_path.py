#!/usr/bin/env python3
"""test_ci_pr_path.py — what the pull-request path runs, and what it does not.

The pull request is the path that matters: it is where a change is judged, and
its cost decides whether a reviewer waits. This file reads
`.github/workflows/ci.yml` as TEXT — the way the repository's gates do — and
pins the shape of that path:

* the pull-request path runs its tests WITHOUT the Kover agent, and no ordinary
  pull request carries an APP coverage verdict: coverage is measured in the
  suite job's OWN conditional steps, on a push, the schedule or a labelled
  pull request, so a verdict is never about a measurement nobody took;
* the suite job names every test task it runs, so what a pull request proves
  is written down rather than implied by an aggregate;
* detekt is judged in exactly one job: two analyses of the same tree are two
  verdicts nobody reads;
* the affected-test job runs BESIDE the suite job, and names one population in
  one spelling;
* the merge gate waits for the jobs that carry verdicts;
* the control phase — the harness that proves each gate can fail — runs when
  the gate corpus changed or on the schedule, and not otherwise.

Every assertion is about the file as it is, not about a fixture. A test that
asserts a synthetic workflow proves that the parser reads what it claims to
read, and nothing about the workflow this repository runs.
"""

import importlib.util
import json
import pathlib
import re
import sys
import tempfile
import unittest

import click.testing
import yaml

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS / "src"))
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import workflow_gradle as wg  # noqa: E402 — the path entry above is the reason
from git_sandbox import git as _git, git_output as _git_output  # noqa: E402
from git_sandbox import no_named_base, sandbox_env  # noqa: E402
from sgate.gatekit.refs import (  # noqa: E402
    BASE_REF_ENV,
    commit_of,
    resolve_change_base_ref,
)

#: The gate that measures the baseline's own growth. It is loaded by path for
#: the same reason the other scripts are: the answer belongs to the file, and
#: the file is the thing under test.
RATCHET = SCRIPTS / "check-baseline-ratchet.py"
_ratchet_spec = importlib.util.spec_from_file_location("check_baseline_ratchet", RATCHET)
ratchet = importlib.util.module_from_spec(_ratchet_spec)
_ratchet_spec.loader.exec_module(ratchet)

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
WORKFLOW = ROOT / ".github/workflows/ci.yml"
SETTINGS = ROOT / "settings.gradle.kts"
POPULATIONS = ROOT / "config/test-populations.properties"
PRE_PUSH = ROOT / "config/hooks/pre-push.json"

#: The APP's coverage tasks — the ROOT ones. The module's own
#: `:detekt-rules:koverVerify` is NOT one of them: the custom rules apply the
#: Kover plugin themselves, and their branch-coverage floor is enforced on
#: every path, a pull request's included. That is a gate, not a measurement
#: nobody acts on.
APP_COVERAGE_TASKS = ("koverReport", "koverHtml", "koverXmlReport", ":koverVerify")

#: The rules' own floor, which the pull-request path DOES carry.
RULES_FLOOR_TASK = ":detekt-rules:koverVerify"


def _workflow() -> dict:
    return yaml.safe_load(WORKFLOW.read_text(encoding="utf-8")) or {}


def _jobs() -> dict:
    return _workflow().get("jobs", {}) or {}


def _job_text(job: dict) -> str:
    """Every `run:` text of a job, joined.

    The job's own text — the commands it runs, without the workflow's
    comments. The gates read the workflow's text including comments; this one
    asks a different question ("what does this job RUN"), so it reads the
    commands.
    """
    parts = []
    for step in job.get("steps", []) or []:
        if isinstance(step, dict) and step.get("run"):
            parts.append(str(step["run"]))
    return "\n".join(parts)


def _step_naming(job: dict, needle: str) -> str:
    """The `run:` text of the job's step that names *needle*."""
    for step in job.get("steps", []) or []:
        if isinstance(step, dict) and needle in str(step.get("run", "")):
            return str(step["run"])
    raise AssertionError(
        f"no step of this job names {needle!r}; the job runs:\n{_job_text(job)}"
    )


def _needs(job: dict) -> list[str]:
    """The job's dependencies, as a list whatever the YAML says."""
    needs = job.get("needs", []) or []
    if isinstance(needs, str):
        return [needs]
    return [str(name) for name in needs]


def _step_env(job: dict) -> dict:
    """Every step's environment of a job, merged."""
    env: dict = {}
    for step in job.get("steps", []) or []:
        if isinstance(step, dict) and isinstance(step.get("env"), dict):
            env.update(step["env"])
    return env


def _coverage_tasks(text: str) -> set[str]:
    """The app coverage tasks a text names."""
    without_rules = text.replace(RULES_FLOOR_TASK, "")
    return {name for name in APP_COVERAGE_TASKS if name in without_rules}


#: The job-level switch that decides, per run, whether the suite job measures
#: coverage. It is one job and one run now: the tests always run, the
#: measurement is a conditional step.
COVERAGE_FLAG = "COVERAGE_ENABLED"


def _coverage_gated_steps(job: dict) -> list[dict]:
    """The steps of a job that run only where a coverage measurement is wanted.

    A step is gated when its `if:` reads the switch the job's `env` sets. An
    ordinary pull request runs none of them, which is what keeps a coverage
    verdict off a run that was not instrumented.
    """
    return [
        step
        for step in job.get("steps", []) or []
        if isinstance(step, dict) and COVERAGE_FLAG in str(step.get("if", ""))
    ]


def _declared_populations() -> dict[str, list[str]]:
    """The populations the repository declares, and the tags each selects."""
    declared: dict[str, list[str]] = {}
    for line in POPULATIONS.read_text(encoding="utf-8").splitlines():
        line = line.split("#", 1)[0].strip()
        if not line or "=" not in line:
            continue
        name, _, tags = line.partition("=")
        declared[name.strip()] = [tag.strip() for tag in tags.split(",") if tag.strip()]
    return declared


def _is_pr_path(job: dict) -> bool:
    """True when the job's condition admits an ordinary pull-request event.

    R1 defines the pull-request path by the job's `if:`: a job that runs only
    on a push to the trunk, only on the schedule, or only on a pull request
    carrying the `run-full-tests` label is not on it.

    The condition is read as text, and the question is what it REQUIRES:
    `needs.changes.outputs.shared == 'true'` admits a pull request — the
    changed-path outputs are computed for pull requests too — while
    `github.event_name == 'schedule'` admits only the schedule.
    """
    condition = str(job.get("if", "") or "")
    if not condition.strip():
        return True
    if "github.event_name == 'schedule'" in condition:
        return False
    if "run-full-tests" in condition:
        return False
    if "refs/heads/main" in condition and "needs.changes.outputs" not in condition:
        return False
    return True


def _matrix_legs(job: dict) -> list[dict]:
    """The matrix entries of a job — one per leg it runs."""
    matrix = (job.get("strategy") or {}).get("matrix") or {}
    return list(matrix.get("include") or [])


def _effective_runs(job: dict) -> list[str]:
    """The run texts of a job that an ordinary pull request executes.

    A matrix leg is a real invocation: `${{ matrix.tasks }}` names the tasks
    that leg runs, and a reader that cannot see them cannot say what the job
    runs. So the expression is replaced with the leg's own value — a task
    hidden behind a matrix is still a task the job runs.

    A step gated on the coverage switch is NOT on the pull-request path: it
    runs on a push, the schedule or a labelled pull request. Counting it would
    read the coverage measurement as an ordinary pull request's verdict.
    """
    legs = _matrix_legs(job)
    texts: list[str] = []
    for step in job.get("steps", []) or []:
        if not isinstance(step, dict):
            continue
        if COVERAGE_FLAG in str(step.get("if", "")):
            continue
        run = step.get("run")
        if not isinstance(run, str):
            continue
        if not legs:
            texts.append(run)
            continue
        for leg in legs:
            texts.append(_substitute_matrix(run, leg))
    return texts


def _substitute_matrix(text: str, leg: dict) -> str:
    """The run text of one matrix leg."""
    for key, value in leg.items():
        text = text.replace("${{ matrix.%s }}" % key, str(value))
    return text


def _detekt_modules() -> set[str]:
    """The modules the record says CI judges."""
    record = json.loads(PRE_PUSH.read_text(encoding="utf-8"))
    return set(record["detekt"]["judged_by_ci"])


def _graph_detekt_tasks() -> set[str]:
    """The detekt tasks the module graph reports — the list CI judges.

    The workflow does not spell these names out: it asks the graph for them
    (`changed-modules.py --detekt-tasks`) and hands the list to Gradle. A
    count over the text would therefore see no detekt task at all, and the
    job that judges detekt would look like a job that judges nothing.
    """
    from sgate.module_graph import detekt_tasks

    return detekt_tasks(ROOT)


def _derived_detekt_tasks(text: str) -> set[str]:
    """The detekt tasks a run judges because it asked the graph for them."""
    if "changed-modules.py --detekt-tasks" not in text:
        return set()
    if "DETEKT_TASKS" not in text:
        return set()
    return _graph_detekt_tasks()


class PrPathHasNoCoverage(unittest.TestCase):
    """The pull-request path carries no coverage measurement it does not enforce."""

    def test_the_pull_request_path_runs_without_the_coverage_agent(self):
        """The pull-request path runs its tests without the Kover agent.

        The agent is attached by `enableCoverage()` in `settings.gradle.kts`,
        and that call is a property of the CONFIGURATION, not of the task
        graph: measured 2026-10-23, an invocation that named no coverage task
        at all still ran the test JVM with `-javaagent:…kover-jvm-agent-0.9.9.jar`
        on its command line, and `--dry-run` listed `:koverAgentSearch`. A job
        that drops `koverReport` from its invocation moves the REPORT and
        leaves the COST.

        So the switch lives in the build, and this file pins three facts: the
        build HAS a switch, the pull-request path asks for it, and the coverage
        steps ask for the other side of it.
        """
        settings = SETTINGS.read_text(encoding="utf-8")

        # The switch is a property, and it is read from the property — not a
        # constant someone flipped by hand. The formatter may break the call
        # across lines, so the whitespace is not pinned.
        self.assertRegex(
            settings,
            r'providers\s*\.gradleProperty\("coverage\.enabled"\)',
        )

        # The switch is a CONDITION: `enableCoverage()` inside the `if` is
        # what makes the agent optional at all.
        self.assertRegex(
            settings,
            r"if\s*\(\s*coverageEnabled\s*\)\s*\{[^{}]*enableCoverage\(\)",
        )

        jobs = _jobs()

        # The suite job's test run asks for the opt-out.
        suite_run = _step_naming(jobs["tests"], ":shared:jvmTest")
        self.assertIn("-Pcoverage.enabled=false", suite_run)

        # The detekt job runs no tests, but it says which side of the switch it
        # is on — a job that does not say is a job that inherits the default.
        self.assertIn("-Pcoverage.enabled=false", _job_text(jobs["detekt"]))

        # The affected-test job runs the map's commands, and the opt-out is
        # appended where those commands are run.
        quick_text = _job_text(jobs["quick"])
        self.assertIn("-Pcoverage.enabled=false", quick_text)

        # The coverage steps of the SAME job opt IN, explicitly, and they run
        # only where the measurement is wanted: one run carries both verdicts.
        coverage_steps = _coverage_gated_steps(jobs["tests"])
        self.assertTrue(
            coverage_steps,
            "the suite job has no coverage-gated step — the measurement has "
            "nowhere to run that is not an ordinary pull request",
        )
        coverage_text = "\n".join(str(step.get("run", "")) for step in coverage_steps)
        self.assertIn("-Pcoverage.enabled=true", coverage_text)

    def test_every_pr_path_test_invocation_opts_out_of_the_coverage_agent(self):
        """Every pull-request job that runs a test task asks for no agent.

        The job that broke the rule was the one a reader of the run text could
        not see: the android job's pro leg names `:pro:testDebugUnitTest`
        behind `${{ matrix.tasks }}`, which is one opaque token to a parser.
        So the legs are expanded here — a task hidden behind a matrix is still
        a task the job runs — and every test-capable invocation on the path
        is checked.

        The rules' own floor is the exception, and it is deliberate: the
        custom rules apply the Kover plugin themselves, and their floor is
        enforced on the pull-request path.

        The affected-test job runs the map's commands, so its invocation is
        assembled at run time and no reader of the workflow can see it; the
        assertion above reads its opt-out from the job's own text.
        """
        checked: list[str] = []
        for name, job in _jobs().items():
            if not _is_pr_path(job):
                continue
            for text in _effective_runs(job):
                for invocation in wg.iter_invocations(text, name):
                    if not invocation.test_capable:
                        continue
                    if RULES_FLOOR_TASK in invocation.tasks:
                        continue
                    checked.append(f"{name} ({invocation.location})")
                    self.assertIn(
                        "-Pcoverage.enabled=false",
                        text,
                        f"the pull-request job {name!r} runs a test task without "
                        f"asking for no coverage agent ({invocation.location})",
                    )
        self.assertTrue(
            checked,
            "no pull-request job runs a test task — the assertion proves nothing",
        )

    def test_no_pr_path_job_names_a_coverage_task(self):
        """Only the suite job names an app coverage task, and only in gated steps.

        The app's coverage tasks are the ROOT ones — `koverReport`,
        `koverHtml`, `:koverVerify`. A pull request that failed a coverage
        floor would be failing on a measurement it never took, so the steps
        that name them run only where the switch says coverage is wanted.

        The rules' own floor (`:detekt-rules:koverVerify`) is the exception,
        and it is a deliberate one: the custom rules apply the Kover plugin
        themselves, and their branch-coverage floor is a gate the pull-request
        path enforces.
        """
        jobs = _jobs()
        naming = {
            name for name, job in jobs.items() if _coverage_tasks(_job_text(job))
        }
        self.assertEqual({"tests"}, naming)

        # And no step that runs on an ordinary pull request names one.
        ungated = "\n".join(
            str(step.get("run", ""))
            for step in jobs["tests"].get("steps", []) or []
            if isinstance(step, dict) and COVERAGE_FLAG not in str(step.get("if", ""))
        )
        self.assertEqual(set(), _coverage_tasks(ungated))

        # And the exception is still there: the rules' floor is enforced on
        # the pull-request path, so the path carries a measurement it DOES
        # enforce.
        self.assertIn(RULES_FLOOR_TASK, _job_text(jobs["tests"]))

    def test_a_pull_request_reports_no_coverage_verdict(self):
        """An ordinary pull request gets no coverage verdict; a labelled one does.

        The job's switch (`COVERAGE_ENABLED`) IS the verdict's condition: the
        measurement runs where it is wanted — a push to the trunk, the
        schedule, a manual run, or a pull request that asks for the full
        population — and not on an ordinary pull request.
        """
        jobs = _jobs()
        switch = str((jobs["tests"].get("env") or {}).get(COVERAGE_FLAG, ""))
        self.assertTrue(switch, "the suite job does not set the coverage switch")

        self.assertIn("github.event_name == 'push'", switch)
        self.assertIn("github.event_name == 'schedule'", switch)

        # The pull request case exists, and the label is what it asks for. The
        # label arrives two ways — the `labeled` event carries it in
        # `github.event.label.name`, every later push carries it in the PR's
        # label list — and reading only one of them means the label works
        # until the next commit and then silently stops working.
        self.assertRegex(
            switch,
            r"(?s)github\.event_name\s*==\s*'pull_request'.{0,400}run-full-tests",
        )
        self.assertIn("github.event.label.name", switch)
        self.assertIn("github.event.pull_request.labels", switch)

        # The job that carries the verdict is one the merge gate waits for —
        # otherwise a coverage breach on a labelled pull request would report
        # green.
        self.assertIn("tests", _needs(jobs["ci-gate"]))


class PrPathRunsTheWholeSuite(unittest.TestCase):
    """The suite job says what it runs."""

    def test_the_tests_job_names_every_test_task_it_runs(self):
        """The suite job names every test task it runs.

        The list used to be implied: `koverReport` pulls the test tasks in, and
        the workflow said nothing about which they were. A second copy of that
        list in the workflow is a list that has to stay in step with kover's —
        and it is exactly how kover silently stops measuring a module. Naming
        the tasks is the list itself.
        """
        jobs = _jobs()
        suite_run = _step_naming(jobs["tests"], ":shared:jvmTest")

        for task in (
            ":shared:jvmTest",
            ":shared:testAndroidHostTest",
            ":desktopApp:test",
            ":mcp-server:test",
            ":detekt-rules:test",
        ):
            self.assertIn(task, suite_run)

        # `:androidApp:test` is NOT named, and that is the point: the module has
        # no JVM unit-test source set, so the task is NO-SOURCE and runs zero
        # tests. A named task that runs nothing reads like a suite that exists.
        self.assertNotIn(":androidApp:test", suite_run)

        # The ADR modules run in their own invocation and the ADR job owns
        # them: naming them here would put a module in two jobs' worth of
        # coverage, and the floor would move.
        self.assertNotIn(":adr-core:jvmTest", suite_run)

        # The non-test parts of the invocation stay: the jar the end-to-end
        # test spawns (the test task builds it too) and the tool-surface
        # gate.
        self.assertIn(":mcp-server:jar", suite_run)
        self.assertIn(":shared:verifyToolSurface", suite_run)

        # And the coverage entry point is gone from the pull-request path.
        self.assertNotIn("koverReport", suite_run)
        self.assertNotIn("koverHtml", suite_run)


class DetektIsJudgedOnce(unittest.TestCase):
    """One job judges detekt."""

    def test_exactly_one_pr_path_job_judges_detekt(self):
        """Exactly one job judges detekt.

        Detekt used to be judged TWICE: the `static` job auto-corrected the
        tree it had already checked, and the suite job's invocation judged it
        again. Two analyses of the same tree are two verdicts, and nobody
        reads the second one.

        The count is over the invocations, not over the text: a comment that
        mentions a detekt task is not a verdict. And the matrix legs are
        expanded — the android job's pro leg names a detekt task behind
        `${{ matrix.tasks }}`, which is one opaque token to a reader of the
        run text, and a task a reader cannot see is a task nobody counts.

        The count is over the modules the record names
        (`config/hooks/pre-push.json`), and the one task outside that record
        is named here: the pro catalogue is a separate build the module graph
        does not know, so the record lists it apart, and the `detekt` job does
        not judge it. Naming the exception is what keeps it an exception.

        A job that judges detekt by ASKING the module graph for the list
        counts too — the task names are not in the workflow, and a list
        written out by hand is exactly what this repository stopped writing.
        """
        modules = _detekt_modules()
        judging: set[str] = set()
        elsewhere: set[tuple[str, str]] = set()
        for name, job in _jobs().items():
            if not _is_pr_path(job):
                continue
            for text in _effective_runs(job):
                derived = _derived_detekt_tasks(text)
                for invocation in wg.iter_invocations(text, name):
                    for task in set(invocation.tasks) | derived:
                        if not task.endswith(":detekt"):
                            continue
                        if task in modules:
                            judging.add(name)
                        else:
                            elsewhere.add((name, task))
        self.assertEqual({"detekt"}, judging)
        self.assertEqual({("android", ":pro:detekt")}, elsewhere)

        # And the job that judges it corrects first and judges second, so the
        # verdict measures an already-corrected tree.
        detekt_steps = [
            str(step.get("name", ""))
            for step in _jobs()["detekt"].get("steps", []) or []
            if isinstance(step, dict)
        ]
        self.assertTrue(detekt_steps, "the detekt job runs no steps")
        self.assertIn("Detekt auto-correct (commit + push)", detekt_steps)
        self.assertIn("Gradle (detekt)", detekt_steps)
        self.assertLess(
            detekt_steps.index("Detekt auto-correct (commit + push)"),
            detekt_steps.index("Gradle (detekt)"),
        )


class QuickJobRunsTheAffectedSubset(unittest.TestCase):
    """The affected-test job."""

    def test_the_quick_job_runs_beside_the_full_suite(self):
        """The affected-test job runs BESIDE the suite job, not instead of it.

        "Beside" means what it says: neither job waits for the other. A job
        that waited would turn the fast signal into a step of the slow one —
        the fast answer would arrive after the slow one, which is the same as
        not having it.
        """
        jobs = _jobs()
        self.assertNotIn("tests", _needs(jobs["quick"]))
        self.assertNotIn("quick", _needs(jobs["tests"]))

        # It is a pull-request job: on a push to the trunk the base reference
        # IS the head, so the comparison is empty and the job would report
        # "the change selects nothing" for every push — a fact about the
        # push, not about the change.
        self.assertIn("github.event_name == 'pull_request'", str(jobs["quick"].get("if", "")))

        # And it compares against a base that has to exist: a shallow
        # checkout has no `origin/main`, and the tool refuses a base it cannot
        # resolve.
        checkout = [
            step
            for step in jobs["quick"].get("steps", []) or []
            if isinstance(step, dict) and "checkout" in str(step.get("uses", ""))
        ]
        self.assertEqual(1, len(checkout))
        self.assertEqual(0, (checkout[0].get("with") or {}).get("fetch-depth"))


class TheGateToolingJobHasTheBaseTest(unittest.TestCase):
    """The job that runs the gates against this repository needs the base.

    The ratchet measures the branch's own growth against the base the
    workflow names, and a ratchet that cannot measure is a gate that fails.
    A shallow checkout has no `origin/main`, so the job that runs the gates
    has to fetch the history — otherwise the failure means "the checkout did
    not fetch" rather than "the comparison could not be made".
    """

    def test_the_gate_suite_job_checks_the_full_history_out(self):
        jobs = _jobs()
        checkout = [
            step
            for step in jobs["meta"].get("steps", []) or []
            if isinstance(step, dict) and "checkout" in str(step.get("uses", ""))
        ]
        self.assertEqual(1, len(checkout))
        self.assertEqual(0, (checkout[0].get("with") or {}).get("fetch-depth"))


class QuickJobReportsItsOwnOutcome(unittest.TestCase):
    """The affected-test job says what happened."""

    def test_the_quick_job_names_which_of_its_three_outcomes_happened(self):
        """The job says which of its three outcomes happened.

        Three: the selection ran and passed, the change selects no test class,
        and the selection could not be computed. The third is a FAILURE and the
        second is an ANSWER — a job that reported both as green would be a
        job that reports a broken map as a change that breaks nothing.
        """
        text = _job_text(_jobs()["quick"])

        for outcome in ("selected", "empty", "uncomputable"):
            self.assertIn(f"outcome={outcome}", text)

        # The empty selection is read from the channel the tool writes it on —
        # stdout — and not guessed from the absence of output.
        self.assertIn("test-impact-map: the change selects no test class", text)

        # And the uncomputable case fails the job: a comparison that could not
        # be made is not a comparison that found nothing.
        self.assertIn("exit 1", text)


class OnePopulationPerJob(unittest.TestCase):
    """One population, one spelling."""

    def test_the_quick_job_names_one_population_in_one_spelling(self):
        """The affected-test job names ONE population, in one spelling.

        The population is a NAME the repository declares in one place
        (`config/test-populations.properties`). A job that spelled the tags
        itself would be a second answer to the same question — and the map
        once answered a different one, selecting the fast and the slow half
        together while the job that called it asked for minutes.
        """
        text = _job_text(_jobs()["quick"])

        named = re.findall(r"--population\s+(\S+)", text)
        self.assertEqual(1, len(named), f"the job names {named}")
        self.assertIn(named[0], _declared_populations())

        # No tag list: the tags are the population's business.
        self.assertNotIn("-Ptest.tags=", text)

    def test_the_populations_the_workflow_selects_are_declared(self):
        """Every population the workflow can select is declared."""
        declared = _declared_populations()
        for name in ("fast", "full"):
            self.assertIn(name, declared)
        self.assertEqual(["fast"], declared["fast"])
        self.assertEqual(["fast", "slow"], declared["full"])


class MergeGateRequiresTheNewJobs(unittest.TestCase):
    """The merge gate waits for the verdicts."""

    def test_the_merge_gate_needs_the_quick_and_detekt_jobs(self):
        """The merge gate waits for the affected-test job and the detekt job.

        A gate that does not wait reports green while a verdict is still
        missing — and the missing verdict is the one a reviewer would have
        looked at first.
        """
        needs = _needs(_jobs()["ci-gate"])
        self.assertIn("quick", needs)
        self.assertIn("detekt", needs)

        # A skipped job is a valid outcome, so a job that does not apply to
        # this pull request does not block the merge.
        self.assertIn("success|skipped", _job_text(_jobs()["ci-gate"]))


class ControlHarnessPlacement(unittest.TestCase):
    """The control phase runs where it is worth running."""

    def test_the_control_phase_runs_on_a_gate_change_and_not_otherwise(self):
        """The control phase runs when the gate corpus changed, or on the schedule.

        The phase proves that each gate can still fail: it sabotages the
        gate's own input and runs it. A change to the app cannot make a gate
        stop working, so proving it on every pull request is minutes spent on
        a question the change did not raise. A change to the GATES is exactly
        that question.
        """
        jobs = _jobs()
        text = _job_text(jobs["static"])

        self.assertIn("sgate run-all --with-controls", text)
        self.assertIn("sgate run-all", text)

        # The condition is CARRIED IN, not recomputed: the corpus is declared
        # once, in the job that computes it, and the shell asks for the answer
        # instead of keeping a second copy of the corpus.
        env = _step_env(jobs["static"])
        self.assertIn("WITH_CONTROLS", env)
        self.assertIn("needs.changes.outputs.gates", env["WITH_CONTROLS"])
        self.assertIn("github.event_name == 'schedule'", env["WITH_CONTROLS"])

        # And the corpus is the gates' own declarations, the configuration they
        # read, the workflow they read, and the hook that runs them: the
        # pre-push hook is a gate surface too, and a change that touches only
        # the hook runs neither the gate suite nor the static gates otherwise.
        filters = _filters(jobs["changes"])
        self.assertEqual(
            {"scripts/**", "config/**", ".github/**", ".githooks/**"},
            set(filters["gates"]),
        )

        # The `changes` job publishes the answer the condition reads.
        self.assertIn("gates", jobs["changes"].get("outputs", {}))


class OneBaseRefForTheWholeWorkflow(unittest.TestCase):
    """The base ref is named once, and never as the branch that was pushed."""

    def test_the_workflow_names_the_base_ref_once(self):
        """The workflow names the base, and the jobs inherit it.

        Every gate that measures a change reads the base through
        `SGATE_BASE_REF`. A job that names its own base — or a workflow that
        names none — is a run in which two gates measure two different
        changes, and nothing says so.
        """
        workflow = _workflow()
        env = workflow.get("env") or {}
        self.assertIn("SGATE_BASE_REF", env)

        for name, job in _jobs().items():
            job_env = job.get("env") or {}
            self.assertNotIn(
                "SGATE_BASE_REF",
                job_env,
                f"the {name} job names its own base — the workflow names it once",
            )

    def test_the_named_base_is_never_the_branch_that_was_pushed(self):
        """The base is the branch a pull request targets, never the pushed one.

        On a push the branch that was pushed IS the change, and a change
        measured against itself is measured against nothing: the ratchet
        compares the baseline with itself and reports no growth. The name is
        therefore never the branch that was pushed — on a pull request it is
        the target branch, and on a push it is the state the push replaced.
        """
        value = (_workflow().get("env") or {}).get("SGATE_BASE_REF", "")
        self.assertIn("github.base_ref", value)
        self.assertNotIn("github.ref_name", value)


class _Namespace:
    """The GitHub context, as far as the base expression reads it.

    A key that is not there is null rather than an error — that is what
    GitHub does, and the expression leans on it: `github.event.before` does
    not exist on a scheduled run.
    """

    def __init__(self, values: dict) -> None:
        self._values = values

    def __getattr__(self, name: str):
        value = self._values.get(name)
        if isinstance(value, dict):
            return _Namespace(value)
        return value


def _format(template: str, *args) -> str:
    """GitHub's `format`, which is `str.format` under another name."""
    return template.format(*args)


def _evaluate(expression: str, context: dict) -> str:
    """Evaluate the workflow's base expression the way GitHub evaluates it.

    GitHub's `&&` and `||` return an OPERAND rather than a boolean, and
    Python's `and` and `or` do the same — so the translation is faithful for
    the shape of expression the workflow uses. The alternative, asserting on
    the string, would pin the text and not the behaviour: the defect this
    guards against is a push measured against the branch it was pushed to,
    and only the behaviour says whether that happens.
    """
    stripped = expression.strip()
    if stripped.startswith("${{") and stripped.endswith("}}"):
        stripped = stripped[3:-2]
    translated = stripped.replace("&&", " and ").replace("||", " or ")
    namespace = {"__builtins__": {}, "format": _format}
    value = eval(translated, namespace, {"github": _Namespace(context)})  # noqa: S307
    return value or ""


#: The event-to-base mapping, as policy.
#:
#: This is the mapping the workflow's expression encodes, written down: what
#: each event measures against, and why. It is pinned here so that a change to
#: the expression nobody wrote down fails a test instead of shipping, and it is
#: written down in the ADR
#: (`docs/decisions/2026-11-11-the-change-is-measured-against-the-integration-branch.md`)
#: so that the policy has a home outside the expression too.
#:
#: Each row is (event, the payload GitHub sends, the base the expression must
#: name).
BASE_BY_EVENT = (
    (
        "pull request → main",
        {
            "event_name": "pull_request",
            "base_ref": "main",
            "ref": "refs/pull/2852/merge",
            "event": {"before": "abc123"},
        },
        "origin/main",
    ),
    (
        "pull request → dev",
        {
            "event_name": "pull_request",
            "base_ref": "dev",
            "ref": "refs/pull/2852/merge",
            "event": {"before": "abc123"},
        },
        "origin/dev",
    ),
    (
        "push → main",
        {
            "event_name": "push",
            "base_ref": "",
            "ref": "refs/heads/main",
            "event": {"before": "abc123"},
        },
        "abc123",
    ),
    (
        "push → main (the push that created the branch)",
        {
            "event_name": "push",
            "base_ref": "",
            "ref": "refs/heads/main",
            "event": {"before": "0" * 40},
        },
        "0" * 40,
    ),
    (
        "push → dev",
        {
            "event_name": "push",
            "base_ref": "",
            "ref": "refs/heads/dev",
            "event": {"before": "abc123"},
        },
        "origin/main",
    ),
    (
        "schedule",
        {
            "event_name": "schedule",
            "base_ref": "",
            "ref": "refs/heads/main",
            "event": {},
        },
        "origin/main",
    ),
    (
        "manual run",
        {
            "event_name": "workflow_dispatch",
            "base_ref": "",
            "ref": "refs/heads/main",
            "event": {},
        },
        "origin/main",
    ),
)


class TheBaseIsNamedPerEventTest(unittest.TestCase):
    """The base of a change depends on the event that produced it.

    ## The defect this covers

    The workflow named the base for a pull request only. On a push the name
    was empty, the chain in `sgate.gatekit.refs` answered, and the chain's
    first candidate after the push to `dev` is `origin/dev` — which the push
    itself left on the commit that was pushed. The comparison was then the
    change against itself: an empty diff, no work selected, and a run that
    was green because it measured nothing.

    So the name is event-aware: a pull request names the branch it targets,
    a push to the trunk names the commit it replaced, and a push to any
    other branch names the trunk that branch integrates toward.
    """

    ZERO_SHA = "0" * 40

    def _expression(self) -> str:
        return (_workflow().get("env") or {}).get("SGATE_BASE_REF", "")

    def _base_for(self, **context) -> str:
        return _evaluate(self._expression(), context)

    def test_a_pull_request_names_the_branch_it_targets(self):
        base = self._base_for(
            event_name="pull_request",
            base_ref="main",
            ref="refs/pull/2852/merge",
            event={"before": "abc123"},
        )
        self.assertEqual("origin/main", base)

    def test_a_push_to_the_trunk_names_the_commit_it_replaced(self):
        """`origin/main` IS the pushed commit after the push.

        Naming it would measure the push against itself, so the push is
        measured against the state it replaced.
        """
        base = self._base_for(
            event_name="push",
            base_ref="",
            ref="refs/heads/main",
            event={"before": "abc123"},
        )
        self.assertEqual("abc123", base)

    def test_a_push_to_another_branch_names_the_trunk(self):
        """The integration branch is measured against the trunk it integrates toward."""
        base = self._base_for(
            event_name="push",
            base_ref="",
            ref="refs/heads/dev",
            event={"before": "abc123"},
        )
        self.assertEqual("origin/main", base)

    def test_a_push_that_created_the_branch_names_no_previous_state(self):
        """A branch that did not exist before the push has no state to replace.

        The payload says so with the null commit — git's name for no commit at
        all. That is what the workflow names, and it is the honest answer:
        there is no previous state to measure the branch's growth against.

        The tempting alternative was `origin/main`, and it is wrong twice over.
        After the push `origin/main` IS the pushed commit, so the branch would
        be measured against itself — an empty diff and a green run that
        measured nothing. And `origin/main` is not necessarily independent of
        the push at all.
        """
        base = self._base_for(
            event_name="push",
            base_ref="",
            ref="refs/heads/main",
            event={"before": self.ZERO_SHA},
        )
        self.assertEqual(self.ZERO_SHA, base)
        self.assertNotEqual("origin/main", base)

    def test_a_scheduled_run_names_the_trunk(self):
        base = self._base_for(
            event_name="schedule",
            base_ref="",
            ref="refs/heads/main",
            event={},
        )
        self.assertEqual("origin/main", base)

    def test_every_event_names_the_base_the_mapping_says(self):
        """The whole mapping, in one place.

        The expression is a policy engine: it says what each event measures
        against, and a policy nobody wrote down is a policy nobody audits.
        Every event GitHub can send to this workflow is in the table, and the
        table is the one in the ADR.
        """
        for label, context, expected in BASE_BY_EVENT:
            with self.subTest(event=label):
                self.assertEqual(expected, self._base_for(**context))

    def test_a_pull_request_against_the_integration_branch_names_it(self):
        """A pull request against `dev` is measured against `dev`."""
        base = self._base_for(
            event_name="pull_request",
            base_ref="dev",
            ref="refs/pull/2852/merge",
            event={"before": "abc123"},
        )
        self.assertEqual("origin/dev", base)

    def test_a_manual_run_names_the_trunk(self):
        """A run a person started has no event to name a base either."""
        base = self._base_for(
            event_name="workflow_dispatch",
            base_ref="",
            ref="refs/heads/main",
            event={},
        )
        self.assertEqual("origin/main", base)

    def test_no_push_names_the_branch_that_was_pushed(self):
        """The one shape that must never come out of the expression.

        A scheduled run is not a push: it has no change at all, and the trunk
        it runs on is the trunk it is measured against.
        """
        pushed_to_main = self._base_for(
            event_name="push", base_ref="", ref="refs/heads/main", event={"before": "abc"}
        )
        self.assertNotEqual("main", pushed_to_main)
        self.assertNotEqual("origin/main", pushed_to_main)

        pushed_to_dev = self._base_for(
            event_name="push", base_ref="", ref="refs/heads/dev", event={"before": "abc"}
        )
        self.assertNotEqual("dev", pushed_to_dev)
        self.assertNotEqual("origin/dev", pushed_to_dev)


class TheNamedBaseIsResolvedTest(unittest.TestCase):
    """What the workflow names, resolved against a repository that has it.

    The expression test above answers *"what does the workflow name?"*. This
    one answers *"what does that name measure against?"* — two questions, and
    the defect lives in the gap between them. A name that resolves to the
    pushed commit measures the change against itself: the diff is empty, every
    gate that selects work by the changed files finds nothing to do, and the
    run is green because it measured nothing.

    So every case here is checked in the state the event leaves behind: after
    a push to `main`, `origin/main` **is** the pushed commit; after a push to
    `dev`, `origin/dev` is.
    """

    ZERO_SHA = "0" * 40
    BASELINE_REL = pathlib.Path("config/detekt/baseline-shared.xml")

    def setUp(self):
        self.enterContext(no_named_base())
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.repo = pathlib.Path(self._tmp.name) / "repo"
        self.repo.mkdir()
        self.env = sandbox_env(self.repo)
        _git("init", "-b", "main", cwd=self.repo, env=self.env)
        self.baseline = self.repo / self.BASELINE_REL
        self.baseline.parent.mkdir(parents=True, exist_ok=True)
        self.baseline.write_text(self._baseline(2), encoding="utf-8")
        (self.repo / "config/detekt/detekt.yml").write_text(
            "style:\n  active: true\n", encoding="utf-8"
        )
        _git("add", "-A", cwd=self.repo, env=self.env)
        _git("commit", "-m", "the trunk", cwd=self.repo, env=self.env)
        self.trunk = _git_output("rev-parse", "HEAD", cwd=self.repo, env=self.env)
        # The pushed work: the commit the push carried, and HEAD after it.
        (self.repo / "README.md").write_text("the pushed work\n", encoding="utf-8")
        _git("add", "-A", cwd=self.repo, env=self.env)
        _git("commit", "-m", "the pushed work", cwd=self.repo, env=self.env)
        self.pushed = _git_output("rev-parse", "HEAD", cwd=self.repo, env=self.env)
        self._original_root = ratchet.ROOT
        self.addCleanup(self._restore_root)

    def _restore_root(self) -> None:
        ratchet.ROOT = self._original_root

    @staticmethod
    def _baseline(entries: int) -> str:
        body = "\n".join(
            f"    <ID>Rule{i}:File.kt:Fqcn$member{i}</ID>" for i in range(entries)
        )
        return (
            '<?xml version="1.0" ?>\n'
            "<SmellBaseline>\n"
            "  <ManuallySuppressedIssues/>\n"
            "  <CurrentIssues>\n"
            f"{body}\n"
            "  </CurrentIssues>\n"
            "</SmellBaseline>\n"
        )

    def _remote(self, name: str, commit: str) -> None:
        """Put a remote-tracking ref where the event leaves it."""
        _git("update-ref", f"refs/remotes/{name}", commit, cwd=self.repo, env=self.env)

    def _name_for(self, **context) -> str:
        expression = (_workflow().get("env") or {}).get("SGATE_BASE_REF", "")
        return _evaluate(expression, context)

    def _resolved(self, name: str) -> str | None:
        """The commit the named base measures against, or None when it has none."""
        env = dict(self.env)
        if name:
            env[BASE_REF_ENV] = name
        base = resolve_change_base_ref(cwd=self.repo, env=env)
        if base is None:
            return None
        return commit_of(base, cwd=self.repo, env=env)

    def test_a_push_to_the_trunk_is_measured_against_the_commit_it_replaced(self):
        """After a push to `main`, `origin/main` IS the pushed commit.

        The name is the state the push replaced, so the measurement is the
        push's own growth and not the push against itself.
        """
        self._remote("origin/main", self.pushed)
        name = self._name_for(
            event_name="push",
            base_ref="",
            ref="refs/heads/main",
            event={"before": self.trunk},
        )
        self.assertEqual(self.trunk, name)
        self.assertEqual(self.trunk, self._resolved(name))
        self.assertNotEqual(self.pushed, self._resolved(name))

    def test_a_push_to_the_integration_branch_is_measured_against_the_trunk(self):
        """`origin/dev` is the pushed branch — and it points at `HEAD`."""
        self._remote("origin/main", self.trunk)
        self._remote("origin/dev", self.pushed)
        name = self._name_for(
            event_name="push",
            base_ref="",
            ref="refs/heads/dev",
            event={"before": self.trunk},
        )
        self.assertEqual("origin/main", name)
        self.assertEqual(self.trunk, self._resolved(name))
        self.assertNotEqual(self.pushed, self._resolved(name))

    def test_a_pull_request_is_measured_against_the_branch_it_targets(self):
        """Both pull-request targets, and neither of them is the change.

        A pull request against `main` is measured against `main`; one against
        `dev` is measured against `dev`. The branch the pull request comes
        from is the change, and it is never the base.
        """
        self._remote("origin/main", self.trunk)
        self._remote("origin/dev", self.trunk)
        for target in ("main", "dev"):
            with self.subTest(target=target):
                name = self._name_for(
                    event_name="pull_request",
                    base_ref=target,
                    ref="refs/pull/2852/merge",
                    event={"before": "abc123"},
                )
                self.assertEqual(f"origin/{target}", name)
                self.assertEqual(self.trunk, self._resolved(name))
                self.assertNotEqual(self.pushed, self._resolved(name))

    def test_a_run_without_an_event_is_measured_against_the_trunk(self):
        """A scheduled run and a manual run have no event to name a base.

        Both name the trunk, and both are measured against the state the
        trunk is at — which, on the trunk itself, is the change: the work is
        already in the trunk, and there is nothing to measure. That is an
        empty change, not a missing one.
        """
        self._remote("origin/main", self.trunk)
        for event_name in ("schedule", "workflow_dispatch"):
            with self.subTest(event=event_name):
                name = self._name_for(
                    event_name=event_name,
                    base_ref="",
                    ref="refs/heads/main",
                    event={},
                )
                self.assertEqual("origin/main", name)
                self.assertEqual(self.trunk, self._resolved(name))

    def test_a_name_that_is_not_here_is_no_base(self):
        """Every name the workflow can produce, against a checkout without it.

        The name is exclusive: a ref that is not here is not a base, and the
        chain is not consulted in its place. The tools that must measure
        against something say so instead of measuring against something else.
        """
        names = {
            "pull request → main": "origin/main",
            "pull request → dev": "origin/dev",
            "push → main": "f" * 40,
            "push → dev": "origin/main",
            "schedule": "origin/main",
            "manual run": "origin/main",
        }
        for label, name in names.items():
            with self.subTest(event=label):
                self.assertIsNone(self._resolved(name))

    def test_the_gate_fails_closed_when_the_named_base_is_not_here(self):
        """The same names, and the gate's answer to each of them.

        A comparison that cannot be made is a failure, not a green run: the
        baseline grew, the base is not here, and the gate says so with a
        non-zero exit instead of reporting that the baseline did not grow.
        """
        runner = click.testing.CliRunner()
        for label, name in (
            ("pull request → main", "origin/main"),
            ("pull request → dev", "origin/dev"),
            ("push → main", "f" * 40),
            ("push → dev", "origin/main"),
            ("schedule", "origin/main"),
            ("manual run", "origin/main"),
        ):
            with self.subTest(event=label):
                result = runner.invoke(
                    ratchet.cli,
                    ["--root", str(self.repo), "--baseline", str(self.baseline)],
                    env={BASE_REF_ENV: name},
                )
                self.assertNotEqual(0, result.exit_code, result.output)
                self.assertIn("does not exist", result.output)
                self.assertNotIn("unchanged", result.output)

    def test_a_push_that_created_the_branch_has_no_baseline_at_all(self):
        """There is no previous state, so there is nothing to measure against.

        The alternative was `origin/main`, and after such a push `origin/main`
        is the pushed commit: the branch measured against itself, an empty
        diff, and a run that was green because it measured nothing.
        """
        self._remote("origin/main", self.pushed)
        name = self._name_for(
            event_name="push",
            base_ref="",
            ref="refs/heads/main",
            event={"before": self.ZERO_SHA},
        )
        self.assertEqual(self.ZERO_SHA, name)
        self.assertIsNone(self._resolved(name))

    def test_the_gate_fails_closed_when_the_push_created_the_branch(self):
        """A comparison that cannot be made is a failure, not a green run.

        The baseline grew in this sandbox, and the comparison the gate exists
        to make cannot be made at all — so the gate says so with a non-zero
        exit rather than reporting that the baseline did not grow.
        """
        self._remote("origin/main", self.pushed)
        name = self._name_for(
            event_name="push",
            base_ref="",
            ref="refs/heads/main",
            event={"before": self.ZERO_SHA},
        )
        runner = click.testing.CliRunner()
        result = runner.invoke(
            ratchet.cli,
            ["--root", str(self.repo), "--baseline", str(self.baseline)],
            env={BASE_REF_ENV: name},
        )
        self.assertNotEqual(0, result.exit_code, result.output)
        self.assertIn("null commit", result.output)
        self.assertNotIn("unchanged", result.output)


def _filters(changes_job: dict) -> dict:
    """The paths-filter's filters, parsed."""
    for step in changes_job.get("steps", []) or []:
        if not isinstance(step, dict):
            continue
        if "paths-filter" not in str(step.get("uses", "")):
            continue
        raw = (step.get("with") or {}).get("filters")
        return yaml.safe_load(raw) or {}
    raise AssertionError("the changes job runs no paths filter")


if __name__ == "__main__":
    unittest.main()
