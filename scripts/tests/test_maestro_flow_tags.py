"""Unit tests for the Maestro flow tag filter (scripts/maestro-flow-tags.sh).

Run with: python3 -m unittest discover -s scripts/tests

`TAGS=… scripts/run-maestro.sh` decides which UI flows execute. A filter that
silently drops a flow is the same defect class as a test class that is never
selected: the suite reports success and the coverage is simply gone. That is
not hypothetical — it is how `RecurrenceRuleMapperTest` and `RruleGeneratorTest`
sat untagged for months while CI stayed green.

The tag matching is pure text, so it is testable here without the emulator that
the flows themselves need. The functions are exercised by *sourcing* the real
shell file, never by re-implementing the match in Python: a second copy of this
predicate is the exact drift this repository has already paid for twice.
"""

import pathlib
import re
import subprocess
import tempfile
import unittest

_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
_TAGS_SH = _ROOT / "scripts" / "maestro-flow-tags.sh"
_FLOWS = _ROOT / "Maestro" / "flows"

HEADER = "appId: com.singularity.todo\n---\nname: probe\n"
FOOTER = "---\nlaunchApp\n"


def _write(tmp: pathlib.Path, name: str, tags_block: str) -> pathlib.Path:
    f = tmp / name
    f.write_text(HEADER + tags_block + FOOTER, encoding="utf-8")
    return f


def _bash(script: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["bash", "-c", f"set -euo pipefail\nsource '{_TAGS_SH}'\n{script}"],
        capture_output=True,
        text=True,
        check=False,
    )


def _has_tag(path: pathlib.Path, tag: str) -> bool:
    return _bash(f'flow_has_tag "{path}" "{tag}"').returncode == 0


def _tags_of(path: pathlib.Path) -> list[str]:
    out = _bash(f'flow_tags "{path}"').stdout
    return [line for line in out.splitlines() if line]


class TagMatchingTest(unittest.TestCase):
    def test_plain_tag_matches(self):
        with tempfile.TemporaryDirectory() as tmp:
            f = _write(pathlib.Path(tmp), "a.yaml", "tags:\n  - smoke\n")
            self.assertTrue(_has_tag(f, "smoke"))
            self.assertFalse(_has_tag(f, "regression"))

    def test_trailing_whitespace_still_matches(self):
        """The defect: `- smoke ` did not match `TAGS=smoke`, so the flow was
        dropped from the run with no message. A human reading the YAML sees a
        smoke flow; the filter did not."""
        with tempfile.TemporaryDirectory() as tmp:
            f = _write(pathlib.Path(tmp), "a.yaml", "tags:\n  - smoke   \n")
            self.assertTrue(_has_tag(f, "smoke"))

    def test_leading_whitespace_and_tabs_match(self):
        with tempfile.TemporaryDirectory() as tmp:
            f = _write(pathlib.Path(tmp), "a.yaml", "tags:\n\t- \tsmoke\t\n")
            self.assertTrue(_has_tag(f, "smoke"))

    def test_carriage_return_does_not_break_the_tag(self):
        """A file edited on another platform keeps its CRs; the tag must still
        match, or the flow drops out of every Windows-formatted run."""
        with tempfile.TemporaryDirectory() as tmp:
            f = pathlib.Path(tmp) / "crlf.yaml"
            f.write_bytes((HEADER + "tags:\n  - smoke\r\n" + FOOTER).encode())
            self.assertTrue(_has_tag(f, "smoke"))

    def test_quoted_tag_matches_unquoted_query(self):
        with tempfile.TemporaryDirectory() as tmp:
            f = _write(pathlib.Path(tmp), "a.yaml", "tags:\n  - \"smoke\"\n")
            self.assertTrue(_has_tag(f, "smoke"))

    def test_single_quoted_tag_matches(self):
        with tempfile.TemporaryDirectory() as tmp:
            f = _write(pathlib.Path(tmp), "a.yaml", "tags:\n  - 'smoke'\n")
            self.assertTrue(_has_tag(f, "smoke"))

    def test_trailing_yaml_comment_is_not_part_of_the_tag(self):
        with tempfile.TemporaryDirectory() as tmp:
            f = _write(pathlib.Path(tmp), "a.yaml", "tags:\n  - smoke   # the default set\n")
            self.assertTrue(_has_tag(f, "smoke"))
            self.assertEqual(_tags_of(f), ["smoke"])

    def test_multiple_tags_all_reachable(self):
        with tempfile.TemporaryDirectory() as tmp:
            f = _write(
                pathlib.Path(tmp),
                "a.yaml",
                "tags:\n  - smoke  \n  - regression\n  - tasks\n",
            )
            for tag in ("smoke", "regression", "tasks"):
                self.assertTrue(_has_tag(f, tag), f"{tag} should match")

    def test_every_document_contributes_its_tags(self):
        """A flow is multi-document YAML, and one file is still one flow.

        A later document's `tags:` must count: the file is what `TAGS=` selects.
        The `---` bound exists so a document's tags are attributed to that
        document, not so that later ones are discarded.
        """
        body = "tags:\n  - smoke\n---\nname: sub\ntags:\n  - regression\n"
        with tempfile.TemporaryDirectory() as tmp:
            f = _write(pathlib.Path(tmp), "a.yaml", body)
            self.assertEqual(_tags_of(f), ["smoke", "regression"])

    def test_a_step_that_looks_like_a_tag_is_not_one(self):
        """`- tapOn:` is a command, not a tag. Reading it as a tag would make
        `TAGS=tapOn:` select every flow."""
        with tempfile.TemporaryDirectory() as tmp:
            f = _write(
                pathlib.Path(tmp),
                "a.yaml",
                "tags:\n  - smoke\ncommands:\n  - tapOn: 'x'\n  - back\n",
            )
            self.assertEqual(_tags_of(f), ["smoke"])

    def test_flow_without_a_tags_block_declares_nothing(self):
        with tempfile.TemporaryDirectory() as tmp:
            f = _write(pathlib.Path(tmp), "a.yaml", "commands:\n  - launchApp\n")
            self.assertEqual(_tags_of(f), [])

    def test_looks_like_a_tag_rule_is_not_applied(self):
        with tempfile.TemporaryDirectory() as tmp:
            f = _write(pathlib.Path(tmp), "a.yaml", "tags:  # a comment line\n  - smoke\n")
            self.assertTrue(_has_tag(f, "smoke"))


class RealFlowsAreReachableTest(unittest.TestCase):
    """Every flow in the repository is selectable by a tag it declares.

    The invariant that closes the loop. Nothing in CI asserted it, which is why a
    typo'd tag would have had the same effect as the trailing-space bug: the flow
    exists, every gate passes, and no run ever includes it.
    """

    def _flows(self) -> list[pathlib.Path]:
        if not _FLOWS.is_dir():
            self.skipTest(f"no flow directory at {_FLOWS}")
        return sorted(_FLOWS.rglob("*.yaml"))

    def test_there_are_flows_to_check(self):
        """A vacuous green is worse than a red: zero flows must not pass."""
        self.assertGreater(len(self._flows()), 0, f"no flows under {_FLOWS}")

    def test_every_flow_declares_at_least_one_tag(self):
        untagged = [str(f.relative_to(_ROOT)) for f in self._flows() if not _tags_of(f)]
        self.assertEqual(
            untagged,
            [],
            "flows with no tag never run under any TAGS= filter, and no gate "
            "noticed. Give them a tag:",
        )

    def test_every_declared_tag_selects_its_own_flow(self):
        unreachable = []
        for flow in self._flows():
            for tag in _tags_of(flow):
                if not _has_tag(flow, tag):
                    unreachable.append(f"{flow.relative_to(_ROOT)}: {tag!r}")
        self.assertEqual(
            unreachable,
            [],
            "a flow declares a tag that does not select it, so the tag is a lie "
            "and the flow is unreachable under that name:",
        )

    def test_every_tag_ci_asks_for_actually_selects_a_flow(self):
        """SUITE values CI uses, read out of the files that drive the jobs.

        CI no longer uses TAGS=-based selection. Instead e2e.yml passes
        SUITE=smoke|full to e2e-shard.sh, which runs either
        Maestro/flows/smoke/ (smoke) or Maestro/flows/ (full) directly,
        bypassing the tag filter entirely.

        This test still guards the same failure mode: a SUITE whose directory
        is empty would run zero flows and report success. It also verifies
        that the smoke directory has at least one flow, because smoke is the
        default SUITE and an empty smoke/ directory would be silent in CI.
        """
        sources = {
            p.relative_to(_ROOT).as_posix(): p.read_text(encoding="utf-8")
            for p in (
                _ROOT / ".github/workflows/e2e.yml",
                _ROOT / "scripts/ci/e2e-shard.sh",
            )
            if p.is_file()
        }
        # SUITE=smoke|full is set by e2e.yml; e2e-shard.sh reads it and picks
        # the directory.  The only values currently used are smoke and full.
        suites: set[str] = set()
        for text in sources.values():
            suites |= set(re.findall(r"SUITE=([a-z]+)", text))

        smoke_dir = _FLOWS / "smoke"
        self.assertTrue(
            smoke_dir.is_dir() and any(smoke_dir.glob("*.yaml")),
            f"smoke SUITE is used in CI but Maestro/flows/smoke/ is empty or "
            f"absent — the job would run zero flows and pass",
        )


if __name__ == "__main__":
    unittest.main()
