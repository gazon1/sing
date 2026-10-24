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

The third half covers the 2026-10-23 fold of the on-demand index. The index used to
cost 2.75 lines per decision; the folded index section costs 0.196 lines per decision
and the folded file 0.289 over the whole corpus, and the budget is a line above the
whole history of the file rather than a wall. These are the checks that the fold stays
folded: every decision is still findable by slug, by title and by tag, repeated text
is folded rather than repeated, the file does not grow by a line per decision, every
prefix of the corpus fits the budget, and a file over budget fails loudly.
"""

import ast
import importlib.util
import math
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

    def test_full_digest_budget_is_the_generators_own_constants(self):
        """The on-demand index is budgeted by a rate, not by a wall.

        A constant ceiling on an exhaustive index is reached by the corpus alone: an
        index of N decisions has N entries, so any fixed number of lines is a
        countdown. What can be stated honestly is what the file may spend on
        everything that does not scale with the corpus, and what one decision may
        add to it.
        """
        self.assertEqual(mod.DIGEST_FULL_FIXED_LINES, 81)
        self.assertEqual(mod.DIGEST_FULL_LINES_PER_DECISION, 0.30)

    def test_the_budget_grows_with_the_corpus(self):
        """The corpus alone must not be able to reach the budget."""
        small = mod.digest_full_budget(100)
        large = mod.digest_full_budget(20_000)
        self.assertGreater(large, small)
        self.assertEqual(large, mod.DIGEST_FULL_FIXED_LINES
                         + math.ceil(mod.DIGEST_FULL_LINES_PER_DECISION * 20_000))

    def test_the_budget_has_no_floor_under_it(self):
        """A floor under the budget is a wall: the corpus cannot reach it.

        `max(400, ...)` held the budget still while the corpus grew, so the
        allowance the fixed part had shrank with every decision until the file ran
        into it. The budget is the fixed part plus the rate times the corpus, so it
        is strictly increasing from the first decision.
        """
        budgets = [mod.digest_full_budget(n) for n in (1, 10, 100, 512, 2_000, 20_000)]
        self.assertEqual(budgets, sorted(budgets))
        self.assertEqual(len(set(budgets)), len(budgets),
                         "the budget must grow with every decision")
        self.assertGreater(mod.digest_full_budget(100), mod.DIGEST_FULL_FIXED_LINES)

    def test_the_measured_digest_fits_the_declared_budget(self):
        """The file that comes out of the generator fits the budget it declares.

        The size is measured by rendering; the budget comes from the constants.
        Nothing here derives the size from the same constants it checks, so a
        budget that stopped covering the corpus would fail this test rather than
        confirm itself.
        """
        entries = mod.CORPUS.decisions(include_archived=False)
        lines = len(mod.render_full_digest(mod.read_corpus(entries)))
        budget = mod.digest_full_budget(len(entries))
        self.assertLessEqual(lines, budget,
                             f'{lines} lines do not fit the budget of {budget}')

    def test_the_measured_rate_is_below_the_ceiling(self):
        """What a decision actually costs, against what it may cost."""
        entries = mod.CORPUS.decisions(include_archived=False)
        lines = len(mod.render_full_digest(mod.read_corpus(entries)))
        rate = (lines - mod.DIGEST_FULL_FIXED_LINES) / len(entries)
        self.assertLessEqual(rate, mod.DIGEST_FULL_LINES_PER_DECISION,
                             f'a decision costs {rate:.3f} lines, the ceiling is '
                             f'{mod.DIGEST_FULL_LINES_PER_DECISION}')

    def test_the_printed_budget_is_the_budget_that_is_applied(self):
        """The number the gate reads is the number the generator enforces.

        The gate parses the budget out of the line the generator prints. A printed
        budget that is not the applied one makes the report of the gate a lie, and
        a test that only checked the applied budget would not notice.

        The property is about any corpus, not about the corpus of today: a test
        that carries the size of the corpus in a literal goes stale with it, and
        a stale literal is a test that checks nothing.
        """
        for decisions in (1, 7, 100, 512, 20_000):
            budget = mod.digest_full_budget(decisions)
            line = mod.digest_status_line(3, 11, decisions)
            printed = re.search(r'DIGEST-full\.md is \d+ lines \(budget: (\d+)\)', line)
            self.assertIsNotNone(printed, f'the line carries no budget: {line!r}')
            self.assertEqual(int(printed.group(1)), budget)
            failure = mod.budget_failures(3, budget + 1, decisions)
            self.assertTrue(failure)
            self.assertIn(f'(budget: {budget})', failure[0])

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


def fake_adr(directory: pathlib.Path, index: int, *, title: str | None = '',
             heading: str = 'A fake decision',
             tags: str = 'testing', consequences=()) -> pathlib.Path:
    """Write a fake decision into a directory — material for testing the fold.

    `title=None` — a decision with no `title:` in its frontmatter: its title is
    taken from the heading of its body.
    """
    slug = f'2026-01-{index:03d}-a-fake-decision'
    path = directory / f'{slug}.md'
    lines = ['---']
    if title is not None:
        lines.append(f'title: {title or f"Fake decision {index}"}')
    lines.extend([
        'date: 2026-01-01',
        'status: accepted',
        f'tags: [{tags}]',
        '---',
        '',
        f'# {heading}',
        '',
        '## Consequences',
        '',
    ])
    lines.extend(f'- {c}' for c in consequences)
    path.write_text('\n'.join(lines) + '\n', encoding='utf-8')
    return path


class TheCriticalSectionTakesRulesOnlyFromDecisionsInForce(unittest.TestCase):
    """A replaced decision does not put its rules into the Critical section.

    The corpus's own rule (`2026-09-26-adr-supersede-process`) says the rules of
    a replaced decision are not duplicated there. "Replaced" is the three-way
    notion: the `supersedes:` of the decision that replaced it, and the two ways
    a decision says it of itself — `status: superseded` and `superseded-by:`.

    The corpus of today carries no rule of a replaced decision, so the property
    is checked on an artificial corpus, where a leak is visible. Every decision
    carries a rule of its own: the section keeps one copy of a rule, so rules
    that read alike would hide a leak behind the copy that is allowed.
    """

    def _decision(self, slug: str, *, status: str = 'accepted',
                  supersedes: str = '', superseded_by: str = '') -> str:
        """A decision with one rule of its own in its consequences."""
        lines = ['---', f'title: Decision {slug}', 'date: 2026-01-01',
                 f'status: {status}', 'tags: [testing]', '---', '',
                 f'# Decision {slug}', '', '## Consequences', '',
                 f'- **Never** the thing {slug} says']
        if supersedes:
            lines.insert(5, f'supersedes: {supersedes}')
        if superseded_by:
            lines.insert(5, f'superseded-by: {superseded_by}')
        return '\n'.join(lines) + '\n'

    def _critical_slugs(self, texts: dict[str, str]) -> set[str]:
        corpus = mod.read_corpus_texts(
            {pathlib.Path(name): text for name, text in texts.items()}
        )
        return {slug for _, slug in corpus.critical}

    def test_the_rule_of_a_replaced_decision_does_not_reach_the_critical_section(self):
        replaced_by_another = '2026-01-001-a-fake-decision'
        texts = {
            '2026-01-001-a-fake-decision.md': self._decision('one'),
            '2026-01-002-a-fake-decision.md': self._decision(
                'two', supersedes=replaced_by_another),
            '2026-01-003-a-fake-decision.md': self._decision('three', status='superseded'),
            '2026-01-004-a-fake-decision.md': self._decision(
                'four', superseded_by='2026-01-001-a-fake-decision'),
        }

        critical = self._critical_slugs(texts)

        self.assertIn('2026-01-002-a-fake-decision', critical,
                      'the rule of a decision in force must reach the section')
        self.assertNotIn(replaced_by_another, critical,
                         'the rule of a decision somebody replaced must not')
        self.assertNotIn('2026-01-003-a-fake-decision', critical,
                         'the rule of a decision that says of itself that it was '
                         'replaced must not')
        self.assertNotIn('2026-01-004-a-fake-decision', critical,
                         'the rule of a decision that names what replaced it must not')


class TheFoldTest(unittest.TestCase):
    """The full index is folded: it must not grow with the corpus.

    Before the fold (2026-10-23) the file cost 2.7 lines per decision — one line
    per entry, one section per tag, one bullet per consequence. These are the checks
    that the fold stays folded.
    """

    def test_every_decision_survives_the_fold(self):
        """Nothing is lost: each decision is still findable by slug, by title, by tag."""
        entries = mod.CORPUS.decisions(include_archived=False)
        corpus = mod.read_corpus(entries)
        rendered = '\n'.join(mod.render_full_digest(corpus))
        for slug, title in corpus.titles.items():
            self.assertIn(f'`{slug}`', rendered, f'{slug} is not in the index')
            if title:
                self.assertIn(title, rendered, f'the title of {slug} is not in the index')
        for slug, tags in corpus.tags.items():
            for tag in tags:
                self.assertIn(f'#{tag}', rendered,
                              f'the tag {tag} of {slug} is not in the index')

    def test_a_decision_without_a_frontmatter_title_is_found_by_its_heading(self):
        """28 decisions of the corpus carry no `title:` — they are found anyway."""
        with tempfile.TemporaryDirectory() as tmp:
            directory = pathlib.Path(tmp)
            path = fake_adr(directory, 1, title=None, heading='The heading is the title')
            rendered = '\n'.join(mod.render_full_digest(mod.read_corpus([path])))
        self.assertIn('The heading is the title', rendered)
        self.assertIn('2026-01-001-a-fake-decision', rendered)

    def test_a_hash_inside_a_code_block_is_not_a_heading(self):
        """A `#` in a shell example is not the title of the decision."""
        text = ('---\ntitle: Named\n---\n\n```bash\n# not a heading\n```\n\n'
                '# The real heading\n')
        self.assertEqual(mod.first_heading(text), 'The real heading')

    def test_a_decision_without_a_title_anywhere_is_marked_as_such(self):
        """No title and no heading: the index says so instead of leaving a blank."""
        with tempfile.TemporaryDirectory() as tmp:
            directory = pathlib.Path(tmp)
            path = directory / '2026-01-002-a-fake-decision.md'
            path.write_text('---\ndate: 2026-01-01\nstatus: accepted\n---\n\n'
                            'Body without a heading.\n', encoding='utf-8')
            rendered = '\n'.join(mod.render_full_digest(mod.read_corpus([path])))
        self.assertIn('_(no title)_', rendered)

    def test_repeated_text_is_folded_rather_than_repeated(self):
        """The same sentence in fifty decisions is one line, not fifty."""
        with tempfile.TemporaryDirectory() as tmp:
            directory = pathlib.Path(tmp)
            entries = [fake_adr(directory, i, consequences=('**Always** keep the door open',))
                       for i in range(50)]
            rendered = '\n'.join(mod.render_full_digest(mod.read_corpus(entries)))
        self.assertEqual(rendered.count('keep the door open'), 1)

    def test_the_index_does_not_grow_by_a_line_per_decision(self):
        """Two hundred decisions must cost a fraction of two hundred lines."""
        with tempfile.TemporaryDirectory() as tmp:
            directory = pathlib.Path(tmp)
            empty = mod.render_full_digest(mod.read_corpus([]))
            entries = [fake_adr(directory, i) for i in range(200)]
            grown = mod.render_full_digest(mod.read_corpus(entries))
        delta = len(grown) - len(empty)
        self.assertLess(delta, 200 / 4,
                        f'200 decisions cost {delta} lines — the fold is not holding')

    def test_the_orientation_layer_does_not_grow_with_the_corpus(self):
        """The always-loaded layer is bounded by kind: a decision adds it no lines.

        Twenty decisions and two hundred decisions must cost the layer the same
        number of lines. The tag paragraph may rewrap by a line or two — the set of
        most-used tags shifts — but nothing may grow with the corpus.
        """
        with tempfile.TemporaryDirectory() as tmp:
            directory = pathlib.Path(tmp)
            few = [fake_adr(directory, i) for i in range(20)]
            many = [fake_adr(directory, i) for i in range(200)]
            small = mod.render_orientation(mod.read_corpus(few), few)
            large = mod.render_orientation(mod.read_corpus(many), many)
        self.assertLessEqual(len(large) - len(small), 5)

    def test_the_orientation_layer_renders_the_corpus_it_is_given(self):
        """The layer draws from the corpus it is handed, not from the module's own.

        A renderer that overwrites its argument with the module's corpus looks
        exactly like one that uses it: both draw the same layer. The difference
        shows only on an artificial corpus, which is why the layer is rendered from
        one here.
        """
        with tempfile.TemporaryDirectory() as tmp:
            directory = pathlib.Path(tmp)
            entries = [fake_adr(directory, i) for i in range(3)]
            rendered = '\n'.join(mod.render_orientation(mod.read_corpus(entries), entries))
        self.assertIn('2026-01-000-a-fake-decision', rendered,
                      'the layer did not draw from the corpus it was given')

    def test_a_horizontal_rule_in_the_body_is_not_a_delimiter(self):
        """A `---` in the body is a rule, and the heading after it is a heading.

        44 decisions of the corpus carry `---` in their body — an amendment note,
        a rule — and a heading that follows one is still the heading of the
        decision.
        """
        text = ('---\ntitle: Named\ndate: 2026-10-05\n---\n\nBody text.\n\n---\n\n'
                '# The heading after the rule\n')
        self.assertEqual(mod.first_heading(text), 'The heading after the rule')

    def test_over_budget_is_reported_with_numbers(self):
        """A file over budget must say so, and say by how much."""
        self.assertEqual(mod.budget_failures(10, 10, 10), [])
        failures = mod.budget_failures(10, 10 ** 6, 100)
        self.assertTrue(failures)
        message = failures[0]
        self.assertIn('DIGEST-full.md', message)
        self.assertIn('budget:', message)
        self.assertIn(str(10 ** 6), message)

    def test_the_orientation_layer_over_budget_is_reported_too(self):
        failures = mod.budget_failures(10 ** 6, 10, 10)
        self.assertTrue(failures)
        self.assertIn('DIGEST.md', failures[0])

    def test_the_generation_is_idempotent(self):
        """The same corpus must render the same file — twice."""
        entries = mod.CORPUS.decisions(include_archived=False)
        first = mod.render_full_digest(mod.read_corpus(entries))
        second = mod.render_full_digest(mod.read_corpus(entries))
        self.assertEqual(first, second)

    def test_packing_keeps_every_item(self):
        """Packing may not drop an entry — that is the one thing it may not do."""
        items = [f'`2026-01-{i:03d}-a-fake-decision` — Fake decision {i}' for i in range(200)]
        packed = mod.pack_items(items)
        self.assertLess(len(packed), len(items))
        for item in items:
            self.assertIn(item, '\n'.join(packed))

    def test_the_corpus_is_read_from_the_texts_it_is_given(self):
        """The texts handed over are the texts that are read.

        The prefix series hands over the texts it has already read, and the
        paths are keys rather than sources: a corpus whose files are not on
        disk reads all the same. Reading the files again for every prefix is
        what made the series cost minutes instead of seconds.
        """
        with tempfile.TemporaryDirectory() as tmp:
            path = pathlib.Path(tmp) / '2026-01-001-a-fake-decision.md'
            text = ('---\ntitle: Handed over\ndate: 2026-01-01\nstatus: accepted\n---\n\n'
                    '# Body\n')
        corpus = mod.read_corpus_texts({path: text})
        self.assertEqual(corpus.titles.get('2026-01-001-a-fake-decision'),
                         'Handed over')




# ── Reading an ADR in the test ───────────────────────────────────────────────
#
# The reading here is written apart from the generator on purpose: a test that
# reads the expected answer out of the code it checks confirms the code, not the
# property. The expectation here comes from the files.


def frontmatter_of(text: str) -> dict[str, str]:
    """The frontmatter of a decision — as a reader sees it, not as the generator does."""
    fields: dict[str, str] = {}
    in_fm = False
    for line in text.splitlines():
        if line.strip() == '---':
            in_fm = not in_fm
            if not in_fm:
                break
            continue
        if in_fm and ':' in line:
            key, _, value = line.partition(':')
            fields[key.strip()] = value.strip().strip('"').strip("'")
    return fields


def body_title_of(text: str) -> str:
    """The first heading of the body of a decision."""
    in_fm = False
    in_code = False
    for line in text.splitlines():
        stripped = line.strip()
        if stripped == '---' and not in_code:
            in_fm = not in_fm
            continue
        if in_fm:
            continue
        if stripped.startswith('```'):
            in_code = not in_code
            continue
        if in_code:
            continue
        if line.startswith('# '):
            return line[2:].strip()
    return ''


def consequences_of(text: str) -> list[str]:
    """The first lines of the bulleted items of the "Consequences" section."""
    bullets: list[str] = []
    in_consequences = False
    for line in text.splitlines():
        if line.strip() == '## Consequences':
            in_consequences = True
            continue
        if in_consequences and line.startswith('## '):
            in_consequences = False
            continue
        if in_consequences:
            match = re.match(r'^[-*]\s+(.+)', line.strip())
            if match:
                bullets.append(match.group(1).strip())
    return bullets


CRITICAL_MARKER = re.compile(r'\*\*Always\*\*|\*\*Never\*\*|\*\*MUST\*\*')


class LanguageTest(unittest.TestCase):
    """The generator speaks one language, and so does the file it writes."""

    def test_the_docstrings_of_the_generator_are_in_one_language(self):
        """The docstrings of the generator are English, as the file has always been.

        The file was written in one language before the fold and is written in one
        language now: a reader of this repository meets the same language in the
        code, in the decision record and in the file the generator writes.
        """
        tree = ast.parse(pathlib.Path(mod.__file__).read_text(encoding='utf-8'))
        for node in ast.walk(tree):
            if not isinstance(node, (ast.Module, ast.FunctionDef, ast.ClassDef)):
                continue
            doc = ast.get_docstring(node)
            if doc:
                name = getattr(node, 'name', 'the module')
                self.assertNotRegex(doc, r'[\u0400-\u04ff]',
                                    f'{name} has a docstring in another language')


class CoverageTest(unittest.TestCase):
    """Every decision of the corpus is findable — by slug, by title, by tag.

    The expectations are computed from the ADR files, not from the generator:
    a test that reads the expected answer out of the code it checks confirms the
    code, not the property. That is how 28 decisions without a frontmatter title
    went missing for a whole review round: the corpus said they had no title, and
    the corpus was the thing being checked.
    """

    def _entries(self):
        return mod.CORPUS.decisions(include_archived=False)

    def _rendered(self):
        entries = self._entries()
        return '\n'.join(mod.render_full_digest(mod.read_corpus(entries)))

    def _replaced_decisions(self):
        """The decisions that no longer stand: their consequences do not reach the file.

        Three declarations say that, and the helper reads all three from the
        files — the `supersedes:` of the decision that replaced it, and the two
        ways a decision says it of itself: `status: superseded` and
        `superseded-by:` (or `superseded_by:`). Reading only the forward edge
        would let the rules of a decision that replaced itself back into the
        Critical section.

        The expectation is read out of the ADR files, not out of the generator:
        the generator is the thing under test.
        """
        replaced = set()
        for path in self._entries():
            text = path.read_text()
            front = frontmatter_of(text)
            supersedes = front.get('supersedes', '')
            if supersedes:
                replaced.add(supersedes.strip('[]').removesuffix('.md'))
            if front.get('status', '') == 'superseded':
                replaced.add(path.stem)
            if front.get('superseded-by', '') or front.get('superseded_by', ''):
                replaced.add(path.stem)
        return replaced

    def test_every_decision_is_findable_by_its_slug(self):
        entries = self._entries()
        rendered = self._rendered()
        missing = [p.stem for p in entries if f'`{p.stem}`' not in rendered]
        self.assertEqual(missing, [],
                         f'{len(missing)} of {len(entries)} slugs are not in the index')

    def test_every_decision_is_findable_by_its_title(self):
        """A decision with no frontmatter title is found by the heading of its body."""
        entries = self._entries()
        rendered = self._rendered()
        titles: dict[str, str] = {}
        without_frontmatter = 0
        for path in entries:
            text = path.read_text()
            title = frontmatter_of(text).get('title', '') or body_title_of(text)
            if not frontmatter_of(text).get('title', ''):
                without_frontmatter += 1
            titles[path.stem] = title
        missing = [slug for slug, title in titles.items()
                   if title and title not in rendered]
        self.assertEqual(missing, [],
                         f'{len(missing)} of {len(titles)} titles are not in the index')
        self.assertEqual(len(titles), len(entries))
        # The corpus really does have decisions without a frontmatter title — if it
        # stopped having them, the fallback would stop being tested.
        self.assertGreater(without_frontmatter, 0,
                           'no decision is left without a frontmatter title — the '
                           'fallback is untested')

    def test_every_tag_is_findable(self):
        entries = self._entries()
        rendered = self._rendered()
        tags: set[str] = set()
        for path in entries:
            raw = frontmatter_of(path.read_text()).get('tags', '')
            for tag in raw.strip('[]').split(','):
                tag = tag.strip().strip('"\'').lower()
                if tag:
                    tags.add(tag)
        missing = sorted(tag for tag in tags if f'#{tag}' not in rendered)
        self.assertEqual(missing, [],
                         f'{len(missing)} of {len(tags)} tags are not in the index')

    def test_the_tags_section_lists_the_whole_vocabulary(self):
        """The summary of the tags is a section of its own, and it is complete.

        The index entries carry the tags of every decision, so a tag is findable
        either way; the summary is what says which tags the corpus has and how
        many decisions carry each.
        """
        entries = self._entries()
        rendered = '\n'.join(mod.render_full_digest(mod.read_corpus(entries)))
        self.assertIn('## Tags', rendered)
        section = rendered.split('## Tags', 1)[1].split('\n## ', 1)[0]
        counts: dict[str, int] = {}
        for path in entries:
            for tag in frontmatter_of(path.read_text()).get('tags', '').strip('[]').split(','):
                tag = tag.strip().strip('"\'').lower()
                if tag:
                    counts[tag] = counts.get(tag, 0) + 1
        self.assertTrue(counts)
        missing = sorted(tag for tag in counts if f'#{tag} ({counts[tag]})' not in section)
        self.assertEqual(missing, [],
                         f'{len(missing)} of {len(counts)} tags are missing from the '
                         f'summary: {missing[:5]}')

    def test_the_heading_of_every_decision_is_found(self):
        """A `---` in the body is a rule, not a delimiter.

        371 decisions of the corpus have no level-1 heading at all — their title is
        in the frontmatter and their body starts at `## Context`. The rest have one,
        and every one of them has to be found: a `---` between the frontmatter and
        the heading is a horizontal rule, not the end of the frontmatter.
        """
        entries = self._entries()
        wrong = []
        for path in entries:
            headings = body_headings(path.read_text())
            expected = headings[0] if headings else ''
            found = mod.first_heading(path.read_text())
            if found != expected:
                wrong.append((path.stem, expected, found))
        self.assertEqual(wrong, [], f'{len(wrong)} headings are not found: {wrong[:5]}')

    def test_every_decision_without_a_frontmatter_title_has_a_heading(self):
        """The fallback has something to fall back to.

        A decision that carries no title in its frontmatter is found by the
        heading of its body, so every one of them has to have one.
        """
        entries = self._entries()
        without_title = [path for path in entries
                         if not frontmatter_of(path.read_text()).get('title')]
        self.assertTrue(without_title,
                        'no decision is left without a frontmatter title — the '
                        'fallback is untested')
        for path in without_title:
            self.assertTrue(mod.first_heading(path.read_text()),
                            f'{path.stem} has neither a title nor a heading')

    def test_every_critical_rule_is_findable(self):
        entries = self._entries()
        rendered = self._rendered()
        replaced = self._replaced_decisions()
        rules: set[str] = set()
        for path in entries:
            if path.stem in replaced:
                continue
            for bullet in consequences_of(path.read_text()):
                if CRITICAL_MARKER.search(bullet):
                    rules.add(bullet)
        missing = sorted(rule for rule in rules if rule not in rendered)
        self.assertEqual(missing, [],
                         f'{len(missing)} of {len(rules)} absolute rules are not in '
                         f'the index')



STATUS_GROUP = re.compile(r'^- \*\*(open|deferred|superseded)\*\* \((\d+)\) — ')

#: A decision slug in the rendered file. The corpus is a set of date-prefixed
#: files, and the titles carry backticks of their own — 19 of them, counting the
#: ones that come from the body — so a slug is read by its shape and not by
#: "whatever is in backticks".
SLUG = re.compile(r'`(\d{4}-\d{2}-\d{2}-[^`]+)`')

#: The decision record of this change, and the rates it states in its table.
ADR_PATH = (ROOT / 'docs' / 'decisions'
            / '2026-10-23-the-full-digest-does-not-grow.md')
ADR_RATE = re.compile(r'^\| (the [^|]+?) \| ([0-9.]+) lines each \|$', re.MULTILINE)

#: The measured series, kept between the classes that ask it questions.
_SERIES: dict[str, object] = {}


class StatusGroupsTest(unittest.TestCase):
    """The Status section names every decision of the corpus once, and only those.

    Three ways for the section to lie are locked down here. It may name a
    decision that is not in the corpus — the target of a `supersedes:` that
    lives in `archive/`, which the corpus deliberately does not read — and then
    the section carries a row that nothing can be found from. It may name the
    same decision in two groups at once, because its own frontmatter says
    `deferred` while another decision replaced it: the groups are exclusive,
    and the replacement wins. And it may leave a decision that was replaced out
    of every group — the digest then shows a decision that no longer stands as
    if it were still in force.

    A decision says of itself that it was replaced in three ways, and the digest
    believes all three: its own `status: superseded`, its own `superseded-by:`
    (or `superseded_by:`), and the `supersedes:` of the decision that replaced
    it.

    The expectations are read out of the ADR files, not out of the generator: the
    generator is the thing under test here.
    """

    def _groups(self) -> dict[str, list[str]]:
        """The slugs of every group of the Status section, read out of the file."""
        section = self._section('## Status')
        groups: dict[str, list[str]] = {}
        label: str | None = None
        rest = ''
        for line in section.splitlines():
            match = STATUS_GROUP.match(line)
            if match:
                label = match.group(1)
                groups[label] = []
                rest = line[match.end():]
            elif line.startswith('  ') and label is not None:
                rest = line
            else:
                continue
            groups[label].extend(SLUG.findall(rest))
        return groups

    def _section(self, marker: str) -> str:
        """A section of the rendered index, read out of the file."""
        entries = mod.CORPUS.decisions(include_archived=False)
        rendered = '\n'.join(mod.render_full_digest(mod.read_corpus(entries)))
        return rendered.split(marker, 1)[1].split('\n## ', 1)[0]

    def _own_declarations(self) -> dict[str, str]:
        """What each decision of the corpus says about itself.

        Read out of the files, one field at a time: the status the decision
        declares, and the name of the decision that replaced it, in either of the
        two spellings the corpus uses.
        """
        declared: dict[str, str] = {}
        for path in mod.CORPUS.decisions(include_archived=False):
            fields = frontmatter_of(path.read_text())
            status = fields.get('status', '').strip().strip('[]"\'').lower()
            replaced_by = ''
            for key in ('superseded-by', 'superseded_by'):
                if fields.get(key):
                    replaced_by = fields[key].strip('[]').strip().removesuffix('.md')
                    break
            if status == 'superseded' or replaced_by:
                declared[path.stem] = 'superseded'
            elif status in ('open', 'deferred'):
                declared[path.stem] = status
            else:
                declared[path.stem] = ''
        return declared

    def _expected(self) -> dict[str, set[str]]:
        """The groups as the ADR files say they are.

        The union of the three declarations: what a decision says of itself, and
        what the decision that replaced it says about it.
        """
        declared = self._own_declarations()
        corpus = set(declared)
        replaced = {slug for slug, group in declared.items() if group == 'superseded'}
        for path in mod.CORPUS.decisions(include_archived=False):
            supersedes = frontmatter_of(path.read_text()).get('supersedes', '')
            if supersedes:
                target = supersedes.strip('[]').removesuffix('.md')
                if target in corpus:
                    replaced.add(target)
        pending = {slug for slug, group in declared.items() if group in ('open', 'deferred')}
        return {
            'open': {slug for slug in pending if declared[slug] == 'open'} - replaced,
            'deferred': {slug for slug in pending if declared[slug] == 'deferred'} - replaced,
            'superseded': replaced,
        }

    def test_the_status_section_names_no_decision_outside_the_corpus(self):
        """Every named decision is a decision of the corpus."""
        corpus = {path.stem for path in mod.CORPUS.decisions(include_archived=False)}
        for label, slugs in self._groups().items():
            outside = sorted(slug for slug in slugs if slug not in corpus)
            self.assertEqual(outside, [],
                             f'the {label} group names {len(outside)} decisions that '
                             f'are not in the corpus: {outside[:5]}')

    def test_the_status_groups_do_not_overlap(self):
        """A decision is in one group, not in two."""
        seen: dict[str, str] = {}
        for label, slugs in self._groups().items():
            for slug in slugs:
                self.assertNotIn(slug, seen,
                                 f'{slug} is named in the {seen.get(slug)} group and '
                                 f'in the {label} group as well')
                seen[slug] = label

    def test_the_status_groups_hold_exactly_the_decisions_that_belong_in_them(self):
        """Every open, deferred and replaced decision of the corpus is named once."""
        groups = self._groups()
        for label, wanted in self._expected().items():
            self.assertEqual(set(groups.get(label, [])), wanted,
                             f'the {label} group does not hold what the files say')

    def test_a_decision_that_says_it_was_replaced_is_listed_as_superseded(self):
        """The decision's own word about itself is believed.

        A decision that says of itself that it was replaced — in its own status
        or in the name of the decision that replaced it — is not in force, and
        the digest has to say so: a reader who opens the index sees the decision
        as it stands, and a decision that no longer stands must not be listed as
        if it did.
        """
        declared = self._own_declarations()
        superseded = set(self._groups().get('superseded', []))
        missing = sorted(slug for slug, group in declared.items()
                         if group == 'superseded' and slug not in superseded)
        self.assertEqual(missing, [],
                         f'{len(missing)} decisions say they were replaced and are '
                         f'not listed as superseded: {missing[:5]}')

    def test_a_replaced_decision_is_not_listed_as_one_that_still_stands(self):
        """The index of the decisions in force does not carry a replaced one."""
        declared = self._own_declarations()
        replaced = set(self._groups().get('superseded', []))
        index = SLUG.findall(self._section('## Decisions'))
        standing = [slug for slug in index
                    if declared.get(slug) == 'superseded' and slug not in replaced]
        self.assertEqual(standing, [],
                         f'{len(standing)} decisions that were replaced are listed '
                         f'in the index of the decisions in force: {standing[:5]}')

    def test_the_status_groups_count_what_they_name(self):
        """The number in the heading is the number of decisions in the group."""
        section = self._section('## Status')
        declared = {match.group(1): int(match.group(2))
                    for match in (STATUS_GROUP.match(line) for line in section.splitlines())
                    if match}
        groups = self._groups()
        for label, count in declared.items():
            self.assertEqual(len(groups.get(label, [])), count,
                             f'the {label} group says {count} and names '
                             f'{len(groups.get(label, []))}')




def body_headings(text: str) -> list[str]:
    """The level-1 headings of the body of a decision, outside code blocks.

    Read here rather than taken from the generator: a test that reads the expected
    answer out of the code it checks confirms the code, not the property.
    """
    lines = text.splitlines()
    if lines and lines[0].strip() == '---':
        for index, line in enumerate(lines[1:], start=1):
            if line.strip() == '---':
                lines = lines[index + 1:]
                break
    headings: list[str] = []
    in_code = False
    for line in lines:
        stripped = line.strip()
        if stripped.startswith('```'):
            in_code = not in_code
            continue
        if in_code:
            continue
        if line.startswith('# '):
            headings.append(line[2:].strip())
    return headings


def prefix_series() -> tuple[list[pathlib.Path], dict[int, int], dict[int, int]]:
    """The corpus as it stood at every earlier date, rendered and measured.

    The size of the file and the size of its index section, for every prefix of
    the corpus — not a sample of it: a sample taken every ten decisions missed
    the tightest point of the history and let a constant a line too low pass.

    Measured once and kept: the series costs seconds, and more than one class
    of checks asks it the same questions.
    """
    if 'series' not in _SERIES:
        entries = sorted(mod.CORPUS.decisions(include_archived=False),
                         key=lambda path: path.stem)
        texts = {path: path.read_text() for path in entries}
        sizes: dict[int, int] = {}
        index_sizes: dict[int, int] = {}
        for n in range(len(entries) + 1):
            prefix = {path: texts[path] for path in entries[:n]}
            rendered = mod.render_full_digest(mod.read_corpus_texts(prefix))
            text = '\n'.join(rendered)
            sizes[n] = len(rendered)
            # The index section with its header: the marker line, the count
            # line, the blank lines and the packed entries.
            index_sizes[n] = len(text.split('## Decisions', 1)[1].splitlines()) + 1
        _SERIES['series'] = (entries, sizes, index_sizes)
    return _SERIES['series']


def worst_window(sizes: dict[int, int], window: int) -> tuple[float, int]:
    """The most expensive window of `window` decisions in the series, and where."""
    points = sorted(sizes)
    rates = [(sizes[n + window] - sizes[n]) / window for n in points[:-window]]
    worst = max(rates)
    return worst, points[rates.index(worst)]


class BudgetCoversTheHistoryTest(unittest.TestCase):
    """The budget is a line above the whole history of the file, not one point.

    The corpus is a series: the file as it stood at every earlier date is the
    corpus with the newest decisions left out. A budget that fits the file today
    and not the file of half a year ago is a wall that happens to be far away, so
    the property is measured on the series and checked at every point of it.
    """

    @classmethod
    def setUpClass(cls):
        # Every prefix of the corpus, not a sample of it. A sample taken every
        # ten decisions missed the tightest point of the history — N=129, where
        # the file comes within a third of a line of the budget — and a constant
        # a whole line too low passed the test because of it. The series is
        # measured at every point of it, and the points are cheap: the decisions
        # are parsed once and the prefixes are rendered from that.
        cls.entries, cls.sizes, cls.index_sizes = prefix_series()

    def test_every_prefix_of_the_corpus_fits_the_budget(self):
        """The budget holds on the whole path the file has taken, not at one point."""
        over = [(n, size, mod.digest_full_budget(n))
                for n, size in sorted(self.sizes.items())
                if size > mod.digest_full_budget(n)]
        self.assertEqual(over, [],
                         f'{len(over)} prefixes of the corpus do not fit the budget: '
                         f'{over[:5]}')

    def test_the_tightest_point_of_the_history_is_covered(self):
        """The intercept is the smallest one that keeps the file off the budget.

        The point where the file comes closest to the line is the point the
        intercept was measured from; a margin of nothing there means the file
        sits on the budget, and a margin below nothing means the file runs past
        it.
        """
        rate = mod.DIGEST_FULL_LINES_PER_DECISION
        tightest = min(self.sizes, key=lambda n: mod.digest_full_budget(n) - self.sizes[n])
        margin = mod.digest_full_budget(tightest) - self.sizes[tightest]
        self.assertGreaterEqual(margin, 0,
                                f'at N={tightest} the file is over the budget')
        # The intercept is the measured one, not a number someone liked: it is
        # the smallest integer strictly above the covering of the history, so
        # that the file keeps a line of margin at every point of it. The series
        # is measured at every point of it, so the measurement is the
        # measurement — 80.0 at N=130, and a constant of 80 would leave the file
        # sitting on the budget there.
        covering = max(size - rate * n for n, size in self.sizes.items())
        self.assertGreater(mod.DIGEST_FULL_FIXED_LINES, covering,
                           f'the intercept {mod.DIGEST_FULL_FIXED_LINES} does not keep '
                           f'the file off the budget: {covering:.3f} is the covering '
                           f'of the history')
        self.assertLessEqual(mod.DIGEST_FULL_FIXED_LINES, covering + 1,
                              f'the intercept {mod.DIGEST_FULL_FIXED_LINES} is more '
                              f'than a line above the covering {covering:.3f}')

    def test_the_marginal_cost_of_a_decision_is_below_the_ceiling(self):
        """What a decision costs on the way in, against what it may cost.

        Measured over the last 50, the last 100 and the last 200 decisions, and
        over the whole corpus: the fold has to be cheaper than the ceiling, or
        the corpus alone would run into the budget.

        The early corpus grew faster than the ceiling — up to a line per decision
        while the capped sections were still filling up — and the intercept is
        what covers that. The ceiling governs the regime the corpus is in now,
        where the capped sections are saturated and a decision costs its own
        record.
        """
        rate = mod.DIGEST_FULL_LINES_PER_DECISION
        last = max(self.sizes)
        for window in (50, 100, 200):
            start = last - window
            cost = (self.sizes[last] - self.sizes[start]) / window
            self.assertLessEqual(cost, rate,
                                 f'the last {window} decisions cost {cost:.3f} lines '
                                 f'each, the ceiling is {rate}')
        whole = (self.sizes[last] - self.sizes[0]) / last
        self.assertLessEqual(whole, rate,
                             f'the whole corpus cost {whole:.3f} lines per decision, '
                             f'the ceiling is {rate}')

    def test_the_budget_grows_with_the_corpus_on_the_whole_path(self):
        """A budget that stops growing is a wall, whatever it was at the start."""
        for n in sorted(self.sizes):
            self.assertGreaterEqual(mod.digest_full_budget(n + 1),
                                    mod.digest_full_budget(n))


class TheDecisionRecordAgreesWithTheMeasurementTest(unittest.TestCase):
    """The numbers in the decision record are the numbers that were measured.

    A decision record is read by the next person who has to decide something,
    and a number that has gone stale misleads them — twice now the numbers of
    this record have been found to be a measurement of something else.

    The record states the corpus it measured on, and the measurement is taken
    on that corpus: the corpus as it stood on the day the record was written is
    the first N decisions of the series, and that prefix does not move when the
    corpus grows.
    """

    def _stated(self) -> dict[str, float]:
        """The rates the decision record states, read out of its table."""
        text = ADR_PATH.read_text(encoding='utf-8')
        return {label: float(value) for label, value in ADR_RATE.findall(text)}

    def _measured_corpus(self) -> int:
        """The size of the corpus the decision record measured on."""
        text = ADR_PATH.read_text(encoding='utf-8')
        found = re.search(r'corpus of (\d+) decisions', text)
        self.assertIsNotNone(found, 'the decision record does not name the corpus '
                                    'it measured on')
        return int(found.group(1))

    def test_the_rates_in_the_decision_record_are_the_measured_ones(self):
        _, sizes, index_sizes = prefix_series()
        last = self._measured_corpus()
        # The corpus as it stood on the day the record was written: the first N
        # decisions of the series. That prefix does not move when the corpus
        # grows, so the numbers of the record stay true.
        history = {n: sizes[n] for n in range(last + 1)}
        index_history = {n: index_sizes[n] for n in range(last + 1)}
        measured: dict[str, float] = {}
        for window in (50, 100, 200):
            measured[f'the last {window} decisions'] = (
                (sizes[last] - sizes[last - window]) / window)
        measured['the whole corpus'] = (sizes[last] - sizes[0]) / last
        measured['the index section alone, the worst window of 50 in the history'] = (
            worst_window(index_history, 50)[0])
        measured['the whole file, the worst window of 50 in the history, at N=80'] = (
            worst_window(history, 50)[0])

        stated = self._stated()
        self.assertEqual(set(stated), set(measured),
                         'the decision record states rates that are not the ones '
                         'measured here, or misses one of them')
        for label, rate in measured.items():
            self.assertAlmostEqual(
                stated[label], rate, places=3,
                msg=f'the decision record states {stated[label]} for {label}, '
                    f'the measurement is {rate:.4f}')

    def test_the_worst_window_of_the_file_is_where_the_record_says_it_is(self):
        """The window of the whole file is named with the point it happens at."""
        _, sizes, _ = prefix_series()
        last = self._measured_corpus()
        history = {n: sizes[n] for n in range(last + 1)}
        worst, at = worst_window(history, 50)
        stated = self._stated()
        label = 'the whole file, the worst window of 50 in the history, at N=80'
        self.assertIn(label, stated, 'the decision record does not name the worst '
                                     'window of the whole file')
        self.assertIn(f'at N={at}', label,
                      f'the worst window of 50 of the whole file is at N={at}, '
                      f'not where the decision record says')
        # The file outgrew the ceiling in that part of its history, and the
        # intercept is what covers the ramp — that is why the ceiling alone
        # would not have been a budget.
        self.assertGreater(worst, mod.DIGEST_FULL_LINES_PER_DECISION,
                           f'the whole file cost {worst:.3f} lines per decision in '
                           f'its worst window of 50 — the decision record says it '
                           f'outgrew the ceiling')


if __name__ == '__main__':
    unittest.main(verbosity=2)