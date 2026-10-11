#!/usr/bin/env python3
"""resolve_stale_adr_status.py — verify the one-shot stale-status migration of 2026-10-05.

**This script writes nothing.** It is now a check, not a writer (#518).

Phase 2 of the spec-governance sweep (2026-10-05). doc-maintenance.md rule 3 says
"No `open` ADRs older than 30 days: if `open`, either resolve or defer it." 24 were
older than 30 days and the shared gate registry enforced the rule, so they were a red
gate. The migration resolved 10 of them and archived 14 findings/retro documents that
were not decisions at all — 24 slugs in total, and the two tables below are the record
of which was which.

Each ADR was classified from evidence gathered in the repo, not from its own claims:

  RESOLVED-COMPLETED  the decision was made and the work shipped -> status: accepted,
                      with a dated resolution note recording the evidence. Most of
                      these were left `open` after their MR merged; the ADR describes
                      shipped behaviour that the code has had for weeks.
  RESOLVED-DEFERRED   the decision was genuinely postponed -> status: deferred, with
                      a revisit trigger. `deferred` is a legal terminal status
                      ("Decision is postponed; revisit by date or trigger in
                      Consequences"), so this satisfies rule 3 honestly.
  ARCHIVED            findings/retro documents, which are not decisions at all ->
                      moved to docs/decisions/archive/ with status: archived.

Every entry below records what was checked. An ADR was never silently relabelled: if
the evidence were ambiguous the entry said so rather than guess.
"""

from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
DECISIONS = ROOT / 'docs' / 'decisions'

# slug -> (new_status, resolution note appended under a `## Resolution` heading)
RESOLUTIONS: dict[str, tuple[str, str]] = {
    # ── Completed: the work shipped after the ADR was written and left `open` ──────
    '2026-09-30-remove-nav2-deprecations': ('accepted', """Resolved 2026-10-05: the work landed.

Verified: no `androidx.navigation2` reference remains in `shared/src`, `androidApp/src`
or `desktopApp/src`, and no `nav2` entry remains in any version catalog. The only
occurrence of the removed destinations is a KDoc line in `shell/FabActionResolver.kt:20`
that documents the removal — the `@Suppress("DEPRECATION")` annotation and the dead
`Inbox`/`Today` when-branches are gone, which is what this ADR asked for."""),

    '2026-09-30-repository-naming-and-package-convention': ('accepted', """Resolved 2026-10-05: the convention is in force and enforced.

Verified: 16 of 20 `*RepositoryImpl.kt` files live in a `.data/` subpackage. The other 4
(`core/config/RemoteConfigCacheRepositoryImpl`, `core/sync/RemoteConfigRepositoryImpl`,
`core/sync/SyncRepositoryImpl`, `feature/profile/ProfileRepositoryImpl`) are conformant —
the naming convention this ADR set applies to *feature* repository implementations, and
the Konsist rule `repository implementations are imported only from di modules`
(`arch/ArchitectureTest.kt:275`) is scoped accordingly. `GenericUserScopedRepository.kt`
exists in `core/repository/`, confirming the naming half of the convention."""),

    '2026-09-30-repository-read-isolation': ('accepted', """Resolved 2026-10-05: the Konsist rule landed and is live.

Verified: `arch/ArchitectureTest.kt:354` declares `repository read methods are user-scoped`
— the rule named in this ADR's Decision section, with the allowlist this ADR specified.
It runs in `:shared:jvmTest`, which passes."""),

    '2026-09-30-god-vm-decomposition': ('accepted', """Resolved 2026-10-05: the extraction landed.

Verified: `DefaultSearchQueryResolver` no longer exists; search query resolution is now
`feature/search/query/SearchQueryResolver.kt`, declared as interfaces (`SearchQueryResolver`,
`TagLookup`, `ProjectLookup`) with the lookup collaborators split out rather than resolved
inside a single VM. `AgendaViewModel.kt` is 129 lines, consistent with the coordinator
shape this ADR set as the target."""),

    '2026-09-28-setup-hooks-broken-githooks-path': ('accepted', """Resolved 2026-10-05: the recipe is fixed and the existence check is load-bearing.

Verified: `justfile:89-135` (`setup-hooks`) now resolves the main checkout from
`git rev-parse --git-dir`, carries an explicit comment that pointing `core.hooksPath` at a
missing directory does not error but silently runs no hooks, and therefore performs an
existence check before setting it. It also propagates `core.hooksPath` to every registered
worktree, which is the case the ADR identified as silently unhooked. `.githooks/` exists
in this checkout."""),

    '2026-09-30-dispatcher-listviewmodel-cost': ('accepted', """Resolved 2026-10-05: superseded by the measurement that answered it.

This ADR deferred "MR-6 Architectural Polish" items on Dispatcher cost in ListViewModel.
The question it deferred on — what the per-item dispatch actually costs — was answered by
the later performance work, and the two detekt rules that now encode the answer
(`no-direct-dispatchers`, `no-op-update-state`) were given config blocks on 2026-10-05 and
now actually execute (previously implemented but dormant, never having run). `:shared:detekt`
passes with 0 findings, so the remaining Dispatcher use is the single whitelisted case in
`core/log/FileLogWriter.kt`. Nothing is left to decide here; the guardrail is enforced."""),

    # ── Genuinely deferred: the decision itself was to postpone, with a trigger ────
    '2026-09-08-instant-migration': ('deferred', """Confirmed deferred 2026-10-05; revisit trigger recorded.

Verified the work is still outstanding rather than quietly done: `kotlin.time.Instant` is
still imported by 62 files under `shared/src`. Scope is unchanged from the original
analysis (~30 files to update, with the scope-creep risk the ADR cited). Revisit when the
`kotlin.time.Clock` migration is picked up as its own change — the two are the same work
and should not be split."""),

    '2026-10-03-android-shutdown-log-tail-lost': ('deferred', """Confirmed deferred 2026-10-05; revisit trigger recorded.

Verified still outstanding: `FileLogWriter.beginShutdown` is defined
(`core/log/FileLogWriter.kt:78`) and is called on JVM via a shutdown hook
(`core/log/LogBootstrap.kt:14`), but no Android-side caller exists — `beginShutdown`
appears in no `androidApp` source file. The log tail can therefore still be lost on Android.
Revisit when log export lands, since the fix depends on the same wiring
(`LogBundleExporter` injection ordering). Tracked in `deferred-backlog.md`."""),

    '2026-10-03-log-writer-redaction-gaps': ('deferred', """Confirmed deferred 2026-10-05; revisit trigger recorded.

Verified still outstanding: `core/log/FileLogWriter.kt` contains no redaction call. The
cause chain is unfixed and tag redaction is still absent. Revisit when a credential-bearing
value is first observed reaching the log (the trigger this ADR named), or as part of the
log-export change, which will make the redaction gap externally visible."""),

    '2026-09-29-remaining-problem-areas-after-maestro-mr': ('deferred', """Confirmed deferred 2026-10-05.

This ADR deferred 10 follow-up items found by the Maestro MR, each self-contained and
non-blocking. Verified: none of them became prerequisites of later work, and the ordering
the ADR proposed (item 9 first, because it "returns the docs-audit signal") is now moot —
docs-audit is not merely warning again as of 2026-10-05, it is blocking end to end. The
remaining items stay tracked here rather than in `deferred-backlog.md`, because they are
ordered and reasoned, which a flat backlog list cannot carry."""),
}

# Findings/retro documents: not decisions, so they leave the decision corpus entirely.
# Their content is inventory, not rationale, and nothing will "migrate" it into a spec.
ARCHIVE: dict[str, str] = {
    '2026-09-25-remaining-test-debt': 'test-debt inventory (post suite-acceleration audit)',
    '2026-09-26-agenda-clean-architecture-r22': 'R22 agenda refactor inventory',
    '2026-09-26-notes-clean-architecture-r21': 'R21 notes refactor inventory',
    '2026-09-26-deferred-r24-r30': 'R24 profile subsystem inventory',
    '2026-09-26-deferred-r25-r30': 'R25-R30 deferred item inventory',
    '2026-09-29-check-tags-sh-allow-patterns-dead-code': 'dead-code allow-pattern inventory',
    '2026-09-30-post-epic-critical-fixes-and-backlog': 'post-epic findings list',
    '2026-09-30-post-mr-1-findings': 'post-MR-1 findings list',
    '2026-09-30-post-mr-2-findings': 'post-MR-2 findings list',
    '2026-09-30-post-mr-3-findings': 'post-MR-3 findings list',
    '2026-09-30-post-mr-4-findings': 'post-MR-4 findings list',
    '2026-09-30-post-mr-5-findings': 'post-MR-5 findings list',
    '2026-09-30-post-mr-6-final-triage': 'post-MR-6 triage list',
    '2026-10-01-remaining-tech-debt': 'post-v4 tech-debt inventory',
}

ALL = set(RESOLUTIONS) | set(ARCHIVE)  # provenance: every slug this migration touched


def frontmatter_end(lines: list[str]) -> int | None:
    if not lines or lines[0].strip() != '---':
        return None
    return next((i for i, l in enumerate(lines[1:], 1) if l.strip() == '---'), None)


def read_status(text: str) -> str:
    """`status:` from the file's own frontmatter, normalized. '' when absent."""
    lines = text.splitlines()
    end = frontmatter_end(lines)
    if end is None:
        return ''
    for line in lines[1:end]:
        if line.startswith('status:'):
            return line.split(':', 1)[1].strip().strip('[]"\'').lower()
    return ''


def main() -> None:
    """Report on the applied migration. Writes nothing (#518).

    This used to be the third writer of ADR frontmatter: `set_status()` did regex
    surgery on `^status:` with a vocabulary of its own, in a file whose `--apply`
    path nobody had run since 2026-10-05. Three writers of one contract meant three
    places for a rule to drift, and they had — see `normalize-adr-frontmatter.py`,
    whose key allowlist rejected `review-by` because `updateAdr` had started emitting
    it and the normalizer was never told.

    The migration is finished, so the remaining question is not "what would it do"
    but "is it still applied". That is a check, not a write, and the difference is
    the whole point: a script that can still relabel a decision is a fourth thing
    that can disagree with the writer about what a status means.

    Status *drift* is reported, not failed on. An ADR resolved to `accepted` on
    2026-10-05 may legitimately be `superseded` today; failing on that would make
    this script a gate against history, which is the exact failure mode the gate
    registry forbids. What is failed on is a migration that did not finish: a slug
    that is nowhere, or an archived file still sitting in the decision corpus.
    """
    resolved = archived = drifted = incomplete = 0
    missing: list[str] = []

    for slug, (status, note) in RESOLUTIONS.items():
        path = DECISIONS / f'{slug}.md'
        if not path.exists():
            missing.append(slug)
            incomplete += 1
            continue
        text = path.read_text()
        resolved += 1
        if f'## Resolution ({status})' not in text:
            print(f'  note missing: {slug} has no "## Resolution ({status})" section')
            incomplete += 1
        if read_status(text) != status:
            print(f'  moved on since: {slug} is now "{read_status(text) or "<none>"}" '
                  f'(resolved to "{status}" on 2026-10-05) — expected, not a failure')
            drifted += 1
        del note  # the evidence text stays in the ADR body; here it is only provenance

    archive_dir = DECISIONS / 'archive'
    for slug, why in ARCHIVE.items():
        archived += 1
        if not (archive_dir / f'{slug}.md').exists():
            print(f'  NOT archived: {slug} ({why}) — still in the decision corpus? '
                  f'{"yes" if (DECISIONS / f"{slug}.md").exists() else "no"}')
            incomplete += 1
        elif (DECISIONS / f'{slug}.md').exists():
            print(f'  duplicated: {slug} exists both at the top level and in archive/')
            incomplete += 1

    print(f'migration of 2026-10-05: {resolved} resolved, {archived} archived, '
          f'{drifted} legitimately moved on since, {incomplete} incomplete')
    if missing:
        print(f'  absent entirely: {sorted(missing)}')
    if incomplete:
        sys.exit(1)


if __name__ == '__main__':
    main()
