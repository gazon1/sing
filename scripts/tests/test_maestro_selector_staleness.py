#!/usr/bin/env python3
"""Unit tests for the staleness rule in scripts/ci/validate-maestro-selectors.py.

`DYNAMIC_EXPANSIONS` is a literal set of ids that flows are validated against.
Nothing expands `saved_agenda_card_${name}` at validation time, so an id that
exists in TestTags.kt but is missing from this set is an id a flow cannot legally
use — the validator answers "unknown id".

That is what makes deletion dangerous. An entry unused by any flow looks like
leftovers, and on 2026-10-19 it looked like 28 of them: every one an output of a
generator still declared in TestTags.kt (`savedAgendaCard("personal")`,
`calendarDay("2026-09-15")`, `genUi("whatsnew")`). Removing them would have looked
like cleanup and been a trapdoor for the next flow written against any of those
functions — the warning the removal was based on was itself the thing to fix.

So the rule under test is the distinction: *unused* is not *stale*. Stale means
the generator is gone. These tests pin both halves, because a rule that only
rejects the false positives stops catching the real event it exists for.
"""
from __future__ import annotations

import importlib.util
import unittest
from pathlib import Path

_ROOT = Path(__file__).resolve().parent.parent.parent
_VALIDATOR = _ROOT / "scripts" / "ci" / "validate-maestro-selectors.py"


def _load_validator():
    """Import the real validator by path.

    The filename has a dash, so it cannot be imported by module name, and copying
    the rule into the test would test the copy — which is how a gate and its
    tests agree on something nobody runs.
    """
    spec = importlib.util.spec_from_file_location("validate_maestro_selectors", _VALIDATOR)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class StaleEntryRuleTest(unittest.TestCase):
    """`_is_stale_entry` and the prefixes it decides against."""

    @classmethod
    def setUpClass(cls):
        cls.mod = _load_validator()
        cls.prefixes = cls.mod._live_dynamic_prefixes()

    def test_live_prefixes_are_read_from_testtags(self):
        """The prefix set comes from TestTags.kt, not from a hand-kept copy.

        A snapshot here would drift the same way `DYNAMIC_EXPANSIONS` did, and
        drift silently: a prefix removed from TestTags.kt would keep reporting
        every entry beneath it as live.
        """
        self.assertGreater(
            len(self.prefixes), 20,
            "expected TestTags.kt to declare 20+ dynamic id generators, "
            f"parsed {len(self.prefixes)}",
        )
        self.assertIn("saved_agenda_card_", self.prefixes)
        self.assertIn("calendar_day_", self.prefixes)

    def test_an_entry_a_live_generator_can_produce_is_not_stale(self):
        """The false positive this rule exists to remove.

        `genui_whatsnew` was one of the 28 named as unused-but-stale.
        `fun genUi(name: String) = "genui_${slug(name)}"` still exists, so no
        flow written tomorrow can be rejected because of it.
        """
        for entry in (
            "genui_whatsnew",
            "saved_agenda_card_personal",
            "calendar_day_2026_09_15",
            "settings_accent_swatch_green",
            "reminder_offset_radio_1_hour_before",
        ):
            with self.subTest(entry=entry):
                self.assertFalse(
                    self.mod._is_stale_entry(entry, self.prefixes),
                    f"{entry} is still generatable by TestTags.kt, so removing it "
                    "would reject a future flow that legitimately uses it",
                )

    def test_an_entry_whose_generator_is_gone_is_stale(self):
        """The real event the warning exists for.

        Symmetric with the case above on purpose: a rule that only suppresses
        false positives stops being a check. A removed feature leaves an entry
        nothing can produce, and that entry should be reported.

        The ids below sit under no generator in TestTags.kt at all — deliberately
        not a near-miss like `dialog_title_nonexistent`, which is under the live
        `dialog_title_` prefix and is therefore still producible.
        """
        for entry in (
            "totally_removed_feature_widget",
            "weather_card_beijing",
            "legacy_task_board_column",
        ):
            with self.subTest(entry=entry):
                self.assertTrue(
                    self.mod._is_stale_entry(entry, self.prefixes),
                    f"{entry} has no generator behind it and should be reported",
                )

    def test_every_live_prefix_ends_at_a_word_boundary(self):
        """Why `startswith` is the right test, and the boundary it must respect.

        Prefixes come from literals like `"project_card_${"`, so each ends with the
        underscore that separates the fixed part from the interpolated one. That is
        what stops `project_card_` from absorbing a sibling generator that merely
        shares a stem. Asserting it on the parsed prefixes is what keeps a future
        generator written as `"project${"` from silently widening the rule.
        """
        for prefix in sorted(self.prefixes):
            with self.subTest(prefix=prefix):
                self.assertTrue(
                    prefix.endswith("_"),
                    f"{prefix!r} does not end at a word boundary, so startswith "
                    "would absorb ids belonging to a different generator",
                )

    def test_no_prefixes_means_everything_is_stale(self):
        """An unreadable TestTags.kt must not read as "nothing is stale".

        Degrading to silence is the failure mode that matters here: the gate would
        go quiet on exactly the removals it was added to notice. Reporting
        everything is the safe direction — it is noisy, and noise is visible.
        """
        self.assertTrue(self.mod._is_stale_entry("anything", set()))
        self.assertTrue(self.mod._is_stale_entry("saved_agenda_card_personal", set()))


if __name__ == "__main__":
    unittest.main()