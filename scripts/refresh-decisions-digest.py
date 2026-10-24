#!/usr/bin/env python3
"""refresh-decisions-digest.py — generate the decision digests from ADR entries.

Two files, two jobs:

  DIGEST.md      — the always-loaded orientation layer, bounded by kind.
  DIGEST-full.md — the complete index, folded so it does not grow with the corpus.

Run via: ./scripts/refresh-decisions-digest.sh
Or directly: python3 ./scripts/refresh-decisions-digest.py
"""

import functools
import math
import re
import sys
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import adr_corpus  # noqa: E402  — single owner of corpus discovery (#520)
import docs_tree  # noqa: E402  — draws docs/; owns no corpus rule

ROOT = Path(__file__).resolve().parent.parent
CORPUS = adr_corpus.load(ROOT)
DECISIONS_DIR = CORPUS.root
DOCS_DIR = ROOT / "docs"
ARCH_DIR = DOCS_DIR / "architecture"

# Two artifacts, split on 2026-10-15 by `2026-10-15-digest-orientation-layer.md`.
#
# DIGEST.md is the ALWAYS-LOADED orientation layer: what an agent is told to read before
# a non-trivial task, so it is read in every session and its size is a tax paid on every
# task. DIGEST-full.md is the complete index, opened only when the orientation layer has
# not already answered the question.
#
# The split removes the treadmill rather than moving it. Before it, one file was both
# the always-loaded layer and the complete index, so every new ADR pushed a gate that
# blocked a PR, and the generator was trimmed four times to keep up: MAX_BULLETS_PER_ADR
# 6->2, MAX_ITEMS_PER_TAG 10->9, a ~385-line duplicate index dropped, MAX_ACTIVE_ENTRIES
# capped. A budget that the growth always reaches is a wall, not a ratchet.
#
# The orientation layer is instead bounded by *kind*, not by corpus size: a fixed number
# of recent decisions, one generated tree of `docs/`, and one wrapped line of tags. Adding
# an ADR cannot grow it past MAX_ORIENTATION_LINES, because nothing in it is one-per-ADR.
DIGEST = DECISIONS_DIR / 'DIGEST.md'
DIGEST_FULL = DECISIONS_DIR / 'DIGEST-full.md'
MAX_ORIENTATION_LINES = 120

# How many recent decisions the always-loaded layer names. 20 was picked from the
# measured digest, not tuned: each entry is one `- \`slug\` — title` line, so 20 entries
# cost ~22 of the 120-line budget and leave the rest for the tree and the pointers.
ORIENTATION_RECENT = 20

# How many tags the always-loaded layer names. The corpus carries 421 of them, and
# the layer is read in every session — so it names the most-used ones and points at
# the full index for the whole vocabulary. Fixed count, like the recent list above.
ORIENTATION_TAGS = 120

# ── The fold (2026-10-23, `2026-10-23-the-full-digest-does-not-grow`) ──────────
#
# The split above bounded the layer that every session reads. The on-demand index
# kept the old shape: one line per ADR, one section per tag, one bullet per
# consequence. So it still grew with every decision — 2235 lines for 814 decisions
# is 2.75 lines per decision, or 2.71 beyond the 30 lines the empty file already
# costs — and the 3000-line budget was a wall the corpus walked into, not a ceiling
# on the structure.
#
# The index is now folded the way the orientation layer is: an entry is a *record*,
# not a row. Every decision appears exactly once, in the packed index, with its
# title and its tags. The consequences that used to be duplicated into a section per
# tag are body text — one link away — and the file says how to grep for them
# instead of carrying a second copy.
#
# That is also why the four trimming knobs are gone rather than relaxed:
# MAX_ITEMS_PER_TAG (20), MAX_BULLETS_PER_ADR (6), MAX_OPEN_DEFERRED (25) and
# MAX_ACTIVE_ENTRIES (600) each answered the same question — "what do we drop
# when the corpus grows?" — by dropping content. A cap is a wall; the fold is the
# alternative. What replaced them:
#
#   * the per-tag sections (which existed only to hold those dropped bullets) are
#     gone; the tags live on the index entries instead, so nothing is dropped at all;
#   * the flat entry list is packed, so it no longer needs a cap to stay short;
#   * the open/deferred list is packed too, and now names *every* open decision
#     rather than the newest 25.
#
# What is left grows in characters, not in lines: one decision costs ~135 characters
# (measured on the corpus of 814 decisions), so the index section grows by ~0.20 lines
# per decision as the corpus grows. The file is a few hundred lines now and stays a
# few hundred lines when the corpus doubles.
#
# The budget is therefore *not* a fixed ceiling. A fixed ceiling on an
# exhaustive index is a wall by construction: an index of N decisions has N
# entries, so any constant number of lines is reached by the corpus alone, and
# the only two answers left are "trim content" (what the four rounds of trimming
# did) or "raise the number again". What can be stated honestly is how much of
# the file each decision is allowed to cost, and how much of the file does not
# scale with the corpus at all.
#
#   DIGEST_FULL_LINES_PER_DECISION — the ceiling on what one decision may cost
#                                  the whole file. Measured on 2026-10-23 over the
#                                  prefix series (the corpus as it stood at every
#                                  earlier date, rendered and measured):
#
#                                    the last 50 decisions   0.200 lines each
#                                    the last 100 decisions  0.190 lines each
#                                    the last 200 decisions  0.225 lines each
#                                    the whole corpus         0.289 lines each
#                                    the index section alone, the worst window
#                                    of 50 in the whole history  0.240 lines each
#
#                                  The ceiling is 0.30, above every one of them.
#
#   DIGEST_FULL_FIXED_LINES        — what the file may spend on everything that
#                                  does not scale with the corpus. Measured the
#                                  same way: the covering of the whole prefix
#                                  series, max over every prefix N of
#                                  (file(N) - 0.30*N) = 80.0, at N=130.
#
#                                  The constant is the smallest integer strictly
#                                  above that covering, so the file never touches
#                                  the budget: at the tightest point of the
#                                  history the margin is one line. A constant of
#                                  80 would cover the history exactly, and the
#                                  file would sit on the line there.
#
# The two constants are read together, and the intercept is not the size of the
# empty file (30 lines): the file grew faster than the ceiling while the capped
# sections were still filling up — up to 0.98 lines per decision over a window
# of 50 decisions, at N≈80, where the readable head of the absolute rules was
# filling — and the intercept is what covers that ramp. A line through the
# prefix series that is fitted to one point is a wall; a line that lies above
# the whole measured path is a budget.
#
# The effective budget is therefore:
#
#     budget(N) = 81 + ceil(0.30 * N)
#
# It is monotone in N and grows with it — there is no floor under it and no
# ceiling over it that the corpus alone can reach. The corpus's own growth is
# slower than the rate at which the budget grows: the index section grew from 72
# lines at 400 decisions to 153 at 814 — 0.196 lines per decision — while the
# budget grows by 0.30, so the margin widens instead of closing. What the file can
# run into is the rate: growing faster than the fold allows is a finding about
# the generator, not about the corpus.
#
# The gate reads the budget out of the line this script prints
# (`DIGEST-full.md is N lines (budget: M)`), so the number has one owner: here.
# The printed budget and the applied budget come from the same function, so they
# cannot drift apart, and a test holds them together.
DIGEST_FULL_FIXED_LINES = 81
DIGEST_FULL_LINES_PER_DECISION = 0.30

# How wide one packed line of the index may get. Markdown here is not manually
# wrapped (`.editorconfig` sets `max_line_length` for `kt`/`kts` only), so a wide
# line is a rendering choice, not a lint offence. 800 characters is about five
# entries per line: few enough to stay scannable, wide enough that the growth of
# the corpus shows up in characters rather than in lines.
INDEX_WIDTH = 800

# How many absolute rules stay a readable list. The rest are packed into a
# paragraph: the rules are still in the file and `grep` still finds them, but the
# readable head cannot grow with the corpus.
MAX_CRITICAL_READABLE = 60


def parse_argv(argv: list[str]) -> None:
    """Reject every argument. This script takes none.

    Both git hooks called it as `refresh-decisions-digest.sh --quiet` (#518), and the
    flag was read by nobody — the script never looked at `sys.argv` at all. A dead
    flag is a small lie: it reads as if output were being suppressed, so the next
    person to add a real option here will assume there is an argument parser and
    write around it. Failing loudly is cheaper than being wrong quietly.
    """
    for arg in argv:
        if arg in ('-h', '--help'):
            print(__doc__)
            sys.exit(0)
        print(f"refresh-decisions-digest: unknown argument {arg!r}\n"
              "usage: refresh-decisions-digest.sh  (takes no arguments)", file=sys.stderr)
        sys.exit(2)


# Root documents that answer "where do I look", with the question each one answers.
# Existence is not checked here on purpose — `check-doc-dead-refs` already resolves every
# backticked path in the repo, so a pointer to a file that stops existing is caught there,
# and duplicating that resolution here would be a second rule to keep in step.
# The labels are the part that cannot be derived from the filesystem, and they are the
# entire reason this section is prose rather than generated names.
ROOT_POINTERS = (
    ("Rules an agent must not get wrong on any task", "`AGENTS.md`"),
    ("How the code is laid out", "`ARCHITECTURE.md`"),
    ("Current domain vocabulary", "`docs/CONTEXT.md`"),
    ("Nav3 key inventory (container / opener / start route)", "`docs/navigation.md`"),
    ("expect/actual ports, platform differences", "`docs/PLATFORM-REFERENCE.md`"),
    ("Which modules have a spec, and which do not", "`openspec/specs/MODULE-INDEX.md`"),
    ("Procedural skills (loaded by the harness)", "`.agents/skills/`"),
    ("How documentation is written and sized", "`docs/doc-maintenance.md`"),
)


def render_doc_tree(decision_count: int, deferred_count: int, archived_count: int) -> list[str]:
    """Draw `docs/` for the orientation layer.

    The drawing lives in `docs_tree.py`, which does not call `adr_corpus.load()` and is
    therefore not a corpus consumer: this module is, and `check-adr-config-sync.py` fails
    any directory walk inside a corpus consumer so that #520 cannot recur. The renderer
    takes every ADR count as an argument for the same reason — it draws directories, and
    `adr_corpus` decides what a decision is.
    """
    return docs_tree.render(
        DOCS_DIR, DECISIONS_DIR, ARCH_DIR,
        decision_count=decision_count,
        deferred_count=deferred_count,
        archived_count=archived_count,
        known_slugs=CORPUS.all_slugs(),
        deferred_dir=CORPUS.deferred_dir,
        archive_dir=CORPUS.archive_dir,
    )


def wrap_tags(tags: list[str], width: int = 96) -> list[str]:
    """One wrapped paragraph of `tag` names, so the index costs lines not entries.

    Forty tags at one line each would spend a third of the orientation budget on a list
    that exists only to say "these words appear in the corpus". Grouped and wrapped it is
    a handful of lines that a reader scans, and `DIGEST-full.md` holds the whole
    vocabulary and the decisions that carry each tag.
    """
    if not tags:
        return []
    lines: list[str] = []
    current = ""
    for tag in tags:
        token = f"`{tag}`"
        candidate = f"{current} {token}".strip()
        if current and len(candidate) > width:
            lines.append(current)
            current = token
        else:
            current = candidate
    if current:
        lines.append(current)
    return lines


# The markers of an absolute rule, and the tail of the form "_(from `slug`)_" that a
# rule already carries.
CRITICAL_RE = re.compile(r'\*\*Always\*\*|\*\*Never\*\*|\*\*MUST\*\*')
CRIT_SUFFIX_RE = re.compile(r'\s*_\(from\s+`([^`]+)`\)_\s*$')


def fmt_bullet(bullet: str, slug: str) -> str:
    """Append the citation of the decision, unless the rule already carries it."""
    existing = CRIT_SUFFIX_RE.search(bullet)
    if existing and existing.group(1) == slug:
        return bullet
    return f"{bullet} _(from `{slug}`)_"


def index_entry(slug: str, title: str, tags: list[str]) -> str:
    """An index entry: slug, title, tags.

    The three fields a decision is searched by: the slug is the file name, the
    title is the heading of the ADR, and the tags are the `#markers` a grep for a
    tag finds.
    """
    entry = f"`{slug}` — {title or '_(no title)_'}"
    if tags:
        entry = f"{entry} " + " ".join(f"#{t}" for t in tags)
    return entry


def digest_full_budget(decisions: int) -> int:
    """The budget of the complete index for a corpus of `decisions` decisions.

    The fixed part plus the ceiling per decision — see the constants above. The
    budget is monotone and grows with the corpus, so "many ADRs" cannot exhaust
    it: only an index that grows faster than the fold allows can. The fixed part
    is the covering of the whole measured history plus the line that keeps the
    file off the budget, so the margin is never zero.
    """
    return DIGEST_FULL_FIXED_LINES + math.ceil(
        DIGEST_FULL_LINES_PER_DECISION * decisions)


def pack_items(items: list[str], width: int = INDEX_WIDTH, sep: str = " · ",
               cont_indent: str = "") -> list[str]:
    """Pack the entries into paragraphs of a fixed width.

    One entry per line means "the file grows with the corpus": the list of
    decisions gave a line to each, and every new decision added a line. Packing
    moves the growth from lines into characters — the file stays readable when the
    corpus doubles, and a grep over it works as it did.

    `cont_indent` — the indent of a continuation line (a Markdown list).
    """
    packed: list[str] = []
    current = ""
    for item in items:
        candidate = f"{current}{sep}{item}" if current else item
        if current and len(candidate) > width:
            packed.append(current)
            current = item
        else:
            current = candidate
    if current:
        packed.append(current)
    if not cont_indent:
        return packed
    return [packed[0]] + [cont_indent + line for line in packed[1:]]


@dataclass
class Corpus:
    """What the corpus of decisions tells the generator.

    A structure of its own rather than dictionaries in the body of `main`: the
    test builds the fold on an artificial corpus and must see exactly what the
    generator sees.
    """

    order: list[str]
    titles: dict[str, str]
    tags: dict[str, list[str]]
    statuses: dict[str, str]
    superseded: dict[str, str]
    self_superseded: set[str]
    critical: list[tuple[str, str]]
    tag_counts: dict[str, int]

    def replaced_slugs(self) -> set[str]:
        """The decisions that no longer stand — the ones that are superseded.

        Three declarations say the same thing, and any one of them is enough:

        - the replaced decision says `status: superseded` in its own frontmatter;
        - it names what replaced it, in `superseded-by:` or `superseded_by:`;
        - the replacing decision names it, in `supersedes:`.

        The first two are the decision's own words about itself, and the third is
        the word of the decision that replaced it. A decision that carries one of
        them is not in force, whatever the others say, and a decision that carries
        none of them is in force until one of them says otherwise.

        Only the decisions of the corpus are returned: the target of a
        `supersedes:` may live in `archive/`, which the corpus deliberately does
        not read.
        """
        forward = {slug for slug in self.superseded if slug in self.titles}
        own = {slug for slug in self.self_superseded if slug in self.titles}
        return forward | own


def parse_frontmatter(text: str) -> dict[str, str]:
    """The frontmatter of a decision — its fields.

    The frontmatter is the first block and only the first block: a decision may
    carry `---` in its body, as a horizontal rule or as the marker of an
    amendment, and every later `---` is body text rather than a delimiter.
    """
    lines = text.splitlines()
    if not lines or lines[0].strip() != '---':
        return {}
    fields: dict[str, str] = {}
    for line in lines[1:]:
        if line.strip() == '---':
            break
        if ':' in line:
            key, _, val = line.partition(':')
            fields[key.strip()] = val.strip().strip('"').strip("'")
    return fields


def body_of(text: str) -> str:
    """The body of a decision — everything after the frontmatter block."""
    lines = text.splitlines()
    if not lines or lines[0].strip() != '---':
        return text
    for index, line in enumerate(lines[1:], start=1):
        if line.strip() == '---':
            return '\n'.join(lines[index + 1:])
    return text


def first_heading(text: str) -> str:
    """The first heading of the body — the fallback for a missing title.

    28 decisions of the corpus carry no `title:` in their frontmatter. Their title
    is the heading of their body, and without it the decision was not findable by
    title anywhere in the file: the index promises three ways to find a decision,
    and the heading of the body is the second of them.

    The body starts after the frontmatter; a `---` further down is a horizontal
    rule and not a delimiter, so the heading that follows one is still a heading.
    Code blocks are skipped: a `#` in an example is not a heading.
    """
    in_code = False
    for line in body_of(text).splitlines():
        stripped = line.strip()
        if stripped.startswith('```'):
            in_code = not in_code
            continue
        if in_code:
            continue
        if line.startswith('# '):
            return line[2:].strip()
    return ''


def read_corpus(entries: list[Path]) -> Corpus:
    """Read the corpus of decisions: frontmatter, consequences, absolute rules."""
    return read_corpus_texts({path: path.read_text() for path in entries})


@dataclass(frozen=True)
class _Decision:
    """What one decision file says — the parts the index is built from.

    Parsed once per file. The prefix series — the corpus as it stood at every
    earlier date — renders the same decisions over and over, and parsing them
    again for every prefix is what made the series cost minutes instead of
    seconds.
    """

    slug: str
    title: str
    tags: tuple[str, ...]
    status: str
    supersedes: str
    superseded_by: str
    bullets: tuple[str, ...]


def _consequence_bullets(text: str) -> tuple[str, ...]:
    """The bulleted items of the "Consequences" section of a decision.

    The consequences are body text: they are read here and kept only where
    they are rules (the Critical section). The ADR body stays the one copy of
    the text.
    """
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
                bullet = match.group(1).strip()
                if bullet:
                    bullets.append(bullet)
    return tuple(bullets)


@functools.lru_cache(maxsize=None)
def _read_decision(slug: str, text: str) -> _Decision:
    """Read one decision — the title, the tags, the status, the rules.

    Cached on the text it was given: the same file is read once per prefix of
    the corpus in the prefix series, and a cache keyed on the text itself is
    exact — a different text is a different entry.
    """
    fm = parse_frontmatter(text)
    # The title of a decision comes from its frontmatter, and when it has none
    # from the first heading of its body: 28 decisions of the corpus carry no
    # `title:` in their frontmatter, and without the fallback they were not
    # findable by title anywhere.
    title = fm.get('title', '') or first_heading(text)
    # The tags of a decision come from its frontmatter. That is the vocabulary
    # of the corpus, and a decision is searched by it: a tag that never showed
    # up in a consequence used to be findable nowhere in the file.
    tags: list[str] = []
    for raw_tag in fm.get('tags', '').strip('[]').split(','):
        tag = raw_tag.strip().strip('"\'').lower()
        if tag and tag not in tags:
            tags.append(tag)
    supersedes = ''
    if fm.get('supersedes'):
        # Strip brackets: `[slug]` was being stored verbatim and never resolved.
        supersedes = fm['supersedes'].strip('[]').removesuffix('.md')
    # The other side of the same edge: the replaced decision names what replaced
    # it. The corpus writes it in two spellings — `superseded-by:` and
    # `superseded_by:` — and the supersede process (`2026-09-26-adr-supersede-process`)
    # asks for the first. Both are read, because a decision that says what
    # replaced it has said it, and the digest has to believe it.
    superseded_by = ''
    for key in ('superseded-by', 'superseded_by'):
        if fm.get(key):
            superseded_by = fm[key].strip('[]').strip().removesuffix('.md')
            break
    return _Decision(
        slug=slug,
        title=title,
        tags=tuple(tags),
        status=CORPUS.normalize_status(fm.get('status', '')),
        supersedes=supersedes,
        superseded_by=superseded_by,
        bullets=_consequence_bullets(text),
    )


def read_corpus_texts(texts: dict[Path, str]) -> Corpus:
    """Read a corpus whose texts are already in hand.

    The prefix series — the corpus as it stood at every earlier date — renders the
    same decisions over and over, and reading the files again for each prefix is
    the only slow part. The texts are read once, here they are handed over, and
    this reads the texts it was given: a parameter that is only used for its keys
    is a parameter that costs the whole run and buys nothing.
    """
    titles: dict[str, str] = {}
    tags_of: dict[str, list[str]] = {}
    statuses: dict[str, str] = {}
    superseded: dict[str, str] = {}
    self_superseded: set[str] = set()
    entries = list(texts)
    decisions = [_read_decision(path.stem, texts[path]) for path in entries]

    for decision in decisions:
        titles[decision.slug] = decision.title
        tags_of[decision.slug] = list(decision.tags)
        statuses[decision.slug] = decision.status
        if decision.supersedes:
            superseded[decision.supersedes] = decision.slug
        # A decision that says of itself that it was replaced has said it, in one
        # of the three ways a decision can say that: its own status, the name of
        # the decision that replaced it, or the `supersedes:` of that decision.
        if decision.status == 'superseded' or decision.superseded_by:
            self_superseded.add(decision.slug)

    # Build corpus: dedupe on (bullet_lower, slug)
    #
    # The consequences of a decision are body text. They used to be copied into a
    # section per tag — the same sentence once per tag the decision carried — and
    # that copy is what made the index grow with the corpus. The fold keeps the
    # consequences only where they are rules (the Critical section) and lets the
    # ADR body be the one copy of the text.
    #
    # The corpus is built first, because the question of which decisions are
    # replaced is the corpus's own question, and the corpus answers it in one
    # place: `Corpus.replaced_slugs`.
    #
    # The vocabulary of the tags — over the whole corpus, superseded decisions
    # included: a decision is searched by its tag, and a decision somebody
    # replaced has to be findable too.
    tag_counts: dict[str, int] = {}
    for decision in decisions:
        for tag in decision.tags:
            tag_counts[tag] = tag_counts.get(tag, 0) + 1

    corpus = Corpus(
        order=[p.stem for p in entries],
        titles=titles,
        tags=tags_of,
        statuses=statuses,
        superseded=superseded,
        self_superseded=self_superseded,
        critical=[],
        tag_counts=tag_counts,
    )

    # A replaced decision does not get to put its rules into the Critical
    # section: the corpus's own rule (2026-09-26-adr-supersede-process) says the
    # rules of a replaced decision are not duplicated there.
    #
    # "Replaced" is the three-way notion of `Corpus.replaced_slugs`, and not only
    # the forward edge. A decision that says of itself that it was replaced —
    # `status: superseded`, or `superseded-by:` — is replaced, and reading only
    # the `supersedes:` of another decision would let its rules back in.
    replaced = corpus.replaced_slugs()
    bullets_by_slug: dict[str, list[str]] = {
        decision.slug: list(decision.bullets)
        for decision in decisions
        if decision.slug not in replaced
    }

    # The absolute rules: the newest decision first, one copy of the same rule.
    critical: list[tuple[str, str]] = []
    seen_bullet: set[str] = set()
    for decision in reversed(decisions):
        for bullet in bullets_by_slug.get(decision.slug, []):
            if CRITICAL_RE.search(bullet) and bullet.lower() not in seen_bullet:
                seen_bullet.add(bullet.lower())
                critical.append((bullet, decision.slug))

    corpus.critical = critical
    return corpus


def render_full_digest(corpus: Corpus) -> list[str]:
    """Render the complete index — the file that is opened on demand."""
    titles = corpus.titles
    tags_of = corpus.tags
    statuses = corpus.statuses
    critical = corpus.critical
    tag_counts = corpus.tag_counts
    # The decisions that no longer stand — see `Corpus.replaced_slugs` for the
    # three ways a decision says that, and for the priority between them.
    replaced = corpus.replaced_slugs()

    open_deferred = {
        s: (statuses[s], titles[s])
        for s in statuses
        if statuses[s] in ('open', 'deferred')
    }

    # ── The index itself ─────────────────────────────────────────────────────
    #
    # Every decision that was not superseded, once, with its title and its tags.
    # The list is packed, so the corpus grows the file in characters, not in lines.
    active = [slug for slug in corpus.order if slug not in replaced]
    index_entries: list[str] = []
    for slug in reversed(active):
        index_entries.append(index_entry(slug, titles.get(slug, ''),
                                         tags_of.get(slug, [])))

    out: list[str] = []
    out.append("# Decision Log — full index")
    out.append("")
    out.append("Auto-generated from `docs/decisions/`. Run `./scripts/refresh-decisions-digest.sh` to rebuild.")
    out.append("")
    out.append(
        "The complete index: every decision in the corpus, once, with its title and its "
        "tags. The entries are packed, so the file grows in characters rather than in "
        "lines. The layer every session reads is `DIGEST.md`."
    )
    out.append("")
    out.append("## How to search")
    out.append("")
    out.append("- by slug — `grep -n 'slug-fragment' docs/decisions/DIGEST-full.md`")
    out.append("- by title — `grep -in 'phrase' docs/decisions/DIGEST-full.md`")
    out.append("- by tag — `grep -n '#tag' docs/decisions/DIGEST-full.md`")
    out.append("- the corpus itself — `grep -rl 'phrase' docs/decisions/`: the index maps, the ADRs hold the text")
    out.append("")

    out.append("## Critical")
    out.append("")

    if critical:
        # The rules stay a list while the list does not grow with the corpus.
        # After that they are packed: the rule is still in the file and a grep
        # still finds it, but the readable list does not grow.
        head = critical[:MAX_CRITICAL_READABLE]
        tail = critical[MAX_CRITICAL_READABLE:]
        for bullet, slug in head:
            out.append(f"- {fmt_bullet(bullet, slug)}")
        if tail:
            out.append("")
            out.append(f"_{len(tail)} older absolute rules, packed: `grep` finds any of them._")
            out.extend(pack_items([fmt_bullet(b, s) for b, s in tail]))
    else:
        out.append("_No critical markers. Add **Always**, **Never**, or **MUST** to Consequences._")

    out.append("")
    out.append("## Tags")
    out.append("")
    if tag_counts:
        out.extend(pack_items([f"#{tag} ({count})" for tag, count in sorted(tag_counts.items())]))
    else:
        out.append("_No tags._")
    out.append("")

    out.append("## Status")
    out.append("")
    # The groups are exclusive: a decision is in one of them, and the counts add up.
    #
    # A decision that was replaced is listed as superseded, and the priority is
    # the replacement: a decision somebody replaced is not pending any more, even
    # when its own frontmatter still says `deferred` or `accepted`. What counts
    # as "somebody replaced it" is any of the three declarations the corpus
    # allows — its own `status: superseded`, its own `superseded-by:`, or the
    # `supersedes:` of the decision that replaced it. See
    # `Corpus.replaced_slugs`.
    #
    # Only the decisions of the corpus are named. The target of a `supersedes:`
    # may live in `archive/`, which the corpus deliberately does not read, and a
    # decision that is not in the corpus has no title here and no place in the
    # index: naming it would put a line in the file that nothing can be found
    # from.
    pending = {slug: (status, title) for slug, (status, title) in open_deferred.items()
               if slug not in replaced}
    for label in ('open', 'deferred'):
        slugs = sorted((slug for slug, (status, _) in pending.items() if status == label),
                       reverse=True)
        if not slugs:
            continue
        packed = pack_items([f"`{slug}`" for slug in slugs], cont_indent="  ")
        out.append(f"- **{label}** ({len(slugs)}) — {packed[0]}")
        out.extend(packed[1:])
    if replaced:
        packed = pack_items(
            [index_entry(slug, titles.get(slug, ''), tags_of.get(slug, []))
             for slug in sorted(replaced, reverse=True)],
            cont_indent="  ")
        out.append(f"- **superseded** ({len(replaced)}) — {packed[0]}")
        out.extend(packed[1:])
    if not pending and not replaced:
        out.append("_No open, deferred or superseded entries._")
    out.append("")

    out.append("## Decisions")
    out.append("")
    out.append(f"_{len(index_entries)} decisions, newest first._")
    out.append("")
    out.extend(pack_items(index_entries))
    out.append("")
    return out


def render_orientation(corpus: Corpus, live_decisions: list[Path]) -> list[str]:
    """Render the orientation layer — the file every session reads.

    Bounded by kind, not by corpus size: a fixed number of recent decisions, one
    tree of `docs/`, a fixed number of tags. No section here is one-per-decision,
    so a new decision cannot push the layer over its ceiling.

    `live_decisions` — the live decisions of the corpus. An argument rather than
    a reading of the module: the layer must draw from what it was given, or its
    size cannot be measured on an artificial corpus.
    """
    titles = corpus.titles
    # The same rule as in the full index: a decision that was replaced is not a
    # live decision, whichever of the three declarations says so.
    replaced = corpus.replaced_slugs()
    tag_counts = corpus.tag_counts

    # ── DIGEST.md — the always-loaded orientation layer ─────────────────────
    #
    # Bounded by kind, not by corpus size: a fixed number of recent decisions, one
    # generated tree, one wrapped line of tags. No section here is one-per-ADR, so a new
    # ADR cannot push it over MAX_ORIENTATION_LINES — which is the whole point, and the
    # thing the single-file version could never be.
    light: list[str] = []
    light.append("# Documentation map")
    light.append("")
    light.append(
        "Auto-generated. Rebuild: `./scripts/refresh-decisions-digest.sh`. "
        "Complete decision index: `docs/decisions/DIGEST-full.md`.")
    light.append("")
    light.append("## Where to look")
    light.append("")
    for question, target in ROOT_POINTERS:
        light.append(f"- {question} — {target}")
    light.append(f"- A decision — `docs/decisions/`; this file's *Recent decisions*, "
                 "or `DIGEST-full.md` for the full index")
    light.append("")
    light.append("## docs/")
    light.append("")
    light.extend(render_doc_tree(
        len(live_decisions), len(CORPUS.deferred()), len(CORPUS.archived())))
    light.append("")
    light.append("## Recent decisions")
    light.append("")
    recent = [p for p in reversed(live_decisions)
              if p.stem not in replaced][:ORIENTATION_RECENT]
    if recent:
        for path in recent:
            slug = path.stem
            light.append(f"- `{slug}` — {titles.get(slug, '') or '_(no title)_'}")
    else:
        light.append("_No dated decisions._")
    live_total = len([p for p in live_decisions if p.stem not in replaced])
    if live_total > ORIENTATION_RECENT:
        light.append(f"- _... and {live_total - ORIENTATION_RECENT} older in "
                     "`docs/decisions/` (filenames are date-prefixed and "
                     "self-describing; `grep` them)_")
    light.append("")
    # The tags of the orientation layer are the tags of the full index, bounded in
    # number: the layer is read in every session, and the whole vocabulary of the
    # corpus (421 tags) would be paid for in every session. The layer is bounded by
    # kind, like its other sections: a fixed number of the most-used tags.
    ranked_tags = sorted(tag_counts.items(), key=lambda kv: (-kv[1], kv[0]))
    light_tags = sorted(tag for tag, _ in ranked_tags[:ORIENTATION_TAGS])
    if light_tags:
        light.append("## Decision tags")
        light.append("")
        light.extend(wrap_tags(light_tags))
        light.append("")
        light.append(f"_{len(light_tags)} most-used tags; the full vocabulary and every "
                     "decision with its title: `DIGEST-full.md`._")
    return light


def budget_failures(light_lines: int, full_lines: int, decisions: int) -> list[str]:
    """The lines that say a file is over its budget; empty when nothing is.

    The message has to name the numbers. "Over budget" without numbers is the
    silence nobody looks at: the reader does not learn what broke, nor by how
    much.
    """
    failures: list[str] = []
    if light_lines > MAX_ORIENTATION_LINES:
        failures.append(f"DIGEST.md is {light_lines} lines (budget: {MAX_ORIENTATION_LINES})")
    budget = digest_full_budget(decisions)
    if full_lines > budget:
        failures.append(f"DIGEST-full.md is {full_lines} lines (budget: {budget})")
    return failures


def digest_status_line(light_lines: int, full_lines: int, decisions: int) -> str:
    """The one machine-readable line the gate parses.

    The budget in this line is the budget the generator applies: both come from
    `digest_full_budget`, so the number the gate reads and the number the
    generator enforces are one number and cannot drift apart.
    """
    budget = digest_full_budget(decisions)
    return (f"refresh-decisions-digest: DIGEST.md is {light_lines} lines "
            f"(budget: {MAX_ORIENTATION_LINES}), DIGEST-full.md is {full_lines} lines "
            f"(budget: {budget}), {decisions} entries")


def main() -> None:
    parse_argv(sys.argv[1:])
    # main made this walk recursive (T1), which fixed a real miss — but with `**/`,
    # which also swept in `archive/`. That regressed #508: all 14 findings/retro
    # inventories that were moved out of the decision corpus came back into the
    # agent-facing digest, which is the one surface that is always loaded.
    #
    # So the miss is fixed and the archive stays out. `CORPUS.deferred()` is what
    # supplies the nested entries T1 was reaching for, and `include_archived=False`
    # is what keeps inventory documents out. Verified: 0 of 14 archived slugs appear
    # in the generated digest.
    entries = CORPUS.decisions(include_archived=False) + CORPUS.deferred()
    if not entries:
        print(f"no dated entries in {DECISIONS_DIR} — digest untouched")
        return

    corpus = read_corpus(entries)

    out = render_full_digest(corpus)
    # ── DIGEST-full.md — the complete index ────────────────────────────────
    DIGEST_FULL.write_text('\n'.join(out) + '\n')
    full_lines = len(out)

    # ── DIGEST.md — the always-loaded orientation layer ─────────────────────
    light = render_orientation(corpus, CORPUS.decisions(include_archived=False))
    DIGEST.write_text('\n'.join(light) + '\n')
    light_lines = len(light)

    over = budget_failures(light_lines, full_lines, len(entries))

    # One machine-readable line, in the shape `sgate decisions_digest_within_budget`
    # parses. It used to print `WARNING: DIGEST is N lines (limit: M)` to stderr and
    # exit 0 — which no regex matched and no gate could act on, so the budget that four
    # rounds of trimming had been paying for was not actually enforced here at all.
    #
    # The line is built by `digest_status_line`, and the budget in it comes from the
    # same function the check above uses: a printed budget that is not the applied one
    # makes the report of the gate a lie.
    print(digest_status_line(light_lines, full_lines, len(entries)))
    for line in over:
        print(line, file=sys.stderr)
    if over:
        sys.exit(1)


if __name__ == '__main__':
    main()
