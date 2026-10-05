"""The Python half of the shared "runnable test member" fixture table.

Run with: python3 -m unittest discover -s scripts/tests

`config/test-fixtures/runnable-test-members.txt` is the single definition of
what makes a class runnable. This suite proves `infra/kiwi/sync.py` agrees with
it; `TestTagCoverageTest` (Kotlin, shared/src/jvmTest) proves the other half.

The two implementations diverged once: the Kotlin gate matched only
`startsWith("@Test")` and therefore reported the two `@ParameterizedTest`
recurrence classes as clean while CI silently skipped them. A shared fixture
table turns the next divergence into a red test on both sides at once, which is
the only way "they agree" stays true without a human reading two regexes
side by side.
"""

import importlib.util
import pathlib
import sys
import unittest

_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
_kiwi_dir = _ROOT / "infra" / "kiwi"
sys.path.insert(0, str(_kiwi_dir))

_spec = importlib.util.spec_from_file_location("kiwi_sync", _kiwi_dir / "sync.py")
_sync = importlib.util.module_from_spec(_spec)
_sync.__name__ = "kiwi_sync"
_sync.__file__ = str(_kiwi_dir / "sync.py")
# Must precede exec_module: the @dataclass at class-definition time looks the
# module up in sys.modules[cls.__module__] and fails without this record.
sys.modules["kiwi_sync"] = _sync
_spec.loader.exec_module(_sync)

sync = _sync

FIXTURE_FILE = _ROOT / "config" / "test-fixtures" / "runnable-test-members.txt"

HEADER = "=== "


def load_fixtures() -> list[tuple[str, bool, str]]:
    """(id, expected_runnable, source) for every record in the shared table.

    A record header is `=== <id> | <expected>`; the source is every following
    line up to the next header. Raising on a malformed table is deliberate: a
    fixture table that silently loses a record is how a green suite starts
    asserting nothing.
    """
    if not FIXTURE_FILE.exists():
        raise AssertionError(f"missing shared fixture table: {FIXTURE_FILE}")
    records: list[tuple[str, bool, str]] = []
    ident: str | None = None
    expected: bool | None = None
    body: list[str] = []

    def flush() -> None:
        if ident is not None and expected is not None:
            records.append((ident, expected, "\n".join(body)))

    for line in FIXTURE_FILE.read_text(encoding="utf-8").splitlines():
        if line.startswith(HEADER):
            flush()
            ident, _, verdict = line[len(HEADER):].strip().partition("|")
            ident, verdict = ident.strip(), verdict.strip()
            if verdict not in {"runnable", "not-runnable"}:
                raise AssertionError(f"fixture {ident!r}: bad verdict {verdict!r}")
            expected = verdict == "runnable"
            body = []
        elif ident is None:
            # The table's own header comment, above the first record. Inside a
            # record a `#` line is part of the Kotlin source and is kept, exactly
            # as RunnableTestFixtureTest keeps it — the two readers must feed the
            # predicates byte-identical input or their agreement means nothing.
            continue
        else:
            body.append(line)
    flush()
    if not records:
        raise AssertionError(f"no fixtures parsed from {FIXTURE_FILE}")
    return records


class SharedRunnableMemberFixtures(unittest.TestCase):
    """sync.py must give every shared fixture the agreed verdict."""

    def test_table_is_not_empty_and_covers_both_verdicts(self):
        records = load_fixtures()
        verdicts = {expected for _, expected, _ in records}
        self.assertEqual(
            verdicts,
            {True, False},
            "the table needs both runnable and not-runnable records: a table of "
            "only positive cases cannot catch a predicate that matches "
            "everything, which is the other way this gate fails",
        )

    def test_ids_are_unique(self):
        ids = [ident for ident, _, _ in load_fixtures()]
        duplicates = {i for i in ids if ids.count(i) > 1}
        self.assertEqual(duplicates, set(), "duplicate fixture ids")

    def test_every_fixture_matches_its_expected_verdict(self):
        failures = []
        for ident, expected, source in load_fixtures():
            actual = sync.has_runnable_test(source)
            if actual != expected:
                failures.append(
                    f"  {ident}: expected {'runnable' if expected else 'not-runnable'}, "
                    f"got {'runnable' if actual else 'not-runnable'}"
                )
        self.assertEqual(
            failures,
            [],
            "sync.py disagrees with the shared runnable-test fixture table:\n"
            + "\n".join(failures),
        )


if __name__ == "__main__":
    unittest.main()
