"""Unit tests for `scanners.maestro_flow` — the one reader of a flow's tags.

Run with: python3 -m unittest discover -s scripts/tests

Three modules act on the `tags:` block of a Maestro flow: the runner that
selects which flows execute (`emulator.maestro._flow`), the traceability
scanner that turns `scenario:` tags into links (`traceability.links`), and
the suggester that proposes tags for a flow (`traceability.maestro_suggest`).

They used to read the block three ways, and a flow whose tags block sat in a
later document was visible to one of them and invisible to the others. The
reader is now one, and these tests are what keep it one: the semantics are
pinned here, and the agreement of the three consumers is asserted against
the real flows rather than against a copy of the rule.
"""

import pathlib
import unittest

from scanners.maestro_flow import flow_tags_text, tag_entries

_ROOT = pathlib.Path(__file__).resolve().parent.parent.parent
_FLOWS = _ROOT / "Maestro" / "flows"


class TestTagEntries(unittest.TestCase):
    """The shape of a `tags:` block."""

    def test_reads_the_tags_of_the_header(self):
        text = "appId: com.singularity.todo\ntags:\n  - smoke\n  - scenario:SC-1\n---\nlaunchApp\n"
        self.assertEqual(flow_tags_text(text), ["smoke", "scenario:SC-1"])

    def test_a_document_separator_ends_the_block(self):
        text = "tags:\n  - smoke\nappId: x\n---\nlaunchApp\n"
        self.assertEqual(flow_tags_text(text), ["smoke"])

    def test_a_later_document_declares_its_own_tags(self):
        """The runner reads a flow document by document, and so must the rest.

        This is the case the three readers disagreed on: the suggester stopped
        at the first `---` and saw no tags at all.
        """
        text = "appId: x\n---\ntags:\n  - smoke\n"
        self.assertEqual(flow_tags_text(text), ["smoke"])

    def test_a_command_is_not_a_tag(self):
        text = "tags:\n  - smoke\n---\n- tapOn: 'Login'\n"
        self.assertEqual(flow_tags_text(text), ["smoke"])

    def test_quotes_and_a_trailing_comment_are_not_part_of_the_tag(self):
        text = "tags:\n  - 'smoke'  # the smoke suite\n"
        self.assertEqual(flow_tags_text(text), ["smoke"])

    def test_a_blank_line_and_a_comment_do_not_close_the_block(self):
        text = "tags:\n  - smoke\n\n  # a comment\n  - full\n"
        self.assertEqual(flow_tags_text(text), ["smoke", "full"])

    def test_the_line_number_is_the_line_the_tag_was_declared_on(self):
        text = "appId: x\ntags:\n  - smoke\n"
        self.assertEqual(tag_entries(text), [(3, "smoke")])


class TestTheThreeConsumersAgree(unittest.TestCase):
    """One reader, three consumers — asserted on the real flows.

    The runner filters on tags, the traceability scanner links them, and the
    suggester proposes them. When they disagree, one of them is wrong about a
    fact the others act on, and nothing in the build says so.
    """

    @staticmethod
    def _flows() -> list[pathlib.Path]:
        return sorted(_FLOWS.rglob("*.yaml"))

    def test_there_are_flows_to_check(self):
        self.assertGreater(len(self._flows()), 10)

    def test_the_runner_and_the_reader_agree(self):
        from emulator.maestro._flow import flow_tags

        for path in self._flows():
            self.assertEqual(
                flow_tags(path),
                flow_tags_text(path.read_text()),
                f"{path}: the runner and the shared reader disagree",
            )

    def test_the_traceability_scanner_and_the_reader_agree(self):
        from traceability.links import scan_maestro
        from traceability.spec import load_specs

        specs = load_specs(_ROOT / "scripts" / "src" / "traceability" / "scenarios")
        linked = {link.detail for link in scan_maestro(specs, root=_ROOT)}
        for path in self._flows():
            for _, tag in tag_entries(path.read_text()):
                if not tag.startswith("scenario:"):
                    continue
                scenario = tag[len("scenario:"):].strip()
                if scenario not in specs:
                    continue
                self.assertIn(
                    tag, linked, f"{path}: {tag} was declared but not linked"
                )

    def test_the_suggester_and_the_reader_agree(self):
        from traceability.maestro_suggest import _header_tags

        for path in self._flows():
            self.assertEqual(
                _header_tags(path.read_text()),
                flow_tags_text(path.read_text()),
                f"{path}: the suggester and the shared reader disagree",
            )


if __name__ == "__main__":
    unittest.main()
