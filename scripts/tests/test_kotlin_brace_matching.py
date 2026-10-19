"""The Python half of the shared "where does this block end" fixture table.

`config/test-fixtures/kotlin-brace-matching.txt` is the single definition of
the answer. This suite proves `source_text.block_body` agrees with it;
`shared/src/jvmTest/.../KotlinTextFixtureTest.kt` proves the same for the
Kotlin lexer.

Why a data file and not shared constants: the two implementations are in two
languages, so a test written in one of them cannot compare them. The fixtures
are the contract, and both suites read the same bytes.

The failure this guards against is the one that made five real test classes
invisible to the test-class inventory and that made 103 function bodies in 27
files of `:shared` end in the wrong place: a brace that is not a brace, because
it lives in a string, in a KDoc or in the text of a template.
"""

from __future__ import annotations

import unittest
from pathlib import Path

from scanners.source_text import block_body

REPO_ROOT = Path(__file__).resolve().parents[2]
FIXTURE_FILE = REPO_ROOT / "config" / "test-fixtures" / "kotlin-brace-matching.txt"

HEADER = "=== "
UNCLOSED = "unclosed"


def _decode(expected: str) -> str | None:
    """The expected body as text, or None when the record says `unclosed`."""
    if expected == UNCLOSED:
        return None
    out = []
    i = 0
    while i < len(expected):
        ch = expected[i]
        if ch == "\\" and i + 1 < len(expected):
            nxt = expected[i + 1]
            if nxt == "n":
                out.append("\n")
            elif nxt == "t":
                out.append("\t")
            elif nxt == "\\":
                out.append("\\")
            else:
                out.append(nxt)
            i += 2
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def _load() -> list[tuple[str, str, str]]:
    """The records: (id, expected body or `unclosed`, source)."""
    if not FIXTURE_FILE.is_file():
        raise AssertionError(
            f"missing shared fixture table: {FIXTURE_FILE} — it is the contract between "
            "the Python and the Kotlin lexer, and both suites read it",
        )
    records: list[tuple[str, str, str]] = []
    current_id: str | None = None
    expected = ""
    body: list[str] = []

    def flush() -> None:
        if current_id is not None:
            records.append((current_id, expected, "\n".join(body)))

    for line in FIXTURE_FILE.read_text(encoding="utf-8").splitlines():
        if line.startswith(HEADER):
            flush()
            header = line.removeprefix(HEADER)
            current_id, _, expected = header.partition("|")
            current_id = current_id.strip()
            expected = expected.strip()
            body = []
        elif current_id is not None:
            body.append(line)
    flush()

    if not records:
        raise AssertionError(f"no fixtures parsed from {FIXTURE_FILE}")
    return records


class TheBlockEndIsTheSameOnBothSides(unittest.TestCase):
    def test_every_fixture_matches_its_agreed_body(self) -> None:
        failures = []
        for fixture_id, expected, source in _load():
            actual = block_body(source)
            wanted = _decode(expected)
            if actual != wanted:
                failures.append(
                    f"  {fixture_id}: expected {wanted!r}, got {actual!r}",
                )
        self.assertEqual(
            [],
            failures,
            "the Python block matcher disagrees with the shared fixture table at "
            f"{FIXTURE_FILE}:\n" + "\n".join(failures),
        )

    def test_the_table_covers_the_hard_shapes(self) -> None:
        ids = {fixture_id for fixture_id, _, _ in _load()}
        for required in (
            "string_with_a_closing_brace",
            "raw_string_with_a_brace",
            "kdoc_with_a_brace",
            "nested_block_comment",
            "char_literal_brace",
            "url_in_a_string",
            "template_with_a_block",
        ):
            self.assertIn(required, ids, f"the table has no {required} record")

    def test_the_table_has_an_unclosed_record(self) -> None:
        # The positive control: a matcher that answered "the end of the file"
        # for everything would pass every other record.
        expected = {expected for _, expected, _ in _load()}
        self.assertIn(UNCLOSED, expected)

    def test_fixture_ids_are_unique(self) -> None:
        ids = [fixture_id for fixture_id, _, _ in _load()]
        self.assertEqual(len(ids), len(set(ids)), "duplicate fixture ids")


if __name__ == "__main__":
    unittest.main()
