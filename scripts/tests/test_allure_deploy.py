import contextlib
import importlib.util
import io
import json
import pathlib
import sys
import tempfile
import unittest
from unittest import mock

CI_DIR = pathlib.Path(__file__).resolve().parent.parent / "ci"
sys.path.insert(0, str(CI_DIR))

MODULE_PATH = CI_DIR / "allure_deploy.py"
spec = importlib.util.spec_from_file_location("allure_deploy", MODULE_PATH)
ad = importlib.util.module_from_spec(spec)
sys.modules["allure_deploy"] = ad
spec.loader.exec_module(ad)


class FakeGh:
    """A `gh api` that answers from a canned table and records its calls.

    An answer may be a list: the first entry answers the first call, and
    the last entry answers every call after that. That is how a branch
    that catches up with the push looks from the outside.
    """

    def __init__(self, responses=None, errors=None):
        self.responses = responses or {}
        self.errors = errors or {}
        self.calls = []
        self.seen = {}

    def __call__(self, args):
        self.calls.append(list(args))
        joined = " ".join(args)
        for key, message in self.errors.items():
            if key in joined:
                raise ad.LinkError(message)
        for key, value in self.responses.items():
            if key in joined:
                if isinstance(value, tuple):
                    used = min(self.seen.get(key, 0), len(value) - 1)
                    self.seen[key] = self.seen.get(key, 0) + 1
                    return json.dumps(value[used])
                return json.dumps(value)
        return "[]"

    def calls_to(self, needle):
        return [call for call in self.calls if needle in " ".join(call)]


def _deployment(sha, created_at="2026-11-12T15:40:59Z", identifier=None):
    return {
        "id": identifier,
        "sha": sha,
        "created_at": created_at,
        "ref": "gh-pages",
    }


def _branch(sha):
    return {"object": {"sha": sha}}


def _file(content):
    import base64

    return {"content": base64.b64encode(content.encode()).decode(), "encoding": "base64"}


class SnapshotTest(unittest.TestCase):
    """The record of the deployments that existed before the publish."""

    def test_the_identifiers_are_read_from_the_snapshot(self):
        with tempfile.TemporaryDirectory() as folder:
            path = pathlib.Path(folder) / "deployments.before.json"
            path.write_text(json.dumps([11, "12"]), encoding="utf-8")
            self.assertEqual(ad.read_snapshot_ids(str(path)), {"11", "12"})

    def test_a_snapshot_that_is_not_a_list_is_refused(self):
        with tempfile.TemporaryDirectory() as folder:
            path = pathlib.Path(folder) / "deployments.before.json"
            path.write_text(json.dumps({"id": 11}), encoding="utf-8")
            with self.assertRaises(ValueError):
                ad.read_snapshot_ids(str(path))

    def test_the_identifiers_of_the_deployments_are_collected(self):
        deployments = [
            _deployment("aaa", identifier=11),
            _deployment("bbb", identifier=12),
            _deployment("ccc", identifier=None),
        ]
        self.assertEqual(ad.deployment_ids(deployments), {"11", "12"})


class NewestDeploymentTest(unittest.TestCase):
    """Which deployment belongs to the publication that is verified."""

    def test_the_newest_deployment_wins(self):
        deployments = [
            _deployment("aaa", "2026-11-12T15:40:59Z", identifier=11),
            _deployment("bbb", "2026-11-12T16:40:59Z", identifier=12),
        ]
        found = ad.newest_deployment(deployments, set())
        self.assertEqual(found["sha"], "bbb")

    def test_a_deployment_the_snapshot_names_is_not_ours(self):
        deployments = [_deployment("aaa", "2026-11-12T15:40:59Z", identifier=11)]
        self.assertIsNone(ad.newest_deployment(deployments, {"11"}))

    def test_a_deployment_after_the_publication_belongs_to_it(self):
        deployments = [_deployment("bbb", "2026-11-12T16:40:59Z", identifier=12)]
        found = ad.newest_deployment(deployments, {"11"})
        self.assertEqual(found["sha"], "bbb")

    def test_a_deployment_without_a_commit_names_nothing(self):
        deployments = [{"id": 12, "created_at": "2026-11-12T16:40:59Z"}]
        self.assertIsNone(ad.newest_deployment(deployments, set()))

    def test_a_deployment_without_an_identifier_names_nothing(self):
        deployments = [_deployment("aaa", "2026-11-12T16:40:59Z", identifier=None)]
        self.assertIsNone(ad.newest_deployment(deployments, set()))

    def test_the_previous_deployment_of_the_same_second_is_not_ours(self):
        """The race the snapshot was taken to end.

        The previous publication finished in the very second this one
        started, and the API answers `created_at` with a second of
        precision — so a moment cannot tell the two apart. The identifier
        can: the previous deployment is one the snapshot names.
        """
        previous = _deployment("aaa", "2026-11-12T16:00:00Z", identifier=11)
        self.assertIsNone(ad.newest_deployment([previous], {"11"}))

    def test_the_new_deployment_is_ours_even_when_the_moment_is_the_same(self):
        previous = _deployment("aaa", "2026-11-12T16:00:00Z", identifier=11)
        current = _deployment("bbb", "2026-11-12T16:00:00Z", identifier=12)
        found = ad.newest_deployment([previous, current], {"11"})
        self.assertEqual(found["sha"], "bbb")


class WaitTest(unittest.TestCase):
    """Waiting for the deployed revision."""

    def test_the_branch_that_holds_the_deployed_commit_is_reached(self):
        gh = FakeGh(
            {
                "deployments": [_deployment("aaa", identifier=12)],
                "git/ref/heads/gh-pages": _branch("aaa"),
            }
        )
        state = ad.wait_for_deployed_revision(
            "gazon1/sing", "gh-pages", gh, set(), sleep=lambda _: None
        )
        self.assertEqual(state["status"], "reached")
        self.assertEqual(state["sha"], "aaa")

    def test_the_api_that_still_shows_the_old_commit_is_waited_for(self):
        gh = FakeGh(
            {
                "deployments": [_deployment("bbb", identifier=12)],
                "git/ref/heads/gh-pages": (_branch("aaa"), _branch("bbb")),
            }
        )
        state = ad.wait_for_deployed_revision(
            "gazon1/sing", "gh-pages", gh, set(), sleep=lambda _: None
        )
        self.assertEqual(state["status"], "reached")
        self.assertEqual(state["attempts"], 2)

    def test_a_branch_that_never_catches_up_is_stale(self):
        gh = FakeGh(
            {
                "deployments": [_deployment("bbb", identifier=12)],
                "git/ref/heads/gh-pages": _branch("aaa"),
            }
        )
        state = ad.wait_for_deployed_revision(
            "gazon1/sing", "gh-pages", gh, set(), attempts=3, sleep=lambda _: None
        )
        self.assertEqual(state["status"], "stale")
        self.assertEqual(state["expected"], "bbb")
        self.assertEqual(state["sha"], "aaa")

    def test_the_attempts_that_were_needed_are_reported(self):
        gh = FakeGh(
            {
                "deployments": [_deployment("bbb", identifier=12)],
                "git/ref/heads/gh-pages": (_branch("aaa"), _branch("bbb")),
            }
        )
        state = ad.wait_for_deployed_revision(
            "gazon1/sing", "gh-pages", gh, set(), attempts=5, sleep=lambda _: None
        )
        self.assertEqual(state["status"], "reached")
        self.assertEqual(state["attempts"], 2)

    def test_a_wait_that_was_asked_for_nothing_still_makes_one_attempt(self):
        gh = FakeGh(
            {
                "deployments": [_deployment("bbb", identifier=12)],
                "git/ref/heads/gh-pages": _branch("aaa"),
            }
        )
        state = ad.wait_for_deployed_revision(
            "gazon1/sing", "gh-pages", gh, set(), attempts=0, sleep=lambda _: None
        )
        self.assertEqual(state["status"], "stale")
        self.assertEqual(state["attempts"], 1)

    def test_a_branch_without_a_deployment_is_stale(self):
        gh = FakeGh({"git/ref/heads/gh-pages": _branch("aaa")})
        state = ad.wait_for_deployed_revision(
            "gazon1/sing", "gh-pages", gh, set(), attempts=2, sleep=lambda _: None
        )
        self.assertEqual(state["status"], "stale")
        self.assertIsNone(state["expected"])

    def test_an_api_that_does_not_answer_is_a_failure_of_its_own(self):
        gh = FakeGh({}, errors={"deployments": "gh: API rate limit exceeded (HTTP 403)"})
        with self.assertRaises(ad.LinkError):
            ad.wait_for_deployed_revision(
                "gazon1/sing", "gh-pages", gh, set(), sleep=lambda _: None
            )

    def test_the_previous_deployment_of_the_same_second_is_waited_for(self):
        """The check waits for a new identifier instead of accepting the old one."""
        previous = _deployment("aaa", "2026-11-12T16:00:00Z", identifier=11)
        current = _deployment("bbb", "2026-11-12T16:00:00Z", identifier=12)
        gh = FakeGh(
            {
                "deployments": ([previous], [previous, current]),
                "git/ref/heads/gh-pages": (_branch("aaa"), _branch("bbb")),
            }
        )
        state = ad.wait_for_deployed_revision(
            "gazon1/sing", "gh-pages", gh, {"11"}, attempts=3, sleep=lambda _: None
        )
        self.assertEqual(state["status"], "reached")
        self.assertEqual(state["sha"], "bbb")
        self.assertEqual(state["attempts"], 2)


class CnameTest(unittest.TestCase):
    """What the deployed CNAME says about the captured one."""

    def _gh(self, deployed):
        return FakeGh(
            {
                "deployments": [_deployment("aaa", identifier=12)],
                "git/ref/heads/gh-pages": _branch("aaa"),
                "contents/CNAME": deployed,
            }
        )

    def test_a_cname_that_survived_the_deploy_matches(self):
        gh = self._gh(_file("maxdrobin.ru\n"))
        result = ad.verify_cname(
            "gazon1/sing", "gh-pages", "maxdrobin.ru\n", set(), gh, sleep=lambda _: None
        )
        self.assertEqual(result["status"], "match")

    def test_a_cname_that_the_deploy_dropped_is_missing(self):
        gh = self._gh(None)
        result = ad.verify_cname(
            "gazon1/sing", "gh-pages", "maxdrobin.ru\n", set(), gh, sleep=lambda _: None
        )
        self.assertEqual(result["status"], "missing")

    def test_a_cname_that_changed_is_a_mismatch(self):
        gh = self._gh(_file("example.com\n"))
        result = ad.verify_cname(
            "gazon1/sing", "gh-pages", "maxdrobin.ru\n", set(), gh, sleep=lambda _: None
        )
        self.assertEqual(result["status"], "mismatch")

    def test_a_branch_without_a_cname_has_nothing_to_lose(self):
        gh = self._gh(None)
        result = ad.verify_cname(
            "gazon1/sing", "gh-pages", None, set(), gh, sleep=lambda _: None
        )
        self.assertEqual(result["status"], "not-expected")

    def test_a_branch_without_a_cname_is_not_asked_about_the_deployment(self):
        gh = self._gh(None)
        result = ad.verify_cname(
            "gazon1/sing", "gh-pages", None, set(), gh, sleep=lambda _: None
        )
        self.assertEqual(result["status"], "not-expected")
        self.assertEqual(result["attempts"], 0)
        self.assertEqual(gh.calls, [])

    def test_a_branch_that_never_catches_up_is_stale_and_not_a_missing_cname(self):
        gh = FakeGh(
            {
                "deployments": [_deployment("bbb", identifier=12)],
                "git/ref/heads/gh-pages": _branch("aaa"),
                "contents/CNAME": _file("maxdrobin.ru\n"),
            }
        )
        result = ad.verify_cname(
            "gazon1/sing",
            "gh-pages",
            "maxdrobin.ru\n",
            set(),
            gh,
            attempts=2,
            sleep=lambda _: None,
        )
        self.assertEqual(result["status"], "stale")

    def test_the_old_deployment_does_not_answer_the_check(self):
        """The previous deployment of the same second is not the published one."""
        previous = _deployment("aaa", "2026-11-12T16:00:00Z", identifier=11)
        gh = FakeGh(
            {
                "deployments": [previous],
                "git/ref/heads/gh-pages": _branch("aaa"),
                "contents/CNAME": _file("maxdrobin.ru\n"),
            }
        )
        result = ad.verify_cname(
            "gazon1/sing",
            "gh-pages",
            "maxdrobin.ru\n",
            {"11"},
            gh,
            attempts=2,
            sleep=lambda _: None,
        )
        self.assertEqual(result["status"], "stale")
        self.assertEqual(result["expected"], None)


class CliTest(unittest.TestCase):
    """The command the workflow runs."""

    def test_the_snapshot_command_records_the_identifiers(self):
        gh = FakeGh(
            {
                "deployments": [
                    _deployment("aaa", identifier=11),
                    _deployment("bbb", identifier=12),
                ]
            }
        )
        with tempfile.TemporaryDirectory() as folder:
            output = pathlib.Path(folder) / "deployments.before.json"
            with mock.patch.object(ad, "gh_api", gh), contextlib.redirect_stdout(io.StringIO()):
                code = ad.main(
                    [
                        "snapshot-deployments",
                        "--repo",
                        "gazon1/sing",
                        "--output",
                        str(output),
                    ]
                )
            self.assertEqual(code, 0)
            self.assertEqual(json.loads(output.read_text(encoding="utf-8")), ["11", "12"])

    def test_without_a_capture_the_command_reports_nothing_to_lose(self):
        gh = FakeGh(
            {
                "deployments": [_deployment("aaa", identifier=12)],
                "git/ref/heads/gh-pages": _branch("aaa"),
            }
        )
        with tempfile.TemporaryDirectory() as folder:
            snapshot = pathlib.Path(folder) / "deployments.before.json"
            snapshot.write_text(json.dumps([]), encoding="utf-8")
            with mock.patch.object(ad, "gh_api", gh), contextlib.redirect_stdout(io.StringIO()):
                code = ad.main(
                    [
                        "verify-cname",
                        "--repo",
                        "gazon1/sing",
                        "--snapshot-file",
                        str(snapshot),
                        "--attempts",
                        "1",
                        "--delay",
                        "0",
                    ]
                )
        self.assertEqual(code, 0)

    def test_without_a_snapshot_the_command_refuses_to_run(self):
        gh = FakeGh({"deployments": [_deployment("aaa", identifier=12)]})
        out = io.StringIO()
        with mock.patch.object(ad, "gh_api", gh), contextlib.redirect_stdout(out):
            code = ad.main(["verify-cname", "--repo", "gazon1/sing"])
        self.assertEqual(code, 1)
        self.assertEqual(json.loads(out.getvalue())["status"], "no-snapshot")
        self.assertEqual(gh.calls, [])

    def test_a_snapshot_that_cannot_be_read_is_refused(self):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            code = ad.main(
                [
                    "verify-cname",
                    "--repo",
                    "gazon1/sing",
                    "--snapshot-file",
                    "/nonexistent/deployments.before.json",
                ]
            )
        self.assertEqual(code, 1)
        self.assertEqual(json.loads(out.getvalue())["status"], "no-snapshot")

    def test_a_missing_capture_file_is_not_a_failure(self):
        self.assertIsNone(ad._expected_cname("/nonexistent/cname.expected"))


if __name__ == "__main__":
    unittest.main()
