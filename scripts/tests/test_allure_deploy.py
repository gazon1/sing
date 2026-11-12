import contextlib
import importlib.util
import io
import json
import pathlib
import sys
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


def _deployment(sha, created_at="2026-11-12T15:40:59Z"):
    return {"sha": sha, "created_at": created_at, "ref": "gh-pages"}


def _branch(sha):
    return {"object": {"sha": sha}}


def _file(content):
    import base64

    return {"content": base64.b64encode(content.encode()).decode(), "encoding": "base64"}


class NewestDeploymentTest(unittest.TestCase):
    """Which deployment belongs to the publication that is verified."""

    def test_the_newest_deployment_wins(self):
        deployments = [
            _deployment("aaa", "2026-11-12T15:40:59Z"),
            _deployment("bbb", "2026-11-12T16:40:59Z"),
        ]
        self.assertEqual(ad.newest_deployment(deployments)["sha"], "bbb")

    def test_a_deployment_of_an_earlier_publication_is_not_ours(self):
        deployments = [_deployment("aaa", "2026-11-12T15:40:59Z")]
        self.assertIsNone(
            ad.newest_deployment(deployments, since="2026-11-12T16:00:00Z")
        )

    def test_a_deployment_after_the_publication_belongs_to_it(self):
        deployments = [_deployment("bbb", "2026-11-12T16:40:59Z")]
        found = ad.newest_deployment(deployments, since="2026-11-12T16:00:00Z")
        self.assertEqual(found["sha"], "bbb")

    def test_a_deployment_without_a_commit_names_nothing(self):
        self.assertIsNone(ad.newest_deployment([{"created_at": "2026-11-12T16:40:59Z"}]))


class WaitTest(unittest.TestCase):
    """Waiting for the deployed revision."""

    def test_the_branch_that_holds_the_deployed_commit_is_reached(self):
        gh = FakeGh(
            {
                "deployments": [_deployment("aaa")],
                "git/ref/heads/gh-pages": _branch("aaa"),
            }
        )
        state = ad.wait_for_deployed_revision("gazon1/sing", "gh-pages", gh, sleep=lambda _: None)
        self.assertEqual(state["status"], "reached")
        self.assertEqual(state["sha"], "aaa")

    def test_the_api_that_still_shows_the_old_commit_is_waited_for(self):
        gh = FakeGh(
            {
                "deployments": [_deployment("bbb")],
                "git/ref/heads/gh-pages": (_branch("aaa"), _branch("bbb")),
            }
        )
        state = ad.wait_for_deployed_revision("gazon1/sing", "gh-pages", gh, sleep=lambda _: None)
        self.assertEqual(state["status"], "reached")
        self.assertEqual(state["attempts"], 2)

    def test_a_branch_that_never_catches_up_is_stale(self):
        gh = FakeGh(
            {
                "deployments": [_deployment("bbb")],
                "git/ref/heads/gh-pages": _branch("aaa"),
            }
        )
        state = ad.wait_for_deployed_revision(
            "gazon1/sing", "gh-pages", gh, attempts=3, sleep=lambda _: None
        )
        self.assertEqual(state["status"], "stale")
        self.assertEqual(state["expected"], "bbb")
        self.assertEqual(state["sha"], "aaa")

    def test_the_attempts_that_were_needed_are_reported(self):
        gh = FakeGh(
            {
                "deployments": [_deployment("bbb")],
                "git/ref/heads/gh-pages": (_branch("aaa"), _branch("bbb")),
            }
        )
        state = ad.wait_for_deployed_revision(
            "gazon1/sing", "gh-pages", gh, attempts=5, sleep=lambda _: None
        )
        self.assertEqual(state["status"], "reached")
        self.assertEqual(state["attempts"], 2)

    def test_a_wait_that_was_asked_for_nothing_still_makes_one_attempt(self):
        gh = FakeGh(
            {
                "deployments": [_deployment("bbb")],
                "git/ref/heads/gh-pages": _branch("aaa"),
            }
        )
        state = ad.wait_for_deployed_revision(
            "gazon1/sing", "gh-pages", gh, attempts=0, sleep=lambda _: None
        )
        self.assertEqual(state["status"], "stale")
        self.assertEqual(state["attempts"], 1)

    def test_a_branch_without_a_deployment_is_stale(self):
        gh = FakeGh({"git/ref/heads/gh-pages": _branch("aaa")})
        state = ad.wait_for_deployed_revision(
            "gazon1/sing", "gh-pages", gh, attempts=2, sleep=lambda _: None
        )
        self.assertEqual(state["status"], "stale")
        self.assertIsNone(state["expected"])

    def test_an_api_that_does_not_answer_is_a_failure_of_its_own(self):
        gh = FakeGh({}, errors={"deployments": "gh: API rate limit exceeded (HTTP 403)"})
        with self.assertRaises(ad.LinkError):
            ad.wait_for_deployed_revision("gazon1/sing", "gh-pages", gh, sleep=lambda _: None)


class CnameTest(unittest.TestCase):
    """What the deployed CNAME says about the captured one."""

    def _gh(self, deployed):
        return FakeGh(
            {
                "deployments": [_deployment("aaa")],
                "git/ref/heads/gh-pages": _branch("aaa"),
                "contents/CNAME": deployed,
            }
        )

    def test_a_cname_that_survived_the_deploy_matches(self):
        gh = self._gh(_file("maxdrobin.ru\n"))
        result = ad.verify_cname("gazon1/sing", "gh-pages", "maxdrobin.ru\n", gh, sleep=lambda _: None)
        self.assertEqual(result["status"], "match")

    def test_a_cname_that_the_deploy_dropped_is_missing(self):
        gh = self._gh(None)
        result = ad.verify_cname("gazon1/sing", "gh-pages", "maxdrobin.ru\n", gh, sleep=lambda _: None)
        self.assertEqual(result["status"], "missing")

    def test_a_cname_that_changed_is_a_mismatch(self):
        gh = self._gh(_file("example.com\n"))
        result = ad.verify_cname("gazon1/sing", "gh-pages", "maxdrobin.ru\n", gh, sleep=lambda _: None)
        self.assertEqual(result["status"], "mismatch")

    def test_a_branch_without_a_cname_has_nothing_to_lose(self):
        gh = self._gh(None)
        result = ad.verify_cname("gazon1/sing", "gh-pages", None, gh, sleep=lambda _: None)
        self.assertEqual(result["status"], "not-expected")

    def test_a_branch_without_a_cname_is_not_asked_about_the_deployment(self):
        gh = self._gh(None)
        result = ad.verify_cname("gazon1/sing", "gh-pages", None, gh, sleep=lambda _: None)
        self.assertEqual(result["status"], "not-expected")
        self.assertEqual(result["attempts"], 0)
        self.assertEqual(gh.calls, [])

    def test_a_branch_that_never_catches_up_is_stale_and_not_a_missing_cname(self):
        gh = FakeGh(
            {
                "deployments": [_deployment("bbb")],
                "git/ref/heads/gh-pages": _branch("aaa"),
                "contents/CNAME": _file("maxdrobin.ru\n"),
            }
        )
        result = ad.verify_cname(
            "gazon1/sing", "gh-pages", "maxdrobin.ru\n", gh, attempts=2, sleep=lambda _: None
        )
        self.assertEqual(result["status"], "stale")


class CliTest(unittest.TestCase):
    """The command the workflow runs."""

    def test_without_a_capture_the_command_reports_nothing_to_lose(self):
        gh = FakeGh(
            {
                "deployments": [_deployment("aaa")],
                "git/ref/heads/gh-pages": _branch("aaa"),
            }
        )
        with mock.patch.object(ad, "gh_api", gh), contextlib.redirect_stdout(io.StringIO()):
            code = ad.main(["verify-cname", "--repo", "gazon1/sing", "--attempts", "1", "--delay", "0"])
        self.assertEqual(code, 0)

    def test_a_missing_capture_file_is_not_a_failure(self):
        self.assertIsNone(ad._expected_cname("/nonexistent/cname.expected"))


if __name__ == "__main__":
    unittest.main()
