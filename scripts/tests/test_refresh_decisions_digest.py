"""`refresh-decisions-digest.sh --quiet` was a flag nobody read (#518).

Both git hooks called it that way and the script never looked at `sys.argv` at all —
it had no argument parser to look with. The flag survived because a dead flag is
invisible: the call succeeds, the output looks suppressed, and the next person to add
a real option assumes there is a parser and writes around it.

These lock down that the script now refuses arguments it does not implement, with the
same exit code `singularity-adr` uses for a mistyped subcommand.

The second half covers the 2026-10-15 split into an always-loaded orientation layer and
an on-demand full index — mostly the *shared* numbers, because a budget declared twice in
two files is how #520 happened.
"""

import importlib.util
import pathlib
import re
import tempfile
import unittest

MODULE_PATH = (pathlib.Path(__file__).resolve().parent.parent
               / 'refresh-decisions-digest.py')
spec = importlib.util.spec_from_file_location('refresh_decisions_digest', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

ROOT = pathlib.Path(__file__).resolve().parent.parent.parent


class ArgvTest(unittest.TestCase):

    def _exit_code(self, args):
        try:
            mod.parse_argv(args)
        except SystemExit as exc:
            return exc.code or 0
        return None  # returned normally — no error

    def test_no_arguments_is_the_whole_interface(self):
        self.assertIsNone(self._exit_code([]))

    def test_the_dead_quiet_flag_is_now_a_usage_error(self):
        """The flag `.githooks/post-checkout` and `post-merge` used to pass."""
        self.assertEqual(self._exit_code(['--quiet']), 2)

    def test_apply_is_refused_too(self):
        """This script always writes; there is no report mode to opt out of."""
        self.assertEqual(self._exit_code(['--apply']), 2)

    def test_help_exits_zero(self):
        self.assertEqual(self._exit_code(['--help']), 0)


class BudgetIsDeclaredOnceTest(unittest.TestCase):
    """The generator holds the digest budgets, and it is their only owner.

    The second copy lived in a doc-sizes script that was removed on 2026-10-19, so
    the #520 shape — one rule, two owners, drifting apart — no longer applies here:
    there is one owner left. The budget is the generator's own constant, and the
    gate that reports it reads the number the generator prints.
    """

    def test_orientation_budget_is_the_generators_own_constant(self):
        self.assertEqual(mod.MAX_ORIENTATION_LINES, 120)

    def test_full_digest_budget_is_the_generators_own_constant(self):
        self.assertEqual(mod.MAX_DIGEST_FULL_LINES, 3000)

    def test_the_budget_constants_are_not_duplicated_anywhere(self):
        """A second copy of either number is how the two drifted apart before."""
        repo_text = "\n".join(
            p.read_text(encoding="utf-8")
            for p in sorted((ROOT / "scripts").rglob("*.py"))
            if p.name != pathlib.Path(__file__).name
        )
        self.assertNotIn("DIGEST_MAX", repo_text)
        self.assertNotIn("DIGEST_FULL_MAX", repo_text)

    def test_the_orientation_layer_has_room_for_its_own_sections(self):
        """A budget below the fixed cost of the sections would fail every run.

        The point of MAX_ORIENTATION_LINES is that it does not track corpus size, so this
        is a floor check: the sections that never grow must fit inside it.
        """
        fixed_cost = len(mod.ROOT_POINTERS) + 12  # pointer rows + headers + tree
        self.assertLess(fixed_cost, mod.MAX_ORIENTATION_LINES)


class OrientationLayerTest(unittest.TestCase):

    def test_wrap_tags_respects_its_width(self):
        tags = [f'tag-number-{i}' for i in range(40)]
        for line in mod.wrap_tags(tags, width=96):
            self.assertLessEqual(len(line), 96)

    def test_wrap_tags_does_not_drop_or_duplicate_a_tag(self):
        tags = [f'tag-number-{i}' for i in range(40)]
        joined = ' '.join(mod.wrap_tags(tags, width=96))
        for tag in tags:
            self.assertEqual(joined.count(f'`{tag}`'), 1)

    def test_wrap_tags_of_nothing_is_nothing(self):
        self.assertEqual(mod.wrap_tags([]), [])

    def test_the_tree_says_how_big_the_collapsed_directory_is(self):
        """Hiding a directory without saying so is the failure mode this file exists to avoid."""
        lines = mod.render_doc_tree(590, 0, 14)
        joined = '\n'.join(lines)
        self.assertIn('590 decisions', joined)
        # `deferred/` is asserted in the breakdown test below rather than here: it was
        # deleted with the rest of docs/decisions/deferred/ in PR #645, and its 129
        # entries migrated to GitHub issues. A renderer that still printed a count for
        # a directory that no longer exists would be reporting a number about nothing.
        self.assertNotIn('deferred backlog entries', joined)
        self.assertIn('14 non-decision records, excluded', joined)

    def test_the_tree_reports_residual_files_instead_of_hiding_them(self):
        """`docs/decisions/` holds files that are neither decisions nor backlog."""
        lines = mod.render_doc_tree(3, 2, 1)
        joined = '\n'.join(lines)
        self.assertIn('other note', joined)

    def test_the_collapsed_directory_accounts_for_every_subdirectory(self):
        """The breakdown must add up, or a reader stops trusting the whole tree."""
        lines = mod.render_doc_tree(592, 0, 14)
        joined = '\n'.join(lines)
        # Derived from what is on disk rather than hardcoded. The hardcoded list
        # still named `deferred/` after PR #645 deleted it, so this test asserted a
        # collapsed directory the renderer was right to stop drawing.
        present = [
            d.name + '/' for d in mod.DECISIONS_DIR.iterdir()
            if d.is_dir() and d.name in mod.CORPUS.all_slugs() or
            (d.is_dir() and d.name in ('archive', 'templates', 'deferred'))
        ]
        self.assertTrue(present, "expected at least one collapsed subdirectory on disk")
        for name in present:
            self.assertIn(name, joined, f"{name} exists on disk but is hidden from the tree")

    def test_a_missing_subdirectory_is_absent_not_an_exception(self):
        """`docs/architecture/` did not exist until the reference-skill migration landed.

        The orientation layer is generated on every `git fetch`, so it must render whatever
        is on disk rather than require a particular phase to have run.
        """
        with tempfile.TemporaryDirectory() as tmp:
            docs = pathlib.Path(tmp) / 'docs'
            decisions = docs / 'decisions'
            decisions.mkdir(parents=True)
            (docs / 'testing').mkdir()
            (decisions / 'deferred').mkdir()
            (decisions / 'README.md').write_text('not an adr', encoding='utf-8')
            lines = mod.docs_tree.render(
                docs, decisions, docs / 'architecture',
                decision_count=0, deferred_count=0, archived_count=0,
                known_slugs=set(), deferred_dir='deferred', archive_dir='archive')
        joined = '\n'.join(lines)
        # A directory that is not on disk is not listed — the tree shows what exists, and
        # the rest of it still renders rather than the whole generation failing.
        self.assertNotIn('architecture/', joined)
        self.assertIn('testing/', joined)
        self.assertIn('decisions/', joined)
        self.assertIn('1 other note', joined)

    def test_the_tree_is_deterministic(self):
        self.assertEqual(mod.render_doc_tree(5, 5, 5),
                         mod.render_doc_tree(5, 5, 5))


if __name__ == '__main__':
    unittest.main(verbosity=2)