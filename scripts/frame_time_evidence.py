#!/usr/bin/env python3
"""The rule of a complete measurement, in one place.

The report of the measurement is written while the measurement is still
going: a run that dies on the fourth scenario still leaves the first three
measured, and that partial report is worth keeping. But it is not a
baseline. A scenario that has two runs out of the five that were asked for
says nothing about the three that are missing, and a table that prints its
median as if it were the median of the whole scenario is a lie in a table.

The publishers and the table of the document read the same report and have
to refuse the same report for the same reason, so the rule lives here and
not in one of them.

## The set of scenarios is written down here, not taken from the report

A report says what it measured. It cannot say what it did not measure, and
the list it carries - the one the measurement was asked for - is written by
the very command that ran it. So a run of a single scenario produces a
report that declares one planned scenario, measures it perfectly, and looks
complete to every reader that trusts the report's own list.

The baseline of the project is a fixed set of screens, and the set is
written down here. A report that does not hold the whole set is not a
baseline of the project, and the publishers refuse it.
"""

from __future__ import annotations

# The screens the Android baseline measures. Every one of them has to be in
# the report: a baseline that holds four of them is a baseline of four.
REQUIRED_ANDROID_SCENARIOS = (
    "agenda-scroll",
    "project-tasks-scroll",
    "project-detail-scroll",
    "task-detail-scroll",
    "calendar-periods",
)

# The screens the desktop baseline measures, in the order they are published.
REQUIRED_DESKTOP_SCENARIOS = (
    "agenda-upcoming-scroll",
    "projects-list-scroll",
    "project-detail-open",
    "task-detail-open",
    "calendar-periods",
)


def expected_runs(report: dict) -> int | None:
    """How many measured runs a scenario of this report was supposed to have."""
    runs = (report.get("method") or {}).get("measuredRuns")
    return runs if isinstance(runs, int) and runs > 0 else None


def planned_scenarios(report: dict) -> list[str] | None:
    """The scenarios the measurement was about, or None when it does not say.

    A report that does not name its scenarios cannot show that none of them
    was left out: two measured scenarios out of five look exactly like a
    complete measurement of two scenarios.
    """
    planned = (report.get("method") or {}).get("plannedScenarios")
    if not isinstance(planned, list) or not planned:
        return None
    return [str(name) for name in planned]


def expected_scenarios(report: dict) -> list[str]:
    """The scenarios a complete report of this kind has to hold.

    The required set comes first and is written down in the module: the
    report's own list of planned scenarios is added to it, because a scenario
    the run promised and did not measure is missing too. The required set is
    not taken from the report - a report that measured one scenario of five
    says only what it measured.
    """
    names = list(REQUIRED_ANDROID_SCENARIOS)
    for name in planned_scenarios(report) or []:
        if name not in names:
            names.append(name)
    return names


def missing_scenarios(report: dict) -> list[str]:
    """The scenarios a complete report has to hold and does not have at all."""
    measured = report.get("scenarios") or {}
    return [name for name in expected_scenarios(report) if name not in measured]


def incomplete_scenarios(report: dict) -> list[str]:
    """The scenarios that do not have all their measured runs."""
    scenarios = report.get("scenarios") or {}
    wanted = expected_runs(report)
    names = sorted(scenarios)
    if wanted is None:
        # The report does not say how many runs it wanted, so nothing in it
        # can be called complete.
        return sorted(names)
    return sorted(
        name
        for name in names
        if len((scenarios.get(name) or {}).get("samples") or []) != wanted
    )


def mixed_build_scenarios(report: dict) -> list[str]:
    """The scenarios whose runs were not all made on the same build."""
    mixed = []
    for name, scenario in (report.get("scenarios") or {}).items():
        builds = {sample.get("appVersionName") for sample in scenario.get("samples") or []}
        builds.discard(None)
        if len(builds) > 1:
            mixed.append(name)
    return sorted(mixed)


def check_report_is_complete(report: dict) -> list[str]:
    """What is wrong with the report, as a list of sentences.

    An empty list means the report is a measurement that can be published.
    """
    problems: list[str] = []
    if not report.get("scenarios"):
        problems.append("the report has no scenarios at all")
    if planned_scenarios(report) is None:
        problems.append(
            "the report does not say which scenarios it was about, so it cannot "
            "show that none of them was left out"
        )
    missing = missing_scenarios(report)
    if missing:
        problems.append(
            f"the scenarios {', '.join(missing)} were not measured at all - the baseline "
            "is the whole set of the screens the project measures, not the set the run "
            "happened to pick"
        )
    incomplete = incomplete_scenarios(report)
    if incomplete:
        problems.append(
            f"the scenarios {', '.join(incomplete)} do not have all their runs "
            f"({expected_runs(report) or 'an unknown number of'} runs were asked for)"
        )
    mixed = mixed_build_scenarios(report)
    if mixed:
        problems.append(f"the scenarios {', '.join(mixed)} were measured on more than one build")
    return problems
