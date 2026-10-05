#!/usr/bin/env python3
"""Tests for check-suppression-intent.py.

The gate this file tests exists because a file-level `@file:Suppress` says
*nothing in this file is checked*, and no other gate in the repository could see
one. So the properties worth pinning are mostly about the gate's own ability to
fail and to refuse to run:

  * the rule ids are **derived**, so a rule registered tomorrow is covered
    without anyone remembering to add it — and a derivation that finds nothing is
    a failure, not a clean tree;
  * both escapes actually work, since a gate with no escape is a gate that gets
    deleted the first time it is inconvenient;
  * a registry entry that no longer matches anything is an error, because a stale
    exemption is an exemption for a file that was fixed;
  * only *custom* ids are policed. `@file:Suppress("LongMethod")` is ordinary
    practice and is not this gate's business.

Each test calls the script's own functions. An earlier test file in this
repository re-implemented its predicate instead and passed on broken code — see
`test_check_rule_intent.py` and ADR
`2026-10-05-positive-control-registry-is-derived`.
"""

import contextlib
import importlib.util
import io
import pathlib
import sys
import unittest

SCRIPT = (
    pathlib.Path(__file__).resolve().parent.parent / "check-suppression-intent.py"
)

_spec = importlib.util.spec_from_file_location("check_suppression_intent", SCRIPT)
csi = importlib.util.module_from_spec(_spec)
sys.modules["check_suppression_intent"] = csi
_spec.loader.exec_module(csi)


class TestRuleIdDerivation(unittest.TestCase):
    """The ids come from the rule sources, not from a list in this script."""

    def test_ids_are_derived_and_non_empty_on_this_repository(self):
        ids = csi.custom_rule_ids()
        self.assertGreater(len(ids), 0, "no custom rule ids derived")
        # The rule this gate was written for has to be among them, or the gate
        # is watching a different set than the one that is being suppressed.
        self.assertIn("NoDirectClockSystem", ids)
        self.assertIn("NoRealDelayInTest", ids)

    def test_a_rule_id_is_read_from_its_providers_rule_name(self):
        self.assertIn("PassThroughUseCase", csi.custom_rule_ids())
        self.assertIn("ProhibitUserIdInObserve", csi.custom_rule_ids())

    def test_an_empty_derivation_is_not_a_pass(self):
        """The vacuous green this whole repository keeps removing.

        If the source path is wrong the scan returns nothing, and a check that
        reports "0 suppressions, all fine" has silently stopped checking. The
        gate's `main` turns an empty id set into a failure; this pins that the
        derivation really is what decides.
        """
        saved = csi.RULE_SOURCES
        try:
            csi.RULE_SOURCES = csi.ROOT / "no-such-directory"
            self.assertEqual(csi.custom_rule_ids(), set())
        finally:
            csi.RULE_SOURCES = saved
        self.assertTrue(csi.custom_rule_ids(), "restored derivation must be non-empty")

    def test_providers_are_read_from_the_service_loader(self):
        providers = csi.provider_names()
        self.assertIn("NoDirectClockSystemProvider", providers)
        self.assertIn("UserScopedRepositoryRulesProvider", providers)


class TestReasonDetection(unittest.TestCase):
    def test_a_trailing_comment_counts_as_a_reason(self):
        self.assertTrue(
            csi.has_adjacent_reason('@file:Suppress("NoDirectClockSystem") // a fake\'s own clock')
        )

    def test_a_block_comment_counts(self):
        self.assertTrue(csi.has_adjacent_reason('@file:Suppress("X") /* because */'))

    def test_a_bare_annotation_has_no_reason(self):
        self.assertFalse(csi.has_adjacent_reason('@file:Suppress("NoDirectClockSystem")'))

    def test_a_closing_paren_is_not_a_reason(self):
        # `// )` is what a wrapping annotation leaves behind; counting it would
        # let any file pass by adding a stray character.
        self.assertFalse(csi.has_adjacent_reason('@file:Suppress("X") // )'))


class TestTestPathsAreExcluded(unittest.TestCase):
    def test_a_test_source_set_is_not_scanned(self):
        self.assertTrue(csi._is_test_path(csi.ROOT / "shared/src/jvmTest/kotlin/XTest.kt"))
        self.assertTrue(csi._is_test_path(csi.ROOT / "shared/src/commonTest/kotlin/a/B.kt"))
        self.assertTrue(
            csi._is_test_path(csi.ROOT / "shared/src/commonMain/kotlin/com/x/test/fakes/F.kt")
        )

    def test_production_code_is_scanned(self):
        self.assertFalse(csi._is_test_path(csi.ROOT / "shared/src/commonMain/kotlin/A.kt"))
        # A class merely *named* like a fake is still production code until it
        # moves; the convention is a path, not a prefix.
        self.assertFalse(
            csi._is_test_path(csi.ROOT / "shared/src/commonMain/kotlin/com/x/FakeThing.kt")
        )


class TestRegistryDiscipline(unittest.TestCase):
    """A registry that cannot notice its own staleness is a sink, not a list."""

    def _run(self, registry):
        """Run `main` with a temporary registry and return what it printed.

        Two things have to be handled, and both were found by the tests erroring
        rather than failing:

        * `main` ends in `sys.exit`, so the call is wrapped;
        * `main` parses `sys.argv`, which under unittest is
        `['python -m unittest', 'discover', '-s', …]` — argparse rejects it,
          exits 2, and writes to *stderr*, leaving the captured stdout empty. So
          argv is replaced too.
        """
        saved_registry = csi.JUSTIFIED_FILE_SUPPRESSIONS
        saved_argv = sys.argv
        try:
            csi.JUSTIFIED_FILE_SUPPRESSIONS = registry
            sys.argv = ["check-suppression-intent.py"]
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                try:
                    csi.main()
                except SystemExit:
                    pass
            return buf.getvalue()
        finally:
            csi.JUSTIFIED_FILE_SUPPRESSIONS = saved_registry
            sys.argv = saved_argv

    def test_an_empty_reason_is_rejected(self):
        path = "shared/src/commonMain/kotlin/com/singularity/todo/core/log/FileLogWriter.kt"
        out = self._run({path: "   "})
        self.assertIn("empty reason", out)

    def test_a_missing_file_is_rejected(self):
        out = self._run({"shared/src/commonMain/kotlin/com/singularity/todo/Nope.kt": "why"})
        self.assertIn("does not exist", out)

    def test_an_entry_that_no_longer_matches_is_rejected(self):
        path = "shared/src/commonMain/kotlin/com/singularity/todo/feature/checklist/ChecklistIds.kt"
        out = self._run({path: "stale"})
        self.assertIn("no longer carries", out)

    def test_an_unjustified_suppression_is_reported_and_a_justified_one_is_not(self):
        """The verdict itself, not only its inputs.

        The tests above pin the reason *detector* and the registry's three error
        shapes, and between them they all stayed green while the line that
        combines them was replaced with an unconditional `"justified"`. Nothing
        asserted the answer, only the parts it is built from — which is the same
        mistake `test_check_rule_intent.py` made in its first version, where a
        correct re-implementation of the predicate was tested instead of the
        predicate.
        """
        target = "shared/src/commonMain/kotlin/com/singularity/todo/core/log/FileLogWriter.kt"
        self.assertIn(target, {str(p.relative_to(csi.ROOT)) for p, _, _, _ in csi.scan_file_suppressions()})

        out = self._run({})
        self.assertIn("UNJUSTIFIED", out)
        self.assertIn("FileLogWriter.kt", out)
        # An empty registry must not manufacture registry-level errors of its own.
        self.assertNotIn("empty reason", out)

        out_justified = self._run({target: "a log line stamps its own time"})
        self.assertIn("justified   shared/src/commonMain/kotlin/com/singularity/todo/core/log/FileLogWriter.kt", out_justified)
        # Scoped to this file: the other eleven are still unaccounted for, so a
        # repository-wide `assertNotIn` would be asserting a state #189 has not
        # reached yet — a test that fails for a reason unrelated to its subject.
        self.assertNotIn("FileLogWriter.kt:1 — @file:Suppress", out_justified)

    def test_every_shipped_entry_names_a_real_file_and_a_real_suppression(self):
        """The registry as committed, not as mutated by a test.

        Every entry must correspond to a file that exists and still carries a
        file-level custom suppression, and must say why. A registry that drifts
        is the defect `find-unwired-surfaces-baseline.txt` was created for.
        """
        for path, reason in csi.JUSTIFIED_FILE_SUPPRESSIONS.items():
            self.assertTrue(reason.strip(), f"{path}: empty reason")
            self.assertTrue((csi.ROOT / path).is_file(), f"{path}: does not exist")
            text = (csi.ROOT / path).read_text(encoding="utf-8")
            self.assertIn("@file:Suppress", text, f"{path}: no longer suppressed")


class TestKDocIsNotASuppression(unittest.TestCase):
    """Writing down why a suppression was removed must not re-register it.

    Found by doing exactly that: the note explaining that
    `@file:Suppress("NoDirectClockSystem")` had been deleted from
    `SavedSearchRepositoryImpl` had to *name* the annotation, and the scanner read
    the note as the annotation. The gate then demanded a justification for a
    justification, which is a gate that punishes writing things down.
    """

    def test_block_comment_state_tracks_a_multi_line_comment(self):
        lines = [
            "/**",
            " * Removed: `@file:Suppress(\"X\")`",
            " * more prose",
            " */",
            "@file:Suppress(\"Y\")",
        ]
        self.assertTrue(csi._inside_block_comment(lines, 1))
        self.assertTrue(csi._inside_block_comment(lines, 2))
        self.assertFalse(csi._inside_block_comment(lines, 3))
        self.assertFalse(csi._inside_block_comment(lines, 4))

    def test_a_kdoc_naming_the_annotation_is_not_counted(self):
        findings = csi.scan_file_suppressions()
        for path, _, _, line in findings:
            text = (path).read_text(encoding="utf-8").splitlines()
            self.assertFalse(
                csi._inside_block_comment(text, line - 1),
                f"{path.name}:{line} is inside a comment and is not a suppression",
            )

    def test_a_line_comment_naming_the_annotation_is_not_counted(self):
        # A trailing `//` mention is prose for the same reason.
        self.assertNotIn(
            "shared/src/commonMain/kotlin/com/singularity/todo/feature/search/data/SavedSearchRepositoryImpl.kt",
            {str(p.relative_to(csi.ROOT)) for p, _, _, _ in csi.scan_file_suppressions()},
        )


class TestCurrentRepositoryState(unittest.TestCase):
    def test_every_current_suppression_is_justified(self):
        """The gate is green, and the count is pinned so a change is visible.

        Nine files as of 2026-10-05, down from twelve: the four LIVE DEFECT
        entries were fixed (the #91 work) and `FakeRepositories.kt` moved its
        exemption into the rule's allow-list. Eight are
        `@file:Suppress("NoDirectClockSystem")` and one
        `@file:Suppress("NoRealDelayInTest")`. Every one carries a reason — five as
        a comment on the file, two through `JUSTIFIED_FILE_SUPPRESSIONS`, and three
        task screens that forward a `now` into `TimeEntryEditorSheet` inline.

        The count is a measurement, not a target: it moves when a file is fixed and
        when a deferral is recorded, and both are supposed to show up in review.
        It went 12 -> 6 -> 9 within one session, then back to 6. The 6 -> 9 move is
        the interesting one: threading a clock through a sheet made its *callers*
        read one, and the rule reported all three. A gate that only counts defects
        would have shown the same number; this one shows the reason each was added,
        which is the difference between a registry and a queue.

        The 9 -> 6 move is the one this pin exists to catch in the other direction.
        The three task screens carried a file-level suppression whose recorded
        reason was "threading `now` is four signature changes across three screens,
        ending in a call no desktop Compose test can execute (#201)". That is work
        not yet done stated as a justification, which is how a deferral turns into
        a permanent exemption. The signatures are made and `now` is a required
        parameter from the nav entry down, so the three screens no longer suppress
        anything.
        """
        findings = csi.scan_file_suppressions()
        self.assertEqual(len(findings), 6, f"expected 6, got {len(findings)}")
        self.assertEqual(
            {rule for _, rule, _, _ in findings},
            {"NoDirectClockSystem", "NoRealDelayInTest"},
        )
        unjustified = [
            f"{path.relative_to(csi.ROOT)}:{line}"
            for path, rule, block, line in findings
            if str(path.relative_to(csi.ROOT)) not in csi.JUSTIFIED_FILE_SUPPRESSIONS
            and not csi.has_adjacent_reason(block)
        ]
        self.assertEqual(unjustified, [], f"unjustified: {unjustified}")

    def test_every_registry_entry_names_its_issue_or_its_shape(self):
        """A recorded exemption has to say which of the two it is.

        "Live defect, tracked in #N" and "the injection is honoured, only the
        default names the system clock" are different claims with different
        consequences, and a registry that mixed them would make a temporary
        exemption indistinguishable from a permanent one.
        """
        for path, reason in csi.JUSTIFIED_FILE_SUPPRESSIONS.items():
            self.assertTrue(
                ("LIVE DEFECT" in reason) or ("#192" in reason) or ("false positive" in reason)
                or ("preview" in reason.lower()) or ("log line" in reason),
                f"{path}: the reason does not say what kind of exemption this is",
            )

    def test_no_built_in_rule_is_policed(self):
        """Only this repository's own rules. `LongMethod` is ordinary practice."""
        findings = csi.scan_file_suppressions()
        for _, rule, _, _ in findings:
            self.assertIn(rule, csi.custom_rule_ids())


if __name__ == "__main__":
    unittest.main()
