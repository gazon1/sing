#!/usr/bin/env python3
"""Report line coverage for the custom detekt rules, worst-covered first.

Added 2026-10-05. The rule-coverage debt for :detekt-rules was tracked as prose in
deferred-backlog.md, which nothing could verify or update. With kover on the module
(see detekt-rules/build.gradle.kts) it is a number, and the actionable part is *which
rule* is uncovered — a 0%-covered rule is one nobody has ever exercised, which is how
two no-op rules survived here.

Run: `just tkr` (generates the report, then calls this).
"""

from __future__ import annotations

import pathlib
import sys
import xml.etree.ElementTree as ET

REPORT = pathlib.Path('detekt-rules/build/reports/kover/report.xml')


def main() -> int:
    if not REPORT.is_file():
        print(f'{REPORT} not found — run `./gradlew :detekt-rules:koverXmlReport` first')
        return 1
    root = ET.parse(REPORT).getroot()

    covered = missed = 0
    rows: list[tuple[int, int, str, bool]] = []
    for cls in root.iter('class'):
        counters = {
            c.get('type'): (int(c.get('covered')), int(c.get('missed')))
            for c in cls.findall('counter')
        }
        if 'LINE' not in counters:
            continue
        cov, mis = counters['LINE']
        name = cls.get('name', '').split('/')[-1]
        # Synthetic classes — companion-object initialisers and inline lambdas — show 0%
        # even when the enclosing rule is well covered. Counting them would cry wolf and
        # train the reader to ignore the report, so they are reported separately.
        synthetic = '$' in name
        covered += cov
        missed += mis
        rows.append((mis, cov, name, synthetic))

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
    return 0


if __name__ == '__main__':
    sys.exit(main())
