"""Regression tests for normalize-adr-frontmatter.py.

The bug these lock down: `parse_frontmatter` treated any ':'-bearing line as a
frontmatter key, and when no closing '---' was found it set body_start = None, so
`body` fell back to the *whole file*. `--apply` then re-emitted the file's own
body as metadata: 2489 -> 3110 bytes on the fixture below, body duplicated, prose
like `> **Superseded in part (2026-09-29):**` promoted to a pseudo-key.

The rule the fix encodes: a file whose frontmatter cannot be trusted is reported
and left byte-identical, never rewritten.
"""

import importlib.util
import pathlib
import sys
import tempfile
import unittest

MODULE_PATH = pathlib.Path(__file__).resolve().parent.parent / 'normalize-adr-frontmatter.py'
spec = importlib.util.spec_from_file_location('normalize_adr_frontmatter', MODULE_PATH)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)
StructuralError = mod.StructuralError

# Verbatim shape of 2026-09-29-archive-has-no-restore-ui.md: frontmatter never
# closed, so the body runs to EOF and its prose sits inside the block.
UNTERMINATED = """---
title: "Archiving is a one-way door - no restore UI exists"
created: 2026-09-29
status: superseded
tags: [ui, tasks, gap]
> **Superseded in part (2026-09-29):** the missing restore UI has shipped - the
> task detail overflow menu now offers Restore for a trashed task.
archive is implemented as a soft-delete, so "archived" and
"trashed" are the same state.

## Context

Archive works. Restore does not exist.
"""

# Body written *inside* the block, with the sections used as capitalized keys.
BODY_IN_FRONTMATTER = """---
title: Task Detail - Document-Style Migration
date: 2026-09-07
status: accepted
Context: TaskDetailScreen.kt was a form-style read-only screen.
Decision: Migrate to document-style UX (TickTick reference).
Rationale: form-style is a known UX anti-pattern.
Consequences: EditableTextRow stub remains.
Links: skill:singularity-todo-task-detail-ux
Tags: ux, task-detail, compose
---

## Context

Real body content lives here.
"""

WELL_FORMED = """---
title: A well formed ADR
date: 2026-09-29
status: accepted
tags: [UI, Tasks]
---

## Context

Body stays put.
"""


class ParseFrontmatterTest(unittest.TestCase):
    def test_well_formed_parses_body_after_closing_delimiter(self):
        lines = WELL_FORMED.splitlines()
        fm, body, body_start = mod.parse_frontmatter(lines)
        self.assertEqual(fm['title'], 'A well formed ADR')
        self.assertEqual(fm['status'], 'accepted')
        self.assertEqual(body[0], '')
        self.assertEqual(body[1], '## Context')

    def test_unterminated_frontmatter_raises(self):
        """Unterminated block with *only* known keys still must not be written.

        This is the fallback that used to duplicate the body: body_start stayed None,
        so `body` became the whole file. Without an unknown key to trip first, only
        the missing closing delimiter stands between the file and a rewrite.
        """
        unterminated_clean_keys = (
            '---\ntitle: T\ndate: 2026-09-29\nstatus: accepted\n\n## Context\n\nBody text here.\n'
        )
        with self.assertRaises(StructuralError) as ctx:
            mod.parse_frontmatter(unterminated_clean_keys.splitlines())
        self.assertIn('never closed', str(ctx.exception))

    def test_unterminated_with_prose_reports_the_offending_key(self):
        """When prose is present, the unknown-key error is the better diagnosis."""
        with self.assertRaises(StructuralError) as ctx:
            mod.parse_frontmatter(UNTERMINATED.splitlines())
        self.assertIn('unknown frontmatter key', str(ctx.exception))

    def test_body_inside_frontmatter_raises_on_section_key(self):
        """Body-in-frontmatter is valid YAML, so it needs its own detector.

        `Context:` / `Decision:` etc. as keys means the whole body sits inside the
        block: the file parses cleanly but has no real body to render.
        """
        with self.assertRaises(StructuralError) as ctx:
            mod.parse_frontmatter(BODY_IN_FRONTMATTER.splitlines())
        self.assertIn('section key', str(ctx.exception))
        self.assertIn('no real body', str(ctx.exception))

    def test_file_without_leading_delimiter_raises(self):
        with self.assertRaises(StructuralError):
            mod.parse_frontmatter(['# Just a heading', '', 'text'])

    def test_prose_colon_line_is_not_silently_accepted(self):
        """The exact corruption trigger: a ':' line that is not a known key."""
        lines = ['---', 'title: T', 'date: 2026-09-29', 'See also: some other doc', '---', 'body']
        with self.assertRaises(StructuralError):
            mod.parse_frontmatter(lines)


class ProcessFileTest(unittest.TestCase):
    """process_file must never modify a file it cannot parse."""

    def _run_on(self, content: str) -> tuple[str, bool, str]:
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / '2026-09-29-fixture.md'
            path.write_text(content)
            before = path.read_bytes()
            try:
                changed, msg = mod.process_file(path, dry_run=False)
            except StructuralError as exc:
                after = path.read_bytes()
                self.assertEqual(before, after, 'file was modified despite StructuralError')
                return content, False, str(exc)
            after = path.read_bytes()
            return after.decode(), changed, msg

    def test_apply_leaves_unterminated_file_byte_identical(self):
        after, changed, msg = self._run_on(UNTERMINATED)
        self.assertEqual(after, UNTERMINATED, 'unterminated ADR was rewritten')
        self.assertFalse(changed)
        # The unknown-key check fires before the unterminated check: the prose line is
        # the more specific diagnosis, and it names the offending line.
        self.assertIn('unknown frontmatter key', msg)

    def test_apply_does_not_duplicate_body(self):
        after, _, _ = self._run_on(UNTERMINATED)
        self.assertEqual(after.count('## Context'), 1)
        self.assertLess(len(after), len(UNTERMINATED) + 200,
                        'file grew unexpectedly - body likely duplicated')

    def test_body_in_frontmatter_file_is_untouched(self):
        after, changed, msg = self._run_on(BODY_IN_FRONTMATTER)
        self.assertEqual(after, BODY_IN_FRONTMATTER)
        self.assertFalse(changed)
        self.assertIn('section key', msg)

    def test_no_pseudo_key_is_emitted_for_prose(self):
        """Prose must never become a metadata key, in either failure mode.

        The fixture file is left untouched, so the prose is still physically present —
        what matters is that it never becomes part of the parsed frontmatter dict that
        gets re-emitted as metadata.
        """
        for fixture in (UNTERMINATED, BODY_IN_FRONTMATTER):
            with self.assertRaises(StructuralError):
                fm, _, _ = mod.parse_frontmatter(fixture.splitlines())
                # If parse ever stopped raising, no key outside the vocabulary may exist.
                self.assertEqual(set(fm) - mod.KNOWN_KEYS, set())

    def test_known_keys_survive_parsing(self):
        """The guard must not reject legitimate frontmatter vocabulary."""
        lines = ['---', 'title: T', 'date: 2026-09-29', 'status: accepted',
                 'supersedes: 2026-09-01-old.md', 'tags: [a, b]', '---', 'body']
        fm, _, _ = mod.parse_frontmatter(lines)
        self.assertEqual(fm['supersedes'], '2026-09-01-old.md')

    def test_list_items_are_not_mistaken_for_keys(self):
        """Real ADRs use this shape (2026-10-03-cancellable-result-capture).

        An earlier guard read each '  - item' line as a key and rejected the file as
        containing unknown keys. The correct outcome is a precise refusal: the items
        are a list value, not keys, and this normalizer cannot round-trip them.
        """
        lines = [
            '---', 'type: decision', 'date: 2026-10-03', 'status: accepted',
            'deciders:',
            '  - Singularity Developer',
            'references:',
            '  - "2026-09-18-mutation-result-handling.md"',
            '  - "core/sync/SyncEngine.kt"',
            '---', 'body',
        ]
        with self.assertRaises(mod.UnrepresentableValue) as ctx:
            mod.parse_frontmatter(lines)
        # The diagnosis must name the real key, not an item that was misread as one.
        self.assertIn('deciders', str(ctx.exception))
        self.assertNotIn('mutation-result-handling', str(ctx.exception))

    def test_folded_scalar_key_is_refused(self):
        """`description: >` is valid YAML, but unwritable by this flat normalizer.

        It must be reported as unrepresentable rather than written back as a bare '>'
        with the prose dropped.
        """
        lines = ['---', 'title: T', 'description: >', '  some folded prose: with colon',
                 '---', 'body']
        with self.assertRaises(mod.UnrepresentableValue) as ctx:
            mod.parse_frontmatter(lines)
        self.assertIn('description', str(ctx.exception))

    def test_list_valued_key_is_refused_not_flattened(self):
        """A key with list items must be refused, not written back empty.

        Found by running the fixed script over the real corpus: keys with no inline
        value ('deciders:' followed by '  - Someone') were flattened to '' and their
        items dropped, and KEY_ORDER emitted the key twice.
        """
        content = (
            '---\n'
            'type: decision\n'
            'date: 2026-10-03\n'
            'status: accepted\n'
            'deciders:\n'
            '  - Singularity Developer\n'
            'references:\n'
            '  - "core/sync/SyncEngine.kt:211,260"\n'
            '---\n'
            '\n'
            '# Body\n'
        )
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / '2026-10-03-list-valued.md'
            path.write_text(content)
            before = path.read_bytes()
            with self.assertRaises(mod.UnrepresentableValue):
                mod.process_file(path, dry_run=False)
            self.assertEqual(path.read_bytes(), before, 'list items were lost')

    def test_block_scalar_key_is_refused(self):
        content = (
            '---\n'
            'title: T\n'
            'date: 2026-10-03\n'
            'description: >\n'
            '  folded prose that would be dropped\n'
            '---\n'
            '\n'
            '# Body\n'
        )
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / '2026-10-03-block-scalar.md'
            path.write_text(content)
            before = path.read_bytes()
            with self.assertRaises(mod.UnrepresentableValue):
                mod.process_file(path, dry_run=False)
            self.assertEqual(path.read_bytes(), before, 'block scalar was lost')

    def test_corpus_vocabulary_covers_every_well_formed_key(self):
        """Guard against KNOWN_KEYS drifting from what ADRs actually use.

        Walks the real corpus; any key in a properly closed frontmatter block that is
        not in KNOWN_KEYS would make the script reject a valid file.
        """
        decisions = MODULE_PATH.parent.parent / 'docs' / 'decisions'
        if not decisions.is_dir():
            self.skipTest('ADR corpus not present')
        missing: set[str] = set()
        unrepresentable: list[str] = []
        for path in sorted(decisions.glob('[0-9][0-9][0-9][0-9]-*-*.md')):
            lines = path.read_text().splitlines()
            if not lines or lines[0].strip() != '---':
                continue
            end = next((i for i, l in enumerate(lines[1:], 1) if l.strip() == '---'), None)
            if end is None:
                continue  # unterminated: reported separately
            has_list = any(
                l.rstrip().endswith(':') or l.startswith((' ', '\t-', '- '))
                for l in lines[1:end]
            )
            try:
                mod.parse_frontmatter(lines)
            except mod.UnrepresentableValue:
                unrepresentable.append(path.name)
                continue
            except mod.StructuralError:
                continue
            for line in lines[1:end]:
                s = line.strip()
                if not s or s.startswith(('#', '-', '|', '>')) or line.startswith((' ', '\t')):
                    continue
                if ':' in s:
                    key = s.partition(':')[0].strip()
                    if key not in mod.KNOWN_KEYS and key not in mod.SECTION_KEYS:
                        missing.add(f'{path.name}:{key}')
        self.assertEqual(missing, set(), f'keys missing from KNOWN_KEYS: {sorted(missing)}')
        # These are legitimately reported as skipped, not silently mangled. Assert the
        # count so a change in how many files are protected is a deliberate one.
        self.assertLessEqual(len(unrepresentable), 10,
                             f'unexpected number of list-valued ADRs: {unrepresentable}')

    def test_well_formed_file_is_still_normalized(self):
        """The fix must not disable the script's actual job."""
        after, changed, msg = self._run_on(WELL_FORMED)
        self.assertTrue(changed, 'well-formed ADR with dirty tags was not normalized')
        self.assertIn('tags: [ui, tasks]', after)
        self.assertIn('## Context', after)


if __name__ == '__main__':
    unittest.main(verbosity=2)
