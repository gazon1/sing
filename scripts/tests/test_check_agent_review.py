"""Unit tests for `scripts/ci/check-agent-review.py` — the agent review of a pull request.

Run with: python3 -m unittest discover -s scripts/tests

The rule under test is the one `AGENTS.md` carries in one line: a change is
reviewed by a separate agent before the pull request is opened. The line was
never enforceable, so this check makes it enforceable — and a check that can
pass on a pull request that was never reviewed is worse than no check.

So these tests pin the things the check has to get right:

  1. a marked comment with a verdict is a review — the check passes;
  2. a comment that does not *open* with the marker is not a review, whatever
     it says — the format is written down in three places, and a comment that
     quotes it is not a review;
  3. the placeholder `pass | fail` is the format, not a verdict;
  4. a marked comment without a verdict says nothing, and the check says so
     rather than passing;
  5. the newest review wins — a later `pass` after an earlier `fail` is a
     story that ended well;
  6. a review that says `fail` does not green the pull request.
"""

import importlib.util
import pathlib
import sys
import unittest

_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
GATE = _ROOT / "scripts" / "ci" / "check-agent-review.py"

_spec = importlib.util.spec_from_file_location("check_agent_review_gate", GATE)
_gate = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_gate)

opens_with_the_marker = _gate.opens_with_the_marker
verdict_of = _gate.verdict_of
review_of = _gate.review_of
ReviewNotFound = _gate.ReviewNotFound
ReviewWithoutVerdict = _gate.ReviewWithoutVerdict
ReviewFailed = _gate.ReviewFailed

MARKER = _gate.MARKER


def comment(body: str, url: str = "https://github.com/gazon1/sing/pull/1#issuecomment-1") -> dict:
    """One comment, as GitHub returns it."""
    return {"body": body, "html_url": url}


def review(verdict: str = "pass") -> str:
    """A review comment in the documented format."""
    return f"{MARKER}\n## Agent review\n\n**Verdict:** {verdict}\n"


class MarkerTest(unittest.TestCase):
    """A review opens with the marker; a quote of it does not."""

    def test_a_comment_that_opens_with_the_marker_is_a_review(self):
        self.assertTrue(opens_with_the_marker(review()))

    def test_a_blank_line_before_the_marker_is_still_the_first_thing_said(self):
        self.assertTrue(opens_with_the_marker(f"\n\n{review()}"))

    def test_a_comment_that_quotes_the_format_is_not_a_review(self):
        # The marker is written down in the script, the template and AGENTS.md.
        # A comment that quotes it — asking what the format is — is not a
        # review, and a verdict found next to the quote is not a verdict.
        body = f"The review has to carry `{MARKER}` and a verdict.\n\n**Verdict:** pass\n"
        self.assertFalse(opens_with_the_marker(body))
        self.assertIsNone(verdict_of(body))

    def test_the_marker_in_the_middle_of_a_comment_is_not_a_review(self):
        body = f"Here is the review.\n\n{MARKER}\n**Verdict:** pass\n"
        self.assertFalse(opens_with_the_marker(body))
        self.assertIsNone(verdict_of(body))

    def test_an_empty_comment_is_not_a_review(self):
        self.assertFalse(opens_with_the_marker(""))
        self.assertIsNone(verdict_of(""))


class VerdictTest(unittest.TestCase):
    """The verdict a marked comment states."""

    def test_a_marked_comment_with_a_verdict_states_it(self):
        self.assertEqual("pass", verdict_of(review()))
        self.assertEqual("fail", verdict_of(review("fail")))

    def test_the_plain_spelling_is_read_too(self):
        self.assertEqual("fail", verdict_of(f"{MARKER}\nverdict: fail\n"))

    def test_the_placeholder_is_the_format_and_not_a_verdict(self):
        # `**Verdict:** pass | fail` is what the template carries. It decides
        # nothing, so a comment that only carries it is not a review.
        body = f"{MARKER}\n**Verdict:** pass | fail\n"
        self.assertIsNone(verdict_of(body))

    def test_a_sentence_about_a_verdict_is_not_a_verdict(self):
        # The verdict is the whole value of the line. A search for the word
        # "pass" anywhere on the line finds "this will not pass" as readily,
        # and a check that reads that as a green light can be fooled by a
        # copy of the format.
        for line in (
            "**Verdict:** pass or fail",
            "**Verdict:** pass/fail",
            "**Verdict:** this will not pass",
            "**Verdict:** failing to pass",
        ):
            with self.subTest(line=line):
                self.assertIsNone(verdict_of(f"{MARKER}\n{line}\n"))

    def test_a_comment_that_states_two_verdicts_decides_nothing(self):
        # A review that says `pass` and a review that says `fail` in the same
        # comment is a comment nobody can read.
        body = f"{MARKER}\n**Verdict:** pass\n**Verdict:** fail\n"
        self.assertIsNone(verdict_of(body))

    def test_a_marked_comment_without_a_verdict_states_nothing(self):
        self.assertIsNone(verdict_of(f"{MARKER}\n## Agent review\n\nNothing found.\n"))


class ReviewTest(unittest.TestCase):
    """The review of a pull request, read from its comments."""

    def test_the_review_is_found(self):
        comments = [
            comment("Looks good to me."),
            comment(review()),
        ]
        verdict, url = review_of(comments)
        self.assertEqual("pass", verdict)
        self.assertTrue(url)

    def test_a_pull_request_without_a_review_has_none(self):
        with self.assertRaises(ReviewNotFound):
            review_of([comment("Looks good to me.")])

    def test_a_pull_request_with_no_comments_at_all_has_none(self):
        with self.assertRaises(ReviewNotFound):
            review_of([])

    def test_a_review_without_a_verdict_says_nothing(self):
        with self.assertRaises(ReviewWithoutVerdict):
            review_of([comment(f"{MARKER}\n## Agent review\n\nI looked at it.\n")])

    def test_a_verdict_after_a_marked_comment_is_found(self):
        # The check is about the pull request as a whole: the comment that
        # carries the verdict may not be the first marked one.
        comments = [
            comment(f"{MARKER}\n## Agent review\n\nStill reading.\n"),
            comment(review()),
        ]
        verdict, _ = review_of(comments)
        self.assertEqual("pass", verdict)

    def test_the_newest_review_wins(self):
        # GitHub returns the comments oldest first. A review that found
        # something and a later review that did not is a story that ended
        # well, and the pull request says so.
        comments = [
            comment(review("fail"), url="https://example.com/first"),
            comment(review("pass"), url="https://example.com/second"),
        ]
        verdict, url = review_of(comments)
        self.assertEqual("pass", verdict)
        self.assertEqual("https://example.com/second", url)

    def test_a_failing_review_does_not_green_the_pull_request(self):
        with self.assertRaises(ReviewFailed):
            review_of([comment(review("fail"))])

    def test_a_failing_review_that_was_answered_by_a_later_pass_is_green(self):
        comments = [
            comment(review("fail")),
            comment(review("pass"), url="https://example.com/second"),
        ]
        verdict, _ = review_of(comments)
        self.assertEqual("pass", verdict)


if __name__ == "__main__":
    unittest.main()
