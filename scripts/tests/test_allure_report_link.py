import importlib.util
import json
import pathlib
import sys
import unittest

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / "ci" / "allure_report_link.py"
spec = importlib.util.spec_from_file_location("allure_report_link", MODULE_PATH)
arl = importlib.util.module_from_spec(spec)
sys.modules["allure_report_link"] = arl
spec.loader.exec_module(arl)


class FakeGh:
    """A `gh api` that answers from a canned table and records its calls."""

    def __init__(self, responses=None):
        self.responses = responses or {}
        self.calls = []

    def __call__(self, args):
        self.calls.append(list(args))
        joined = " ".join(args)
        for key, value in self.responses.items():
            if key in joined:
                return json.dumps(value)
        return "[]"

    def calls_to(self, needle):
        return [call for call in self.calls if needle in " ".join(call)]


class ClosingKeywordTest(unittest.TestCase):
    """Which issues a pull-request body closes."""

    def test_a_closing_keyword_closes_the_number_that_follows_it(self):
        self.assertEqual(arl.parse_closing_issues("Fixes #2729"), [2729])

    def test_a_number_behind_other_words_is_not_closed(self):
        # `Fixes #2729 and implements #2737` closes one issue, not two.
        self.assertEqual(arl.parse_closing_issues("Fixes #2729 and implements #2737"), [2729])

    def test_refs_is_not_a_closing_keyword(self):
        self.assertEqual(arl.parse_closing_issues("Refs #2828"), [])

    def test_a_keyword_may_close_a_list(self):
        # GitHub closes every issue of the list: the merge of #275 closed all
        # four issues its body named that way.
        self.assertEqual(arl.parse_closing_issues("Closes #1, #2"), [1, 2])

    def test_a_colon_after_the_keyword_still_closes(self):
        # `Closes: #10` is GitHub's own documented form.
        self.assertEqual(arl.parse_closing_issues("Closes: #10"), [10])

    def test_the_uppercase_form_closes(self):
        self.assertEqual(arl.parse_closing_issues("CLOSES: #10"), [10])

    def test_a_keyword_in_a_code_block_closes_nothing(self):
        body = "What the body says:\n\n```\nCloses #10\n```\n"
        self.assertEqual(arl.parse_closing_issues(body), [])

    def test_a_keyword_in_an_inline_code_span_closes_nothing(self):
        self.assertEqual(arl.parse_closing_issues("`Closes #10`"), [])

    def test_a_quoted_keyword_closes_nothing(self):
        self.assertEqual(arl.parse_closing_issues("> Closes #10"), [])

    def test_a_keyword_next_to_a_code_block_still_closes(self):
        body = "Fixes #10\n\n```\nCloses #11\n```\n"
        self.assertEqual(arl.parse_closing_issues(body), [10])

    def test_every_closing_keyword_is_recognised(self):
        for keyword in ("Closes", "Fixes", "Resolves", "close", "fixed", "resolved"):
            with self.subTest(keyword=keyword):
                self.assertEqual(arl.parse_closing_issues(f"{keyword} #42"), [42])

    def test_an_empty_body_closes_nothing(self):
        self.assertEqual(arl.parse_closing_issues(""), [])

    def test_the_same_issue_is_named_once(self):
        self.assertEqual(arl.parse_closing_issues("Closes #7\nFixes #7"), [7])


class UrlTest(unittest.TestCase):
    """The URLs the comment carries."""

    def test_the_pages_url_wins_and_loses_its_trailing_slash(self):
        self.assertEqual(
            arl.site_url("http://maxdrobin.ru/sing/", "gazon1", "sing"),
            "http://maxdrobin.ru/sing",
        )

    def test_without_a_pages_url_the_repository_url_is_the_fallback(self):
        self.assertEqual(
            arl.site_url(None, "gazon1", "sing"),
            "https://gazon1.github.io/sing",
        )

    def test_the_run_report_and_the_latest_report_are_both_addressed(self):
        run_url, latest_url = arl.report_links("http://maxdrobin.ru/sing", "fast", "3028")
        self.assertEqual(run_url, "http://maxdrobin.ru/sing/fast/3028/index.html")
        self.assertEqual(latest_url, "http://maxdrobin.ru/sing/fast/index.html")

    def test_the_comment_body_carries_the_marker_and_both_links(self):
        body = arl.comment_body("http://site/fast/1/index.html", "http://site/fast/index.html")
        self.assertIn(arl.MARKER, body)
        self.assertIn("http://site/fast/1/index.html", body)
        self.assertIn("http://site/fast/index.html", body)


class UpsertTest(unittest.TestCase):
    """One comment per target, updated instead of added."""

    def test_a_target_without_the_marker_gets_a_new_comment(self):
        gh = FakeGh({"issues/42/comments": [{"id": 1, "body": "hello", "user": {"login": "someone"}}]})
        action = arl.upsert_comment("gazon1/sing", 42, arl.comment_body("a", "b"), gh)
        self.assertEqual(action, "created")
        self.assertTrue(any("--method" in call and "POST" in call for call in gh.calls))

    def test_a_target_with_the_marker_gets_the_same_comment_updated(self):
        gh = FakeGh(
            {
                "issues/42/comments": [
                    {"id": 9, "body": arl.MARKER + "\nold", "user": {"login": "github-actions[bot]"}}
                ]
            }
        )
        action = arl.upsert_comment("gazon1/sing", 42, arl.comment_body("a", "b"), gh)
        self.assertEqual(action, "updated")
        self.assertTrue(any("issues/comments/9" in " ".join(call) for call in gh.calls))
        self.assertFalse(any("POST" in call for call in gh.calls))

    def test_the_marker_comment_of_the_bot_wins_over_somebody_elses(self):
        comments = [
            {"id": 1, "body": arl.MARKER, "user": {"login": "a-human"}},
            {"id": 2, "body": arl.MARKER, "user": {"login": "github-actions[bot]"}},
        ]
        self.assertEqual(arl.find_marker_comment(comments)["id"], 2)

    def test_a_target_without_any_comment_gets_a_new_comment(self):
        gh = FakeGh()
        action = arl.upsert_comment("gazon1/sing", 42, arl.comment_body("a", "b"), gh)
        self.assertEqual(action, "created")


class PostReportLinkTest(unittest.TestCase):
    """The whole thing, driven by a fake `gh`."""

    def test_a_commit_without_a_pull_request_comments_nothing(self):
        gh = FakeGh({"commits/abc/pulls": []})
        result = arl.post_report_link("gazon1/sing", "fast", "3028", "abc", gh)
        self.assertEqual(result["status"], "no-pull-request")
        self.assertEqual(result["comments"], [])

    def test_the_pull_request_and_the_issues_it_closes_are_commented_on(self):
        gh = FakeGh(
            {
                "repos/gazon1/sing/pages": {"html_url": "http://maxdrobin.ru/sing/"},
                "commits/abc/pulls": [{"number": 42, "body": "Closes #7"}],
                "issues/42/comments": [],
                "issues/7/comments": [
                    {"id": 5, "body": arl.MARKER, "user": {"login": "github-actions[bot]"}}
                ],
            }
        )
        result = arl.post_report_link("gazon1/sing", "fast", "3028", "abc", gh)
        self.assertEqual(result["status"], "commented")
        self.assertEqual(result["pull_request"], 42)
        self.assertEqual(result["issues"], [7])
        self.assertEqual(
            result["comments"],
            [{"target": 42, "action": "created"}, {"target": 7, "action": "updated"}],
        )
        posted = [
            call
            for call in gh.calls_to("issues/42/comments")
            if "POST" in call or "PATCH" in call
        ]
        self.assertTrue(posted)
        self.assertIn("http://maxdrobin.ru/sing/fast/3028/index.html", " ".join(posted[0]))

    def test_the_fallback_url_is_used_when_the_pages_settings_do_not_answer(self):
        gh = FakeGh({"commits/abc/pulls": [{"number": 42, "body": ""}]})
        result = arl.post_report_link("gazon1/sing", "full", "3027", "abc", gh)
        self.assertEqual(result["site"], "https://gazon1.github.io/sing")


class CliTest(unittest.TestCase):
    """The command the workflow runs."""

    def test_without_a_repository_the_command_refuses(self):
        self.assertEqual(arl.main(["--population", "fast", "--run-number", "1", "--sha", "abc"]), 2)

    def test_without_a_population_the_command_refuses(self):
        with self.assertRaises(SystemExit):
            arl.main(["--repo", "gazon1/sing", "--run-number", "1", "--sha", "abc"])


if __name__ == "__main__":
    unittest.main()
