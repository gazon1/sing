#!/usr/bin/env python3
"""Report line and branch coverage for the custom detekt rules, worst-covered first.

Added 2026-10-05. The rule-coverage debt for :detekt-rules was tracked as prose in
deferred-backlog.md, which nothing could verify or update. With kover on the module
(see detekt-rules/build.gradle.kts) it is a number, and the actionable part is *which
rule* is uncovered — a 0%-covered rule is one nobody has ever exercised, which is how
two no-op rules survived here.

Branch was added 2026-10-15, the day the number became a floor:
`DETEKT_RULE_BRANCH_FLOOR` and `koverVerify` measure BRANCH, so a report that prints
only lines describes a different quantity than the gate enforces — and the named list
the floor's comment quotes would have had no generator at all, which is how such a
list goes stale while staying plausible. The branch total here is read from the same
XML `koverLog` prints from and must show the same digits: a second, quieter claim
about coverage is exactly what "one source" exists to prevent.

Run: `just tkr` (generates the report, then calls this).
"""

from __future__ import annotations

import pathlib
import sys
import xml.etree.ElementTree as ET

REPORT = pathlib.Path('detekt-rules/build/reports/kover/report.xml')

#: How many of the worst rules the branch section prints — the same head the
#: floor's comment quotes as a snapshot.
WORST = 10


def main() -> int:
    if not REPORT.is_file():
        print(f'{REPORT} not found — run `./gradlew :detekt-rules:koverXmlReport` first')
        return 1
    root = ET.parse(REPORT).getroot()
    report_counters = {
        c.get('type'): (int(c.get('covered')), int(c.get('missed')))
        for c in root.findall('counter')
    }

    covered = missed = 0
    rows: list[tuple[int, int, str, bool]] = []
    branch_rows: list[tuple[int, int, str, bool]] = []
    for cls in root.iter('class'):
        counters = {
            c.get('type'): (int(c.get('covered')), int(c.get('missed')))
            for c in cls.findall('counter')
        }
        name = cls.get('name', '').split('/')[-1]
        # Synthetic classes — companion-object initialisers and inline lambdas — show 0%
        # even when the enclosing rule is well covered. Counting them would cry wolf and
        # train the reader to ignore the report, so they are reported separately.
        synthetic = '$' in name
        if 'LINE' in counters:
            cov, mis = counters['LINE']
            covered += cov
            missed += mis
            rows.append((mis, cov, name, synthetic))
        if 'BRANCH' in counters:
            bcov, bmis = counters['BRANCH']
            branch_rows.append((bmis, bcov, name, synthetic))

    total = covered + missed
    if total == 0:
        print('no line data in the report')
        return 1

    print(f'\nline coverage: {covered}/{total} = {100.0 * covered / total:.1f}%')
    print('\nworst-covered classes (0% = a rule nobody exercises):')
    for mis, cov, name, synthetic in sorted(rows, reverse=True)[:10]:
        marker = '  <-- RULE NEVER EXERCISED' if mis and cov == 0 and not synthetic else ''
        print(f'  {mis:4d} missed  {name}{marker}')

    never = [n for mis, cov, n, syn in rows if mis and cov == 0 and not syn]
    if never:
        print(f'\n{len(never)} rule(s) whose detection logic has never run:')
        for name in sorted(never):
            print(f'  {name}')
    else:
        print('\nEvery rule has at least some coverage.')

    synth = [n for mis, cov, n, syn in rows if mis and cov == 0 and syn]
    if synth:
        print(f'({len(synth)} synthetic helper class(es) at 0% — companion initialisers '
              'and inline lambdas; not rules, and not counted above.)')

    return report_branch_coverage(report_counters, branch_rows)


def report_branch_coverage(
    report_counters: dict[str | None, tuple[int, int]],
    branch_rows: list[tuple[int, int, str, bool]],
) -> int:
    """Print the branch section: the floor's quantity, and the list behind it.

    The total comes from the report-level counters — the same numbers `koverLog`
    prints and `koverVerify` compares against `DETEKT_RULE_BRANCH_FLOOR`. The list
    is worst-first by missed branches over rule classes, because a percentage with
    no rule name attached is not actionable; that head of the list is what the
    constant's comment quotes, with the date it was measured.
    """
    if 'BRANCH' in report_counters:
        bcov, bmis = report_counters['BRANCH']
    else:
        bcov = sum(cov for _mis, cov, _n, _s in branch_rows)
        bmis = sum(mis for mis, _cov, _n, _s in branch_rows)

    btotal = bcov + bmis
    if btotal == 0:
        print('\nno branch data in the report')
        return 1

    print(f'\nbranch coverage: {bcov}/{btotal} = {100.0 * bcov / btotal:.1f}%  '
          '(the quantity koverVerify enforces via DETEKT_RULE_BRANCH_FLOOR)')

    rules = [(mis, cov, name) for mis, cov, name, syn in branch_rows
             if not syn and mis]
    top = sorted(rules, reverse=True)[:WORST]
    print('\nworst-covered rules by missed branch (the list the floor comment snapshots):')
    for mis, cov, name in top:
        marker = '  <-- RULE NEVER EXERCISED' if cov == 0 else ''
        print(f'  {mis:4d} missed / {mis + cov:4d} ({100.0 * cov / (mis + cov):5.1f}%)  {name}{marker}')
    if top:
        print('top-{} compact: {}'.format(
            len(top), ', '.join(f'{name} ({mis})' for mis, _cov, name in top)))

    all_rules = [(mis, cov, name) for mis, cov, name, syn in branch_rows if not syn]
    with_gap = sum(1 for mis, _cov, _name in all_rules if mis)
    print(f'{with_gap} of {len(all_rules)} rule classes still have at least one missed branch.')

    synth_gap = sum(1 for mis, _cov, _n, syn in branch_rows if syn and mis)
    if synth_gap:
        print(f'({synth_gap} synthetic helper class(es) with missed branches — companion '
              'initialisers and inline lambdas; counted in the total, not listed above.)')
    return 0


if __name__ == '__main__':
    sys.exit(main())
