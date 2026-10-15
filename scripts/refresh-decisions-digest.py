#!/usr/bin/env python3
"""refresh-decisions-digest.py — generate DIGEST.md from ADR entries.

Run via: ./scripts/refresh-decisions-digest.sh
Or directly: python3 ./scripts/refresh-decisions-digest.py
"""

import re
import sys
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
MAX_DIGEST_FULL_LINES = 3000

# How many recent decisions the always-loaded layer names. 20 was picked from the
# measured digest, not tuned: each entry is one `- \`slug\` — title` line, so 20 entries
# cost ~22 of the 120-line budget and leave the rest for the tree and the pointers.
ORIENTATION_RECENT = 20

# The caps below now bound DIGEST-full.md only. They were written for a file that every
# session paid for; on an on-demand index the tradeoff is different — the reader opened
# it deliberately and wants the tag section, not nine of its forty bullets. They are
# relaxed rather than removed: an uncapped index is a second copy of the directory
# listing, and one verbose ADR tagged `always` would fill every section it touches.
MAX_ITEMS_PER_TAG = 20  # per tag section; the ADR body is one link away
MAX_BULLETS_PER_ADR = 6  # per ADR inside the per-tag sections
# The two flat sections below are capped for the same reason, with the same
# "_... and N more items_" convention the per-tag sections already use.
#
# MAX_ACTIVE_ENTRIES covers the whole live corpus (595 records as of 2026-10-15) rather
# than the previous 300, so "Active entries" is no longer a truncated tail — the honest
# count line only appears if the corpus actually grows past this.
MAX_OPEN_DEFERRED = 25
MAX_ACTIVE_ENTRIES = 600


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
    a handful of lines that a reader scans, and `DIGEST-full.md` holds the per-tag detail.
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

    titles: dict[str, str] = {}
    tags_raw: dict[str, str] = {}
    statuses: dict[str, str] = {}
    superseded: dict[str, str] = {}

    for path in entries:
        slug = path.stem
        fm: dict[str, str] = {}
        in_fm = False
        for line in path.read_text().splitlines():
            if line.strip() == '---':
                in_fm = not in_fm
                if not in_fm:
                    break
            if in_fm and ':' in line:
                key, _, val = line.partition(':')
                fm[key.strip()] = val.strip().strip('"').strip("'")
        titles[slug] = fm.get('title', '')
        tags_raw[slug] = fm.get('tags', '')
        statuses[slug] = CORPUS.normalize_status(fm.get('status', ''))
        if fm.get('supersedes'):
            # Strip brackets: `[slug]` was being stored verbatim and never resolved.
            superseded[fm['supersedes'].strip('[]').removesuffix('.md')] = slug

    CRITICAL_RE = re.compile(r'\*\*Always\*\*|\*\*Never\*\*|\*\*MUST\*\*')
    CRIT_SUFFIX_RE = re.compile(r'\s*_\(from\s+`([^`]+)`\)_\s*$')

    # Build corpus: dedupe on (bullet_lower, slug)
    by_slug_tag: dict[str, list[tuple[str, str]]] = {}
    slug_for_bullet: dict[str, str] = {}

    for path in entries:
        slug = path.stem
        if slug in superseded:
            continue
        text = path.read_text()
        in_consequences = False
        for line in text.splitlines():
            if line.strip() == '## Consequences':
                in_consequences = True
                continue
            if in_consequences and line.startswith('## '):
                in_consequences = False
                continue
            if in_consequences:
                m = re.match(r'^[-*]\s+(.+)', line.strip())
                if m:
                    bullet = m.group(1).strip()
                    if bullet:
                        tag_str = tags_raw.get(slug, '').strip('[]').strip()
                        if tag_str:
                            for tag in tag_str.split(','):
                                tag = tag.strip().strip('"\'').lower()
                                if tag:
                                    by_slug_tag.setdefault(slug, []).append((tag, bullet))
                        else:
                            by_slug_tag.setdefault(slug, []).append(('_untagged_', bullet))
                    slug_for_bullet[bullet.lower()] = slug

    seen_bullet_slug: set[tuple[str, str]] = set()
    corpus: list[tuple[str, str, str]] = []
    for slug, tag_bullets in by_slug_tag.items():
        for tag, bullet in tag_bullets:
            key = (bullet.lower(), slug)
            if key not in seen_bullet_slug:
                seen_bullet_slug.add(key)
                corpus.append((tag, bullet, slug))

    corpus.sort()
    critical = [(b, slug_for_bullet[b.lower()]) for (t, b, s) in corpus if CRITICAL_RE.search(b)]

    # Cap each ADR's contribution to the per-tag sections. A verbose ADR that
    # lists fifteen consequences used to fill every tag section it was tagged
    # with, so the digest grew with the wordiest author rather than with the
    # number of decisions. The Critical section is exempt: **Always** / **Never**
    # rules are exactly what a reader came for, and they are already a short,
    # hand-curated list.
    per_slug_kept: dict[str, int] = {}
    dropped_per_slug: dict[str, int] = {}
    per_tag: dict[str, list[tuple[str, str]]] = {}
    for (t, b, s) in corpus:
        if CRITICAL_RE.search(b):
            continue
        seen = per_slug_kept.get(s, 0)
        if seen >= MAX_BULLETS_PER_ADR:
            dropped_per_slug[s] = dropped_per_slug.get(s, 0) + 1
            continue
        per_slug_kept[s] = seen + 1
        per_tag.setdefault(t, []).append((b, s))

    open_deferred = {
        s: (statuses[s], titles[s])
        for s in statuses
        if statuses[s] in ('open', 'deferred')
    }

    recent_superseded: list[tuple[str, str]] = []
    for path in reversed(entries):
        slug = path.stem
        if slug in superseded:
            recent_superseded.append((slug, titles.get(slug, '')))
            if len(recent_superseded) >= 5:
                break

    def fmt_bullet(bullet: str, slug: str) -> str:
        """Append slug citation unless bullet already ends with one for the same slug."""
        existing = CRIT_SUFFIX_RE.search(bullet)
        if existing and existing.group(1) == slug:
            return bullet
        return f"{bullet} _(from `{slug}`)_"

    out: list[str] = []
    out.append("# Decision Log — full index")
    out.append("")
    out.append("Auto-generated from `docs/decisions/`. Run `./scripts/refresh-decisions-digest.sh` to rebuild.")
    out.append("")
    out.append("Complete index, opened on demand. The layer every session reads is `DIGEST.md`.")
    out.append("")
    out.append("## Critical")
    out.append("")

    if critical:
        seen_b: set[str] = set()
        for b, s in critical:
            if b.lower() not in seen_b:
                seen_b.add(b.lower())
                out.append(f"- {fmt_bullet(b, s)}")
    else:
        out.append("_No critical markers. Add **Always**, **Never**, or **MUST** to Consequences._")

    out.append("")
    out.append("## Per-tag")
    out.append("")

    for tag in sorted(per_tag.keys()):
        out.append(f"### `{tag}`")
        out.append("")
        seen: set[tuple[str, str]] = set()
        items = sorted(per_tag[tag])
        shown = 0
        marker_done: set[str] = set()
        for b, s in items:
            key = (b.lower(), s)
            if key in seen:
                continue
            seen.add(key)
            if shown >= MAX_ITEMS_PER_TAG:
                continue
            extra = dropped_per_slug.get(s, 0)
            if extra and s not in marker_done:
                marker_done.add(s)
                out.append(f"- {b} _(+{extra} more in `{s}`)_")
            else:
                out.append(f"- {b}")
            shown += 1
        total = len(seen)
        if total > MAX_ITEMS_PER_TAG:
            out.append(f"- _... and {total - MAX_ITEMS_PER_TAG} more items_")
        out.append("")

    out.append("## Open / Deferred")
    out.append("")
    if open_deferred:
        out.append(f"_{len(open_deferred)} entries need attention._")
        out.append("")
        shown = 0
        for slug in sorted(open_deferred.keys(), reverse=True):
            if shown >= MAX_OPEN_DEFERRED:
                break
            st, ti = open_deferred[slug]
            out.append(f"- `{slug}` — **{st}** — {ti}")
            shown += 1
        total_open = len(open_deferred)
        if total_open > MAX_OPEN_DEFERRED:
            out.append(f"- _... and {total_open - MAX_OPEN_DEFERRED} more in "
                       "`docs/decisions/deferred-backlog.md`_")
    else:
        out.append("_No open or deferred entries._")

    out.append("")
    if recent_superseded:
        out.append("## Recently superseded")
        out.append("")
        for slug, ti in recent_superseded:
            out.append(f"- `{slug}` — {ti}")
        out.append("")

    out.append("## Active entries")
    out.append("")
    # One index, not two. A former "Index (slug -> tags)" section listed every slug
    # alongside its tags while this one lists the same slugs alongside their titles —
    # ~385 duplicated lines that pushed the digest past its budget on every new ADR.
    # Tags are already reachable through the per-tag sections above, and a title is the
    # more useful half of an index.
    active = [p for p in entries if p.stem not in superseded]
    for path in reversed(active[-MAX_ACTIVE_ENTRIES:]):
        slug = path.stem
        out.append(f"- `{slug}` — {titles.get(slug, '') or '_(no title)_'}")
    if len(active) > MAX_ACTIVE_ENTRIES:
        out.append(f"- _... and {len(active) - MAX_ACTIVE_ENTRIES} older entries in "
                   "`docs/decisions/`_")
    out.append("")

    # ── DIGEST-full.md — the complete index ────────────────────────────────
    DIGEST_FULL.write_text('\n'.join(out) + '\n')
    full_lines = len(out)

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
    live_decisions = CORPUS.decisions(include_archived=False)
    light.extend(render_doc_tree(
        len(live_decisions), len(CORPUS.deferred()), len(CORPUS.archived())))
    light.append("")
    light.append("## Recent decisions")
    light.append("")
    recent = [p for p in reversed(live_decisions)
              if p.stem not in superseded][:ORIENTATION_RECENT]
    if recent:
        for path in recent:
            slug = path.stem
            light.append(f"- `{slug}` — {titles.get(slug, '') or '_(no title)_'}")
    else:
        light.append("_No dated decisions._")
    live_total = len([p for p in live_decisions if p.stem not in superseded])
    if live_total > ORIENTATION_RECENT:
        light.append(f"- _... and {live_total - ORIENTATION_RECENT} older in "
                     "`docs/decisions/` (filenames are date-prefixed and "
                     "self-describing; `grep` them)_")
    light.append("")
    all_tags = sorted({t for t in per_tag if t != '_untagged_'})
    if all_tags:
        light.append("## Decision tags")
        light.append("")
        light.extend(wrap_tags(all_tags))
        light.append("")
        light.append("_Per-tag detail in `DIGEST-full.md`._")

    DIGEST.write_text('\n'.join(light) + '\n')
    light_lines = len(light)

    over = []
    if light_lines > MAX_ORIENTATION_LINES:
        over.append(f"DIGEST.md is {light_lines} lines (budget: {MAX_ORIENTATION_LINES})")
    if full_lines > MAX_DIGEST_FULL_LINES:
        over.append(f"DIGEST-full.md is {full_lines} lines (budget: {MAX_DIGEST_FULL_LINES})")

    # One machine-readable line, in the shape `sgate decisions_digest_within_budget`
    # parses. It used to print `WARNING: DIGEST is N lines (limit: M)` to stderr and
    # exit 0 — which no regex matched and no gate could act on, so the budget that four
    # rounds of trimming had been paying for was not actually enforced here at all.
    print(f"refresh-decisions-digest: DIGEST.md is {light_lines} lines "
          f"(budget: {MAX_ORIENTATION_LINES}), DIGEST-full.md is {full_lines} lines "
          f"(budget: {MAX_DIGEST_FULL_LINES}), {len(entries)} entries")
    for line in over:
        print(line, file=sys.stderr)
    if over:
        sys.exit(1)


if __name__ == '__main__':
    main()
