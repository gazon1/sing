#!/usr/bin/env python3
"""test_ci_pr_path.py — what the pull-request path runs, and what it does not.

The pull request is the path that matters: it is where a change is judged, and
its cost decides whether a reviewer waits. This file reads
`.github/workflows/ci.yml` as TEXT — the way the repository's gates do — and
pins the shape of that path:

* the pull-request path runs its tests WITHOUT the Kover agent, and names no
  APP coverage task: a coverage verdict on a pull request would be a verdict
  about a measurement the pull request never took;
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

import json
import pathlib
import re
import sys
import unittest

import yaml

SCRIPTS = pathlib.Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS / "src"))

import workflow_gradle as wg  # noqa: E402 — the path entry above is the reason

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
    """The run texts of a job, one per matrix leg.

    A matrix leg is a real invocation: `${{ matrix.tasks }}` names the tasks
    that leg runs, and a reader that cannot see them cannot say what the job
    runs. So the expression is replaced with the leg's own value — a task
    hidden behind a matrix is still a task the job runs.
    """
    legs = _matrix_legs(job)
    texts: list[str] = []
    for step in job.get("steps", []) or []:
        if not isinstance(step, dict):
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
        job asks for the other side of it.
        """
        settings = SETTINGS.read_text(encoding="utf-8")

        # The switch is a property, and it is read from the property — not a
        # constant someone flipped by hand.
        self.assertIn('providers.gradleProperty("coverage.enabled")', settings)

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

        # The coverage job opts IN, explicitly.
        self.assertIn("-Pcoverage.enabled=true", _job_text(jobs["coverage"]))

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
        """Only the coverage job names an app coverage task.

        The app's coverage tasks are the ROOT ones — `koverReport`,
        `koverHtml`, `:koverVerify`. A pull request that failed a coverage
        floor would be failing on a measurement it never took.

        The rules' own floor (`:detekt-rules:koverVerify`) is the exception,
        and it is a deliberate one: the custom rules apply the Kover plugin
        themselves, and their branch-coverage floor is a gate the pull-request
        path enforces.
        """
        naming = {
            name for name, job in _jobs().items() if _coverage_tasks(_job_text(job))
        }
        self.assertEqual({"coverage"}, naming)

        # And the exception is still there: the rules' floor is enforced on
        # the pull-request path, so the path carries a measurement it DOES
        # enforce.
        self.assertIn(RULES_FLOOR_TASK, _job_text(_jobs()["tests"]))

    def test_a_pull_request_reports_no_coverage_verdict(self):
        """An ordinary pull request gets no coverage verdict; a labelled one does.

        The coverage job's own condition IS the verdict's condition: it runs
        where a coverage measurement is wanted — a push to the trunk, the
        schedule, a manual run, or a pull request that asks for the full
        population — and not on an ordinary pull request.
        """
        jobs = _jobs()
        condition = str(jobs["coverage"].get("if", ""))

        self.assertIn("github.event_name == 'push'", condition)
        self.assertIn("github.event_name == 'schedule'", condition)

        # The pull request case exists, and the label is what it asks for. The
        # label arrives two ways — the `labeled` event carries it in
        # `github.event.label.name`, every later push carries it in the PR's
        # label list — and reading only one of them means the label works
        # until the next commit and then silently stops working.
        self.assertRegex(
            condition,
            r"(?s)github\.event_name\s*==\s*'pull_request'.{0,400}run-full-tests",
        )
        self.assertIn("github.event.label.name", condition)
        self.assertIn("github.event.pull_request.labels", condition)

        # The job that carries the verdict is one the merge gate waits for —
        # otherwise a coverage breach on a labelled pull request would report
        # green.
        self.assertIn("coverage", _needs(jobs["ci-gate"]))


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
            ":androidApp:test",
            ":detekt-rules:test",
        ):
            self.assertIn(task, suite_run)

        # The ADR modules run in their own invocation and the ADR job owns
        # them: naming them here would put a module in two jobs' worth of
        # coverage, and the floor would move.
        self.assertNotIn(":adr-core:jvmTest", suite_run)

        # The non-test parts of the invocation stay: the jar the end-to-end
        # test resolves (a silent pass when it is absent) and the tool-surface
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
        """
        modules = _detekt_modules()
        judging: set[str] = set()
        elsewhere: set[tuple[str, str]] = set()
        for name, job in _jobs().items():
            if not _is_pr_path(job):
                continue
            for text in _effective_runs(job):
                for invocation in wg.iter_invocations(text, name):
                    for task in invocation.tasks:
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
        # read, and the workflow they read.
        filters = _filters(jobs["changes"])
        self.assertEqual({"scripts/**", "config/**", ".github/**"}, set(filters["gates"]))

        # The `changes` job publishes the answer the condition reads.
        self.assertIn("gates", jobs["changes"].get("outputs", {}))


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
