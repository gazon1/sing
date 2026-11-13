"""Tests for the publication workflow of `.github/workflows/ci.yml`.

The publication is a sequence of steps in one file, and the file is the
workflow: the steps are not called by anything, they are *run* by GitHub
Actions. A unit test of the helpers cannot say whether the workflow calls
them in the right order, on the right event, with the right permissions —
so this module reads the workflow and pins those properties.

What is pinned, and why each of them matters:

* **the order of the CNAME steps** — the custom domain lives in a file at
  the root of the `gh-pages` branch, and the deploy replaces the branch
  contents with the publish tree. A CNAME that is captured *after* the tree
  changed is a capture of the wrong tree, and a CNAME that is restored after
  the deploy is restored into a branch that has already been published.
  The capture comes first, the restore before the deploy, and the check
  after it;
* **the links are posted on a push only** — a scheduled run publishes the
  tip of the default branch, and the pull request that merged that commit
  is long closed. Its marker comment would be taken over by a run that has
  nothing to do with it;
* **the link step is advisory** — a report that was published must not be
  un-published because a comment did not post;
* **the permissions are minimal and sufficient** — the job writes the
  branch, comments on issues, reads the Pages address and reads the pull
  request. It does not administer the repository, and it does not write
  anywhere else;
* **a re-run is a different publication** — the run attempt is part of the
  publication identity, so a re-run of the same workflow run does not
  collide with the publication it repeats.

Every assertion is about the file as it is, in this repository. A test that
asserts a synthetic workflow proves that the parser reads what it claims to
read, and nothing about the workflow this repository runs.
"""

from __future__ import annotations

import pathlib
import unittest

import yaml

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
WORKFLOW = ROOT / ".github/workflows/ci.yml"

#: The job that publishes the report.
JOB = "publish-allure"


def _workflow() -> dict:
    return yaml.safe_load(WORKFLOW.read_text(encoding="utf-8")) or {}


def _job() -> dict:
    jobs = _workflow().get("jobs") or {}
    return jobs.get(JOB) or {}


def _steps() -> list[dict]:
    return list(_job().get("steps") or [])


def _index_of(name: str) -> int:
    """The position of the step with that name."""
    for index, step in enumerate(_steps()):
        if step.get("name") == name:
            return index
    raise AssertionError(f"the workflow has no step named {name!r}")


def _step(name: str) -> dict:
    return _steps()[_index_of(name)]


def _run_of(name: str) -> str:
    return str(_step(name).get("run") or "")


def _condition_of(name: str) -> str:
    return str(_step(name).get("if") or "")


class JobShapeTest(unittest.TestCase):
    """The job itself: when it runs and with what permissions."""

    def test_the_job_publishes_on_a_push_on_the_default_branch(self):
        condition = str(_job().get("if") or "")
        self.assertIn("github.ref == 'refs/heads/main'", condition)
        self.assertIn("github.event_name == 'push'", condition)

    def test_the_job_publishes_on_the_schedule_and_on_a_manual_run(self):
        condition = str(_job().get("if") or "")
        self.assertIn("github.event_name == 'schedule'", condition)
        self.assertIn("github.event_name == 'workflow_dispatch'", condition)

    def test_the_job_waits_for_the_tests_it_publishes_the_results_of(self):
        needs = _job().get("needs") or []
        self.assertIn("tests", needs)
        condition = str(_job().get("if") or "")
        self.assertIn("needs.tests.result != 'skipped'", condition)

    def test_the_permissions_are_the_ones_the_publication_needs(self):
        permissions = _job().get("permissions") or {}
        self.assertEqual("write", permissions.get("contents"))
        self.assertEqual("read", permissions.get("actions"))
        self.assertEqual("write", permissions.get("issues"))
        self.assertEqual("read", permissions.get("pages"))
        self.assertEqual("write", permissions.get("pull-requests"))

    def test_the_job_may_write_the_comment_on_the_pull_request(self):
        # The link goes on the merged pull request, and a comment on a pull
        # request is not a comment on an issue: the token may not write it
        # without the scope of a pull request. The API answers 403 with
        # `issues: write` alone, and the publication says nothing about the
        # report it published.
        permissions = _job().get("permissions") or {}
        self.assertEqual("write", permissions.get("pull-requests"))
        self.assertIn(
            "scripts/ci/allure_report_link.py",
            _run_of("Link the report on the merged PR and on the issues it closes (advisory)"),
        )

    def test_the_job_may_read_the_deployments_the_check_after_the_deploy_reads(self):
        # The check after the deploy learns the published commit from the
        # Pages deployments, and the API answers 403 without this scope. The
        # scope is named in the job, the reader is named in the step: a check
        # that reads the deployments and a job that may not read them are two
        # halves of one contract.
        permissions = _job().get("permissions") or {}
        self.assertEqual("read", permissions.get("deployments"))
        self.assertIn(
            "scripts/ci/allure_deploy.py",
            _run_of("The custom domain CNAME survived the deploy"),
        )

    def test_the_permissions_are_exactly_the_ones_the_publication_needs(self):
        # The whole set, not the write scopes alone: a scope that is missing
        # is a scope nobody notices until the API answers 403 in the middle
        # of a publication.
        permissions = _job().get("permissions") or {}
        self.assertEqual(
            {
                "contents": "write",
                "actions": "read",
                "issues": "write",
                "pages": "read",
                "pull-requests": "write",
                "deployments": "read",
            },
            permissions,
        )

    def test_the_job_writes_nothing_else(self):
        permissions = _job().get("permissions") or {}
        writable = {name for name, level in permissions.items() if level == "write"}
        self.assertEqual({"contents", "issues", "pull-requests"}, writable)

    def test_the_job_never_administers_the_repository(self):
        permissions = _job().get("permissions") or {}
        self.assertNotIn("administration", permissions)
        self.assertNotIn("workflows", permissions)
        self.assertNotIn("packages", permissions)
        self.assertNotIn("id-token", permissions)


class StepOrderTest(unittest.TestCase):
    """The order the CNAME is captured, restored and checked in."""

    def test_the_cname_is_captured_before_the_tree_is_changed(self):
        capture = _index_of("Capture the custom domain CNAME")
        drop = _index_of("Drop the legacy root reports")
        self.assertLess(capture, drop)

    def test_the_cname_is_restored_into_the_publish_tree_before_the_deploy(self):
        preserve = _index_of("Preserve custom domain CNAME")
        deploy = _index_of("Publish Allure report to GitHub Pages")
        self.assertLess(preserve, deploy)

    def test_the_publish_tree_is_checked_before_the_deploy(self):
        check = _index_of("The custom domain CNAME is still in the publish tree")
        deploy = _index_of("Publish Allure report to GitHub Pages")
        self.assertLess(check, deploy)

    def test_the_deployed_branch_is_checked_after_the_deploy(self):
        deploy = _index_of("Publish Allure report to GitHub Pages")
        survived = _index_of("The custom domain CNAME survived the deploy")
        self.assertLess(deploy, survived)

    def test_the_state_is_validated_before_the_deploy(self):
        validate = _index_of("Validate publication state before deploy")
        deploy = _index_of("Publish Allure report to GitHub Pages")
        self.assertLess(validate, deploy)

    def test_the_links_are_posted_after_the_report_is_published(self):
        deploy = _index_of("Publish Allure report to GitHub Pages")
        link = _index_of(
            "Link the report on the merged PR and on the issues it closes (advisory)"
        )
        self.assertLess(deploy, link)


class PostDeployCheckTest(unittest.TestCase):
    """The check that reads the branch back after the deploy."""

    def test_the_check_waits_for_the_deployed_revision(self):
        run = _run_of("The custom domain CNAME survived the deploy")
        self.assertIn("allure_deploy.py verify-cname", run)

    def test_the_check_names_the_deployment_by_its_identifier(self):
        # The deployment of this publication is the one whose identifier the
        # snapshot taken before the deploy does not name. A moment cannot do
        # that: the API answers `created_at` with a second of precision.
        run = _run_of("The custom domain CNAME survived the deploy")
        self.assertIn("--snapshot-file deployments.before.json", run)
        self.assertNotIn("--since", run)

    def test_the_check_is_about_the_branch_that_was_published(self):
        run = _run_of("The custom domain CNAME survived the deploy")
        self.assertIn("--branch gh-pages", run)

    def test_the_check_compares_against_the_captured_cname(self):
        run = _run_of("The custom domain CNAME survived the deploy")
        self.assertIn("--expected-file cname.expected", run)

    def test_the_deployments_are_snapshotted_before_the_deploy(self):
        snapshot = _index_of("Snapshot the Pages deployments")
        deploy = _index_of("Publish Allure report to GitHub Pages")
        self.assertLess(snapshot, deploy)
        run = _run_of("Snapshot the Pages deployments")
        self.assertIn("allure_deploy.py snapshot-deployments", run)
        self.assertIn("--output deployments.before.json", run)

    def test_the_deploy_start_is_marked_before_the_deploy(self):
        mark = _index_of("Mark deploy start")
        deploy = _index_of("Publish Allure report to GitHub Pages")
        self.assertLess(mark, deploy)
        self.assertIn("ts=", _run_of("Mark deploy start"))

    def test_the_check_is_bound_to_the_publication_that_is_verified(self):
        condition = _condition_of("The custom domain CNAME survived the deploy")
        self.assertIn("steps.decide.outputs.action == 'publish'", condition)


class ReportLinkStepTest(unittest.TestCase):
    """The step that posts the links."""

    def test_the_links_are_posted_on_a_push_only(self):
        condition = _condition_of(
            "Link the report on the merged PR and on the issues it closes (advisory)"
        )
        self.assertIn("github.event_name == 'push'", condition)
        self.assertNotIn("schedule", condition)

    def test_the_links_are_posted_for_a_publication_only(self):
        condition = _condition_of(
            "Link the report on the merged PR and on the issues it closes (advisory)"
        )
        self.assertIn("steps.decide.outputs.action == 'publish'", condition)

    def test_the_step_is_advisory(self):
        step = _step("Link the report on the merged PR and on the issues it closes (advisory)")
        self.assertIs(True, step.get("continue-on-error"))

    def test_the_step_runs_the_helper(self):
        run = _run_of("Link the report on the merged PR and on the issues it closes (advisory)")
        self.assertIn("allure_report_link.py", run)


class PublicationIdentityTest(unittest.TestCase):
    """A re-run is a publication of its own."""

    def test_the_run_attempt_is_part_of_the_artifact_name(self):
        env = _job().get("env") or {}
        self.assertIn("github.run_attempt", str(env.get("ALLURE_PAGES_INPUT") or ""))

    def test_the_decision_carries_the_run_attempt(self):
        step = _step("Decide publication state")
        self.assertIn("--run-attempt", str(step.get("run") or ""))
        self.assertIn("github.run_attempt", str((step.get("env") or {}).get("RUN_ATTEMPT") or ""))

    def test_the_published_state_carries_the_run_attempt(self):
        step = _step("Apply publication state (monotonic/idempotent)")
        self.assertIn("--run-attempt", str(step.get("run") or ""))
        self.assertIn("github.run_attempt", str((step.get("env") or {}).get("RUN_ATTEMPT") or ""))


if __name__ == "__main__":
    unittest.main()
