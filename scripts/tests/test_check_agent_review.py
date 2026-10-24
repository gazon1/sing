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
  6. a review that says `fail` does not green the pull request;
  7. the comment and the verdict file are two verdicts of two different
     scopes, and the check does not compare them — it checks the pointer
     between them instead.
"""

import importlib.util
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
GATE = _ROOT / "scripts" / "ci" / "check-agent-review.py"

_spec = importlib.util.spec_from_file_location("check_agent_review_gate", GATE)
_gate = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_gate)

opens_with_the_marker = _gate.opens_with_the_marker
stated_verdict = _gate.stated_verdict
review_of = _gate.review_of
pointer_findings = _gate.pointer_findings
paths_of = _gate.paths_of
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
        self.assertIsNone(stated_verdict(body))

    def test_the_marker_in_the_middle_of_a_comment_is_not_a_review(self):
        body = f"Here is the review.\n\n{MARKER}\n**Verdict:** pass\n"
        self.assertFalse(opens_with_the_marker(body))
        self.assertIsNone(stated_verdict(body))

    def test_an_empty_comment_is_not_a_review(self):
        self.assertFalse(opens_with_the_marker(""))
        self.assertIsNone(stated_verdict(""))


class VerdictTest(unittest.TestCase):
    """The verdict a marked comment states."""

    def test_a_marked_comment_with_a_verdict_states_it(self):
        self.assertEqual("pass", stated_verdict(review()))
        self.assertEqual("fail", stated_verdict(review("fail")))

    def test_the_plain_spelling_is_read_too(self):
        self.assertEqual("fail", stated_verdict(f"{MARKER}\nverdict: fail\n"))

    def test_the_placeholder_is_the_format_and_not_a_verdict(self):
        # `**Verdict:** pass | fail` is what the template carries. It decides
        # nothing, so a comment that only carries it is not a review.
        body = f"{MARKER}\n**Verdict:** pass | fail\n"
        self.assertIsNone(stated_verdict(body))

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
                self.assertIsNone(stated_verdict(f"{MARKER}\n{line}\n"))

    def test_a_comment_that_states_two_verdicts_decides_nothing(self):
        # A review that says `pass` and a review that says `fail` in the same
        # comment is a comment nobody can read.
        body = f"{MARKER}\n**Verdict:** pass\n**Verdict:** fail\n"
        self.assertIsNone(stated_verdict(body))

    def test_a_marked_comment_without_a_verdict_states_nothing(self):
        self.assertIsNone(stated_verdict(f"{MARKER}\n## Agent review\n\nNothing found.\n"))


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

    def test_the_newest_review_decides_even_when_it_decides_nothing(self):
        # A marked comment that states no verdict is a review that says
        # nothing — and the pull request is judged by the newest review, not
        # by the newest one that happens to be readable.
        comments = [
            comment(review("pass"), url="https://example.com/first"),
            comment(f"{MARKER}\n## Agent review\n\nI looked at it.\n", url="https://example.com/second"),
        ]
        with self.assertRaises(ReviewWithoutVerdict):
            review_of(comments)

    def test_a_review_that_states_two_verdicts_decides_nothing(self):
        body = f"{MARKER}\n## Agent review\n\n**Verdict:** pass\n\n**Verdict:** fail\n"
        with self.assertRaises(ReviewWithoutVerdict):
            review_of([comment(body)])

    def test_a_review_that_states_two_verdicts_does_not_borrow_the_older_pass(self):
        # The check must not fall back to the verdict before it: a green that
        # nobody wrote is worse than a red.
        comments = [
            comment(review("pass"), url="https://example.com/first"),
            comment(
                f"{MARKER}\n## Agent review\n\n**Verdict:** pass\n\n**Verdict:** fail\n",
                url="https://example.com/second",
            ),
        ]
        with self.assertRaises(ReviewWithoutVerdict):
            review_of(comments)


class ExitCodeTest(unittest.TestCase):
    """The exit code is the verdict the CI gate reads."""

    def _run(self, comments: list[dict]) -> int:
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "comments.json"
            path.write_text(json.dumps(comments), encoding="utf-8")
            result = subprocess.run(
                [sys.executable, str(GATE), "--comments-file", str(path)],
                capture_output=True,
                text=True,
                check=False,
            )
        return result.returncode

    def test_a_passing_review_exits_zero(self):
        self.assertEqual(0, self._run([comment(review("pass"))]))

    def test_a_failing_review_exits_non_zero(self):
        self.assertEqual(1, self._run([comment(review("fail"))]))

    def test_a_pull_request_without_a_review_exits_non_zero(self):
        self.assertEqual(1, self._run([]))


def verdict_file(verdict: str = "pass", pr: str = "1017", change: str = "the-change") -> str:
    """A verdict file, as the registry writes it."""
    return (
        "---\n"
        f"change: {change}\n"
        f"verdict: {verdict}\n"
        "date: 2026-10-23\n"
        f"pr: {pr}\n"
        "reviewed: " + "a" * 40 + "\n"
        "documents: sha256:" + "b" * 64 + "\n"
        "---\n\n## What was reviewed\n\nThe change.\n"
    )


class PointerTest(unittest.TestCase):
    """The verdict file points at a pull request, and the two have to agree.

    The comment is the pull request's verdict and the file is the change's:
    two scopes, two lives, and a `fail` in the file is a legal permanent
    record. So the check does not compare them — it checks the pointer
    between them.
    """

    def _findings(self, files: dict[str, str], paths: list[str], pull_request: int = 1017) -> list[str]:
        with tempfile.TemporaryDirectory() as directory:
            reviews = pathlib.Path(directory) / "reviews"
            reviews.mkdir()
            for name, text in files.items():
                (reviews / name).write_text(text, encoding="utf-8")
            return pointer_findings(pull_request, reviews, paths)

    def test_a_file_that_names_this_pull_request_and_a_change_it_carries_agrees(self):
        findings = self._findings(
            {"the-change.md": verdict_file(pr="1017")},
            ["openspec/changes/2026-10-23-the-change/proposal.md"],
        )
        self.assertEqual([], findings)

    def test_a_file_that_names_this_pull_request_and_a_change_it_does_not_carry_is_a_finding(self):
        findings = self._findings(
            {"the-change.md": verdict_file(pr="1017")},
            ["scripts/ci/check-agent-review.py"],
        )
        self.assertEqual(1, len(findings))
        self.assertIn("does not carry", findings[0])

    def test_a_file_that_names_another_pull_request_is_not_about_this_one(self):
        findings = self._findings(
            {"the-change.md": verdict_file(pr="42")},
            ["scripts/ci/check-agent-review.py"],
        )
        self.assertEqual([], findings)

    def test_a_contract_that_moved_here_has_to_carry_a_verdict_that_names_this_pull_request(self):
        findings = self._findings(
            {"the-change.md": verdict_file(pr="42")},
            ["openspec/changes/2026-10-23-the-change/proposal.md"],
        )
        self.assertEqual(1, len(findings))
        self.assertIn("re-issued", findings[0])

    def test_a_contract_that_moved_here_and_a_verdict_that_names_this_pull_request_agree(self):
        findings = self._findings(
            {"the-change.md": verdict_file(pr="1017")},
            ["openspec/changes/2026-10-23-the-change/specs/one/spec.md"],
        )
        self.assertEqual([], findings)

    def test_a_checklist_that_moved_is_not_a_contract_that_moved(self):
        # `tasks.md` is a checklist: ticking a box is not a change of what
        # the change requires, and the verdict still speaks for the contract.
        findings = self._findings(
            {"the-change.md": verdict_file(pr="42")},
            ["openspec/changes/2026-10-23-the-change/tasks.md"],
        )
        self.assertEqual([], findings)

    def test_a_change_that_is_filed_away_is_not_a_contract_that_moved(self):
        findings = self._findings(
            {"the-change.md": verdict_file(pr="42")},
            ["openspec/changes/archive/2026-10-23-the-change/proposal.md"],
        )
        self.assertEqual([], findings)

    def test_a_file_that_names_the_pull_request_by_url_names_it(self):
        url = "https://github.com/gazon1/sing/pull/1017"
        findings = self._findings(
            {"the-change.md": verdict_file(pr=url)},
            ["scripts/ci/check-agent-review.py"],
        )
        self.assertEqual(1, len(findings))

    def test_the_owner_name_in_a_url_is_not_a_pull_request_number(self):
        # `gazon1` carries a digit, and a pull request is not `gazon1`.
        findings = self._findings(
            {"the-change.md": verdict_file(pr="https://github.com/gazon1/sing")},
            ["scripts/ci/check-agent-review.py"],
        )
        self.assertEqual([], findings)


class PathsTest(unittest.TestCase):
    """The changed paths of a pull request, read from a file."""

    def _paths(self, payload: str) -> list[str]:
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "files.json"
            path.write_text(payload, encoding="utf-8")
            return paths_of(path)

    def test_a_list_of_paths_is_read(self):
        self.assertEqual(
            ["openspec/changes/the-change/proposal.md"],
            self._paths('["openspec/changes/the-change/proposal.md"]'),
        )

    def test_the_shape_github_returns_is_read(self):
        # `gh api .../pulls/<n>/files` returns objects, not strings.
        payload = '[{"filename": "openspec/changes/the-change/proposal.md", "status": "modified"}]'
        self.assertEqual(
            ["openspec/changes/the-change/proposal.md"],
            self._paths(payload),
        )

    def test_a_file_that_is_not_a_list_is_not_read(self):
        with self.assertRaises(ReviewNotFound):
            self._paths('{"filename": "a.kt"}')

    def test_a_file_that_is_not_json_is_not_read(self):
        with self.assertRaises(ReviewNotFound):
            self._paths("not json at all")

    def test_a_file_that_is_not_there_is_not_read(self):
        with self.assertRaises(ReviewNotFound):
            paths_of(pathlib.Path("/nonexistent/files.json"))


class VerdictFileExitCodeTest(unittest.TestCase):
    """The pointer findings reach the exit code the CI gate reads."""

    def _run(self, comments: list[dict], files: dict[str, str], paths: list[str]) -> int:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            comments_path = root / "comments.json"
            comments_path.write_text(json.dumps(comments), encoding="utf-8")
            files_path = root / "files.json"
            files_path.write_text(json.dumps(paths), encoding="utf-8")
            reviews = root / "reviews"
            reviews.mkdir()
            for name, text in files.items():
                (reviews / name).write_text(text, encoding="utf-8")
            result = subprocess.run(
                [
                    sys.executable,
                    str(GATE),
                    "--comments-file",
                    str(comments_path),
                    "--files-file",
                    str(files_path),
                    "--pr",
                    "1017",
                    "--reviews-dir",
                    str(reviews),
                ],
                capture_output=True,
                text=True,
                check=False,
            )
        return result.returncode

    def test_a_passing_review_and_an_agreeing_file_exit_zero(self):
        exit_code = self._run(
            [comment(review("pass"))],
            {"the-change.md": verdict_file(pr="1017")},
            ["openspec/changes/2026-10-23-the-change/proposal.md"],
        )
        self.assertEqual(0, exit_code)

    def test_the_comment_and_the_file_are_not_compared(self):
        # The comment is the pull request's verdict and the file is the
        # change's: a `fail` in the file is a legal permanent record, and a
        # gate that demanded the two agree would keep a pull request red for
        # as long as the change exists.
        exit_code = self._run(
            [comment(review("pass"))],
            {"the-change.md": verdict_file(verdict="fail", pr="42")},
            ["scripts/ci/check-agent-review.py"],
        )
        self.assertEqual(0, exit_code)

    def test_a_stale_pointer_exits_non_zero(self):
        exit_code = self._run(
            [comment(review("pass"))],
            {"the-change.md": verdict_file(pr="1017")},
            ["scripts/ci/check-agent-review.py"],
        )
        self.assertEqual(1, exit_code)

    def test_a_contract_that_moved_without_a_re_issue_exits_non_zero(self):
        exit_code = self._run(
            [comment(review("pass"))],
            {"the-change.md": verdict_file(pr="42")},
            ["openspec/changes/2026-10-23-the-change/proposal.md"],
        )
        self.assertEqual(1, exit_code)


if __name__ == "__main__":
    unittest.main()
