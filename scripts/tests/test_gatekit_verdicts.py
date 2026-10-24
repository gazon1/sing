"""Unit tests for `scripts/src/sgate/gatekit/verdicts.py` — the shared vocabulary.

Run with: python3 -m unittest discover -s scripts/tests

Two gates read the verdicts of this repository, and a verdict is one thing
written down in two places. The vocabulary of that writing — the frontmatter,
the two verdicts a file may carry, the pull request it names, the slug of a
change — lives in one module, and these tests pin it there.

The pull request a file names is the sharpest part: the field is written as a
number by some and as a URL by others, and a pull request is not `gazon1`.
"""

import importlib.util
import pathlib
import unittest

_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
MODULE = _ROOT / "scripts" / "src" / "sgate" / "gatekit" / "verdicts.py"

_spec = importlib.util.spec_from_file_location("gatekit_verdicts", MODULE)
_verdicts = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_verdicts)

parse_frontmatter = _verdicts.parse_frontmatter
verdict_of = _verdicts.verdict_of
pull_request_of = _verdicts.pull_request_of
slug_of = _verdicts.slug_of
changed_slugs = _verdicts.changed_slugs
moved_contracts = _verdicts.moved_contracts
contract_documents = _verdicts.contract_documents
baseline_slugs = _verdicts.baseline_slugs

VERDICTS = _verdicts.VERDICTS


def fields(text: str) -> dict[str, str]:
    """The frontmatter of a verdict file."""
    return parse_frontmatter(text)


class FrontmatterTest(unittest.TestCase):
    """The shape of a verdict file's frontmatter."""

    def test_the_fields_of_a_verdict_file_are_read(self):
        text = "---\nchange: the-change\nverdict: pass\npr: 1040\n---\n\nThe review.\n"
        self.assertEqual(
            {"change": "the-change", "verdict": "pass", "pr": "1040"},
            fields(text),
        )

    def test_a_file_without_a_frontmatter_has_no_fields(self):
        self.assertEqual({}, fields("The review, with no frontmatter.\n"))

    def test_a_file_with_an_unclosed_frontmatter_has_no_fields(self):
        self.assertEqual({}, fields("---\nchange: the-change\n"))

    def test_a_value_may_be_quoted(self):
        text = "---\npr: \"1040\"\n---\n"
        self.assertEqual({"pr": "1040"}, fields(text))


class VerdictTest(unittest.TestCase):
    """The two verdicts a file may carry."""

    def test_the_vocabulary_is_pass_and_fail(self):
        self.assertEqual({"pass", "fail"}, set(VERDICTS))

    def test_a_verdict_is_read(self):
        self.assertEqual("pass", verdict_of({"verdict": "pass"}))
        self.assertEqual("fail", verdict_of({"verdict": "fail"}))

    def test_the_spelling_is_not_the_point(self):
        self.assertEqual("pass", verdict_of({"verdict": "PASS"}))

    def test_a_word_that_is_not_a_verdict_states_nothing(self):
        self.assertIsNone(verdict_of({"verdict": "pass | fail"}))
        self.assertIsNone(verdict_of({"verdict": ""}))
        self.assertIsNone(verdict_of({}))


class PullRequestTest(unittest.TestCase):
    """The pull request a verdict file names."""

    def test_a_number_names_a_pull_request(self):
        self.assertEqual(1040, pull_request_of({"pr": "1040"}))

    def test_a_hash_number_names_a_pull_request(self):
        self.assertEqual(1040, pull_request_of({"pr": "#1040"}))

    def test_a_url_names_a_pull_request(self):
        url = "https://github.com/gazon1/sing/pull/1040"
        self.assertEqual(1040, pull_request_of({"pr": url}))

    def test_a_url_with_a_trailing_slash_names_a_pull_request(self):
        url = "https://github.com/gazon1/sing/pull/1040/"
        self.assertEqual(1040, pull_request_of({"pr": url}))

    def test_the_owner_name_in_a_url_is_not_a_pull_request(self):
        # `gazon1` carries a digit, and a pull request is not `gazon1`.
        self.assertIsNone(pull_request_of({"pr": "https://github.com/gazon1/sing"}))
        self.assertIsNone(pull_request_of({"pr": "gazon1"}))

    def test_an_empty_field_names_nothing(self):
        self.assertIsNone(pull_request_of({"pr": ""}))
        self.assertIsNone(pull_request_of({}))

    def test_a_sentence_is_not_a_pull_request(self):
        self.assertIsNone(pull_request_of({"pr": "the pull request that carried it"}))


class SlugTest(unittest.TestCase):
    """The slug of a change."""

    def test_a_dated_directory_carries_a_slug(self):
        self.assertEqual("the-change", slug_of("2026-10-23-the-change"))

    def test_a_slug_is_a_slug(self):
        self.assertEqual("the-change", slug_of("the-change"))


class ChangedSlugsTest(unittest.TestCase):
    """The changes a pull request carries."""

    def test_a_path_inside_a_change_carries_it(self):
        paths = ["openspec/changes/2026-10-23-the-change/proposal.md"]
        self.assertEqual({"the-change"}, changed_slugs(paths))

    def test_a_path_outside_a_change_carries_nothing(self):
        self.assertEqual(set(), changed_slugs(["scripts/ci/check-agent-review.py"]))

    def test_a_change_that_is_filed_away_is_the_change_it_was(self):
        paths = ["openspec/changes/archive/2026-10-23-the-change/proposal.md"]
        self.assertEqual({"the-change"}, changed_slugs(paths))


class MovedContractsTest(unittest.TestCase):
    """The changes whose contract a pull request moves."""

    def test_a_proposal_is_the_contract(self):
        paths = ["openspec/changes/2026-10-23-the-change/proposal.md"]
        self.assertEqual({"the-change"}, moved_contracts(paths))

    def test_a_spec_delta_is_the_contract(self):
        paths = ["openspec/changes/2026-10-23-the-change/specs/todo/spec.md"]
        self.assertEqual({"the-change"}, moved_contracts(paths))

    def test_a_checklist_is_not_the_contract(self):
        paths = ["openspec/changes/2026-10-23-the-change/tasks.md"]
        self.assertEqual(set(), moved_contracts(paths))

    def test_a_change_that_is_filed_away_is_not_a_contract_that_moved(self):
        paths = ["openspec/changes/archive/2026-10-23-the-change/proposal.md"]
        self.assertEqual(set(), moved_contracts(paths))

    def test_a_change_that_is_filed_away_is_not_a_contract_that_moved_however_it_is_reported(self):
        # GitHub reports an archive move as a rename (the new path alone) or
        # as a removal and an addition. The rule must not depend on the shape.
        paths = [
            "openspec/changes/2026-10-23-the-change/proposal.md",
            "openspec/changes/archive/2026-10-23-the-change/proposal.md",
        ]
        self.assertEqual(set(), moved_contracts(paths))

    def test_a_document_that_is_not_part_of_the_contract_is_not_a_contract_that_moved(self):
        # The digest is over the contract's *markdown* documents, so a file
        # under `specs/` that is not one does not move the contract.
        paths = ["openspec/changes/2026-10-23-the-change/specs/todo/schema.json"]
        self.assertEqual(set(), moved_contracts(paths))


class ContractDocumentsTest(unittest.TestCase):
    """The contract of a change — the documents the digest is over."""

    def _change(self, files: list[str]) -> pathlib.Path:
        import tempfile

        directory = pathlib.Path(tempfile.mkdtemp())
        for name in files:
            path = directory / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("text", encoding="utf-8")
        return directory

    def test_the_proposal_and_the_spec_deltas_are_the_contract(self):
        change = self._change(
            ["proposal.md", "specs/todo/spec.md", "specs/notes/spec.md", "tasks.md"]
        )
        names = [path.name for path in contract_documents(change)]
        self.assertEqual(["proposal.md", "spec.md", "spec.md"], sorted(names))
        self.assertNotIn("tasks.md", names)

    def test_a_change_without_a_contract_has_none(self):
        change = self._change(["tasks.md"])
        self.assertEqual([], contract_documents(change))


class BaselineTest(unittest.TestCase):
    """The changes the rule does not ask a verdict file of."""

    def test_the_entries_are_read_and_the_comments_are_not(self):
        import tempfile

        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "baseline.txt"
            path.write_text("# A comment.\nthe-change\n\nanother-change\n", encoding="utf-8")
            self.assertEqual({"the-change", "another-change"}, baseline_slugs(path))

    def test_a_missing_baseline_accepts_nothing(self):
        self.assertEqual(set(), baseline_slugs(pathlib.Path("/nonexistent/baseline.txt")))


if __name__ == "__main__":
    unittest.main()
