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

    def __init__(self, responses=None, errors=None):
        self.responses = responses or {}
        self.errors = errors or {}
        self.calls = []

    def __call__(self, args):
        self.calls.append(list(args))
        joined = " ".join(args)
        for key, message in self.errors.items():
            if key in joined:
                if "404" in message:
                    raise arl.NotFound(message)
                raise arl.LinkError(message)
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

    def test_a_keyword_in_a_double_backtick_span_closes_nothing(self):
        self.assertEqual(arl.parse_closing_issues("``Closes #10``"), [])

    def test_a_keyword_on_another_line_closes_nothing(self):
        # GitHub reads a keyword and its number on one line.
        self.assertEqual(arl.parse_closing_issues("Closes\n#10"), [])

    def test_the_number_zero_names_no_issue(self):
        self.assertEqual(arl.parse_closing_issues("Closes #0"), [])

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
        self.assertEqual(arl.site_url("http://maxdrobin.ru/sing/"), "http://maxdrobin.ru/sing")

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

    def test_a_comment_of_a_human_is_not_ours_to_update(self):
        comments = [{"id": 1, "body": arl.MARKER, "user": {"login": "a-human"}}]
        self.assertIsNone(arl.find_marker_comment(comments))

    def test_a_comment_of_another_bot_is_not_ours_to_update(self):
        comments = [{"id": 1, "body": arl.MARKER, "user": {"login": "dependabot[bot]"}}]
        self.assertIsNone(arl.find_marker_comment(comments))

    def test_the_newest_of_our_own_comments_wins(self):
        comments = [
            {
                "id": 2,
                "body": arl.MARKER,
                "created_at": "2026-11-12T10:00:00Z",
                "user": {"login": "github-actions[bot]"},
            },
            {
                "id": 3,
                "body": arl.MARKER,
                "created_at": "2026-11-12T11:00:00Z",
                "user": {"login": "github-actions[bot]"},
            },
        ]
        self.assertEqual(arl.find_marker_comment(comments)["id"], 3)

    def test_the_choice_does_not_depend_on_the_order_the_api_answered_in(self):
        older = {
            "id": 2,
            "body": arl.MARKER,
            "created_at": "2026-11-12T10:00:00Z",
            "user": {"login": "github-actions[bot]"},
        }
        newer = {
            "id": 3,
            "body": arl.MARKER,
            "created_at": "2026-11-12T11:00:00Z",
            "user": {"login": "github-actions[bot]"},
        }
        self.assertEqual(arl.find_marker_comment([older, newer])["id"], 3)
        self.assertEqual(arl.find_marker_comment([newer, older])["id"], 3)

    def test_a_human_comment_with_the_marker_is_never_edited(self):
        gh = FakeGh(
            {
                "issues/42/comments": [
                    {"id": 9, "body": arl.MARKER + "\nhello", "user": {"login": "a-human"}}
                ]
            }
        )
        action = arl.upsert_comment("gazon1/sing", 42, arl.comment_body("a", "b"), gh)
        self.assertEqual(action, "created")
        self.assertFalse(any("PATCH" in call for call in gh.calls))
        self.assertTrue(any("--method" in call and "POST" in call for call in gh.calls))

    def test_our_own_comment_is_updated_and_the_human_one_is_left_alone(self):
        gh = FakeGh(
            {
                "issues/42/comments": [
                    {"id": 9, "body": arl.MARKER + "\nhello", "user": {"login": "a-human"}},
                    {
                        "id": 10,
                        "body": arl.MARKER + "\nold",
                        "created_at": "2026-11-12T10:00:00Z",
                        "user": {"login": "github-actions[bot]"},
                    },
                ]
            }
        )
        action = arl.upsert_comment("gazon1/sing", 42, arl.comment_body("a", "b"), gh)
        self.assertEqual(action, "updated")
        self.assertTrue(any("issues/comments/10" in " ".join(call) for call in gh.calls))
        self.assertFalse(any("issues/comments/9" in " ".join(call) for call in gh.calls))

    def test_a_target_without_any_comment_gets_a_new_comment(self):
        gh = FakeGh()
        action = arl.upsert_comment("gazon1/sing", 42, arl.comment_body("a", "b"), gh)
        self.assertEqual(action, "created")


class PagesSiteTest(unittest.TestCase):
    """The address of the published site, and how it came to be known."""

    def test_the_custom_domain_the_site_answers_with_is_used(self):
        gh = FakeGh({"repos/gazon1/sing/pages": {"html_url": "http://maxdrobin.ru/sing/"}})
        site = arl.fetch_pages_site("gazon1/sing", gh)
        self.assertEqual(site.status, "confirmed")
        self.assertEqual(arl.resolve_site_url(site), "http://maxdrobin.ru/sing")

    def test_a_site_without_a_custom_domain_answers_with_its_own_url(self):
        gh = FakeGh({"repos/gazon1/sing/pages": {"html_url": "https://gazon1.github.io/sing/"}})
        site = arl.fetch_pages_site("gazon1/sing", gh)
        self.assertEqual(site.status, "confirmed")
        self.assertEqual(arl.resolve_site_url(site), "https://gazon1.github.io/sing")

    def test_a_forbidden_api_is_not_a_site_without_a_custom_domain(self):
        gh = FakeGh({}, errors={"repos/gazon1/sing/pages": "gh: Forbidden (HTTP 403)"})
        site = arl.fetch_pages_site("gazon1/sing", gh)
        self.assertEqual(site.status, "unavailable")
        self.assertIsNone(arl.resolve_site_url(site))

    def test_a_temporary_failure_is_not_a_site_without_a_custom_domain(self):
        gh = FakeGh({}, errors={"repos/gazon1/sing/pages": "gh: Server Error (HTTP 502)"})
        site = arl.fetch_pages_site("gazon1/sing", gh)
        self.assertEqual(site.status, "unavailable")
        self.assertIsNone(arl.resolve_site_url(site))

    def test_a_missing_site_is_not_configured(self):
        gh = FakeGh({}, errors={"repos/gazon1/sing/pages": "gh: Not Found (HTTP 404)"})
        site = arl.fetch_pages_site("gazon1/sing", gh)
        self.assertEqual(site.status, "not-configured")
        self.assertIsNone(arl.resolve_site_url(site))

    def test_an_answer_without_an_address_is_not_configured(self):
        gh = FakeGh({"repos/gazon1/sing/pages": {"status": "built"}})
        site = arl.fetch_pages_site("gazon1/sing", gh)
        self.assertEqual(site.status, "not-configured")
        self.assertIsNone(arl.resolve_site_url(site))

    def test_an_answer_that_is_not_json_is_not_a_confirmed_address(self):
        gh = FakeGh({}, errors={"repos/gazon1/sing/pages": "gh api answered with invalid JSON"})
        site = arl.fetch_pages_site("gazon1/sing", gh)
        self.assertEqual(site.status, "unavailable")
        self.assertIsNone(arl.resolve_site_url(site))

    def test_the_configured_address_is_used_when_the_settings_do_not_answer(self):
        gh = FakeGh({}, errors={"repos/gazon1/sing/pages": "gh: Forbidden (HTTP 403)"})
        site = arl.fetch_pages_site("gazon1/sing", gh)
        self.assertEqual(
            arl.resolve_site_url(site, "http://maxdrobin.ru/sing/"),
            "http://maxdrobin.ru/sing",
        )

    def test_the_confirmed_address_wins_over_the_configured_one(self):
        gh = FakeGh({"repos/gazon1/sing/pages": {"html_url": "http://maxdrobin.ru/sing/"}})
        site = arl.fetch_pages_site("gazon1/sing", gh)
        self.assertEqual(
            arl.resolve_site_url(site, "https://gazon1.github.io/sing"),
            "http://maxdrobin.ru/sing",
        )


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

    def test_no_link_is_posted_when_the_site_url_is_unknown(self):
        gh = FakeGh({"commits/abc/pulls": [{"number": 42, "body": ""}]})
        result = arl.post_report_link("gazon1/sing", "full", "3027", "abc", gh)
        self.assertEqual(result["status"], "no-site-url")
        self.assertIsNone(result["site"])
        self.assertEqual(result["comments"], [])

    def test_the_configured_url_is_used_when_the_settings_do_not_answer(self):
        gh = FakeGh({"commits/abc/pulls": [{"number": 42, "body": ""}]})
        result = arl.post_report_link(
            "gazon1/sing",
            "full",
            "3027",
            "abc",
            gh,
            configured_url="http://maxdrobin.ru/sing/",
        )
        self.assertEqual(result["site"], "http://maxdrobin.ru/sing")
        self.assertEqual(result["status"], "commented")

    def test_an_unreachable_target_does_not_cost_the_others_their_comment(self):
        gh = FakeGh(
            {
                "repos/gazon1/sing/pages": {"html_url": "http://maxdrobin.ru/sing/"},
                "commits/abc/pulls": [{"number": 42, "body": "Closes #7, #999"}],
            },
            errors={"issues/999/comments": "HTTP 404: Not Found"},
        )
        result = arl.post_report_link("gazon1/sing", "fast", "3028", "abc", gh)
        actions = {comment["target"]: comment["action"] for comment in result["comments"]}
        self.assertEqual(actions[42], "created")
        self.assertEqual(actions[7], "created")
        self.assertEqual(actions[999], "failed")
        self.assertEqual(result["status"], "commented")


class CliTest(unittest.TestCase):
    """The command the workflow runs."""

    def test_without_a_repository_the_command_refuses(self):
        self.assertEqual(arl.main(["--population", "fast", "--run-number", "1", "--sha", "abc"]), 2)

    def test_without_a_population_the_command_refuses(self):
        with self.assertRaises(SystemExit):
            arl.main(["--repo", "gazon1/sing", "--run-number", "1", "--sha", "abc"])


if __name__ == "__main__":
    unittest.main()
