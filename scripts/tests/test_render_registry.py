"""Tests for the generated gate-registry table.

The table is a Markdown table, so a row that wraps is a row that no longer
parses: the reader sees a gate with three cells and the header says six.
The purpose of a gate comes from a docstring, and a docstring wraps.
"""
from __future__ import annotations

import pathlib
import re
import sys
import unittest

SCRIPTS = pathlib.Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS / "src"))

from sgate.commands.render_registry import _cell, _generate_table  # noqa: E402

COMMANDS_DIR = SCRIPTS / "src" / "sgate" / "commands"


def _cells(row: str) -> list[str]:
    """Split a table row into cells, ignoring the pipes inside an escaped one."""
    return re.split(r"(?<!\\)\|", row)


class GateRegistryTableTest(unittest.TestCase):
    def test_a_wrapped_purpose_stays_one_row(self) -> None:
        self.assertEqual(
            _cell("prove the declared schema, the exported\nschema, and the chain"),
            "prove the declared schema, the exported schema, and the chain",
        )

    def test_a_pipe_in_the_prose_does_not_end_the_cell(self) -> None:
        self.assertEqual(_cell("a | b"), "a \\| b")

    def test_every_gate_is_one_row_of_six_cells(self) -> None:
        rows = _generate_table(COMMANDS_DIR).splitlines()
        self.assertTrue(rows, "the registry has no gates")
        for row in rows:
            with self.subTest(row=row[:60]):
                self.assertTrue(row.startswith("| ") and row.endswith(" |"), row)
                self.assertEqual(len(_cells(row)) - 2, 6, row)


if __name__ == "__main__":
    unittest.main()
